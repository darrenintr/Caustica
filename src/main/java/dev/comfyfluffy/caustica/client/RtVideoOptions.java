package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.EnumSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link CausticaConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dcaustica.*} startup surface. Upscaler quality is runtime-tunable because the selected
 * provider is resized or recreates its feature lazily when the quality preset changes.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /** Runtime-tunable RT options, in display order. Paired two-per-row by {@code OptionsList.addSmall}. */
    public static OptionInstance<?>[] runtimeOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
            spp(),
            maxBounces(),
            sunSize(),
            entities(),
            particles(),
            waterWaves(),
            upscalerMode(),
            denoiseMode(),
            upscalerQuality(),
            hdrEnabled(),
            hdrPaperWhite(),
            hdrPeak(),
            debugOverlay(),
            debugView(),
        };
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "caustica.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.value(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        // Stored in radians via the degrees->radians sanitizer; the slider works in tenths of a degree.
        FloatSetting setting = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "caustica.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> entities() {
        return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
    }

    private static OptionInstance<Integer> upscalerQuality() {
        IntSetting setting = CausticaConfig.Rt.Upscaler.QUALITY;
        return new OptionInstance<>(
            "caustica.options.rt.upscalerQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.upscalerQuality.tooltip")),
            (caption, quality) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.upscalerQuality." + quality)),
            new OptionInstance.IntRange(0, 4),
            Math.clamp(setting.value(), 0, 4),
            setting::set);
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        return bool("caustica.options.rt.hdr", CausticaConfig.Rt.Hdr.ENABLED);
    }

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.value()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> hdrPeak() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.value()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<String> upscalerMode() {
        EnumSetting<CausticaConfig.UpscalerMode> setting = CausticaConfig.Rt.Upscaler.MODE;
        // TAAU: pure compute fallback. FSR2: classic FidelityFX Super Resolution. Legacy vendor
        // mode keys still parse from caustica.toml but are not shown in the dropdown.
        List<String> values = List.of("auto", "taau", "fsr2", "off");
        return new OptionInstance<>(
            "caustica.options.rt.upscalerMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.upscalerMode.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.upscalerMode." + value),
            new OptionInstance.Enum<>(values, Codec.STRING),
            setting.value().key(),
            value -> {
                setting.set(CausticaConfig.UpscalerMode.fromKey(value));
                // Hot-reload: clear the resolved-once latch so the next composite() call re-runs
                // UpscalerSelector.resolve() and the new backend takes effect on the next frame.
                dev.comfyfluffy.caustica.rt.RtComposite.INSTANCE.invalidateUpscalerSelection();
            });
    }

    private static OptionInstance<String> denoiseMode() {
        EnumSetting<CausticaConfig.DenoiserKind> setting = CausticaConfig.Rt.Denoise.MODE;
        // AMD vendor routes to NRD via AUTO; the legacy AMD_FIDELITYFX FFX-only mode
        // is gone (2.x modular loader has no denoiser provider — see commit log).
        // AUTO picks the right denoiser per vendor; NRD is the explicit
        // Radiance-style choice; HYBRID is FFX prepass + NRD.
        List<String> values = List.of("auto", "nrd", "hybrid", "ffx", "off");
        return new OptionInstance<>(
            "caustica.options.rt.denoiseMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.denoiseMode.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.denoiseMode." + value),
            new OptionInstance.Enum<>(values, Codec.STRING),
            setting.value().key(),
            value -> {
                CausticaConfig.DenoiserKind kind = CausticaConfig.DenoiserKind.fromKey(value);
                setting.set(kind);
                dev.comfyfluffy.caustica.rt.RtComposite.INSTANCE.invalidateDenoiseSelection();
            });
    }

    private static OptionInstance<Boolean> debugOverlay() {
        BooleanSetting setting = dev.comfyfluffy.caustica.CausticaConfig.Rt.DebugOverlay.ENABLED;
        return OptionInstance.createBoolean(
            "caustica.options.rt.debugOverlay",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugOverlay.tooltip")),
            setting.value(),
            setting::set);
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), Codec.INT),
            Math.clamp(setting.value(), 0, 10),
            setting::set);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }
}
