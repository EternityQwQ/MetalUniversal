package com.metallum.client.metal.render;

/**
 * A Metal render pass: the encoder plus the color/depth attachments it writes.
 *
 * <p>Adapted from {@code MetalRenderPass}; the original carried blaze3d
 * attachment descriptors. The 1.12.2 port uses a minimal attachment record that
 * the {@link GlMetalTranslator} fills in from the bound GL framebuffer.</p>
 */
public final class MetalRenderPass {
    private final long encoder;
    private final Attachment[] colorAttachments;
    private final Attachment depthAttachment;

    public MetalRenderPass(long encoder, Attachment[] colorAttachments, Attachment depthAttachment) {
        this.encoder = encoder;
        this.colorAttachments = colorAttachments;
        this.depthAttachment = depthAttachment;
    }

    public long encoder() {
        return encoder;
    }

    public Attachment[] colorAttachments() {
        return colorAttachments;
    }

    public Attachment depthAttachment() {
        return depthAttachment;
    }

    public static final class Attachment {
        public final MetalGpuTexture texture;
        public final float clearR;
        public final float clearG;
        public final float clearB;
        public final float clearA;
        public final double clearDepth;

        public Attachment(MetalGpuTexture texture, float r, float g, float b, float a, double depth) {
            this.texture = texture;
            this.clearR = r;
            this.clearG = g;
            this.clearB = b;
            this.clearA = a;
            this.clearDepth = depth;
        }

        public static Attachment color(MetalGpuTexture tex, float r, float g, float b, float a) {
            return new Attachment(tex, r, g, b, a, 0.0);
        }

        public static Attachment depth(MetalGpuTexture tex, double depth) {
            return new Attachment(tex, 0f, 0f, 0f, 0f, depth);
        }
    }
}
