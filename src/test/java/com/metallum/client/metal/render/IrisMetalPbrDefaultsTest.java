package com.metallum.client.metal.render;

import net.irisshaders.iris.pbr.texture.PBRType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the neutral PBR defaults the raster bridges bind for the level sampler
 * names {@code normals}/{@code specular} when no PBR resource pack texture was
 * loaded.
 *
 * <p>Upstream Iris routes those names through
 * {@code PBRTextureManager.defaultHolder}, which uses
 * {@code PBRType.NORMAL.getDefaultValue()} (RGBA 127/127/255/255, flat) and
 * {@code PBRType.SPECULAR.getDefaultValue()} (RGBA 0/0/0/0, none). Sildur's
 * {@code nMap>=1} and Unbound's labPBR options activate the samplers; binding
 * white for both would push terrain normals sideways and add phantom specular.
 */
final class IrisMetalPbrDefaultsTest {
    @Test
    void neutralValuesMatchUpstreamPbrTypeDefaults() {
        assertEquals(PBRType.NORMAL.getDefaultValue(), IrisMetalPbrDefaults.NORMAL_DEFAULT_RGBA);
        assertEquals(PBRType.SPECULAR.getDefaultValue(), IrisMetalPbrDefaults.SPECULAR_DEFAULT_RGBA);
        assertEquals(0x7F7FFFFF, IrisMetalPbrDefaults.NORMAL_DEFAULT_RGBA);
        assertEquals(0x00000000, IrisMetalPbrDefaults.SPECULAR_DEFAULT_RGBA);
    }

    @Test
    void pbrNamesAreExactlyNormalsAndSpecular() {
        assertTrue(IrisMetalPbrDefaults.isPbrSampler("normals"));
        assertTrue(IrisMetalPbrDefaults.isPbrSampler("specular"));
        for (String name : new String[]{
                "noisetex", "normals2", "specular0", "gaux1", "colortex0", "tex", ""
        }) {
            assertFalse(IrisMetalPbrDefaults.isPbrSampler(name), name + " must not be a PBR sampler");
        }
    }
}
