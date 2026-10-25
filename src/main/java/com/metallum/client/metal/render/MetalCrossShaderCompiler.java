package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.GlslangBridge;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLVertexFormat;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout.VulkanBindGroupEntryType;
import com.mojang.blaze3d.vulkan.glsl.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslShaderInterfaceVar2;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
public final class MetalCrossShaderCompiler {
    private static final String IRIS_SSBO_DESCRIPTOR_PREFIX = "iris_ssbo/";
    /** Prefix Iris' vanilla-core transformer adds to physical vertex input names. */
    private static final String IRIS_INPUT_ALIAS_PREFIX = "iris_";
    private static final Set<String> BUILT_IN_UNIFORMS = Set.of("Projection", "Lighting", "Fog", "Globals");
    /** Sodium's stable per-region time buffer is a texel buffer, not a 2D sampler. */
    private static final GpuFormat SODIUM_SECTION_TIME_FORMAT = GpuFormat.R32_SINT;
    private static final int MSL_VERSION_4_0 = 0x040000;
    /**
     * Metal's per-stage hardware limit for sampler slots: MSL rejects
     * {@code [[sampler(N)]]} for any {@code N > 15}. Shaderpack programs can
     * declare far more combined image samplers than that (Complementary
     * Reimagined's deferred1 declares 23), so the shaderpack MSL path remaps
     * the <i>active</i> sampled images of each stage onto a compact
     * {@code 0..n-1} range. Canonical definition lives in the native-free
     * {@link ShaderpackSamplerIndexPlan}; this alias keeps the limit visible at
     * the call sites.
     */
    static final int MAX_METAL_SAMPLERS_PER_STAGE = ShaderpackSamplerIndexPlan.MAX_METAL_SAMPLERS_PER_STAGE;
    private static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern EXPLICIT_FRAGMENT_OUTPUT_PATTERN = Pattern.compile(
            "\\blayout\\s*\\(\\s*location\\s*=\\s*(\\d+)[^)]*\\)\\s*"
                    + "(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\s+)*"
                    + "out\\s+(?:lowp\\s+|mediump\\s+|highp\\s+)?\\w+\\s+(\\w+)\\b"
    );
    /**
     * MSL sampled-texture argument attributes. With
     * {@code MSL_ENABLE_DECORATION_BINDING}, SPIRV-Cross splits a combined
     * image sampler into a {@code texture2d} argument ({@code [[texture(N)]]})
     * and a separate {@code sampler} argument ({@code [[sampler(N)]]}), so the
     * emitted indices are the ground truth for the H1 binding-mismatch
     * instrumentation. Captures {@code (argumentName, index)}.
     */
    private static final Pattern MSL_TEXTURE_ATTRIBUTE = Pattern.compile(
            "([A-Za-z_]\\w*)\\s*(?:\\[\\d+\\])?\\s*\\[\\[texture\\((\\d+)\\)\\]\\]"
    );
    private static final Pattern MSL_SAMPLER_ATTRIBUTE = Pattern.compile(
            "([A-Za-z_]\\w*)\\s*(?:\\[\\d+\\])?\\s*\\[\\[sampler\\((\\d+)\\)\\]\\]"
    );

    /**
     * 在 iOS 上，Amethyst 启动器捆绑的 libMoltenVK.dylib 内部静态链接了 SPIRV-Cross，
     * 但只编译了 Vulkan 后端（MoltenVK 自己用 C++ API 做 SPIR-V→MSL 转换，不需要 C API
     * 的 MSL 后端）。LWJGL 在 iOS 上没有自己的 iOS natives，回退到 dlsym(RTLD_DEFAULT,
     * ...) 时找到的是 MoltenVK 的精简版符号，导致 spvc_context_create_compiler(
     * SPVC_BACKEND_MSL) 返回 -4 "Invalid backend"。
     *
     * 修复：在 LWJGL 的 Spvc 类被首次加载之前，从 jar 中抽取完整版 libspvc.dylib
     * （带 MSL 后端），用 System.load 加载（经 Amethyst 的 hooked dlopen），然后设置
     * Configuration.SPVC_LIBRARY_NAME 指向该路径。LWJGL 加载时会用该绝对路径直接
     * dlopen，dlsym(handle, ...) 只查询该镜像的符号，不会被 MoltenVK 抢占。
     *
     * <p><b>关键：必须在 Spvc 类首次初始化前调用。</b> Spvc.SPVC 是 static final 字段，
     * 类初始化时通过 Library.loadNative(...) 读取 Configuration.SPVC_LIBRARY_NAME
     * 并缓存。一旦 Spvc 类被加载，后续修改 Configuration.SPVC_LIBRARY_NAME 无效。
     * MetalBackend.createDevice 已经在最开头调用了 ensureSpvcLibraryConfigured，
     * 此处的静态块作为兜底，防止其他路径在 MetalBackend 之前触发 Spvc 类加载。
     */
    static {
        MetalNativeBridge.ensureSpvcLibraryConfigured();
    }

    private MetalCrossShaderCompiler() {
    }

    private enum RasterStorageKind {
        BUFFER,
        IMAGE
    }

    private record RasterStorageUse(
            RasterStorageKind kind,
            String resourceName,
            String descriptorName,
            int logicalBinding,
            int stageMask,
            ByteBuffer spirv,
            int bindingWordOffset
    ) {
    }

    private record RasterStorageResource(
            RasterStorageKind kind,
            String descriptorName,
            int physicalBinding,
            int stageMask
    ) {
    }

    static String storageBufferDescriptorName(final int logicalBinding, final String resourceName) {
        if (logicalBinding < 0) {
            throw new IllegalArgumentException("SSBO binding must be non-negative: " + logicalBinding);
        }
        return IRIS_SSBO_DESCRIPTOR_PREFIX + logicalBinding + '/' + resourceName;
    }

    static int storageBufferLogicalBinding(final String descriptorName) {
        if (!descriptorName.startsWith(IRIS_SSBO_DESCRIPTOR_PREFIX)) {
            return -1;
        }
        int start = IRIS_SSBO_DESCRIPTOR_PREFIX.length();
        int end = descriptorName.indexOf('/', start);
        if (end < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(descriptorName.substring(start, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static MetalCompiledRenderPipeline compile(final MetalDevice device, final RenderPipeline pipeline, final ShaderSource shaderSource) {
        try {
            IntermediaryShaderModule vertexSpirv = device.getOrCompileShader(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), shaderSource);
            IntermediaryShaderModule fragmentSpirv = device.getOrCompileShader(pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), shaderSource);
            if (vertexSpirv == IntermediaryShaderModule.INVALID || fragmentSpirv == IntermediaryShaderModule.INVALID) {
                throw new IllegalStateException(
                        "Couldn't compile shader for pipeline " + pipeline.getLocation()
                );
            }

            List<VulkanBindGroupLayout.Entry> layoutEntries = new ArrayList<>();
            addToBindGroup(layoutEntries, vertexSpirv, pipeline);
            addToBindGroup(layoutEntries, fragmentSpirv, pipeline);
            List<RasterStorageResource> storageResources = rebindRasterStorageResources(
                    vertexSpirv, fragmentSpirv, layoutEntries.size()
            );
            List<String> vertexOutputs = extractVariableNames(vertexSpirv.outputs());

            VertexInputLayout vertexInputs = vertexInputLayout(pipeline, vertexSpirv.inputs());
            vertexSpirv.rebind(tolerateUnprovidedInputs(vertexInputs.names(), vertexSpirv.inputs()), layoutEntries);
            applyVertexInputLocations(vertexSpirv, vertexInputs);
            List<GenericVertexInput> genericVertexInputs = genericVertexInputs(
                    vertexSpirv.spirv(), vertexInputs.names()
            );
            boolean enablePointSize = pipeline.getPrimitiveTopology() == com.mojang.blaze3d.PrimitiveTopology.POINTS;
            MslShader vertexMsl = spirvToMsl(
                    vertexSpirv.spirv(), layoutEntries.size() + storageResources.size(),
                    vertexInputs.formats(), enablePointSize
            );

            fragmentSpirv.rebind(tolerateUnprovidedInputs(vertexOutputs, fragmentSpirv.inputs()), layoutEntries);
            String fragmentSource = shaderSource.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
            MslShader fragmentMsl = spirvToMsl(
                    fragmentSpirv.spirv(),
                    layoutEntries.size() + storageResources.size(),
                    Map.of(),
                    true,
                    explicitFragmentOutputLocations(fragmentSource)
            );
            validateFragmentOutputSignature(pipeline, fragmentMsl.stageOutputLocations());

            String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
            String fragmentEntryPoint = extractEntryPoint(fragmentMsl.source(), FRAGMENT_ENTRY_PATTERN, "main0");
            List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(
                    layoutEntries, storageResources, vertexMsl, fragmentMsl, false
            );
            dumpSampledImageBindings(
                    pipeline.getLocation().toString(), resources, vertexMsl, fragmentMsl
            );
            return new MetalCompiledRenderPipeline(
                    device,
                    pipeline,
                    vertexMsl.source(),
                    fragmentMsl.source(),
                    vertexEntryPoint,
                    fragmentEntryPoint,
                    resources,
                    genericVertexInputs
            );
        } catch (ShaderCompileException e) {
            throw new IllegalStateException("Failed to compile Metal cross shader for pipeline " + pipeline.getLocation(), e);
        }
    }

    /**
     * Compiles a shaderpack (Iris light-shader) GLSL pair to a Metal pipeline.
     *
     * <p>Unlike {@link #compile(MetalDevice, RenderPipeline, ShaderSource)}, this
     * entry point bypasses the blaze3d {@code device.getOrCompileShader} SPIR-V
     * cache and compiles GLSL directly via {@link GlslangBridge} (GLSL&#8594;SPIR-V),
     * then reuses the existing SPIRV-Cross SPIR-V&#8594;MSL path ({@link #spirvToMsl}).
     *
     * <p>The shaderpack path applies the same vertex-input contract as the
     * vanilla path: Iris's OpenGL-style attribute names are mapped to the
     * physical {@link VertexFormat} order, while unprovided inputs use the
     * existing generic-attribute default buffer.
     *
     * <p>Resource bindings are reflected from the compiled vertex/fragment SPIR-V
     * via SPIRV-Cross ({@link #reflectShaderpackResources}), merged into a single
     * bind-group entry list ({@link #buildShaderpackBindGroupEntries}), and fed
     * to {@link #spirvToMsl} (as the push-constant binding slot, mirroring the
     * vanilla {@code layoutEntries.size()}) and {@link #buildResourceBindings}.
     * This fills the otherwise-empty {@code resources()} of the resulting
     * pipeline with the shaderpack program's real uniform/sampler declarations.
     *
     * @param device                   the Metal device.
     * @param name                     logical name used in error messages.
     * @param vertexGlsl               vertex GLSL source (must declare its own {@code #version}).
     * @param fragmentGlsl             fragment GLSL source (must declare its own {@code #version}).
     * @param defines                  optional preprocessor defines forwarded to glslang.
     * @param vertexAttributeFormats   vertex attribute formats for integer-input
     *                                 conversion (may be empty).
     * @param enablePointSize          whether to emit Metal {@code [[point_size]]}
     *                                 (POINTS topology only).
     * @param cull                     back-face cull enabled.
     * @param polygonMode              fill / wireframe.
     * @param primitiveTopology        primitive topology.
     * @param vertexFormatBindings     vertex format bindings.
     * @param depthStencilState        depth/stencil state (nullable).
     * @param colorTargets             compact physical color-target states in
     *                                 the same order as Iris DRAWBUFFERS.
     * @param glDepthEmulation         whether to wrap the vertex stage with the
     *                                 GL NDC&rarr;depth viewport transform for
     *                                 shadow caster programs (see
     *                                 {@link IrisMetalShadowDepthFix}).
     * @return the compiled Metal render pipeline.
     * @throws ShaderCompileException if GLSL&#8594;SPIR-V or SPIR-V&#8594;MSL fails.
     */
    static MetalCompiledRenderPipeline compileShaderpack(
            final MetalDevice device,
            final String name,
            final String vertexGlsl,
            final String fragmentGlsl,
            final String defines,
            final Map<String, GpuFormat> vertexAttributeFormats,
            final boolean enablePointSize,
            final boolean cull,
            final PolygonMode polygonMode,
            final PrimitiveTopology primitiveTopology,
            final VertexFormat[] vertexFormatBindings,
            final DepthStencilState depthStencilState,
            final ColorTargetState[] colorTargets,
            final boolean glDepthEmulation
    ) throws ShaderCompileException {
        final int[] vertexSpvWords;
        final int[] fragmentSpvWords;
        final String vertexSource = IrisMetalShadowDepthFix.vertexSource(vertexGlsl, glDepthEmulation);
        final String vertexDefines = IrisMetalShadowDepthFix.vertexDefines(defines, glDepthEmulation);
        try {
            vertexSpvWords = GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.VERTEX, vertexSource, vertexDefines);
        } catch (GlslangBridge.ShaderCompileException e) {
            throw wrapGlslangError("Failed to compile shaderpack vertex shader '" + name + "'", e);
        }
        try {
            // The wrapper must never leak into the fragment stage: it uses the
            // shared define only for the vertex compile above.
            fragmentSpvWords = GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.FRAGMENT, fragmentGlsl, defines);
        } catch (GlslangBridge.ShaderCompileException e) {
            throw wrapGlslangError("Failed to compile shaderpack fragment shader '" + name + "'", e);
        }

        // 反射 vertex/fragment SPIR-V，构造 shaderpack 真实资源绑定。shaderpack 路径
        // 没有 IntermediaryShaderModule，故直接用 SPIRV-Cross 反射 glslang 产出的原始
        // SPIR-V（替代原先由调用方传入的空 bindGroupEntries）。pushConstantBinding 必须
        // 与反射后条目数一致，使 push-constant 落在最后一个资源槽之后（与 vanilla
        // layoutEntries.size() 语义对齐）。
        final ByteBuffer vertexSpirv = spirvWordsToByteBuffer(vertexSpvWords);
        final ByteBuffer fragmentSpirv = spirvWordsToByteBuffer(fragmentSpvWords);
        final ShaderpackReflection vertexReflection = reflectShaderpackResources(vertexSpirv);
        final ShaderpackReflection fragmentReflection = reflectShaderpackResources(fragmentSpirv);
        rejectSeparateSamplers(name, vertexReflection, fragmentReflection);
        final List<VulkanBindGroupLayout.Entry> reflectedEntries = buildShaderpackBindGroupEntries(vertexReflection, fragmentReflection);
        final List<RasterStorageResource> storageResources = rebindRasterStorageResources(
                vertexSpirv, fragmentSpirv, reflectedEntries.size()
        );
        final Map<String, Integer> resourceBindings = shaderpackResourceBindings(reflectedEntries);
        final List<String> physicalInputNames = vertexInputNames(vertexFormatBindings);

        final int pushConstantBinding = reflectedEntries.size() + storageResources.size();
        final MslShader vertexMsl = spirvToMsl(
                vertexSpirv, pushConstantBinding,
                vertexAttributeFormats, enablePointSize, Map.of(), resourceBindings, physicalInputNames,
                name + " vertex"
        );
        final MslShader fragmentMsl = spirvToMsl(
                fragmentSpirv, pushConstantBinding,
                Map.of(), true, Map.of(), resourceBindings, null,
                name + " fragment"
        );
        validateFragmentOutputSignature(name, colorTargets, fragmentMsl.stageOutputLocations());

        final String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
        final String fragmentEntryPoint = extractEntryPoint(fragmentMsl.source(), FRAGMENT_ENTRY_PATTERN, "main0");
        final List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(
                reflectedEntries, storageResources, vertexMsl, fragmentMsl, true
        );
        dumpSampledImageBindings(name, resources, vertexMsl, fragmentMsl);

        return new MetalCompiledRenderPipeline(
                device,
                name,
                vertexMsl.source(),
                fragmentMsl.source(),
                vertexEntryPoint,
                fragmentEntryPoint,
                resources,
                cull,
                polygonMode,
                primitiveTopology,
                vertexFormatBindings,
                depthStencilState,
                colorTargets,
                vertexMsl.genericVertexInputs()
        );
    }

