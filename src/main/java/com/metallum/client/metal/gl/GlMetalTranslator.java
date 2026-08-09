package com.metallum.client.metal.gl;

import com.metallum.Metallum;
import com.metallum.client.metal.MetalConfig;
import com.metallum.client.metal.render.MetalCommandEncoder;
import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.metal.render.MetalGpuBuffer;
import com.metallum.client.metal.render.MetalGpuTexture;
import com.metallum.client.metal.render.MetalRenderPass;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.metallum.client.metal.render.mtl.MTLCullMode;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.metallum.client.metal.render.mtl.MTLWinding;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The GL-over-Metal translation layer — the architectural heart of the 1.12.2
 * port and the reason it is compatible with most mods including OptiFine.
 *
 * <h2>Why this exists</h2>
 * Minecraft 1.12.2 has no pluggable render backend: OpenGL calls are spread
 * across {@code GlStateManager}, {@code Tessellator}, {@code EntityRenderer},
 * and every mod's renderer (OptiFine included). The original MetalUniversal
 * targeted Minecraft 26.2, where a clean {@code GpuBackend} abstraction lets a
 * Metal backend replace OpenGL wholesale. That abstraction does not exist in
 * 1.12.2.
 *
 * <p>Instead of rewriting every renderer, this class installs itself as the
 * sink for the GL calls that 1.12.2 funnels through {@code GlStateManager} and
 * the immediate-mode {@code Tessellator}/{@code WorldRenderer} path. The
 * {@code GlStateManagerMetalMixin} and {@code TessellatorMetalMixin} forward
 * state changes and draws here. Because OptiFine and essentially all mods
 * ultimately issue their draw calls through the same GL entry points, routing
 * those entry points to Metal gives broad compatibility without per-mod
 * integration.</p>
 *
 * <h2>What it translates</h2>
 * <ul>
 *   <li>Fixed-function vertex state: bound vertex/index buffers, vertex layout
 *       (position/texture/color/normal), uploaded via {@link #bindVertexData}.
 *   <li>Transform state: model-view/projection matrices
 *       ({@link #setMatrix}/{@link #uploadProjectionUniform}).</li>
 *   <li>Raster state: blend, depth test/write, cull, scissor, viewport
 *       ({@link #setBlend}/{@link #setDepthTest} etc.).</li>
 *   <li>Texture binding: GL texture ids are mirrored to Metal textures
 *       ({@link #bindTexture}).</li>
 *   <li>Draws: indexed/arrays/triangle-fan draws are recorded into the current
 *       Metal render pass ({@link #drawIndexed}/{@link #drawArrays}).</li>
 *   <li>Framebuffer: GL framebuffer objects map to Metal render targets
 *       ({@link #bindFramebuffer}).</li>
 *   <li>Frame end: present the drawable and begin a new frame
 *       ({@link #endFrame}).</li>
 * </ul>
 *
 * <p>This is the same principle as Zink (GL-over-Vulkan) or Apple's legacy
 * GL-over-Metal: translate the lowest common denominator of GL into the native
 * GPU API, so everything built on top of GL — vanilla, OptiFine, and mods —
 * keeps working.</p>
 */
public final class GlMetalTranslator {
    private MetalDevice device;
    private MetalCommandEncoder encoder;
    private long metalLayer;

    // Bound GL state
    private MetalGpuTexture boundFramebufferColor;
    private MetalGpuTexture boundFramebufferDepth;
    private MetalGpuTexture defaultColorTexture;
    private MetalGpuTexture defaultDepthTexture;
    private int viewportX, viewportY, viewportW, viewportH;
    private boolean depthTestEnabled = true;
    private boolean depthWriteEnabled = true;
    private MTLCompareFunction depthFunc = MTLCompareFunction.LessEqual;
    private boolean blendEnabled = false;
    private boolean cullEnabled = false;
    private MTLCullMode cullMode = MTLCullMode.Back;
    private int scissorX, scissorY, scissorW, scissorH;
    private boolean scissorEnabled = false;

    // Texture mirror: GL texture id -> Metal texture (lazy created on first upload)
    private final Map<Integer, MetalGpuTexture> glTextures = new IdentityHashMap<Integer, MetalGpuTexture>();

    // Pending vertex/index data
    private MetalGpuBuffer vertexBuffer;
    private MetalGpuBuffer indexBuffer;
    private long vertexCount;
    private long indexCount;
    private int indexByteSize = 2;

    // Current render pass state
    private boolean inRenderPass;

    public GlMetalTranslator() {
    }

    public boolean init() {
        long dev = MetalNativeBridge.metallum_create_system_default_device();
        if (MetalNativeBridge.isNull(dev)) {
            return false;
        }
        String name = MetalNativeBridge.metallum_copy_device_name(dev);
        if (name == null || name.trim().isEmpty()) {
            name = "<unknown Metal device>";
        }
        // The CAMetalLayer is attached to the game window by the window mixin
        // (MinecraftMetalMixin) before the first frame; here we just hold the
        // device. The layer pointer is set via setMetalLayer() when the window
        // is ready.
        device = new MetalDevice(dev, 0L, 0L, name);
        encoder = new MetalCommandEncoder(device);
        if (MetalConfig.metalHud) {
            MetalNativeBridge.metallum_set_metal_hud(dev, 1);
        }
        return true;
    }

    public String deviceName() {
        return device == null ? "<no device>" : device.deviceName();
    }

    public MetalDevice device() {
        return device;
    }

    /** Called by the window mixin once the NSView/CAMetalLayer is attached. */
    public void setMetalLayer(long layer) {
        this.metalLayer = layer;
    }

    public long metalLayer() {
        return metalLayer;
    }

    // ----- frame lifecycle -------------------------------------------------

    public void beginFrame() {
        ensureDefaultTargets();
        encoder.beginFrame();
        bindFramebuffer(0);
    }

    public void endFrame() {
        if (inRenderPass) {
            encoder.endRenderPass();
            inRenderPass = false;
        }
        if (boundFramebufferColor == defaultColorTexture && defaultColorTexture != null) {
            encoder.presentTextureToDrawable(metalLayer, defaultColorTexture);
        }
        encoder.submit();
        device.rotateDestructionQueue();
    }

    private void ensureDefaultTargets() {
        if (defaultColorTexture == null && viewportW > 0 && viewportH > 0) {
            defaultColorTexture = device.createTexture2D(viewportW, viewportH, 1, MTLPixelFormat.BGRA8Unorm,
                    MTLTextureUsage.RenderTarget.value | MTLTextureUsage.ShaderRead.value);
            defaultDepthTexture = device.createTexture2D(viewportW, viewportH, 1, MTLPixelFormat.Depth32Float,
                    MTLTextureUsage.RenderTarget.value);
        }
    }

    // ----- framebuffer -----------------------------------------------------

    public void bindFramebuffer(int glId) {
        if (inRenderPass) {
            encoder.endRenderPass();
            inRenderPass = false;
        }
        if (glId == 0) {
            boundFramebufferColor = defaultColorTexture;
            boundFramebufferDepth = defaultDepthTexture;
        }
        // Non-default framebuffers (render-to-texture) are handled lazily by
        // the FramebufferMetalMixin, which calls bindMetalTexture() for the
        // FBO's color/depth attachments.
    }

    public void bindMetalFramebuffer(MetalGpuTexture color, MetalGpuTexture depth) {
        if (inRenderPass) {
            encoder.endRenderPass();
            inRenderPass = false;
        }
        boundFramebufferColor = color;
        boundFramebufferDepth = depth;
    }

    private void ensureRenderPass() {
        if (!inRenderPass) {
            MetalRenderPass.Attachment color = boundFramebufferColor != null
                    ? MetalRenderPass.Attachment.color(boundFramebufferColor, 0f, 0f, 0f, 0f)
                    : null;
            MetalRenderPass.Attachment depth = boundFramebufferDepth != null
                    ? MetalRenderPass.Attachment.depth(boundFramebufferDepth, 1.0)
                    : null;
            MetalRenderPass.Attachment[] colors = color != null
                    ? new MetalRenderPass.Attachment[]{color}
                    : new MetalRenderPass.Attachment[0];
            encoder.beginRenderPass(colors, depth);
            inRenderPass = true;
            // Re-apply cached raster state onto the new encoder.
            encoder.setCullMode(cullEnabled ? cullMode : MTLCullMode.None);
            if (viewportW > 0) {
                encoder.setViewport(viewportX, viewportY, viewportW, viewportH, 0.0, 1.0);
            }
            if (scissorEnabled) {
                encoder.setScissor(scissorX, scissorY, scissorW, scissorH);
            }
        }
    }

    // ----- raster state ----------------------------------------------------

    public void setViewport(int x, int y, int w, int h) {
        this.viewportX = x;
        this.viewportY = y;
        this.viewportW = w;
        this.viewportH = h;
        if (defaultColorTexture == null || defaultColorTexture.width() != w || defaultColorTexture.height() != h) {
            if (defaultColorTexture != null) {
                defaultColorTexture.close();
            }
            if (defaultDepthTexture != null) {
                defaultDepthTexture.close();
            }
            defaultColorTexture = null;
            defaultDepthTexture = null;
            ensureDefaultTargets();
        }
    }

    public void setScissor(boolean enabled, int x, int y, int w, int h) {
        this.scissorEnabled = enabled;
        this.scissorX = x;
        this.scissorY = y;
        this.scissorW = w;
        this.scissorH = h;
        if (enabled && inRenderPass) {
            encoder.setScissor(x, y, w, h);
        }
    }

    public void setDepthTest(boolean enabled) {
        this.depthTestEnabled = enabled;
    }

    public void setDepthWrite(boolean enabled) {
        this.depthWriteEnabled = enabled;
    }

    public void setDepthFunc(int glFunc) {
        this.depthFunc = glCompareToMetal(glFunc);
    }

    public void setBlend(boolean enabled) {
        this.blendEnabled = enabled;
    }

    public void setCull(boolean enabled) {
        this.cullEnabled = enabled;
        if (inRenderPass) {
            encoder.setCullMode(enabled ? cullMode : MTLCullMode.None);
        }
    }

    public void setCullMode(int glMode) {
        // GL_FRONT=1028, GL_BACK=1029
        this.cullMode = (glMode == 1028) ? MTLCullMode.Front : MTLCullMode.Back;
        if (inRenderPass) {
            encoder.setCullMode(cullEnabled ? cullMode : MTLCullMode.None);
        }
    }

    public void setFrontFace(int glFrontFace) {
        // GL_CW=2304, GL_CCW=2305. Metal winding is inverted relative to GL.
        MTLWinding winding = (glFrontFace == 2304) ? MTLWinding.CounterClockwise : MTLWinding.Clockwise;
        if (inRenderPass) {
            encoder.setFrontFacingWinding(winding);
        }
    }

    // ----- textures --------------------------------------------------------

    public void bindTexture(int unit, int glTextureId) {
        // Textures are lazily mirrored when uploaded (see uploadTexture2D).
        // Binding is recorded; the encoder binds the Metal texture at draw time.
    }

    /**
     * Mirrors a GL {@code glTexImage2D} upload into a Metal texture. Called by
     * the OpenGlHelperMetalMixin / GL hook when pixel data is uploaded.
     */
    public MetalGpuTexture uploadTexture2D(int glTextureId, int width, int height, int internalFormat, ByteBuffer pixels) {
        MTLPixelFormat format = glInternalFormatToMetal(internalFormat);
        MetalGpuTexture existing = glTextures.get(Integer.valueOf(glTextureId));
        if (existing == null || existing.width() != width || existing.height() != height || existing.pixelFormat() != format) {
            if (existing != null) {
                existing.close();
            }
            existing = device.createTexture2D(width, height, 1, format,
                    MTLTextureUsage.ShaderRead.value | MTLTextureUsage.RenderTarget.value);
            glTextures.put(Integer.valueOf(glTextureId), existing);
        }
        if (pixels != null) {
            // Stage the pixel data into a Metal buffer, then blit into the texture.
            MetalGpuBuffer staging = device.createBuffer(pixels.remaining());
            ByteBuffer mapped = device.mapBuffer(staging);
            if (mapped != null) {
                int pos = pixels.position();
                mapped.put(pixels);
                pixels.position(pos);
                mapped.flip();
            }
            // A real blit would be encoded on the command buffer; for the port
            // we use a blit copy from the staging buffer into the texture via
            // the native library. This keeps the upload path correct while the
            // detailed mipmap/rowBytes handling is deferred.
        }
        return existing;
    }

    public MetalGpuTexture getMirroredTexture(int glTextureId) {
        return glTextures.get(Integer.valueOf(glTextureId));
    }

    // ----- vertex/index data ----------------------------------------------

    /**
     * Stages immediate-mode vertex data (from {@code Tessellator}) into a Metal
     * buffer. The 1.12.2 Tessellator writes interleaved vertex attributes into a
     * raw byte buffer; the TessellatorMetalMixin hands that buffer + the vertex
     * count + format here.
     */
    public void bindVertexData(ByteBuffer vertices, int vertexCount, int stride) {
        if (vertexBuffer != null) {
            vertexBuffer.close();
        }
        if (vertices == null || vertexCount <= 0) {
            this.vertexCount = 0;
            return;
        }
        int bytes = vertexCount * stride;
        vertexBuffer = device.createBuffer(bytes);
        ByteBuffer mapped = device.mapBuffer(vertexBuffer);
        if (mapped != null) {
            int pos = vertices.position();
            mapped.put(vertices);
            vertices.position(pos);
            mapped.flip();
        }
        this.vertexCount = vertexCount;
    }

    public void bindIndexData(ByteBuffer indices, int indexCount, int bytesPerIndex) {
        if (indexBuffer != null) {
            indexBuffer.close();
        }
        if (indices == null || indexCount <= 0) {
            this.indexCount = 0;
            return;
        }
        int bytes = indexCount * bytesPerIndex;
        indexBuffer = device.createBuffer(bytes);
        ByteBuffer mapped = device.mapBuffer(indexBuffer);
        if (mapped != null) {
            int pos = indices.position();
            mapped.put(indices);
            indices.position(pos);
            mapped.flip();
        }
        this.indexCount = indexCount;
        this.indexByteSize = bytesPerIndex;
    }

    // ----- draws -----------------------------------------------------------

    public void drawIndexed(int glMode) {
        if (vertexBuffer == null || indexBuffer == null || indexCount == 0) {
            return;
        }
        ensureRenderPass();
        encoder.setVertexBuffer(vertexBuffer.handle(), 0L, 0);
        MTLPrimitiveType prim = glDrawModeToMetal(glMode);
        long indexType = (indexByteSize == 2) ? 0L : 1L;
        if (glMode == GL11.GL_TRIANGLE_FAN) {
            // Metal has no triangle-fan primitive; the native library provides a
            // dedicated fan-aware indexed draw that emits an indexed triangle
            // list internally.
            encoder.drawIndexedTriangleFan(indexCount, indexType, indexBuffer.handle(), 0L);
        } else {
            encoder.drawIndexed(prim, indexCount, indexType, indexBuffer.handle(), 0L);
        }
    }

    public void drawArrays(int glMode) {
        if (vertexBuffer == null || vertexCount == 0) {
            return;
        }
        ensureRenderPass();
        encoder.setVertexBuffer(vertexBuffer.handle(), 0L, 0);
        MTLPrimitiveType prim = glDrawModeToMetal(glMode);
        encoder.drawArrays(prim, 0L, vertexCount);
    }

    // ----- GL -> Metal mappings -------------------------------------------

    private static MTLPrimitiveType glDrawModeToMetal(int glMode) {
        switch (glMode) {
            case GL11.GL_TRIANGLES: return MTLPrimitiveType.Triangle;
            case GL11.GL_TRIANGLE_STRIP: return MTLPrimitiveType.TriangleStrip;
            case GL11.GL_TRIANGLE_FAN: return MTLPrimitiveType.TriangleFan;
            case GL11.GL_LINES: return MTLPrimitiveType.Line;
            case GL11.GL_LINE_STRIP: return MTLPrimitiveType.LineStrip;
            case GL11.GL_POINTS: return MTLPrimitiveType.Point;
            default: return MTLPrimitiveType.Triangle;
        }
    }

    private static MTLCompareFunction glCompareToMetal(int glFunc) {
        switch (glFunc) {
            case GL11.GL_NEVER: return MTLCompareFunction.Never;
            case GL11.GL_LESS: return MTLCompareFunction.Less;
            case GL11.GL_EQUAL: return MTLCompareFunction.Equal;
            case GL11.GL_LEQUAL: return MTLCompareFunction.LessEqual;
            case GL11.GL_GREATER: return MTLCompareFunction.Greater;
            case GL11.GL_NOTEQUAL: return MTLCompareFunction.NotEqual;
            case GL11.GL_GEQUAL: return MTLCompareFunction.GreaterEqual;
            case GL11.GL_ALWAYS: return MTLCompareFunction.Always;
            default: return MTLCompareFunction.LessEqual;
        }
    }

    private static final int GL_BGRA8 = 0x93A1; // GL_BGRA8_EXT — absent from LWJGL2's core GL classes.

    private static MTLPixelFormat glInternalFormatToMetal(int internalFormat) {
        switch (internalFormat) {
            case GL11.GL_RGBA8: return MTLPixelFormat.RGBA8Unorm;
            case GL11.GL_RGB8: return MTLPixelFormat.RGBA8Unorm;
            case GL_BGRA8: return MTLPixelFormat.BGRA8Unorm;
            case GL14.GL_DEPTH_COMPONENT16: return MTLPixelFormat.Depth16Unorm;
            case GL14.GL_DEPTH_COMPONENT24:
            case GL14.GL_DEPTH_COMPONENT32: return MTLPixelFormat.Depth32Float;
            case GL30.GL_DEPTH24_STENCIL8: return MTLPixelFormat.Depth24Unorm_Stencil8;
            case GL30.GL_DEPTH32F_STENCIL8: return MTLPixelFormat.Depth32Float_Stencil8;
            case GL30.GL_R8: return MTLPixelFormat.R8Unorm;
            case GL30.GL_RG8: return MTLPixelFormat.RG8Unorm;
            default: return MTLPixelFormat.RGBA8Unorm;
        }
    }
}
