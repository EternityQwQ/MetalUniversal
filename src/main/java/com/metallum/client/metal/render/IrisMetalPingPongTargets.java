package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.Set;

/**
 * Generation-owned main/alternate target pairs implementing Iris
 * {@code BufferFlipper} semantics for colortex and shadowcolor resources.
 */
@Environment(EnvType.CLIENT)
final class IrisMetalPingPongTargets implements AutoCloseable {
    static final int TEXTURE_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING
            | MetalGpuTexture.USAGE_SHADER_WRITE
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;

    private final MetalDevice device;
    private final String labelPrefix;
    private final GpuFormat[] formats;
    private MetalGpuTexture[] main;
    private MetalGpuTexture[] alt;
    private MetalGpuTextureView[] mainViews;
    private MetalGpuTextureView[] altViews;
    private final BitSet flipped;
    private final BitSet flippedAtLeastOnce;
    private final BitSet mipmappedTargets;
    private final BitSet mipmapsOnMain;
    private final BitSet mipmapsOnAlt;
    private int[] widths;
    private int[] heights;
    /** Largest target extent; retained for uniform-size callers. Prefer {@link #width(int)}. */
    private int width;
    private int height;
    private boolean closed;

    IrisMetalPingPongTargets(
            final MetalDevice device,
            final String labelPrefix,
            final GpuFormat[] formats,
            final int width,
            final int height
    ) {
        this(device, labelPrefix, formats, width, height, Set.of());
    }

    IrisMetalPingPongTargets(
            final MetalDevice device,
            final String labelPrefix,
            final GpuFormat[] formats,
            final int width,
            final int height,
            final Set<Integer> mipmappedTargets
    ) {
        this(device, labelPrefix, formats, uniform(formats.length, width),
                uniform(formats.length, height), mipmappedTargets);
    }

    /**
     * Per-target-size constructor used by {@code size.buffer} support: every
     * logical target (and both of its ping-pong sides) gets its own extent and
     * mip chain.
     */
    IrisMetalPingPongTargets(
            final MetalDevice device,
            final String labelPrefix,
            final GpuFormat[] formats,
            final int[] widths,
            final int[] heights,
            final Set<Integer> mipmappedTargets
    ) {
        if (formats.length == 0) {
            throw new IllegalArgumentException("At least one logical target is required");
        }
        if (widths.length != formats.length || heights.length != formats.length) {
            throw new IllegalArgumentException(
                    "Target extent arrays must match the logical target count: "
                            + widths.length + "/" + heights.length + " vs " + formats.length
            );
        }
        this.device = device;
        this.labelPrefix = labelPrefix;
        this.formats = formats.clone();
        this.flipped = new BitSet(formats.length);
        this.flippedAtLeastOnce = new BitSet(formats.length);
        this.mipmappedTargets = validatedTargets(
                Objects.requireNonNull(mipmappedTargets, "mipmappedTargets"), formats.length
        );
        this.mipmapsOnMain = new BitSet(formats.length);
        this.mipmapsOnAlt = new BitSet(formats.length);
        createTextures(widths, heights);
    }

    private void createTextures(final int[] newWidths, final int[] newHeights) {
        checkExtents(newWidths, newHeights);
        int largestWidth = 1;
        int largestHeight = 1;
        for (int index = 0; index < formats.length; index++) {
            largestWidth = Math.max(largestWidth, newWidths[index]);
            largestHeight = Math.max(largestHeight, newHeights[index]);
        }
        this.widths = newWidths.clone();
        this.heights = newHeights.clone();
        this.width = largestWidth;
        this.height = largestHeight;
        this.main = new MetalGpuTexture[formats.length];
        this.alt = new MetalGpuTexture[formats.length];
        this.mainViews = new MetalGpuTextureView[formats.length];
        this.altViews = new MetalGpuTextureView[formats.length];
        for (int index = 0; index < formats.length; index++) {
            int targetWidth = newWidths[index];
            int targetHeight = newHeights[index];
            int mipLevels = this.mipmappedTargets.get(index)
                    ? fullMipLevelCount(targetWidth, targetHeight)
                    : 1;
            main[index] = (MetalGpuTexture) device.createTexture(
                    labelPrefix + index + "-main", TEXTURE_USAGE, formats[index],
                    targetWidth, targetHeight, 1, mipLevels);
            alt[index] = (MetalGpuTexture) device.createTexture(
                    labelPrefix + index + "-alt", TEXTURE_USAGE, formats[index],
                    targetWidth, targetHeight, 1, mipLevels);
            mainViews[index] = new MetalGpuTextureView(main[index], 0, mipLevels);
            altViews[index] = new MetalGpuTextureView(alt[index], 0, mipLevels);
        }
    }

