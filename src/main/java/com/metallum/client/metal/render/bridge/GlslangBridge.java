package com.metallum.client.metal.render.bridge;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Function;

/**
 * Foreign Memory & Function API (FFM, {@code java.lang.foreign}) bridge to the
 * glslang C API ({@code vendor/glslang/glslang/Include/glslang_c_interface.h}),
 * exposing GLSL &#8594; SPIR-V compilation.
 *
 * <p>This mirrors the FFM pattern established by {@link MetalNativeBridge}: a
 * static initializer first ensures the bundled native library is loaded (via
 * {@link MetalNativeBridge#ensureGlslangLibraryConfigured()}, which is a no-op
 * on macOS and extracts/loads the bundled glslang dylibs on iOS), obtains a
 * {@link SymbolLookup}, and resolves every glslang downcall to a
 * {@link MethodHandle}. The one-shot process initialization
 * ({@code glslang_initialize_process}) is deferred to a lazy, idempotent method
 * invoked on the first compilation, so that merely class-loading
 * {@code GlslangBridge} does not initialize native glslang state.
 *
 * <p><b>Library loading</b>
 * <ul>
 *   <li>macOS: extract the bundled {@code libglslang.dylib} (and any sibling
 *       dylibs produced by a split build) from {@code /natives/macos/} into a
 *       temp directory, {@code System.load} each (dependencies first), and look
 *       the symbols up via {@link SymbolLookup#loaderLookup()}.</li>
 *   <li>iOS: {@link MetalNativeBridge#ensureGlslangLibraryConfigured()} extracts
 *       the bundled glslang dylibs to a writable directory and
 *       {@code System.load}s them (via Amethyst's hooked {@code dlopen}); a
 *       best-effort {@code System.loadLibrary("glslang")} handles the app
 *       bundle's {@code Frameworks/} deployment path. Symbols are then exposed
 *       through {@link SymbolLookup#loaderLookup()}.</li>
 * </ul>
 *
 * <p><b>glslang process lifetime.</b> glslang documents
 * {@code glslang_initialize_process} as once-per-process; it is therefore
 * invoked exactly once and {@code glslang_finalize_process} is intentionally
 * never called eagerly (the OS reclaims the native memory at JVM exit).
 */
@Environment(EnvType.CLIENT)
public final class GlslangBridge {
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final Linker LINKER = Linker.nativeLinker();

    /** SPIR-V magic number (little-endian first word of every valid SPIR-V binary). */
    private static final int SPIRV_MAGIC = 0x07230203;

    // --- glslang enum constants (verbatim from glslang_c_shader_types.h) ---

    // glslang_stage_t
    private static final int GLSLANG_STAGE_VERTEX = 0;
    private static final int GLSLANG_STAGE_TESSCONTROL = 1;
    private static final int GLSLANG_STAGE_TESSEVALUATION = 2;
    private static final int GLSLANG_STAGE_GEOMETRY = 3;
    private static final int GLSLANG_STAGE_FRAGMENT = 4;

    // glslang_source_t
    private static final int GLSLANG_SOURCE_GLSL = 1;

    // glslang_client_t
    private static final int GLSLANG_CLIENT_VULKAN = 1;

    // glslang_target_client_version_t
    private static final int GLSLANG_TARGET_VULKAN_1_1 = (1 << 22) | (1 << 12);

    // glslang_target_language_t
    private static final int GLSLANG_TARGET_SPV = 1;

    // glslang_target_language_version_t (conservative, broadly compatible target)
    private static final int GLSLANG_TARGET_SPV_1_3 = (1 << 16) | (3 << 8);

    // glslang_profile_t
    private static final int GLSLANG_NO_PROFILE = (1 << 0);

