package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for the demand-driven depthtex0 capture
 * ({@code -Dmetallum.iris.debug.depthCapture=lazy|eager}). The first non-sky
 * world takeover only seeds depthtex0 when the program it is about to encode
 * actually declares {@code depthtex0}; the {@code eager} rollback restores the
 * previous unconditional capture. No Metal device is touched.
 */
final class IrisMetalWorldBridgeDepthCaptureTest {
    private static final boolean DECLARES = true;
    private static final boolean NO_DECLARATION = false;

    private static IrisMetalGlslLinker.SamplerDecl sampler(final String name, final String type) {
        return new IrisMetalGlslLinker.SamplerDecl(name, type);
    }

    private static boolean capture(
            final boolean lazy,
            final boolean alreadyCaptured,
            final boolean sky,
            final boolean declaresDepthtex0,
            final boolean mainSceneDepth
    ) {
        return IrisMetalWorldBridge.shouldCaptureMainDepth(
                lazy, alreadyCaptured, sky, mainSceneDepth, () -> declaresDepthtex0
        );
    }

    @Test
    void lazyCaptureRequiresTheProgramToDeclareDepthtex0() {
        assertFalse(
                capture(true, false, false, NO_DECLARATION, true),
                "a program that never samples depthtex0 must not pay for the full-screen copy"
        );
        assertTrue(capture(true, false, false, DECLARES, true));
    }

    @Test
    void eagerCaptureIgnoresTheDeclaration() {
        assertTrue(
                capture(false, false, false, NO_DECLARATION, true),
                "depthCapture=eager restores the unconditional once-per-frame capture"
        );
    }

    @Test
    void skyAlreadyCapturedAndForeignDepthNeverCapture() {
        assertFalse(
                capture(true, false, true, DECLARES, true),
                "sky renders before terrain depth exists"
        );
        assertFalse(
                capture(true, true, false, DECLARES, true),
                "at most one lazy capture per frame"
        );
        assertFalse(
                capture(true, false, false, DECLARES, false),
                "weather / improved-transparency passes own their own depth buffer"
        );
        assertFalse(
                capture(false, false, false, DECLARES, false),
                "the eager path still requires the vanilla main scene depth attachment"
        );
    }

    @Test
    void declarationScanIsSkippedWhenAnEarlierGateRejects() {
        AtomicInteger scans = new AtomicInteger();
        boolean captured = IrisMetalWorldBridge.shouldCaptureMainDepth(
                true, true, false, true, () -> {
                    scans.incrementAndGet();
                    return true;
                }
        );

        assertFalse(captured);
        assertEquals(0, scans.get(), "no per-pass sampler scan once depthtex0 is already seeded");
    }

    @Test
    void declarationScanOnlyCountsSampledDeclarations() {
        assertTrue(IrisMetalWorldBridge.declaresDepthtex0(List.of(
                sampler("gtexture", "texture2D"),
                sampler("depthtex0", "texture2D")
        )));
        assertFalse(IrisMetalWorldBridge.declaresDepthtex0(List.of(
                sampler("depthtex1", "texture2D"),
                sampler("gtexture", "texture2D")
        )), "depthtex1 is not depthtex0");
        assertFalse(IrisMetalWorldBridge.declaresDepthtex0(List.of(
                sampler("depthtex0", "image2D")
        )), "a storage image named depthtex0 is not the depth sampler");
        assertFalse(IrisMetalWorldBridge.declaresDepthtex0(List.of()));
    }

    @Test
    void depthCaptureSwitchDefaultsToLazy() {
        assertTrue(MetalDebugSwitches.depthCaptureLazy(null), "unset defaults to the lazy capture");
        assertTrue(MetalDebugSwitches.depthCaptureLazy(""));
        assertTrue(MetalDebugSwitches.depthCaptureLazy("lazy"));
        assertTrue(MetalDebugSwitches.depthCaptureLazy("LAZY"));
        assertTrue(MetalDebugSwitches.depthCaptureLazy("bogus"), "unknown values keep the default");
        assertFalse(MetalDebugSwitches.depthCaptureLazy("eager"));
        assertFalse(MetalDebugSwitches.depthCaptureLazy("EAGER"));
        assertFalse(MetalDebugSwitches.depthCaptureLazy(" eager "));
    }
}
