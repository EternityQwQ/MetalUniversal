package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

import java.lang.foreign.MemorySegment;

public final class MTLRenderPipelineDescriptor implements AutoCloseable {
    private final MemorySegment handle;
    private boolean closed;

    public MTLRenderPipelineDescriptor() {
        this.handle = MetalNativeBridge.metallum_MTLRenderPipelineDescriptor_create();
    }

    public MemorySegment handle() {
        return this.handle;
    }

    public void setCompiledFunctions(final MemorySegment vertexFunction, final MemorySegment fragmentFunction) {
        MetalNativeBridge.metallum_MTLRenderPipelineDescriptor_setCompiledFunctions(
                this.handle,
                vertexFunction,
                fragmentFunction
        );
    }

    public void setVertexDescriptor(final MTLVertexDescriptor vertexDescriptor) {
        MetalNativeBridge.metallum_MTLRenderPipelineDescriptor_setVertexDescriptor(
                this.handle,
                vertexDescriptor.handle()
        );
    }

    public void setColorAttachmentFormat(final int index, final MTLPixelFormat format) {
        MetalNativeBridge.metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat(
                this.handle,
                index,
                format
        );
    }

    public void setDepthStencilFormats(final MTLPixelFormat depthFormat, final MTLPixelFormat stencilFormat) {
        MetalNativeBridge.metallum_MTLRenderPipelineDescriptor_setDepthStencilFormats(
                this.handle,
                depthFormat,
                stencilFormat
        );
    }

    public void setColorAttachmentBlendState(
            final int index,
            final boolean enabled,
            final MTLBlendFactor sourceColorBlendFactor,
            final MTLBlendFactor destinationColorBlendFactor,
            final MTLBlendOperation colorBlendOperation,
            final MTLBlendFactor sourceAlphaBlendFactor,
            final MTLBlendFactor destinationAlphaBlendFactor,
            final MTLBlendOperation alphaBlendOperation,
            final long writeMask
    ) {
        MetalNativeBridge.metallum_MTLRenderPipelineDescriptor_setColorAttachmentBlendState(
                this.handle,
                index,
                enabled,
                sourceColorBlendFactor.value,
                destinationColorBlendFactor.value,
                colorBlendOperation.value,
                sourceAlphaBlendFactor.value,
                destinationAlphaBlendFactor.value,
                alphaBlendOperation.value,
                writeMask
        );
    }

    public void disableBlending(final long writeMask) {
        disableBlending(0, writeMask);
    }

    public void disableBlending(final int index, final long writeMask) {
        MetalNativeBridge.metallum_MTLRenderPipelineDescriptor_setColorAttachmentBlendState(
                this.handle,
                index,
                false,
                0, 0, 0, 0, 0, 0,
                writeMask
        );
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            MetalNativeBridge.metallum_release_object(this.handle);
        }
    }
}
