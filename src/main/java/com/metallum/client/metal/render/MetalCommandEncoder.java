package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.*;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

@Environment(EnvType.CLIENT)
final class MetalCommandEncoder implements CommandEncoderBackend {
    public static final int MAX_SUBMITS_IN_FLIGHT = 3;
    private final MetalDevice device;
    private long currentSubmitIndex = MAX_SUBMITS_IN_FLIGHT;
    private final InFlight[] inFlight = new InFlight[MAX_SUBMITS_IN_FLIGHT];
    private final MemorySegment[] submitSemaphores = new MemorySegment[MAX_SUBMITS_IN_FLIGHT];
    private final MetalDestructionQueue destroyQueue = new MetalDestructionQueue(MAX_SUBMITS_IN_FLIGHT);
    private final MetalTransientMemory transientMemory;
    private final Map<MetalGpuTexture, Vector4fc> pendingColorClears = new IdentityHashMap<>();
    private final Map<MetalGpuTexture, Double> pendingDepthClears = new IdentityHashMap<>();
    private final MemorySegment fence;
    @Nullable
    private MetalRenderPass currentRenderPass;
    @Nullable
    private MTLCommandBuffer commandBuffer;
    @Nullable
    private MTLCommandEncoder currentEncoder;
    private MemorySegment[] renderColorAttachments = new MemorySegment[0];
    private MemorySegment renderDepthAttachment = MemorySegment.NULL;
    /** Pending per-encoder attachment traffic (set on native render encoder creation). */
    private long encoderLoadBytes;
    private long encoderStoreBytes;
    private boolean encoderTrafficPending;
    private final Long2ObjectOpenHashMap<java.util.ArrayDeque<MemorySegment>> dynamicBackingPool = new Long2ObjectOpenHashMap<>();

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.transientMemory = new MetalTransientMemory(device, this);
        fence = MetalNativeBridge.metallum_create_fence(device.metalDeviceHandle());
        if (MetalNativeBridge.isNullHandle(fence)) {
            throw new IllegalStateException("Failed to allocate MTLFence");
        }
        for (int slot = 0; slot < MAX_SUBMITS_IN_FLIGHT; slot++) {
            submitSemaphores[slot] = MetalNativeBridge.metallum_create_semaphore();
            if (MetalNativeBridge.isNullHandle(submitSemaphores[slot])) {
                throw new IllegalStateException("Failed to allocate submit semaphore");
            }
        }
    }

    MTLCommandBuffer commandBuffer() {
        if (commandBuffer != null) {
            return commandBuffer;
        }
        return commandBuffer = device.commandQueue.makeCommandBuffer(
                device.useLabels() ? "Metallum frame " + currentSubmitIndex : null
        );
    }

    MTLBlitCommandEncoder blitCommandEncoder() {
        if (MetalRenderPolicy.reuseBlitEncoder(
                MetalDebugSwitches.BLIT_BATCH,
                currentEncoder instanceof MTLBlitCommandEncoder
        )) {
            // Batching: consecutive copies share the blit encoder that is
            // already open instead of paying an end/recreate pair per copy.
            return (MTLBlitCommandEncoder) currentEncoder;
        }
        endEncoder();
        MTLBlitCommandEncoder encoder = commandBuffer().makeBlitCommandEncoder();
        MetalPerformanceProbe.recordBlitEncoderCreated();
        encoder.waitForFence(fence);
        currentEncoder = encoder;
        return encoder;
    }

    MTLComputeCommandEncoder computeCommandEncoder() {
        endEncoder();
        MTLComputeCommandEncoder encoder = commandBuffer().makeComputeCommandEncoder();
        encoder.waitForFence(fence);
        currentEncoder = encoder;
        return encoder;
    }

    void endEncoder() {
        if (currentEncoder != null) {
            if (currentEncoder instanceof MTLRenderCommandEncoder renderEncoder) {
                renderEncoder.updateFence(fence, MTLRenderStages.VertexAndFragment);
            } else if (currentEncoder instanceof MTLBlitCommandEncoder blitEncoder) {
                blitEncoder.updateFence(fence);
            } else if (currentEncoder instanceof MTLComputeCommandEncoder computeEncoder) {
                computeEncoder.updateFence(fence);
            }
            currentEncoder.endEncoding();
            currentEncoder = null;
            flushEncoderTraffic();
        }
        renderColorAttachments = new MemorySegment[0];
        renderDepthAttachment = MemorySegment.NULL;
    }

    /**
     * Charges the encoder that just ended. The pair is set when a native
     * render encoder is opened ({@link #renderCommandEncoder},
     * {@link #flushPendingClear}); a reused encoder never sets it again, so one
     * reused attachment set is charged exactly one load + one store.
     */
    private void flushEncoderTraffic() {
        if (!encoderTrafficPending) {
            return;
        }
        encoderTrafficPending = false;
        MetalPerformanceProbe.recordEncoderTraffic(encoderLoadBytes, encoderStoreBytes);
        encoderLoadBytes = 0L;
        encoderStoreBytes = 0L;
    }

    /**
     * Begins a mod-private compute pass. Vanilla Blaze3D 26.2 has no compute
     * abstraction, so this API is only reachable from metallum code (Iris
     * backend). The pass owns the underlying compute encoder until
     * {@link MetalComputePass#close()}; interleaving other encoder work while
     * a pass is open is a caller error.
     */
    MetalComputePass createComputePass() {
        submitRenderPass();
        // Pending deferred clears materialize through transient render
        // encoders; they must all land BEFORE the compute encoder opens, since
        // flushing mid-pass would tear the pass's encoder out from under it.
        flushAllPendingClears();
        return new MetalComputePass(this, computeCommandEncoder());
    }

    private void flushAllPendingClears() {
        while (!pendingColorClears.isEmpty() || !pendingDepthClears.isEmpty()) {
            MetalGpuTexture next = !pendingColorClears.isEmpty()
                    ? pendingColorClears.keySet().iterator().next()
                    : pendingDepthClears.keySet().iterator().next();
            flushPendingClear(next);
        }
    }

    boolean hasPendingClear(final MetalGpuTexture texture) {
        return pendingColorClears.containsKey(texture) || pendingDepthClears.containsKey(texture);
    }

    void endComputePass(final MTLComputeCommandEncoder encoder) {
        if (currentEncoder != encoder) {
            throw new IllegalStateException(
                    "Compute pass closed after another encoder was started; passes must be closed before other encoding"
            );
        }
        endEncoder();
    }

    /**
     * GPU mipmap generation for a texture whose levels should derive from
     * level 0 (Iris {@code setupMipmapping}/{@code glGenerateMipmap} semantics).
     * Runs on a blit encoder inside the global fence chain.
     */
    void generateMipmaps(final MetalGpuTexture texture) {
        if (texture.getMipLevels() <= 1) {
            return;
        }
        flushPendingClear(texture);
        blitCommandEncoder().generateMipmaps(texture.nativeHandle());
    }

    @Override
    public @NonNull TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public void submit() {
        if (commandBuffer == null) {
            return;
        }

        submitRenderPass();
        endEncoder();

        int slot = (int) (currentSubmitIndex % MAX_SUBMITS_IN_FLIGHT);
        MemorySegment completedSemaphore = submitSemaphores[slot];
        commandBuffer.commitWithSignal(completedSemaphore);

        InFlight toClose = inFlight[slot];
        inFlight[slot] = new InFlight(currentSubmitIndex, commandBuffer, completedSemaphore);
        commandBuffer = null;
        currentSubmitIndex++;

        if (!awaitSubmitCompletion(currentSubmitIndex - MAX_SUBMITS_IN_FLIGHT, 5000L)) {
            throw new IllegalStateException("5s timeout reached when waiting for Metal submit completion");
        }

        if (toClose != null) {
            if (MetalPerformanceProbe.ENABLED) {
                // The semaphore waited above proves this buffer completed, so
                // Metal's GPU timestamps are final; read them before close().
                double gpuStart = toClose.buffer.gpuStartTime();
                double gpuEnd = toClose.buffer.gpuEndTime();
                if (gpuStart > 0.0 && gpuEnd > gpuStart) {
                    MetalPerformanceProbe.recordGpuFrame((gpuEnd - gpuStart) * 1000.0);
                }
            }
            toClose.buffer.close();
        }

        transientMemory.rotate();
        destroyQueue.rotate();
    }

    MTLRenderCommandEncoder renderCommandEncoder(
            final MetalGpuTextureView[] colorTextureViews,
            @Nullable final MetalGpuTextureView depthTextureView,
            final int viewportWidth,
            final int viewportHeight,
            final int[] clearColorEnabled,
            final float[] clearColorValues,
            final boolean clearDepthEnabled,
            final double clearDepthValue
    ) {
        if (colorTextureViews == null
                || colorTextureViews.length > Math.min(
                        com.mojang.blaze3d.pipeline.ColorTargetState.MAX_COLOR_TARGETS,
                        device.getDeviceInfo().limits().maxColorAttachments()
                )
                || clearColorEnabled == null || clearColorValues == null
                || clearColorEnabled.length != colorTextureViews.length
                || clearColorValues.length != colorTextureViews.length * 4) {
            throw new IllegalArgumentException("Invalid Metal MRT attachment arrays");
        }

        MemorySegment[] colorAttachments = new MemorySegment[colorTextureViews.length];
        for (int index = 0; index < colorTextureViews.length; index++) {
            colorAttachments[index] = colorTextureViews[index] == null
                    ? MemorySegment.NULL
                    : colorTextureViews[index].nativeHandle();
        }
        MemorySegment depthAttachment = depthTextureView == null ? MemorySegment.NULL : depthTextureView.nativeHandle();
        boolean openRenderEncoder = currentEncoder instanceof MTLRenderCommandEncoder;
        boolean sameAttachments = openRenderEncoder
                && sameAttachmentHandles(renderColorAttachments, colorAttachments)
                && MetalPipelineSupport.sameHandle(renderDepthAttachment, depthAttachment);
        boolean clearPending = clearDepthEnabled || hasClearColor(clearColorEnabled);
        if (sameAttachments && !clearPending) {
            MetalPerformanceProbe.recordEncoderDecision(MetalPerformanceProbe.EncoderDecision.REUSE);
            return (MTLRenderCommandEncoder) currentEncoder;
        }
        if (!sameAttachments && openRenderEncoder) {
            // An attachment change tears down the open render encoder and
            // starts a new one; count the transition, not the first encoder.
            MetalPerformanceProbe.recordEncoderSwitch();
        }
        if (MetalPerformanceProbe.ENABLED) {
            // Same inputs the branch above just tested, in the same order, so
            // the attribution can never disagree with the decision itself.
            MetalPerformanceProbe.recordEncoderDecision(MetalPerformanceProbe.encoderDecision(
                    currentEncoder != null,
                    openRenderEncoder,
                    renderColorAttachments.length,
                    colorAttachments.length,
                    sameAttachmentHandles(renderColorAttachments, colorAttachments),
                    MetalPipelineSupport.sameHandle(renderDepthAttachment, depthAttachment),
                    clearPending
            ));
        }

        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoderV2(
                colorAttachments,
                depthAttachment,
                viewportWidth,
                viewportHeight,
                clearColorEnabled,
                clearColorValues,
                clearDepthEnabled ? 1 : 0,
                clearDepthValue
        );
        MetalPerformanceProbe.recordRenderEncoderCreated();
        encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        currentEncoder = encoder;
        renderColorAttachments = colorAttachments;
        renderDepthAttachment = depthAttachment;
        accountEncoderTraffic(colorTextureViews, clearColorEnabled, depthTextureView, clearDepthEnabled);
        return encoder;
    }

    /**
     * Charges this encoder's whole attachment extents once: every attachment
     * is stored, and only the ones opened without a clear are loaded. Uses the
     * level-0 extent of each attachment (Metal's load/store is per attachment,
     * not per render area), which is the same unit the per-pass declared model
     * charges for a full-screen pass.
     */
    private void accountEncoderTraffic(
            final MetalGpuTextureView[] colorViews,
            final int[] clearColorEnabled,
            final @Nullable MetalGpuTextureView depthView,
            final boolean clearDepthEnabled
    ) {
        encoderTrafficPending = false;
        encoderLoadBytes = 0L;
        encoderStoreBytes = 0L;
        if (!MetalPerformanceProbe.ENABLED) {
            return;
        }
        long[] colorBytes = new long[colorViews.length];
        boolean[] colorCleared = new boolean[colorViews.length];
        for (int index = 0; index < colorViews.length; index++) {
            MetalGpuTextureView view = colorViews[index];
            if (view == null) {
                continue;
            }
            colorBytes[index] = attachmentBytes(view);
            colorCleared[index] = clearColorEnabled[index] != 0;
        }
        long depthBytes = depthView == null ? 0L : attachmentBytes(depthView);
        MetalPerformanceProbe.EncoderTraffic traffic = MetalPerformanceProbe.encoderTraffic(
                colorBytes, colorCleared, depthBytes, clearDepthEnabled
        );
        encoderLoadBytes = traffic.loadBytes();
        encoderStoreBytes = traffic.storeBytes();
        encoderTrafficPending = true;
    }

    /** Level-0 extent of one attachment in bytes. */
    private static long attachmentBytes(final MetalGpuTextureView view) {
        GpuTexture texture = view.texture();
        return (long) texture.getWidth(0) * (long) texture.getHeight(0)
                * texture.getFormat().blockSize();
    }

    /**
     * Mirrors Iris's {@code UndoReverseZFive}: while a shaderpack is in use,
     * shaderpack rendering runs in standard z, so depth clear values must be
     * complemented. The GL backend does this in {@code GlCommandEncoder}.
     */
    private static double irisDepthClear(final double clearDepth) {
        return complementDepthClear(clearDepth, Iris.isPackInUseQuick());
    }

    /**
     * Pure {@link #irisDepthClear} body. The shaderpack-active flag is a
     * parameter so the shadow-seed value equivalence can be unit tested
     * without an Iris runtime: both shadow depth maps must be handed the same
     * engine-space far value and get the complement applied exactly once.
     */
    static double complementDepthClear(final double clearDepth, final boolean shaderpackActive) {
        return shaderpackActive ? 1.0 - Math.clamp(clearDepth, 0.0, 1.0) : clearDepth;
    }

    private static boolean hasClearColor(final int[] clearColorEnabled) {
        for (int enabled : clearColorEnabled) {
            if (enabled != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameAttachmentHandles(final MemorySegment[] first, final MemorySegment[] second) {
        if (first.length != second.length) {
            return false;
        }
        for (int index = 0; index < first.length; index++) {
            if (!MetalPipelineSupport.sameHandle(first[index], second[index])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NonNull RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        descriptor = IrisMetalWorldBridge.rewriteWorldDescriptor(this.device, descriptor);
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();
        int maxColorAttachments = Math.min(
                com.mojang.blaze3d.pipeline.ColorTargetState.MAX_COLOR_TARGETS,
                device.getDeviceInfo().limits().maxColorAttachments()
        );
        if (colorAttachments.size() > maxColorAttachments) {
            throw new IllegalArgumentException(
                    "Metal render pass has " + colorAttachments.size()
                            + " color slots but the backend limit is " + maxColorAttachments
            );
        }
        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        if (colorAttachments.isEmpty() && depthAttachment == null) {
            throw new IllegalArgumentException("Metal render pass has no color or depth attachment");
        }

        GpuTextureView extentTexture = null;
        for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment : colorAttachments) {
            if (colorAttachment != null) {
                extentTexture = colorAttachment.textureView();
                break;
            }
        }
        if (extentTexture == null && depthAttachment != null) {
            extentTexture = depthAttachment.textureView();
        }
        if (extentTexture == null) {
            throw new IllegalArgumentException("Metal render pass contains only unused color slots and no depth attachment");
        }

        MetalGpuTextureView[] colorTextureViews = new MetalGpuTextureView[colorAttachments.size()];
        Vector4fc[] clearColors = new Vector4fc[colorAttachments.size()];
        boolean hasColorClear = false;
        for (int index = 0; index < colorAttachments.size(); index++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = colorAttachments.get(index);
            if (colorAttachment == null) {
                continue;
            }
            GpuTextureView colorTexture = colorAttachment.textureView();
            if (colorTexture.isClosed()) {
                throw new IllegalStateException("Color texture " + index + " is closed");
            }
            if ((colorTexture.texture().usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
                throw new IllegalStateException("Color texture " + index + " must have USAGE_RENDER_ATTACHMENT");
            }
            if (colorTexture.texture().getDepthOrLayers() > 1) {
                throw new UnsupportedOperationException("Color texture " + index + " has multiple layers");
            }
            if (colorTexture.getWidth(0) != extentTexture.getWidth(0)
                    || colorTexture.getHeight(0) != extentTexture.getHeight(0)) {
                throw new IllegalArgumentException(
                        "Color texture " + index + " dimensions do not match the first non-null attachment"
                );
            }

            MetalGpuTexture colorTex = (MetalGpuTexture) colorTexture.texture();
            Optional<Vector4fc> colorClear = colorAttachment.clearValue();
            Vector4fc pendingColor = pendingColorClears.get(colorTex);
            if (pendingColor != null && isFullTextureView(colorTexture) && colorClear.isEmpty()) {
                pendingColorClears.remove(colorTex);
                colorClear = Optional.of(pendingColor);
            } else if (pendingColor != null && colorClear.isEmpty()) {
                flushPendingClear(colorTex);
            } else {
                pendingColorClears.remove(colorTex);
            }
            if (colorClear.isPresent()) {
                clearColors[index] = new Vector4f(colorClear.get());
                hasColorClear = true;
            }
            colorTex.markContentsDirty();
            colorTextureViews[index] = (MetalGpuTextureView) colorTexture;
        }

        GpuTextureView depthTexture = depthAttachment == null ? null : depthAttachment.textureView();
        OptionalDouble depthClear = depthAttachment == null
                ? OptionalDouble.empty()
                : (depthAttachment.clearValue().isPresent()
                        ? OptionalDouble.of(irisDepthClear(depthAttachment.clearValue().getAsDouble()))
                        : OptionalDouble.empty());
        if (depthAttachment != null) {
            if (depthTexture.isClosed()) {
                throw new IllegalStateException("Depth texture is closed");
            }
            if ((depthTexture.texture().usage() & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0) {
                throw new IllegalStateException("Depth texture must have USAGE_RENDER_ATTACHMENT");
            }
            if (depthTexture.texture().getDepthOrLayers() > 1) {
                throw new UnsupportedOperationException("Depth texture has multiple layers");
            }
            if (depthTexture.getWidth(0) != extentTexture.getWidth(0)
                    || depthTexture.getHeight(0) != extentTexture.getHeight(0)) {
                throw new IllegalArgumentException(
                        "Depth texture dimensions do not match the first non-null color attachment"
                );
            }
            MetalGpuTexture metalDepth = (MetalGpuTexture) depthTexture.texture();
            Double pendingDepth = pendingDepthClears.get(metalDepth);
            if (pendingDepth != null && isFullTextureView(depthTexture) && depthClear.isEmpty()) {
                pendingDepthClears.remove(metalDepth);
                depthClear = OptionalDouble.of(pendingDepth);
            } else if (pendingDepth != null && depthClear.isEmpty()) {
                flushPendingClear(metalDepth);
            } else {
                pendingDepthClears.remove(metalDepth);
            }
            metalDepth.markContentsDirty();
        }

        assert descriptor.renderArea != null;
        RenderPass.RenderArea renderArea = descriptor.renderArea;
        if (renderArea == null) {
            throw new IllegalArgumentException("RenderPassDescriptor.renderArea must be provided");
        }
        long renderRight = (long) renderArea.x() + renderArea.width();
        long renderBottom = (long) renderArea.y() + renderArea.height();
        if (renderArea.x() < 0 || renderArea.y() < 0
                || renderArea.width() <= 0 || renderArea.height() <= 0
                || renderRight > extentTexture.getWidth(0)
                || renderBottom > extentTexture.getHeight(0)) {
            throw new IllegalArgumentException(
                    "Metal render area " + renderArea + " is outside attachment extent "
                            + extentTexture.getWidth(0) + "x" + extentTexture.getHeight(0)
            );
        }
        MetalRenderPass renderPass = new MetalRenderPass(
                device,
                this,
                descriptor.label(),
                colorTextureViews,
                depthTexture,
                renderArea,
                hasColorClear ? clearColors : null,
                depthClear.isPresent(),
                depthClear.orElse(0.0)
        );
        recordPassTraffic(descriptor, colorTextureViews, clearColors, depthTexture, depthClear.isPresent(), extentTexture, renderArea);
        currentRenderPass = renderPass;
        renderPass.pushDebugGroup(descriptor.label());
        MetalPerformanceProbe.recordRenderPassCreated();
        return renderPass;
    }

    @Override
    public void submitRenderPass() {
        if (currentRenderPass != null) {
            currentRenderPass.materializePendingClear();
            currentRenderPass.popDebugGroup();
            currentRenderPass = null;
            MetalPerformanceProbe.recordRenderPassSubmitted();
        }
    }

    /**
     * Feeds one render pass's attachment traffic into {@link MetalPerformanceProbe}
     * ({@code -Dmetallum.perf} only, so a release run allocates none of it).
     * The byte math lives in the probe and is unit tested without Metal; here
     * we only read the formats, extents and clear flags the descriptor already
     * carries.
     */
    private static void recordPassTraffic(
            final RenderPassDescriptor descriptor,
            final MetalGpuTextureView[] colorViews,
            final Vector4fc[] clearColors,
            final GpuTextureView depthTexture,
            final boolean depthClear,
            final GpuTextureView extent,
            final RenderPass.RenderArea renderArea
    ) {
        if (!MetalPerformanceProbe.ENABLED) {
            return;
        }
        int[] colorBpp = new int[colorViews.length];
        boolean[] colorCleared = new boolean[colorViews.length];
        boolean allViewsFull = true;
        for (int index = 0; index < colorViews.length; index++) {
            MetalGpuTextureView view = colorViews[index];
            if (view == null) {
                continue;
            }
            colorBpp[index] = view.texture().getFormat().blockSize();
            colorCleared[index] = clearColors[index] != null;
            allViewsFull &= isFullTextureView(view);
        }
        int depthBpp = 0;
        if (depthTexture != null) {
            depthBpp = depthTexture.texture().getFormat().blockSize();
            allViewsFull &= isFullTextureView(depthTexture);
        }
        MetalPerformanceProbe.PassTraffic traffic = MetalPerformanceProbe.passTraffic(
                renderArea.x(), renderArea.y(), renderArea.width(), renderArea.height(),
                extent.getWidth(0), extent.getHeight(0), allViewsFull,
                colorBpp, colorCleared, depthBpp, depthClear
        );
        MetalPerformanceProbe.recordRenderPassTraffic(descriptor.label().get(), traffic);
        if (MetalPerformanceProbe.capturingOrder()) {
            // The dump samples only the first frames of a world, so the extra
            // label read and identity scan never run in steady state.
            MetalPerformanceProbe.recordRenderPassOrder(descriptor.label().get(), passFlipSide(colorViews));
        }
    }

    /**
     * Probe-only ({@code -Dmetallum.perf}, order dump frames only): which
     * ping-pong side this pass's color attachments belong to, resolved from
     * {@link IrisMetalPingPongTargets} by texture identity (never from a label
     * string, so it still works with debug labels off).
     *
     * @return {@code "main"} / {@code "alt"} when every recognized attachment
     *         is on that side, {@code "mixed"} when they disagree, and
     *         {@code null} when no attachment is a ping-pong target (a vanilla
     *         pass) or the generation's targets are gone.
     */
    private static @Nullable String passFlipSide(final MetalGpuTextureView[] colorViews) {
        try {
            WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
            if (!(pipeline instanceof MetalWorldRenderingPipeline metal)) {
                return null;
            }
            IrisMetalWorldResources resources = metal.resources();
            IrisMetalRenderTargets renderTargets = resources.renderTargets();
            IrisMetalPingPongTargets colorTargets = renderTargets.colorTargets();
            IrisMetalShadowTargets shadowTargets = resources.shadowTargets();
            IrisMetalPingPongTargets shadowColorTargets =
                    shadowTargets == null ? null : shadowTargets.colorTargets();
            String side = null;
            for (MetalGpuTextureView view : colorViews) {
                if (view == null) {
                    continue;
                }
                GpuTexture texture = view.texture();
                String viewSide = colorTargets.sideOf(texture);
                if (viewSide == null && shadowColorTargets != null) {
                    viewSide = shadowColorTargets.sideOf(texture);
                }
                if (viewSide == null) {
                    continue;
                }
                if (side == null) {
                    side = viewSide;
                } else if (!side.equals(viewSide)) {
                    return "mixed";
                }
            }
            return side;
        } catch (RuntimeException ignored) {
            // A resize or pack reload can close the targets between passes; a
            // probe sample must never take the frame down for that.
            return null;
        }
    }

    /**
     * Returns the currently-active {@link MetalRenderPass}, or {@code null} if
     * no render pass is active (i.e. {@code createRenderPass} has not been
     * called, or {@code submitRenderPass} has already finalized it).
     *
     * <p>Package-private accessor used by {@link MetalIrisProgram} (the Metal
     * equivalent of Iris's {@code IrisProgram}) to reach the live render pass
     * during {@code iris$setupState} so it can swap in a shaderpack's
     * pre-compiled Metal render pipeline and bind samplers against the
     * shaderpack's resource table.
     *
     * @return the active Metal render pass, or {@code null}.
     */
    @Nullable
    MetalRenderPass currentRenderPass() {
        return currentRenderPass;
    }

    void presentTextureToDrawable(final MemorySegment drawable, final GpuTextureView textureView) {
        MetalGpuTexture source = (MetalGpuTexture) textureView.texture();
        flushPendingClear(source);
        submitRenderPass();
        endEncoder();
        MTLCommandBuffer commandBuffer = commandBuffer();
        commandBuffer.encodePresentTextureToDrawable(drawable, source.nativeHandle(), fence);
    }

    @Override
    public void clearColorTexture(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor) {
        pendingColorClears.put((MetalGpuTexture) colorTexture, new Vector4f(clearColor));
        MetalPerformanceProbe.recordClearQueued();
    }

    @Override
    public void clearColorAndDepthTextures(final @NonNull GpuTexture colorTexture, final @NonNull Vector4fc clearColor, final @NonNull GpuTexture depthTexture, final double clearDepth) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        pendingColorClears.put(color, new Vector4f(clearColor));
        pendingDepthClears.put(depth, irisDepthClear(clearDepth));
        MetalPerformanceProbe.recordClearQueued();
        MetalPerformanceProbe.recordClearQueued();
    }

    @Override
    public void clearColorAndDepthTextures(
            final @NonNull GpuTexture colorTexture,
            final @NonNull Vector4fc clearColor,
            final @NonNull GpuTexture depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight
    ) {
        MetalGpuTexture color = (MetalGpuTexture) colorTexture;
        MetalGpuTexture depth = (MetalGpuTexture) depthTexture;
        Vector4fc clearColorCopy = new Vector4f(clearColor);
        if (isFullTextureRegion(color, depth, regionX, regionY, regionWidth, regionHeight)) {
            pendingColorClears.put(color, clearColorCopy);
            pendingDepthClears.put(depth, irisDepthClear(clearDepth));
            MetalPerformanceProbe.recordClearQueued();
            MetalPerformanceProbe.recordClearQueued();
            return;
        }
        color.markContentsDirty();
        depth.markContentsDirty();
        submitRenderPass();
        endEncoder();
        MetalPerformanceProbe.recordClearMaterialized();
        commandBuffer().clearColorDepthTexturesRegion(
                color.nativeHandle(),
                clearColorCopy.x(),
                clearColorCopy.y(),
                clearColorCopy.z(),
                clearColorCopy.w(),
                depth.nativeHandle(),
                irisDepthClear(clearDepth),
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                fence
        );
    }

    @Override
    public void clearDepthTexture(final @NonNull GpuTexture depthTexture, final double clearDepth) {
        pendingDepthClears.put((MetalGpuTexture) depthTexture, irisDepthClear(clearDepth));
        MetalPerformanceProbe.recordClearQueued();
    }

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination.buffer();
        int length = data.remaining();

        if (buffer.isDynamic()) {
            orphanWrite(buffer, destination.offset(), data);
            return;
        }

        // Heap buffers have no stable native address; the transient-memory
        // staging upload memcpys from memAddress(data) and would SIGBUS the JVM.
        if (!data.isDirect()) {
            throw new IllegalArgumentException("writeToBuffer requires a direct ByteBuffer");
        }

        GpuBufferSlice staging = transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC);
        MetalGpuBuffer stagingBuffer = (MetalGpuBuffer) staging.buffer();

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                stagingBuffer.nativeHandle(),
                staging.offset(),
                buffer.nativeHandle(),
                destination.offset(),
                length
        );
        endEncoder();
    }

    private void orphanWrite(final MetalGpuBuffer buffer, final long offset, final ByteBuffer data) {
        long size = buffer.allocationSize();
        MemorySegment old = buffer.nativeHandle();
        MemorySegment fresh = acquireDynamicBacking(size, buffer.resourceOptions());
        ByteBuffer freshStorage = MetalNativeBridge.nativeByteBufferView(
                MetalNativeBridge.metallum_get_buffer_contents(fresh), size).order(ByteOrder.nativeOrder());

        if (offset != 0 || data.remaining() != buffer.size()) {
            ByteBuffer previous = buffer.currentStorage();
            previous.clear();
            freshStorage.duplicate().put(previous);
        }

        ByteBuffer dst = freshStorage.duplicate().order(ByteOrder.nativeOrder());
        dst.position(Math.toIntExact(offset));
        dst.put(data.duplicate());

        buffer.swapBacking(fresh, freshStorage);
        recycleDynamicBacking(old, size);
    }

    private MemorySegment acquireDynamicBacking(final long size, final long resourceOptions) {
        java.util.ArrayDeque<MemorySegment> bucket = dynamicBackingPool.get(size);
        if (bucket != null && !bucket.isEmpty()) {
            return bucket.pop();
        }
        MemorySegment handle = MetalNativeBridge.metallum_create_buffer(device.metalDeviceHandle(), size, resourceOptions);
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("Failed to create dynamic backing buffer");
        }
        return handle;
    }

    private void recycleDynamicBacking(final MemorySegment handle, final long size) {
        queueForDestroy(() -> dynamicBackingPool.computeIfAbsent(size, k -> new java.util.ArrayDeque<>()).push(handle));
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        MetalGpuBuffer sourceBuffer = (MetalGpuBuffer) source.buffer();
        MetalGpuBuffer targetBuffer = (MetalGpuBuffer) target.buffer();
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToBuffer(
                sourceBuffer.nativeHandle(),
                source.offset(),
                targetBuffer.nativeHandle(),
                target.offset(),
                source.length()
        );
        endEncoder();
    }

    @Override
    public void writeToTexture(
            final @NonNull GpuTexture destination,
            final @NonNull ByteBuffer source,
            final int mipLevel,
            final int depthOrLayer,
            final int destX,
            final int destY,
            final int width,
            final int height
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        flushPendingClearForWrite(metalDst);

        // Heap buffers have no stable native address; the transient-memory
        // staging upload memcpys from memAddress(source) and would SIGBUS.
        if (!source.isDirect()) {
            throw new IllegalArgumentException("writeToTexture requires a direct ByteBuffer");
        }

        int pixelSize = metalDst.pixelSize();
        int rowBytes = width * pixelSize;
        int bytesPerImage = rowBytes * height;
        int depth = metalDst.isTexture3D()
                ? Math.max(1, metalDst.getDepthOrLayers() >> mipLevel)
                : 1;
        long bytesRequired = Math.multiplyExact((long) bytesPerImage, depth);
        ByteBuffer upload = source.duplicate();
        if (upload.remaining() < bytesRequired) {
            throw new IllegalArgumentException(
                    "Texture upload has " + upload.remaining() + " bytes but requires " + bytesRequired
            );
        }
        upload.limit(Math.toIntExact(upload.position() + bytesRequired));
        GpuBufferSlice slice = transientMemory.uploadStaging(upload, pixelSize, GpuBuffer.USAGE_COPY_SRC);

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        if (metalDst.isTexture3D()) {
            if (depthOrLayer != 0) {
                throw new IllegalArgumentException("3D texture uploads do not accept an array layer: " + depthOrLayer);
            }
            blit.copyFromBufferToTexture3d(
                    ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                    slice.offset(),
                    metalDst.nativeHandle(),
                    mipLevel,
                    destX,
                    destY,
                    0,
                    width,
                    height,
                    depth,
                    rowBytes,
                    bytesPerImage
            );
        } else {
            blit.copyFromBufferToTexture(
                    ((MetalGpuBuffer) slice.buffer()).nativeHandle(),
                    slice.offset(),
                    metalDst.nativeHandle(),
                    mipLevel,
                    depthOrLayer,
                    destX,
                    destY,
                    width,
                    height,
                    rowBytes,
                    bytesPerImage
            );
        }
        endEncoder();
    }

    @Override
    public void copyBufferToTexture(
            final @NonNull GpuBufferSlice source,
            final int sourceX,
            final int sourceY,
            final int sourceWidth,
            final int sourceHeight,
            final @NonNull GpuTexture destination,
            final int destinationX,
            final int destinationY,
            final int copyWidth,
            final int copyHeight,
            final int mipLevel,
            final int arrayLayer
    ) {
        MetalGpuTexture metalDst = (MetalGpuTexture) destination;
        if (metalDst.isTexture3D()) {
            throw new UnsupportedOperationException(
                    "copyBufferToTexture does not expose a 3D source extent; use writeToTexture"
            );
        }
        flushPendingClearForWrite(metalDst);

        int texelSize = destination.getFormat().blockSize();
        long skipBytes = (sourceX + (long) sourceY * sourceWidth) * texelSize;
        long rowBytes = (long) sourceWidth * texelSize;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromBufferToTexture(
                ((MetalGpuBuffer) source.buffer()).nativeHandle(),
                source.offset() + skipBytes,
                metalDst.nativeHandle(),
                mipLevel,
                arrayLayer,
                destinationX,
                destinationY,
                copyWidth,
                copyHeight,
                rowBytes,
                rowBytes * sourceHeight
        );
        endEncoder();
    }

    @Override
    public void copyTextureToBuffer(final @NonNull GpuTexture source, final @NonNull GpuBuffer destination, final long offset, final @NonNull Runnable callback, final int mipLevel) {
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(
            final @NonNull GpuTexture source,
            final @NonNull GpuBuffer destination,
            final long offset,
            final @NonNull Runnable callback,
            final int mipLevel,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        MetalGpuTexture texture = (MetalGpuTexture) source;
        flushPendingClear(texture);
        MetalGpuBuffer buffer = (MetalGpuBuffer) destination;
        int bytesPerPixel = texture.pixelSize();
        int rowBytes = width * bytesPerPixel;
        int bytesPerImage = rowBytes * height;

        MTLBlitCommandEncoder blit = blitCommandEncoder();
        if (texture.isTexture3D()) {
            int depth = Math.max(1, texture.getDepthOrLayers() >> mipLevel);
            blit.copyFromTexture3dToBuffer(
                    texture.nativeHandle(), buffer.nativeHandle(), offset, mipLevel,
                    x, y, 0, width, height, depth, rowBytes, bytesPerImage
            );
        } else {
            blit.copyFromTextureToBuffer(
                    texture.nativeHandle(),
                    buffer.nativeHandle(),
                    offset,
                    mipLevel,
                    0,
                    x,
                    y,
                    width,
                    height,
                    rowBytes,
                    bytesPerImage
            );
        }

        endEncoder();
        queueForDestroy(callback);
    }

    @Override
    public void copyTextureToTexture(
            final @NonNull GpuTexture source,
            final @NonNull GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height
    ) {
        copyTextureToTexture(
                source, destination, mipLevel, destX, destY, sourceX, sourceY, width, height, "other"
        );
    }

    /**
     * Copy variant that attributes the transferred bytes to a probe reason
     * label ({@code -Dmetallum.perf} aggregation only). Without a label the
     * copy still counts as {@code other}.
     */
    void copyTextureToTexture(
            final GpuTexture source,
            final GpuTexture destination,
            final int mipLevel,
            final int destX,
            final int destY,
            final int sourceX,
            final int sourceY,
            final int width,
            final int height,
            final String reason
    ) {
        MetalGpuTexture srcTexture = (MetalGpuTexture) source;
        MetalGpuTexture dstTexture = (MetalGpuTexture) destination;
        if (srcTexture.isTexture3D() || dstTexture.isTexture3D()) {
            throw new UnsupportedOperationException(
                    "copyTextureToTexture does not expose a 3D extent; use a dedicated 3D copy path"
            );
        }
        flushPendingClear(srcTexture);
        flushPendingClearForWrite(dstTexture);
        MetalPerformanceProbe.recordTextureCopy(
                (long) width * (long) height * srcTexture.getFormat().blockSize(),
                reason
        );
        MTLBlitCommandEncoder blit = blitCommandEncoder();
        blit.copyFromTextureToTexture(
                srcTexture.nativeHandle(),
                dstTexture.nativeHandle(),
                mipLevel,
                sourceX,
                sourceY,
                destX,
                destY,
                width,
                height
        );
        if (!MetalDebugSwitches.BLIT_BATCH) {
            endEncoder();
        }
    }

    @Override
    public @NonNull GpuFence createFence() {
        return new MetalFence(this, currentSubmitIndex);
    }

    void queueForDestroy(final Runnable destroyAction) {
        destroyQueue.add(destroyAction);
    }

    boolean awaitSubmitCompletion(final long submitIndex, final long timeoutMs) {
        if (submitIndex == currentSubmitIndex) {
            throw new IllegalStateException("Cannot wait on a fence for the current submit");
        }
        for (InFlight f : inFlight) {
            if (f != null && f.index == submitIndex) {
                return MetalNativeBridge.metallum_semaphore_wait(f.completedSemaphore, Math.max(timeoutMs, 0L)) == 0;
            }
        }
        return true;
    }

    void close() {
        submitRenderPass();
        endEncoder();
        for (int slot = 0; slot < inFlight.length; slot++) {
            InFlight f = inFlight[slot];
            if (f != null) {
                f.buffer.close();
                inFlight[slot] = null;
            }
        }
        for (int slot = 0; slot < submitSemaphores.length; slot++) {
            if (!MetalNativeBridge.isNullHandle(submitSemaphores[slot])) {
                MetalNativeBridge.metallum_release_object(submitSemaphores[slot]);
                submitSemaphores[slot] = MemorySegment.NULL;
            }
        }
        if (commandBuffer != null) {
            commandBuffer.close();
            commandBuffer = null;
        }
        transientMemory.close();
        device.queueResourceRelease(fence);
        destroyQueue.close();
        for (java.util.ArrayDeque<MemorySegment> bucket : dynamicBackingPool.values()) {
            for (MemorySegment handle : bucket) {
                MetalNativeBridge.metallum_release_object(handle);
            }
        }
        dynamicBackingPool.clear();
    }

    void waitForSubmittedGpuWork() {
        if (commandBuffer != null || currentRenderPass != null || currentEncoder != null) {
            submit();
        } else {
            endEncoder();
        }
        long latestSubmit = currentSubmitIndex - 1L;
        if (latestSubmit >= MAX_SUBMITS_IN_FLIGHT) {
            awaitSubmitCompletion(latestSubmit, Long.MAX_VALUE);
        }
    }

    @Override
    public void writeTimestamp(final @NonNull GpuQueryPool pool, final int index) {
        if (pool instanceof MetalGpuQueryPool metalPool && index >= 0 && index < pool.size()) {
            metalPool.setValue(index, device.getTimestampNow());
        }
    }

    private void flushPendingClearForWrite(final MetalGpuTexture texture) {
        flushPendingClear(texture);
        texture.markContentsDirty();
    }

    void flushPendingClear(final MetalGpuTexture texture) {
        Vector4fc colorClear = pendingColorClears.remove(texture);
        Double depthClear = pendingDepthClears.remove(texture);
        if (colorClear == null && depthClear == null) {
            return;
        }

        if (texture.clearIsRedundant(colorClear, depthClear)) {
            return;
        }

        MetalPerformanceProbe.recordClearMaterialized();
        endEncoder();
        MTLRenderCommandEncoder encoder = commandBuffer().makeRenderCommandEncoder(
                colorClear != null ? texture.nativeHandle() : null,
                depthClear != null ? texture.nativeHandle() : null,
                1.0, 1.0,
                colorClear != null ? 1 : 0,
                colorClear != null ? colorClear.x() : 0.0F,
                colorClear != null ? colorClear.y() : 0.0F,
                colorClear != null ? colorClear.z() : 0.0F,
                colorClear != null ? colorClear.w() : 0.0F,
                depthClear != null ? 1 : 0,
                depthClear != null ? depthClear : 1.0
        );
        MetalPerformanceProbe.recordClearEncoderCreated();
        if (MetalPerformanceProbe.ENABLED) {
            // A materialized clear never loads: both attached extents only
            // store their cleared contents.
            long clearBytes = (long) texture.getWidth(0) * (long) texture.getHeight(0)
                    * texture.getFormat().blockSize();
            MetalPerformanceProbe.EncoderTraffic clearTraffic = MetalPerformanceProbe.encoderTraffic(
                    new long[]{colorClear != null ? clearBytes : 0L},
                    new boolean[]{true},
                    depthClear != null ? clearBytes : 0L,
                    true
            );
            encoderTrafficPending = true;
            encoderLoadBytes = clearTraffic.loadBytes();
            encoderStoreBytes = clearTraffic.storeBytes();
        }
        encoder.waitForFence(fence, MTLRenderStages.VertexAndFragment);
        currentEncoder = encoder;
        texture.recordMaterializedClear(colorClear, depthClear);
    }

    private static boolean isFullTextureView(final GpuTextureView textureView) {
        return textureView.baseMipLevel() == 0
                && textureView.mipLevels() >= textureView.texture().getMipLevels()
                && textureView.texture().getDepthOrLayers() == 1;
    }

    private static boolean isFullTextureRegion(
            final MetalGpuTexture color,
            final MetalGpuTexture depth,
            final int x,
            final int y,
            final int width,
            final int height
    ) {
        return x == 0
                && y == 0
                && width == color.getWidth(0)
                && height == color.getHeight(0)
                && width == depth.getWidth(0)
                && height == depth.getHeight(0);
    }

    private record InFlight(long index, MTLCommandBuffer buffer, MemorySegment completedSemaphore) {
    }
}
