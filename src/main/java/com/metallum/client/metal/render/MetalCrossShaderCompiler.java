package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline.UniformDescription;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslShaderInterfaceVar2;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
final class MetalCrossShaderCompiler {
    private static final int MSL_VERSION_4_0 = 0x040000;
    private static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern MSL_SAMPLER_PARAM_PATTERN = Pattern.compile("(\\btexture2d\\s*<[^>]+>\\s+)sampler\\b");

    /**
     * 在 iOS 上，Amethyst 启动器捆绑的 libMoltenVK.dylib 内部静态链接了 SPIRV-Cross，
     * 但只编译了 Vulkan 后端。LWJGL 在 iOS 上没有自己的 iOS natives，回退到
     * dlsym(RTLD_DEFAULT, ...) 时找到的是 MoltenVK 的精简版符号，导致
     * spvc_context_create_compiler(SPVC_BACKEND_MSL) 返回 -4 "Invalid backend"。
     *
     * <p>修复：在 LWJGL 的 Spvc 类被首次加载之前，从 jar 中抽取完整版 libspvc.dylib
     * （带 MSL 后端），System.load 加载后设置 Configuration.SPVC_LIBRARY_NAME 指向
     * 该路径。此静态块作为兜底，确保任何路径在 Spvc 初始化前完成配置。
     */
    static {
        MetalNativeBridge.ensureSpvcLibraryConfigured();
    }

    private MetalCrossShaderCompiler() {
    }

    static MetalCompiledRenderPipeline compile(final MetalDevice device, final RenderPipeline pipeline, final ShaderSource shaderSource) {
        try {
            // 1.21.11 的 ShaderSource 是接口（get(id, type) → GLSL 字符串），
            // defines 预处理已由 device.getOrCompileShaderSource 完成
            String vertexGlsl = device.getOrCompileShaderSource(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), shaderSource);
            String fragmentGlsl = device.getOrCompileShaderSource(pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), shaderSource);
            if (vertexGlsl == null || fragmentGlsl == null) {
                throw new IllegalStateException("Couldn't find shader source for pipeline " + pipeline.getLocation());
            }

            ByteBuffer vertexSpirv = shadercCompile(vertexGlsl, ShaderType.VERTEX, pipeline.getLocation().toString());
            ByteBuffer fragmentSpirv = shadercCompile(fragmentGlsl, ShaderType.FRAGMENT, pipeline.getLocation().toString());

            // 1.21.11 用 VertexFormat.Mode（26.2 为 PrimitiveTopology）；POINTS 拓扑才需要 point_size
            boolean enablePointSize = pipeline.getVertexFormatMode() == VertexFormat.Mode.POINTS;
            // fragDepth 内建仅当管线写深度且深度测试开启时启用
            boolean enableFragDepth = pipeline.getDepthTestFunction() != DepthTestFunction.NO_DEPTH_TEST && pipeline.isWriteDepth();

            Map<String, VertexFormatElement.Type> attributeFormats = vertexAttributeTypes(pipeline);
            BindingPlan bindingPlan = planBindings(pipeline);

            // vertex 输入 attribute 按 vertexAttributeNames 顺序编号（与
            // MTLVertexDescriptor.buildVertexDescriptor 的 attrIndex 顺序一致）
            List<String> attributeNames = MetalPipelineSupport.vertexAttributeNames(pipeline);
            Map<String, Integer> attributeLocations = new LinkedHashMap<>();
            for (int i = 0; i < attributeNames.size(); i++) {
                attributeLocations.put(attributeNames.get(i), i);
            }

            MslShader vertexMsl = spirvToMsl(vertexSpirv, attributeFormats, enablePointSize, true, bindingPlan, attributeLocations);
            Map<String, Integer> vertexOutputLocations = vertexMsl.outputLocations();
            MslShader fragmentMsl = spirvToMsl(fragmentSpirv, Map.of(), true, enableFragDepth, bindingPlan, vertexOutputLocations);

            // SPIRV-Cross 保留 GLSL 参数名：terrain.fsh 的 sampleNearest(sampler2D sampler, ...)
            // 生成的 MSL 中 texture2d<float> sampler 会遮蔽内置类型名 sampler → 参数改名
            vertexMsl = new MslShader(sanitizeMsl(vertexMsl.source()), vertexMsl.activeResources(), vertexMsl.outputLocations(), vertexMsl.integerInputs());
            fragmentMsl = new MslShader(sanitizeMsl(fragmentMsl.source()), fragmentMsl.activeResources(), fragmentMsl.outputLocations(), fragmentMsl.integerInputs());

