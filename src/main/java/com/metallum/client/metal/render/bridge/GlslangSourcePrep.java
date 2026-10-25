package com.metallum.client.metal.render.bridge;

import com.metallum.client.metal.render.MetalDebugSwitches;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java GLSL source preparation shared by {@link GlslangBridge}.
 *
 * <p>Separated from the bridge so Linux gates can pin the exact source glslang
 * receives without triggering the native-library static initializer (which
 * throws on non-macOS hosts). Mirrors the existing behavior: a compatibility
 * preamble with Iris/Minecraft defines, the raster {@code #version} directive
 * stripped (glslang forces 460 through
 * {@code force_default_version_and_profile}), and user defines appended before
 * the source.
 */
public final class GlslangSourcePrep {
    /**
     * Compatibility macros injected before the source when compiling raw
     * shaderpack GLSL. These are normally supplied by Iris's TransformPatcher;
     * defining them here lets the glslang frontend parse shaderpack sources
     * that branch on them ({@code IS_IRIS}, {@code MC_VERSION}, ...).
     */
    private static final String COMPAT_PREAMBLE = String.join("\n",
            "#define IS_IRIS 1",
            "#define MC_VERSION 12111",
            "#define MC_GLSL_VERSION 460",
            "#define MC_GL_VERSION 460",
            "#define MC_RENDER_QUALITY 1.0",
            "#define MC_SHADOW_QUALITY 1.0",
            "#define MC_NORMAL_MAP",
            "#define MC_SPECULAR_MAP",
            "#define METALLUM_GLSLANG_FRONTEND 1",
            ""
    );

    /**
     * Default precision for float/int. Vulkan-mode glslang gives an
     * unqualified float parameter no precision; declaring the defaults keeps
     * user code aligned with desktop-GL behavior. Kept as defense-in-depth:
     * the builtin-redeclaration failure that originally motivated it (Mellow's
     * {@code fma}) is actually handled by {@link #RESERVED_USER_FUNCTIONS},
     * because no parameter precision can satisfy glslang's overload check.
     * Inert when {@link MetalDebugSwitches#GLSLANG_PRECISION_PREAMBLE} is
     * disabled.
     */
    private static final String PRECISION_PREAMBLE = String.join("\n",
            "precision highp float;",
            "precision highp int;",
            ""
    );

    /**
     * User-defined function names that must be renamed before compiling a
     * pack source, in two classes:
     *
     * <ul>
     *   <li>GLSL builtins that {@code #version 120}-era packs legitimately
     *       define themselves because they only exist in later versions
     *       (Mellow's SMAA block defines {@code fma}; Bliss defines
     *       {@code tanh}). Forcing a modern Vulkan-compatible version makes
     *       glslang reject the redeclaration ("overloaded functions must have
     *       the same parameter precision qualifiers") and no source precision
     *       can satisfy that check.</li>
     *   <li>Metal namespace names that SPIRV-Cross emits unqualified in
     *       generated sampling code (Bliss's gbuffers define {@code bias()},
     *       which collides with {@code metal::bias} used for sampling with
     *       bias). The MSL compiler reports "reference to 'bias' is
     *       ambiguous".</li>
     * </ul>
     *
     * Renaming the pack's definition and every reference to it keeps the
     * pack's intended math: at its original version, all of its calls already
     * targeted its own function. Gated by
     * {@link MetalDebugSwitches#GLSLANG_BUILTIN_RENAME}.
     */
    private static final List<String> RESERVED_USER_FUNCTIONS = List.of(
            "fma", "tanh", "sinh", "cosh", "round", "trunc", "roundEven",
            "isnan", "isinf", "frexp", "ldexp", "findLSB", "findMSB",
            "bias", "level", "gradient2d", "gradient3d", "gradientcube"
    );
    private static final Pattern RESERVED_USER_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:const[ \\t]+)?"
                    + "(?:void|float|double|int|uint|bool|"
                    + "vec[234]|dvec[234]|ivec[234]|uvec[234]|bvec[234]|"
                    + "mat[234](?:x[234])?|dmat[234](?:x[234])?)[ \\t]+"
                    + "(" + String.join("|", RESERVED_USER_FUNCTIONS) + ")[ \\t]*\\("
    );
    private static final String RENAMED_USER_FUNCTION_PREFIX = "metallum_user_";

    private GlslangSourcePrep() {
    }

    /** The preamble currently in effect, including the optional precision defaults. */
    public static String compatPreamble() {
        return MetalDebugSwitches.GLSLANG_PRECISION_PREAMBLE
                ? COMPAT_PREAMBLE + PRECISION_PREAMBLE
                : COMPAT_PREAMBLE;
    }

    /**
     * The exact source glslang receives for a raw shaderpack source: version
     * stripped, builtin-colliding user functions renamed, compatibility
     * preamble (plus the precision defaults) injected and user defines
     * appended.
     */
    public static String buildFullSource(final String source, final String defines) {
        return buildSourceWithDefines(
                compatPreamble() + renameBuiltinCollisions(stripVersionDirective(source)),
                defines
        );
    }

    /**
     * Renames user-defined functions that collide with a GLSL builtin once the
     * source is forced to a modern version (see
     * {@link #RESERVED_USER_FUNCTIONS}). Only applies when the source actually
     * declares such a function; then every reference in the translation unit
     * is renamed so declarations, prototypes and calls stay consistent. Inert
     * when {@link MetalDebugSwitches#GLSLANG_BUILTIN_RENAME} is disabled.
     */
    static String renameBuiltinCollisions(final String source) {
        if (!MetalDebugSwitches.GLSLANG_BUILTIN_RENAME) {
            return source;
        }
        Matcher matcher = RESERVED_USER_DECLARATION.matcher(source);
        Set<String> declared = new LinkedHashSet<>();
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        if (declared.isEmpty()) {
            return source;
        }
        String result = source;
        for (String name : declared) {
            result = Pattern.compile("\\b" + name + "\\b").matcher(result)
                    .replaceAll(Matcher.quoteReplacement(RENAMED_USER_FUNCTION_PREFIX + name));
        }
        return result;
    }

    static String stripVersionDirective(final String source) {
        // Find the #version line in the leading run of comments / whitespace.
        String[] lines = source.split("\\R", -1);
        int versionLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("#version")) {
                versionLine = i;
                break;
            }
            // Allow leading comments and blank lines before #version.
            if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")) {
                // Non-comment, non-blank, non-#version line — #version won't
                // appear after real code, stop looking.
                break;
            }
        }
        if (versionLine < 0) {
            return source;
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < lines.length; i++) {
            if (i == versionLine) continue;
            if (!first) sb.append('\n');
            sb.append(lines[i]);
            first = false;
        }
        return sb.toString();
    }

    private static String buildSourceWithDefines(final String source, final String defines) {
        if (defines == null || defines.isBlank()) {
            return source;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : defines.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                sb.append(trimmed).append('\n');
            } else {
                sb.append("#define ").append(trimmed).append('\n');
            }
        }
        sb.append("#line 1\n");
        sb.append(source);
        return sb.toString();
    }
}
