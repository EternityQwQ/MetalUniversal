package com.metallum.client.metal.render.mtl;

/**
 * Internal pixel/vertex format abstraction used by the 1.12.2 port in place of
 * {@code com.mojang.blaze3d.GpuFormat}, which does not exist in 1.12.2.
 *
 * <p>Each value maps to a {@link MTLPixelFormat}; the GL-side translation layer
 * selects a format based on the OpenGL internal format constants it observes
 * (e.g. {@code GL_RGBA8}, {@code GL_DEPTH_COMPONENT24}).</p>
 */
public enum GlMetalFormat {
    R8_UNORM,
    R8_SNORM,
    R8_UINT,
    R8_SINT,
    R16_UNORM,
    R16_SNORM,
    R16_UINT,
    R16_SINT,
    R16_FLOAT,
    RG8_UNORM,
    RG8_SNORM,
    RG8_UINT,
    RG8_SINT,
    R32_UINT,
    R32_SINT,
    R32_FLOAT,
    RG16_UNORM,
    RG16_SNORM,
    RG16_UINT,
    RG16_SINT,
    RG16_FLOAT,
    RGBA8_UNORM,
    RGBA8_SNORM,
    RGBA8_UINT,
    RGBA8_SINT,
    RGB10A2_UNORM,
    RG11B10_FLOAT,
    RG32_UINT,
    RG32_SINT,
    RG32_FLOAT,
    RGBA16_UNORM,
    RGBA16_SNORM,
    RGBA16_UINT,
    RGBA16_SINT,
    RGBA16_FLOAT,
    RGBA32_UINT,
    RGBA32_SINT,
    RGBA32_FLOAT,
    D16_UNORM,
    D32_FLOAT,
    S8_UINT,
    D24_UNORM_S8_UINT,
    D32_FLOAT_S8_UINT;

    public MTLPixelFormat toMetal() {
        switch (this) {
            case R8_UNORM: return MTLPixelFormat.R8Unorm;
            case R8_SNORM: return MTLPixelFormat.R8Snorm;
            case R8_UINT: return MTLPixelFormat.R8Uint;
            case R8_SINT: return MTLPixelFormat.R8Sint;
            case R16_UNORM: return MTLPixelFormat.R16Unorm;
            case R16_SNORM: return MTLPixelFormat.R16Snorm;
            case R16_UINT: return MTLPixelFormat.R16Uint;
            case R16_SINT: return MTLPixelFormat.R16Sint;
            case R16_FLOAT: return MTLPixelFormat.R16Float;
            case RG8_UNORM: return MTLPixelFormat.RG8Unorm;
            case RG8_SNORM: return MTLPixelFormat.RG8Snorm;
            case RG8_UINT: return MTLPixelFormat.RG8Uint;
            case RG8_SINT: return MTLPixelFormat.RG8Sint;
            case R32_UINT: return MTLPixelFormat.R32Uint;
            case R32_SINT: return MTLPixelFormat.R32Sint;
            case R32_FLOAT: return MTLPixelFormat.R32Float;
            case RG16_UNORM: return MTLPixelFormat.RG16Unorm;
            case RG16_SNORM: return MTLPixelFormat.RG16Snorm;
            case RG16_UINT: return MTLPixelFormat.RG16Uint;
            case RG16_SINT: return MTLPixelFormat.RG16Sint;
            case RG16_FLOAT: return MTLPixelFormat.RG16Float;
            case RGBA8_UNORM: return MTLPixelFormat.RGBA8Unorm;
            case RGBA8_SNORM: return MTLPixelFormat.RGBA8Snorm;
            case RGBA8_UINT: return MTLPixelFormat.RGBA8Uint;
            case RGBA8_SINT: return MTLPixelFormat.RGBA8Sint;
            case RGB10A2_UNORM: return MTLPixelFormat.RGB10A2Unorm;
            case RG11B10_FLOAT: return MTLPixelFormat.RG11B10Float;
            case RG32_UINT: return MTLPixelFormat.RG32Uint;
            case RG32_SINT: return MTLPixelFormat.RG32Sint;
            case RG32_FLOAT: return MTLPixelFormat.RG32Float;
            case RGBA16_UNORM: return MTLPixelFormat.RGBA16Unorm;
            case RGBA16_SNORM: return MTLPixelFormat.RGBA16Snorm;
            case RGBA16_UINT: return MTLPixelFormat.RGBA16Uint;
            case RGBA16_SINT: return MTLPixelFormat.RGBA16Sint;
            case RGBA16_FLOAT: return MTLPixelFormat.RGBA16Float;
            case RGBA32_UINT: return MTLPixelFormat.RGBA32Uint;
            case RGBA32_SINT: return MTLPixelFormat.RGBA32Sint;
            case RGBA32_FLOAT: return MTLPixelFormat.RGBA32Float;
            case D16_UNORM: return MTLPixelFormat.Depth16Unorm;
            case D32_FLOAT: return MTLPixelFormat.Depth32Float;
            case S8_UINT: return MTLPixelFormat.Stencil8;
            case D24_UNORM_S8_UINT: return MTLPixelFormat.Depth24Unorm_Stencil8;
            case D32_FLOAT_S8_UINT: return MTLPixelFormat.Depth32Float_Stencil8;
            default: throw new IllegalStateException("Unsupported format: " + this);
        }
    }
}
