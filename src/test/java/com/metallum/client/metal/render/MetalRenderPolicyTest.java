package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for the render-attachment view policy and blit batching:
 * <ul>
 *   <li>{@code attachmentViews=full} (default) must select a view that hands
 *       out the texture's own native handle, so a mipmapped attachment no
 *       longer changes handle between passes and the encoder can stay open.</li>
 *   <li>{@code attachmentViews=subresource} must reproduce the old level-0-only
 *       view (which only churns for mipmapped textures).</li>
 *   <li>Blit batching may only reuse an encoder that is already a blit
 *       encoder; any other encoder boundary (or {@code blitBatch=off}) ends
 *       it.</li>
 * </ul>
 * No Metal device is touched.
 */
final class MetalRenderPolicyTest {
    @Test
    void fullRangeAttachmentViewKeepsTheTextureHandle() {
        MetalRenderPolicy.AttachmentViewRange range =
                MetalRenderPolicy.attachmentViewRange(true, 6);

        assertEquals(0, range.baseMipLevel());
        assertEquals(6, range.mipLevels());
        assertTrue(
                MetalRenderPolicy.usesTextureHandle(range, 6),
                "a full-range view returns the texture itself, so sameAttachmentHandles() is stable"
        );
    }

    @Test
    void subresourceAttachmentViewOfAMipmappedTargetChurnsNativeViews() {
        MetalRenderPolicy.AttachmentViewRange range =
                MetalRenderPolicy.attachmentViewRange(false, 6);

        assertEquals(0, range.baseMipLevel());
        assertEquals(1, range.mipLevels());
        assertFalse(
                MetalRenderPolicy.usesTextureHandle(range, 6),
                "a level-0-only view of a mipmapped texture allocates a native view per pass"
        );
    }

    @Test
    void subresourceModeCannotRegressSingleLevelTargets() {
        for (boolean fullRange : new boolean[]{true, false}) {
            MetalRenderPolicy.AttachmentViewRange range =
                    MetalRenderPolicy.attachmentViewRange(fullRange, 1);
            assertTrue(
                    MetalRenderPolicy.usesTextureHandle(range, 1),
                    "single-level targets (colortex without mipmaps, depthtex0/1/2) always shared the texture handle"
            );
        }
    }

    @Test
    void malformedMipCountIsTreatedAsOneLevel() {
        MetalRenderPolicy.AttachmentViewRange range =
                MetalRenderPolicy.attachmentViewRange(true, 0);

        assertEquals(1, range.mipLevels());
        assertTrue(MetalRenderPolicy.usesTextureHandle(range, 0));
    }

    @Test
    void blitReuseRequiresBatchingAndAnOpenBlitEncoder() {
        assertTrue(MetalRenderPolicy.reuseBlitEncoder(true, true));
        assertFalse(
                MetalRenderPolicy.reuseBlitEncoder(true, false),
                "a render/compute encoder must be ended before a blit can encode"
        );
        assertFalse(
                MetalRenderPolicy.reuseBlitEncoder(false, true),
                "blitBatch=off ends the blit encoder after every copy"
        );
        assertFalse(MetalRenderPolicy.reuseBlitEncoder(false, false));
    }

    @Test
    void switchesDefaultToTheOptimizedBehavior() {
        assertTrue(MetalDebugSwitches.attachmentViewsFull(null), "unset defaults to full-range views");
        assertTrue(MetalDebugSwitches.attachmentViewsFull("full"));
        assertTrue(MetalDebugSwitches.attachmentViewsFull("bogus"), "unknown values keep the default");
        assertFalse(MetalDebugSwitches.attachmentViewsFull("subresource"));
        assertFalse(MetalDebugSwitches.attachmentViewsFull("SUBRESOURCE"));

        assertTrue(MetalDebugSwitches.blitBatchEnabled(null), "unset defaults to batched blits");
        assertTrue(MetalDebugSwitches.blitBatchEnabled("on"));
        assertTrue(MetalDebugSwitches.blitBatchEnabled("bogus"), "unknown values keep the default");
        assertFalse(MetalDebugSwitches.blitBatchEnabled("off"));
        assertFalse(MetalDebugSwitches.blitBatchEnabled("OFF"));
    }

    @Test
    void batchedCopiesShareOneEncoderUntilOtherWorkArrives() {
        assertEquals(1, countBlitEncoders(true, List.of("copy", "copy", "copy")));
        assertEquals(
                2,
                countBlitEncoders(true, List.of("copy", "copy", "end", "copy")),
                "a pending-clear flush or a render pass tears the batched encoder down"
        );
        assertEquals(
                3,
                countBlitEncoders(false, List.of("copy", "copy", "copy")),
                "blitBatch=off keeps the historical one-encoder-per-copy behavior"
        );
    }

    /**
     * Minimal model of {@code MetalCommandEncoder.blitCommandEncoder()}: each
     * {@code copy} joins the open blit encoder when the policy allows it, and
     * any other encoder work ({@code end}) closes the open blit encoder.
     */
    private static int countBlitEncoders(final boolean batching, final List<String> operations) {
        int created = 0;
        boolean openBlit = false;
        for (String operation : operations) {
            if ("copy".equals(operation)) {
                if (!MetalRenderPolicy.reuseBlitEncoder(batching, openBlit)) {
                    created++;
                }
                openBlit = true;
            } else {
                openBlit = false;
            }
        }
        return created;
    }
}