    // glslang_messages_t
    private static final int GLSLANG_MSG_DEFAULT_BIT = 0;
    private static final int GLSLANG_MSG_SPV_RULES_BIT = 1 << 3;
    private static final int GLSLANG_MSG_VULKAN_RULES_BIT = 1 << 4;
    private static final int GLSLANG_SHADER_AUTO_MAP_BINDINGS = 1 << 0;
    private static final int GLSLANG_SHADER_AUTO_MAP_LOCATIONS = 1 << 1;
    private static final int SHADER_OPTIONS =
            GLSLANG_SHADER_AUTO_MAP_BINDINGS | GLSLANG_SHADER_AUTO_MAP_LOCATIONS;
    /** Standard link-time messages for Vulkan SPIR-V generation (mirrors glslang's example.c). */
    private static final int LINK_MESSAGES = GLSLANG_MSG_SPV_RULES_BIT | GLSLANG_MSG_VULKAN_RULES_BIT;

    /** Vulkan-compatible GLSL version forced via {@code force_default_version_and_profile}. */
    private static final int FORCED_VULKAN_VERSION = 460;

    /** Upper bound used when reinterpret()ing a returned C string for reading. */
    private static final long CSTRING_MAX = 1L << 20;

    // --- macOS bundled dylib resources (dependencies first; primary last) ---
    private static final String GLSLANG_MACOS_PRIMARY_RESOURCE = "/natives/macos/libglslang.dylib";
    private static final String[] GLSLANG_MACOS_RESOURCES = {
            "/natives/macos/libSPIRV-Tools-opt.dylib",
            "/natives/macos/libSPIRV-Tools.dylib",
            "/natives/macos/libOSDependent.dylib",
            "/natives/macos/libGenericCodeGen.dylib",
            "/natives/macos/libMachineIndependent.dylib",
            "/natives/macos/libHLSL.dylib",
            "/natives/macos/libSPIRV.dylib",
            "/natives/macos/libglslang-default-resource-limits.dylib",
            GLSLANG_MACOS_PRIMARY_RESOURCE,
    };

    /**
     * {@code glslang_input_t} layout, mirroring the C struct in
     * {@code glslang_c_interface.h}. FFM struct layouts do not insert C ABI
     * padding automatically, so the four bytes before {@code resource} are
     * represented explicitly.
     */
    private static final MemoryLayout INPUT_LAYOUT = MemoryLayout.structLayout(
            INT.withName("language"),
            INT.withName("stage"),
            INT.withName("client"),
            INT.withName("client_version"),
            INT.withName("target_language"),
            INT.withName("target_language_version"),
            ValueLayout.ADDRESS.withName("code"),
            INT.withName("default_version"),
            INT.withName("default_profile"),
            INT.withName("force_default_version_and_profile"),
            INT.withName("forward_compatible"),
            INT.withName("messages"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("resource"),
            MemoryLayout.structLayout(
                    ValueLayout.ADDRESS.withName("include_system"),
                    ValueLayout.ADDRESS.withName("include_local"),
                    ValueLayout.ADDRESS.withName("free_include_result")
            ).withName("callbacks"),
            ValueLayout.ADDRESS.withName("callbacks_ctx")
    );

    private static final VarHandle V_LANGUAGE = varHandle("language");
    private static final VarHandle V_STAGE = varHandle("stage");
    private static final VarHandle V_CLIENT = varHandle("client");
    private static final VarHandle V_CLIENT_VERSION = varHandle("client_version");
    private static final VarHandle V_TARGET_LANGUAGE = varHandle("target_language");
    private static final VarHandle V_TARGET_LANGUAGE_VERSION = varHandle("target_language_version");
    private static final VarHandle V_CODE = varHandle("code");
    private static final VarHandle V_DEFAULT_VERSION = varHandle("default_version");
    private static final VarHandle V_DEFAULT_PROFILE = varHandle("default_profile");
    private static final VarHandle V_FORCE_DEFAULT = varHandle("force_default_version_and_profile");
    private static final VarHandle V_MESSAGES = varHandle("messages");
    private static final VarHandle V_RESOURCE = varHandle("resource");

    private static VarHandle varHandle(String name) {
        return INPUT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(name));
    }

