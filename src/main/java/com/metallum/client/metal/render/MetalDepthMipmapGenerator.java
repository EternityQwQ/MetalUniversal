package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.List;
import java.util.OptionalDouble;

/** Generates a depth mip chain using the filtered reduction required by glGenerateMipmap. */
@Environment(EnvType.CLIENT)
final class MetalDepthMipmapGenerator implements AutoCloseable {
    private static final int SCRATCH_USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;
    private static final String VERTEX_MSL = """
            #include <metal_stdlib>
            using namespace metal;

            struct MipmapVertexOut {
                float4 position [[position]];
                float pointSize [[point_size]];
            };

            vertex MipmapVertexOut depthMipmapVertex(
                    uint vertexId [[vertex_id]],
                    texture2d<float, access::sample> sourceDepth [[texture(0)]],
                    sampler sourceDepthSampler [[sampler(0)]]) {
                uint2 sourceExtent = uint2(sourceDepth.get_width(), sourceDepth.get_height());
                uint2 destinationExtent = max(sourceExtent / 2, uint2(1));
                uint2 destinationPixel = uint2(
                    vertexId % destinationExtent.x,
                    vertexId / destinationExtent.x
                );
                float2 texCoord = (float2(destinationPixel) + 0.5) / float2(destinationExtent);
                float depth = sourceDepth.sample(sourceDepthSampler, texCoord).r;
                float2 clipPosition = texCoord * float2(2.0, -2.0) + float2(-1.0, 1.0);
                MipmapVertexOut out;
                out.position = float4(clipPosition, depth, 1.0);
                out.pointSize = 1.0;
                return out;
            }
            """;
    private static final String FRAGMENT_MSL = """
            #include <metal_stdlib>
            using namespace metal;

            struct MipmapVertexOut {
                float4 position [[position]];
                float pointSize [[point_size]];
            };

            fragment void depthMipmapFragment(MipmapVertexOut in [[stage_in]]) {}
            """;

    private final MetalDevice device;
    private final MetalCompiledRenderPipeline pipeline;
    private final MetalGpuSampler sampler;
    private MetalGpuTexture scratch;
    private int scratchWidth;
    private int scratchHeight;
    private int scratchMipLevels;
    private boolean closed;

    MetalDepthMipmapGenerator(final MetalDevice device) {
        this.device = device;
        this.pipeline = new MetalCompiledRenderPipeline(
                device,
                "iris/depth_mipmap",
                VERTEX_MSL,
                FRAGMENT_MSL,
                "depthMipmapVertex",
                "depthMipmapFragment",
                List.of(new MetalCompiledRenderPipeline.ResourceBinding(
                        MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE,
                        "sourceDepth",
                        0,
                        MetalCompiledRenderPipeline.STAGE_VERTEX,
                        null,
                        0
                )),
                false,
                PolygonMode.FILL,
                PrimitiveTopology.POINTS,
                new VertexFormat[0],
                new DepthStencilState(CompareOp.ALWAYS_PASS, true),
                new ColorTargetState[0]
        );
        if (!pipeline.isValid()) {
            pipeline.close();
            throw new IllegalStateException("Metal depth-only mipmap pipeline is unavailable");
        }
        this.sampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.of(0.0),
                null
        );
    }

    void generate(final MetalCommandEncoder encoder, final MetalGpuTexture texture) {
        ensureOpen();
        if (texture.mtlPixelFormat() != MTLPixelFormat.Depth32Float) {
            throw new IllegalArgumentException(
                    "Depth mipmap generation requires Depth32Float, got " + texture.mtlPixelFormat()
            );
        }
        if (texture.getMipLevels() <= 1) {
            return;
        }
        ensureScratch(texture.getWidth(0), texture.getHeight(0), texture.getMipLevels());

        for (int destinationLevel = 1; destinationLevel < texture.getMipLevels(); destinationLevel++) {
            int sourceLevel = destinationLevel - 1;
            int level = destinationLevel;
            int sourceWidth = texture.getWidth(sourceLevel);
            int sourceHeight = texture.getHeight(sourceLevel);
            encoder.copyTextureToTexture(
                    texture, scratch, sourceLevel, 0, 0, 0, 0, sourceWidth, sourceHeight, "depth.mip"
            );

            try (MetalGpuTextureView sourceView = new MetalGpuTextureView(scratch, sourceLevel, 1);
                 MetalGpuTextureView destinationView = new MetalGpuTextureView(texture, destinationLevel, 1)) {
                int destinationWidth = texture.getWidth(destinationLevel);
                int destinationHeight = texture.getHeight(destinationLevel);
                RenderPassDescriptor descriptor = RenderPassDescriptor.create(
                                () -> "Iris depth mip " + level
                        )
                        .withDepthAttachment(destinationView, OptionalDouble.empty())
                        .withRenderArea(new RenderPass.RenderArea(
                                0, 0, destinationWidth, destinationHeight
                        ));
                MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
                pass.setCompiledPipeline(pipeline);
                pass.bindTexture("sourceDepth", sourceView, sampler);
                pass.draw(Math.multiplyExact(destinationWidth, destinationHeight), 1, 0, 0);
                encoder.submitRenderPass();
            }
        }
    }

    private void ensureScratch(final int width, final int height, final int mipLevels) {
        if (scratch != null
                && scratchWidth == width
                && scratchHeight == height
                && scratchMipLevels == mipLevels) {
            return;
        }
        if (scratch != null) {
            scratch.close();
        }
        scratch = (MetalGpuTexture) device.createTexture(
                "iris-depth-mipmap-scratch",
                SCRATCH_USAGE,
                GpuFormat.D32_FLOAT,
                width,
                height,
                1,
                mipLevels
        );
        scratchWidth = width;
        scratchHeight = height;
        scratchMipLevels = mipLevels;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Metal depth mipmap generator is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (scratch != null) {
            scratch.close();
            scratch = null;
        }
        sampler.close();
        pipeline.close();
    }
}
