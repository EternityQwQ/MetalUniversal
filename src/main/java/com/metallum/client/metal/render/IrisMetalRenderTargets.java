package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.mtl.MTLSamplerMipFilter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Generation-owned Iris colortex ping-pong and depthtex0/1/2 resources. */
@Environment(EnvType.CLIENT)
final class IrisMetalRenderTargets implements AutoCloseable {
    private static final int DEPTH_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;

    /** {@code metallum.iris.debug.magentaClear}: colortex0 clear color for unwritten-region bisection. */
    private static final Vector4fc MAGENTA_CLEAR_COLOR = new Vector4f(1.0F, 0.0F, 1.0F, 1.0F);

    private final MetalDevice device;
    private final IrisMetalPingPongTargets colorTargets;
    private final Map<Integer, RenderTargetSettings> targetSettings;
    /** Pack directives backing per-target {@code size.buffer} extents; null on the uniform/test path. */
    private final @Nullable PackDirectives directives;
    private IrisMetalRenderTargetExtents.Extents extents;
    private MetalGpuTexture mainDepth;
    private MetalGpuTexture noTranslucentsDepth;
    private MetalGpuTexture noHandDepth;
    private MetalGpuTextureView mainDepthView;
    private MetalGpuTextureView noTranslucentsDepthView;
    private MetalGpuTextureView noHandDepthView;
    private final MetalGpuSampler colorSampler;
    private final MetalGpuSampler nearestSampler;
    private final MetalGpuSampler colorMipSampler;
    private final MetalGpuSampler nearestMipSampler;
    private int width;
    private int height;
    private boolean fullClearRequired = true;
    private boolean closed;

    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height
    ) {
        this(device, colorFormats, width, height, Map.of(), Set.of());
    }

    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings
    ) {
        this(device, colorFormats, width, height, targetSettings, Set.of());
    }

    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings,
            final Set<Integer> mipmappedTargets
    ) {
        this(device, colorFormats, width, height, targetSettings, mipmappedTargets, null);
    }

    /**
     * Pack-path constructor: resolves every target's extent from the pack's
     * {@code size.buffer.*} directives (via
     * {@link IrisMetalRenderTargetExtents}) instead of using one uniform size.
     */
    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings,
            final Set<Integer> mipmappedTargets,
            final @Nullable PackDirectives directives
    ) {
        this.device = device;
        this.directives = directives;
        this.extents = IrisMetalRenderTargetExtents.from(
                directives, colorFormats.length, width, height
        );
        this.colorTargets = new IrisMetalPingPongTargets(
                device, "iris-colortex", colorFormats,
                this.extents.widths(), this.extents.heights(), mipmappedTargets
        );
        this.targetSettings = Map.copyOf(targetSettings);
        this.colorSampler = sampler(FilterMode.LINEAR, MTLSamplerMipFilter.NotMipmapped);
        this.nearestSampler = sampler(FilterMode.NEAREST, MTLSamplerMipFilter.NotMipmapped);
        this.colorMipSampler = sampler(FilterMode.LINEAR, MTLSamplerMipFilter.Linear);
        this.nearestMipSampler = sampler(FilterMode.NEAREST, MTLSamplerMipFilter.Linear);
        createDepthTextures(width, height);
        reportExtentsOnce();
    }

    private void reportExtentsOnce() {
        if (!MetalDebugSwitches.SIZE_BUFFER || !extents.anyNonBase()) {
            return;
        }
        String summary = extents.nonBaseSummary();
        if (REPORTED_EXTENTS.add(summary + "@" + extents.baseWidth() + "x" + extents.baseHeight())) {
            Metallum.LOGGER.info(
                    "[metallum-iris] size.buffer extents: {} (base {}x{})",
                    summary, extents.baseWidth(), extents.baseHeight()
            );
        }
    }

    /** Dedupes the one-line extents report across frames/resizes. */
    private static final Set<String> REPORTED_EXTENTS = ConcurrentHashMap.newKeySet();

    private MetalGpuSampler sampler(final FilterMode filter, final MTLSamplerMipFilter mipFilter) {
        return new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                filter,
                filter,
                1,
                OptionalDouble.empty(),
                null,
                mipFilter
        );
    }

    boolean clearForFrame(final MetalCommandEncoder encoder, final Vector4fc fogColor) {
        ensureOpen();
        Vector4f fog = new Vector4f(fogColor.x(), fogColor.y(), fogColor.z(), 1.0F);
        boolean fullClear = this.fullClearRequired;
        for (int index = 0; index < colorTargets.targetCount(); index++) {
            RenderTargetSettings settings = targetSettings.get(index);
            if (!fullClear && (settings == null || !settings.shouldClear())) {
                continue;
            }
            Vector4fc clear = settings == null || settings.getClearColor().isEmpty()
                    ? defaultClearColor(index, fog)
                    : settings.getClearColor().get();
            if (MetalDebugSwitches.MAGENTA_CLEAR && index == 0) {
                clear = MAGENTA_CLEAR_COLOR;
            }
            encoder.clearColorTexture(colorTargets.mainTexture(index), clear);
            encoder.clearColorTexture(colorTargets.altTexture(index), clear);
        }
        this.fullClearRequired = false;
        return fullClear;
    }

    private static Vector4f defaultClearColor(final int index, final Vector4fc fogColor) {
        if (index == 0) {
            return new Vector4f(fogColor);
        }
        if (index == 1) {
            return new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
    }

    /**
     * Resolves a pack sampler name to its logical colortex index. Accepts
     * {@code colortexN} directly and Iris's legacy render-target aliases
     * ({@code gcolor}..{@code gaux4}) through
     * {@link PackRenderTargetDirectives#LEGACY_RENDER_TARGETS}, whose list
     * position <em>is</em> the colortex index. Names outside both tables (and a
     * {@code colortex} prefix followed by a non-number) return {@code -1}.
     */
    static int renderTargetIndex(final String name) {
        if (name.startsWith("colortex")) {
            try {
                return Integer.parseInt(name.substring("colortex".length()));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.indexOf(name);
    }

    /**
     * Resolves a pack sampler name to its logical shadowcolor index: the
     * explicit {@code shadowcolor} is 0, {@code shadowcolorN} is N, and
     * anything else ({@code shadowcolorimg}, a non-numeric suffix, a
     * different prefix) is {@code -1}. Callers bounds-check the result against
     * the generation's shadowcolor target count. Shared by the world/terrain
     * bridges and the execution graph so all three agree on the mapping.
     */
    static int shadowColorIndex(final String name) {
        if (name.equals("shadowcolor")) {
            return 0;
        }
        if (!name.startsWith("shadowcolor") || name.startsWith("shadowcolorimg")) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring("shadowcolor".length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void createDepthTextures(final int newWidth, final int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            throw new IllegalArgumentException("Target extent must be positive: " + newWidth + "x" + newHeight);
        }
        this.width = newWidth;
        this.height = newHeight;
        this.mainDepth = texture("iris-depthtex0", newWidth, newHeight);
        this.noTranslucentsDepth = texture("iris-depthtex1", newWidth, newHeight);
        this.noHandDepth = texture("iris-depthtex2", newWidth, newHeight);
        this.mainDepthView = new MetalGpuTextureView(this.mainDepth, 0, 1);
        this.noTranslucentsDepthView = new MetalGpuTextureView(this.noTranslucentsDepth, 0, 1);
        this.noHandDepthView = new MetalGpuTextureView(this.noHandDepth, 0, 1);
    }

    private MetalGpuTexture texture(final String label, final int textureWidth, final int textureHeight) {
        return (MetalGpuTexture) device.createTexture(
                label, DEPTH_USAGE, GpuFormat.D32_FLOAT, textureWidth, textureHeight, 1, 1
        );
    }

    IrisMetalPingPongTargets colorTargets() {
        ensureOpen();
        return colorTargets;
    }

    MetalGpuTexture mainDepthTexture() {
        ensureOpen();
        return mainDepth;
    }

    MetalGpuTexture noTranslucentsDepthTexture() {
        ensureOpen();
        return noTranslucentsDepth;
    }

    MetalGpuTexture noHandDepthTexture() {
        ensureOpen();
        return noHandDepth;
    }

    MetalGpuTextureView mainDepthView() {
        ensureOpen();
        return mainDepthView;
    }

    MetalGpuTextureView noTranslucentsDepthView() {
        ensureOpen();
        return noTranslucentsDepthView;
    }

    MetalGpuTextureView noHandDepthView() {
        ensureOpen();
        return noHandDepthView;
    }

    GpuSampler colorSampler() {
        ensureOpen();
        return colorSampler;
    }

    GpuSampler colorSampler(final int logicalTarget) {
        ensureOpen();
        String componentType = colorTargets.format(logicalTarget).componentType().name();
        boolean nearest = componentType.startsWith("UINT") || componentType.startsWith("SINT");
        boolean mipmapped = colorTargets.readMipmapsEnabled(logicalTarget);
        if (nearest) {
            return mipmapped ? nearestMipSampler : nearestSampler;
        }
        return mipmapped ? colorMipSampler : colorSampler;
    }

    void enableReadMipmaps(final int logicalTarget) {
        ensureOpen();
        colorTargets.enableReadMipmaps(logicalTarget);
    }

    void resetMipmaps() {
        ensureOpen();
        colorTargets.resetMipmaps();
    }

    GpuSampler depthSampler() {
        ensureOpen();
        return nearestSampler;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Pixel width of one logical colortex target (base size unless {@code size.buffer} overrides it). */
    int targetWidth(final int logicalTarget) {
        ensureOpen();
        return colorTargets.width(logicalTarget);
    }

    /** Pixel height of one logical colortex target (base size unless {@code size.buffer} overrides it). */
    int targetHeight(final int logicalTarget) {
        ensureOpen();
        return colorTargets.height(logicalTarget);
    }

    void captureNoTranslucentsDepth(final MetalCommandEncoder encoder) {
        ensureOpen();
        encoder.copyTextureToTexture(mainDepth, noTranslucentsDepth, 0, 0, 0, 0, 0, width, height, "depth.depthtex1");
    }

    void captureNoTranslucentsDepth(final MetalCommandEncoder encoder, final GpuTexture sourceDepth) {
        ensureOpen();
        checkDepthExtent(sourceDepth);
        encoder.copyTextureToTexture(sourceDepth, noTranslucentsDepth, 0, 0, 0, 0, 0, width, height, "depth.depthtex1");
    }

    void captureMainDepth(final MetalCommandEncoder encoder, final GpuTexture sourceDepth) {
        ensureOpen();
        checkDepthExtent(sourceDepth);
        encoder.copyTextureToTexture(sourceDepth, mainDepth, 0, 0, 0, 0, 0, width, height, "depth.depthtex0");
    }

    void captureNoHandDepth(final MetalCommandEncoder encoder) {
        ensureOpen();
        encoder.copyTextureToTexture(mainDepth, noHandDepth, 0, 0, 0, 0, 0, width, height, "depth.depthtex2");
    }

    void captureNoHandDepth(final MetalCommandEncoder encoder, final GpuTexture sourceDepth) {
        ensureOpen();
        checkDepthExtent(sourceDepth);
        encoder.copyTextureToTexture(sourceDepth, noHandDepth, 0, 0, 0, 0, 0, width, height, "depth.depthtex2");
    }

    private void checkDepthExtent(final GpuTexture sourceDepth) {
        if (sourceDepth.getWidth(0) != width || sourceDepth.getHeight(0) != height) {
            throw new IllegalArgumentException(
                    "Scene depth extent " + sourceDepth.getWidth(0) + "x" + sourceDepth.getHeight(0)
                            + " does not match Iris targets " + width + "x" + height
            );
        }
    }

    /**
     * Depth-attached passes (terrain/world gbuffer draws) cannot write a scaled
     * colortex target: Metal requires every attachment of a render pass to have
     * the same dimensions, while the depth attachment is always the base
     * extent.
     */
    private void requireBaseExtent(final String passKind, final String label, final int logicalTarget) {
        int targetWidth = colorTargets.width(logicalTarget);
        int targetHeight = colorTargets.height(logicalTarget);
        if (targetWidth != width || targetHeight != height) {
            throw new IllegalStateException(
                    "Iris " + passKind + " pass '" + label + "' writes scaled colortex" + logicalTarget
                            + " (" + targetWidth + "x" + targetHeight + ") but attaches scene depth ("
                            + width + "x" + height + "); depth-attached passes must use base-size draw buffers"
            );
        }
    }

    RenderPassDescriptorWithViews createWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            @Nullable final Vector4fc[] clearColors,
            final boolean withDepth,
            @Nullable final Double clearDepth,
            final int @Nullable [] readTargets
    ) {
        ensureOpen();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("A pass must write at least one draw buffer");
        }
        if (clearColors != null && clearColors.length != drawBuffers.length) {
            throw new IllegalArgumentException("Clear color array must match draw buffer count");
        }
        if (readTargets != null) {
            colorTargets.checkNoFeedbackLoop(drawBuffers, readTargets);
        }
        int targetWidth = targetWidth(drawBuffers[0]);
        int targetHeight = targetHeight(drawBuffers[0]);
        for (int slot = 1; slot < drawBuffers.length; slot++) {
            int width = targetWidth(drawBuffers[slot]);
            int height = targetHeight(drawBuffers[slot]);
            if (width != targetWidth || height != targetHeight) {
                // Upstream CompositeRenderer.recalculateSizes rejects this too.
                throw new IllegalStateException(
                        "Iris pass '" + label + "' draw buffers must all have the same size: colortex"
                                + drawBuffers[0] + " " + targetWidth + "x" + targetHeight
                                + " vs colortex" + drawBuffers[slot] + " " + width + "x" + height
                );
            }
        }
        if (withDepth && (targetWidth != width || targetHeight != height)) {
            throw new IllegalStateException(
                    "Iris pass '" + label + "' attaches scene depth (" + width + "x" + height
                            + ") but writes scaled draw buffer(s) at " + targetWidth + "x" + targetHeight
                            + "; depth-attached passes must use base-size draw buffers"
            );
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        MetalGpuTextureView[] views = new MetalGpuTextureView[drawBuffers.length + (withDepth ? 1 : 0)];
        AttachmentViewOwnership owned = new AttachmentViewOwnership();
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            MetalGpuTexture writeTexture = colorTargets.writeTexture(drawBuffers[slot]);
            MetalGpuTextureView fullView = colorTargets.writeView(drawBuffers[slot]);
            // Full-range attachment view by default (see MetalRenderPolicy):
            // the cached view keeps one native handle across passes, so the
            // encoder is not reopened; rendering still only writes level 0.
            MetalGpuTextureView view = attachmentView(fullView, writeTexture);
            owned.add(fullView, view);
            views[slot] = view;
            descriptor.withColorAttachment(
                    view,
                    clearColors == null || clearColors[slot] == null
                            ? Optional.empty()
                            : Optional.of(clearColors[slot])
            );
        }
        if (withDepth) {
            MetalGpuTextureView fullDepthView = mainDepthView();
            MetalGpuTextureView depthView = attachmentView(fullDepthView, mainDepth);
            owned.add(fullDepthView, depthView);
            views[drawBuffers.length] = depthView;
            descriptor.withDepthAttachment(
                    depthView,
                    clearDepth == null ? OptionalDouble.empty() : OptionalDouble.of(clearDepth)
            );
        }
        // The viewport of the pass is derived from the first attachment, so the
        // render area must be the common draw-buffer extent (upstream
        // CompositeRenderer uses pass.viewWidth/viewHeight the same way).
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, targetWidth, targetHeight));
        // Closing the descriptor must only release the views this call created;
        // the generation-owned full-range views stay open.
        return new RenderPassDescriptorWithViews(descriptor, views, owned.toArray());
    }

    RenderPassDescriptor createTerrainWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            final GpuTextureView mainColor,
            @Nullable final Vector4fc mainClearColor,
            @Nullable final GpuTextureView sceneDepth,
            @Nullable final Double clearDepth
    ) {
        ensureOpen();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("A gbuffer pass must write at least one draw buffer");
        }
        if (mainColor.getWidth(0) != width || mainColor.getHeight(0) != height) {
            throw new IllegalArgumentException(
                    "Scene color extent " + mainColor.getWidth(0) + "x" + mainColor.getHeight(0)
                            + " does not match Iris targets " + width + "x" + height
            );
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        boolean[] written = new boolean[colorTargets.targetCount()];
        for (int logicalTarget : drawBuffers) {
            if (logicalTarget < 0 || logicalTarget >= colorTargets.targetCount()) {
                throw new IllegalArgumentException("Terrain DRAWBUFFERS target out of range: " + logicalTarget);
            }
            if (written[logicalTarget]) {
                throw new IllegalArgumentException("Terrain DRAWBUFFERS repeats logical target " + logicalTarget);
            }
            written[logicalTarget] = true;
            requireBaseExtent("Terrain", label, logicalTarget);
            Optional<Vector4fc> clear = logicalTarget == 0 && mainClearColor != null
                    ? Optional.of(mainClearColor)
                    : Optional.empty();
            descriptor.withColorAttachment(colorTargets.readView(logicalTarget), clear);
        }
        if (sceneDepth != null) {
            descriptor.withDepthAttachment(
                    sceneDepth,
                    clearDepth == null ? OptionalDouble.empty() : OptionalDouble.of(clearDepth)
            );
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        return descriptor;
    }

    /**
     * Builds the rewritten attachment set for a taken-over world draw. When
     * {@code depthView} is present (M2 vanilla-depth mode) the pass tests and
     * writes the vanilla scene depth, so later translucent terrain passes
     * observe entity/hand depth; {@code null} falls back to the Iris
     * depthtex0 attachment (legacy behavior).
     */
    RenderPassDescriptor createWorldWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            final RenderPass.@Nullable RenderArea renderArea,
            final @Nullable GpuTextureView depthView,
            final OptionalDouble depthClear
    ) {
        ensureOpen();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("A world gbuffer pass must write at least one draw buffer");
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        boolean[] written = new boolean[colorTargets.targetCount()];
        for (int logicalTarget : drawBuffers) {
            if (logicalTarget < 0 || logicalTarget >= colorTargets.targetCount()) {
                throw new IllegalArgumentException("World DRAWBUFFERS target out of range: " + logicalTarget);
            }
            if (written[logicalTarget]) {
                throw new IllegalArgumentException("World DRAWBUFFERS repeats logical target " + logicalTarget);
            }
            written[logicalTarget] = true;
            requireBaseExtent("World", label, logicalTarget);
            descriptor.withColorAttachment(colorTargets.readView(logicalTarget), Optional.empty());
        }
        if (depthView != null) {
            descriptor.withDepthAttachment(depthView, depthClear);
        } else {
            descriptor.withDepthAttachment(mainDepthView(), OptionalDouble.empty());
        }
        descriptor.withRenderArea(renderArea == null
                ? new RenderPass.RenderArea(0, 0, width, height)
                : renderArea);
        return descriptor;
    }

    void resize(final int newWidth, final int newHeight) {
        ensureOpen();
        if (newWidth == width && newHeight == height) {
            return;
        }
        // Re-derive every target extent from the new base size; depth stays base.
        IrisMetalRenderTargetExtents.Extents resized = IrisMetalRenderTargetExtents.from(
                directives, colorTargets.targetCount(), newWidth, newHeight
        );
        this.extents = resized;
        colorTargets.resize(resized.widths(), resized.heights());
        releaseDepthTextures();
        createDepthTextures(newWidth, newHeight);
        this.fullClearRequired = true;
    }

    private void releaseDepthTextures() {
        if (mainDepthView != null) {
            mainDepthView.close();
            mainDepthView = null;
        }
        if (noTranslucentsDepthView != null) {
            noTranslucentsDepthView.close();
            noTranslucentsDepthView = null;
        }
        if (noHandDepthView != null) {
            noHandDepthView.close();
            noHandDepthView = null;
        }
        if (mainDepth != null) {
            mainDepth.close();
            mainDepth = null;
        }
        if (noTranslucentsDepth != null) {
            noTranslucentsDepth.close();
            noTranslucentsDepth = null;
        }
        if (noHandDepth != null) {
            noHandDepth.close();
            noHandDepth = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Iris render targets are closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        colorTargets.close();
        releaseDepthTextures();
        colorSampler.close();
        nearestSampler.close();
        colorMipSampler.close();
        nearestMipSampler.close();
    }

    /**
     * Render-attachment view: the generation-owned full-range view when the
     * attachment-view switch is on (default), otherwise the legacy
     * level-0-only view, which the caller owns and must close (identity
     * comparison against {@code fullView} detects that case).
     *
     * <p>A full-range view makes {@code MetalGpuTextureView.nativeHandle()}
     * return the texture itself, so a mipmapped attachment keeps one handle
     * across passes and the encoder can stay open. Rendering still only writes
     * level 0 either way.</p>
     */
    static MetalGpuTextureView attachmentView(
            final MetalGpuTextureView fullView,
            final @Nullable MetalGpuTexture texture
    ) {
        if (texture == null) {
            return fullView;
        }
        MetalRenderPolicy.AttachmentViewRange range = MetalRenderPolicy.attachmentViewRange(
                MetalDebugSwitches.ATTACHMENT_VIEWS_FULL, texture.getMipLevels()
        );
        return MetalRenderPolicy.usesTextureHandle(range, texture.getMipLevels())
                ? fullView
                : new MetalGpuTextureView(texture, range.baseMipLevel(), range.mipLevels());
    }

    /**
     * Collects the attachment views one descriptor builder created, so the
     * descriptor closes exactly those. The default full-range path creates
     * none and leaves the list unallocated.
     */
    static final class AttachmentViewOwnership {
        private static final MetalGpuTextureView[] NONE = new MetalGpuTextureView[0];
        private List<MetalGpuTextureView> created;

        void add(final MetalGpuTextureView fullView, final MetalGpuTextureView attachmentView) {
            if (attachmentView == fullView) {
                return;
            }
            if (created == null) {
                created = new ArrayList<>(2);
            }
            created.add(attachmentView);
        }

        MetalGpuTextureView[] toArray() {
            return created == null ? NONE : created.toArray(new MetalGpuTextureView[0]);
        }
    }

    /**
     * A descriptor plus the attachment views it was built from.
     *
     * <p>{@code ownedViews} are the views this record created and must close
     * (the legacy subresource path); views borrowed from a generation-owned
     * target are only listed in {@code views} and stay open. Callers that build
     * a descriptor from engine-provided views use the two-argument form, which
     * owns {@code views}.</p>
     */
    record RenderPassDescriptorWithViews(
            RenderPassDescriptor descriptor,
            MetalGpuTextureView[] views,
            MetalGpuTextureView[] ownedViews
    ) implements AutoCloseable {
        RenderPassDescriptorWithViews(final RenderPassDescriptor descriptor, final MetalGpuTextureView[] views) {
            this(descriptor, views, views);
        }

        @Override
        public void close() {
            for (MetalGpuTextureView view : ownedViews) {
                if (view != null) {
                    view.close();
                }
            }
        }
    }
}
