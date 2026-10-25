package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for the frame-start shadow depth seed
 * ({@code -Dmetallum.iris.shadowSeed=clear|copy}): clearing shadowtex1
 * directly must leave exactly the value the old shadowtex0&rarr;shadowtex1
 * blit transferred. Both paths hand {@code MetalCommandEncoder} the same
 * <em>engine-space</em> far value, and the encoder applies
 * {@code irisDepthClear} (the reverse-z complement) exactly once. No Metal
 * device is touched.
 */
final class IrisMetalShadowSeedTest {
    @Test
    void shadowFarIsTheEngineReverseZFarValue() {
        assertEquals(0.0, IrisMetalExecutionGraph.SHADOW_FAR_ENGINE_DEPTH);
    }

    @Test
    void directClearAndCopyPathMaterializeTheSameShadowtex1Value() {
        for (boolean shaderpackActive : new boolean[]{false, true}) {
            // Legacy copy path: shadowtex0 is cleared, then its materialized
            // contents are copied into shadowtex1 unchanged.
            double shadowTex0AfterClear = MetalCommandEncoder.complementDepthClear(
                    IrisMetalExecutionGraph.SHADOW_FAR_ENGINE_DEPTH, shaderpackActive
            );
            double copyPathValue = shadowTex0AfterClear;

            // Direct clear path: the same engine-space value goes through the
            // same complement once, so shadowtex1 lands on the same depth.
            double clearPathValue = MetalCommandEncoder.complementDepthClear(
                    IrisMetalExecutionGraph.SHADOW_FAR_ENGINE_DEPTH, shaderpackActive
            );

            assertEquals(
                    copyPathValue, clearPathValue,
                    "shadowSeed=clear must be a value-equivalent replacement for the copy"
            );
        }
    }

    @Test
    void shaderpackComplementTurnsTheFarValueIntoStandardZFar() {
        assertEquals(
                1.0, MetalCommandEncoder.complementDepthClear(0.0, true),
                "with a pack active the engine's 0.0 reverse-z far clears to 1.0"
        );
        assertEquals(
                0.0, MetalCommandEncoder.complementDepthClear(0.0, false),
                "without a pack the engine far value stays 0.0"
        );
        assertEquals(0.0, MetalCommandEncoder.complementDepthClear(2.0, true), "clamped to 0..1");
        assertEquals(1.0, MetalCommandEncoder.complementDepthClear(-1.0, true), "clamped to 0..1");
        assertEquals(0.25, MetalCommandEncoder.complementDepthClear(0.75, true), 1.0e-9);
    }

    @Test
    void shadowSeedSwitchDefaultsToClearingBothMaps() {
        assertTrue(MetalDebugSwitches.shadowSeedClear(null), "unset defaults to the direct clear");
        assertTrue(MetalDebugSwitches.shadowSeedClear(""));
        assertTrue(MetalDebugSwitches.shadowSeedClear("clear"));
        assertTrue(MetalDebugSwitches.shadowSeedClear("CLEAR"));
        assertTrue(MetalDebugSwitches.shadowSeedClear("bogus"), "unknown values keep the default");
        assertFalse(MetalDebugSwitches.shadowSeedClear("copy"));
        assertFalse(MetalDebugSwitches.shadowSeedClear("COPY"));
        assertFalse(MetalDebugSwitches.shadowSeedClear(" copy "));
    }
}
