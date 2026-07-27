package com.metallum.client.metal.render;

/**
 * Bytecode signatures the motion hooks inject against.
 *
 * <p>These live in a constant so the mixin annotation and the test that checks the
 * signature still exists cannot drift apart. All of them are compile-time
 * constants, which is what lets an annotation reference them; the compiler inlines
 * the value into the class file, so Mixin sees a plain string.</p>
 *
 * <p>A signature that stops matching after a Minecraft update would otherwise
 * surface only when the mixin config loads — during client startup, long after the
 * build passed. {@code MetalMotionHookDescriptorTest} turns that into a build
 * failure that names the method whose shape changed.</p>
 */
public final class MetalMotionHooks {
    public static final String MODEL_BLOCK_RENDERER_CLASS = "net.minecraft.client.renderer.block.ModelBlockRenderer";
    public static final String MOVING_BLOCK_SUBMIT_CLASS =
            "net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer$Submit";

    public static final String MOVING_BLOCK_FEATURE_RENDERER_CLASS =
            "net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer";

    /**
     * The method the moving-block wrapper is scoped to. Independent of the descriptor
     * below: a rename here leaves the descriptor valid and the injection unplaceable.
     */
    public static final String BUILD_GROUP_METHOD = "buildGroup";

    public static final String TESSELATE_BLOCK_NAME = "tesselateBlock";

    /**
     * {@code ModelBlockRenderer.tesselateBlock}. The fifth parameter is declared
     * {@code BlockAndTintGetter}, and at the moving-block call site the argument is
     * the submit's own {@code MovingBlockRenderState}, which is the key the motion
     * sample was recorded under.
     */
    public static final String TESSELATE_BLOCK_DESCRIPTOR =
            "(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFF"
                    + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                    + "Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V";

    /** Full Mixin {@code @At} target for the moving-block tesselation call. */
    public static final String TESSELATE_BLOCK_TARGET =
            "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;"
                    + TESSELATE_BLOCK_NAME + TESSELATE_BLOCK_DESCRIPTOR;

    /** {@code MovingBlockFeatureRenderer.Submit(Matrix4fc, MovingBlockRenderState, int)}. */
    public static final String MOVING_BLOCK_SUBMIT_DESCRIPTOR =
            "(Lorg/joml/Matrix4fc;"
                    + "Lnet/minecraft/client/renderer/block/MovingBlockRenderState;I)V";

    private MetalMotionHooks() {
    }
}
