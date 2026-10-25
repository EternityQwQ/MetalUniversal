package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the {@code -Dmetallum.iris.debug.view=colortex6} device
 * freeze: a Metal texture blit cannot convert between pixel formats of
 * different sizes, and MakeUp's {@code gaux3} (colortex6, {@code R16F}, 2
 * bytes/pixel) against the 4-byte main target hung the command queue.
 * {@link IrisMetalDebugViewBlitter} now samples every debug view through a
 * fullscreen pass; there is no raw-copy fallback. Because the sampled path
 * needs the Metal native library, this test pins the format facts the freeze
 * was diagnosed from: the mismatched pairs that are illegal to blit, and the
 * real MakeUp target formats.
 */
final class IrisMetalDebugViewBlitTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "MakeUp-UltraFast-9.5f.zip");

    @Test
    void pixelSizeMismatchIsTheIllegalBlitClass() {
        // The device-freeze pair: gaux3 R16F (2 B/px) into an RGBA8 main target.
        assertTrue(IrisMetalDebugViewBlitter.needsSampledBlit(
                GpuFormat.R16_FLOAT.blockSize(), GpuFormat.RGBA8_UNORM.blockSize()));
        assertEquals(2, GpuFormat.R16_FLOAT.blockSize());
        // R8 targets (colortex2) are the same class of hazard.
        assertTrue(IrisMetalDebugViewBlitter.needsSampledBlit(
                GpuFormat.R8_UNORM.blockSize(), GpuFormat.RGBA8_UNORM.blockSize()));
        // Same-size pairs were the only ones the old direct blit handled.
        assertFalse(IrisMetalDebugViewBlitter.needsSampledBlit(
                GpuFormat.RG11B10_FLOAT.blockSize(), GpuFormat.RGBA8_UNORM.blockSize()));
        assertFalse(IrisMetalDebugViewBlitter.needsSampledBlit(
                GpuFormat.RGBA8_UNORM.blockSize(), GpuFormat.RGBA8_UNORM.blockSize()));
        // A byte-count mismatch is a mismatch even for two wide formats.
        assertTrue(IrisMetalDebugViewBlitter.needsSampledBlit(
                GpuFormat.RGBA8_UNORM.blockSize(), GpuFormat.RGBA16_FLOAT.blockSize()));
    }

    @Test
    void makeupTargetFormatsMatchTheFreezePair() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;
        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    Map.of(),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            GpuFormat[] formats = IrisMetalRenderTargetFormats.from(programSet.getPackDirectives());

            // MakeUp's exposure history is R16F (gaux3), its main buffer RG11B10F.
            assertEquals(GpuFormat.R16_FLOAT, formats[6],
                    "MakeUp gaux3/colortex6 must be R16F; got " + formats[6]);
            assertEquals(GpuFormat.RG11B10_FLOAT, formats[1],
                    "MakeUp colortex1 must be RG11B10F; got " + formats[1]);
            assertTrue(IrisMetalDebugViewBlitter.needsSampledBlit(
                    formats[6].blockSize(), GpuFormat.RGBA8_UNORM.blockSize()),
                    "colortex6 is the mismatched pair that froze the device");
            // colortex1 is the same-size pair the old blit happened to survive;
            // production now samples it too, so this only documents the format
            // facts the freeze was diagnosed from.
            assertFalse(IrisMetalDebugViewBlitter.needsSampledBlit(
                    formats[1].blockSize(), GpuFormat.RGBA8_UNORM.blockSize()),
                    "colortex1 is the historical same-size pair");
        }
    }

    /**
     * Round-4 R16F write-chain review, pinned native-free: MakeUp's
     * {@code gaux3} color target must (a) be created with render-attachment
     * usage like every other target, and (b) lower to Metal's
     * {@code MTLPixelFormat.r16Float} (25) exactly. Either one wrong would
     * silently drop the composite attachment write and leave the exposure
     * history zero.
     */
    @Test
    void r16fTargetIsRenderableAndLowersToR16Float() {
        assertTrue(
                (IrisMetalPingPongTargets.TEXTURE_USAGE & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0,
                "color ping-pong targets must carry USAGE_RENDER_ATTACHMENT"
        );
        assertTrue(
                (IrisMetalPingPongTargets.TEXTURE_USAGE & GpuTexture.USAGE_TEXTURE_BINDING) != 0,
                "color ping-pong targets must be sampleable"
        );
        assertEquals(25L, MTLPixelFormat.R16Float.value,
                "MTLPixelFormat.r16Float raw value must be 25");
        assertEquals(MTLPixelFormat.R16Float, MTLPixelFormat.from(GpuFormat.R16_FLOAT),
                "GpuFormat.R16_FLOAT must lower to MTLPixelFormat.r16Float");
    }
}
