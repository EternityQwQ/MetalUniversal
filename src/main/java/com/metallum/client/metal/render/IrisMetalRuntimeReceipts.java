package com.metallum.client.metal.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Opt-in final-target evidence for physical Iris runs.
 *
 * <p>The recorder is deliberately inert unless a receipt path is supplied.
 * When enabled, every sampled frame is copied from the final Metal texture
 * into a CPU-visible buffer before its hash and pixel metrics are written.
 * A failed copy is fatal to the run: a missing readback is not a pass.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalRuntimeReceipts implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String RECEIPT_PROPERTY = "metallum.iris.validation.receipt";
    private static final String CAPTURE_PROPERTY = "metallum.iris.validation.captureDir";
    private static final String CAPTURE_EVERY_PROPERTY = "metallum.iris.validation.captureEvery";
    private static final int DEFAULT_CAPTURE_EVERY = 1;
    private static final int MAX_CAPTURE_BYTES = 512 * 1024 * 1024;

    private final int generation;
    private final Path receiptPath;
    private final Path captureDirectory;
    private final int captureEvery;
    private final BufferedWriter writer;
    private long frameIndex;
    private byte[] previousFrame;
    private boolean closed;

    private IrisMetalRuntimeReceipts(
            final int generation,
            final Path receiptPath,
            final Path captureDirectory,
            final int captureEvery,
            final BufferedWriter writer
    ) {
        this.generation = generation;
        this.receiptPath = receiptPath;
        this.captureDirectory = captureDirectory;
        this.captureEvery = captureEvery;
        this.writer = writer;
        writeSession();
    }

    static IrisMetalRuntimeReceipts open(final int generation) {
        String configured = System.getProperty(RECEIPT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return new IrisMetalRuntimeReceipts();
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("Iris receipt generation must be positive: " + generation);
        }
        Path receipt = Path.of(configured).toAbsolutePath().normalize();
        Path capture = configuredCaptureDirectory();
        int captureEvery = configuredCaptureEvery();
        try {
            Path parent = receipt.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (capture != null) {
                Files.createDirectories(capture);
            }
            BufferedWriter writer = Files.newBufferedWriter(
                    receipt,
                    java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
            return new IrisMetalRuntimeReceipts(generation, receipt, capture, captureEvery, writer);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not open Iris runtime receipt " + receipt, failure);
        }
    }

    private IrisMetalRuntimeReceipts() {
        this.generation = 0;
        this.receiptPath = null;
        this.captureDirectory = null;
        this.captureEvery = 0;
        this.writer = null;
    }

    void recordEvent(final String event) {
        if (!enabled()) {
            return;
        }
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("Iris runtime receipt event must be non-empty");
        }
        JsonObject object = baseObject("event");
        object.addProperty("event", event);
        object.addProperty("frame", frameIndex);
        write(object);
    }

    void captureFinalTarget(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final GpuTextureView finalTarget
    ) {
        if (!enabled()) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("Iris runtime receipt is already closed");
        }
        if (!(finalTarget.texture() instanceof MetalGpuTexture texture)) {
            throw new IllegalStateException("Iris final target is not owned by the Metal backend");
        }
        int width = texture.getWidth(0);
        int height = texture.getHeight(0);
        int pixelSize = texture.pixelSize();
        long byteCount = Math.multiplyExact(
                Math.multiplyExact((long) width, height), pixelSize
        );
        if (byteCount <= 0L || byteCount > MAX_CAPTURE_BYTES) {
            throw new IllegalStateException(
                    "Iris final target readback has unsupported size " + byteCount + " bytes"
            );
        }

        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "Iris final target receipt",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                byteCount
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            device.waitForSubmittedGpuWork();
            ByteBuffer mapped = buffer.currentStorage();
            mapped.limit(Math.toIntExact(byteCount));
            byte[] bytes = new byte[Math.toIntExact(byteCount)];
            mapped.get(bytes);
            FrameMetrics metrics = analyze(bytes, width, height, pixelSize, previousFrame);
            Path capturePath = null;
            if (captureDirectory != null && frameIndex % captureEvery == 0L) {
                capturePath = captureDirectory.resolve(
                        String.format(Locale.ROOT, "final-%06d.rgba", frameIndex)
                );
                Files.write(
                        capturePath,
                        bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            }
            JsonObject object = baseObject("final-frame");
            object.addProperty("frame", frameIndex);
            object.addProperty("width", width);
            object.addProperty("height", height);
            object.addProperty("pixelSize", pixelSize);
            object.addProperty("format", texture.getFormat().toString());
            object.addProperty("sha256", metrics.sha256());
            object.addProperty("fnv1a64", Long.toUnsignedString(metrics.fnv1a64(), 16));
            object.addProperty("nonBlackRgbPixels", metrics.nonBlackRgbPixels());
            object.addProperty("changedPixels", metrics.changedPixels());
            object.addProperty("meanAbsoluteByteDelta", metrics.meanAbsoluteByteDelta());
            object.addProperty("maxByte", metrics.maxByte());
            object.addProperty("sumRgbBytes", metrics.sumRgbBytes());
            object.addProperty("hasPreviousFrame", metrics.hasPreviousFrame());
            if (capturePath != null) {
                object.addProperty("capture", capturePath.toString());
            }
            write(object);
            previousFrame = bytes;
            frameIndex++;
        } catch (IOException | RuntimeException failure) {
            if (enabled()) {
                JsonObject error = baseObject("error");
                error.addProperty("frame", frameIndex);
                error.addProperty("operation", "final-target-readback");
                error.addProperty("message", failure.toString());
                write(error);
            }
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new UncheckedIOException((IOException) failure);
        }
    }

    private void writeSession() {
        if (!enabled()) {
            return;
        }
        JsonObject object = baseObject("session");
        object.addProperty("receipt", receiptPath.toString());
        object.addProperty("captureEvery", captureEvery);
        object.addProperty("javaVersion", System.getProperty("java.version", "unknown"));
        object.addProperty("irisVersion", safeIrisVersion());
        object.addProperty("pack", safePackName());
        object.addProperty("metalfxMode", System.getProperty("metallum.metalfx.mode", "unset"));
        object.addProperty(
                "frameGeneration",
                System.getProperty("metallum.metalfx.frameGeneration", "unset")
        );
        addCodeIdentity(object);
        write(object);
    }

    private JsonObject baseObject(final String type) {
        JsonObject object = new JsonObject();
        object.addProperty("schema", "iris-metal-runtime-receipt-v1");
        object.addProperty("type", type);
        object.addProperty("generation", generation);
        return object;
    }

    private void addCodeIdentity(final JsonObject object) {
        try {
            var source = IrisMetalRuntimeReceipts.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return;
            }
            URI uri = source.getLocation().toURI();
            Path path = Path.of(uri).toAbsolutePath().normalize();
            object.addProperty("codeSource", path.toString());
            if (Files.isRegularFile(path)) {
                object.addProperty("codeSourceSha256", sha256(Files.readAllBytes(path)));
            }
        } catch (Exception failure) {
            object.addProperty("codeIdentityError", failure.toString());
        }
    }

    private void write(final JsonObject object) {
        try {
            writer.write(GSON.toJson(object));
            writer.newLine();
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write Iris runtime receipt " + receiptPath, failure);
        }
    }

    boolean enabled() {
        return writer != null;
    }

    private static Path configuredCaptureDirectory() {
        String configured = System.getProperty(CAPTURE_PROPERTY);
        return configured == null || configured.isBlank()
                ? null
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static int configuredCaptureEvery() {
        String configured = System.getProperty(CAPTURE_EVERY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_CAPTURE_EVERY;
        }
        try {
            int value = Integer.parseInt(configured);
            if (value <= 0) {
                throw new NumberFormatException("must be positive");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "Invalid " + CAPTURE_EVERY_PROPERTY + ": " + configured, failure
            );
        }
    }

    private static String safeIrisVersion() {
        try {
            return Iris.getVersion();
        } catch (Throwable failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    private static String safePackName() {
        try {
            return Iris.getCurrentPackName();
        } catch (Throwable failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    static FrameMetrics analyze(
            final byte[] bytes,
            final int width,
            final int height,
            final int pixelSize,
            final byte[] previous
    ) {
        if (bytes == null || width <= 0 || height <= 0 || pixelSize <= 0
                || bytes.length != Math.multiplyExact(Math.multiplyExact(width, height), pixelSize)) {
            throw new IllegalArgumentException("Invalid final-target receipt buffer shape");
        }
        long nonBlack = 0L;
        long changedPixels = 0L;
        long absoluteDelta = 0L;
        long sumRgb = 0L;
        int maxByte = 0;
        for (int pixel = 0; pixel < width * height; pixel++) {
            int base = pixel * pixelSize;
            boolean nonBlackPixel = false;
            for (int channel = 0; channel < Math.min(3, pixelSize); channel++) {
                int value = Byte.toUnsignedInt(bytes[base + channel]);
                nonBlackPixel |= value != 0;
                sumRgb += value;
                maxByte = Math.max(maxByte, value);
            }
            if (nonBlackPixel) {
                nonBlack++;
            }
            if (previous != null) {
                boolean changed = false;
                for (int byteIndex = 0; byteIndex < pixelSize; byteIndex++) {
                    int current = Byte.toUnsignedInt(bytes[base + byteIndex]);
                    int old = Byte.toUnsignedInt(previous[base + byteIndex]);
                    int delta = Math.abs(current - old);
                    absoluteDelta += delta;
                    changed |= delta != 0;
                }
                if (changed) {
                    changedPixels++;
                }
            }
        }
        return new FrameMetrics(
                sha256(bytes),
                fnv1a64(bytes),
                nonBlack,
                changedPixels,
                previous == null ? 0.0D : (double) absoluteDelta / bytes.length,
                maxByte,
                sumRgb,
                previous != null
        );
    }

    private static long fnv1a64(final byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : bytes) {
            hash ^= Byte.toUnsignedLong(value);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError("JRE does not provide SHA-256", failure);
        }
    }

    @Override
    public void close() {
        if (!enabled() || closed) {
            return;
        }
        closed = true;
        recordEvent("generation-retired");
        try {
            writer.close();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not close Iris runtime receipt " + receiptPath, failure);
        }
    }

    record FrameMetrics(
            String sha256,
            long fnv1a64,
            long nonBlackRgbPixels,
            long changedPixels,
            double meanAbsoluteByteDelta,
            int maxByte,
            long sumRgbBytes,
            boolean hasPreviousFrame
    ) {
    }
}
