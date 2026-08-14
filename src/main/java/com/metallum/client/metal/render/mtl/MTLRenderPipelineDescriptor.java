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

    public void setAttachmentFormats(final MTLPixelFormat colorFormat, final MTLPixelFormat depthFormat, final MTLPixelFormat stencilFormat) {
        MetalNativeBridge.metallum_MTLRenderPipelineDescriptor_setAttachmentFormats(
                this.handle,
                colorFormat,
                depthFormat,
                stencilFormat
        );
    }

    public void setBlendState(
            final MTLBlendFactor sourceColorBlendFactor,
            final MTLBlendFactor destinationColorBlendFactor,
            final MTLBlendOperation colorBlendOperation,
            final MTLBlendFactor sourceAlphaBlendFactor,
            final MTLBlendFactor destinationAlphaBlendFactor,
            final MTLBlendOperation alphaBlendOperation,
            final long writeMask
    ) {
        MetalNativeBridge.metallum_MTLRenderPipelineDescriptor_setBlendState(
                this.handle,
                1,
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
        MetalNativeBridge.metallum_MTLRenderPipelineDescriptor_setBlendState(
                this.handle,
                0,
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
