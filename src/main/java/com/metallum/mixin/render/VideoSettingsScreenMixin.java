package com.metallum.mixin.render;

import com.metallum.client.metal.fx.MetalFxConfig;
import com.metallum.client.metal.fx.MetalFxWarningScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
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
 * <p><b>Why {@code OptionsSubScreen.init} RETURN and not
 * {@code VideoSettingsScreen.addOptions} TAIL.</b> The previous implementation
 * injected at {@code TAIL} of {@code VideoSettingsScreen.addOptions()}. In
 * Minecraft 26.2 the {@code OptionsSubScreen.init()} flow is:
 * <pre>
 *   init()
 *     ├─ create HeaderAndFooterLayout
 *     ├─ addOptions()          ← old injection point (TAIL)
 *     └─ layout.arrangeElements()  ← runs AFTER addOptions
 * </pre>
 * Adding the button in {@code addOptions} meant it was inserted into
 * {@code renderables} <em>before</em> {@code arrangeElements()} ran. The
 * subsequent layout pass and footer background-panel render could then push
 * the button into the footer region or overwrite its placement, producing
 * the reported symptoms: button vanishing, drifting to the bottom-right
 * corner, and flickering frame-to-frame.
 *
 * <p>Injecting at {@code RETURN} of {@code OptionsSubScreen.init()} runs
 * <em>after</em> {@code arrangeElements()} has completed, so the footer panel
 * bounds are final and {@code this.width}/{@code this.height} are correct for
 * the current init cycle. The button is placed in the top-right corner
 * (y=6), well above the footer background panel, and is never repositioned
 * by a later layout step.
 *
 * <p><b>Why the target is {@code OptionsSubScreen} and not
 * {@code VideoSettingsScreen}.</b> In 26.2 {@code VideoSettingsScreen} no
 * longer overrides {@code init} — it inherits it from
 * {@code OptionsSubScreen}. Mixin can only inject into methods declared by
 * the target class, so {@code @Mixin(VideoSettingsScreen.class)} with
 * {@code @Inject(method = "init")} fails at runtime with
 * "could not find any targets matching 'init'". Targeting
 * {@code OptionsSubScreen} (which does declare {@code init}) and filtering
 * with {@code instanceof VideoSettingsScreen} at runtime cleanly scopes the
 * button to the video-settings screen only.
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
 *
 * <p><b>No dedup check.</b> {@code Screen.rebuildWidgets()} — the only path
 * that re-invokes {@code init()} — always calls {@code clearWidgets()}
 * first, so {@code renderables} is guaranteed empty at {@code init} RETURN.
 * A defensive dedup pass would be dead code for a scenario that cannot
 * occur under the documented widget lifecycle.
 */
@Mixin(OptionsSubScreen.class)
public abstract class VideoSettingsScreenMixin extends Screen {
    protected VideoSettingsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void metallum$addMetalFxButton(CallbackInfo ci) {
        // Target is OptionsSubScreen (which declares init), but we only want
        // the button on the video-settings screen — filter at runtime.
        if (!((Object) this instanceof VideoSettingsScreen)) {
            return;
        }
        if (!metallum$isMetalBackend()) {
            return;
        }
        // Force a capability query in case the user opened the screen before
        // the first frame was rendered. Safe to call repeatedly — it caches.
        // We can't get the MetalDevice handle from here, so the query happens
        // lazily on the MetalDevice ctor; this just makes sure the config is
        // loaded so the options screen reflects persisted state.
        MetalFxConfig.reload();

        int buttonWidth = 150;
        int buttonHeight = 20;
        // Top-right corner: below the title (y=16) and above the options list.
        // This avoids the HeaderAndFooterLayout's footer background panel that
        // renders on top of renderables in the bottom ~36-66px.
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
