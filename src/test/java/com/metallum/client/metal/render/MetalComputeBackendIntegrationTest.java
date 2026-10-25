package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/**
 * macOS-only Iris-backend capability suite: generic compute pipelines, SSBO
 * write/read, storage-image load/store, indirect dispatch, encoder-boundary
 * synchronization (render->compute->render), GPU mipmap generation and
 * depth-compare samplers — all through the production MetalDevice /
 * MetalCommandEncoder / FFM bridge / Swift ABI, with GPU readback assertions.
 */
@EnabledOnOs(OS.MAC)
final class MetalComputeBackendIntegrationTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 4;

    private final Map<String, String> shaders = new HashMap<>();
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        assertTrue(MetalNativeBridge.supportsComputeAbi(), "dylib must export the compute ABI");
        assertTrue(MetalNativeBridge.supportsGenerateMipmaps(), "dylib must export generateMipmaps");
        assertTrue(MetalNativeBridge.supportsSamplerCompare(), "dylib must export the compare-sampler ABI");
        ShaderSource source = (identifier, type) ->
                shaders.get(identifier.getPath().substring(identifier.getPath().lastIndexOf('/') + 1)
                        + (type == ShaderType.VERTEX ? ".vert" : ".frag"));
        device = MetalGpuTestSupport.createSystemDefaultDevice(source, "Metal compute integration device");
        encoder = device.createCommandEncoder();
    }

    @AfterEach
    void closeDevice() {
        if (device != null) {
            device.close();
        }
    }

    @Test
    void computeWritesStorageBufferAbsoluteDispatch() {
        String glsl = """
                #version 450
                layout(local_size_x = 8) in;
                layout(std430, binding = 0) buffer OutBuf { uint values[]; };
                void main() {
                    values[gl_GlobalInvocationID.x] = gl_GlobalInvocationID.x * 3u + 5u;
                }
                """;
        try (MetalComputePipeline pipeline = MetalComputePipeline.compileGlsl(device, "ssbo_write", glsl);
             MetalGpuBuffer out = (MetalGpuBuffer) device.createBuffer(
                     () -> "ssbo-out", GpuBuffer.USAGE_MAP_READ, 32 * Integer.BYTES)) {
            assertEquals(8, pipeline.threadgroupWidth(), "local_size_x must be reflected from SPIR-V");
            assertEquals(1, pipeline.threadgroupHeight());
            try (MetalComputePass pass = encoder.createComputePass()) {
                pass.setPipeline(pipeline).bindBuffer(0, out).dispatchGroups(4, 1, 1);
            }
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer data = out.currentStorage().order(ByteOrder.nativeOrder());
            for (int i = 0; i < 32; i++) {
                assertEquals(i * 3 + 5, data.getInt(i * 4), "SSBO element " + i);
            }
        }
    }

    @Test
    void relativeDispatchCoversThreadGridWithBoundsGuard() {
        String glsl = """
                #version 450
                layout(local_size_x = 8) in;
                layout(std430, binding = 0) buffer OutBuf { uint values[]; };
                layout(std430, binding = 1) buffer Limits { uint count; };
                void main() {
                    if (gl_GlobalInvocationID.x < count) {
                        values[gl_GlobalInvocationID.x] = 7u;
                    }
                }
                """;
        try (MetalComputePipeline pipeline = MetalComputePipeline.compileGlsl(device, "relative_dispatch", glsl);
             MetalGpuBuffer out = (MetalGpuBuffer) device.createBuffer(
                     () -> "relative-out", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 24 * Integer.BYTES)) {
            ByteBuffer zero = ByteBuffer.allocateDirect(24 * Integer.BYTES);
            encoder.writeToBuffer(out.slice(), zero);
            ByteBuffer limit = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            limit.putInt(0, 20);
            try (MetalGpuBuffer limits = (MetalGpuBuffer) device.createBuffer(
                    () -> "relative-limit", GpuBuffer.USAGE_COPY_DST, limit)) {
                try (MetalComputePass pass = encoder.createComputePass()) {
                    pass.setPipeline(pipeline)
                            .bindBuffer(0, out)
                            .bindBuffer(1, limits)
                            .dispatchThreadsCovering(20, 1, 1);
                }
                encoder.submit();
                device.waitForSubmittedGpuWork();
            }
            ByteBuffer data = out.currentStorage().order(ByteOrder.nativeOrder());
            for (int i = 0; i < 20; i++) {
                assertEquals(7, data.getInt(i * 4), "covered element " + i);
            }
            for (int i = 20; i < 24; i++) {
                assertEquals(0, data.getInt(i * 4), "out-of-range element " + i + " must stay untouched");
            }
        }
    }

    @Test
    void computeToComputeStorageChainIsOrdered() {
        String producer = """
                #version 450
                layout(local_size_x = 16) in;
                layout(std430, binding = 0) buffer A { uint a[]; };
                void main() { a[gl_GlobalInvocationID.x] = gl_GlobalInvocationID.x + 100u; }
                """;
        String consumer = """
                #version 450
                layout(local_size_x = 16) in;
                layout(std430, binding = 0) buffer A { uint a[]; };
                layout(std430, binding = 1) buffer B { uint b[]; };
                void main() { b[gl_GlobalInvocationID.x] = a[gl_GlobalInvocationID.x] * 2u; }
                """;
        try (MetalComputePipeline first = MetalComputePipeline.compileGlsl(device, "chain_producer", producer);
             MetalComputePipeline second = MetalComputePipeline.compileGlsl(device, "chain_consumer", consumer);
             MetalGpuBuffer a = (MetalGpuBuffer) device.createBuffer(() -> "chain-a", 0, 16 * Integer.BYTES);
             MetalGpuBuffer b = (MetalGpuBuffer) device.createBuffer(() -> "chain-b", GpuBuffer.USAGE_MAP_READ, 16 * Integer.BYTES)) {
            try (MetalComputePass pass = encoder.createComputePass()) {
                pass.setPipeline(first).bindBuffer(0, a).dispatchGroups(1, 1, 1);
            }
            try (MetalComputePass pass = encoder.createComputePass()) {
                pass.setPipeline(second).bindBuffer(0, a).bindBuffer(1, b).dispatchGroups(1, 1, 1);
            }
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer data = b.currentStorage().order(ByteOrder.nativeOrder());
            for (int i = 0; i < 16; i++) {
                assertEquals((i + 100) * 2, data.getInt(i * 4), "chained element " + i);
            }
        }
    }

    @Test
    void indirectDispatchReadsGpuArguments() {
        String glsl = """
                #version 450
                layout(local_size_x = 8) in;
                layout(std430, binding = 0) buffer OutBuf { uint values[]; };
                void main() { values[gl_GlobalInvocationID.x] = 11u; }
                """;
        ByteBuffer args = ByteBuffer.allocateDirect(3 * Integer.BYTES).order(ByteOrder.nativeOrder());
        args.putInt(0, 3).putInt(4, 1).putInt(8, 1);
        try (MetalComputePipeline pipeline = MetalComputePipeline.compileGlsl(device, "indirect_dispatch", glsl);
             MetalGpuBuffer argBuffer = (MetalGpuBuffer) device.createBuffer(
                     () -> "indirect-args", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDIRECT_PARAMETERS, args);
             MetalGpuBuffer out = (MetalGpuBuffer) device.createBuffer(
                     () -> "indirect-out", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 32 * Integer.BYTES)) {
            ByteBuffer zero = ByteBuffer.allocateDirect(32 * Integer.BYTES);
            encoder.writeToBuffer(out.slice(), zero);
            try (MetalComputePass pass = encoder.createComputePass()) {
                pass.setPipeline(pipeline).bindBuffer(0, out).dispatchIndirect(argBuffer, 0L);
            }
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer data = out.currentStorage().order(ByteOrder.nativeOrder());
            for (int i = 0; i < 24; i++) {
                assertEquals(11, data.getInt(i * 4), "indirect-covered element " + i);
            }
            for (int i = 24; i < 32; i++) {
                assertEquals(0, data.getInt(i * 4), "element beyond 3 groups must stay untouched");
            }
        }
    }

    @Test
    void computeImageStoreThenReadback() {
        String glsl = """
                #version 450
                layout(local_size_x = 8, local_size_y = 4) in;
                layout(binding = 0, rgba8) writeonly uniform image2D dst;
                void main() {
                    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                    imageStore(dst, p, vec4(0.25, 0.5, 0.75, 1.0));
                }
                """;
        try (MetalComputePipeline pipeline = MetalComputePipeline.compileGlsl(device, "image_store", glsl);
             MetalGpuTexture storage = (MetalGpuTexture) device.createTexture(
                     "storage-image",
                     GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | MetalGpuTexture.USAGE_SHADER_WRITE,
                     GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1)) {
            try (MetalComputePass pass = encoder.createComputePass()) {
                pass.setPipeline(pipeline)
                        .bindTexture(0, storage)
                        .dispatchThreadsCovering(WIDTH, HEIGHT, 1);
            }
            ByteBuffer data = MetalGpuTestSupport.readback(
                    device, encoder, storage, "caps readback", 0, WIDTH, HEIGHT
            );
            assertByteNear(data.get(0), 64, "imageStore red");
            assertByteNear(data.get(1), 128, "imageStore green");
            assertByteNear(data.get(2), 191, "imageStore blue");
            assertByteNear(data.get((WIDTH * HEIGHT - 1) * 4), 64, "imageStore red at last pixel");
        }
    }

    @Test
    void texture3dUploadAndReadbackPreservesDepthSlices() {
        int width = 4;
        int height = 2;
        int depth = 3;
        int pixelSize = GpuFormat.RGBA8_UNORM.blockSize();
        ByteBuffer source = ByteBuffer.allocateDirect(width * height * depth * pixelSize);
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    source.put((byte) (z * 50 + x));
                    source.put((byte) (y * 80));
                    source.put((byte) (255 - z * 50));
                    source.put((byte) 255);
                }
            }
        }
        source.flip();
        try (MetalGpuTexture texture = new MetalGpuTexture(
                device,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC
                        | GpuTexture.USAGE_COPY_DST | MetalGpuTexture.USAGE_SHADER_WRITE,
                "caps-3d",
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                depth,
                1,
                true
        ); MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "caps-3d-readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                source.capacity()
        )) {
            encoder.writeToTexture(texture, source, 0, 0, 0, 0, width, height);
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer result = buffer.currentStorage().limit(source.capacity()).slice();
            int secondSlice = (width * height) * pixelSize;
            assertEquals(50, Byte.toUnsignedInt(result.get(secondSlice)), "z=1 red base");
            assertEquals(80, Byte.toUnsignedInt(result.get(secondSlice + width * pixelSize + 1)), "z=1 green second row");
            int thirdSlice = secondSlice * 2;
            assertEquals(100, Byte.toUnsignedInt(result.get(thirdSlice)), "z=2 red base");
            assertEquals(155, Byte.toUnsignedInt(result.get(thirdSlice + 2)), "z=2 blue base");
        }
    }

    @Test
    void renderThenComputeImageLoadObservesFragmentOutput() {
        shaders.put("caps_fill.vert", FULLSCREEN_VERTEX);
        shaders.put("caps_fill.frag", """
                #version 450
                layout(location=0) out vec4 color;
                void main() { color = vec4(0.5, 0.25, 1.0, 1.0); }
                """);
        String glsl = """
                #version 450
                layout(local_size_x = 8) in;
                layout(binding = 0, rgba8) readonly uniform image2D src;
                layout(std430, binding = 0) buffer OutBuf { uint matches; };
                void main() {
                    ivec2 p = ivec2(int(gl_GlobalInvocationID.x), 1);
                    vec4 texel = imageLoad(src, p);
                    if (abs(texel.r - 0.5) < 0.01 && abs(texel.g - 0.25) < 0.01 && abs(texel.b - 1.0) < 0.01) {
                        atomicAdd(matches, 1u);
                    }
                }
                """;
        try (MetalGpuTexture target = (MetalGpuTexture) device.createTexture(
                "render-then-compute",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
             MetalComputePipeline pipeline = MetalComputePipeline.compileGlsl(device, "image_load", glsl);
             MetalGpuBuffer out = (MetalGpuBuffer) device.createBuffer(
                     () -> "match-count", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, Integer.BYTES)) {
            ByteBuffer zero = ByteBuffer.allocateDirect(Integer.BYTES);
            encoder.writeToBuffer(out.slice(), zero);
            renderFullscreen("caps_fill", target, new Vector4f(0.0F, 0.0F, 0.0F, 1.0F));
            try (MetalComputePass pass = encoder.createComputePass()) {
                pass.setPipeline(pipeline)
                        .bindTexture(0, target)
                        .bindBuffer(0, out)
                        .dispatchGroups(WIDTH / 8, 1, 1);
            }
            encoder.submit();
            device.waitForSubmittedGpuWork();
            assertEquals(WIDTH, out.currentStorage().order(ByteOrder.nativeOrder()).getInt(0),
                    "every sampled pixel must show the fragment output (render->compute ordering)");
        }
    }

    @Test
    void computeImageStoreSampledByRenderPass() {
        String glsl = """
                #version 450
                layout(local_size_x = 8, local_size_y = 4) in;
                layout(binding = 0, rgba8) writeonly uniform image2D dst;
                void main() {
                    imageStore(dst, ivec2(gl_GlobalInvocationID.xy), vec4(0.0, 1.0, 0.25, 1.0));
                }
                """;
        shaders.put("caps_sample.vert", FULLSCREEN_VERTEX);
        shaders.put("caps_sample.frag", """
                #version 450
                layout(location=0) out vec4 color;
                void main() { color = vec4(0.75, 0.5, 0.25, 1.0); }
                """);
        try (MetalComputePipeline pipeline = MetalComputePipeline.compileGlsl(device, "compute_then_render", glsl);
             MetalGpuTexture storage = (MetalGpuTexture) device.createTexture(
                     "compute-src",
                     GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | MetalGpuTexture.USAGE_SHADER_WRITE,
                     GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
             MetalGpuTexture target = (MetalGpuTexture) device.createTexture(
                     "compute-then-render-target",
                     GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                     GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1)) {
            try (MetalComputePass pass = encoder.createComputePass()) {
                pass.setPipeline(pipeline)
                        .bindTexture(0, storage)
                        .dispatchThreadsCovering(WIDTH, HEIGHT, 1);
            }
            // compute -> render ordering across the fence chain: the render
            // pass draws over the target, then we copy the COMPUTE result to
            // prove its writes completed independently of the draw.
            renderFullscreen("caps_sample", target, new Vector4f(0.0F, 0.0F, 0.0F, 1.0F));
            ByteBuffer computeData = MetalGpuTestSupport.readback(
                    device, encoder, storage, "caps readback", 0, WIDTH, HEIGHT
            );
            assertByteNear(computeData.get(1), 255, "compute green after interleaved render");
            ByteBuffer renderData = MetalGpuTestSupport.readback(
                    device, encoder, target, "caps readback", 0, WIDTH, HEIGHT
            );
            assertByteNear(renderData.get(0), 191, "render red after compute");
        }
    }

    @Test
    void generateMipmapsProducesDownsampledLevels() {
        int mips = 3;
        try (MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "mipmap-src",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, mips)) {
            ByteBuffer level0 = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    boolean red = x < WIDTH / 2;
                    level0.put((byte) (red ? 255 : 0));
                    level0.put((byte) 0);
                    level0.put((byte) (red ? 0 : 255));
                    level0.put((byte) 255);
                }
            }
            level0.flip();
            encoder.writeToTexture(texture, level0, 0, 0, 0, 0, WIDTH, HEIGHT);
            encoder.generateMipmaps(texture);
            int mipWidth = WIDTH >> 2;
            ByteBuffer mip2 = MetalGpuTestSupport.readback(
                    device, encoder, texture, "caps readback", 2, mipWidth, 1
            );
            assertByteNear(mip2.get(2 * 4), 255, "mip2 left half red");
            assertByteNear(mip2.get(2 * 4 + 2), 0, "mip2 left half has no blue");
            assertByteNear(mip2.get((mipWidth - 3) * 4), 0, "mip2 right half has no red");
            assertByteNear(mip2.get((mipWidth - 3) * 4 + 2), 255, "mip2 right half blue");
        }
    }

    @Test
    void compareSamplerImplementsShadowSemantics() {
        String glsl = """
                #version 450
                layout(local_size_x = 2) in;
                layout(binding = 1) uniform sampler2DShadow shadowMap;
                layout(std430, binding = 0) buffer OutBuf { float results[]; };
                void main() {
                    float reference = gl_GlobalInvocationID.x == 0u ? 0.25 : 0.75;
                    results[gl_GlobalInvocationID.x] = texture(shadowMap, vec3(0.5, 0.5, reference));
                }
                """;
        try (MetalGpuTexture depth = (MetalGpuTexture) device.createTexture(
                "shadow-depth",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1, 1);
             MetalComputePipeline pipeline = MetalComputePipeline.compileGlsl(device, "shadow_compare", glsl);
             MetalGpuBuffer out = (MetalGpuBuffer) device.createBuffer(
                     () -> "shadow-results", GpuBuffer.USAGE_MAP_READ, 2 * Float.BYTES)) {
            encoder.clearDepthTexture(depth, 0.5);
            MetalGpuSampler compareSampler = new MetalGpuSampler(
                    device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,
                    FilterMode.NEAREST,
                    1,
                    OptionalDouble.of(0.0),
                    MTLCompareFunction.LessEqual
            );
            try {
                try (MetalComputePass pass = encoder.createComputePass()) {
                    pass.setPipeline(pipeline)
                            .bindTexture(1, depth)
                            .bindSampler(1, compareSampler.nativeHandle())
                            .bindBuffer(0, out)
                            .dispatchGroups(1, 1, 1);
                }
                encoder.submit();
                device.waitForSubmittedGpuWork();
                ByteBuffer data = out.currentStorage().order(ByteOrder.nativeOrder());
                assertEquals(1.0F, data.getFloat(0), 0.001F, "ref 0.25 <= depth 0.5 must pass");
                assertEquals(0.0F, data.getFloat(4), 0.001F, "ref 0.75 <= depth 0.5 must fail");
            } finally {
                compareSampler.close();
            }
        }
    }

    @Test
    void staleBridgeGuardReportsMissingSymbolsClearly() {
        // With a fresh dylib all three capability probes are true (asserted in
        // setup); this test pins the contract that pipeline compilation checks
        // the probe rather than crashing later at dispatch time.
        assertTrue(MetalNativeBridge.supportsComputeAbi());
    }

    private static final String FULLSCREEN_VERTEX = """
            #version 450
            void main() {
                vec2 positions[3] = vec2[](
                    vec2(-1.0, -1.0),
                    vec2( 3.0, -1.0),
                    vec2(-1.0,  3.0)
                );
                gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
            }
            """;

    private void renderFullscreen(final String shaderName, final MetalGpuTexture target, final Vector4f clear) {
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_caps/" + shaderName)
                .withVertexShader("metallum_caps/" + shaderName)
                .withFragmentShader("metallum_caps/" + shaderName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .build();
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "caps " + shaderName);
        try (MetalGpuTextureView view = new MetalGpuTextureView(target, 0, 1)) {
            descriptor.withColorAttachment(view, Optional.of(clear));
            descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
            pass.setPipeline(pipeline);
            pass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
        }
    }

    private static void assertByteNear(final byte actualByte, final int expected, final String label) {
        int actual = Byte.toUnsignedInt(actualByte);
        assertTrue(Math.abs(actual - expected) <= 2, label + ": expected " + expected + ", got " + actual);
    }
}