    int targetCount() {
        return formats.length;
    }

    GpuFormat format(final int index) {
        return formats[checkIndex(index)];
    }

    /** Largest target extent; prefer {@link #width(int)} for a specific target. */
    int width() {
        return width;
    }

    /** Largest target extent; prefer {@link #height(int)} for a specific target. */
    int height() {
        return height;
    }

    int width(final int index) {
        ensureOpen();
        return widths[checkIndex(index)];
    }

    int height(final int index) {
        ensureOpen();
        return heights[checkIndex(index)];
    }

    MetalGpuTexture readTexture(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? alt[index] : main[index];
    }

    /** Returns the read side selected by an execution-plan snapshot. */
    MetalGpuTexture readTexture(final int index, final BitSet snapshot) {
        ensureOpen();
        int checked = checkIndex(index);
        validateSnapshot(snapshot);
        return snapshot.get(checked) ? alt[checked] : main[checked];
    }

    MetalGpuTexture writeTexture(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? main[index] : alt[index];
    }

    /** Returns the write side selected by an execution-plan snapshot. */
    MetalGpuTexture writeTexture(final int index, final BitSet snapshot) {
        ensureOpen();
        int checked = checkIndex(index);
        validateSnapshot(snapshot);
        return snapshot.get(checked) ? main[checked] : alt[checked];
    }

    MetalGpuTexture mainTexture(final int index) {
        ensureOpen();
        return main[checkIndex(index)];
    }

    MetalGpuTexture altTexture(final int index) {
        ensureOpen();
        return alt[checkIndex(index)];
    }

    MetalGpuTextureView readView(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? altViews[index] : mainViews[index];
    }

    MetalGpuTextureView readView(final int index, final BitSet snapshot) {
        ensureOpen();
        int checked = checkIndex(index);
        validateSnapshot(snapshot);
        return snapshot.get(checked) ? altViews[checked] : mainViews[checked];
    }

    MetalGpuTextureView writeView(final int index) {
        ensureOpen();
        return flipped.get(checkIndex(index)) ? mainViews[index] : altViews[index];
    }

    MetalGpuTextureView writeView(final int index, final BitSet snapshot) {
        ensureOpen();
        int checked = checkIndex(index);
        validateSnapshot(snapshot);
        return snapshot.get(checked) ? mainViews[checked] : altViews[checked];
    }

    void enableReadMipmaps(final int index) {
        ensureOpen();
        int checked = checkIndex(index);
        if (!this.mipmappedTargets.get(checked)) {
            throw new IllegalStateException(
                    "Logical target " + checked + " was not allocated with a mip chain"
            );
        }
        if (this.flipped.get(checked)) {
            this.mipmapsOnAlt.set(checked);
        } else {
            this.mipmapsOnMain.set(checked);
        }
    }

    boolean readMipmapsEnabled(final int index) {
        ensureOpen();
        int checked = checkIndex(index);
        return this.flipped.get(checked)
                ? this.mipmapsOnAlt.get(checked)
                : this.mipmapsOnMain.get(checked);
    }

    void resetMipmaps() {
        ensureOpen();
        this.mipmapsOnMain.clear();
        this.mipmapsOnAlt.clear();
    }

    void flip(final int index) {
        ensureOpen();
        int checked = checkIndex(index);
        flipped.flip(checked);
        flippedAtLeastOnce.set(checked);
    }

    boolean isFlipped(final int index) {
        return flipped.get(checkIndex(index));
    }

    /**
     * Probe-only ({@code -Dmetallum.perf}): which side of this pair a texture
     * is, resolved by identity rather than by its label string. Returns
     * {@code "main"}, {@code "alt"}, or {@code null} when the texture does not
     * belong to this pair. Read-only: flipping state is never touched.
     */
    String sideOf(final GpuTexture texture) {
        if (!MetalPerformanceProbe.ENABLED || texture == null) {
            return null;
        }
        ensureOpen();
        for (int index = 0; index < formats.length; index++) {
            if (main[index] == texture) {
                return "main";
            }
            if (alt[index] == texture) {
                return "alt";
            }
        }
        return null;
    }

