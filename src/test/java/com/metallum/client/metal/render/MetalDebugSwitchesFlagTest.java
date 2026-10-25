package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for {@link MetalDebugSwitches#enabledFlag(String)}: the
 * enable-style debug switches must accept a bare JVM argument
 * ({@code -Dmetallum.iris.debug.dumpBindings}, whose property value is the
 * empty string) as "on", while {@code =false}, any other value and an unset
 * property stay "off". The user hit the bare-parameter case in the field:
 * {@code Boolean.getBoolean} silently read it as false.
 *
 * <p>The test only touches its own property key, so the class's static switch
 * table sees the normal all-off defaults.
 */
final class MetalDebugSwitchesFlagTest {
    private static final String FLAG = "metallum.iris.test.enabledFlag";

    @Test
    void enabledFlagAcceptsBareParameterAndExplicitTrueOnly() {
        System.clearProperty(FLAG);
        try {
            assertFalse(MetalDebugSwitches.enabledFlag(FLAG), "unset must be off");
            System.setProperty(FLAG, "");
            assertTrue(MetalDebugSwitches.enabledFlag(FLAG), "bare -Dflag must be on");
            System.setProperty(FLAG, "true");
            assertTrue(MetalDebugSwitches.enabledFlag(FLAG), "=true must be on");
            System.setProperty(FLAG, "TRUE");
            assertTrue(MetalDebugSwitches.enabledFlag(FLAG), "=TRUE must be on");
            System.setProperty(FLAG, "false");
            assertFalse(MetalDebugSwitches.enabledFlag(FLAG), "=false must be off");
            System.setProperty(FLAG, "1");
            assertFalse(MetalDebugSwitches.enabledFlag(FLAG), "only empty or true are on");
        } finally {
            System.clearProperty(FLAG);
        }
    }
}
