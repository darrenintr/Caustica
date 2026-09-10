package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Optional Sodium (Fabric performance mod) integration.
 *
 * <p>Sodium 0.6+ (the CaffeineMC rebrand — current 0.9.x uses the package
 * {@code net.caffeinemc.mods.sodium.*}) replaced the legacy
 * {@code me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages} injection point with a typed
 * config-builder API: an {@code ConfigEntryPoint} registers a {@code ConfigBuilder}, then
 * {@code ConfigManager.registerConfigsLate()} iterates registered entries and calls
 * {@code registerConfigLate(configBuilder)} on each. We register a proxy implementing that
 * interface at client-init time so the page appears the first time Sodium finalises the late
 * config (which happens at the end of a resource reload — guaranteed to be after our
 * {@code onInitializeClient}).
 *
 * <p>Sodium is deliberately <em>not</em> a compile dependency. Everything below happens through
 * reflection; every reflective step is fail-open (a Sodium version with a renamed class or method
 * causes us to log a warning and do nothing — never to crash the game).
 */
public final class SodiumOptionsCompat {
    private static final String OPT = "net.caffeinemc.mods.sodium.";

    private SodiumOptionsCompat() {
    }

    public static boolean isSodiumLoaded() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Register a Caustica {@code ConfigEntryPoint} with Sodium. Safe to call regardless of whether
     * Sodium is present; no-op if not. Called once from {@link CausticaClient#onInitializeClient()}.
     * The actual page construction happens later, inside the entry's
     * {@code registerConfigLate(ConfigBuilder)} callback, which Sodium invokes when it processes
     * late registrations (after the first resource reload).
     */
    public static void tryRegisterEntry() {
        if (!isSodiumLoaded()) {
            return;
        }
        try {
            Class<?> entryPointIface = Class.forName(OPT + "api.config.ConfigEntryPoint", false,
                    SodiumOptionsCompat.class.getClassLoader());
            Class<?> configManager = Class.forName(OPT + "client.config.ConfigManager", false,
                    SodiumOptionsCompat.class.getClassLoader());
            InvocationHandler handler = (proxy, method, args) -> {
                if ("registerConfigLate".equals(method.getName()) && args != null && args.length == 1) {
                    buildCausticaPage(args[0]);
                }
                // registerConfigEarly, hashCode, toString, equals all no-op / return defaults
                return null;
            };
            Object proxy = Proxy.newProxyInstance(entryPointIface.getClassLoader(),
                    new Class<?>[] { entryPointIface }, handler);
            // The Supplier's type is erased at runtime; reflection passes it as a raw Supplier.
            Supplier<Object> supplier = () -> proxy;
            Method register = configManager.getMethod("registerConfigEntryPoint", Supplier.class, String.class);
            register.invoke(null, supplier, "caustica");
            CausticaMod.LOGGER.info("Sodium compat: registered Caustica config entry (CaffeineMC API)");
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("Sodium compat: failed to register config entry: {}", t.toString());
        }
    }

    // -----------------------------------------------------------------
    // page construction (called from inside the ConfigEntryPoint callback)
    // -----------------------------------------------------------------

    /**
     * Build the entire Caustica "Ray Tracing" page inside the supplied Sodium
     * {@code ConfigBuilder}. Mirrors {@link RtVideoOptions#runtimeOptions()} 1:1 — same set of
     * settings, same display groupings — so vanilla and Sodium players see the same surface.
     */
    private static void buildCausticaPage(Object configBuilder) {
        try {
            Object modOptionsBuilder = invoke3(configBuilder, "registerModOptions",
                    String.class, "caustica",
                    String.class, "Caustica",
                    String.class, currentModVersion());
            invoke1(modOptionsBuilder, "setName", String.class, "Caustica");

            Object pageBuilder = invoke0(configBuilder, "createOptionPage");
            invoke1(pageBuilder, "setName", Component.class,
                    Component.translatable("caustica.options.rt.header"));

            Class<?> optionGroupBuilderIface = classByName(OPT + "api.config.structure.OptionGroupBuilder");
            Class<?> optionBuilderIface = classByName(OPT + "api.config.structure.OptionBuilder");
            Class<?> pageBuilderIface = classByName(OPT + "api.config.structure.PageBuilder");

            // Rendering group: exposure + trace knobs + scene composition.
            Object renderingGroup = invoke0(configBuilder, "createOptionGroup");
            invoke1(renderingGroup, "setName", Component.class, Component.literal("Rendering"));
            addOption(renderingGroup, optionBuilderIface, () -> cycleStringOption(configBuilder,
                    "caustica:rt/exposure_mode",
                    "caustica.options.rt.exposureMode", "caustica.options.rt.exposureMode.",
                    CausticaConfig.Rt.Exposure.MODE,
                    List.of("auto", "manual")));
            addOption(renderingGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/manual_ev", "caustica.options.rt.manualEv",
                    () -> Math.round(CausticaConfig.Rt.Exposure.MANUAL_EV.value() * 10.0f),
                    v -> CausticaConfig.Rt.Exposure.MANUAL_EV.set(v.floatValue() / 10.0f),
                    -50, 50, 1,
                    v -> Component.literal(String.format(Locale.ROOT, "%+.1f EV", v / 10.0))));
            addOption(renderingGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/spp", "caustica.options.rt.spp",
                    CausticaConfig.Rt.Composite.SPP));
            addOption(renderingGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/max_bounces", "caustica.options.rt.maxBounces",
                    CausticaConfig.Rt.Composite.MAX_BOUNCES));
            addOption(renderingGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/sun_size", "caustica.options.rt.sunSize",
                    () -> Math.clamp(Math.round((float) Math.toDegrees(
                            CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.value()) * 10.0f), 1, 50),
                    v -> CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.set(v.floatValue() / 10.0f),
                    1, 50, 1,
                    v -> Component.literal(String.format(Locale.ROOT, "%.1f\u00b0", v / 10.0))));
            addOption(renderingGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/entities", "caustica.options.rt.entities",
                    CausticaConfig.Rt.Entities.ENABLED));
            addOption(renderingGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/particles", "caustica.options.rt.particles",
                    CausticaConfig.Rt.Entities.PARTICLES_ENABLED));
            addOption(renderingGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/water_waves", "caustica.options.rt.waterWaves",
                    CausticaConfig.Rt.Composite.WATER_WAVES));
            addOption(renderingGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/hybrid_enabled", "caustica.options.rt.hybridEnabled",
                    CausticaConfig.Rt.Hybrid.ENABLED));
            invoke1(pageBuilder, "addOptionGroup", optionGroupBuilderIface, renderingGroup);

            // ReSTIR group: global illumination + the ReSTIR PT Enhanced sub-flags.
            Object restirGroup = invoke0(configBuilder, "createOptionGroup");
            invoke1(restirGroup, "setName", Component.class, Component.literal("ReSTIR"));
            addOption(restirGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/gi_enabled", "caustica.options.rt.giEnabled",
                    CausticaConfig.Rt.Gi.ENABLED));
            addOption(restirGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/gi_max_m_temporal", "caustica.options.rt.giMaxMTemporal",
                    () -> Math.round(CausticaConfig.Rt.Gi.MAX_M_TEMPORAL.value()),
                    v -> CausticaConfig.Rt.Gi.MAX_M_TEMPORAL.set(v.floatValue()),
                    1, 256, 1, v -> Component.literal(String.valueOf(v))));
            addOption(restirGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/gi_max_m_spatial", "caustica.options.rt.giMaxMSpatial",
                    () -> Math.round(CausticaConfig.Rt.Gi.MAX_M_SPATIAL.value()),
                    v -> CausticaConfig.Rt.Gi.MAX_M_SPATIAL.set(v.floatValue()),
                    1, 256, 1, v -> Component.literal(String.valueOf(v))));
            addOption(restirGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/paired_reuse", "caustica.options.rt.pairedReuseEnabled",
                    CausticaConfig.Rt.RestirEnhanced.PAIRED_REUSE_ENABLED));
            addOption(restirGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/paired_reuse_shuffle", "caustica.options.rt.pairedReuseShufflePeriod",
                    CausticaConfig.Rt.RestirEnhanced.PAIRED_REUSE_SHUFFLE_PERIOD));
            addOption(restirGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/duplication_map", "caustica.options.rt.duplicationMapEnabled",
                    CausticaConfig.Rt.RestirEnhanced.DUPLICATION_MAP_ENABLED));
            addOption(restirGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/footprint_reconnection", "caustica.options.rt.footprintReconnection",
                    CausticaConfig.Rt.RestirEnhanced.FOOTPRINT_RECONNECTION));
            addOption(restirGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/footprint_motion_cap", "caustica.options.rt.footprintMotionCapPx",
                    () -> Math.round(CausticaConfig.Rt.RestirEnhanced.FOOTPRINT_MOTION_CAP_PX.value()),
                    v -> CausticaConfig.Rt.RestirEnhanced.FOOTPRINT_MOTION_CAP_PX.set(v.floatValue()),
                    1, 200, 1, v -> Component.literal(v + " px")));
            invoke1(pageBuilder, "addOptionGroup", optionGroupBuilderIface, restirGroup);

            // Upscale + denoise group.
            Object upscaleGroup = invoke0(configBuilder, "createOptionGroup");
            invoke1(upscaleGroup, "setName", Component.class, Component.literal("Upscale & Denoise"));
            addOption(upscaleGroup, optionBuilderIface, () -> cycleEnumOption(configBuilder,
                    "caustica:rt/upscaler_mode",
                    "caustica.options.rt.upscalerMode", "caustica.options.rt.upscalerMode.",
                    CausticaConfig.Rt.Upscaler.MODE, List.of("auto", "taau", "fsr2", "off"),
                    CausticaConfig.UpscalerMode::fromKey));
            addOption(upscaleGroup, optionBuilderIface, () -> cycleEnumOption(configBuilder,
                    "caustica:rt/denoise_mode",
                    "caustica.options.rt.denoiseMode", "caustica.options.rt.denoiseMode.",
                    CausticaConfig.Rt.Denoise.MODE, List.of("auto", "nrd", "hybrid", "ffx", "off"),
                    CausticaConfig.DenoiserKind::fromKey));
            addOption(upscaleGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/upscaler_quality", "caustica.options.rt.upscalerQuality",
                    CausticaConfig.Rt.Upscaler.QUALITY::value,
                    CausticaConfig.Rt.Upscaler.QUALITY::set,
                    0, 4, 1,
                    v -> Component.translatable("caustica.options.rt.upscalerQuality." + v)));
            invoke1(pageBuilder, "addOptionGroup", optionGroupBuilderIface, upscaleGroup);

            // Display (HDR + DRS) + debug.
            Object displayGroup = invoke0(configBuilder, "createOptionGroup");
            invoke1(displayGroup, "setName", Component.class, Component.literal("Display & Debug"));
            addOption(displayGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/drs_enabled", "caustica.options.rt.drsEnabled",
                    CausticaConfig.Drs.ENABLED));
            addOption(displayGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/hdr", "caustica.options.rt.hdr",
                    CausticaConfig.Rt.Hdr.ENABLED));
            addOption(displayGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/hdr_paper_white", "caustica.options.rt.hdrPaperWhite",
                    () -> Math.round(CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS.value()),
                    v -> CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS.set(v.floatValue()),
                    80, 1000, 1, v -> Component.literal(v + " nits")));
            addOption(displayGroup, optionBuilderIface, () -> integerSliderOption(configBuilder,
                    "caustica:rt/hdr_peak", "caustica.options.rt.hdrPeak",
                    () -> Math.round(CausticaConfig.Rt.Hdr.PEAK_NITS.value()),
                    v -> CausticaConfig.Rt.Hdr.PEAK_NITS.set(v.floatValue()),
                    80, 10000, 1, v -> Component.literal(v + " nits")));
            addOption(displayGroup, optionBuilderIface, () -> booleanOption(configBuilder,
                    "caustica:rt/debug_overlay", "caustica.options.rt.debugOverlay",
                    CausticaConfig.Rt.DebugOverlay.ENABLED));
            addOption(displayGroup, optionBuilderIface, () -> cycleIntOption(configBuilder,
                    "caustica:rt/debug_view",
                    "caustica.options.rt.debugView", "caustica.options.rt.debugView.",
                    CausticaConfig.Rt.Composite.DEBUG_VIEW,
                    List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
            addOption(displayGroup, optionBuilderIface, () -> {
                // Probe flight recorder: Sodium's boolean option has no confirm
                // dialog, so enabling flips straight on — the tooltip carries
                // the disk/perf warning instead. The vanilla Video Settings
                // toggle keeps the two-step confirm gate.
                CausticaConfig.BooleanSetting setting = CausticaConfig.Rt.Probe.ENABLED;
                return booleanOption(configBuilder,
                        "caustica:rt/probe_record", "caustica.options.rt.probeRecord",
                        setting);
            });
            invoke1(pageBuilder, "addOptionGroup", optionGroupBuilderIface, displayGroup);

            // Finalise: pass the pageBuilder to addPage() — it finalises via .build() internally.
            invoke1(modOptionsBuilder, "addPage", pageBuilderIface, pageBuilder);
            CausticaMod.LOGGER.info("Sodium compat: built 'Ray Tracing' page (4 groups, 27 options)");
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("Sodium compat: failed to build 'Ray Tracing' page: {}", t.toString());
        }
    }

    // -----------------------------------------------------------------
    // typed option builders (each returns an OptionBuilder, NOT a built Option;
    // addOption() / addOptionGroup() / addPage() finalise via .build() internally)
    // -----------------------------------------------------------------

    private static Object booleanOption(Object configBuilder, String id, String key,
                                        CausticaConfig.BooleanSetting setting) {
        Object optionBuilder = invoke1(configBuilder, "createBooleanOption",
                Identifier.class, optionIdentifier(id));
        invoke1(optionBuilder, "setName", Component.class, Component.translatable(key));
        invoke1(optionBuilder, "setTooltip", Component.class, Component.translatable(key + ".tooltip"));
        invoke1(optionBuilder, "setDefaultValue", Boolean.class, setting.defaultValue());
        invoke2(optionBuilder, "setBinding",
                Consumer.class, (Consumer<Boolean>) setting::set,
                Supplier.class, (Supplier<Boolean>) setting::value);
        return optionBuilder;
    }

    /** {@link CausticaConfig.IntSetting} slider in [min, max] with step 1 and a plain integer label. */
    private static Object integerSliderOption(Object configBuilder, String id, String key,
                                              CausticaConfig.IntSetting setting) {
        return integerSliderOption(configBuilder, id, key,
                setting::value, setting::set, 1, 8, 1,
                v -> Component.literal(String.valueOf(v)));
    }

    private static Object integerSliderOption(Object configBuilder, String id, String key,
                                              Supplier<Integer> getter, Consumer<Integer> setter,
                                              int min, int max, int step,
                                              IntFunction<Component> formatter) {
        Object optionBuilder = invoke1(configBuilder, "createIntegerOption",
                Identifier.class, optionIdentifier(id));
        invoke1(optionBuilder, "setName", Component.class, Component.translatable(key));
        invoke1(optionBuilder, "setTooltip", Component.class, Component.translatable(key + ".tooltip"));
        invoke1(optionBuilder, "setDefaultValue", Integer.class, getter.get());
        invoke3(optionBuilder, "setRange", int.class, min, int.class, max, int.class, step);
        // ControlValueFormatter is a functional interface: Component format(int). Build a proxy.
        Class<?> fmtIface = classByName(OPT + "api.config.option.ControlValueFormatter");
        if (fmtIface != null) {
            Object fmtProxy = Proxy.newProxyInstance(fmtIface.getClassLoader(),
                    new Class<?>[] { fmtIface },
                    (proxy, method, args) -> method.getName().equals("format") && args.length == 1
                            ? formatter.apply((Integer) args[0])
                            : null);
            invoke1(optionBuilder, "setValueFormatter", fmtIface, fmtProxy);
        }
        invoke2(optionBuilder, "setBinding", Consumer.class, setter, Supplier.class, getter);
        return optionBuilder;
    }

    private static Object cycleStringOption(Object configBuilder, String id, String key,
                                            String valueKeyPrefix,
                                            CausticaConfig.StringSetting setting, List<String> allowedValues) {
        return cycleStringOption(configBuilder, id, key, valueKeyPrefix,
                () -> allowedValues.contains(setting.get()) ? setting.get() : allowedValues.get(0),
                setting::set, allowedValues);
    }

    private static Object cycleStringOption(Object configBuilder, String id, String key,
                                            String valueKeyPrefix,
                                            Supplier<String> getter, Consumer<String> setter,
                                            List<String> allowedValues) {
        Object optionBuilder = invoke2(configBuilder, "createEnumOption",
                Identifier.class, optionIdentifier(id),
                Class.class, String.class);
        invoke1(optionBuilder, "setName", Component.class, Component.translatable(key));
        invoke1(optionBuilder, "setTooltip", Component.class, Component.translatable(key + ".tooltip"));
        invoke1(optionBuilder, "setDefaultValue", String.class, allowedValues.get(0));
        // EnumSet only accepts enum constants; build a regular insertion-ordered Set.
        Set<String> allowedSet = new LinkedHashSet<>(allowedValues);
        invoke1(optionBuilder, "setAllowedValues", Set.class, allowedSet);
        Function<String, Component> nameFn = v -> Component.translatable(valueKeyPrefix + v);
        // setElementNameProvider(Function<E, Component>) is erased to Function<Object, Component>.
        Object nameProxy = Proxy.newProxyInstance(
                Function.class.getClassLoader(),
                new Class<?>[] { Function.class },
                (proxy, method, args) -> method.getName().equals("apply") && args != null && args.length == 1
                        ? nameFn.apply((String) args[0])
                        : null);
        invoke1(optionBuilder, "setElementNameProvider", Function.class, nameProxy);
        invoke2(optionBuilder, "setBinding", Consumer.class, setter, Supplier.class, getter);
        return optionBuilder;
    }

    private static <T extends Enum<T>> Object cycleEnumOption(Object configBuilder, String id, String key,
                                                              String valueKeyPrefix,
                                                              CausticaConfig.EnumSetting<T> setting,
                                                              List<String> allowedValues,
                                                              Function<String, T> fromKey) {
        Supplier<String> getter = () -> {
            String current = setting.valueKey();
            return allowedValues.contains(current) ? current : allowedValues.get(0);
        };
        Consumer<String> setter = value -> {
            T parsed = fromKey.apply(value);
            if (parsed != null) {
                setting.set(parsed);
            }
        };
        return cycleStringOption(configBuilder, id, key, valueKeyPrefix, getter, setter, allowedValues);
    }

    /**
     * Integer-backed cycle option. Strings cycle through the displayed translations but the binding
     * stores an {@code Integer} in the underlying {@link CausticaConfig.IntSetting}.
     */
    private static Object cycleIntOption(Object configBuilder, String id, String key, String valueKeyPrefix,
                                         CausticaConfig.IntSetting setting, List<Integer> values) {
        Supplier<String> getter = () -> {
            int current = setting.value();
            return values.contains(current)
                    ? String.valueOf(current)
                    : String.valueOf(values.get(0));
        };
        Consumer<String> setter = value -> {
            try {
                int parsed = Integer.parseInt(value);
                if (values.contains(parsed)) {
                    setting.set(parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        };
        List<String> stringValues = values.stream().map(String::valueOf).toList();
        return cycleStringOption(configBuilder, id, key, valueKeyPrefix, getter, setter, stringValues);
    }

    // -----------------------------------------------------------------
    // plumbing
    // -----------------------------------------------------------------

    /**
     * Adds the supplied option (or option factory result) to the given group builder, swallowing
     * per-option failures so a single broken entry doesn't drop the entire page. Pass the option's
     * BUILDER (not the built option) — {@code addOption} finalises via {@code .build()}.
     */
    private static void addOption(Object groupBuilder, Class<?> optionBuilderIface,
                                  Supplier<Object> optionFactory) {
        try {
            Object option = optionFactory.get();
            if (option == null) {
                return;
            }
            invoke1(groupBuilder, "addOption", optionBuilderIface, option);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("Sodium compat: failed to add option: {}", t.toString());
        }
    }

    /** Parse {@code "caustica:rt/spp"} → {@code Identifier("caustica","rt/spp")}. */
    private static Identifier optionIdentifier(String id) {
        int colon = id.indexOf(':');
        String ns = colon >= 0 ? id.substring(0, colon) : "caustica";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return Identifier.fromNamespaceAndPath(ns, path);
    }

    /** Look up a Sodium class by FQN, returning {@code null} on absence (used to fail-open). */
    private static Class<?> classByName(String name) {
        try {
            return Class.forName(name, false, SodiumOptionsCompat.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reads our own mod version via Fabric Loader (the templated {@code ${version}} from
     * {@code processResources} is available at runtime through
     * {@link net.fabricmc.loader.api.ModContainer#getMetadata()}). Falls back to a literal so the
     * Sodium page never crashes if the loader returns nothing.
     */
    private static String currentModVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("caustica")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("0.0.0");
        } catch (Throwable t) {
            return "0.0.0";
        }
    }

    // -----------------------------------------------------------------
    // reflective invocation helpers (one per arity, so Java can resolve the call site)
    // -----------------------------------------------------------------

    private static Object invoke0(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0], new Object[0]);
    }

    private static Object invoke1(Object target, String methodName, Class<?> t1, Object a1) {
        return invoke(target, methodName, new Class<?>[] { t1 }, new Object[] { a1 });
    }

    private static Object invoke2(Object target, String methodName,
                                  Class<?> t1, Object a1, Class<?> t2, Object a2) {
        return invoke(target, methodName, new Class<?>[] { t1, t2 }, new Object[] { a1, a2 });
    }

    private static Object invoke3(Object target, String methodName,
                                  Class<?> t1, Object a1, Class<?> t2, Object a2,
                                  Class<?> t3, Object a3) {
        return invoke(target, methodName, new Class<?>[] { t1, t2, t3 }, new Object[] { a1, a2, a3 });
    }

    private static Object invoke(Object target, String methodName, Class<?>[] paramTypes, Object[] args) {
        try {
            Method m = target.getClass().getMethod(methodName, paramTypes);
            return m.invoke(target, args);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("Sodium compat: invoke {}#{} failed: {}",
                    target.getClass().getName(), methodName, t.toString());
            return null;
        }
    }
}