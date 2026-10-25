package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/** Generation-owned SSBOs and Iris custom images used by compute and raster stages. */
@Environment(EnvType.CLIENT)
final class IrisMetalComputeResources implements AutoCloseable {
    private static final int ZERO_CHUNK_BYTES = 1024 * 1024;
    private static final int BUFFER_USAGE = GpuBuffer.USAGE_COPY_SRC
            | GpuBuffer.USAGE_COPY_DST
            | GpuBuffer.USAGE_INDIRECT_PARAMETERS;
    private static final int IMAGE_USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST
            | MetalGpuTexture.USAGE_SHADER_WRITE;

    private final MetalDevice device;
    private final Map<Integer, BuiltShaderStorageInfo> bufferDefinitions = new LinkedHashMap<>();
    private final Map<Integer, MetalGpuBuffer> buffers = new LinkedHashMap<>();
    private final Map<String, OwnedImage> imagesByName = new LinkedHashMap<>();
    private final Map<String, OwnedImage> imagesBySampler = new LinkedHashMap<>();
    private int width;
    private int height;
    private boolean closed;

    IrisMetalComputeResources(
            final MetalDevice device,
            final ShaderPack pack,
            final int width,
            final int height
    ) {
        this.device = Objects.requireNonNull(device, "device");
        Objects.requireNonNull(pack, "pack");
        validateExtent(width, height);
        validatePack(pack);
        this.width = width;
        this.height = height;
        pack.getBufferObjects().forEach((int binding, BuiltShaderStorageInfo info) -> {
            this.bufferDefinitions.put(binding, Objects.requireNonNull(info, "SSBO " + binding));
        });
        try {
            this.bufferDefinitions.forEach((binding, info) ->
                    this.buffers.put(binding, createBuffer(binding, info, width, height))
            );
            for (ImageInformation image : pack.getIrisCustomImages()) {
                addImage(image, width, height);
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Nullable
    GpuBufferSlice storageBuffer(final int binding) {
        ensureOpen();
        MetalGpuBuffer buffer = this.buffers.get(binding);
        return buffer == null ? null : buffer.slice();
    }

    MetalRenderPass.@Nullable TextureViewAndSampler sampledImage(final String samplerName) {
        ensureOpen();
        OwnedImage image = this.imagesBySampler.get(samplerName);
        return image == null ? null : image.sampledBinding();
    }

    @Nullable
    MetalGpuTextureView storageImage(final String imageName) {
        ensureOpen();
        OwnedImage image = this.imagesByName.get(imageName);
        return image == null ? null : image.view;
    }

    void clearForFrame(final MetalCommandEncoder encoder) {
        ensureOpen();
        for (OwnedImage image : this.imagesByName.values()) {
            if (!image.definition.clear()) {
                continue;
            }
            if (image.texture.isTexture3D()) {
                encoder.writeToTexture(
                        image.texture,
                        image.zeroBytes.duplicate(),
                        0,
                        0,
                        0,
                        0,
                        image.width,
                        image.height
                );
            } else {
                encoder.clearColorTexture(image.texture, new Vector4f());
            }
        }
    }

    void resize(final int newWidth, final int newHeight) {
        ensureOpen();
        validateExtent(newWidth, newHeight);
        if (newWidth == this.width && newHeight == this.height) {
            return;
        }
        this.width = newWidth;
        this.height = newHeight;
        for (Map.Entry<Integer, BuiltShaderStorageInfo> entry : this.bufferDefinitions.entrySet()) {
            if (!entry.getValue().relative()) {
                continue;
            }
            MetalGpuBuffer old = this.buffers.put(
                    entry.getKey(), createBuffer(entry.getKey(), entry.getValue(), newWidth, newHeight)
            );
            old.close();
        }
        for (OwnedImage image : this.imagesByName.values().toArray(OwnedImage[]::new)) {
            if (image.definition.isRelative()) {
                replaceImage(image.definition, newWidth, newHeight);
            }
        }
    }

    private MetalGpuBuffer createBuffer(
            final int binding,
            final BuiltShaderStorageInfo info,
            final int width,
            final int height
    ) {
        long size = bufferSize(info, width, height);
        if (size <= 0L) {
            throw new IllegalArgumentException(
                    "Iris SSBO " + binding + " resolved to non-positive size " + size
            );
        }
        MetalGpuBuffer buffer = (MetalGpuBuffer) this.device.createBuffer(
                () -> "metallum:iris_ssbo/" + binding,
                BUFFER_USAGE,
                size
        );
        try {
            zero(buffer);
            byte[] content = info.content();
            if (!info.relative() && content != null && content.length != 0) {
                if (content.length > size) {
                    throw new IllegalArgumentException(
                            "Iris SSBO " + binding + " initial content is " + content.length
                                    + " bytes but allocation is " + size
                    );
                }
                ByteBuffer initial = ByteBuffer.allocateDirect(content.length);
                initial.put(content).flip();
                this.device.createCommandEncoder().writeToBuffer(buffer.slice(0L, content.length), initial);
            }
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
        }
    }

    private void zero(final MetalGpuBuffer buffer) {
        int chunkSize = Math.toIntExact(Math.min(buffer.size(), ZERO_CHUNK_BYTES));
        ByteBuffer zeroes = ByteBuffer.allocateDirect(chunkSize);
        for (long offset = 0L; offset < buffer.size(); offset += chunkSize) {
            int length = Math.toIntExact(Math.min(chunkSize, buffer.size() - offset));
            ByteBuffer chunk = zeroes.duplicate();
            chunk.limit(length);
            this.device.createCommandEncoder().writeToBuffer(buffer.slice(offset, length), chunk);
        }
    }

    static long bufferSize(final BuiltShaderStorageInfo info, final int width, final int height) {
        Objects.requireNonNull(info, "info");
        if (!info.relative()) {
            return info.size();
        }
        long scaledWidth = Math.max(1L, (long) (width * info.scaleX()));
        long scaledHeight = Math.max(1L, (long) (height * info.scaleY()));
        return Math.multiplyExact(Math.multiplyExact(scaledWidth, scaledHeight), info.size());
    }

    private void addImage(final ImageInformation definition, final int width, final int height) {
        Objects.requireNonNull(definition, "custom image");
        String name = Objects.requireNonNull(definition.name(), "custom image name");
        if (this.imagesByName.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate Iris custom image name '" + name + "'");
        }
        String samplerName = definition.samplerName();
        if (samplerName != null && this.imagesBySampler.containsKey(samplerName)) {
            throw new IllegalArgumentException(
                    "Duplicate Iris custom image sampler '" + samplerName + "'"
            );
        }
        OwnedImage image = createImage(definition, width, height);
        this.imagesByName.put(name, image);
        if (samplerName != null) {
            this.imagesBySampler.put(samplerName, image);
        }
    }

    private void replaceImage(final ImageInformation definition, final int width, final int height) {
        OwnedImage replacement = createImage(definition, width, height);
        OwnedImage old = this.imagesByName.put(definition.name(), replacement);
        if (definition.samplerName() != null) {
            this.imagesBySampler.put(definition.samplerName(), replacement);
        }
        if (old == null) {
            throw new IllegalStateException("Relative Iris image disappeared during resize: " + definition.name());
        }
        old.close();
    }

    /** Validates pack declarations without allocating Metal resources. */
    static void validatePack(final ShaderPack pack) {
        Objects.requireNonNull(pack, "pack");
        pack.getBufferObjects().forEach((int binding, BuiltShaderStorageInfo info) -> {
            if (binding < 0) {
                throw new IllegalArgumentException("Iris SSBO binding must be non-negative: " + binding);
            }
            Objects.requireNonNull(info, "SSBO " + binding);
            if (info.size() <= 0L) {
                throw new IllegalArgumentException("Iris SSBO " + binding + " size must be positive");
            }
            if (info.relative()) {
                if (!Float.isFinite(info.scaleX()) || info.scaleX() <= 0.0F
                        || !Float.isFinite(info.scaleY()) || info.scaleY() <= 0.0F) {
                    throw new IllegalArgumentException(
                            "Iris relative SSBO " + binding + " has invalid scale "
                                    + info.scaleX() + 'x' + info.scaleY()
                    );
                }
            } else if (info.content() != null && info.content().length > info.size()) {
                throw new IllegalArgumentException(
                        "Iris SSBO " + binding + " initial content is " + info.content().length
                                + " bytes but allocation is " + info.size()
                );
            }
        });

        Map<String, ImageInformation> names = new LinkedHashMap<>();
        Map<String, ImageInformation> samplers = new LinkedHashMap<>();
        for (ImageInformation image : pack.getIrisCustomImages()) {
            Objects.requireNonNull(image, "custom image");
            if (image.target() != TextureType.TEXTURE_2D && image.target() != TextureType.TEXTURE_3D) {
                throw new UnsupportedOperationException(
                        "Iris custom image '" + image.name() + "' uses unsupported target " + image.target()
                );
            }
            if (image.depth() <= 0 || (image.target() == TextureType.TEXTURE_2D && image.depth() != 1)) {
                throw new IllegalArgumentException(
                        "Iris custom image '" + image.name() + "' has invalid depth " + image.depth()
                );
            }
            if (image.isRelative()) {
                if (!Float.isFinite(image.relativeWidth()) || image.relativeWidth() <= 0.0F
                        || !Float.isFinite(image.relativeHeight()) || image.relativeHeight() <= 0.0F) {
                    throw new IllegalArgumentException(
                            "Iris relative custom image '" + image.name() + "' has invalid scale "
                                    + image.relativeWidth() + 'x' + image.relativeHeight()
                    );
                }
            } else if (image.width() <= 0 || image.height() <= 0) {
                throw new IllegalArgumentException(
                        "Iris custom image '" + image.name() + "' has invalid extent "
                                + image.width() + 'x' + image.height() + 'x' + image.depth()
                );
            }
            imageFormat(image.internalTextureFormat());
            if (names.putIfAbsent(Objects.requireNonNull(image.name(), "custom image name"), image) != null) {
                throw new IllegalArgumentException("Duplicate Iris custom image name '" + image.name() + "'");
            }
            if (image.samplerName() != null && samplers.putIfAbsent(image.samplerName(), image) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Iris custom image sampler '" + image.samplerName() + "'"
                );
            }
        }
    }

    private OwnedImage createImage(
            final ImageInformation definition,
            final int width,
            final int height
    ) {
        int imageWidth = definition.isRelative()
                ? Math.max(1, (int) (width * definition.relativeWidth()))
                : definition.width();
        int imageHeight = definition.isRelative()
                ? Math.max(1, (int) (height * definition.relativeHeight()))
                : definition.height();
        int imageDepth = definition.target() == TextureType.TEXTURE_3D ? definition.depth() : 1;
        if (imageWidth <= 0 || imageHeight <= 0 || imageDepth <= 0) {
            throw new IllegalArgumentException(
                    "Iris custom image '" + definition.name() + "' has invalid extent "
                            + imageWidth + "x" + imageHeight + "x" + imageDepth
            );
        }
        GpuFormat format = imageFormat(definition.internalTextureFormat());
        MetalGpuTexture texture = null;
        MetalGpuTextureView view = null;
        MetalGpuSampler sampler = null;
        try {
            texture = definition.target() == TextureType.TEXTURE_3D
                    ? new MetalGpuTexture(
                            this.device,
                            IMAGE_USAGE,
                            "metallum:iris_image/" + definition.name(),
                            format,
                            imageWidth,
                            imageHeight,
                            imageDepth,
                            1,
                            true
                    )
                    : (MetalGpuTexture) this.device.createTexture(
                            "metallum:iris_image/" + definition.name(),
                            IMAGE_USAGE,
                            format,
                            imageWidth,
                            imageHeight,
                            1,
                            1
                    );
            view = (MetalGpuTextureView) this.device.createTextureView(texture);
            boolean integer = format.componentType().name().contains("UINT")
                    || format.componentType().name().contains("SINT");
            FilterMode filter = integer ? FilterMode.NEAREST : FilterMode.LINEAR;
            sampler = new MetalGpuSampler(
                    this.device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    filter,
                    filter,
                    1,
                    OptionalDouble.of(0.0)
            );
            ByteBuffer zeroBytes = ByteBuffer.allocateDirect(
                    Math.toIntExact(Math.multiplyExact(
                            Math.multiplyExact((long) imageWidth, imageHeight),
                            Math.multiplyExact((long) imageDepth, format.blockSize())
                    ))
            );
            if (definition.clear()) {
                if (texture.isTexture3D()) {
                    this.device.createCommandEncoder().writeToTexture(
                            texture, zeroBytes.duplicate(), 0, 0, 0, 0, imageWidth, imageHeight
                    );
                } else {
                    this.device.createCommandEncoder().clearColorTexture(texture, new Vector4f());
                }
            }
            return new OwnedImage(definition, texture, view, sampler, imageWidth, imageHeight, zeroBytes);
        } catch (RuntimeException | Error failure) {
            if (view != null) {
                view.close();
            }
            if (texture != null) {
                texture.close();
            }
            if (sampler != null) {
                sampler.close();
            }
            throw failure;
        }
    }

    static GpuFormat imageFormat(final InternalTextureFormat format) {
        return IrisMetalTextureFormats.fromInternalName(format.name());
    }

    private static void validateExtent(final int width, final int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Iris compute resource extent must be positive: " + width + "x" + height
            );
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris Metal compute resources are closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.buffers.values().forEach(MetalGpuBuffer::close);
        this.buffers.clear();
        this.imagesByName.values().forEach(OwnedImage::close);
        this.imagesByName.clear();
        this.imagesBySampler.clear();
    }

    private static final class OwnedImage implements AutoCloseable {
        private final ImageInformation definition;
        private final MetalGpuTexture texture;
        private final MetalGpuTextureView view;
        private final MetalGpuSampler sampler;
        private final int width;
        private final int height;
        private final ByteBuffer zeroBytes;

        private OwnedImage(
                final ImageInformation definition,
                final MetalGpuTexture texture,
                final MetalGpuTextureView view,
                final MetalGpuSampler sampler,
                final int width,
                final int height,
                final ByteBuffer zeroBytes
        ) {
            this.definition = definition;
            this.texture = texture;
            this.view = view;
            this.sampler = sampler;
            this.width = width;
            this.height = height;
            this.zeroBytes = zeroBytes;
        }

        private MetalRenderPass.TextureViewAndSampler sampledBinding() {
            return new MetalRenderPass.TextureViewAndSampler(this.view, this.sampler);
        }

        @Override
        public void close() {
            this.view.close();
            this.texture.close();
            this.sampler.close();
        }
    }
}