            String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
            String fragmentEntryPoint = extractEntryPoint(fragmentMsl.source(), FRAGMENT_ENTRY_PATTERN, "main0");
            List<MetalCompiledRenderPipeline.ResourceBinding> resources = buildResourceBindings(bindingPlan, vertexMsl, fragmentMsl);
            return new MetalCompiledRenderPipeline(
                    device,
                    pipeline,
                    vertexMsl.source(),
                    fragmentMsl.source(),
                    vertexEntryPoint,
                    fragmentEntryPoint,
                    resources,
                    vertexMsl.integerInputs()
            );
        } catch (ShaderCompileException e) {
            throw new IllegalStateException("Failed to compile Metal cross shader for pipeline " + pipeline.getLocation(), e);
        }
    }

    /**
     * 1.21.11 无 bind group 体系：资源清单直接来自 RenderPipeline（uniforms/samplers）。
     * UBO 与 texture 各自独立连续编号（Metal 的 buffer(i)/texture(i) 命名空间分离），
     * spvc 编译时通过 binding 装饰重设为该编号，保证 MSL 输出索引连续可预期。
     */
    private static BindingPlan planBindings(final RenderPipeline pipeline) {
        Map<String, Integer> uboBindings = new LinkedHashMap<>();
        Map<String, Integer> textureBindings = new LinkedHashMap<>();
        Map<String, TextureFormat> texelFormats = new LinkedHashMap<>();
        List<ResourceSlot> slots = new ArrayList<>();

        int uboIndex = 0;
        int textureIndex = 0;
        for (UniformDescription uniform : pipeline.getUniforms()) {
            if (uniform.type() == UniformType.TEXEL_BUFFER) {
                textureBindings.putIfAbsent(uniform.name(), textureIndex);
                texelFormats.put(uniform.name(), uniform.textureFormat());
                slots.add(new ResourceSlot(uniform.name(), MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER, textureIndex));
                textureIndex++;
            } else {
                uboBindings.putIfAbsent(uniform.name(), uboIndex);
                slots.add(new ResourceSlot(uniform.name(), MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER, uboIndex));
                uboIndex++;
            }
        }
        for (String sampler : pipeline.getSamplers()) {
            textureBindings.putIfAbsent(sampler, textureIndex);
            slots.add(new ResourceSlot(sampler, MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE, textureIndex));
            textureIndex++;
        }
        return new BindingPlan(uboBindings, textureBindings, texelFormats, slots);
    }

    private static List<MetalCompiledRenderPipeline.ResourceBinding> buildResourceBindings(
            final BindingPlan plan,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        List<MetalCompiledRenderPipeline.ResourceBinding> resources = new ArrayList<>(plan.slots().size());
        for (ResourceSlot slot : plan.slots()) {
            int stageMask = stageMask(slot.name(), vertexMsl, fragmentMsl);
            TextureFormat texelFormat = plan.texelFormats().get(slot.name());
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(slot.kind(), slot.name(), slot.binding(), stageMask, texelFormat));
        }
        return resources;
    }

    private static int stageMask(final String name, final MslShader vertexMsl, final MslShader fragmentMsl) {
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

    private static String extractEntryPoint(final String msl, final Pattern pattern, final String fallback) {
        Matcher matcher = pattern.matcher(msl);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    /**
     * SPIRV-Cross 保留 GLSL 参数名：1.21.11 的 terrain.fsh 有 sampleNearest(sampler2D sampler, ...)，
     * 生成的 MSL 中 `texture2d<float> sampler` 参数声明会遮蔽内置类型名 sampler（后续
     * `sampler samplerSmplr` 解析失败："must use 'struct' tag to refer to type 'sampler'"）。
     * 仅把"texture2d<...> 空格 sampler"形态的参数名改为 samplerTex；类型名位置的 sampler
     * （如 `sampler samplerSmplr` 中前者）因前缀非 texture2d<...> 不被匹配，sampler2D/Sampler0
     * 等因 \b 边界不受影响。
     */
    private static String sanitizeMsl(final String msl) {
        return MSL_SAMPLER_PARAM_PATTERN.matcher(msl).replaceAll("$1samplerTex");
    }

    private static Map<String, VertexFormatElement.Type> vertexAttributeTypes(final RenderPipeline pipeline) {
        Map<String, VertexFormatElement.Type> types = new LinkedHashMap<>();
        // 1.21.11 单 VertexFormat（26.2 的 getVertexFormatBindings 为多绑定列表）
        for (VertexFormatElement element : pipeline.getVertexFormat().getElements()) {
            types.putIfAbsent(pipeline.getVertexFormat().getElementName(element), element.type());
        }
        return types;
    }

    /**
     * GLSL → SPIR-V：直接使用 LWJGL Shaderc（1.21.11 无 26.2 的 GlslCompiler 封装）。
     */
    private static ByteBuffer shadercCompile(final String source, final ShaderType type, final String name) throws ShaderCompileException {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new ShaderCompileException("shaderc_compiler_initialize failed");
        }
        try {
            long options = Shaderc.shaderc_compile_options_initialize();
            long result;
            try {
                // 1.21.11 的 shader 是 GL 方言（gl_VertexID/gl_InstanceID 等），
                // Vulkan 目标（GL_KHR_vulkan_glsl）会报 gl_VertexID undeclared。
                // MC 1.21.11 的 GL 后端走驱动编译（glCompileShader），我们需用 GL 方言
                // 编译出 SPIR-V（方言无关中间表示，spvc→MSL 不受影响）。
                Shaderc.shaderc_compile_options_set_target_env(
                        options,
                        Shaderc.shaderc_target_env_opengl,
                        Shaderc.shaderc_env_version_opengl_4_5
                );
                // 1.21.11 的 GLSL 无显式 layout(binding/location)（GL 后端编译前自动注入），
                // 对齐 26.2 的 GlslCompiler：shaderc 自动绑定 UBO/自动映射 location。
                // 后续 spvc 的 rebindResourceType 会按 BindingPlan 重设最终编号。
                Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
                Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
                result = Shaderc.shaderc_compile_into_spv(
                        compiler,
                        source,
                        type == ShaderType.VERTEX ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader,
                        name,
                        "main",
                        options
                );
            } finally {
                Shaderc.shaderc_compile_options_release(options);
            }
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    // LWJGL 3.3.3 的 shaderc_result_get_error_message 直接返回 String
                    String error = Shaderc.shaderc_result_get_error_message(result);
                    throw new ShaderCompileException("shaderc failed (" + status + ") for " + name + ": " + error);
                }
                ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
                // 拷贝出独立缓冲区（result 释放后失效）
                ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
                copy.put(spirv).flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void registerIntegerInputConversions(
            final MemoryStack stack,
            final long compiler,
            final Map<String, VertexFormatElement.Type> attributeTypes
    ) throws ShaderCompileException {
        if (attributeTypes.isEmpty()) {
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
            VertexFormatElement.Type type = attributeTypes.get(input.nameString());
            if (type == null || (type != VertexFormatElement.Type.UINT && type != VertexFormatElement.Type.INT)) {
                continue;
            }
            // LWJGL 3.3.3 无 UINT32/INT32 格式常量：32 位整形统一用 ANY32
            int width = Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_ANY32;

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

    /**
     * SPIR-V → MSL。bindingPlan 提供 UBO/texture 的 binding 重映射（轻量 rebind），
     * 使 MSL 输出的 buffer/texture 索引连续且与 RenderPipeline 资源清单一致。
     *
     * <p>stageLocationMap：本 stage 输入变量的 location 重映射（名字 → location）。
     * vertex 阶段传 attributeLocations（attribute 顺序编号，与 MTLVertexDescriptor
     * 对齐）；fragment 阶段传 vertex 输出的 location 映射，保证跨 stage 的
     * user(locnN) 接口一致（否则 Metal 报 "Fragment input(s) mismatching vertex
     * shader output type(s) or not written by vertex shader"）。
     */
    private static MslShader spirvToMsl(
            final ByteBuffer spirvBytes,
            final Map<String, VertexFormatElement.Type> attributeTypes,
            final boolean enablePointSize,
            final boolean enableFragDepth,
            final BindingPlan bindingPlan,
            final Map<String, Integer> stageLocationMap
    ) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();
            int wordCount = spirvWords.remaining();
            if (wordCount < 5) {
                throw new ShaderCompileException("SPIR-V is too small: " + wordCount + " words (minimum 5 required)");
            }

            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pContext), "spvc_context_create");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_context_parse_spirv(context, spirvWords, wordCount, pIr), "spvc_context_parse_spirv");
                long ir = pIr.get(0);
                if (ir == 0L) {
                    String lastError = Spvc.spvc_context_get_last_error_string(context);
                    throw new ShaderCompileException(
                            "spvc_context_parse_spirv returned SPVC_SUCCESS but parsed_ir is NULL. " +
                            "This indicates a version mismatch between the loaded libspvc.dylib and LWJGL's Java bindings, " +
                            "or symbol interposition from another library (e.g. libMoltenVK.dylib). " +
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
                            " Last error: " + lastError
                    );
                }
                long compiler = pCompiler.get(0);

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
                if (!enableFragDepth) {
                    checkSpvc(
                            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_FRAG_DEPTH_BUILTIN, false),
                            "spvc_compiler_options_set_bool(MSL_ENABLE_FRAG_DEPTH_BUILTIN)"
                    );
                }
                // Metal 拒绝非 Point 拓扑管线携带 [[point_size]] 顶点输出；仅 POINTS 需要
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, enablePointSize),
                        "spvc_compiler_options_set_bool(MSL_ENABLE_POINT_SIZE_BUILTIN)"
                );
                checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");

                PointerBuffer pActiveSet = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_get_active_interface_variables(compiler, pActiveSet), "spvc_compiler_get_active_interface_variables");
                long activeSet = pActiveSet.get(0);
                checkSpvc(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, activeSet), "spvc_compiler_set_enabled_interface_variables");

                Set<String> activeResources = collectActiveResourceNames(stack, compiler, activeSet);

                // 轻量 rebind：按 plan 重设 UBO 与 texture 的 binding 装饰，
                // 保证 MSL 输出索引连续（buffer(0..n-1) / texture(0..n-1)）
                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");
                long resources = pResources.get(0);
                // 1. stage outputs：按出现顺序重设 location（跳过 builtin），
                //    vertex 的映射回传给 fragment 用于输入对齐
                Map<String, Integer> outputLocations = rebindStageOutputs(stack, compiler, resources);
                // 2. stage inputs：按名字匹配重设 location（未匹配的按顺序追加）
                rebindStageInputs(stack, compiler, resources, stageLocationMap);
                // 3. 资源 binding（UBO/texture）
                rebindResourceType(stack, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, bindingPlan.uboBindings());
                rebindResourceType(stack, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, bindingPlan.textureBindings());
                rebindResourceType(stack, compiler, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, bindingPlan.textureBindings());
                // 4. 整数输入转换（需在 location 重设之后读取装饰）
                registerIntegerInputConversions(stack, compiler, attributeTypes);
                // 5. 收集 shader 中声明为 int/uint 的顶点输入名（descriptor 需用非 normalized 格式）
                Set<String> integerInputs = collectIntegerInputs(stack, compiler, resources);

                PointerBuffer pSource = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_compile(compiler, pSource), "spvc_compiler_compile");
                String msl = MemoryUtil.memUTF8(pSource.get(0));
                // spvc 只启用了 FLIP_VERTEX_Y（LWJGL 3.3.3 未暴露 fixup_clipspace 选项），
                // GL NDC z∈[-1,1] 未重映射为 Metal [0,1]：z<0 的三角形被 Metal 裁剪
                // （GUI z=0 恰在边界所以正常，世界地形前方 z<0 全被裁 → 不可见）。
                // 按 SPIRV-Cross spirv_msl.cpp 的 fixup_clipspace 公式补 clip 空间重映射
                // （z' = (z + w) * 0.5，与 y 翻转同位置、同格式）。
                if (msl.contains("out.gl_Position.y = -(out.gl_Position.y);")) {
                    msl = msl.replace(
                            "out.gl_Position.y = -(out.gl_Position.y);",
                            "out.gl_Position.z = (out.gl_Position.z + out.gl_Position.w) * 0.5;    // Adjust clip-space for Metal\n    out.gl_Position.y = -(out.gl_Position.y);"
                    );
                    if (!diagClipspaceLogged) {
                        diagClipspaceLogged = true;
                        com.metallum.Metallum.LOGGER.error("[diag] clipspace fix applied (z = (z+w)*0.5 inserted)");
                    }
                } else {
                    if (!diagClipspaceLogged) {
                        diagClipspaceLogged = true;
                        com.metallum.Metallum.LOGGER.error("[diag] clipspace fix NOT applied: y-flip pattern not found in MSL; first pos line: {}", firstPositionLine(msl));
                    }
                }
                return new MslShader(msl, activeResources, outputLocations, integerInputs);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static boolean diagClipspaceLogged = false;

    private static String firstPositionLine(final String msl) {
        for (String line : msl.split("\n")) {
            if (line.contains("gl_Position")) {
                return line.trim();
            }
        }
        return "(no gl_Position line found)";
    }

    private static void rebindResourceType(
            final MemoryStack stack,
            final long compiler,
            final long resources,
            final int resourceType,
            final Map<String, Integer> bindings
    ) throws ShaderCompileException {
        if (bindings.isEmpty()) {
            return;
        }
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, pList, pCount), "spvc_resources_get_resource_list_for_type");
        int count = (int) pCount.get(0);
        if (count == 0) {
            return;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (int i = 0; i < count; i++) {
            SpvcReflectedResource resource = list.get(i);
            Integer binding = bindings.get(resource.nameString());
            if (binding != null) {
                // LWJGL 3.3.3 的 spvc_compiler_set_decoration 返回 void（26.2 的 3.4.1 返回 int）
                Spvc.spvc_compiler_set_decoration(compiler, resource.id(), Spv.SpvDecorationBinding, binding);
            }
        }
    }

    /**
     * 重设 stage 输出变量的 location（按反射顺序 0..n-1，跳过 builtin 输出）。
     * 返回名字 → location 映射，供 fragment 阶段对齐输入。
     */
    private static Map<String, Integer> rebindStageOutputs(
            final MemoryStack stack,
            final long compiler,
            final long resources
    ) throws ShaderCompileException {
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, pList, pCount), "spvc_resources_get_resource_list_for_type(STAGE_OUTPUT)");
        int count = (int) pCount.get(0);
        Map<String, Integer> locations = new LinkedHashMap<>();
        if (count == 0) {
            return locations;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        int next = 0;
        for (int i = 0; i < count; i++) {
            SpvcReflectedResource output = list.get(i);
            String name = output.nameString();
            if (isBuiltInVariable(name)) {
                continue;
            }
            Spvc.spvc_compiler_set_decoration(compiler, output.id(), Spv.SpvDecorationLocation, next);
            locations.put(name, next);
            next++;
        }
        return locations;
    }

    /**
     * 重设 stage 输入变量的 location：优先按 matchedLocations（名字匹配，跨 stage
     * 对齐），未匹配的（extra inputs，tolerateUnprovidedInputs 语义）按顺序追加编号。
     * builtin 输入（gl_FragCoord 等）跳过。
     */
    private static void rebindStageInputs(
            final MemoryStack stack,
            final long compiler,
            final long resources,
            final Map<String, Integer> matchedLocations
    ) throws ShaderCompileException {
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, pList, pCount), "spvc_resources_get_resource_list_for_type(STAGE_INPUT)");
        int count = (int) pCount.get(0);
        if (count == 0) {
            return;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        int nextLocation = matchedLocations.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        for (int i = 0; i < count; i++) {
            SpvcReflectedResource input = list.get(i);
            String name = input.nameString();
            if (isBuiltInVariable(name)) {
                continue;
            }
            Integer location = matchedLocations.get(name);
            if (location == null) {
                location = nextLocation++;
            }
            Spvc.spvc_compiler_set_decoration(compiler, input.id(), Spv.SpvDecorationLocation, location);
        }
    }

    /**
     * 收集 SPIR-V 中声明为 int/uint 的 stage 输入名（GLSL 的 ivec/uvec，如
     * rendertype_text.vsh 的 `in ivec2 UV2`）。Metal 的 vertex descriptor 对这些
     * attribute 必须使用非 normalized 格式（normalized 只允许转 float）。
     */
    private static Set<String> collectIntegerInputs(
            final MemoryStack stack,
            final long compiler,
            final long resources
    ) throws ShaderCompileException {
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, pList, pCount), "spvc_resources_get_resource_list_for_type(STAGE_INPUT)");
        int count = (int) pCount.get(0);
        Set<String> integerInputs = new LinkedHashSet<>();
        if (count == 0) {
            return integerInputs;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (int i = 0; i < count; i++) {
            SpvcReflectedResource input = list.get(i);
            long typeHandle = Spvc.spvc_compiler_get_type_handle(compiler, input.type_id());
            int baseType = Spvc.spvc_type_get_basetype(typeHandle);
            if (baseType >= Spvc.SPVC_BASETYPE_INT8 && baseType <= Spvc.SPVC_BASETYPE_UINT64) {
                integerInputs.add(input.nameString());
            }
        }
        return integerInputs;
    }

    /**
     * GLSL 内置变量（gl_* 前缀，spvc 反射名保留 GLSL 名）：不参与 user(locnN)
     * 接口匹配，重设 location 会破坏 builtin 语义，必须跳过。
     */
    private static boolean isBuiltInVariable(final String name) {
        return name.startsWith("gl_");
    }

    record MslShader(String source, Set<String> activeResources, Map<String, Integer> outputLocations, Set<String> integerInputs) {
    }

    record ResourceSlot(String name, MetalCompiledRenderPipeline.ResourceKind kind, int binding) {
    }

    record BindingPlan(
            Map<String, Integer> uboBindings,
            Map<String, Integer> textureBindings,
            Map<String, TextureFormat> texelFormats,
            List<ResourceSlot> slots
    ) {
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

    private static void checkSpvc(final int result, final String stage) throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new ShaderCompileException("SPIRV-Cross error at " + stage + ": " + result);
        }
    }
}
