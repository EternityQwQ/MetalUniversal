package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLSamplerMipFilter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
final class IrisMetalPingPongTargetsIntegrationTest {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 8;
    private static final String VERTEX_SHADER = """
            #version 450
            void main() {
                vec2 positions[3] = vec2[](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
                gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
            }
            """;
    private static final Map<String, String> FRAGMENTS = Map.of(
            "red", "#version 450\nlayout(location=0) out vec4 color; void main(){ color=vec4(1,0,0,1); }",
            "green", "#version 450\nlayout(location=0) out vec4 color; void main(){ color=vec4(0,1,0,1); }"
    );

    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice));
        ShaderSource source = (identifier, type) -> type == ShaderType.VERTEX
                ? VERTEX_SHADER
                : FRAGMENTS.get(identifier.getPath().substring(identifier.getPath().lastIndexOf('/') + 1));
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris ping-pong integration device",
                MemorySegment.NULL
        );
        encoder = device.createCommandEncoder();
    }

    @AfterEach
    void closeDevice() {
        if (device != null) {
            device.close();
        }
    }

    @Test
    void flipPreservesHistoryAndResizeRetiresGeneration() {
        try (IrisMetalPingPongTargets targets = new IrisMetalPingPongTargets(
                device, "iris-test-colortex", new GpuFormat[]{GpuFormat.RGBA8_UNORM},
                WIDTH, HEIGHT, Set.of(0)
        )) {
            MetalGpuTexture oldMain = targets.mainTexture(0);
            MetalGpuTexture oldAlt = targets.altTexture(0);
            assertSame(oldMain, targets.readTexture(0));
            assertSame(oldAlt, targets.writeTexture(0));

            render(targets.writeView(0), pipeline("red"), WIDTH, HEIGHT);
            assertPixel(targets.writeTexture(0), 255, 0);
            targets.flip(0);
            assertSame(oldAlt, targets.readTexture(0));
            assertPixel(targets.readTexture(0), 255, 0);
            targets.enableReadMipmaps(0);
            assertTrue(targets.readMipmapsEnabled(0));

            render(targets.writeView(0), pipeline("green"), WIDTH, HEIGHT);
            targets.flip(0);
            assertSame(oldMain, targets.readTexture(0));
            assertPixel(targets.readTexture(0), 0, 255);
            assertPixel(targets.writeTexture(0), 255, 0);

            targets.resize(new int[]{WIDTH * 2}, new int[]{HEIGHT * 2});
            assertTrue(oldMain.isClosed());
            assertTrue(oldAlt.isClosed());
            assertFalse(targets.isFlipped(0));
            assertFalse(targets.flippedAtLeastOnce(0));
            assertFalse(targets.readMipmapsEnabled(0));
            assertEquals(WIDTH * 2, targets.readTexture(0).getWidth(0));
            assertEquals(WIDTH * 2, targets.width(0));
            assertNotSame(oldMain, targets.readTexture(0));
        }
    }

    @Test
    void perTargetExtentsAllocateScaleAndResizeIndependently() {
        try (IrisMetalPingPongTargets targets = new IrisMetalPingPongTargets(
                device,
                "iris-test-scaled",
                new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM},
                new int[]{WIDTH, WIDTH / 2, WIDTH * 2},
                new int[]{HEIGHT, HEIGHT / 2, HEIGHT * 2},
                Set.of(1)
        )) {
            // Each target and both ping-pong sides allocate at their own extent.
            assertEquals(WIDTH, targets.width(0));
            assertEquals(HEIGHT, targets.height(0));
            assertEquals(WIDTH / 2, targets.width(1));
            assertEquals(HEIGHT / 2, targets.height(1));
            assertEquals(WIDTH * 2, targets.width(2));
            assertEquals(HEIGHT * 2, targets.height(2));
            assertEquals(WIDTH / 2, targets.readTexture(1).getWidth(0));
            assertEquals(HEIGHT / 2, targets.readTexture(1).getHeight(0));
            assertEquals(WIDTH / 2, targets.altTexture(1).getWidth(0));
            assertEquals(HEIGHT / 2, targets.altTexture(1).getHeight(0));
            assertEquals(WIDTH * 2, targets.mainTexture(2).getWidth(0));
            // Mip chains are per target: target 1 is mipmapped at its scaled size.
            assertEquals(1, targets.mainTexture(0).getMipLevels());
            assertTrue(targets.mainTexture(1).getMipLevels() > 1);
            assertEquals(1, targets.mainTexture(2).getMipLevels());

            // A pass into the scaled target renders at the scaled render area.
            render(targets.writeView(1), pipeline("red"), WIDTH / 2, HEIGHT / 2);
            assertPixel(targets.writeTexture(1), 255, 0);

            targets.resize(
                    new int[]{WIDTH * 2, WIDTH / 4, WIDTH},
                    new int[]{HEIGHT * 2, HEIGHT / 4, HEIGHT}
            );
            assertEquals(WIDTH * 2, targets.readTexture(0).getWidth(0));
            assertEquals(WIDTH / 4, targets.width(1));
            assertEquals(HEIGHT / 4, targets.height(1));
            assertEquals(WIDTH, targets.readTexture(2).getWidth(0));
            assertEquals(WIDTH, targets.width(2));

            // The uniform convenience overload still resizes every target.
            targets.resize(WIDTH, HEIGHT);
            assertEquals(WIDTH, targets.width(1));
            assertEquals(HEIGHT, targets.height(1));
            assertEquals(WIDTH, targets.width(2));
            assertEquals(HEIGHT, targets.height(2));
        }
    }

    @Test
    void worldGenerationOwnsResizeAndRetiresAllTargets() {
        IrisMetalWorldResources resources = new IrisMetalWorldResources(
                device,
                7,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA16_FLOAT},
                WIDTH,
                HEIGHT,
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                8,
                null
        );
        MetalGpuTexture oldColor = resources.renderTargets().colorTargets().mainTexture(0);
        MetalGpuTexture oldDepth = resources.renderTargets().mainDepthTexture();
        MetalGpuTexture noise = resources.noiseTexture().texture();

        assertEquals(7, resources.generation());
        assertTrue(resources.isOwnedBy(device));
        resources.resize(WIDTH * 2, HEIGHT * 2);
        assertTrue(oldColor.isClosed());
        assertTrue(oldDepth.isClosed());
        assertFalse(noise.isClosed());
        assertEquals(WIDTH * 2, resources.renderTargets().width());

        resources.close();
        resources.close();
        assertTrue(noise.isClosed());
        assertThrows(IllegalStateException.class, resources::renderTargets);
    }

    @Test
    void snapshotRestoreAndFeedbackValidationFailClosed() {
        try (IrisMetalPingPongTargets targets = new IrisMetalPingPongTargets(
                device, "iris-test-state", new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM},
                WIDTH, HEIGHT
        )) {
            targets.flip(0);
            BitSet snapshot = targets.snapshot();
            targets.flip(1);
            targets.restore(snapshot);
            assertTrue(targets.isFlipped(0));
            assertFalse(targets.isFlipped(1));
            assertThrows(
                    IllegalStateException.class,
                    () -> targets.checkNoFeedbackLoop(new int[]{0}, new int[]{0}, new BitSet())
            );
            targets.checkNoFeedbackLoop(new int[]{0}, new int[]{0}, snapshot);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> targets.checkNoFeedbackLoop(new int[]{2}, new int[]{1})
            );
            targets.checkNoFeedbackLoop(new int[]{0}, new int[]{1});
        }
    }

    @Test
    void renderTargetsMapDrawBuffersAndCaptureThreeDepthStages() {
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM},
                WIDTH,
                HEIGHT
        )) {
            try (IrisMetalRenderTargets.RenderPassDescriptorWithViews mapped = targets.createWriteDescriptor(
                    "compact DRAWBUFFERS",
                    new int[]{2, 0},
                    null,
                    false,
                    null,
                    new int[]{1}
            )) {
                assertSame(
                        targets.colorTargets().writeTexture(2),
                        mapped.descriptor().colorAttachments().get(0).textureView().texture()
                );
                assertSame(
                        targets.colorTargets().writeTexture(0),
                        mapped.descriptor().colorAttachments().get(1).textureView().texture()
                );
            }
            assertDoesNotThrow(
                    () -> {
                        try (IrisMetalRenderTargets.RenderPassDescriptorWithViews ignored =
                                     targets.createWriteDescriptor(
                                             "feedback", new int[]{0}, null, false, null, new int[]{0}
                                     )) {
                            // Same logical target is valid when the pass reads the other ping-pong side.
                        }
                    }
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> targets.createWriteDescriptor(
                            "out-of-range", new int[]{0}, null, false, null, new int[]{3}
                    )
            );

            clearDepth(targets.mainDepthView(), 0.25);
            targets.captureNoTranslucentsDepth(encoder);
            clearDepth(targets.mainDepthView(), 0.5);
            targets.captureNoHandDepth(encoder);
            clearDepth(targets.mainDepthView(), 0.75);
            encoder.submit();
            device.waitForSubmittedGpuWork();

            assertDepth(targets.noTranslucentsDepthTexture(), 0.25F);
            assertDepth(targets.noHandDepthTexture(), 0.5F);
            assertDepth(targets.mainDepthTexture(), 0.75F);
        }
    }

    @Test
    void renderTargetDefaultClearInitializesBothPhysicalSidesOncePerGeneration() {
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM},
                WIDTH,
                HEIGHT
        )) {
            assertTrue(targets.clearForFrame(encoder, new Vector4f(0.2F, 0.3F, 0.4F, 1.0F)));
            encoder.submit();
            device.waitForSubmittedGpuWork();
            assertPixel(targets.colorTargets().mainTexture(0), 51, 77);
            assertPixel(targets.colorTargets().altTexture(0), 51, 77);
            assertPixel(targets.colorTargets().mainTexture(1), 255, 255);
            assertFalse(targets.clearForFrame(encoder, new Vector4f(1.0F, 0.0F, 0.0F, 1.0F)));

            targets.resize(WIDTH * 2, HEIGHT * 2);
            assertTrue(targets.clearForFrame(encoder, new Vector4f(0.0F, 0.5F, 0.0F, 1.0F)));
        }
    }

    @Test
    void targetSamplersTrackFormatAndPhysicalMipState() {
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UINT},
                WIDTH,
                HEIGHT,
                Map.of(),
                Set.of(0, 1)
        )) {
            MetalGpuSampler floatSampler = (MetalGpuSampler) targets.colorSampler(0);
            MetalGpuSampler integerSampler = (MetalGpuSampler) targets.colorSampler(1);
            assertEquals(com.mojang.blaze3d.textures.FilterMode.LINEAR, floatSampler.getMinFilter());
            assertEquals(com.mojang.blaze3d.textures.FilterMode.NEAREST, integerSampler.getMinFilter());
            assertEquals(MTLSamplerMipFilter.NotMipmapped, floatSampler.mipFilter());
            assertEquals(MTLSamplerMipFilter.NotMipmapped, integerSampler.mipFilter());

            targets.enableReadMipmaps(0);
            targets.enableReadMipmaps(1);
            assertEquals(MTLSamplerMipFilter.Linear, ((MetalGpuSampler) targets.colorSampler(0)).mipFilter());
            assertEquals(MTLSamplerMipFilter.Linear, ((MetalGpuSampler) targets.colorSampler(1)).mipFilter());
            targets.resetMipmaps();
            assertEquals(MTLSamplerMipFilter.NotMipmapped, ((MetalGpuSampler) targets.colorSampler(0)).mipFilter());
        }
    }

    @Test
    void shadowTargetsIsolateDepthColorFlipAndResize() {
        try (IrisMetalShadowTargets shadow = new IrisMetalShadowTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM},
                32,
                new boolean[]{false},
                new boolean[]{true, false},
                new boolean[]{false, false}
        )) {
            MetalGpuTexture oldDepth = shadow.shadowDepthTexture();
            MetalGpuTexture oldNoTranslucents = shadow.shadowDepthNoTranslucentsTexture();
            MetalGpuTexture oldMainColor = shadow.colorTargets().mainTexture(0);
            MetalGpuTexture oldAltColor = shadow.colorTargets().altTexture(0);
            assertEquals(1, oldDepth.getMipLevels());
            assertEquals(1, oldNoTranslucents.getMipLevels());
            assertNotEquals(
                    shadow.depthSampler(0, false).nativeHandle(),
                    shadow.depthSampler(0, true).nativeHandle()
            );

            clearDepth(shadow.shadowDepthView(), 0.3, 32, 32);
            shadow.captureNoTranslucentsDepth(encoder);
            clearDepth(shadow.shadowDepthView(), 0.1, 32, 32);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            assertDepth(shadow.shadowDepthTexture(), 0.1F);
            assertDepth(shadow.shadowDepthNoTranslucentsTexture(), 0.3F);

            try (IrisMetalRenderTargets.RenderPassDescriptorWithViews gbuffer =
                         shadow.createShadowGbufferDescriptor("shadow gbuffer", new int[]{0}, null, null)) {
                assertSame(
                        oldMainColor,
                        gbuffer.descriptor().colorAttachments().getFirst().textureView().texture()
                );
            }
            BitSet readsMain = new BitSet();
            try (IrisMetalRenderTargets.RenderPassDescriptorWithViews composite =
                         shadow.createShadowCompositeDescriptor(
                                 "shadow composite", new int[]{0}, readsMain, 0, 0, 32, 32
                         )) {
                assertSame(
                        oldAltColor,
                        composite.descriptor().colorAttachments().getFirst().textureView().texture()
                );
            }
            BitSet readsAlt = new BitSet();
            readsAlt.set(0);
            shadow.publishFlipState(readsAlt);
            assertSame(oldAltColor, shadow.colorTexture(0, readsAlt));
            assertSame(oldAltColor, shadow.colorTargets().readTexture(0));

            shadow.resize(16);
            assertTrue(oldDepth.isClosed());
            assertTrue(oldNoTranslucents.isClosed());
            assertTrue(oldMainColor.isClosed());
            assertTrue(oldAltColor.isClosed());
            assertEquals(16, shadow.resolution());
            assertEquals(16, shadow.shadowDepthTexture().getWidth(0));
            assertFalse(shadow.colorTargets().isFlipped(0));
        }
    }

    @Test
    void shadowDepthMipmapsUseFilteredReductionAcrossLevels() {
        try (IrisMetalShadowTargets shadow = new IrisMetalShadowTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM},
                4,
                new boolean[]{false},
                new boolean[]{false, true},
                new boolean[]{true, true}
        )) {
            assertEquals(3, shadow.shadowDepthTexture().getMipLevels());
            writeDepthPattern(shadow.shadowDepthTexture(), 4, 4);
            shadow.captureNoTranslucentsDepth(encoder);
            shadow.generateDepthMipmaps(encoder);
            encoder.submit();
            device.waitForSubmittedGpuWork();

            assertEquals(0.1F, readDepth(shadow.shadowDepthTexture(), 0), 0.001F);
            assertEquals(0.1F, readDepth(shadow.shadowDepthNoTranslucentsTexture(), 0), 0.001F);
            for (int level = 1; level < shadow.shadowDepthTexture().getMipLevels(); level++) {
                assertEquals(0.45F, readDepth(shadow.shadowDepthTexture(), level), 0.001F, "shadowtex0 mip " + level);
                assertEquals(
                        0.45F,
                        readDepth(shadow.shadowDepthNoTranslucentsTexture(), level),
                        0.001F,
                        "shadowtex1 mip " + level
                );
            }
        }
    }

    private RenderPipeline pipeline(final String name) {
        return RenderPipeline.builder()
                .withLocation("metallum_test/" + name)
                .withVertexShader("metallum_test/" + name)
                .withFragmentShader("metallum_test/" + name)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL
                ))
                .build();
    }

    private void render(
            final MetalGpuTextureView view,
            final RenderPipeline pipeline,
            final int width,
            final int height
    ) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Iris ping-pong pass")
                .withColorAttachment(view, Optional.of(new Vector4f(0.0F)))
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
        pass.setPipeline(pipeline);
        pass.draw(3, 1, 0, 0);
        encoder.submitRenderPass();
        encoder.submit();
        device.waitForSubmittedGpuWork();
    }

    private void writeDepthPattern(
            final MetalGpuTexture depth,
            final int width,
            final int height
    ) {
        try (MetalGpuTexture color = (MetalGpuTexture) device.createTexture(
                "depth-pattern-color",
                com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1
        )) {
            float[] values = {0.1F, 0.3F, 0.5F, 0.9F};
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float value = values[(y & 1) * 2 + (x & 1)];
                    encoder.clearColorAndDepthTextures(
                            color,
                            new Vector4f(0.0F),
                            depth,
                            value,
                            x,
                            y,
                            1,
                            1
                    );
                }
            }
        }
    }

    private void assertPixel(final MetalGpuTexture texture, final int red, final int green) {
        int bytes = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "Iris ping-pong readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                bytes
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {}, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer data = buffer.currentStorage().limit(bytes).slice().order(ByteOrder.nativeOrder());
            assertEquals(red, Byte.toUnsignedInt(data.get(0)), "red");
            assertEquals(green, Byte.toUnsignedInt(data.get(1)), "green");
            assertEquals(255, Byte.toUnsignedInt(data.get(3)), "alpha");
        }
    }

    private void clearDepth(final MetalGpuTextureView view, final double value) {
        clearDepth(view, value, WIDTH, HEIGHT);
    }

    private void clearDepth(
            final MetalGpuTextureView view,
            final double value,
            final int width,
            final int height
    ) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Iris depth clear")
                .withDepthAttachment(view, java.util.OptionalDouble.of(value))
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        encoder.createRenderPass(descriptor);
        encoder.submitRenderPass();
    }

    private void assertDepth(final MetalGpuTexture texture, final float expected) {
        int bytes = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "Iris depth readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                bytes
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {}, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer data = buffer.currentStorage().limit(bytes).slice().order(ByteOrder.nativeOrder());
            assertEquals(expected, data.getFloat(0), 0.001F);
        }
    }

    private float readDepth(final MetalGpuTexture texture, final int mipLevel) {
        int width = texture.getWidth(mipLevel);
        int height = texture.getHeight(mipLevel);
        int bytes = width * height * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "Iris depth mip readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                bytes
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {}, mipLevel);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            return buffer.currentStorage().limit(bytes).slice().order(ByteOrder.nativeOrder()).getFloat(0);
        }
    }
}
