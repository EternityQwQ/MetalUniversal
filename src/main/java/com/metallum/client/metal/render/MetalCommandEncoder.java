package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCommandBuffer;
import com.metallum.client.metal.render.mtl.MTLCullMode;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLWinding;

/**
 * Records Metal commands for a frame and submits them.
 *
 * <p>Adapted from the original {@code MetalCommandEncoder}, which implemented
 * {@code com.mojang.blaze3d.systems.CommandEncoderBackend} and was deeply
 * coupled to the validation-contract framework. The 1.12.2 port keeps the core
 * in-flight-ring / deferred-destroy / render-pass-then-draw logic and drops the
 * blaze3d and validation-contract surface, which do not exist in 1.12.2.</p>
 */
public final class MetalCommandEncoder {
    public static final int MAX_SUBMITS_IN_FLIGHT = 3;

    private final MetalDevice device;
    private final long fence;
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT + 1);
    private final long[] submitSemaphores = new long[MAX_SUBMITS_IN_FLIGHT];

    private int currentSubmitIndex = MAX_SUBMITS_IN_FLIGHT;
    private final InFlight[] inFlight = new InFlight[MAX_SUBMITS_IN_FLIGHT];

    private MTLCommandBuffer commandBuffer;
    private MetalRenderPass currentRenderPass;
    private boolean pendingPresent;

    public MetalCommandEncoder(MetalDevice device) {
        this.device = device;
        this.fence = device.createFence();
        for (int i = 0; i < MAX_SUBMITS_IN_FLIGHT; i++) {
            submitSemaphores[i] = MetalNativeBridge.metallum_create_semaphore(0);
            inFlight[i] = new InFlight();
        }
    }

    /**
     * Begins a new submit slot, waiting on the ring slot's semaphore so that no
     * more than {@link #MAX_SUBMITS_IN_FLIGHT} command buffers are in flight.
     */
    public void beginFrame() {
        currentSubmitIndex = (currentSubmitIndex + 1) % MAX_SUBMITS_IN_FLIGHT;
        long sem = submitSemaphores[currentSubmitIndex];
        // Wait for the previous user of this slot to finish.
        MetalNativeBridge.metallum_semaphore_wait(sem, 10_000L);
        long cb = MetalNativeBridge.metallum_MTLCommandQueue_makeCommandBuffer(device.commandQueue());
        commandBuffer = new MTLCommandBuffer(cb);
        InFlight slot = inFlight[currentSubmitIndex];
        slot.commandBufferHandle = cb;
        slot.fence = fence;
    }

    public MetalRenderPass beginRenderPass(MetalRenderPass.Attachment[] colorAttachments, MetalRenderPass.Attachment depthAttachment) {
        if (currentRenderPass != null) {
            endRenderPass();
        }
        // For the 1.12.2 port we use a Metal clear-then-draw path: clear the
        // attachments directly via the command buffer, then make a render
        // command encoder bound to them. The native library's v2 encoder
        // constructor takes a render-pass descriptor; we build it implicitly
        // by clearing first. This matches how the translation layer drives
        // rendering: one render pass per framebuffer binding.
        long cb = commandBuffer.handle();
        for (int i = 0; i < colorAttachments.length; i++) {
            MetalRenderPass.Attachment a = colorAttachments[i];
            if (a != null && a.texture != null) {
                MetalNativeBridge.metallum_MTLCommandBuffer_clearColorDepthTexturesRegion(
                        cb, a.texture.handle(), 0L, 1,
                        a.clearR, a.clearG, a.clearB, a.clearA, 0.0, 0,
                        0, 0, 0, a.texture.width(), a.texture.height(), 1);
            }
        }
        if (depthAttachment != null && depthAttachment.texture != null) {
            MetalNativeBridge.metallum_MTLCommandBuffer_clearColorDepthTexturesRegion(
                    cb, 0L, depthAttachment.texture.handle(), 2,
                    0f, 0f, 0f, 0f, depthAttachment.clearDepth, 0,
                    0, 0, 0, depthAttachment.texture.width(), depthAttachment.texture.height(), 1);
        }
        long encoder = MetalNativeBridge.metallum_MTLCommandBuffer_makeRenderCommandEncoder(cb, 0L);
        currentRenderPass = new MetalRenderPass(encoder, colorAttachments, depthAttachment);
        return currentRenderPass;
    }

    public void endRenderPass() {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLCommandEncoder_endEncoding(currentRenderPass.encoder());
            currentRenderPass = null;
        }
    }

    public void setCullMode(MTLCullMode mode) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_setCullMode(currentRenderPass.encoder(), mode.value);
        }
    }

    public void setFrontFacingWinding(MTLWinding winding) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_setFrontFacingWinding(currentRenderPass.encoder(), winding.value);
        }
    }

    public void setViewport(double x, double y, double w, double h, double znear, double zfar) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_setViewport(currentRenderPass.encoder(), x, y, w, h, znear, zfar);
        }
    }

    public void setScissor(int x, int y, int width, int height) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_setScissorRect(currentRenderPass.encoder(), x, y, width, height);
        }
    }

    public void setRenderPipelineState(long pipelineState) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_setRenderPipelineState(currentRenderPass.encoder(), pipelineState);
        }
    }

    public void setDepthStencilState(long state) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_setDepthStencilState(currentRenderPass.encoder(), state);
        }
    }

    public void setVertexBuffer(long buffer, long offset, int index) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_setVertexBuffer(currentRenderPass.encoder(), buffer, offset, index);
        }
    }

    public void setFragmentBuffer(long buffer, long offset, int index) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_setFragmentBuffer(currentRenderPass.encoder(), buffer, offset, index);
        }
    }

    public void setFragmentTexture(long texture, int index) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_setFragmentTexture(currentRenderPass.encoder(), texture, index);
        }
    }

    public void drawIndexed(MTLPrimitiveType primitive, long indexCount, long indexType, long indexBuffer, long indexBufferOffset) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_drawIndexedPrimitives(currentRenderPass.encoder(), primitive.value, indexCount, indexType, indexBuffer, indexBufferOffset);
        }
    }

    public void drawIndexedTriangleFan(long indexCount, long indexType, long indexBuffer, long indexBufferOffset) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(currentRenderPass.encoder(), indexCount, indexType, indexBuffer, indexBufferOffset);
        }
    }

    public void drawArrays(MTLPrimitiveType primitive, long vertexStart, long vertexCount) {
        if (currentRenderPass != null) {
            MetalNativeBridge.metallum_MTLRenderCommandEncoder_drawPrimitives(currentRenderPass.encoder(), primitive.value, vertexStart, vertexCount);
        }
    }

    public void presentTextureToDrawable(long metalLayer, MetalGpuTexture texture) {
        if (commandBuffer != null && texture != null) {
            MetalNativeBridge.metallum_MTLCommandBuffer_encodePresentTextureToDrawable(commandBuffer.handle(), texture.handle(), metalLayer);
            pendingPresent = true;
        }
    }

    public void submit() {
        if (currentRenderPass != null) {
            endRenderPass();
        }
        if (commandBuffer == null) {
            return;
        }
        long cb = commandBuffer.handle();
        long sem = submitSemaphores[currentSubmitIndex];
        MetalNativeBridge.metallum_MTLCommandBuffer_commitWithSignal(cb, sem);
        commandBuffer = null;
        pendingPresent = false;
        destroyQueue.rotate();
    }

    public boolean awaitSubmitCompletion(long submitIndex, long timeoutMs) {
        long sem = submitSemaphores[(int) (submitIndex % MAX_SUBMITS_IN_FLIGHT)];
        MetalNativeBridge.metallum_semaphore_wait(sem, timeoutMs);
        return true;
    }

    public void destroyLater(Runnable r) {
        destroyQueue.add(r);
    }

    public void close() {
        destroyQueue.close();
        if (!MetalNativeBridge.isNull(fence)) {
            MetalNativeBridge.metallum_release_object(fence);
        }
        for (int i = 0; i < MAX_SUBMITS_IN_FLIGHT; i++) {
            if (!MetalNativeBridge.isNull(submitSemaphores[i])) {
                MetalNativeBridge.metallum_release_object(submitSemaphores[i]);
            }
        }
    }

    private static final class InFlight {
        long commandBufferHandle;
        long fence;
    }
}
