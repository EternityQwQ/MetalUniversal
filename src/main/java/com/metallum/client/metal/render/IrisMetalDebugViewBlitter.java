package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Sampled debug-view blit ({@code -Dmetallum.iris.debug.view=colortexN}).
 *
 * <p>The original implementation copied the selected colortex directly into
 * the main color target with a Metal texture blit. Metal blits do not convert
 * pixel formats, so copying MakeUp's {@code gaux3}/colortex6 ({@code R16F},
 * 2 bytes/pixel) into the wider main target is an illegal blit that froze the
 * device. This pass therefore <b>always</b> samples the source: a fullscreen
 * triangle with a nearest/clamp sampler into the main target, valid for every
 * color format, including R8/R16F/RGBA16F. The caller never falls back to a
 * raw texture copy (only a warning and no-op if this blitter cannot be built).
 *
 * <p>An {@code R16F} source reads {@code .rgb} = {@code (r, 0, 0)}, so the
 * debug view shows as the red channel; that is expected.
 *
 * <p>Compiled lazily and only when the debug switch is set, so release runs pay
 * nothing.
 */
@Environment(EnvType.CLIENT)
final class IrisMetalDebugViewBlitter implements AutoCloseable {
    private static final String SAMPLER_NAME = "metallum_debug_view";
    private static final String VERTEX_SOURCE = """
            #version 450
            layout(location = 0) out vec2 texCoord;

            void main() {
                vec2 positions[3] = vec2[](
                    vec2(-1.0, -1.0),
                    vec2( 3.0, -1.0),
                    vec2(-1.0,  3.0)
                );
                vec2 position = positions[gl_VertexIndex];
                gl_Position = vec4(position, 0.0, 1.0);
                texCoord = vec2(position.x * 0.5 + 0.5, 0.5 - position.y * 0.5);
            }
            """;
    private static final String FRAGMENT_SOURCE = """
            #version 450
            layout(location = 0) in vec2 texCoord;
            uniform sampler2D metallum_debug_view;
            layout(location = 0) out vec4 iris_fragColor;

            void main() {
                iris_fragColor = vec4(texture(metallum_debug_view, texCoord).rgb * __VIEW_GAIN__, 1.0);
            }
            """;

    private final MetalDevice device;
    private final MetalGpuSampler sampler;
    private final RenderPipeline pipeline;
    private final float gain;
    private boolean closed;

    IrisMetalDebugViewBlitter(
            final MetalDevice device,
            final int generation,
            final GpuFormat destinationFormat,
            final float gain
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.gain = gain > 0.0f && Float.isFinite(gain) ? gain : 1.0f;
        String base = "iris/gen" + generation + "/debug_view";
        Identifier vertexId = Identifier.fromNamespaceAndPath("metallum", base + "_v");
        Identifier fragmentId = Identifier.fromNamespaceAndPath("metallum", base + "_f");
        // The gain is baked into the fragment source instead of a uniform: the
        // blit pipeline has no uniform block and the value is fixed for the
        // process, so a literal is the only dependency-free option.
        // Float.toString may emit an uppercase exponent ("1.0E-5"); keep the
        // GLSL literal lowercase.
        String gainLiteral = Float.toString(this.gain).replace('E', 'e');
        String fragmentSource = FRAGMENT_SOURCE.replace("__VIEW_GAIN__", gainLiteral);
        ShaderSource source = (identifier, type) -> {
            if (identifier.equals(vertexId) && type == ShaderType.VERTEX) {
                return VERTEX_SOURCE;
            }
            if (identifier.equals(fragmentId) && type == ShaderType.FRAGMENT) {
                return fragmentSource;
            }
            throw new IllegalStateException(
                    "Unexpected fallback shader lookup while creating Iris debug-view blit: "
                            + identifier + " / " + type
            );
        };
        BindGroupLayout resources = BindGroupLayout.builder()
                .withSampler(SAMPLER_NAME)
                .build();
        this.pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metallum", base))
                .withVertexShader(vertexId)
                .withFragmentShader(fragmentId)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withBindGroupLayout(resources)
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), destinationFormat, ColorTargetState.WRITE_ALL
                ))
                .build();
        var compiled = device.precompilePipeline(this.pipeline, source);
        if (!compiled.isValid()) {
            throw new IllegalStateException("Metal debug-view render pipeline is invalid");
        }
        this.sampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.of(0.0)
        );
    }

    /**
     * Renders {@code source} over the top-left {@code width x height} region of
     * {@code destination}. A source smaller than that region is stretched; the
     * original blit could not scale at all, so this is strictly more useful for
     * the debug view.
     */
    void blit(
            final GpuTextureView source,
            final GpuTextureView destination,
            final int width,
            final int height
    ) {
        ensureOpen();
        if (source.isClosed() || destination.isClosed()) {
            throw new IllegalStateException("Debug view textures are closed");
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        MetalCommandEncoder encoder = this.device.createCommandEncoder();
        RenderPassDescriptor descriptor = RenderPassDescriptor
                .create(() -> "Iris debug view")
                .withColorAttachment(destination, Optional.empty())
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
        try {
            pass.setPipeline(this.pipeline);
            pass.bindTexture(SAMPLER_NAME, source, this.sampler);
            pass.draw(3, 1, 0, 0);
        } finally {
            encoder.submitRenderPass();
        }
    }

    /** Brightness multiplier baked into the fragment shader. */
    float gain() {
        return this.gain;
    }

    /** Readable gain for probe receipts: {@code 50} rather than {@code 50.0}. */
    static String formatGain(final float gain) {
        if (Float.isFinite(gain) && gain == Math.floor(gain) && Math.abs(gain) < 1.0e15f) {
            return Long.toString((long) gain);
        }
        return Float.toString(gain);
    }

    /**
     * Hazard classifier retained for the regression gate, not production
     * routing: the debug-view path always samples, but a pixel-size mismatch
     * is exactly the class of direct blit that used to fault the device
     * (MakeUp gaux3 R16F 2 B/px against a 4-byte main target).
     */
    static boolean needsSampledBlit(final int sourcePixelSize, final int destinationPixelSize) {
        return sourcePixelSize != destinationPixelSize;
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris debug-view blitter is closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.sampler.close();
    }
}