    boolean flippedAtLeastOnce(final int index) {
        return flippedAtLeastOnce.get(checkIndex(index));
    }

    BitSet snapshot() {
        ensureOpen();
        return (BitSet) flipped.clone();
    }

    void restore(final BitSet snapshot) {
        ensureOpen();
        validateSnapshot(snapshot);
        flipped.clear();
        flipped.or(snapshot);
    }

    void checkNoFeedbackLoop(final int[] writeTargets, final int[] readTargets) {
        checkNoFeedbackLoop(writeTargets, readTargets, snapshot());
    }

    /**
     * Rejects only a physical side alias. Logical Iris feedback is valid when
     * the pass reads one side and writes the other side of a target.
     */
    void checkNoFeedbackLoop(
            final int[] writeTargets,
            final int[] readTargets,
            final BitSet readSide
    ) {
        validateSnapshot(readSide);
        for (int write : writeTargets) {
            checkIndex(write);
            for (int read : readTargets) {
                checkIndex(read);
                boolean writeFromAlt = !flipped.get(write);
                boolean readFromAlt = readSide.get(read);
                if (write == read && writeFromAlt == readFromAlt) {
                    throw new IllegalStateException(
                            "Pass reads and writes the same physical side of logical target " + write
                                    + " (readFromAlt=" + readSide.get(read) + ")"
                    );
                }
            }
        }
    }

    private void validateSnapshot(final BitSet snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.length() > formats.length) {
            throw new IllegalArgumentException("Flip snapshot contains an out-of-range logical target");
        }
    }

    void resize(final int newWidth, final int newHeight) {
        resize(uniform(formats.length, newWidth), uniform(formats.length, newHeight));
    }

    /** Per-target resize: each target is recreated at its own extent. */
    void resize(final int[] newWidths, final int[] newHeights) {
        ensureOpen();
        checkExtents(newWidths, newHeights);
        if (Arrays.equals(newWidths, this.widths) && Arrays.equals(newHeights, this.heights)) {
            return;
        }
        releaseTextures();
        flipped.clear();
        flippedAtLeastOnce.clear();
        resetMipmaps();
        createTextures(newWidths, newHeights);
    }

    private void checkExtents(final int[] newWidths, final int[] newHeights) {
        if (newWidths.length != formats.length || newHeights.length != formats.length) {
            throw new IllegalArgumentException(
                    "Target extent arrays must match the logical target count: "
                            + newWidths.length + "/" + newHeights.length + " vs " + formats.length
            );
        }
        for (int index = 0; index < formats.length; index++) {
            if (newWidths[index] <= 0 || newHeights[index] <= 0) {
                throw new IllegalArgumentException(
                        "Target extent must be positive: " + newWidths[index] + "x" + newHeights[index]
                );
            }
        }
    }

    private static int[] uniform(final int count, final int value) {
        int[] result = new int[count];
        Arrays.fill(result, value);
        return result;
    }

    private static BitSet validatedTargets(final Set<Integer> targets, final int targetCount) {
        BitSet validated = new BitSet(targetCount);
        for (Integer target : targets) {
            if (target == null || target < 0 || target >= targetCount) {
                throw new IllegalArgumentException("Mipmapped logical target out of range: " + target);
            }
            validated.set(target);
        }
        return validated;
    }

    private static int fullMipLevelCount(final int width, final int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    private void releaseTextures() {
        for (int index = 0; index < formats.length; index++) {
            if (mainViews[index] != null) {
                mainViews[index].close();
                mainViews[index] = null;
            }
            if (altViews[index] != null) {
                altViews[index].close();
                altViews[index] = null;
            }
            if (main[index] != null) {
                main[index].close();
                main[index] = null;
            }
            if (alt[index] != null) {
                alt[index].close();
                alt[index] = null;
            }
        }
    }

    private int checkIndex(final int index) {
        if (index < 0 || index >= formats.length) {
            throw new IllegalArgumentException("Logical target index out of range: " + index);
        }
        return index;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Ping-pong targets are closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        releaseTextures();
    }
}