    /**
     * {@code glsl_include_result_t} layout, mirroring the C struct in
     * {@code glslang_c_interface.h}. Returned by the include-local upcall to
     * feed {@code #include} resolution back to glslang's preprocessor.
     */
    private static final MemoryLayout INCLUDE_RESULT_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("header_name"),
            ValueLayout.ADDRESS.withName("header_data"),
            ValueLayout.JAVA_LONG.withName("header_length")
    );
    private static final VarHandle V_IR_HEADER_NAME = INCLUDE_RESULT_LAYOUT.varHandle(
            MemoryLayout.PathElement.groupElement("header_name"));
    private static final VarHandle V_IR_HEADER_DATA = INCLUDE_RESULT_LAYOUT.varHandle(
            MemoryLayout.PathElement.groupElement("header_data"));
    private static final VarHandle V_IR_HEADER_LENGTH = INCLUDE_RESULT_LAYOUT.varHandle(
            MemoryLayout.PathElement.groupElement("header_length"));

    // --- Resolved glslang downcall handles ---
    private static final MethodHandle glslangInitializeProcess;
    private static final MethodHandle glslangDefaultResource;
    private static final MethodHandle glslangShaderCreate;
    private static final MethodHandle glslangShaderDelete;
    private static final MethodHandle glslangShaderSetOptions;
    private static final MethodHandle glslangShaderPreprocess;
    private static final MethodHandle glslangShaderParse;
    private static final MethodHandle glslangShaderGetInfoLog;
    private static final MethodHandle glslangShaderGetInfoDebugLog;
    private static final MethodHandle glslangProgramCreate;
    private static final MethodHandle glslangProgramDelete;
    private static final MethodHandle glslangProgramAddShader;
    private static final MethodHandle glslangProgramLink;
    private static final MethodHandle glslangProgramSpvGenerate;
    private static final MethodHandle glslangProgramSpvGetSize;
    private static final MethodHandle glslangProgramSpvGet;
    private static final MethodHandle glslangProgramGetInfoLog;

    /** Cached pointer returned by {@code glslang_default_resource()} (process-static). */
    private static volatile MemorySegment defaultResource = MemorySegment.NULL;
    private static volatile boolean processInitialized = false;

    /** Serializes compilations; glslang is not guaranteed reentrant for concurrent compiles. */
    private static final Object COMPILE_LOCK = new Object();

    /**
     * Thread-local include resolver used by the {@code include_local} upcall.
     * Set per-compile by {@link #compileGlslToSpv(Stage, String, String, Function)}
     * and cleared afterwards. Maps a header name (e.g. {@code "/lib/settings.glsl"})
     * to its full source text, or {@code null} if the include cannot be resolved
     * (glslang then reports a preprocessor error).
     */
    private static final ThreadLocal<Function<String, String>> INCLUDE_RESOLVER = new ThreadLocal<>();

    /**
     * Per-compile shared arena holding include results returned by the
     * {@code include_local} upcall. Created at the start of a compile (when a
     * resolver is active) and closed in the {@code finally} of that compile.
     * Must be a shared arena because the upcall may execute on a different
     * thread (glslang may invoke the callback synchronously from within
     * {@code glslang_shader_preprocess}).
     */
    private static final ThreadLocal<Arena> INCLUDE_ARENA = new ThreadLocal<>();

    /** Byte offset of the {@code include_local} field within {@code glslang_input_t}. */
    private static final long CALLBACKS_OFFSET = INPUT_LAYOUT.byteOffset(
            MemoryLayout.PathElement.groupElement("callbacks"));
    private static final long INCLUDE_LOCAL_OFFSET = CALLBACKS_OFFSET + ValueLayout.ADDRESS.byteSize();

    /**
     * The {@code include_local} upcall stub, allocated once in a process-lifetime
     * shared arena. Passed to glslang via the {@code callbacks.include_local}
     * field of {@code glslang_input_t} when an include resolver is active.
     */
    private static final MemorySegment INCLUDE_LOCAL_UPCALL;
    /** Shared arena holding the upcall stub (kept alive for the JVM lifetime). */
    private static final Arena UPCALL_ARENA = Arena.ofShared();

    static {
        try {
            // Ensure the bundled glslang library is loaded BEFORE resolving any
            // glslang symbol (mirrors MetalCrossShaderCompiler's Spvc static
            // block, which calls ensureSpvcLibraryConfigured() first). On macOS
            // this is a no-op; on iOS it extracts & System.loads libglslang.dylib.
            MetalNativeBridge.ensureGlslangLibraryConfigured();

            SymbolLookup lookup = createGlslangSymbolLookup();

            glslangInitializeProcess = downcall(lookup, "glslang_initialize_process", FunctionDescriptor.of(INT));
            glslangDefaultResource = downcall(lookup, "glslang_default_resource", FunctionDescriptor.of(ValueLayout.ADDRESS));
            glslangShaderCreate = downcall(lookup, "glslang_shader_create", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            glslangShaderDelete = downcall(lookup, "glslang_shader_delete", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            glslangShaderSetOptions = downcall(
                    lookup,
                    "glslang_shader_set_options",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT)
            );
            glslangShaderPreprocess = downcall(lookup, "glslang_shader_preprocess", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            glslangShaderParse = downcall(lookup, "glslang_shader_parse", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            glslangShaderGetInfoLog = downcall(lookup, "glslang_shader_get_info_log", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            glslangShaderGetInfoDebugLog = downcall(lookup, "glslang_shader_get_info_debug_log", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            glslangProgramCreate = downcall(lookup, "glslang_program_create", FunctionDescriptor.of(ValueLayout.ADDRESS));
            glslangProgramDelete = downcall(lookup, "glslang_program_delete", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            glslangProgramAddShader = downcall(lookup, "glslang_program_add_shader", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            glslangProgramLink = downcall(lookup, "glslang_program_link", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, INT));
            glslangProgramSpvGenerate = downcall(lookup, "glslang_program_SPIRV_generate", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
            glslangProgramSpvGetSize = downcall(lookup, "glslang_program_SPIRV_get_size", FunctionDescriptor.of(LONG, ValueLayout.ADDRESS));
            glslangProgramSpvGet = downcall(lookup, "glslang_program_SPIRV_get", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            glslangProgramGetInfoLog = downcall(lookup, "glslang_program_get_info_log", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // Create the include_local upcall stub. Signature:
            //   glsl_include_result_t* include_local(void* ctx, const char* header_name,
            //                                        const char* includer_name, size_t depth)
            MethodHandle includeLocalHandle;
            try {
                includeLocalHandle = java.lang.invoke.MethodHandles.lookup().findStatic(
                        GlslangBridge.class, "includeLocalCallback",
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG).toMethodType());
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new IllegalStateException("Failed to link include_local upcall", e);
            }
            INCLUDE_LOCAL_UPCALL = LINKER.upcallStub(
                    includeLocalHandle,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
                    UPCALL_ARENA);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load glslang native bridge", e);
        }
    }

    private GlslangBridge() {
    }

    /**
     * GLSL shader stage, mapped to the {@code glslang_stage_t} enumerators.
     */
    public enum Stage {
        VERTEX(GLSLANG_STAGE_VERTEX),
        TESS_CONTROL(GLSLANG_STAGE_TESSCONTROL),
        TESS_EVALUATION(GLSLANG_STAGE_TESSEVALUATION),
        GEOMETRY(GLSLANG_STAGE_GEOMETRY),
        FRAGMENT(GLSLANG_STAGE_FRAGMENT);

        final int glslangStage;

        Stage(int glslangStage) {
            this.glslangStage = glslangStage;
        }
    }

    /**
     * Compiles a GLSL source string to a SPIR-V binary using glslang, targeting
     * Vulkan 1.1 / SPIR-V 1.3.
     *
     * <p>Equivalent to {@link #compileGlslToSpv(Stage, String, String, Function)}
     * with no include resolver — {@code #include} directives in the source will
     * be reported as preprocessor errors.
     *
     * @param stage   the GLSL shader stage.
     * @param source  the GLSL source. Must declare its own {@code #version}.
     * @param defines optional preprocessor defines. Each non-empty line is
     *                emitted verbatim if it already starts with {@code #}, or
     *                wrapped as {@code #define <line>} otherwise, followed by a
     *                {@code #line 1} reset before the real source. May be
     *                {@code null}/empty to pass the source through unchanged.
     * @return the SPIR-V words (uint32, as Java {@code int}).
     * @throws ShaderCompileException if preprocessing, parsing, linking or
     *                                SPIR-V generation fails, or if the result
     *                                is not a valid SPIR-V binary.
     */
    public static int[] compileGlslToSpv(Stage stage, String source, String defines) throws ShaderCompileException {
        return compileGlslToSpv(stage, source, defines, null);
    }

    /**
     * Compiles a GLSL source string to a SPIR-V binary using glslang, targeting
     * Vulkan 1.1 / SPIR-V 1.3, with optional {@code #include} resolution.
     *
     * <p><b>Task 6.3 fixes (extension enabling / resource limits / macro
     * injection) applied here:</b>
     * <ul>
     *   <li><b>Macro injection.</b> A compatibility preamble
     *       ({@link GlslangSourcePrep}) is prepended so raw shaderpack sources
     *       that branch on {@code IS_IRIS}/{@code MC_VERSION}/... parse. In the
     *       real Iris integration path Iris's TransformPatcher supplies these;
     *       the preamble is a safety net for partially-patched / raw sources.</li>
     *   <li><b>Version override.</b> {@code force_default_version_and_profile=1}
     *       with {@code default_version=460} overrides shaderpack
     *       {@code #version 120} to a Vulkan-compatible target. glslang in
     *       Vulkan client mode rejects pre-330 versions otherwise.</li>
     *   <li><b>Resource limits.</b> {@code glslang_default_resource()} already
     *       supplies generous limits (max varyings, uniforms, samplers); BSL
     *       stays within them.</li>
     *   <li><b>Include resolution.</b> When {@code includeResolver} is non-null,
     *       the {@code include_local} callback is wired so {@code #include}
     *       directives resolve through the resolver (header name → source text).
     *       This is what lets the BSL fixture compile programs whose logic lives
     *       behind {@code #include "/program/..."}.</li>
     * </ul>
     *
     * @param stage           the GLSL shader stage.
     * @param source          the GLSL source. Must declare its own {@code #version}.
     * @param defines         optional preprocessor defines (see 3-arg overload).
     * @param includeResolver optional function mapping an include header name
     *                        (e.g. {@code "/lib/settings.glsl"}) to its source
     *                        text, or {@code null} if the include cannot be
     *                        resolved. When {@code null}, include directives
     *                        are not wired and glslang reports them as errors.
     * @return the SPIR-V words (uint32, as Java {@code int}).
     * @throws ShaderCompileException if preprocessing, parsing, linking or
     *                                SPIR-V generation fails, or if the result
     *                                is not a valid SPIR-V binary.
     */
    public static int[] compileGlslToSpv(Stage stage, String source, String defines,
                                         Function<String, String> includeResolver) throws ShaderCompileException {
        if (stage == null) {
            throw new ShaderCompileException("Shader stage is null", null);
        }
        if (source == null) {
            throw new ShaderCompileException("GLSL source is null", null);
        }
        ensureProcessInitialized();

        final int glslangStage = stage.glslangStage;
        // Strip any #version directive — we force 460 via force_default_version_and_profile,
        // and #version must be the first directive if present. Injecting the
        // compatibility preamble before the source would otherwise violate that.
        final String fullSource = GlslangSourcePrep.buildFullSource(source, defines);

        synchronized (COMPILE_LOCK) {
            INCLUDE_RESOLVER.set(includeResolver);
            if (includeResolver != null) {
                INCLUDE_ARENA.set(Arena.ofShared());
            }
            try (Arena arena = Arena.ofConfined()) {
                final MemorySegment input = arena.allocate(INPUT_LAYOUT);
                V_LANGUAGE.set(input, 0L, GLSLANG_SOURCE_GLSL);
                V_STAGE.set(input, 0L, glslangStage);
                V_CLIENT.set(input, 0L, GLSLANG_CLIENT_VULKAN);
                V_CLIENT_VERSION.set(input, 0L, GLSLANG_TARGET_VULKAN_1_1);
                V_TARGET_LANGUAGE.set(input, 0L, GLSLANG_TARGET_SPV);
                V_TARGET_LANGUAGE_VERSION.set(input, 0L, GLSLANG_TARGET_SPV_1_3);
                final MemorySegment code = arena.allocateFrom(fullSource);
                V_CODE.set(input, 0L, code);
                // Force Vulkan-compatible 460 so shaderpack #version 120/330
                // sources are accepted in Vulkan client mode.
                V_DEFAULT_VERSION.set(input, 0L, FORCED_VULKAN_VERSION);
                V_DEFAULT_PROFILE.set(input, 0L, GLSLANG_NO_PROFILE);
                V_FORCE_DEFAULT.set(input, 0L, 1);
                V_MESSAGES.set(input, 0L, GLSLANG_MSG_DEFAULT_BIT);
                V_RESOURCE.set(input, 0L, defaultResource);

                // Wire include_local callback when a resolver is provided. The
                // callbacks struct is a nested group inside glslang_input_t; we
                // write the include_local function pointer at its byte offset
                // and leave include_system / free_include_result null — glslang
                // tolerates null system/free callbacks.
                if (includeResolver != null) {
                    input.set(ValueLayout.ADDRESS, CALLBACKS_OFFSET, MemorySegment.NULL);
                    input.set(ValueLayout.ADDRESS, INCLUDE_LOCAL_OFFSET, INCLUDE_LOCAL_UPCALL);
                }

                MemorySegment shader = MemorySegment.NULL;
                MemorySegment program = MemorySegment.NULL;
                try {
                    shader = (MemorySegment) glslangShaderCreate.invokeExact(input);
                    if (isNull(shader)) {
                        throw new ShaderCompileException("glslang_shader_create returned null", "");
                    }
                    glslangShaderSetOptions.invokeExact(shader, SHADER_OPTIONS);

                    int preprocessed = (int) glslangShaderPreprocess.invokeExact(shader, input);
                    if (preprocessed == 0) {
                        throw new ShaderCompileException("glslang preprocessing failed", readShaderLog(shader));
                    }

                    int parsed = (int) glslangShaderParse.invokeExact(shader, input);
                    if (parsed == 0) {
                        throw new ShaderCompileException("glslang parsing failed", readShaderLog(shader));
                    }

                    program = (MemorySegment) glslangProgramCreate.invokeExact();
                    glslangProgramAddShader.invokeExact(program, shader);

                    int linked = (int) glslangProgramLink.invokeExact(program, LINK_MESSAGES);
                    if (linked == 0) {
                        throw new ShaderCompileException("glslang linking failed", readProgramLog(program));
                    }

                    glslangProgramSpvGenerate.invokeExact(program, glslangStage);

                    long wordCount = (long) glslangProgramSpvGetSize.invokeExact(program);
                    if (wordCount <= 0L) {
                        throw new ShaderCompileException("glslang produced an empty SPIR-V binary", readProgramLog(program));
                    }

                    final MemorySegment spvBuf = arena.allocate(INT, wordCount);
                    glslangProgramSpvGet.invokeExact(program, spvBuf);
                    final int[] words = spvBuf.toArray(INT);

                    if (words.length < 5 || words[0] != SPIRV_MAGIC) {
                        throw new ShaderCompileException(
                                "glslang produced an invalid SPIR-V binary (bad magic header)",
                                readProgramLog(program));
                    }
                    return words;
                } catch (ShaderCompileException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new ShaderCompileException(
                            "glslang compilation failed: " + t.getMessage(),
                            readProgramLog(program),
                            t);
                } finally {
                    if (!isNull(program)) {
                        try {
                            glslangProgramDelete.invokeExact(program);
                        } catch (Throwable ignored) {
                            // best-effort cleanup
                        }
                    }
                    if (!isNull(shader)) {
                        try {
                            glslangShaderDelete.invokeExact(shader);
                        } catch (Throwable ignored) {
                            // best-effort cleanup
                        }
                    }
                }
            } finally {
                INCLUDE_RESOLVER.remove();
                Arena includeArena = INCLUDE_ARENA.get();
                if (includeArena != null) {
                    includeArena.close();
                    INCLUDE_ARENA.remove();
                }
            }
        }
    }

    /**
     * Upcall target for glslang's {@code include_local} callback. Reads the
     * header name from native memory, resolves it through the thread-local
     * {@link #INCLUDE_RESOLVER}, and returns a {@code glsl_include_result_t}
     * allocated in the per-compile {@link #INCLUDE_ARENA}.
     */
    private static MemorySegment includeLocalCallback(MemorySegment ctx, MemorySegment headerNameSeg,
                                                      MemorySegment includerNameSeg, long depth) {
        Function<String, String> resolver = INCLUDE_RESOLVER.get();
        if (resolver == null) {
            return MemorySegment.NULL;
        }
        String headerName = readCString(headerNameSeg);
        String contents = resolver.apply(headerName);
        if (contents == null) {
            return MemorySegment.NULL;
        }
        Arena arena = INCLUDE_ARENA.get();
        if (arena == null) {
            return MemorySegment.NULL;
        }
        byte[] bytes = contents.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment result = arena.allocate(INCLUDE_RESULT_LAYOUT);
        MemorySegment nameSeg = arena.allocateFrom(headerName);
        // allocateFrom(String) produces a null-terminated UTF-8 C string; the
        // header_length is the byte count without the terminator, matching
        // glslang's expectation.
        MemorySegment dataSeg = arena.allocateFrom(contents);
        V_IR_HEADER_NAME.set(result, 0L, nameSeg);
        V_IR_HEADER_DATA.set(result, 0L, dataSeg);
        V_IR_HEADER_LENGTH.set(result, 0L, (long) bytes.length);
        return result;
    }

    /**
     * Thrown when glslang fails to compile GLSL to SPIR-V. Carries the glslang
     * info log when one is available. Extends {@link RuntimeException} for
     * consistency with the rest of the bridge layer, but is declared on
     * {@link #compileGlslToSpv} so callers may catch it specifically.
     */
    public static final class ShaderCompileException extends RuntimeException {
        private final String infoLog;

        ShaderCompileException(String message, String infoLog) {
            super(appendLog(message, infoLog));
            this.infoLog = infoLog;
        }

        ShaderCompileException(String message, String infoLog, Throwable cause) {
            super(appendLog(message, infoLog), cause);
            this.infoLog = infoLog;
        }

        private static String appendLog(String message, String infoLog) {
            if (infoLog == null || infoLog.isEmpty()) {
                return message;
            }
            return message + "\n--- glslang info log ---\n" + infoLog;
        }
    }

    // --- process initialization (lazy, idempotent) ---

    private static void ensureProcessInitialized() {
        if (processInitialized) {
            return;
        }
        synchronized (GlslangBridge.class) {
            if (processInitialized) {
                return;
            }
            try {
                int rc = (int) glslangInitializeProcess.invokeExact();
                if (rc == 0) {
                    throw new IllegalStateException("glslang_initialize_process() returned 0 (initialization failed)");
                }
                // glslang_default_resource() returns a pointer to process-static
                // data; safe to cache for the JVM lifetime.
                defaultResource = (MemorySegment) glslangDefaultResource.invokeExact();
                processInitialized = true;
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to initialize glslang process", t);
            }
        }
    }

    // --- native library loading ---

    private static SymbolLookup createGlslangSymbolLookup() throws IOException {
        if (MetalNativeBridge.isIOS()) {
            return createIOSGlslangLookup();
        }
        return createMacOSGlslangLookup();
    }

    /**
     * macOS: extract the bundled {@code libglslang.dylib} (and any sibling
     * dylibs present in the jar) into a single temp directory and
     * {@code System.load} them dependency-first, then expose their symbols via
     * {@link SymbolLookup#loaderLookup()}. Sibling dylibs that are not bundled
     * (e.g. when the build produced a single fat {@code libglslang.dylib}) are
     * silently skipped; only the primary {@code libglslang.dylib} is mandatory.
     */
    private static SymbolLookup createMacOSGlslangLookup() throws IOException {
        Path tempDir = Files.createTempDirectory("glslang-native-");
        tempDir.toFile().deleteOnExit();
        boolean primaryLoaded = false;
        for (String resourcePath : GLSLANG_MACOS_RESOURCES) {
            try (InputStream stream = GlslangBridge.class.getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    continue; // sibling not bundled in this build
                }
                String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
                Path lib = tempDir.resolve(fileName);
                Files.copy(stream, lib, StandardCopyOption.REPLACE_EXISTING);
                lib.toFile().deleteOnExit();
                try {
                    System.load(lib.toString());
                } catch (UnsatisfiedLinkError e) {
                    // A sibling may fail to load if its own deps are unsatisfiable;
                    // only the primary dylib is mandatory.
                    if (resourcePath.equals(GLSLANG_MACOS_PRIMARY_RESOURCE)) {
                        throw e;
                    }
                }
                if (resourcePath.equals(GLSLANG_MACOS_PRIMARY_RESOURCE)) {
                    primaryLoaded = true;
                }
            }
        }
        if (!primaryLoaded) {
            throw new IllegalStateException("Missing native library resource: " + GLSLANG_MACOS_PRIMARY_RESOURCE);
        }
        return SymbolLookup.loaderLookup();
    }

    /**
     * iOS: {@link MetalNativeBridge#ensureGlslangLibraryConfigured()} (invoked
     * in the static initializer) has already extracted and {@code System.load}ed
     * the bundled {@code libglslang.dylib} from {@code /natives/ios/} into a
     * writable directory (via Amethyst's hooked {@code dlopen}). Additionally
     * try the app bundle's {@code Frameworks/} directory through
     * {@code java.library.path}, then resolve symbols via
     * {@link SymbolLookup#loaderLookup()}.
     */
    private static SymbolLookup createIOSGlslangLookup() {
        try {
            System.loadLibrary("glslang");
        } catch (UnsatisfiedLinkError ignored) {
            // Not in Frameworks/; rely on the dylib loaded by ensureGlslangLibraryConfigured().
        }
        return SymbolLookup.loaderLookup();
    }

    // --- helpers ---

    private static String readShaderLog(MemorySegment shader) {
        if (isNull(shader)) {
            return "";
        }
        try {
            MemorySegment ptr = (MemorySegment) glslangShaderGetInfoLog.invokeExact(shader);
            String log = readCString(ptr);
            MemorySegment debugPtr = (MemorySegment) glslangShaderGetInfoDebugLog.invokeExact(shader);
            String debugLog = readCString(debugPtr);
            return joinLogs(log, debugLog);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readProgramLog(MemorySegment program) {
        if (isNull(program)) {
            return "";
        }
        try {
            MemorySegment ptr = (MemorySegment) glslangProgramGetInfoLog.invokeExact(program);
            return readCString(ptr);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String joinLogs(String primary, String debug) {
        if (debug == null || debug.isEmpty()) {
            return primary == null ? "" : primary;
        }
        if (primary == null || primary.isEmpty()) {
            return debug;
        }
        return primary + "\n--- debug log ---\n" + debug;
    }

    private static String readCString(MemorySegment ptr) {
        if (isNull(ptr)) {
            return "";
        }
        try {
            return ptr.reinterpret(CSTRING_MAX).getString(0);
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.address() == 0L;
    }

    private static MethodHandle downcall(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor, Linker.Option.critical(false));
    }
}
