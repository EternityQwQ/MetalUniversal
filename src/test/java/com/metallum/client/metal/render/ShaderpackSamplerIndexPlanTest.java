package com.metallum.client.metal.render;

import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure (native-free) unit tests for the shaderpack per-stage compact sampler
 * index plan that works around Metal's 16-sampler-per-stage limit.
 *
 * <p>Real-world reference: Complementary Reimagined's {@code deferred1}
 * fragment stage declares 23 combined image samplers and uses 7 of them
 * (colortex0/3/4/6, depthtex0, noisetex, shadowtex0), which previously produced
 * {@code [[sampler(17)]]}/{@code [[sampler(23)]]} and failed MSL compilation.
 */
final class ShaderpackSamplerIndexPlanTest {
    private static final int LIMIT = ShaderpackSamplerIndexPlan.MAX_METAL_SAMPLERS_PER_STAGE;

    @Test
    void crLikePlanCompactsSevenActiveImagesOntoZeroThroughSix() throws Exception {
        List<String> declared = declaredImages(23);
        Set<String> active = Set.of(
                "image0", "image2", "image3", "image5", "image9", "image17", "image22"
        );

        Map<String, Integer> plan = ShaderpackSamplerIndexPlan.assignSampledImageIndices(
                declared, active, "ComplementaryReimagined deferred1 fragment"
        );

        assertEquals(7, plan.size(), () -> "plan: " + plan);
        // Declaration-order assignment: the i-th active declared image gets i.
        int expected = 0;
        for (String name : declared) {
            Integer index = plan.get(name);
            if (index == null) {
                continue;
            }
            assertEquals(expected, index, "active image " + name + " got a non-compact index; plan=" + plan);
            expected++;
        }
        assertEquals(6, plan.get("image22"), "plan: " + plan);
        for (Integer index : plan.values()) {
            assertNotNull(index);
            assertEquals(0, index < LIMIT ? 0 : 1, "index " + index + " exceeds the Metal per-stage limit of " + LIMIT);
        }
    }

    @Test
    void sixteenActiveImagesFitExactlyAtTheLimit() throws Exception {
        List<String> declared = declaredImages(16);

        Map<String, Integer> plan = ShaderpackSamplerIndexPlan.assignSampledImageIndices(
                declared, new LinkedHashSet<>(declared), "sixteen-active"
        );

        assertEquals(16, plan.size());
        assertEquals(0, plan.get("image0"));
        assertEquals(15, plan.get("image15"));
    }

    @Test
    void seventeenthActiveImageIsRejectedWithStageNameAndResourceName() {
        List<String> declared = declaredImages(23);
        Set<String> active = new LinkedHashSet<>(declared.subList(0, 17));

        ShaderCompileException failure = assertThrows(
                ShaderCompileException.class,
                () -> ShaderpackSamplerIndexPlan.assignSampledImageIndices(
                        declared, active, "ComplementaryReimagined deferred1 fragment"
                )
        );

        String message = failure.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("ComplementaryReimagined deferred1 fragment"), message);
        assertTrue(message.contains("image16"), message);
        assertTrue(message.contains("[[sampler(" + LIMIT + ")]]"), message);
        assertTrue(message.contains("at most " + LIMIT), message);
        assertTrue(message.contains("image15"), message);
    }

    @Test
    void bslLikePlanCompactsFiveActiveImagesOntoZeroThroughFour() throws Exception {
        List<String> declared = declaredImages(5);
        Set<String> active = Set.of("image0", "image1", "image2", "image3", "image4");

        Map<String, Integer> plan = ShaderpackSamplerIndexPlan.assignSampledImageIndices(
                declared, active, "BSL deferred fragment"
        );

        assertEquals(5, plan.size());
        assertEquals(0, plan.get("image0"));
        assertEquals(4, plan.get("image4"));
        assertNull(plan.get("image99"), "unknown names must never get an index");
    }

    private static List<String> declaredImages(final int count) {
        List<String> declared = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            declared.add("image" + index);
        }
        return declared;
    }
}
