package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Metal-owned, stage-scoped implementation of Iris shader-pack custom textures. */
@Environment(EnvType.CLIENT)
final class IrisMetalCustomTextures implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_COPY_SRC;

    private final MetalDevice device;
    private final EnumMap<TextureStage, Map<String, CustomTextureData>> definitions;
    /** Global raw textures: the {@code customtexN} names programs are patched to sample. */
    private final Map<String, CustomTextureData> irisDefinitions;
    private final Map<Key, OwnedBinding> loaded = new HashMap<>();
    private boolean closed;

    IrisMetalCustomTextures(final MetalDevice device, final ShaderPack pack) {
        this(
                device,
                Objects.requireNonNull(pack, "pack").getCustomTextureDataMap(),
                pack.getIrisCustomTextureDataMap()
        );
    }

    /** Package-private map seam keeps focused tests independent of a complete shader-pack parse. */
    IrisMetalCustomTextures(
            final MetalDevice device,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> definitions
    ) {
        this(device, definitions, Map.of());
    }

    /**
     * Full seam: stage-scoped definitions plus the global {@code customtexN}
     * raw-texture map (upstream {@code ShaderPack#getIrisCustomTextureDataMap}).
     * Raw directives are renamed by upstream's {@code TextureTransformer}
     * ({@code colortex6} to {@code customtex0}) and resolved from the global
     * map on every stage, exactly like {@code IrisSamplers.addCustomTextures}.
     */
    IrisMetalCustomTextures(
            final MetalDevice device,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> definitions,
            final Map<String, CustomTextureData> irisDefinitions
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.definitions = copyDefinitions(Objects.requireNonNull(definitions, "definitions"));
        Objects.requireNonNull(irisDefinitions, "irisDefinitions");
        LinkedHashMap<String, CustomTextureData> globals = new LinkedHashMap<>();
        irisDefinitions.forEach((name, data) -> globals.put(
                Objects.requireNonNull(name, "global custom texture sampler"),
                data
        ));
        this.irisDefinitions = Collections.unmodifiableMap(globals);
    }

    /**
     * Resolves the first stage-local sampler alias exactly as Iris's custom-texture interceptor does.
     * Callers must ask this layer before standard samplers so a matching directive takes precedence.
     */
    synchronized MetalRenderPass.@Nullable TextureViewAndSampler resolve(
            final TextureStage stage,
            final String... samplerNames
    ) {
        ensureOpen();
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(samplerNames, "samplerNames");
        Map<String, CustomTextureData> stageDefinitions = this.definitions.get(stage);
        for (String samplerName : samplerNames) {
            Objects.requireNonNull(samplerName, "samplerName");
            boolean stageHit = stageDefinitions != null && stageDefinitions.containsKey(samplerName);
            if (stageHit) {
                return bindingFor(stage, samplerName, stageDefinitions.get(samplerName)).binding();
            }
            CustomTextureData global = this.irisDefinitions.get(samplerName);
            if (global != null) {
                return bindingFor(null, samplerName, global).binding();
            }
        }
        return null;
    }

    /** Returns the stage override when present, otherwise the caller's standard binding. */
    synchronized MetalRenderPass.@Nullable TextureViewAndSampler overrideOrDefault(
            final TextureStage stage,
            final MetalRenderPass.@Nullable TextureViewAndSampler standard,
            final String... samplerNames
    ) {
        MetalRenderPass.TextureViewAndSampler override = resolve(stage, samplerNames);
        return override == null ? standard : override;
    }

    synchronized boolean hasOverride(final TextureStage stage, final String samplerName) {
        ensureOpen();
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(samplerName, "samplerName");
        Map<String, CustomTextureData> stageDefinitions = this.definitions.get(stage);
        return (stageDefinitions != null && stageDefinitions.containsKey(samplerName))
                || this.irisDefinitions.containsKey(samplerName);
    }

    /**
     * Applies the custom-texture precedence to a name-based sampler fallback:
     * a custom stage texture always beats the standard/legacy binding, and a
     * miss defers to the fallback untouched. Upstream implements the same
     * precedence in {@code ProgramSamplers.CustomTextureSamplerInterceptor},
     * which substitutes the override for every {@code addDynamicSampler} call
     * whose name matches, before the standard binding is recorded.
     */
    static MetalRenderPass.@Nullable TextureViewAndSampler overrideFirst(
            final MetalRenderPass.@Nullable TextureViewAndSampler custom,
            final Supplier<MetalRenderPass.@Nullable TextureViewAndSampler> fallback
    ) {
        Objects.requireNonNull(fallback, "fallback");
        return custom != null ? custom : fallback.get();
    }

    /**
     * Materializes every declared custom texture before any render encoder is
     * live: PNGs are decoded/uploaded and {@code RawData3D} volumes uploaded
     * here. {@code ResourceData} entries only build their identifier holder;
     * their TextureManager lookup happens at binding time (upstream
     * {@code CustomTextureManager} re-queries it on every binding), so a world
     * load never fails because a resource is not initialized yet.
     */
    synchronized void prewarmAll() {
        ensureOpen();
        for (Map.Entry<TextureStage, Map<String, CustomTextureData>> stage : this.definitions.entrySet()) {
            for (Map.Entry<String, CustomTextureData> entry : stage.getValue().entrySet()) {
                bindingFor(stage.getKey(), entry.getKey(), entry.getValue());
            }
        }
        this.irisDefinitions.forEach((name, data) -> bindingFor(null, name, data));
    }

    /** Returns the cached (or newly created) binding holder for one definition. */
    private OwnedBinding bindingFor(
            final @Nullable TextureStage stage,
            final String samplerName,
            final CustomTextureData data
    ) {
        Key key = new Key(stage, samplerName);
        OwnedBinding texture = this.loaded.get(key);
        if (texture == null) {
            texture = create(stage, samplerName, data);
            this.loaded.put(key, texture);
        }
        return texture;
    }

    /**
     * Builds the owned binding for one declared entry.
     *
     * <p>Supported: {@link CustomTextureData.PngData} (decoded to RGBA8),
     * {@link CustomTextureData.RawData3D} (uploaded as a 3D texture via
     * {@link IrisMetalTextureFormats}), and {@link CustomTextureData.ResourceData}
     * (lazy reference to a {@code TextureManager} texture). The remaining Iris
     * types (RawData1D/2D/Rect, LightmapMarker) still fail with the type name
     * until they are ported.
     */
    private OwnedBinding create(
            final @Nullable TextureStage stage,
            final String samplerName,
            final @Nullable CustomTextureData data
    ) {
        if (data instanceof CustomTextureData.PngData png) {
            return createPng(stage, samplerName, png);
        }
        if (data instanceof CustomTextureData.RawData3D raw3D) {
            return createRaw3D(stage, samplerName, raw3D);
        }
        if (data instanceof CustomTextureData.ResourceData resourceData) {
            return new ResourceBinding(resourceData.getNamespace(), resourceData.getLocation());
        }

        String type = data == null ? "null" : data.getClass().getSimpleName();
        throw new UnsupportedOperationException(
                "Unsupported Iris custom texture on Metal: stage=" + stage
                        + ", sampler=" + samplerName + ", type=" + type
        );
    }

    private OwnedBinding createPng(
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData.PngData png
    ) {
        NativeImage image;
        try {
            image = NativeImage.read(png.getContent());
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Failed to decode Iris custom texture PNG: stage=" + stage
                            + ", sampler=" + samplerName + ", type=PngData",
                    exception
            );
        }

        MetalGpuTexture texture = null;
        MetalGpuTextureView view = null;
        MetalGpuSampler sampler = null;
        try (image) {
            texture = (MetalGpuTexture) this.device.createTexture(
                    label(stage, samplerName),
                    USAGE,
                    GpuFormat.RGBA8_UNORM,
                    image.getWidth(),
                    image.getHeight(),
                    1,
                    1
            );
            view = (MetalGpuTextureView) this.device.createTextureView(texture);
            sampler = samplerFor(png.getFilteringData());

            ByteBuffer pixels = image.getPixelBytes().duplicate();
            pixels.position(0);
            this.device.createCommandEncoder().writeToTexture(
                    texture,
                    pixels,
                    0,
                    0,
                    0,
                    0,
                    image.getWidth(),
                    image.getHeight()
            );
            return new OwnedTexture(texture, view, sampler);
        } catch (RuntimeException | Error failure) {
            closePartial(texture, view, sampler);
            throw failure;
        }
    }

    /**
     * Uploads a declared {@code RawData3D} volume (Mellow's 64³ R8
     * {@code worley_perlin.bin}) as a real Metal 3D texture. The internal
     * format is lowered through {@link IrisMetalTextureFormats}; filtering
     * follows the same blur/clamp mapping as PNG data.
     */
    private OwnedBinding createRaw3D(
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData.RawData3D raw
    ) {
        GpuFormat format = IrisMetalTextureFormats.fromInternalName(raw.getInternalFormat().name());
        int width = raw.getSizeX();
        int height = raw.getSizeY();
        int depth = raw.getSizeZ();

        MetalGpuTexture texture = null;
        MetalGpuTextureView view = null;
        MetalGpuSampler sampler = null;
        try {
            texture = new MetalGpuTexture(
                    this.device,
                    USAGE,
                    label(stage, samplerName),
                    format,
                    width,
                    height,
                    depth,
                    1,
                    true
            );
            view = (MetalGpuTextureView) this.device.createTextureView(texture);
            sampler = samplerFor(raw.getFilteringData());

            byte[] content = raw.getContent();
            ByteBuffer pixels = ByteBuffer.allocateDirect(content.length);
            pixels.put(content);
            pixels.flip();
            this.device.createCommandEncoder().writeToTexture(
                    texture,
                    pixels,
                    0,
                    0,
                    0,
                    0,
                    width,
                    height
            );
            return new OwnedTexture(texture, view, sampler);
        } catch (RuntimeException | Error failure) {
            closePartial(texture, view, sampler);
            throw failure;
        }
    }

    private MetalGpuSampler samplerFor(final TextureFilteringData filtering) {
        boolean clamp = filtering.shouldClamp();
        boolean blur = filtering.shouldBlur();
        AddressMode addressMode = clamp ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
        FilterMode filterMode = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
        return new MetalGpuSampler(
                this.device,
                addressMode,
                addressMode,
                filterMode,
                filterMode,
                1,
                OptionalDouble.of(0.0)
        );
    }

    private static String label(final @Nullable TextureStage stage, final String samplerName) {
        String scope = stage == null ? "global" : stage.name().toLowerCase(Locale.ROOT);
        return "metallum:iris_custom/" + scope + "/" + samplerName;
    }

    private static EnumMap<TextureStage, Map<String, CustomTextureData>> copyDefinitions(
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> source
    ) {
        EnumMap<TextureStage, Map<String, CustomTextureData>> copy = new EnumMap<>(TextureStage.class);
        source.forEach((stage, entries) -> {
            Objects.requireNonNull(stage, "custom texture stage");
            Objects.requireNonNull(entries, "custom textures for stage " + stage);
            LinkedHashMap<String, CustomTextureData> stageCopy = new LinkedHashMap<>();
            entries.forEach((name, data) -> stageCopy.put(
                    Objects.requireNonNull(name, "custom texture sampler for stage " + stage),
                    data
            ));
            copy.put(stage, Collections.unmodifiableMap(stageCopy));
        });
        return copy;
    }

    private static void closePartial(
            final @Nullable MetalGpuTexture texture,
            final @Nullable MetalGpuTextureView view,
            final @Nullable MetalGpuSampler sampler
    ) {
        if (view != null) {
            view.close();
        }
        if (texture != null) {
            texture.close();
        }
        if (sampler != null) {
            sampler.close();
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris Metal custom textures are closed");
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.loaded.values().forEach(OwnedBinding::close);
        this.loaded.clear();
    }

    private record Key(@Nullable TextureStage stage, String samplerName) {
    }

    /** One cached definition entry: either ported GPU data or a lazy resource. */
    private interface OwnedBinding extends AutoCloseable {
        /** Current binding, or {@code null} when this entry cannot bind right now. */
        MetalRenderPass.@Nullable TextureViewAndSampler binding();

        @Override
        void close();
    }

    /** A Metal-owned texture created and uploaded by this class. */
    private static final class OwnedTexture implements OwnedBinding {
        private final MetalGpuTexture texture;
        private final MetalGpuTextureView view;
        private final MetalGpuSampler sampler;

        private OwnedTexture(
                final MetalGpuTexture texture,
                final MetalGpuTextureView view,
                final MetalGpuSampler sampler
        ) {
            this.texture = texture;
            this.view = view;
            this.sampler = sampler;
        }

        @Override
        public MetalRenderPass.TextureViewAndSampler binding() {
            return new MetalRenderPass.TextureViewAndSampler(this.view, this.sampler);
        }

        @Override
        public void close() {
            this.view.close();
            this.texture.close();
            this.sampler.close();
        }
    }

    /**
     * Lazy reference to a vanilla {@code TextureManager} texture.
     *
     * <p>Upstream {@code CustomTextureManager} (pin 20e226b, lines 125-150)
     * re-queries the manager on every binding because resource reloads replace
     * the {@code AbstractTexture}; nothing but the identifier is cached here.
     * The engine already resolved the texture into a Metal view + engine-default
     * sampler, so both are used verbatim (atlas filtering for atlas textures).
     * Before the texture is uploaded (or outside a client session) the binding
     * is {@code null}, which lets prewarm complete without a loaded client and
     * lets the caller keep its standard sampler until the next draw.
     */
    private static final class ResourceBinding implements OwnedBinding {
        private final Identifier location;

        private ResourceBinding(final String namespace, final String location) {
            this.location = Identifier.fromNamespaceAndPath(namespace, location);
        }

        @Override
        public MetalRenderPass.@Nullable TextureViewAndSampler binding() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return null;
            }
            AbstractTexture texture = minecraft.getTextureManager().getTexture(this.location);
            GpuTextureView view;
            GpuSampler sampler;
            try {
                view = texture.getTextureView();
                sampler = texture.getSampler();
            } catch (IllegalStateException notUploadedYet) {
                // AbstractTexture throws for a view requested before apply();
                // treat it exactly like "not ready", never as a hard failure.
                return null;
            }
            if (view == null || sampler == null) {
                return null;
            }
            return new MetalRenderPass.TextureViewAndSampler(view, sampler);
        }

        @Override
        public void close() {
            // The TextureManager owns the AbstractTexture's lifetime.
        }
    }
}
