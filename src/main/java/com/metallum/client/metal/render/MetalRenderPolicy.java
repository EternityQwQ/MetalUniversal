package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Pure policy decisions behind the low-risk render-encoder optimizations, kept
 * in one native-free place so each choice can be unit tested against the
 * {@code MetalDebugSwitches} rollback switches without a Metal device.
 *
 * <p>Two invariants live here:</p>
 * <ul>
 *   <li><b>Attachment view range.</b> {@code MetalGpuTextureView.nativeHandle()}
 *       hands out the texture itself only for a view whose
 *       {@code baseMipLevel == 0} and {@code mipLevels >=
 *       texture.getMipLevels()}; a level-0-only view of a mipmapped texture
 *       creates a fresh native view every time. Because the encoder reuse test
 *       compares attachment <em>handles</em>, the legacy subresource view tore
 *       the encoder down on every pass of a mipmapped target. The default full
 *       range keeps the handle stable (rendering still only writes level 0).</li>
 *   <li><b>Blit batching.</b> A blit encoder may be reused only when batching
 *       is enabled <em>and</em> the open encoder already is a blit encoder;
 *       any other encoder kind (or batching off) must end it first.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
final class MetalRenderPolicy {
    private MetalRenderPolicy() {
    }

    /** Mip range of one render-attachment view (always starting at level 0). */
    record AttachmentViewRange(int baseMipLevel, int mipLevels) {
    }

    /**
     * Mip range for a render-attachment view of {@code textureMipLevels}
     * levels: the whole chain when full-range views are enabled (the default,
     * keeping {@code nativeHandle()} == the texture), otherwise the legacy
     * level-0-only subresource view.
     */
    static AttachmentViewRange attachmentViewRange(final boolean fullRange, final int textureMipLevels) {
        int levels = Math.max(1, textureMipLevels);
        return fullRange
                ? new AttachmentViewRange(0, levels)
                : new AttachmentViewRange(0, 1);
    }

    /**
     * Mirrors {@code MetalGpuTextureView.nativeHandle()}: whether a view of this
     * range returns the texture's own handle instead of allocating a native
     * view. Pinned by test because a false here is exactly the per-pass native
     * view churn the attachment-view switch removes.
     */
    static boolean usesTextureHandle(final AttachmentViewRange range, final int textureMipLevels) {
        return range.baseMipLevel() == 0 && range.mipLevels() >= Math.max(1, textureMipLevels);
    }

    /**
     * Whether the next blit operation can encode into the encoder that is
     * already open. Only an open blit encoder under the batching switch
     * qualifies; a render/compute encoder, no encoder at all, or
     * {@code blitBatch=off} all require ending and recreating it.
     */
    static boolean reuseBlitEncoder(final boolean batchingEnabled, final boolean openEncoderIsBlit) {
        return batchingEnabled && openEncoderIsBlit;
    }
}
