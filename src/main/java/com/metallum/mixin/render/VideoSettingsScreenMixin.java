package com.metallum.mixin.render;

import com.metallum.client.metal.fx.MetalFxConfig;
import com.metallum.client.metal.fx.MetalFxWarningScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "MetalFX Settings..." button into the vanilla
 * {@link VideoSettingsScreen}.
 *
 * <p><b>Injection point: {@code addOptions} TAIL.</b>
 * In Minecraft 26.2, {@code VideoSettingsScreen} no longer overrides
 * {@code init} or {@code rebuildWidgets} — both live only on the
 * {@code Screen}/{@code OptionsSubScreen} base classes. Mixin can only
 * inject into methods declared by the target class itself, so
 * {@code @Inject(method = "init")} on {@code @Mixin(VideoSettingsScreen.class)}
 * fails at runtime. {@code addOptions()} is the method declared on
 * {@code VideoSettingsScreen} itself (inherited contract from
 * {@code OptionsSubScreen}), so it is the reliable injection target.
 *
 * <p><b>Button placement: top-right corner (y=6).</b>
 * The button is placed at {@code x = width - 158, y = 6}, which is above
 * the title row (y=16) and well clear of the {@code HeaderAndFooterLayout}'s
 * footer background panel (bottom ~36-66px). This avoids the footer
 * overlap that previously made the button invisible when it was placed
 * at the bottom-right.
 *
 * <p>The button is only added when the active GPU backend is Metal — on
 * OpenGL/Vulkan it would be misleading to show MetalFX controls.
 *
 * <p>Clicking the button routes through
 * {@link MetalFxWarningScreen#openIfNotAcknowledged(Screen)}: the first
 * time the user opens MetalFX settings they see a warning dialog with
 * the official Apple MetalFX system/chip requirements and explicit
 * Enable / Do Not Enable choices. On subsequent opens the warning is
 * skipped and the options screen is shown directly.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends Screen {
    protected VideoSettingsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void metallum$addMetalFxButton(CallbackInfo ci) {
        if (!metallum$isMetalBackend()) {
            return;
        }
        // Force a capability query in case the user opened the screen before
        // the first frame was rendered. Safe to call repeatedly — it caches.
        MetalFxConfig.reload();

        int buttonWidth = 150;
        int buttonHeight = 20;
        // Top-right corner: above the title row and clear of the
        // HeaderAndFooterLayout's footer background panel.
        int x = this.width - buttonWidth - 8;
        int y = 6;

        this.addRenderableWidget(Button.builder(
                Component.translatable("metallum.fx.button.open"),
                button -> MetalFxWarningScreen.openIfNotAcknowledged((Screen) (Object) this)
        ).bounds(x, y, buttonWidth, buttonHeight).build());
    }

    private boolean metallum$isMetalBackend() {
        try {
            var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
            if (device == null) {
                return false;
            }
            return "Metal".equals(device.getDeviceInfo().backendName());
        } catch (Throwable t) {
            // Backend not initialised yet — err on the side of showing the
            // button; the options screen handles unsupported devices.
            return false;
        }
    }
}