    /**
     * Dry-compile an Iris shaderpack program through the full
     * glslang&#8594;SPIRV-Cross&#8594;MSL pipeline WITHOUT creating a
     * {@link MetalCompiledRenderPipeline} or requiring a {@link MetalDevice}.
     *
     * <p>This entry point validates that a shaderpack program's GLSL (already
     * patched and {@code #include}-expanded by Iris's {@code TransformPatcher})
     * can be cross-compiled to MSL. It is the natural progression from
     * {@link #compileShaderpack}: same GLSL&#8594;MSL pipeline, but decoupled
     * from {@code MetalDevice} so it can be invoked from Iris
     * {@code ShaderCreator.link} interception before a Metal pipeline state
     * object is assembled.
     *
     * <p><b>Limitations.</b>
     * <ul>
     *   <li>Geometry and tessellation (tessControl/tessEval) stages are accepted
     *       for API symmetry with {@code ShaderCreator.link} but are <b>not</b>
     *       compiled: the current Metal pipeline is vertex+fragment only. A
     *       warning is logged when any non-null non-vertex/fragment stage is
     *       present.</li>
     *   <li>Vertex attribute integer&#8594;MSL conversion
     *       ({@link #registerIntegerInputConversions}) is skipped (empty
     *       attribute-format map); the dry-compiled MSL therefore uses default
     *       vertex input declarations. Full conversion is applied in
     *       {@link #compileShaderpack} once the {@code VertexFormat} bindings
     *       are known.</li>
     *   <li>The push-constant binding slot defaults to {@code 0} (no bind-group
     *       entries); {@link #compileShaderpack} derives it from the size of the
     *       SPIR-V-reflected bind-group entry list.</li>
     *   <li>{@code enablePointSize} is {@code false} for the vertex stage in
     *       dry-compile (the actual topology is not known here).</li>
     * </ul>
     *
     * @param name             logical program name.
     * @param vertexGlsl       vertex GLSL source (must be non-null and declare
     *                         its own {@code #version}).
     * @param geometryGlsl     geometry GLSL source (nullable; ignored with a
     *                         warning if non-null).
     * @param tessControlGlsl  tessellation-control GLSL source (nullable;
     *                         ignored with a warning if non-null).
     * @param tessEvalGlsl     tessellation-evaluation GLSL source (nullable;
     *                         ignored with a warning if non-null).
     * @param fragmentGlsl     fragment GLSL source (must be non-null and declare
     *                         its own {@code #version}).
     * @param defines          optional preprocessor defines forwarded to
     *                         glslang (may be {@code null}).
     * @return the dry-compiled MSL result.
     * @throws ShaderCompileException if GLSL&#8594;SPIR-V or SPIR-V&#8594;MSL
     *                               fails; the exception message includes the
     *                               glslang info log.
     */
    public static ShaderpackMslResult tryCompileShaderpackMsl(
            final String name,
            final @Nullable String vertexGlsl,
            final @Nullable String geometryGlsl,
            final @Nullable String tessControlGlsl,
            final @Nullable String tessEvalGlsl,
            final @Nullable String fragmentGlsl,
            final @Nullable String defines
    ) throws ShaderCompileException {
        return tryCompileShaderpackMsl(
                name, vertexGlsl, geometryGlsl, tessControlGlsl, tessEvalGlsl,
                fragmentGlsl, defines, null
        );
    }

    /**
     * Device-free dry compile that additionally maps the vertex stage inputs to
     * explicit MSL {@code [[attribute(N)]]} locations, mirroring the production
     * path's {@code physicalInputNames} wiring ({@link #compileShaderpack}).
     *
     * <p>Used by tests to verify the entity attribute mapping without a Metal
     * device.</p>
     *
     * @param name               program name (diagnostics only).
     * @param vertexGlsl         vertex GLSL source (non-null, declares {@code #version}).
     * @param fragmentGlsl       fragment GLSL source (non-null, declares {@code #version}).
     * @param defines            optional preprocessor defines (may be {@code null}).
     * @param physicalInputNames vertex input names in physical attribute order,
     *                           as produced by {@link #vertexInputNames(VertexFormat[])}.
     * @return the dry-compiled MSL result.
     * @throws ShaderCompileException if GLSL&#8594;SPIR-V or SPIR-V&#8594;MSL fails.
     */
    static ShaderpackMslResult tryCompileShaderpackMsl(
            final String name,
            final String vertexGlsl,
            final String fragmentGlsl,
            final @Nullable String defines,
            final @Nullable List<String> physicalInputNames
    ) throws ShaderCompileException {
        return tryCompileShaderpackMsl(
                name, vertexGlsl, null, null, null, fragmentGlsl, defines, physicalInputNames
        );
    }

