package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mod-private compute pipeline for the Iris backend. Vanilla Blaze3D 26.2 has
 * no compute concept, so this class owns the whole chain for one GLSL compute
 * shader:
 *
 * <pre>
 * GLSL compute (explicit layout(binding=N))
 *   -> shaderc (Vulkan semantics, same family as Mojang's GlslCompiler)
 *   -> SPIRV-Cross MSL backend (decoration bindings preserved)
 *   -> runtime MSL compile -> MTLComputePipelineState
 * </pre>
 *
 * <p>Binding contract (also the contract Iris's {@code glBindBufferBase}/
 * {@code glBindImageTexture} indices are mapped onto): SPIR-V
 * {@code layout(binding=N)} is preserved verbatim — buffer-class resources
 * (UBO and SSBO share one namespace) become MSL {@code [[buffer(N)]]},
 * images/textures become {@code [[texture(N)]]}, samplers
 * {@code [[sampler(N)]]}. Callers must therefore keep buffer-class binding
 * indices unique among themselves, and texture-class indices unique among
 * themselves.</p>
 *
 * <p>The threadgroup size is reflected from the shader's
 * {@code local_size_x/y/z} (literal values only; specialization-constant
 * workgroup sizes are rejected) and used by
 * {@link MetalComputePass#dispatchGroups(int, int, int)}.</p>
 *
 * <p>Ownership: instances hold a retained {@code MTLComputePipelineState};
 * {@link #close()} defers the release to the destruction queue so in-flight
 * command buffers stay valid. Thread constraints follow the rest of the
 * backend: render-thread only.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalComputePipeline implements AutoCloseable {
    private static final Pattern KERNEL_ENTRY_PATTERN = Pattern.compile("\\bkernel\\s+\\w+\\s+(\\w+)\\s*\\(");
    /** Metal rejects {@code [[sampler(N)]]} with {@code N > 15}; guard compute MSL against it. */
    private static final Pattern SAMPLER_ATTRIBUTE_PATTERN = Pattern.compile("\\[\\[sampler\\((\\d+)\\)]]");
    private static final int MSL_VERSION_4_0 = 0x040000;

    private final MetalDevice device;
    private final String label;
    private final MemorySegment pipelineState;
    private final int threadgroupWidth;
    private final int threadgroupHeight;
    private final int threadgroupDepth;
    private final int maxTotalThreadsPerThreadgroup;
    private boolean closed;

    private MetalComputePipeline(
            final MetalDevice device,
            final String label,
            final MemorySegment pipelineState,
            final int threadgroupWidth,
            final int threadgroupHeight,
            final int threadgroupDepth
    ) {
        this.device = device;
        this.label = label;
        this.pipelineState = pipelineState;
        this.threadgroupWidth = threadgroupWidth;
        this.threadgroupHeight = threadgroupHeight;
        this.threadgroupDepth = threadgroupDepth;
        this.maxTotalThreadsPerThreadgroup =
                MetalNativeBridge.MTLComputePipelineState_maxTotalThreadsPerThreadgroup(pipelineState);
        int requested = threadgroupWidth * threadgroupHeight * threadgroupDepth;
        if (requested > this.maxTotalThreadsPerThreadgroup) {
            close();
            throw new IllegalStateException(
                    "Compute shader " + label + " declares local size "
                            + threadgroupWidth + "x" + threadgroupHeight + "x" + threadgroupDepth
                            + " (" + requested + " threads) but the device pipeline limit is "
                            + this.maxTotalThreadsPerThreadgroup
            );
        }
    }

    static MetalComputePipeline compileGlsl(final MetalDevice device, final String label, final String glslSource) {
        if (!MetalNativeBridge.supportsComputeAbi()) {
            throw new IllegalStateException(
                    "Native bridge lacks the compute ABI; rebuild libmetallum.dylib (gradle buildMacNative)"
            );
        }
        ByteBuffer spirv = compileGlslToSpirv(label, glslSource);
        MslKernel kernel = spirvToMslKernel(label, spirv);
        MemorySegment function = device.getOrCompileFunction(kernel.source(), kernel.entryPoint());
        if (MetalNativeBridge.isNullHandle(function)) {
            throw new IllegalStateException(
                    "Failed to compile MSL kernel for compute shader " + label
                            + " (entry " + kernel.entryPoint() + ")"
            );
        }
        MemorySegment pipelineState = MetalNativeBridge.MTLDevice_makeComputePipelineState(
                device.metalDeviceHandle(), function
        );
        if (MetalNativeBridge.isNullHandle(pipelineState)) {
            throw new IllegalStateException("Failed to create MTLComputePipelineState for " + label);
        }
        return new MetalComputePipeline(
                device,
                label,
                pipelineState,
                kernel.localSizeX(),
                kernel.localSizeY(),
                kernel.localSizeZ()
        );
    }

    private static ByteBuffer compileGlslToSpirv(final String label, final String glslSource) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0L || options == 0L) {
            throw new IllegalStateException("Failed to initialize shaderc for compute compilation");
        }
        try {
            Shaderc.shaderc_compile_options_set_target_env(
                    options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2
            );
            long result = Shaderc.shaderc_compile_into_spv(
                    compiler, glslSource, Shaderc.shaderc_glsl_compute_shader, label, "main", options
            );
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    String message = Shaderc.shaderc_result_get_error_message(result);
                    throw new IllegalStateException(
                            "Failed to compile compute shader " + label + ": " + message
                    );
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                if (bytes == null || bytes.remaining() < 20) {
                    throw new IllegalStateException("shaderc produced empty SPIR-V for " + label);
                }
                // Copy out so the shaderc result can be released eagerly.
                ByteBuffer copy = ByteBuffer.allocateDirect(bytes.remaining()).order(bytes.order());
                copy.put(bytes.duplicate());
                copy.flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static MslKernel spirvToMslKernel(final String label, final ByteBuffer spirvBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();
            int wordCount = spirvWords.remaining();

            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pContext), label, "spvc_context_create");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_context_parse_spirv(context, spirvWords, wordCount, pIr),
                        label, "spvc_context_parse_spirv"
                );
                PointerBuffer pCompiler = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_context_create_compiler(
                                context, Spvc.SPVC_BACKEND_MSL, pIr.get(0), Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler
                        ),
                        label, "spvc_context_create_compiler"
                );
                long compiler = pCompiler.get(0);

                PointerBuffer pOptions = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_compiler_create_compiler_options(compiler, pOptions),
                        label, "spvc_compiler_create_compiler_options"
                );
                long options = pOptions.get(0);
                checkSpvc(
                        Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS),
                        label, "set_uint(MSL_PLATFORM)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_4_0),
                        label, "set_uint(MSL_VERSION)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true),
                        label, "set_bool(MSL_ENABLE_DECORATION_BINDING)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_install_compiler_options(compiler, options),
                        label, "spvc_compiler_install_compiler_options"
                );

                int localSizeX = (int) Spvc.spvc_compiler_get_execution_mode_argument_by_index(
                        compiler, Spv.SpvExecutionModeLocalSize, 0
                );
                int localSizeY = (int) Spvc.spvc_compiler_get_execution_mode_argument_by_index(
                        compiler, Spv.SpvExecutionModeLocalSize, 1
                );
                int localSizeZ = (int) Spvc.spvc_compiler_get_execution_mode_argument_by_index(
                        compiler, Spv.SpvExecutionModeLocalSize, 2
                );

                PointerBuffer pSource = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_compile(compiler, pSource), label, "spvc_compiler_compile");
                String msl = MemoryUtil.memUTF8(pSource.get(0));
                Matcher samplerMatcher = SAMPLER_ATTRIBUTE_PATTERN.matcher(msl);
                while (samplerMatcher.find()) {
                    int samplerIndex = Integer.parseInt(samplerMatcher.group(1));
                    if (samplerIndex >= MetalCrossShaderCompiler.MAX_METAL_SAMPLERS_PER_STAGE) {
                        throw new IllegalStateException(
                                "Compute shader " + label + " uses [[sampler(" + samplerIndex + ")]], but Metal allows "
                                        + "at most " + MetalCrossShaderCompiler.MAX_METAL_SAMPLERS_PER_STAGE
                                        + " sampler slots per stage; compute MSL is not re-indexed"
                        );
                    }
                }
                Matcher matcher = KERNEL_ENTRY_PATTERN.matcher(msl);
                String entryPoint = matcher.find() ? matcher.group(1) : "main0";
                return new MslKernel(
                        msl,
                        entryPoint,
                        Math.max(1, localSizeX),
                        Math.max(1, localSizeY),
                        Math.max(1, localSizeZ)
                );
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void checkSpvc(final int result, final String label, final String stage) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new IllegalStateException(
                    "SPIRV-Cross error compiling compute shader " + label + " at " + stage + ": " + result
            );
        }
    }

    MemorySegment pipelineStateHandle() {
        if (closed) {
            throw new IllegalStateException("Compute pipeline " + label + " is closed");
        }
        return pipelineState;
    }

    String label() {
        return label;
    }

    int threadgroupWidth() {
        return threadgroupWidth;
    }

    int threadgroupHeight() {
        return threadgroupHeight;
    }

    int threadgroupDepth() {
        return threadgroupDepth;
    }

    int maxTotalThreadsPerThreadgroup() {
        return maxTotalThreadsPerThreadgroup;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        device.queueResourceRelease(pipelineState);
    }

    private record MslKernel(
            String source,
            String entryPoint,
            int localSizeX,
            int localSizeY,
            int localSizeZ
    ) {
    }
}
