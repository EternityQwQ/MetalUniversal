package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.Optional;
import java.util.OptionalDouble;

/** Generation-owned shadowtex0/1 and shadowcolor ping-pong resources. */
@Environment(EnvType.CLIENT)
final class IrisMetalShadowTargets implements AutoCloseable {
    private static final int DEPTH_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;

    private final MetalDevice device;
    private final IrisMetalPingPongTargets colorTargets;
    private final MetalGpuTexture[] colorMain;
    private final MetalGpuTexture[] colorAlt;
    private final MetalGpuTextureView[] colorMainViews;
    private final MetalGpuTextureView[] colorAltViews;
    private final MetalGpuSampler[] colorSamplers;
    private final MetalGpuSampler[] depthSamplers;
    private final MetalGpuSampler[] depthCompareSamplers;
    private final boolean[] colorMipmapped;
    private final boolean[] colorClear;
    private final Vector4fc[] colorClearColors;
    private final boolean[] depthMipmapped;
    @Nullable
    private final MetalDepthMipmapGenerator depthMipmapGenerator;
    private MetalGpuTexture shadowDepth;
    private MetalGpuTexture shadowDepthNoTranslucents;
    private MetalGpuTextureView shadowDepthView;
    private MetalGpuTextureView shadowDepthNoTranslucentsView;
    private int resolution;
    private boolean closed;

    IrisMetalShadowTargets(
            final MetalDevice device,
            final GpuFormat[] shadowColorFormats,
            final int resolution,
            final boolean[] nearestColor,
            final boolean[] nearestDepth,
            final boolean[] mipmappedDepth
    ) {
        this(
                device,
                shadowColorFormats,
                resolution,
                nearestColor,
                new boolean[shadowColorFormats.length],
                nearestDepth,
                mipmappedDepth
        );
    }

    IrisMetalShadowTargets(
            final MetalDevice device,
            final GpuFormat[] shadowColorFormats,
            final int resolution,
            final boolean[] nearestColor,
            final boolean[] colorMipmapped,
            final boolean[] nearestDepth,
            final boolean[] mipmappedDepth
    ) {
        this(
                device,
                shadowColorFormats,
                resolution,
                nearestColor,
                colorMipmapped,
                nearestDepth,
                mipmappedDepth,
                allSet(shadowColorFormats.length),
                whiteColors(shadowColorFormats.length)
        );
    }

    IrisMetalShadowTargets(
            final MetalDevice device,
            final GpuFormat[] shadowColorFormats,
            final int resolution,
            final boolean[] nearestColor,
            final boolean[] colorMipmapped,
            final boolean[] nearestDepth,
            final boolean[] mipmappedDepth,
            final boolean[] colorClear,
            final Vector4fc[] colorClearColors
    ) {
        if (nearestColor.length != shadowColorFormats.length
                || colorMipmapped.length != shadowColorFormats.length) {
            throw new IllegalArgumentException(
                    "One color sampling and mipmap mode is required per shadowcolor target"
            );
        }
        if (colorClear.length != shadowColorFormats.length
                || colorClearColors.length != shadowColorFormats.length) {
            throw new IllegalArgumentException(
                    "One clear mode and clear color is required per shadowcolor target"
            );
        }
        if (nearestDepth.length != 2 || mipmappedDepth.length != 2) {
            throw new IllegalArgumentException("Exactly two shadow depth sampling and mipmap modes are required");
        }
        this.device = device;
        this.colorTargets = new IrisMetalPingPongTargets(
                device, "iris-shadowcolor", shadowColorFormats, resolution, resolution,
                mipmappedIndices(colorMipmapped)
        );
        this.colorMain = new MetalGpuTexture[shadowColorFormats.length];
        this.colorAlt = new MetalGpuTexture[shadowColorFormats.length];
        this.colorMainViews = new MetalGpuTextureView[shadowColorFormats.length];
        this.colorAltViews = new MetalGpuTextureView[shadowColorFormats.length];
        refreshColorSides();
        this.colorSamplers = new MetalGpuSampler[shadowColorFormats.length];
        this.colorMipmapped = colorMipmapped.clone();
        this.colorClear = colorClear.clone();
        this.colorClearColors = colorClearColors.clone();
        for (int index = 0; index < colorSamplers.length; index++) {
            colorSamplers[index] = createSampler(nearestColor[index], colorMipmapped[index], false);
        }
        this.depthSamplers = new MetalGpuSampler[2];
        this.depthCompareSamplers = new MetalGpuSampler[2];
        this.depthMipmapped = mipmappedDepth.clone();
        this.depthMipmapGenerator = mipmappedDepth[0] || mipmappedDepth[1]
                ? new MetalDepthMipmapGenerator(device)
                : null;
        for (int index = 0; index < depthSamplers.length; index++) {
            depthSamplers[index] = createSampler(nearestDepth[index], mipmappedDepth[index], false);
            depthCompareSamplers[index] = createSampler(nearestDepth[index], mipmappedDepth[index], true);
        }
        createDepthTextures(resolution);
    }