    private static ShaderpackMslResult tryCompileShaderpackMsl(
            final String name,
            final @Nullable String vertexGlsl,
            final @Nullable String geometryGlsl,
            final @Nullable String tessControlGlsl,
            final @Nullable String tessEvalGlsl,
            final @Nullable String fragmentGlsl,
            final @Nullable String defines,
            final @Nullable List<String> physicalInputNames
    ) throws ShaderCompileException {
        if (vertexGlsl == null || fragmentGlsl == null) {
            throw new ShaderCompileException(
                    "Cannot dry-compile shaderpack program '" + name + "': vertex or fragment GLSL is null "
                            + "(vertex=" + (vertexGlsl == null ? "null" : "present")
                            + ", fragment=" + (fragmentGlsl == null ? "null" : "present") + ")."
            );
        }
        if (geometryGlsl != null || tessControlGlsl != null || tessEvalGlsl != null) {
            Metallum.LOGGER.warn(
                    "[MetalUniversal/Iris] Shaderpack program '{}' declares geometry/tessellation stages, "
                            + "which have no Metal equivalent in the current vertex+fragment pipeline; "
                            + "they are skipped by tryCompileShaderpackMsl.",
                    name
            );
        }

        final int[] vertexSpvWords;
        final int[] fragmentSpvWords;
        try {
            vertexSpvWords = GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.VERTEX, vertexGlsl, defines);
        } catch (GlslangBridge.ShaderCompileException e) {
            throw wrapGlslangError("Failed to dry-compile shaderpack vertex shader '" + name + "'", e);
        }
        try {
            fragmentSpvWords = GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.FRAGMENT, fragmentGlsl, defines);
        } catch (GlslangBridge.ShaderCompileException e) {
            throw wrapGlslangError("Failed to dry-compile shaderpack fragment shader '" + name + "'", e);
        }

        final ShaderpackReflection vertexReflection =
                reflectShaderpackResources(spirvWordsToByteBuffer(vertexSpvWords));
        final ShaderpackReflection fragmentReflection =
                reflectShaderpackResources(spirvWordsToByteBuffer(fragmentSpvWords));
        rejectSeparateSamplers(name, vertexReflection, fragmentReflection);
        final List<VulkanBindGroupLayout.Entry> entries =
                buildShaderpackBindGroupEntries(vertexReflection, fragmentReflection);
        final Map<String, Integer> resourceBindings = shaderpackResourceBindings(entries);
        final int pushConstantBinding = entries.size();
        final MslShader vertexMsl = spirvToMsl(
                spirvWordsToByteBuffer(vertexSpvWords), pushConstantBinding,
                Map.of(), false, Map.of(), resourceBindings, physicalInputNames,
                name + " vertex"
        );
        final MslShader fragmentMsl = spirvToMsl(
                spirvWordsToByteBuffer(fragmentSpvWords), pushConstantBinding,
                Map.of(), true, Map.of(), resourceBindings, null,
                name + " fragment"
        );

        final String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
        final String fragmentEntryPoint = extractEntryPoint(fragmentMsl.source(), FRAGMENT_ENTRY_PATTERN, "main0");

        final ShaderpackMslResult result = new ShaderpackMslResult(
                name, vertexMsl.source(), fragmentMsl.source(), vertexEntryPoint, fragmentEntryPoint,
                vertexMsl.sampledImageIndices(), fragmentMsl.sampledImageIndices()
        );
        return result;
    }

    /**
     * Cache of shaderpack programs whose Metal render pipeline state object
     * ({@link MetalCompiledRenderPipeline}) has been successfully constructed,
     * keyed by program name. Populated by
     * {@link #compileShaderpackPipeline} and read back by
     * {@link #getCachedShaderpackPipeline}.
     */
    private static final Map<String, MetalCompiledRenderPipeline> SHADERPACK_PIPELINE_CACHE = new ConcurrentHashMap<>();

    /**
     * Constructs a {@link MetalCompiledRenderPipeline} for an Iris shaderpack
     * program, using the active {@link MetalDevice} from
     * {@link MetalDeviceRegistry} and default pipeline states.
     *
     * <p>This is the public entry point called from the Iris intercept mixin
     * ({@code ShaderCreatorMixin}) when the Metal backend is active. It
     * performs the full GLSL&#8594;SPIR-V&#8594;MSL&#8594;pipeline construction
     * in one shot, caching the resulting pipeline under {@code name} for later
     * retrieval by the render dispatch path.
     *
     * <p><b>Default pipeline states.</b> Because Iris manages framebuffers,
     * depth/stencil, blend, and cull states outside of
     * {@code ShaderCreator.link}, this method uses conservative defaults:
     * <ul>
     *   <li>{@code cull = false} (shaderpacks manage their own culling)</li>
     *   <li>{@code polygonMode = FILL}</li>
     *   <li>{@code primitiveTopology = TRIANGLES}</li>
     *   <li>{@code depthStencilState = null} (Iris manages depth via
     *       framebuffers)</li>
     *   <li>{@code colorTarget = null} (Iris manages color attachments via
     *       framebuffers)</li>
     *   <li>{@code bindGroupEntries} are reflected from the compiled vertex/fragment
     *       SPIR-V inside {@link #compileShaderpack} (uniform buffers and sampled
     *       images/samplers declared by the shaderpack are mapped to bind-group
     *       entries); the push-constant binding slot follows the reflected entry
     *       count</li>
     * </ul>
     *
     * <p><b>Limitations.</b> The returned pipeline compiles and links the MSL
     * shaders into a Metal pipeline state object, and resource <i>names and
     * types</i> are now reflected from SPIR-V into the pipeline's resource list.
     * The runtime mapping of Iris sampler/uniform objects to Metal bind-group
     * slots is still forthcoming. Rendering with this pipeline will require that
     * bind-group mapping step.
     *
     * @param name             logical program name (also the cache key).
     * @param vertexGlsl       vertex GLSL source (must be non-null).
     * @param fragmentGlsl     fragment GLSL source (must be non-null).
     * @param defines          optional preprocessor defines (may be null).
     * @param vertexFormat     the Iris vertex format (drives vertex attribute
     *                         integer conversion and the Metal vertex
     *                         descriptor).
     * @param enablePointSize  whether to emit Metal {@code [[point_size]]}
     *                         (true for POINTS topology programs).
     * @return {@code true} if the pipeline was successfully constructed and
     *         cached; {@code false} if no Metal device is active.
     * @throws ShaderCompileException if GLSL&#8594;SPIR-V, SPIR-V&#8594;MSL,
     *                               or Metal pipeline state creation fails.
     */
    public static boolean compileShaderpackPipeline(
            final String name,
            final String vertexGlsl,
            final String fragmentGlsl,
            final @Nullable String defines,
            final VertexFormat vertexFormat,
            final boolean enablePointSize
    ) throws ShaderCompileException {
        final MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            return false;
        }

        final Map<String, GpuFormat> vertexAttributeFormats = new LinkedHashMap<>();
        for (VertexFormatElement element : vertexFormat.getElements()) {
            vertexAttributeFormats.putIfAbsent(element.name(), element.format());
        }

        final MetalCompiledRenderPipeline pipeline = compileShaderpack(
                device,
                name,
                vertexGlsl,
                fragmentGlsl,
                defines,
                vertexAttributeFormats,
                enablePointSize,
                false,
                PolygonMode.FILL,
                PrimitiveTopology.TRIANGLES,
                new VertexFormat[]{vertexFormat},
                null,
                new ColorTargetState[]{ColorTargetState.DEFAULT},
                false
        );
        SHADERPACK_PIPELINE_CACHE.put(name, pipeline);
        return true;
    }

    /**
     * Retrieves a cached shaderpack Metal render pipeline by program name.
     * Intended for internal use by the Metal render dispatch path (within the
     * {@code com.metallum.client.metal.render} package).
     *
     * @param name the program name.
     * @return the cached pipeline, or {@code null}.
     */
    static @Nullable MetalCompiledRenderPipeline getCachedShaderpackPipeline(final String name) {
        return SHADERPACK_PIPELINE_CACHE.get(name);
    }

    /**
     * Result of a successful shaderpack dry-compile: the program name, the
     * compiled vertex/fragment MSL sources, their entry-point function names,
     * and the per-stage compact sampled-image index plans the runtime binding
     * path uses to match {@code [[texture(N)]]}/{@code [[sampler(N)]]}
     * attributes.
     */
    public record ShaderpackMslResult(
            String name,
            String vertexMsl,
            String fragmentMsl,
            String vertexEntryPoint,
            String fragmentEntryPoint,
            Map<String, Integer> vertexSampledImageIndices,
            Map<String, Integer> fragmentSampledImageIndices
    ) {
    }

    /**
     * Wraps a SPIR-V word array into a direct {@link ByteBuffer} in
     * {@link ByteOrder#LITTLE_ENDIAN} order. SPIR-V is a little-endian word
     * stream; the resulting buffer's position is left at {@code 0} so that
     * {@code asIntBuffer()} views (used by {@link #spirvToMsl}) start at the
     * first word. The view buffer advances its own position independently of
     * this buffer's position.
     */
    private static ByteBuffer spirvWordsToByteBuffer(final int[] words) {
        // LWJGL's SPIRV-Cross bindings pass the IntBuffer address directly to
        // native code. A heap buffer has no stable native address and turns
        // into a near-null pointer at spvc_context_parse_spirv.
        final ByteBuffer buffer = ByteBuffer.allocateDirect(words.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(words);
        return buffer;
    }

    /**
     * Rewraps a {@link GlslangBridge.ShaderCompileException} (an unchecked
     * {@code RuntimeException} from the native glslang bridge) as a blaze3d
     * {@link ShaderCompileException}, preserving the original failure as the
     * cause via {@code initCause}. The blaze3d type is the one already thrown
     * throughout this class and expected by callers of {@link #compile}.
     */
    private static ShaderCompileException wrapGlslangError(final String message, final GlslangBridge.ShaderCompileException cause) {
        final ShaderCompileException wrapped = new ShaderCompileException(message);
        wrapped.initCause(cause);
        return wrapped;
    }

    private static void addToBindGroup(
            final List<VulkanBindGroupLayout.Entry> entries,
            final IntermediaryShaderModule shader,
            final RenderPipeline pipeline
    ) throws ShaderCompileException {
        List<UniformDescription> uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
        List<String> samplers = BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());
        for (SpvUniformBuffer buffer : shader.uniformBuffers()) {
            String name = buffer.name();
            if (findUniform(uniforms, name) == null && !BUILT_IN_UNIFORMS.contains(name)) {
                throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
            }
            addBindingIfAbsent(entries, VulkanBindGroupEntryType.UNIFORM_BUFFER, name, null);
        }

        for (SpvSampler sampler : shader.samplers()) {
            String name = sampler.name();
            UniformDescription uniform = findUniform(uniforms, name);
            int dimensions = sampler.dimensions();
            if (uniform != null) {
                if (dimensions != Spv.SpvDimBuffer) {
                    throw new ShaderCompileException("UTB (" + name + ") must have type of SpvDimBuffer");
                }
                addBindingIfAbsent(entries, VulkanBindGroupEntryType.TEXEL_BUFFER, name, uniform.gpuFormat());
            } else {
                if (!samplers.contains(name)) {
                    throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
                }
                if (dimensions != Spv.SpvDim2D && dimensions != Spv.SpvDimCube) {
                    throw new ShaderCompileException("Sampled texture (" + name + ") must have type of SpvDim2D or SpvDimCube");
                }
                addBindingIfAbsent(entries, VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null);
            }
        }
    }

    /**
     * Mojang's intermediary reflection exposes UBOs and sampled images, while
     * Iris raster programs may also declare SSBOs and storage images. Reflect
     * those declarations from the same SPIR-V and move them after the regular
     * bind-group entries before SPIRV-Cross emits MSL.
     */
    private static List<RasterStorageResource> rebindRasterStorageResources(
            final IntermediaryShaderModule vertex,
            final IntermediaryShaderModule fragment,
            final int firstPhysicalBinding
    ) throws ShaderCompileException {
        List<RasterStorageUse> uses = new ArrayList<>();
        collectRasterStorageUses(vertex.spirv(), MetalCompiledRenderPipeline.STAGE_VERTEX, uses);
        collectRasterStorageUses(fragment.spirv(), MetalCompiledRenderPipeline.STAGE_FRAGMENT, uses);
        return rebindRasterStorageResources(uses, firstPhysicalBinding);
    }

    private static List<RasterStorageResource> rebindRasterStorageResources(
            final ByteBuffer vertex,
            final ByteBuffer fragment,
            final int firstPhysicalBinding
    ) throws ShaderCompileException {
        List<RasterStorageUse> uses = new ArrayList<>();
        collectRasterStorageUses(vertex, MetalCompiledRenderPipeline.STAGE_VERTEX, uses);
        collectRasterStorageUses(fragment, MetalCompiledRenderPipeline.STAGE_FRAGMENT, uses);
        return rebindRasterStorageResources(uses, firstPhysicalBinding);
    }

    private static List<RasterStorageResource> rebindRasterStorageResources(
            final List<RasterStorageUse> uses,
            final int firstPhysicalBinding
    ) throws ShaderCompileException {
        if (firstPhysicalBinding < 0) {
            throw new IllegalArgumentException("First raster storage binding must be non-negative");
        }
        if (uses.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> physicalByDescriptor = new LinkedHashMap<>();
        Map<String, Integer> stagesByDescriptor = new LinkedHashMap<>();
        Map<String, RasterStorageKind> kindByDescriptor = new LinkedHashMap<>();
        for (RasterStorageUse use : uses) {
            int physical = physicalByDescriptor.computeIfAbsent(
                    use.descriptorName(), ignored -> firstPhysicalBinding + physicalByDescriptor.size()
            );
            RasterStorageKind previousKind = kindByDescriptor.putIfAbsent(use.descriptorName(), use.kind());
            if (previousKind != null && previousKind != use.kind()) {
                throw new ShaderCompileException(
                        "Raster resource '" + use.descriptorName() + "' is both "
                                + previousKind + " and " + use.kind()
                );
            }
            stagesByDescriptor.merge(use.descriptorName(), use.stageMask(), (left, right) -> left | right);
            use.spirv().asIntBuffer().put(use.bindingWordOffset(), physical);
        }

        List<RasterStorageResource> resources = new ArrayList<>(physicalByDescriptor.size());
        physicalByDescriptor.forEach((descriptor, physical) -> resources.add(new RasterStorageResource(
                kindByDescriptor.get(descriptor), descriptor, physical, stagesByDescriptor.get(descriptor)
        )));
        return List.copyOf(resources);
    }

    private static void collectRasterStorageUses(
            final ByteBuffer spirv,
            final int stageMask,
            final List<RasterStorageUse> output
    ) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = spirv.asIntBuffer();
            PointerBuffer pointer = stack.callocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pointer), "spvc_context_create(raster storage)");
            long context = pointer.get(0);
            try {
                checkSpvc(
                        Spvc.spvc_context_parse_spirv(context, words, words.remaining(), pointer),
                        "spvc_context_parse_spirv(raster storage)"
                );
                long ir = pointer.get(0);
                checkSpvc(
                        Spvc.spvc_context_create_compiler(
                                context, Spvc.SPVC_BACKEND_NONE, ir,
                                Spvc.SPVC_CAPTURE_MODE_COPY, pointer
                        ),
                        "spvc_context_create_compiler(raster storage)"
                );
                long compiler = pointer.get(0);
                checkSpvc(
                        Spvc.spvc_compiler_create_shader_resources(compiler, pointer),
                        "spvc_compiler_create_shader_resources(raster storage)"
                );
                long resources = pointer.get(0);
                collectRasterStorageType(
                        stack, compiler, resources, spirv, stageMask,
                        Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, RasterStorageKind.BUFFER, output
                );
                collectRasterStorageType(
                        stack, compiler, resources, spirv, stageMask,
                        Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, RasterStorageKind.IMAGE, output
                );
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void collectRasterStorageType(
            final MemoryStack stack,
            final long compiler,
            final long resources,
            final ByteBuffer spirv,
            final int stageMask,
            final int resourceType,
            final RasterStorageKind kind,
            final List<RasterStorageUse> output
    ) throws ShaderCompileException {
        PointerBuffer listPointer = stack.callocPointer(1);
        PointerBuffer countPointer = stack.callocPointer(1);
        checkSpvc(
                Spvc.spvc_resources_get_resource_list_for_type(
                        resources, resourceType, listPointer, countPointer
                ),
                "spvc_resources_get_resource_list_for_type(raster storage " + resourceType + ')'
        );
        int count = Math.toIntExact(countPointer.get(0));
        if (count == 0) {
            return;
        }
        SpvcReflectedResource.Buffer reflected = SpvcReflectedResource.create(listPointer.get(0), count);
        IntBuffer offset = stack.callocInt(1);
        for (SpvcReflectedResource resource : reflected) {
            if (!Spvc.spvc_compiler_has_decoration(compiler, resource.id(), Spv.SpvDecorationBinding)) {
                throw new ShaderCompileException(
                        "Raster storage resource '" + resource.nameString() + "' has no binding"
                );
            }
            if (!Spvc.spvc_compiler_get_binary_offset_for_decoration(
                    compiler, resource.id(), Spv.SpvDecorationBinding, offset
            )) {
                throw new ShaderCompileException(
                        "Could not locate raster storage binding for '" + resource.nameString() + "'"
                );
            }
            int logicalBinding = Spvc.spvc_compiler_get_decoration(
                    compiler, resource.id(), Spv.SpvDecorationBinding
            );
            String resourceName = resource.nameString();
            if (resourceName == null || resourceName.isBlank()) {
                resourceName = "binding" + logicalBinding;
            }
            if (kind == RasterStorageKind.IMAGE) {
                long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
                int dimension = Spvc.spvc_type_get_image_dimension(type);
                if (dimension != Spv.SpvDim2D && dimension != Spv.SpvDim3D) {
                    throw new ShaderCompileException(
                            "Raster storage image '" + resourceName + "' has unsupported SPIR-V dimension "
                                    + dimension + "; only 2D and 3D are supported"
                    );
                }
            }
            String descriptorName = kind == RasterStorageKind.BUFFER
                    ? storageBufferDescriptorName(logicalBinding, resourceName)
                    : resourceName;
            output.add(new RasterStorageUse(
                    kind, resourceName, descriptorName, logicalBinding,
                    stageMask, spirv, offset.get(0)
            ));
        }
    }

    @Nullable
    private static UniformDescription findUniform(final List<UniformDescription> uniforms, final String name) {
        for (UniformDescription uniform : uniforms) {
            if (uniform.name().equals(name)) {
                return uniform;
            }
        }
        return null;
    }

    private static void addBindingIfAbsent(
            final List<VulkanBindGroupLayout.Entry> entries,
            final VulkanBindGroupEntryType type,
            final String name,
            @Nullable final GpuFormat texelBufferFormat
    ) {
        for (VulkanBindGroupLayout.Entry entry : entries) {
            if (entry.type() == type && entry.name().equals(name)) {
                return;
            }
        }
        entries.add(new VulkanBindGroupLayout.Entry(type, name, texelBufferFormat));
    }

    static List<String> tolerateUnprovidedInputs(final List<String> provided, final List<SpvVariable> shaderInputs) {
        List<String> result = null;
        for (SpvVariable input : shaderInputs) {
            String name = input.name();
            if (!provided.contains(name)) {
                if (result == null) {
                    result = new ArrayList<>(provided);
                }
                if (!result.contains(name)) {
                    result.add(name);
                }
            }
        }
        return result == null ? provided : result;
    }

    private static List<String> extractVariableNames(final List<SpvVariable> variables) {
        List<String> names = new ArrayList<>(variables.size());
        for (SpvVariable variable : variables) {
            names.add(variable.name());
        }
        return names;
    }

    private static String extractEntryPoint(final String msl, final Pattern pattern, final String fallback) {
        Matcher matcher = pattern.matcher(msl);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    /**
     * Assigns compact per-stage Metal sampler/texture indices to the active
     * sampled images of one shader stage. Delegates to the native-free
     * {@link ShaderpackSamplerIndexPlan} so the policy is unit-testable on
     * hosts without the Metal native libraries.
     *
     * @param declaredImages sampled image names in stage declaration order.
     * @param activeNames    names SPIRV-Cross reported as active for the stage.
     * @param label          human-readable stage label used in the error message.
     * @return an immutable, declaration-ordered map of active image name to
     *         compact Metal index.
     * @throws ShaderCompileException if more than
     *                               {@link #MAX_METAL_SAMPLERS_PER_STAGE}
     *                               declared images are active.
     */
    static Map<String, Integer> assignSampledImageIndices(
            final List<String> declaredImages,
            final Set<String> activeNames,
            final String label
    ) throws ShaderCompileException {
        return ShaderpackSamplerIndexPlan.assignSampledImageIndices(declaredImages, activeNames, label);
    }

    private static List<MetalCompiledRenderPipeline.ResourceBinding> buildResourceBindings(
            final List<VulkanBindGroupLayout.Entry> entries,
            final List<RasterStorageResource> storageResources,
            final MslShader vertexMsl,
            final MslShader fragmentMsl,
            final boolean compactShaderpackBindings
    ) {
        List<MetalCompiledRenderPipeline.ResourceBinding> resources = new ArrayList<>(
                entries.size() + storageResources.size() + 1
        );
        for (int index = 0; index < entries.size(); index++) {
            VulkanBindGroupLayout.Entry entry = entries.get(index);
            MetalCompiledRenderPipeline.ResourceKind kind = switch (entry.type()) {
                case UNIFORM_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER;
                case SAMPLED_IMAGE -> MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE;
                case TEXEL_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER;
            };
            GpuFormat texelFormat = entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER ? entry.texelBufferFormat() : null;
            boolean sampledResource = entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE
                    || entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER;
            if (compactShaderpackBindings && sampledResource) {
                // Shaderpack sampled resources are re-indexed per stage: each
                // stage assigns 0..n-1 to its own active images, so a resource
                // may live at different Metal indices in the vertex and fragment
                // MSL (or be absent from one stage entirely, giving mask 0 and
                // being skipped at push time).
                Integer vertexIndex = vertexMsl.sampledImageIndices().get(entry.name());
                Integer fragmentIndex = fragmentMsl.sampledImageIndices().get(entry.name());
                int stageMask = (vertexIndex != null ? MetalCompiledRenderPipeline.STAGE_VERTEX : 0)
                        | (fragmentIndex != null ? MetalCompiledRenderPipeline.STAGE_FRAGMENT : 0);
                resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                        kind,
                        entry.name(),
                        vertexIndex == null ? 0 : vertexIndex,
                        stageMask,
                        texelFormat,
                        fragmentIndex == null ? 0 : fragmentIndex
                ));
                continue;
            }
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                    kind, entry.name(), index, stageMask(entry.name(), vertexMsl, fragmentMsl), texelFormat, index
            ));
        }

        for (RasterStorageResource storage : storageResources) {
            MetalCompiledRenderPipeline.ResourceKind kind = switch (storage.kind()) {
                case BUFFER -> MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER;
                case IMAGE -> MetalCompiledRenderPipeline.ResourceKind.STORAGE_IMAGE;
            };
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                    kind,
                    storage.descriptorName(),
                    storage.physicalBinding(),
                    storage.stageMask(),
                    null,
                    storage.physicalBinding()
            ));
        }

        int pushConstantStageMask = (vertexMsl.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_VERTEX : 0)
                | (fragmentMsl.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_FRAGMENT : 0);
        if (pushConstantStageMask != 0) {
            int pushConstantBinding = entries.size() + storageResources.size();
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                    MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                    "push_constants",
                    pushConstantBinding,
                    pushConstantStageMask,
                    null,
                    pushConstantBinding
            ));
        }
        return resources;
    }

    /**
     * H1 diagnosis ({@code -Dmetallum.iris.debug.dumpBindings}): dumps every
     * {@code SAMPLED_IMAGE} binding's per-stage compact indices and the
     * {@code [[texture(N)]]}/{@code [[sampler(N)]]} indices actually present in
     * the emitted MSL, then warns (never throws) when a stage whose mask has
     * the resource active has an expected texture index absent from that
     * stage's MSL index set. The check runs on the same MSL text the pipeline
     * is built from, so a persistent mismatch here would mean the runtime
     * binding table disagrees with SPIRV-Cross's decoration.
     *
     * <p>Every logged line is also recorded into {@link MetalProbeReport}
     * (the game's log file is not retrievable on the target device), bounded by
     * {@link #BINDINGS_PROBE_LINE_LIMIT} with one
     * {@code bindings ... truncated (N more)} marker past the cap.
     */
    private static void dumpSampledImageBindings(
            final String program,
            final List<MetalCompiledRenderPipeline.ResourceBinding> resources,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        if (!MetalDebugSwitches.DUMP_BINDINGS) {
            return;
        }
        Map<String, Integer> vertexTextures = parseMslIndices(vertexMsl.source(), MSL_TEXTURE_ATTRIBUTE);
        Map<String, Integer> fragmentTextures = parseMslIndices(fragmentMsl.source(), MSL_TEXTURE_ATTRIBUTE);
        Map<String, Integer> vertexSamplers = parseMslIndices(vertexMsl.source(), MSL_SAMPLER_ATTRIBUTE);
        Map<String, Integer> fragmentSamplers = parseMslIndices(fragmentMsl.source(), MSL_SAMPLER_ATTRIBUTE);
        List<String> probeLines = new ArrayList<>();
        String header = "bindings " + program + " vertexTextures=" + vertexTextures
                + " vertexSamplers=" + vertexSamplers
                + " fragmentTextures=" + fragmentTextures
                + " fragmentSamplers=" + fragmentSamplers;
        probeLines.add(header);
        Metallum.LOGGER.info("[metallum-iris][bindings] {}", header);
        for (MetalCompiledRenderPipeline.ResourceBinding resource : resources) {
            if (resource.kind() != MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
                continue;
            }
            int vertexIndex = resource.bindingIndexForStage(MetalCompiledRenderPipeline.STAGE_VERTEX);
            int fragmentIndex = resource.bindingIndexForStage(MetalCompiledRenderPipeline.STAGE_FRAGMENT);
            String binding = "bindings " + program + " name=" + resource.name()
                    + " stageMask=" + resource.stageMask()
                    + " vertexIndex=" + vertexIndex
                    + " fragmentIndex=" + fragmentIndex;
            probeLines.add(binding);
            Metallum.LOGGER.info("[metallum-iris][bindings] {}", binding);
            if ((resource.stageMask() & MetalCompiledRenderPipeline.STAGE_VERTEX) != 0
                    && !vertexTextures.containsValue(vertexIndex)) {
                String mismatch = "bindings " + program + " MISMATCH stage=vertex name="
                        + resource.name() + " expected=" + vertexIndex
                        + " mslSet=" + vertexTextures.values();
                probeLines.add(mismatch);
                Metallum.LOGGER.warn("[metallum-iris][bindings] {}", mismatch);
            }
            if ((resource.stageMask() & MetalCompiledRenderPipeline.STAGE_FRAGMENT) != 0
                    && !fragmentTextures.containsValue(fragmentIndex)) {
                String mismatch = "bindings " + program + " MISMATCH stage=fragment name="
                        + resource.name() + " expected=" + fragmentIndex
                        + " mslSet=" + fragmentTextures.values();
                probeLines.add(mismatch);
                Metallum.LOGGER.warn("[metallum-iris][bindings] {}", mismatch);
            }
        }
        recordBindingsToProbe(program, probeLines);
    }

    /** Probe-file cap for binding dump lines; the logger still gets every line. */
    private static final int BINDINGS_PROBE_LINE_LIMIT = 800;
    private static int bindingsProbeLines;
    private static boolean bindingsProbeTruncated;

    /**
     * Appends this program's binding lines to {@link MetalProbeReport} while
     * the 800-line budget lasts, then records exactly one truncation marker
     * carrying the number of lines dropped by the program that crossed the cap.
     * Later programs are suppressed silently (the marker already tells the
     * reader the dump is incomplete).
     */
    private static synchronized void recordBindingsToProbe(
            final String program,
            final List<String> lines
    ) {
        if (bindingsProbeTruncated) {
            return;
        }
        int budget = BINDINGS_PROBE_LINE_LIMIT - bindingsProbeLines;
        if (lines.size() <= budget) {
            for (String line : lines) {
                MetalProbeReport.record(line);
            }
            bindingsProbeLines += lines.size();
            return;
        }
        for (int index = 0; index < budget; index++) {
            MetalProbeReport.record(lines.get(index));
        }
        bindingsProbeLines = BINDINGS_PROBE_LINE_LIMIT;
        bindingsProbeTruncated = true;
        MetalProbeReport.record(
                "bindings " + program + " truncated (" + (lines.size() - budget) + " more)"
        );
    }

    private static Map<String, Integer> parseMslIndices(final String msl, final Pattern pattern) {
        Map<String, Integer> indices = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(msl);
        while (matcher.find()) {
            indices.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return indices;
    }

    private static int stageMask(
            final String name,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        int mask = 0;
        if (vertexMsl.activeResources().contains(name)) {
            mask |= MetalCompiledRenderPipeline.STAGE_VERTEX;
        }
        if (fragmentMsl.activeResources().contains(name)) {
            mask |= MetalCompiledRenderPipeline.STAGE_FRAGMENT;
        }
        if (mask == 0) {
            mask = MetalCompiledRenderPipeline.STAGE_ALL;
        }

        return mask;
    }

    static VertexInputLayout vertexInputLayout(
            final RenderPipeline pipeline,
            final List<SpvVariable> shaderInputs
    ) {
        Set<String> shaderNames = new HashSet<>();
        for (SpvVariable input : shaderInputs) {
            shaderNames.add(input.name());
        }

        List<String> physicalNames = MetalPipelineSupport.vertexAttributeNames(pipeline);
        Set<String> physicalNameSet = new HashSet<>(physicalNames);
        List<String> resolvedNames = new ArrayList<>(physicalNames.size());
        Map<String, GpuFormat> resolvedFormats = new LinkedHashMap<>();
        for (VertexFormat binding : pipeline.getVertexFormatBindings()) {
            if (binding == null) {
                continue;
            }
            for (VertexFormatElement element : binding.getElements()) {
                String physicalName = element.name();
                String resolvedName = physicalName;
                String irisAlias = "iris_" + physicalName;
                if (!shaderNames.contains(physicalName)
                        && shaderNames.contains(irisAlias)
                        && !physicalNameSet.contains(irisAlias)) {
                    resolvedName = irisAlias;
                }
                resolvedNames.add(resolvedName);
                resolvedFormats.putIfAbsent(resolvedName, element.format());
            }
        }

        return new VertexInputLayout(List.copyOf(resolvedNames), Map.copyOf(resolvedFormats));
    }

    record VertexInputLayout(List<String> names, Map<String, GpuFormat> formats) {
    }

    static List<String> vertexInputNames(final VertexFormat[] bindings) {
        List<String> names = new ArrayList<>();
        for (VertexFormat binding : bindings) {
            if (binding == null) {
                continue;
            }
            for (VertexFormatElement element : binding.getElements()) {
                names.add(element.name());
            }
        }
        return List.copyOf(names);
    }

    static void applyVertexInputLocations(
            final IntermediaryShaderModule shader,
            final VertexInputLayout physicalInputs
    ) {
        Map<String, Integer> physicalLocations = new HashMap<>();
        for (int location = 0; location < physicalInputs.names().size(); location++) {
            physicalLocations.putIfAbsent(physicalInputs.names().get(location), location);
        }

        IntBuffer words = shader.spirv().asIntBuffer();
        int genericLocation = physicalInputs.names().size();
        for (SpvVariable input : shader.inputs()) {
            Integer physicalLocation = physicalLocations.get(input.name());
            words.put(input.locationOffset(), physicalLocation == null ? genericLocation++ : physicalLocation);
        }
    }

    enum BaseType {
        FLOAT(0),
        INT(16),
        UINT(32);

        private final int defaultValueOffset;

        BaseType(final int defaultValueOffset) {
            this.defaultValueOffset = defaultValueOffset;
        }

        int defaultValueOffset() {
            return this.defaultValueOffset;
        }
    }

    record GenericVertexInput(int location, BaseType baseType, int components) {
        GenericVertexInput {
            if (location < 0) {
                throw new IllegalArgumentException("Generic vertex input location must be non-negative");
            }
            Objects.requireNonNull(baseType, "baseType");
            if (components < 1 || components > 4) {
                throw new IllegalArgumentException("Generic vertex input components must be in 1..4");
            }
        }

        MTLVertexFormat metalFormat() {
            return switch (baseType) {
                case FLOAT -> switch (components) {
                    case 1 -> MTLVertexFormat.Float;
                    case 2 -> MTLVertexFormat.Float2;
                    case 3 -> MTLVertexFormat.Float3;
                    case 4 -> MTLVertexFormat.Float4;
                    default -> throw new AssertionError(components);
                };
                case INT -> switch (components) {
                    case 1 -> MTLVertexFormat.Int;
                    case 2 -> MTLVertexFormat.Int2;
                    case 3 -> MTLVertexFormat.Int3;
                    case 4 -> MTLVertexFormat.Int4;
                    default -> throw new AssertionError(components);
                };
                case UINT -> switch (components) {
                    case 1 -> MTLVertexFormat.UInt;
                    case 2 -> MTLVertexFormat.UInt2;
                    case 3 -> MTLVertexFormat.UInt3;
                    case 4 -> MTLVertexFormat.UInt4;
                    default -> throw new AssertionError(components);
                };
            };
        }

        int defaultValueOffset() {
            return baseType.defaultValueOffset();
        }
    }

    static final int GENERIC_VERTEX_DEFAULT_VALUES_SIZE = 48;

    static void writeGenericVertexDefaultValues(final ByteBuffer destination) {
        if (destination.remaining() < GENERIC_VERTEX_DEFAULT_VALUES_SIZE) {
            throw new IllegalArgumentException(
                    "Generic vertex default buffer requires " + GENERIC_VERTEX_DEFAULT_VALUES_SIZE + " bytes"
            );
        }
        ByteBuffer values = destination.duplicate().order(ByteOrder.nativeOrder());
        int start = values.position();
        for (int index = 0; index < GENERIC_VERTEX_DEFAULT_VALUES_SIZE; index++) {
            values.put(start + index, (byte) 0);
        }
        values.putFloat(start + BaseType.FLOAT.defaultValueOffset() + 12, 1.0F);
        values.putInt(start + BaseType.INT.defaultValueOffset() + 12, 1);
        values.putInt(start + BaseType.UINT.defaultValueOffset() + 12, 1);
    }

    static List<GenericVertexInput> genericVertexInputs(
            final ByteBuffer spirvBytes,
            final List<String> physicalInputNames
    ) throws ShaderCompileException {
        Set<String> physicalInputs = Set.copyOf(physicalInputNames);
        List<GenericVertexInput> result = new ArrayList<>();
        Map<Integer, String> namesByLocation = new HashMap<>();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();
            if (spirvWords.remaining() < 5) {
                throw new ShaderCompileException("SPIR-V is too small to reflect generic vertex inputs");
            }

            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pContext), "spvc_context_create(generic vertex inputs)");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_context_parse_spirv(context, spirvWords, spirvWords.remaining(), pIr),
                        "spvc_context_parse_spirv(generic vertex inputs)"
                );
                PointerBuffer pCompiler = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_context_create_compiler(
                                context, Spvc.SPVC_BACKEND_NONE, pIr.get(0),
                                Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler
                        ),
                        "spvc_context_create_compiler(generic vertex inputs)"
                );
                long compiler = pCompiler.get(0);

                PointerBuffer pActiveSet = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_compiler_get_active_interface_variables(compiler, pActiveSet),
                        "spvc_compiler_get_active_interface_variables(generic vertex inputs)"
                );
                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_compiler_create_shader_resources_for_active_variables(
                                compiler, pResources, pActiveSet.get(0)
                        ),
                        "spvc_compiler_create_shader_resources_for_active_variables(generic vertex inputs)"
                );
                PointerBuffer pList = stack.mallocPointer(1);
                PointerBuffer pCount = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_resources_get_resource_list_for_type(
                                pResources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, pList, pCount
                        ),
                        "spvc_resources_get_resource_list_for_type(STAGE_INPUT generic vertex inputs)"
                );

                int count = Math.toIntExact(pCount.get(0));
                if (count == 0) {
                    return List.of();
                }
                SpvcReflectedResource.Buffer inputs = SpvcReflectedResource.create(pList.get(0), count);
                for (int index = 0; index < count; index++) {
                    SpvcReflectedResource input = inputs.get(index);
                    String name = input.nameString();
                    if (physicalInputs.contains(name)
                            || Spvc.spvc_compiler_has_decoration(compiler, input.id(), Spv.SpvDecorationBuiltIn)) {
                        continue;
                    }
                    if (!Spvc.spvc_compiler_has_decoration(compiler, input.id(), Spv.SpvDecorationLocation)) {
                        throw new ShaderCompileException(
                                "Active generic vertex input " + name + " has no location"
                        );
                    }

                    long type = Spvc.spvc_compiler_get_type_handle(compiler, input.type_id());
                    int columns = Spvc.spvc_type_get_columns(type);
                    int arrayDimensions = Spvc.spvc_type_get_num_array_dimensions(type);
                    if (columns != 1 || arrayDimensions != 0) {
                        throw new ShaderCompileException(
                                "Unsupported generic vertex input shape for " + name
                                        + ": columns=" + columns + ", arrayDimensions=" + arrayDimensions
                        );
                    }

                    int spvcBaseType = Spvc.spvc_type_get_basetype(type);
                    BaseType baseType = switch (spvcBaseType) {
                        case Spvc.SPVC_BASETYPE_FP32 -> BaseType.FLOAT;
                        case Spvc.SPVC_BASETYPE_INT32 -> BaseType.INT;
                        case Spvc.SPVC_BASETYPE_UINT32 -> BaseType.UINT;
                        default -> throw new ShaderCompileException(
                                "Unsupported generic vertex input base type for " + name + ": " + spvcBaseType
                        );
                    };
                    int components = Spvc.spvc_type_get_vector_size(type);
                    if (components < 1 || components > 4) {
                        throw new ShaderCompileException(
                                "Unsupported generic vertex input vector size for " + name + ": " + components
                        );
                    }

                    int location = Spvc.spvc_compiler_get_decoration(
                            compiler, input.id(), Spv.SpvDecorationLocation
                    );
                    String conflict = namesByLocation.putIfAbsent(location, name);
                    if (conflict != null) {
                        throw new ShaderCompileException(
                                "Generic vertex inputs " + conflict + " and " + name
                                        + " both use location " + location
                        );
                    }
                    result.add(new GenericVertexInput(location, baseType, components));
                }
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }

        result.sort(Comparator.comparingInt(GenericVertexInput::location));
        return List.copyOf(result);
    }

    private static void registerIntegerInputConversions(
            final MemoryStack stack,
            final long compiler,
            final Map<String, GpuFormat> attributeFormats
    ) throws ShaderCompileException {
        if (attributeFormats.isEmpty()) {
            return;
        }

        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");

        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(pResources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, pList, pCount), "spvc_resources_get_resource_list_for_type(STAGE_INPUT)");
        int count = (int) pCount.get(0);
        if (count == 0) {
            return;
        }

        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (int i = 0; i < count; i++) {
            SpvcReflectedResource input = list.get(i);
            String inputName = input.nameString();
            GpuFormat format = attributeFormats.get(inputName);
            if (format == null && inputName.startsWith(IRIS_INPUT_ALIAS_PREFIX)) {
                // Iris' vanilla-core transformer renames the physical inputs
                // (UV1 -> iris_UV1, Position -> iris_Position, ...) while the
                // format table is keyed by the physical element names. Retry
                // the alias so renamed integer inputs still get their MSL
                // interface format.
                format = attributeFormats.get(
                        inputName.substring(IRIS_INPUT_ALIAS_PREFIX.length())
                );
            }
            if (format == null) {
                continue;
            }
            String formatName = format.name();
            int width;
            if (formatName.endsWith("_UINT")) {
                width = formatName.contains("8") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_UINT8
                        : formatName.contains("16") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_UINT16
                          : Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER;
            } else if (formatName.endsWith("_SINT")) {
                // No signed-width enum exists in the SPIRV-Cross binding; the
                // Any16/Any32 hints make it emit shortN/intN from the SPIR-V
                // type, matching the backend's Short2/Int2 vertex formats.
                width = formatName.contains("16") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_ANY16
                        : formatName.contains("32") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_ANY32
                          : Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER;
            } else {
                continue;
            }
            if (width == Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER) {
                continue;
            }

            long typeHandle = Spvc.spvc_compiler_get_type_handle(compiler, input.type_id());
            int baseType = Spvc.spvc_type_get_basetype(typeHandle);
            if (baseType != Spvc.SPVC_BASETYPE_INT8 && baseType != Spvc.SPVC_BASETYPE_INT16
                    && baseType != Spvc.SPVC_BASETYPE_INT32 && baseType != Spvc.SPVC_BASETYPE_INT64) {
                continue;
            }

            SpvcMslShaderInterfaceVar2 var = SpvcMslShaderInterfaceVar2.malloc(stack);
            Spvc.spvc_msl_shader_interface_var_init_2(var);
            var.location(Spvc.spvc_compiler_get_decoration(compiler, input.id(), Spv.SpvDecorationLocation));
            var.vecsize(Spvc.spvc_type_get_vector_size(typeHandle));
            var.format(width);
            var.rate(Spvc.SPVC_MSL_SHADER_VARIABLE_RATE_PER_VERTEX);
            checkSpvc(Spvc.spvc_compiler_msl_add_shader_input_2(compiler, var), "spvc_compiler_msl_add_shader_input_2");
        }
    }

    static Map<String, Integer> explicitFragmentOutputLocations(@Nullable final String source)
            throws ShaderCompileException {
        if (source == null || source.isBlank()) {
            return Map.of();
        }

        Map<String, Integer> locations = new HashMap<>();
        Set<Integer> occupiedLocations = new HashSet<>();
        Matcher matcher = EXPLICIT_FRAGMENT_OUTPUT_PATTERN.matcher(source);
        while (matcher.find()) {
            int location = Integer.parseInt(matcher.group(1));
            String name = matcher.group(2);
            if (location < 0 || location >= ColorTargetState.MAX_COLOR_TARGETS) {
                throw new ShaderCompileException(
                        "Fragment output " + name + " uses color location " + location
                                + "; supported range is 0.." + (ColorTargetState.MAX_COLOR_TARGETS - 1)
                );
            }
            Integer previous = locations.putIfAbsent(name, location);
            if (previous != null && previous != location) {
                throw new ShaderCompileException(
                        "Fragment output " + name + " declares conflicting locations "
                                + previous + " and " + location
                );
            }
            if (previous == null && !occupiedLocations.add(location)) {
                throw new ShaderCompileException("Multiple fragment outputs declare color location " + location);
            }
        }
        return Map.copyOf(locations);
    }

    private static Set<Integer> applyExplicitFragmentOutputLocations(
            final MemoryStack stack,
            final long compiler,
            final Map<String, Integer> explicitLocations
    ) throws ShaderCompileException {
        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_compiler_create_shader_resources(compiler, pResources),
                "spvc_compiler_create_shader_resources(fragment outputs)"
        );
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_resources_get_resource_list_for_type(
                        pResources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, pList, pCount
                ),
                "spvc_resources_get_resource_list_for_type(STAGE_OUTPUT)"
        );

        int count = (int) pCount.get(0);
        if (count == 0) {
            return Set.of();
        }
        SpvcReflectedResource.Buffer outputs = SpvcReflectedResource.create(pList.get(0), count);
        Set<Integer> activeLocations = new HashSet<>();
        for (int index = 0; index < count; index++) {
            SpvcReflectedResource output = outputs.get(index);
            Integer location = explicitLocations.get(output.nameString());
            if (location != null) {
                Spvc.spvc_compiler_set_decoration(
                        compiler, output.id(), Spv.SpvDecorationLocation, location
                );
            }
            if (!Spvc.spvc_compiler_has_decoration(
                    compiler, output.id(), Spv.SpvDecorationBuiltIn
            )) {
                activeLocations.add(Spvc.spvc_compiler_get_decoration(
                        compiler, output.id(), Spv.SpvDecorationLocation
                ));
            }
        }
        return Set.copyOf(activeLocations);
    }

    static void validateFragmentOutputSignature(
            final RenderPipeline pipeline,
            final Set<Integer> shaderLocations
    ) throws ShaderCompileException {
        validateFragmentOutputSignature(
                pipeline.getLocation().toString(),
                pipeline.getColorTargetStates(),
                shaderLocations
        );
    }

    static void validateFragmentOutputSignature(
            final String pipelineName,
            final ColorTargetState[] targets,
            final Set<Integer> shaderLocations
    ) throws ShaderCompileException {
        Set<Integer> targetLocations = new HashSet<>();
        for (int index = 0; index < targets.length; index++) {
            if (targets[index] != null) {
                targetLocations.add(index);
            }
        }
        if (!targetLocations.containsAll(shaderLocations)) {
            throw new ShaderCompileException(
                    "Fragment output/color-target location mismatch for " + pipelineName
                            + ": shader=" + shaderLocations + ", targets=" + targetLocations
            );
        }
    }

    private static MslShader spirvToMsl(
            final ByteBuffer spirvBytes,
            final int pushConstantBinding,
            final Map<String, GpuFormat> attributeFormats,
            final boolean enablePointSize,
            final Map<String, Integer> explicitFragmentOutputLocations,
            final Map<String, Integer> explicitResourceBindings
    ) throws ShaderCompileException {
        return spirvToMsl(
                spirvBytes,
                pushConstantBinding,
                attributeFormats,
                enablePointSize,
                explicitFragmentOutputLocations,
                explicitResourceBindings,
                null,
                null
        );
    }

    private static MslShader spirvToMsl(
            final ByteBuffer spirvBytes,
            final int pushConstantBinding,
            final Map<String, GpuFormat> attributeFormats,
            final boolean enablePointSize
    ) throws ShaderCompileException {
        return spirvToMsl(spirvBytes, pushConstantBinding, attributeFormats, enablePointSize, Map.of());
    }

    private static MslShader spirvToMsl(
            final ByteBuffer spirvBytes,
            final int pushConstantBinding,
            final Map<String, GpuFormat> attributeFormats,
            final boolean enablePointSize,
            final Map<String, Integer> explicitFragmentOutputLocations
    ) throws ShaderCompileException {
        return spirvToMsl(
                spirvBytes,
                pushConstantBinding,
                attributeFormats,
                enablePointSize,
                explicitFragmentOutputLocations,
                Map.of(),
                null,
                null
        );
    }

    private static MslShader spirvToMsl(
            final ByteBuffer spirvBytes,
            final int pushConstantBinding,
            final Map<String, GpuFormat> attributeFormats,
            final boolean enablePointSize,
            final Map<String, Integer> explicitFragmentOutputLocations,
            final Map<String, Integer> explicitResourceBindings,
            @Nullable final List<String> physicalInputNames,
            @Nullable final String stageLabel
    ) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();
            int wordCount = spirvWords.remaining();

            // SPIR-V 二进制必须至少包含 5 个字（头部：magic、version、generator、bound、schema）。
            // 空或过短的 SPIR-V 会导致 spvc_context_parse_spirv 在某些版本中行为不确定。
            if (wordCount < 5) {
                throw new ShaderCompileException(
                        "SPIR-V is too small: " + wordCount + " words (minimum 5 required). " +
                        "ByteBuffer remaining=" + spirvBytes.remaining() + " byteOrder=" + spirvBytes.order()
                );
            }

            int magic = spirvWords.get(0);

            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pContext), "spvc_context_create");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_context_parse_spirv(context, spirvWords, wordCount, pIr), "spvc_context_parse_spirv");

                long ir = pIr.get(0);
                if (ir == 0L) {
                    // spvc_context_parse_spirv 返回了成功但未写入 IR 指针。
                    // 这通常表示加载的 libspvc.dylib 版本与 LWJGL 绑定不匹配，
                    // 或者 MoltenVK 导出的 spvc_ 符号覆盖了 LWJGL 的实现。
                    String lastError = Spvc.spvc_context_get_last_error_string(context);
                    throw new ShaderCompileException(
                            "spvc_context_parse_spirv returned SPVC_SUCCESS but parsed_ir is NULL. " +
                            "This indicates a version mismatch between the loaded libspvc.dylib and LWJGL's Java bindings, " +
                            "or symbol interposition from another library (e.g. libMoltenVK.dylib). " +
                            "SPIR-V: " + wordCount + " words, magic=0x" + Integer.toHexString(magic) + ". " +
                            "Last error: " + lastError
                    );
                }

                PointerBuffer pCompiler = stack.mallocPointer(1);
                int createCompilerResult = Spvc.spvc_context_create_compiler(
                        context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler
                );
                if (createCompilerResult != Spvc.SPVC_SUCCESS) {
                    String lastError = Spvc.spvc_context_get_last_error_string(context);
                    throw new ShaderCompileException(
                            "SPIRV-Cross error at spvc_context_create_compiler: " + createCompilerResult +
                            " (context=0x" + Long.toHexString(context) + ", ir=0x" + Long.toHexString(ir) +
                            ", backend=MSL, mode=COPY). Last error: " + lastError
                    );
                }
                long compiler = pCompiler.get(0);

                // Active-set analysis must run before any explicit binding is
                // applied: the compact shaderpack index plan below is derived
                // from it. Decoration writes and compiler options do not affect
                // the active interface set, so hoisting this block ahead of
                // applyExplicitResourceBindings is order-independent.
                PointerBuffer pActiveSet = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_get_active_interface_variables(compiler, pActiveSet), "spvc_compiler_get_active_interface_variables");
                long activeSet = pActiveSet.get(0);
                checkSpvc(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, activeSet), "spvc_compiler_set_enabled_interface_variables");

                Set<String> activeResources = collectActiveResourceNames(stack, compiler, activeSet);

                final Map<String, Integer> sampledImageIndices;
                final Map<String, Integer> effectiveResourceBindings;
                if (explicitResourceBindings.isEmpty()) {
                    // Vanilla path: bindings are already final (layout-derived).
                    sampledImageIndices = Map.of();
                    effectiveResourceBindings = explicitResourceBindings;
                } else {
                    // Shaderpack path: remap the stage's active sampled images
                    // onto a compact 0..n-1 Metal index range (Metal allows at
                    // most 16 sampler slots per stage). Non-active images keep a
                    // distinct sentinel binding (16 + declaration ordinal) so
                    // applyExplicitResourceBindings still finds every declared
                    // name; they are never declared in the emitted MSL because
                    // the enabled-interface set excludes them.
                    final List<String> declaredImages = declaredSampledImages(stack, compiler);
                    sampledImageIndices = assignSampledImageIndices(
                            declaredImages,
                            activeResources,
                            stageLabel == null ? "<unknown stage>" : stageLabel
                    );
                    final Map<String, Integer> mergedBindings = new LinkedHashMap<>(explicitResourceBindings);
                    for (int ordinal = 0; ordinal < declaredImages.size(); ordinal++) {
                        final String imageName = declaredImages.get(ordinal);
                        final Integer compactIndex = sampledImageIndices.get(imageName);
                        mergedBindings.put(
                                imageName,
                                compactIndex != null ? compactIndex : MAX_METAL_SAMPLERS_PER_STAGE + ordinal
                        );
                    }
                    effectiveResourceBindings = mergedBindings;
                }
                applyExplicitResourceBindings(stack, compiler, effectiveResourceBindings);

                List<GenericVertexInput> genericVertexInputs = physicalInputNames == null
                        ? List.of()
                        : applyShaderpackVertexInputLocations(stack, compiler, physicalInputNames);

                PointerBuffer pOptions = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_compiler_options(compiler, pOptions), "spvc_compiler_create_compiler_options");
                long options = pOptions.get(0);
                checkSpvc(
                        Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS),
                        "spvc_compiler_options_set_uint(MSL_PLATFORM)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_4_0),
                        "spvc_compiler_options_set_uint(MSL_VERSION)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true),
                        "spvc_compiler_options_set_bool(MSL_ENABLE_DECORATION_BINDING)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true),
                        "spvc_compiler_options_set_bool(MSL_TEXTURE_BUFFER_NATIVE)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true),
                        "spvc_compiler_options_set_bool(FLIP_VERTEX_Y)"
                );
                // Metal 拒绝非 Point 拓扑管线携带 [[point_size]] 顶点输出（报错：
                // "Vertex shader writes point size but inputPrimitiveTopology is ..."）。
                // 仅 POINTS 拓扑需要 point_size；对 DEBUG_LINES/TRIANGLES/QUADS 等拓扑抑制该内建，
                // 使 makeRenderPipelineState 不再失败（修复 litematica 覆盖轮廓渲染崩溃）。
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, enablePointSize),
                        "spvc_compiler_options_set_bool(MSL_ENABLE_POINT_SIZE_BUILTIN)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(
                                options,
                                Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS,
                                true
                        ),
                        "spvc_compiler_options_set_bool(MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS)"
                );
                checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");

                registerIntegerInputConversions(stack, compiler, attributeFormats);
                Set<Integer> stageOutputLocations = applyExplicitFragmentOutputLocations(
                        stack, compiler, explicitFragmentOutputLocations
                );

                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");
                long resources = pResources.get(0);

                PointerBuffer pList = stack.mallocPointer(1);
                PointerBuffer pCount = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT, pList, pCount), "spvc_resources_get_resource_list_for_type");
                boolean hasPushConstants = pCount.get(0) > 0;
                if (hasPushConstants) {
                    SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), 1);
                    Spvc.spvc_compiler_set_decoration(compiler, list.get(0).id(), Spv.SpvDecorationBinding, pushConstantBinding);
                }

                PointerBuffer pSource = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_compile(compiler, pSource), "spvc_compiler_compile");
                return new MslShader(
                        MemoryUtil.memUTF8(pSource.get(0)),
                        hasPushConstants,
                        activeResources,
                        sampledImageIndices,
                        stageOutputLocations,
                        genericVertexInputs
                );
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    record MslShader(
            String source,
            boolean hasPushConstants,
            Set<String> activeResources,
            Map<String, Integer> sampledImageIndices,
            Set<Integer> stageOutputLocations,
            List<GenericVertexInput> genericVertexInputs
    ) {
    }

    private static List<GenericVertexInput> applyShaderpackVertexInputLocations(
            final MemoryStack stack,
            final long compiler,
            final List<String> physicalInputNames
    ) throws ShaderCompileException {
        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_compiler_create_shader_resources(compiler, pResources),
                "spvc_compiler_create_shader_resources(vertex inputs)"
        );
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_resources_get_resource_list_for_type(
                        pResources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, pList, pCount
                ),
                "spvc_resources_get_resource_list_for_type(STAGE_INPUT vertex inputs)"
        );

        int count = (int) pCount.get(0);
        if (count == 0) {
            return List.of();
        }
        SpvcReflectedResource.Buffer inputs = SpvcReflectedResource.create(pList.get(0), count);
        Set<String> shaderNames = new HashSet<>();
        for (int index = 0; index < count; index++) {
            SpvcReflectedResource input = inputs.get(index);
            if (!Spvc.spvc_compiler_has_decoration(compiler, input.id(), Spv.SpvDecorationBuiltIn)) {
                shaderNames.add(input.nameString());
            }
        }

        Set<String> physicalNameSet = new HashSet<>(physicalInputNames);
        Map<String, Integer> locations = new LinkedHashMap<>();
        for (int location = 0; location < physicalInputNames.size(); location++) {
            String physicalName = physicalInputNames.get(location);
            String resolvedName = physicalName;
            String irisAlias = "iris_" + physicalName;
            if (!shaderNames.contains(physicalName)
                    && shaderNames.contains(irisAlias)
                    && !physicalNameSet.contains(irisAlias)) {
                resolvedName = irisAlias;
            }
            locations.putIfAbsent(resolvedName, location);
        }

        int genericLocation = physicalInputNames.size();
        List<GenericVertexInput> genericInputs = new ArrayList<>();
        Set<Integer> usedLocations = new HashSet<>();
        for (int index = 0; index < count; index++) {
            SpvcReflectedResource input = inputs.get(index);
            if (Spvc.spvc_compiler_has_decoration(compiler, input.id(), Spv.SpvDecorationBuiltIn)) {
                continue;
            }

            Integer physicalLocation = locations.get(input.nameString());
            int location = physicalLocation == null ? genericLocation++ : physicalLocation;
            if (!usedLocations.add(location)) {
                throw new ShaderCompileException(
                        "Vertex input " + input.nameString() + " reuses location " + location
                );
            }
            Spvc.spvc_compiler_set_decoration(compiler, input.id(), Spv.SpvDecorationLocation, location);

            if (physicalLocation == null) {
                long type = Spvc.spvc_compiler_get_type_handle(compiler, input.type_id());
                int columns = Spvc.spvc_type_get_columns(type);
                int arrayDimensions = Spvc.spvc_type_get_num_array_dimensions(type);
                if (columns != 1 || arrayDimensions != 0) {
                    throw new ShaderCompileException(
                            "Unsupported generic vertex input shape for " + input.nameString()
                                    + ": columns=" + columns + ", arrayDimensions=" + arrayDimensions
                    );
                }

                BaseType baseType = switch (Spvc.spvc_type_get_basetype(type)) {
                    case Spvc.SPVC_BASETYPE_FP32 -> BaseType.FLOAT;
                    case Spvc.SPVC_BASETYPE_INT32 -> BaseType.INT;
                    case Spvc.SPVC_BASETYPE_UINT32 -> BaseType.UINT;
                    default -> throw new ShaderCompileException(
                            "Unsupported generic vertex input base type for " + input.nameString()
                    );
                };
                int components = Spvc.spvc_type_get_vector_size(type);
                if (components < 1 || components > 4) {
                    throw new ShaderCompileException(
                            "Unsupported generic vertex input vector size for " + input.nameString()
                                    + ": " + components
                    );
                }
                genericInputs.add(new GenericVertexInput(location, baseType, components));
            }
        }
        genericInputs.sort(Comparator.comparingInt(GenericVertexInput::location));
        return List.copyOf(genericInputs);
    }

    private static void applyExplicitResourceBindings(
            final MemoryStack stack,
            final long compiler,
            final Map<String, Integer> bindings
    ) throws ShaderCompileException {
        if (bindings.isEmpty()) {
            return;
        }
        PointerBuffer resourcesPointer = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_compiler_create_shader_resources(compiler, resourcesPointer),
                "spvc_compiler_create_shader_resources(resource rebind)"
        );
        long resources = resourcesPointer.get(0);
        int[] resourceTypes = {
                Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
                Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
                Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
                Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS
        };
        PointerBuffer listPointer = stack.mallocPointer(1);
        PointerBuffer countPointer = stack.mallocPointer(1);
        for (int resourceType : resourceTypes) {
            checkSpvc(
                    Spvc.spvc_resources_get_resource_list_for_type(
                            resources, resourceType, listPointer, countPointer
                    ),
                    "spvc_resources_get_resource_list_for_type(resource rebind)"
            );
            int count = (int) countPointer.get(0);
            SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(listPointer.get(0), count);
            for (int index = 0; index < count; index++) {
                SpvcReflectedResource resource = list.get(index);
                Integer binding = bindings.get(resource.nameString());
                if (binding == null) {
                    throw new ShaderCompileException(
                            "Shader resource '" + resource.nameString() + "' has no unified Metal binding"
                    );
                }
                Spvc.spvc_compiler_set_decoration(
                        compiler, resource.id(), Spv.SpvDecorationBinding, binding
                );
                Spvc.spvc_compiler_set_decoration(
                        compiler, resource.id(), Spv.SpvDecorationDescriptorSet, 0
                );
            }
        }
    }

    private static Set<String> collectActiveResourceNames(final MemoryStack stack, final long compiler, final long activeSet) throws ShaderCompileException {
        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, pResources, activeSet),
                "spvc_compiler_create_shader_resources_for_active_variables"
        );
        long resources = pResources.get(0);

        Set<String> names = new HashSet<>();
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, names);
        return names;
    }

    private static void collectResourceNames(
            final MemoryStack stack,
            final long resources,
            final int resourceType,
            final Set<String> out
    ) throws ShaderCompileException {
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, pList, pCount), "spvc_resources_get_resource_list_for_type");
        int count = (int) pCount.get(0);
        if (count == 0) {
            return;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (int i = 0; i < count; i++) {
            out.add(list.get(i).nameString());
        }
    }

    /**
     * 反射一段 SPIR-V，提取 shaderpack 程序声明的 uniform buffer、sampled image
     * （含 separate image）与 separate sampler 资源名。
     *
     * <p>本方法与 {@link #spirvToMsl} 共用同一套 SPIRV-Cross context/compiler
     * 创建模式，但只做反射、不做 MSL 编译：创建 {@code SPVC_BACKEND_MSL} compiler
     * 后直接调 {@code spvc_compiler_create_shader_resources} 取<b>全部声明资源</b>
     * （非 active-only，以匹配 vanilla {@link #addToBindGroup} 反射所有声明
     * uniform/sampler 的语义）。context 用完在 {@code finally} 中经
     * {@code spvc_context_destroy} 释放，与 {@link #spirvToMsl} 对称。
     *
     * <p>shaderpack 路径没有 {@code IntermediaryShaderModule}，无法复用 vanilla
     * 的 {@code shader.uniformBuffers()}/{@code samplers()}；本方法以 SPIRV-Cross
     * 直接反射 glslang 产出的原始 SPIR-V，填补该缺口。反射本身不需要 Metal device。
     *
     * @param spirvBytes SPIR-V 字节流（little-endian 字序列，position 在 0）。
     * @return 反射出的资源名分组。
     * @throws ShaderCompileException 若 SPIRV-Cross 创建/反射失败。
     */
    private static ShaderpackReflection reflectShaderpackResources(final ByteBuffer spirvBytes) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer spirvWords = spirvBytes.asIntBuffer();
            final int wordCount = spirvWords.remaining();
            if (wordCount < 5) {
                throw new ShaderCompileException(
                        "SPIR-V is too small for shaderpack reflection: " + wordCount
                                + " words (minimum 5 required)."
                );
            }

            final PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pContext), "spvc_context_create");
            final long context = pContext.get(0);
            try {
                final PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_context_parse_spirv(context, spirvWords, wordCount, pIr), "spvc_context_parse_spirv");
                final long ir = pIr.get(0);
                if (ir == 0L) {
                    final String lastError = Spvc.spvc_context_get_last_error_string(context);
                    throw new ShaderCompileException(
                            "spvc_context_parse_spirv returned SPVC_SUCCESS but parsed_ir is NULL during shaderpack reflection. "
                                    + "This indicates a version mismatch between the loaded libspvc and LWJGL's Java bindings, "
                                    + "or symbol interposition from another library. "
                                    + "SPIR-V: " + wordCount + " words. Last error: " + lastError
                    );
                }

                final PointerBuffer pCompiler = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler),
                        "spvc_context_create_compiler"
                );
                final long compiler = pCompiler.get(0);

                final PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");
                final long resources = pResources.get(0);

                // 用 LinkedHashSet 保留反射顺序并去重；sampledImages 同时收纳 separate
                // image，故需去重。复用 collectResourceNames（其签名为 Set<String>，
                // LinkedHashSet 即 Set<String> 的有序实现）。
                final LinkedHashSet<String> uniformBuffers = new LinkedHashSet<>();
                final LinkedHashSet<String> sampledImages = new LinkedHashSet<>();
                final LinkedHashSet<String> separateSamplers = new LinkedHashSet<>();
                collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, uniformBuffers);
                collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, sampledImages);
                // separate image（无采样器的纯纹理资源）在 Metal 端也是纹理绑定，
                // 归入 sampledImages 一并映射为 SAMPLED_IMAGE。
                collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, sampledImages);
                collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, separateSamplers);

                return new ShaderpackReflection(
                        List.copyOf(uniformBuffers),
                        List.copyOf(sampledImages),
                        List.copyOf(separateSamplers)
                );
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    /**
     * 合并 vertex 与 fragment 的反射结果，构造 shaderpack 的 bind-group 条目列表。
     *
     * <p>顺序与 vanilla {@link #addToBindGroup} 一致：先所有 uniform buffer，
     * 再 sampled image（含 separate image），最后 separate sampler。vertex 与
     * fragment 的同名资源经 {@link #addBindingIfAbsent} 去重，保留首次出现的顺序。
     * 所有 sampler/image 统一映射为 {@link VulkanBindGroupEntryType#SAMPLED_IMAGE}
     * （shaderpack 路径暂不区分 UTB/纹理缓冲，与 vanilla 的 texel-buffer 判定不同）。
     *
     * <p><b>TODO: 保留纹理槽对齐。</b> Iris 的
     * {@code IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS = {0, 1, 2}} 是 vanilla 占用
     * 的 sampler slot（albedo/overlay/lightmap）。当前实现按反射顺序分配
     * {@code bindingIndex}（uniform buffer 在前、sampler 在后），尚未与 vanilla 保留
     * 槽精确错开。若后续 runtime 绑定阶段发现 shaderpack sampler 与 vanilla 抢占槽位，
     * 需在此重排 sampler 条目使其 bindingIndex 跳过 {0,1,2}，或在
     * {@code buildResourceBindings} 中按名查保留表赋槽。spec 接受此折衷。
     *
     * @param vertexReflection    vertex SPIR-V 反射结果。
     * @param fragmentReflection  fragment SPIR-V 反射结果。
     * @return 合并去重后的 bind-group 条目（uniform buffer 在前）。
     */
    private static List<VulkanBindGroupLayout.Entry> buildShaderpackBindGroupEntries(
            final ShaderpackReflection vertexReflection,
            final ShaderpackReflection fragmentReflection
    ) {
        final List<VulkanBindGroupLayout.Entry> entries = new ArrayList<>();
        for (final String name : vertexReflection.uniformBuffers()) {
            addBindingIfAbsent(entries, VulkanBindGroupEntryType.UNIFORM_BUFFER, name, null);
        }
        for (final String name : fragmentReflection.uniformBuffers()) {
            addBindingIfAbsent(entries, VulkanBindGroupEntryType.UNIFORM_BUFFER, name, null);
        }
        for (final String name : vertexReflection.sampledImages()) {
            addShaderpackSampledBinding(entries, name);
        }
        for (final String name : fragmentReflection.sampledImages()) {
            addShaderpackSampledBinding(entries, name);
        }
        for (final String name : vertexReflection.separateSamplers()) {
            addBindingIfAbsent(entries, VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null);
        }
        for (final String name : fragmentReflection.separateSamplers()) {
            addBindingIfAbsent(entries, VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null);
        }
        return entries;
    }

    private static void addShaderpackSampledBinding(
            final List<VulkanBindGroupLayout.Entry> entries,
            final String name
    ) {
        if ("u_SectionTimeInfo".equals(name)) {
            addBindingIfAbsent(entries, VulkanBindGroupEntryType.TEXEL_BUFFER, name, SODIUM_SECTION_TIME_FORMAT);
        } else {
            addBindingIfAbsent(entries, VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null);
        }
    }

    private static Map<String, Integer> shaderpackResourceBindings(
            final List<VulkanBindGroupLayout.Entry> entries
    ) {
        Map<String, Integer> bindings = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            String name = entries.get(index).name();
            Integer previous = bindings.putIfAbsent(name, index);
            if (previous != null && previous != index) {
                throw new IllegalStateException("Shader resource '" + name + "' has duplicate Metal bindings");
            }
        }
        return Map.copyOf(bindings);
    }

    /**
     * shaderpack SPIR-V 反射结果：按资源类别分组的声明资源名列表。用于在
     * {@link #compileShaderpack} 中构造 bind-group 条目，替代 vanilla 路径中由
     * {@code IntermediaryShaderModule} 提供的 uniform/sampler 名。
     */
    record ShaderpackReflection(
            List<String> uniformBuffers,
            List<String> sampledImages,
            List<String> separateSamplers
    ) {
    }

    /**
     * Rejects shaderpack programs that declare standalone (separate) sampler
     * objects.
     *
     * <p>The Metal raster path maps each sampled image to one combined
     * {@code [[texture(N)]]}/{@code [[sampler(N)]]} pair. A standalone sampler
     * variable has no image to pair with and cannot be represented on that
     * path, so fail the compile with the offending names instead of letting
     * SPIRV-Cross emit an unbound sampler parameter.
     *
     * @param programName        shaderpack program name (diagnostics).
     * @param vertexReflection   vertex SPIR-V reflection.
     * @param fragmentReflection fragment SPIR-V reflection.
     * @throws ShaderCompileException if either stage declares a separate
     *                               sampler object.
     */
    private static void rejectSeparateSamplers(
            final String programName,
            final ShaderpackReflection vertexReflection,
            final ShaderpackReflection fragmentReflection
    ) throws ShaderCompileException {
        final Set<String> separateSamplers = new LinkedHashSet<>(vertexReflection.separateSamplers());
        separateSamplers.addAll(fragmentReflection.separateSamplers());
        if (!separateSamplers.isEmpty()) {
            throw new ShaderCompileException(
                    "Shaderpack program '" + programName + "' declares separate sampler objects " + separateSamplers
                            + "; the Metal raster path requires combined image samplers and cannot bind standalone "
                            + "sampler objects."
            );
        }
    }

    /**
     * Collects the sampled image names declared by one stage in declaration
     * order: combined {@code SAMPLED_IMAGE} resources first, then
     * {@code SEPARATE_IMAGE} resources, de-duplicated. This is the list
     * {@link #assignSampledImageIndices} walks to build the compact per-stage
     * Metal index plan.
     */
    private static List<String> declaredSampledImages(final MemoryStack stack, final long compiler) throws ShaderCompileException {
        final PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_compiler_create_shader_resources(compiler, pResources),
                "spvc_compiler_create_shader_resources(declared sampled images)"
        );
        final long resources = pResources.get(0);
        final LinkedHashSet<String> names = new LinkedHashSet<>();
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, names);
        return List.copyOf(names);
    }

    private static void checkSpvc(final int result, final String stage) throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new ShaderCompileException("SPIRV-Cross error at " + stage + ": " + result);
        }
    }
}
