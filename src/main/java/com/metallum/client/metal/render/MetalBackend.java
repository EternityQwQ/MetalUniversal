package com.metallum.client.metal.render;

import com.metallum.Metallum;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.shaders.ShaderSource;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFWNativeCocoa;

import java.lang.foreign.MemorySegment;

/**
 * 1.21.11 无 GpuBackend 体系（26.2 的多后端抽象）：GpuDevice 是接口，渲染设备由
 * RenderSystem 持有。本工厂在 mixin 注入的 device 创建点被调用，产出 MetalDevice。
 */
@Environment(EnvType.CLIENT)
public final class MetalBackend {
    private MetalBackend() {
    }

    /**
     * Metal 主机判定：macOS / iOS（完整判定链，含 JVM 谎报 os.name 的 iOS 沙箱信号）。
     * 供 RenderSystemDeviceMixin 决定是否接管 device 创建。
     */
    public static boolean isMetalHost() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(java.util.Locale.ROOT).contains("mac")
                || osName.toLowerCase(java.util.Locale.ROOT).contains("ios")
                || MetalNativeBridge.isIOS();
    }

    @NonNull
    public static MetalDevice createDevice(final long window, @Nullable final ShaderSource defaultShaderSource) {
        MetalNativeBridge.ensureSpvcLibraryConfigured();

        MemorySegment deviceHandle;
        MemorySegment cocoaWindow;
        MemorySegment cocoaView;
        MemorySegment metalLayer;
        String deviceName;
        deviceHandle = MetalNativeBridge.metallum_create_system_default_device();
        if (MetalNativeBridge.isNullHandle(deviceHandle)) {
            throw new IllegalStateException("MTLCreateSystemDefaultDevice returned null");
        }

        deviceName = MetalNativeBridge.metallum_copy_device_name(deviceHandle);
        if (deviceName.isBlank()) deviceName = "<unknown Metal device>";

        double scale;
        if (MetalNativeBridge.isIOS()) {
            // iOS: GLFW does not expose Cocoa window handles. The host launcher
            // (e.g. PojavLauncher) owns the UIWindow/UIView and publishes the
            // view pointer (and optionally the backing scale) via system
            // properties so we can attach a CAMetalLayer to it.
            cocoaWindow = MemorySegment.NULL;
            cocoaView = readIOSSurfacePointer();
            scale = readIOSScreenScale();
        } else {
            cocoaWindow = MemorySegment.ofAddress(GLFWNativeCocoa.glfwGetCocoaWindow(window));
            if (MetalNativeBridge.isNullHandle(cocoaWindow)) {
                throw new IllegalStateException("glfwGetCocoaWindow returned null");
            }

            cocoaView = MetalNativeBridge.metallum_NSWindow_contentView(cocoaWindow);
            if (MetalNativeBridge.isNullHandle(cocoaView)) {
                throw new IllegalStateException("glfwGetCocoaView returned null");
            }

            scale = MetalNativeBridge.metallum_NSWindow_backingScaleFactor(cocoaWindow);
        }
        if (scale <= 0.0) scale = 1.0;

        if (MetalNativeBridge.isIOS()) {
            // iOS: GameSurfaceView already overrides +layerClass to return
            // CAMetalLayer.class, so cocoaView.layer IS a CAMetalLayer. Use it
            // directly as the render target — this matches what Amethyst's own
            // Vulkan path does in pojavCreateContext (Natives/egl_bridge.m).
            metalLayer = MetalNativeBridge.metallum_ios_get_view_metal_layer(cocoaView, deviceHandle, scale);
            if (MetalNativeBridge.isNullHandle(metalLayer)) {
                throw new IllegalStateException("metallum_ios_get_view_metal_layer returned null");
            }
        } else {
            metalLayer = MetalNativeBridge.metallum_create_metal_layer(deviceHandle, scale);
            if (MetalNativeBridge.isNullHandle(metalLayer)) {
                throw new IllegalStateException("Failed to create CAMetalLayer");
            }

            MetalNativeBridge.metallum_NSView_setMetalLayer(cocoaView, metalLayer);
        }

        Metallum.LOGGER.info("Metal device: {}", deviceName);

        try {
            return new MetalDevice(deviceHandle, metalLayer, deviceName, cocoaView, defaultShaderSource);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Metal device initialization failed: " + throwable.getMessage(), throwable);
        }
    }

    /**
     * Reads the host-provided {@code UIView} pointer on iOS. The host launcher
     * (PojavLauncher) owns the {@code UIView} that backs the game surface and
     * exposes its address via a system property so the mod can attach a
     * {@code CAMetalLayer} to it.
     *
     * <p>Recognised properties (in order of preference):
     * <ul>
     *   <li>{@code metallum.ios.view.pointer} – hex address of the UIView</li>
     *   <li>{@code pojav.view.pointer} – legacy PojavLauncher property</li>
     * </ul>
     */
    private static MemorySegment readIOSSurfacePointer() {
        String raw = System.getProperty("metallum.ios.view.pointer");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("pojav.view.pointer");
        }
        if (raw == null || raw.isBlank()) {
            // Amethyst-iOS does not publish the UIView pointer as a system
            // property. Resolve it directly via the ObjC runtime instead:
            // metallum_ios_find_surface_view calls +[SurfaceViewController surface]
            // (with a key-window view-hierarchy fallback) to locate the host
            // launcher's GameSurfaceView.
            MemorySegment nativeView = MetalNativeBridge.metallum_ios_find_surface_view();
            if (!MetalNativeBridge.isNullHandle(nativeView)) {
                return nativeView;
            }
            throw new IllegalStateException(
                    "Could not locate the iOS surface view. Neither the "
                            + "'metallum.ios.view.pointer'/'pojav.view.pointer' system property "
                            + "nor the +[SurfaceViewController surface] class method returned a UIView. "
                            + "If you are using a launcher other than Amethyst/PojavLauncher, set "
                            + "'-Dmetallum.ios.view.pointer=<hex>' to the UIView address."
            );
        }
        raw = raw.trim();
        String hex = raw.startsWith("0x") || raw.startsWith("0X") ? raw.substring(2) : raw;
        long address;
        try {
            address = Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid UIView pointer '" + raw + "': expected a hex address");
        }
        MemorySegment view = MemorySegment.ofAddress(address);
        if (MetalNativeBridge.isNullHandle(view)) {
            throw new IllegalStateException("Host-provided UIView pointer is null");
        }
        return view;
    }

    /**
     * Reads the backing scale factor on iOS. Defaults to {@code 2.0} (typical
     * Retina scale) if the host does not publish one.
     */
    private static double readIOSScreenScale() {
        String raw = System.getProperty("metallum.ios.screen.scale");
        if (raw == null || raw.isBlank()) {
            return 2.0;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 2.0;
        }
    }
}