    private MetalGpuSampler createSampler(
            final boolean nearest,
            final boolean mipmapped,
            final boolean comparison
    ) {
        FilterMode filter = nearest ? FilterMode.NEAREST : FilterMode.LINEAR;
        return new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                filter,
                filter,
                1,
                mipmapped ? OptionalDouble.empty() : OptionalDouble.of(0.0),
                comparison ? MTLCompareFunction.LessEqual : null
        );
    }

    private static java.util.Set<Integer> mipmappedIndices(final boolean[] mipmapped) {
        java.util.Set<Integer> result = new java.util.HashSet<>();
        for (int index = 0; index < mipmapped.length; index++) {
            if (mipmapped[index]) {
                result.add(index);
            }
        }
        return result;
    }

    private static boolean[] allSet(final int length) {
        boolean[] flags = new boolean[length];
        java.util.Arrays.fill(flags, true);
        return flags;
    }

    private static Vector4fc[] whiteColors(final int length) {
        Vector4fc[] colors = new Vector4fc[length];
        java.util.Arrays.fill(colors, new Vector4f(1.0F, 1.0F, 1.0F, 1.0F));
        return colors;
    }

    /**
     * Shadowcolor formats shared by target creation and shadow-program PSO
     * compilation; same source as {@code IrisMetalWorldResources.createShadowTargets}
     * so the caster pass and the compiled pipelines cannot drift.
     */
    static GpuFormat[] colorFormats(final ProgramSet programSet) {
        PackShadowDirectives shadow = programSet.getPackDirectives().getShadowDirectives();
        int targetCount = programSet.getPack().hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR)
                ? PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS
                : PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_OF;
        GpuFormat[] formats = new GpuFormat[targetCount];
        for (int index = 0; index < targetCount; index++) {
            PackShadowDirectives.SamplingSettings settings = shadow.getColorSamplingSettings().get(index);
            if (settings == null) {
                settings = new PackShadowDirectives.SamplingSettings();
            }
            formats[index] = IrisMetalRenderTargetFormats.fromInternalName(settings.getFormat().name());
        }
        return formats;
    }

    private void refreshColorSides() {
        colorTargets.restore(new BitSet());
        for (int index = 0; index < colorTargets.targetCount(); index++) {
            colorMain[index] = colorTargets.readTexture(index);
            colorAlt[index] = colorTargets.writeTexture(index);
            colorMainViews[index] = colorTargets.readView(index);
            colorAltViews[index] = colorTargets.writeView(index);
        }
    }

    private void createDepthTextures(final int newResolution) {
        if (newResolution <= 0) {
            throw new IllegalArgumentException("Shadow resolution must be positive: " + newResolution);
        }
        this.resolution = newResolution;
        this.shadowDepth = (MetalGpuTexture) device.createTexture(
                "iris-shadowtex0", DEPTH_USAGE, GpuFormat.D32_FLOAT, newResolution, newResolution, 1,
                mipLevels(newResolution, depthMipmapped[0])
        );
        this.shadowDepthNoTranslucents = (MetalGpuTexture) device.createTexture(
                "iris-shadowtex1", DEPTH_USAGE, GpuFormat.D32_FLOAT, newResolution, newResolution, 1,
                mipLevels(newResolution, depthMipmapped[1])
        );
        this.shadowDepthView = new MetalGpuTextureView(shadowDepth, 0, shadowDepth.getMipLevels());
        this.shadowDepthNoTranslucentsView = new MetalGpuTextureView(
                shadowDepthNoTranslucents, 0, shadowDepthNoTranslucents.getMipLevels()
        );
    }

    private static int mipLevels(final int extent, final boolean mipmapped) {
        return mipmapped ? 32 - Integer.numberOfLeadingZeros(extent) : 1;
    }

    IrisMetalPingPongTargets colorTargets() {
        ensureOpen();
        return colorTargets;
    }

    MetalGpuTexture shadowDepthTexture() {
        ensureOpen();
        return shadowDepth;
    }

    MetalGpuTexture shadowDepthNoTranslucentsTexture() {
        ensureOpen();
        return shadowDepthNoTranslucents;
    }

    MetalGpuTextureView shadowDepthView() {
        ensureOpen();
        return shadowDepthView;
    }

    MetalGpuTextureView shadowDepthNoTranslucentsView() {
        ensureOpen();
        return shadowDepthNoTranslucentsView;
    }

    MetalGpuSampler depthSampler(final int index, final boolean comparison) {
        ensureOpen();
        checkDepthIndex(index);
        return comparison ? depthCompareSamplers[index] : depthSamplers[index];
    }

    MetalGpuSampler colorSampler(final int index) {
        ensureOpen();
        return colorSamplers[checkColorIndex(index)];
    }

    void resetMipmaps() {
        ensureOpen();
        colorTargets.resetMipmaps();
    }

    void generateColorMipmaps(final MetalCommandEncoder encoder) {
        ensureOpen();
        for (int index = 0; index < colorMipmapped.length; index++) {
            if (!colorMipmapped[index]) {
                continue;
            }
            encoder.generateMipmaps(colorTargets.readTexture(index));
            colorTargets.enableReadMipmaps(index);
        }
    }

    GpuFormat colorFormat(final int index) {
        ensureOpen();
        return colorTargets.format(checkColorIndex(index));
    }

    /** Whether this shadowcolor target should be cleared at the start of the shadow pass. */
    boolean clearsColor(final int index) {
        ensureOpen();
        return this.colorClear[checkColorIndex(index)];
    }

    /** The pack's clear color for this shadowcolor target. */
    Vector4fc colorClearColor(final int index) {
        ensureOpen();
        Vector4fc color = this.colorClearColors[checkColorIndex(index)];
        return color == null ? new Vector4f(1.0F, 1.0F, 1.0F, 1.0F) : color;
    }

    MetalGpuTexture colorTexture(final int index, final BitSet readsFromAlt) {
        ensureOpen();
        validateSnapshot(readsFromAlt);
        int checked = checkColorIndex(index);
        return readsFromAlt.get(checked) ? colorAlt[checked] : colorMain[checked];
    }

    MetalGpuTextureView colorView(final int index, final BitSet readsFromAlt) {
        ensureOpen();
        validateSnapshot(readsFromAlt);
        int checked = checkColorIndex(index);
        return readsFromAlt.get(checked) ? colorAltViews[checked] : colorMainViews[checked];
    }

    int resolution() {
        return resolution;
    }

    /**
     * Copies shadowtex0 into shadowtex1, the pack's "no translucent" depth map.
     * Called after the opaque terrain and entity caster passes at the
     * opaque/translucent boundary, and at frame start (and in the no-caster
     * fallback clear) to seed shadowtex1 with the cleared far value.
     */
    void captureNoTranslucentsDepth(final MetalCommandEncoder encoder) {
        ensureOpen();
        encoder.copyTextureToTexture(
                shadowDepth, shadowDepthNoTranslucents, 0, 0, 0, 0, 0, resolution, resolution, "shadow.depth"
        );
    }

    void generateDepthMipmaps(final MetalCommandEncoder encoder) {
        ensureOpen();
        if (depthMipmapped[0]) {
            depthMipmapGenerator.generate(encoder, shadowDepth);
        }
        if (depthMipmapped[1]) {
            depthMipmapGenerator.generate(encoder, shadowDepthNoTranslucents);
        }
    }

    /**
     * Color-only clear descriptor for one ping-pong side of the requested
     * shadowcolor targets (upstream clears each clear=true target on both
     * sides; the depth attachment is untouched).
     */
    IrisMetalRenderTargets.RenderPassDescriptorWithViews createShadowColorClearDescriptor(
            final String label,
            final int[] drawBuffers,
            final Vector4fc[] clearColors,
            final boolean alt
    ) {
        ensureOpen();
        if (drawBuffers.length == 0 || clearColors.length != drawBuffers.length) {
            throw new IllegalArgumentException(
                    "A shadow color clear requires one clear color per target"
            );
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        MetalGpuTextureView[] views = new MetalGpuTextureView[drawBuffers.length];
        IrisMetalRenderTargets.AttachmentViewOwnership owned =
                new IrisMetalRenderTargets.AttachmentViewOwnership();
        boolean[] written = new boolean[colorTargets.targetCount()];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            int target = validateDrawTarget(drawBuffers[slot], written, "Shadow color clear");
            // Full-range view by default: the same native handle whatever the
            // pass order, so the color clear shares the caster's encoder
            // instead of reopening it (see MetalRenderPolicy).
            MetalGpuTextureView fullView = alt ? colorAltViews[target] : colorMainViews[target];
            MetalGpuTextureView view = IrisMetalRenderTargets.attachmentView(
                    fullView, alt ? colorAlt[target] : colorMain[target]
            );
            owned.add(fullView, view);
            views[slot] = view;
            descriptor.withColorAttachment(view, Optional.of(clearColors[slot]));
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, resolution, resolution));
        return new IrisMetalRenderTargets.RenderPassDescriptorWithViews(
                descriptor, views, owned.toArray()
        );
    }

    IrisMetalRenderTargets.RenderPassDescriptorWithViews createShadowGbufferDescriptor(
            final String label,
            final int[] drawBuffers,
            @Nullable final Vector4fc[] clearColors,
            @Nullable final Double clearDepth
    ) {
        ensureOpen();
        if (clearColors != null && clearColors.length != drawBuffers.length) {
            throw new IllegalArgumentException("Clear color array must match draw buffer count");
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        MetalGpuTextureView[] views = new MetalGpuTextureView[drawBuffers.length + 1];
        IrisMetalRenderTargets.AttachmentViewOwnership owned =
                new IrisMetalRenderTargets.AttachmentViewOwnership();
        boolean[] written = new boolean[colorTargets.targetCount()];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            int target = validateDrawTarget(drawBuffers[slot], written, "Shadow DRAWBUFFERS");
            // Caster draws write the fixed "main" side; a full-range attachment
            // view keeps the encoder alive across consecutive draws.
            MetalGpuTextureView view = IrisMetalRenderTargets.attachmentView(
                    colorMainViews[target], colorMain[target]
            );
            owned.add(colorMainViews[target], view);
            views[slot] = view;
            descriptor.withColorAttachment(
                    view,
                    clearColors == null || clearColors[slot] == null
                            ? Optional.empty()
                            : Optional.of(clearColors[slot])
            );
        }
        MetalGpuTextureView depthView = IrisMetalRenderTargets.attachmentView(shadowDepthView, shadowDepth);
        owned.add(shadowDepthView, depthView);
        views[drawBuffers.length] = depthView;
        descriptor.withDepthAttachment(
                depthView,
                clearDepth == null ? OptionalDouble.empty() : OptionalDouble.of(clearDepth)
        );
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, resolution, resolution));
        return new IrisMetalRenderTargets.RenderPassDescriptorWithViews(
                descriptor, views, owned.toArray()
        );
    }

    IrisMetalRenderTargets.RenderPassDescriptorWithViews createShadowCompositeDescriptor(
            final String label,
            final int[] drawBuffers,
            final BitSet readsFromAlt,
            final int viewportX,
            final int viewportY,
            final int viewportWidth,
            final int viewportHeight
    ) {
        ensureOpen();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("A shadow composite render pass must write at least one target");
        }
        validateSnapshot(readsFromAlt);
        if (viewportX < 0 || viewportY < 0 || viewportWidth <= 0 || viewportHeight <= 0
                || viewportX + viewportWidth > resolution || viewportY + viewportHeight > resolution) {
            throw new IllegalArgumentException(
                    "Shadow composite viewport is outside " + resolution + "x" + resolution + ": "
                            + viewportX + "," + viewportY + " " + viewportWidth + "x" + viewportHeight
            );
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        MetalGpuTextureView[] views = new MetalGpuTextureView[drawBuffers.length];
        IrisMetalRenderTargets.AttachmentViewOwnership owned =
                new IrisMetalRenderTargets.AttachmentViewOwnership();
        boolean[] written = new boolean[colorTargets.targetCount()];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            int target = validateDrawTarget(drawBuffers[slot], written, "Shadow composite DRAWBUFFERS");
            MetalGpuTextureView fullView = readsFromAlt.get(target)
                    ? colorMainViews[target]
                    : colorAltViews[target];
            MetalGpuTextureView view = IrisMetalRenderTargets.attachmentView(
                    fullView, readsFromAlt.get(target) ? colorMain[target] : colorAlt[target]
            );
            owned.add(fullView, view);
            views[slot] = view;
            descriptor.withColorAttachment(view, Optional.empty());
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(
                viewportX, viewportY, viewportWidth, viewportHeight
        ));
        return new IrisMetalRenderTargets.RenderPassDescriptorWithViews(
                descriptor, views, owned.toArray()
        );
    }

    void publishFlipState(final BitSet finalReadsFromAlt) {
        ensureOpen();
        validateSnapshot(finalReadsFromAlt);
        colorTargets.restore(finalReadsFromAlt);
    }

    /**
     * Resizes the shadowcolor ping-pong targets and both depth textures.
     *
     * <p>TODO(M6.4): no production caller. The shadow resolution comes from the
     * pack's {@code shadow.resolution} directive when the generation is built,
     * and the pinned upstream tree has no runtime shadow-resolution override
     * (only shadow distance), so changing shader settings or packs rebuilds the
     * whole generation (a new {@link IrisMetalWorldResources}) instead of
     * calling this. If a runtime resolution override is ever added, it must
     * call this and reset the execution graph's {@code shadowFullClearRequired}
     * (a resize requires clearing every shadowcolor target, as upstream's
     * {@code isFullClearRequired} does).</p>
     */
    void resize(final int newResolution) {
        ensureOpen();
        if (newResolution == resolution) {
            return;
        }
        colorTargets.resize(newResolution, newResolution);
        refreshColorSides();
        releaseDepthTextures();
        createDepthTextures(newResolution);
    }

    private void releaseDepthTextures() {
        if (shadowDepthView != null) {
            shadowDepthView.close();
            shadowDepthView = null;
        }
        if (shadowDepthNoTranslucentsView != null) {
            shadowDepthNoTranslucentsView.close();
            shadowDepthNoTranslucentsView = null;
        }
        if (shadowDepth != null) {
            shadowDepth.close();
            shadowDepth = null;
        }
        if (shadowDepthNoTranslucents != null) {
            shadowDepthNoTranslucents.close();
            shadowDepthNoTranslucents = null;
        }
    }

    private int validateDrawTarget(final int target, final boolean[] written, final String label) {
        int checked = checkColorIndex(target);
        if (written[checked]) {
            throw new IllegalArgumentException(label + " repeats logical target " + checked);
        }
        written[checked] = true;
        return checked;
    }

    private int checkColorIndex(final int index) {
        if (index < 0 || index >= colorTargets.targetCount()) {
            throw new IllegalArgumentException(
                    "Shadow color target out of range: " + index + " (count=" + colorTargets.targetCount() + ")"
            );
        }
        return index;
    }

    private static void checkDepthIndex(final int index) {
        if (index < 0 || index > 1) {
            throw new IllegalArgumentException("Shadow depth target out of range: " + index);
        }
    }

    private void validateSnapshot(final BitSet snapshot) {
        int invalid = snapshot.nextSetBit(colorTargets.targetCount());
        if (invalid >= 0) {
            throw new IllegalArgumentException(
                    "Shadow flip snapshot contains target " + invalid + " outside target set"
            );
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Iris shadow targets are closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        colorTargets.close();
        releaseDepthTextures();
        for (MetalGpuSampler sampler : colorSamplers) {
            sampler.close();
        }
        for (MetalGpuSampler sampler : depthSamplers) {
            sampler.close();
        }
        for (MetalGpuSampler sampler : depthCompareSamplers) {
            sampler.close();
        }
        if (depthMipmapGenerator != null) {
            depthMipmapGenerator.close();
        }
    }
}
