package com.metallum.client.metal.fx;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ExternalPageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Sodium 0.9+ {@link ConfigEntryPoint} integration — the Iris-style
 * first-class integration with Sodium's video settings screen.
 *
 * <p>Replaces the legacy {@code SodiumVideoSettingsScreenMixin} button
 * injection. Instead of dropping a stray button into the top-left corner
 * of Sodium's UI, this entry point registers MetalUniversal as a mod with
 * options, and adds an {@link ExternalPageBuilder external page} that
 * Sodium renders as a tab in the left-side mod list of its video
 * settings screen (exactly where Iris surfaces its "Shader Packs" page).
 *
 * <p>When the user selects the MetalUniversal tab, Sodium invokes the
 * screen consumer registered via
 * {@link ExternalPageBuilder#setScreenConsumer}, passing the current
 * video-settings screen as the parent. The consumer routes through
 * {@link MetalFxWarningScreen#openIfNotAcknowledged}, which shows the
 * one-time adaptation warning on first open and the MetalFX options
 * screen directly on subsequent opens — preserving the {@code Done}
 * back-navigation chain back to Sodium's video settings.
 *
 * <p><b>Why an external page and not an option page.</b> MetalFX options
 * (spatial / temporal / interpolation modes) are persisted through
 * {@code MetalFxConfig} to {@code config/metallum_fx.properties}, not
 * through Sodium's own config store. An {@code OptionPageBuilder} would
 * require reimplementing every option as a Sodium
 * {@code StatefulOption} with a custom storage backend — a large
 * refactor for no user-visible benefit. An external page lets us keep
 * the existing {@link MetalFxOptionsScreen} UI and storage untouched
 * while still appearing as a native tab in Sodium's settings.
