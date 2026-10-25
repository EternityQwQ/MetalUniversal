package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import org.jspecify.annotations.Nullable;

/**
 * M6.1.1 shadow caster GL-depth emulation.
 *
 * <p>Shaderpack shadow vertex shaders are written for OpenGL: they may scale
 * {@code gl_Position.z} (BSL's {@code shadow.glsl} does {@code *= 0.2}) and
 * rely on the fixed-function viewport transform that maps GL NDC
 * {@code z in [-1,1]} to depth {@code [0,1]}. Metal's clip space already is
 * {@code [0,1]}, so without the transform the caster stores a value 0.4 away
 * from what the consumer's {@code DistortShadow} reference
 * ({@code 0.5 + 0.1 * z_pack}) expects, and {@code LessEqual} fails for every
 * texel ("shadow=0 everywhere").
 *
 * <p>The fix renames the original vertex {@code main} through a preprocessor
 * define and appends a wrapper that runs it and applies the GL viewport depth
 * transform. Only shadow-group programs get it; fragment compiles and all
 * other programs are untouched.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalShadowDepthFix {
    /** Entry point the original vertex {@code main} is renamed to. */
    static final String WRAPPED_MAIN = "iris_metal_shadow_main";

    /**
     * Vertex-stage define line. {@code GlslangBridge} wraps non-{@code #} lines
     * as {@code #define <line>}, so this renames the source's {@code main}.
     */
    static final String VERTEX_DEFINE = "main " + WRAPPED_MAIN;

    /**
     * Appended after the original vertex source: {@code #undef main} restores
     * the real entry-point name and the wrapper performs GL's NDC&rarr;depth
     * viewport mapping in clip space.
     */
    private static final String TAIL = "\n#undef main\n"
            + "void main() {\n"
            + "    " + WRAPPED_MAIN + "();\n"
            + "    gl_Position.z = gl_Position.z * 0.5 + 0.5 * gl_Position.w;\n"
            + "}\n";

    private IrisMetalShadowDepthFix() {
    }

    /** Whether the requested program belongs to the shadow caster group. */
    static boolean appliesTo(final @Nullable ProgramId requested) {
        return requested != null && requested.getGroup() == ProgramGroup.Shadow;
    }

    /** Appends the GL-depth wrapper to the vertex source when enabled. */
    static String vertexSource(final String source, final boolean enabled) {
        return enabled ? source + TAIL : source;
    }

    /** Adds the {@code main} rename define for the vertex stage when enabled. */
    static @Nullable String vertexDefines(final @Nullable String defines, final boolean enabled) {
        if (!enabled) {
            return defines;
        }
        return defines == null || defines.isBlank()
                ? VERTEX_DEFINE
                : defines + "\n" + VERTEX_DEFINE;
    }
}
