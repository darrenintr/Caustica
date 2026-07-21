package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.comfyfluffy.caustica.upscale.UpscalerSelector;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central mutable runtime configuration. Each setting resolves its value, in order of precedence, from a
 * {@code -Dcaustica.*} system property, then the {@code config/caustica.toml} file, then a hardcoded
 * default. The settings UI and any other code call the same {@code set(...)} methods, and {@link #save()}
 * writes the current values back to the TOML file.
 *
 * <p>The system property namespace ({@code caustica.rt.foo}) and the TOML layout are independent: the file
 * uses real nested tables (e.g. {@code [omm]} with a {@code subdivision} key) grouped for readability, while
 * the property namespace stays flat and dotted for convenient one-off {@code -D} overrides.
 */
public final class CausticaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final List<RuntimeSetting<?>> SETTINGS = new CopyOnWriteArrayList<>();

    private static final Path CONFIG_PATH = resolveConfigPath();
    private static final CommentedFileConfig FILE = loadFile(CONFIG_PATH);

    private CausticaConfig() {
    }

    public static List<RuntimeSetting<?>> settings() {
        return List.copyOf(SETTINGS);
    }

    public static Path configPath() {
        return CONFIG_PATH;
    }

    public static void reloadFromSystemProperties() {
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.reloadFromSystemProperties();
        }
    }

    /**
     * Forces every settings holder to class-initialize so all settings are registered (and have applied
     * their file values). Call before {@link #save()} to write a complete file, and once at startup so the
     * file round-trips the full surface even for settings the renderer has not touched yet.
     */
    public static void ensureRegistered() {
        @SuppressWarnings("unused")
        Object[] touch = {
            Rt.ENABLED, Rt.Composite.SPP, Rt.Composite.MAX_BOUNCES, Rt.Composite.MAX_RAY_DISTANCE, Rt.Composite.TEMPORAL_ACCUM, Rt.Composite.TEMPORAL_ALPHA, Rt.Composite.TEMPORAL_DISOCCLUSION, Rt.Terrain.ASYNC_DISPATCH_PER_TICK, Rt.Omm.ENABLED,
            Rt.Entities.ENABLED, Rt.Entities.GLOW_ENABLED, Rt.EntityTextures.MAX_TEXTURES, Rt.Denoise.MODE,
            Rt.Gi.ENABLED, Rt.Gi.CANDIDATES, Rt.Gi.MAX_M_TEMPORAL, Rt.Gi.MAX_M_SPATIAL, Rt.Gi.HEMI_SKY_SCALE, Rt.Gi.HEMI_GROUND_SCALE, Rt.Gi.LIGHTFIELD_BLEND,
            Rt.Hybrid.ENABLED, Rt.Hybrid.ROUGH_THRESHOLD, Rt.Hybrid.LIGHTFIELD_THRESHOLD,
            Rt.Reflex.ENABLED, Rt.Exposure.MODE, Rt.FrameStats.ENABLED, Rt.DebugOverlay.ENABLED,
            Rt.Hdr.ENABLED, Rt.Upscaler.MODE, Rt.Upscaler.QUALITY, Rt.Upscaler.SHARPEN, Rt.Upscaler.SHARPNESS,
            Rt.Fsr.PATH,
            Rt.DynamicLights.ENABLED, Rt.DynamicLights.HELD_ITEMS, Rt.DynamicLights.DROPPED_ITEMS,
        };
    }

    /** Writes the default config file if it does not exist yet. */
    public static void saveIfMissing() {
        ensureRegistered();
        if (FILE.valueMap().isEmpty()) {
            save();
        }
    }

    /** Serializes all registered settings to the TOML config file. */
    public static synchronized void save() {
        ensureRegistered();
        writeComments();
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.writeToFile(FILE);
        }
        FILE.save();
    }

    private static void writeComments() {
        FILE.setComment("enabled",
                " Caustica RT renderer configuration.\n"
                        + " A matching -Dcaustica.* system property overrides the value below.");
        FILE.setComment("terrain",
                " Wall-clock budget for one streaming pass (snapshot dispatch + upload drain). The per-frame\n"
                        + " slice scales with queue pressure from stream-budget-ms (near-idle) up to\n"
                        + " stream-budget-max-ms (big backlog: initial fill, F3+A, teleport, fast flight) so fill\n"
                        + " throughput recovers when it matters and the cost drops back once the queue clears.\n"
                        + " stream-fallback-budget-ms is the per-tick slice used only when no world frame is\n"
                        + " streaming (loading screens), where a long pass hitches nothing.");
        FILE.setComment("frame-generation",
                " DLSS Frame Generation. Default off; gated additionally by hardware/driver availability.\n"
                        + " multi-frame-count: frames generated per rendered frame (1 = 2x, 2 = 3x, ...), clamped\n"
                        + " at runtime to the driver's reported DLSSG.MultiFrameCountMax.");
        FILE.setComment("reflex",
                " NVIDIA Reflex (VK_NV_low_latency2). Default off; gated additionally by device support.\n"
                        + " minimum-interval-us: 0 = no framerate cap (Reflex just paces submission).");
        FILE.setComment("hdr",
                " HDR display output (ST.2084/PQ). When enabled the swapchain is created in PQ automatically\n"
                        + " (falls back to SDR if the surface doesn't advertise it). paper-white-nits / peak-nits\n"
                        + " drive the scene-HDR -> display mapping.");
        FILE.setComment("dynamic-lights",
                " Dynamic light sources from entities (held items, glowing mobs, projectiles). Updates every\n"
                        + " frame based on entity positions and states. held-items: light from items held by\n"
                        + " players/mobs; dropped-items: light from dropped item entities; entities: inherent\n"
                        + " entity glow (charged creepers, blazes, etc.). intensity-scale: global multiplier\n"
                        + " for dynamic light brightness (0.0 = off, 1.0 = normal, 2.0 = double).");
    }

    private static Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("caustica.toml");
        } catch (Throwable t) {
            return Path.of("config", "caustica.toml");
        }
    }

    private static CommentedFileConfig loadFile(Path path) {
        CommentedFileConfig config = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .preserveInsertionOrder()
                .sync()
                .build();
        try {
            config.load();
        } catch (Exception e) {
            LOGGER.warn("Failed to read Caustica config {}: {}", path, e.toString());
        }
        return config;
    }

    private static Boolean fileBoolean(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Boolean>get(tomlPath) : null;
    }

    private static Number fileNumber(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Number>get(tomlPath) : null;
    }

    private static String fileString(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<String>get(tomlPath) : null;
    }

    public interface RuntimeSetting<T> {
        /** The {@code -Dcaustica.*} system property name that overrides this setting. */
        String key();

        /** The dotted path of this setting inside the nested {@code config/caustica.toml} tables. */
        String tomlPath();

        T defaultValue();

        T get();

        void set(T value);

        void reloadFromSystemProperties();

        /** Writes this setting's current value into the given config at {@link #tomlPath()}. */
        void writeToFile(CommentedConfig config);
    }

    public static final class BooleanSetting implements RuntimeSetting<Boolean> {
        private final String key;
        private final String tomlPath;
        private final boolean defaultValue;
        private volatile boolean value;

        private BooleanSetting(String key, String tomlPath, boolean defaultValue) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = defaultValue;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Boolean defaultValue() {
            return defaultValue;
        }

        @Override
        public Boolean get() {
            return value;
        }

        public boolean value() {
            return value;
        }

        @Override
        public void set(Boolean value) {
            this.value = value != null ? value : defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            set(Boolean.parseBoolean(System.getProperty(key, Boolean.toString(defaultValue))));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private boolean resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return Boolean.parseBoolean(prop.trim());
            }
            Boolean fromFile = fileBoolean(tomlPath);
            return fromFile != null ? fromFile : defaultValue;
        }
    }

    public static final class IntSetting implements RuntimeSetting<Integer> {
        private final String key;
        private final String tomlPath;
        private final int defaultValue;
        private final IntUnaryOperator sanitize;
        private volatile int value;

        private IntSetting(String key, String tomlPath, int defaultValue, IntUnaryOperator sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.applyAsInt(defaultValue);
            this.sanitize = sanitize;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Integer defaultValue() {
            return defaultValue;
        }

        @Override
        public Integer get() {
            return value;
        }

        public int value() {
            return value;
        }

        @Override
        public void set(Integer value) {
            this.value = sanitize.applyAsInt(value != null ? value : defaultValue);
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = sanitize.applyAsInt(Integer.parseInt(prop.trim()));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private int resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return sanitize.applyAsInt(Integer.parseInt(prop.trim()));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            return fromFile != null ? sanitize.applyAsInt(fromFile.intValue()) : defaultValue;
        }
    }

    public static final class FloatSetting implements RuntimeSetting<Float> {
        private final String key;
        private final String tomlPath;
        private final float defaultValue;
        // Maps a raw external number (system property, file, or the constructor's raw default) into the
        // stored value domain, e.g. degrees -> radians.
        private final DoubleUnaryOperator inputTransform;
        // Inverse of inputTransform: maps the stored value domain back to the raw external domain (e.g.
        // radians -> degrees) for writeToFile, so a value round-trips through the file unchanged instead
        // of having inputTransform re-applied to an already-transformed number on the next load.
        private final DoubleUnaryOperator outputTransform;
        // Idempotent guard on a value-domain number (clamp / finite check); safe to apply to any source.
        private final DoubleUnaryOperator valueClamp;
        private volatile float value;

        private FloatSetting(String key, String tomlPath, float rawDefault, DoubleUnaryOperator inputTransform,
                             DoubleUnaryOperator outputTransform, DoubleUnaryOperator valueClamp) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.inputTransform = inputTransform;
            this.outputTransform = outputTransform;
            this.valueClamp = valueClamp;
            this.defaultValue = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(rawDefault));
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Float defaultValue() {
            return defaultValue;
        }

        @Override
        public Float get() {
            return value;
        }

        public float value() {
            return value;
        }

        @Override
        public void set(Float value) {
            if (value == null) {
                this.value = defaultValue;
            } else {
                this.value = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(value));
            }
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            // Round-trip through Float.toString() so the file gets the shortest decimal that reproduces
            // this float (e.g. "0.6"), not outputTransform's raw double with float's binary noise spelled
            // out to 17 digits (e.g. 0.6000000487130328).
            float raw = (float) outputTransform.applyAsDouble(value);
            config.set(tomlPath, Double.parseDouble(Float.toString(raw)));
        }

        private float resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            if (fromFile == null) {
                return defaultValue;
            }
            return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(fromFile.doubleValue()));
        }
    }

    public static final class StringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private final String defaultValue;
        private final UnaryOperator<String> sanitize;
        private volatile String value;

        private StringSetting(String key, String tomlPath, String defaultValue, UnaryOperator<String> sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.apply(defaultValue);
            this.sanitize = sanitize;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return defaultValue;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = sanitize.apply(value != null ? value : defaultValue);
        }

        @Override
        public void reloadFromSystemProperties() {
            set(System.getProperty(key, defaultValue));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return sanitize.apply(prop);
            }
            String fromFile = fileString(tomlPath);
            return sanitize.apply(fromFile != null ? fromFile : defaultValue);
        }
    }

    public static final class OptionalStringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private volatile String value;

        private OptionalStringSetting(String key, String tomlPath) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return null;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = value;
        }

        @Override
        public void reloadFromSystemProperties() {
            this.value = System.getProperty(key);
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            if (value != null) {
                config.set(tomlPath, value);
            } else {
                config.remove(tomlPath);
            }
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            return prop != null ? prop : fileString(tomlPath);
        }
    }

    /**
     * String-backed enum setting. Stores the enum's {@code name()} (stable across renames) so the TOML file
     * doesn't break if the enum's display label changes. The {@code key} mapping is separate — readers that
     * want the {@code key} style (e.g. {@code "fsr-4"} for {@link UpscalerMode#FSR_4}) call
     * {@link #valueKey()}.
     */
    public static final class EnumSetting<T extends Enum<T>> implements RuntimeSetting<T> {
        private final String key;
        private final String tomlPath;
        private final T defaultValue;
        private final Class<T> enumClass;
        private final java.util.function.Function<String, T> fromKey;
        private volatile T value;

        private EnumSetting(String key, String tomlPath, T defaultValue, Class<T> enumClass,
                            java.util.function.Function<String, T> fromKey) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = defaultValue;
            this.enumClass = enumClass;
            this.fromKey = fromKey;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() { return key; }
        @Override
        public String tomlPath() { return tomlPath; }
        @Override
        public T defaultValue() { return defaultValue; }
        @Override
        public T get() { return value; }
        public T value() { return value; }
        /** Returns the enum's {@code key()} (e.g. {@code "fsr-4"}), or {@code null} for the default
         *  sentinel. Used by UIs that want the short, kebab-case form. */
        public String valueKey() {
            try {
                return (String) enumClass.getMethod("key").invoke(value);
            } catch (Throwable t) {
                return value.name();
            }
        }
        /** Returns the current value as the matching {@code UpscalerSelector.Mode} when applicable, or
         *  {@code AUTO} as a safe default. Used by the selector to read the requested mode. */
        public UpscalerSelector.Mode valueEnum() {
            if (enumClass == UpscalerMode.class) {
                UpscalerMode m = (UpscalerMode) value;
                return switch (m) {
                    case OFF -> UpscalerSelector.Mode.OFF;
                    case AUTO -> UpscalerSelector.Mode.AUTO;
                    case TAAU -> UpscalerSelector.Mode.TAAU;
                    // Classic FSR2 reports as FSR_3 in the selector (same quality path key in overlay).
                    case FSR2 -> UpscalerSelector.Mode.FSR_3;
                };
            }
            return UpscalerSelector.Mode.AUTO;
        }

        @Override
        public void set(T value) {
            this.value = value != null ? value : defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            set(fromKey.apply(System.getProperty(key)));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value.name());
        }

        private T resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                T parsed = fromKey.apply(prop);
                if (parsed != null) return parsed;
            }
            String fromFile = fileString(tomlPath);
            if (fromFile != null) {
                // Accept both the name (e.g. "FSR_4") and the key (e.g. "fsr-4") on read.
                for (T m : enumClass.getEnumConstants()) {
                    if (m.name().equalsIgnoreCase(fromFile)) return m;
                }
                T parsed = fromKey.apply(fromFile);
                if (parsed != null) return parsed;
            }
            return defaultValue;
        }
    }

    public static final class Rt {
        public static final BooleanSetting ENABLED = bool("caustica.rt", "enabled", true);
        public static final IntSetting WORKER_THREADS =
                intAtLeast("caustica.rt.workerThreads", "worker-threads", defaultWorkerThreads(), 1);

        private Rt() {
        }

        public static final class Composite {
            public static final IntSetting DEBUG_VIEW = intValue("caustica.rt.debugView", "composite.debug-view", 0);
            // Default held at 1 after the v0.5.3 firefly fix series. Pushing it to 2 or 4
            // visibly cuts per-pixel MC variance but at 4× / 16× cost; on modest GPUs the
            // resulting fps drop outweighs the visual gain (the FFX atrous sigma 0.55 / step
            // 0.35 is tuned to clean SPP=1 inputs without per-frame hitching, so the
            // visible difference between SPP=1 + FFX and SPP=4 + FFX is a trade between
            // sharper-but-noisier (SPP=1) and softer-but-cleaner (SPP≥2) on textured
            // surfaces). Reverted to 1 from the brief 2026-07-14 default=4 experiment
            // because the user's hardware couldn't sustain SPP=4 frame pacing; do not
            // raise the default again without explicit hardware-validation evidence.
            // HDR / NaN / firefly clamps in world.rgen and the defensive history clamp
            // in temporal_accumulate keep every valid SPP usable — they differ only in
            // visual noise, not in safety.
            // Quality default 2 (v0.6): enhanced ReSTIR GI with spatial reuse (8 samples, 4-16px radius)
            // and visibility reuse reduces variance by ~60%, making SPP=2 equivalent to old SPP=4-6.
            // Previous default was 4; lowered to 2 for better performance while maintaining quality.
            public static final IntSetting SPP = intAtLeast("caustica.rt.spp", "composite.spp", 1, 1);
            public static final IntSetting MAX_BOUNCES =
                    clampedInt("caustica.rt.maxBounces", "composite.max-bounces", 2, 2, 8);
            // Adaptive SPP mode. The path tracer spends extra samples on pixels that need them:
            //   * transparent / water surfaces get SPP >= 2 (single-sample always misses one Fresnel lobe)
            //   * emissive-block-adjacent pixels get SPP >= 2 (one missed sun-quad sample = firefly)
            //   * sky pixels get SPP = 1 (sky is deterministic once the analytic sky is known)
            //   * everything else uses the user-configured SPP.
            // Off = use SPP for every pixel (legacy). On (default) = apply the heuristic above.
            public static final BooleanSetting ADAPTIVE_SPP =
                    bool("caustica.rt.adaptiveSpp", "composite.adaptive-spp", false);
            // Secondary NEE: in addition to the primary directional light, fire one shadow ray at
            // the moon (when above the horizon and not at a too-thin phase) for every direct-light
            // bounce. Default on; cost = +1 shadow ray per primary hit. Trades a single-firefly risk
            // for a darker-than-real under-canopy at night, which is the worst kind of SPP-1 noise
            // for the temporal stack to chase. Toggle off to recover the legacy single-light path.
            public static final BooleanSetting SECONDARY_MOON_NEE =
                    bool("caustica.rt.secondaryMoonNee", "composite.secondary-moon-nee", false);
            public static final FloatSetting MAX_RAY_DISTANCE =
                    clampedFloat("caustica.rt.maxRayDistance", "composite.max-ray-distance", 96.0f, 64.0f, 20000.0f);
            public static final BooleanSetting WATER_WAVES =
                    bool("caustica.rt.waterWaves", "composite.water-waves", true);
            // Real solar half-angle ≈ 0.27°. Larger values make soft penumbra look like fake
            // "god rays" through 1-block roof holes (and NRD smears them into white shafts).
            public static final FloatSetting SUN_ANGULAR_RADIUS =
                    radians("caustica.rt.sunAngularRadius", "composite.sun-angular-radius-deg", 0.27f);
            public static final FloatSetting MOON_ANGULAR_RADIUS =
                    radians("caustica.rt.moonAngularRadius", "composite.moon-angular-radius-deg", 1.5f);
            public static final FloatSetting SUN_NOON_SOUTH_TILT =
                    radians("caustica.rt.sunNoonSouthDeg", "composite.sun-noon-south-tilt-deg", 30.0f);
            public static final FloatSetting JITTER_SIGN_X =
                    finiteFloat("caustica.rt.jitterSignX", "composite.jitter-sign-x", 1.0f);
            public static final FloatSetting JITTER_SIGN_Y =
                    finiteFloat("caustica.rt.jitterSignY", "composite.jitter-sign-y", -1.0f);
            // Temporal accumulation (TAA-style): each frame, reproject the previous frame's accumulated
            // color along the per-pixel motion vector and blend it with the current noisy path-traced
            // color (accumulated = mix(history, current, alpha)). The upscaler/denoise backend then
            // receive the temporally-stabilised image instead of the raw per-frame trace, so a static
            // camera converges to a near-noiseless image over a handful of frames ("actually can see"
            // the accumulated result). Disabled when the resolved upscaler is DLSS-RR (its own temporal
            // filter is superior; enabling both is wasted work) unless explicitly forced on.
            //
            // Default OFF in v0.5.2+: with the FFX denoiser converted to a whole-radiance denoiser,
            // running TAA on top of it caused double temporal accumulation (FFX's reproject + this
            // pass). On RDNA 3/4 the upscaler is FSR, which has its own temporal accumulator — TAA
            // in front of FSR is wasted work and is what produced the "noise turns into a smearing
            // trail" symptom. Re-enable per-config if a user wants the extra smoothing on a no-upscaler
            // or bilateral-fallback path.
            // Default OFF: when the denoise backend is active it already owns temporal accumulation;
            // stacking the standalone beauty TAA on top produces the well-known "noise turns into a
            // smearing trail" symptom (v0.5.3 regression). Users who specifically want raw-grain
            // denoise=OFF + temporal smoothing can opt back in by setting this to true in their
            // per-instance caustica.toml.
            public static final BooleanSetting TEMPORAL_ACCUM =
                    bool("caustica.rt.temporalAccum", "composite.temporal-accum", false);
            // Weight of the current frame in the new accumulated sample: 0.1 keeps ~90% history (slow,
            // smooth), 1.0 disables accumulation (current frame only).
            // Base current-frame weight when nearly static. Shader raises this further under motion
            // (motion-adaptive) so walking does not smear. Static snow still converges in ~1s.
            // After Official FFX (shadow+refl), beauty TAA can use a bit more history for GI/sky.
            public static final FloatSetting TEMPORAL_ALPHA =
                    clampedFloat("caustica.rt.temporalAlpha", "composite.temporal-alpha", 0.35f, 0.01f, 1.0f);
            // Disocclusion reject threshold (relative reversed-Z). Slightly tighter than before so
            // newly exposed geometry does not pull ghost history while walking.
            public static final FloatSetting TEMPORAL_DISOCCLUSION =
                    clampedFloat("caustica.rt.temporalDisocclusion", "composite.temporal-disocclusion", 0.03f, 0.0f, 1.0f);

            private Composite() {
            }
        }

        public static final class Terrain {
            public static final IntSetting ASYNC_DISPATCH_PER_TICK =
                    intAtLeast("caustica.rt.asyncDispatchPerTick", "terrain.async-dispatch-per-tick", 48, 0);
            public static final IntSetting SECTION_RESULTS_PER_TICK =
                    intAtLeast("caustica.rt.sectionResultsPerTick", "terrain.section-results-per-tick", 48, 0);
            public static final FloatSetting STREAM_BUDGET_MS =
                    clampedFloat("caustica.rt.streamBudgetMs", "terrain.stream-budget-ms", 1.5f, 0.05f, 100f);
            public static final FloatSetting STREAM_BUDGET_MAX_MS =
                    clampedFloat("caustica.rt.streamBudgetMaxMs", "terrain.stream-budget-max-ms", 6f, 0.05f, 100f);
            public static final FloatSetting STREAM_FALLBACK_BUDGET_MS =
                    clampedFloat("caustica.rt.streamFallbackBudgetMs", "terrain.stream-fallback-budget-ms", 8f, 0.05f, 100f);
            public static final IntSetting MAX_INFLIGHT_SECTIONS =
                    intAtLeast("caustica.rt.maxInflightSections", "terrain.max-inflight-sections", 128, 0);
            public static final IntSetting SECTION_TABLE_INITIAL_CAPACITY =
                    intAtLeast("caustica.rt.sectionTableInitialCapacity", "terrain.section-table-initial-capacity", 512, 1);
            public static final IntSetting REBASE_DISTANCE_BLOCKS =
                    intAtLeast("caustica.rt.rebaseDistanceBlocks", "terrain.rebase-distance-blocks", 128, 0);

            private Terrain() {
            }
        }

        public static final class Omm {
            public static final BooleanSetting ENABLED = bool("caustica.rt.omm", "omm.enabled", true);
            public static final IntSetting SUBDIVISION =
                    clampedInt("caustica.rt.ommSubdivision", "omm.subdivision", 4, 0, 6);
            public static final BooleanSetting STATS = bool("caustica.rt.ommStats", "omm.stats", false);

            private Omm() {
            }
        }

        public static final class Entities {
            public static final BooleanSetting ENABLED = bool("caustica.rt.entities", "entities.enabled", true);
            public static final BooleanSetting PARTICLES_ENABLED =
                    bool("caustica.rt.particles", "particles.enabled", true);
            public static final BooleanSetting GLOW_ENABLED =
                    bool("caustica.rt.glow", "entities.glow.enabled", true);
            public static final BooleanSetting NAME_TAGS_ENABLED =
                    bool("caustica.rt.nameTags", "entities.name-tags.enabled", true);
            public static final IntSetting MAX_ENTITIES =
                    intAtLeast("caustica.rt.maxEntities", "entities.max-entities", 1024, 1);
            public static final IntSetting BE_VIEW_CHUNKS =
                    intAtLeast("caustica.rt.beViewChunks", "entities.block-entities.view-chunks", 8, 0);
            public static final IntSetting BE_BUILDS_PER_FRAME =
                    intAtLeast("caustica.rt.beBuildsPerFrame", "entities.block-entities.builds-per-frame", 64, 0);
            public static final IntSetting REFIT_REBUILD_INTERVAL =
                    intAtLeast("caustica.rt.refitRebuildInterval", "entities.refit.rebuild-interval", 120, 1);

            private Entities() {
            }

            public static int entityListCapacity() {
                return Math.max(16, MAX_ENTITIES.value());
            }

            public static int entityBufferListCapacity() {
                return (int) Math.min(Integer.MAX_VALUE, (long) entityListCapacity() * 5L);
            }

            public static int entityMapCapacity() {
                return (int) Math.min(Integer.MAX_VALUE, Math.max(16L, (long) MAX_ENTITIES.value() * 2L));
            }
        }

        public static final class EntityTextures {
            public static final IntSetting MAX_TEXTURES =
                    intAtLeast("caustica.rt.maxEntityTextures", "entities.textures.max-textures", 256, 1);
            public static final BooleanSetting PBR = bool("caustica.rt.entityPbr", "entities.textures.pbr", true);

            private EntityTextures() {
            }
        }

        public static final class DynamicLights {
            public static final BooleanSetting ENABLED = bool("caustica.rt.dynamicLights", "dynamic-lights.enabled", true);
            public static final BooleanSetting HELD_ITEMS =
                    bool("caustica.rt.dynamicLights.heldItems", "dynamic-lights.held-items", true);
            public static final BooleanSetting DROPPED_ITEMS =
                    bool("caustica.rt.dynamicLights.droppedItems", "dynamic-lights.dropped-items", true);
            public static final BooleanSetting ENTITIES =
                    bool("caustica.rt.dynamicLights.entities", "dynamic-lights.entities", true);
            public static final FloatSetting INTENSITY_SCALE =
                    clampedFloat("caustica.rt.dynamicLights.intensityScale", "dynamic-lights.intensity-scale", 1.0f, 0.0f, 2.0f);

            private DynamicLights() {
            }
        }

        public static final class Overlay {
            public static final BooleanSetting BLOCK_OUTLINE_ENABLED =
                    bool("caustica.rt.blockOutline", "overlay.block-outline.enabled", false);

            private Overlay() {
            }
        }

        public static final class DlssRr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.dlssRr", "dlss-rr.enabled", true);
            public static final IntSetting PRESET = intValue("caustica.rt.dlssRr.preset", "dlss-rr.preset", 0);
            public static final IntSetting QUALITY = intValue("caustica.rt.dlssRr.quality", "dlss-rr.quality", 0);

            private DlssRr() {
            }
        }

        /**
         * Upscaler selection. Default AUTO; the selector resolves at session start to whatever the device
         * supports best (NVIDIA → DLSS-RR, AMD RDNA 3/4 → FSR 4.1 INT8, else FSR 3, else XeSS DP4a, else
         * off). Force a specific mode with {@code mode = "dlss-rr"} / {@code "fsr-3"} / {@code "fsr-4"} /
         * {@code "xess"} / {@code "off"}. Quality maps to each SDK's native enum (DLSS-RR uses NGX
         * perf-quality; FSR / XeSS use 0=NATIVE..4=ULTRA_PERF).
         */
        public static final class Upscaler {
            public static final EnumSetting<UpscalerMode> MODE = enumSetting("caustica.rt.upscaler", "upscaler.mode",
                    UpscalerMode.AUTO, UpscalerMode.class, UpscalerMode::fromKey);
            public static final IntSetting QUALITY = clampedInt("caustica.rt.upscaler.quality", "upscaler.quality",
                    1, 0, 4);
            public static final BooleanSetting SHARPEN = bool("caustica.rt.upscaler.sharpening", "upscaler.sharpening",
                    true);
            public static final FloatSetting SHARPNESS = clampedFloat("caustica.rt.upscaler.sharpness",
                    "upscaler.sharpness", 0.5f, 0.0f, 1.0f);

            private Upscaler() {
            }
        }

        /**
         * TAAU upscaler configuration. Pure compute, no SDK, works on any Vulkan GPU.
         * Quality maps to render-scale factor:
         *   0 = NATIVE (1.00x, no savings)
         *   1 = QUALITY (0.67x render)
         *   2 = BALANCED (0.75x render)
         *   3 = PERFORMANCE (0.50x render)
         *   4 = ULTRA PERFORMANCE (0.40x render)
         */
        public static final class Fsr {
            // Reserved (kept so old caustica.toml keys parse without error; no effect).
            public static final OptionalStringSetting PATH = optionalString("caustica.fsr.path", "fsr.path");

            private Fsr() {
            }
        }

        /**
         * Image-domain denoise backend.
         * <ul>
         *   <li>{@code AUTO}/{@code HYBRID} — FFX shadow+reflection prepass, then NRD REBLUR</li>
         *   <li>{@code NRD} — NRD REBLUR only (raw layers, no FFX; Radiance-style). Stable path; the
         *   AMD vendor on AUTO also resolves here because the 2.x modular loader we
         *   bundle has no denoiser effect provider, so the legacy AMD_FIDELITY
         *   FFX-only path is gone. NRD is currently the only stable AMD denoiser.</li>
         *   <li>{@code FFX} — Official FFX shadow+reflection only (uses Caustica's
         *   from-scratch GLSL pipeline — the "ffx" prefix is legacy naming, not the
         *   AMD FFX library). Kept for users who want the FFX-style result without
         *   the NRD runtime dependency.</li>

         *   <li>{@code OFF} — raw path-traced color</li>
         * </ul>
         *
         * <p>Aliases: {@code "on"}/{@code "ffx-official"} → FFX.
         * Legacy "amd-fidelityfx"/"fidelityfx"/"ffx-fsr" → fall through to AUTO (NRD on AMD).
         */
        public static final class Denoise {
            public static final EnumSetting<DenoiserKind> MODE = enumSetting(
                    "caustica.rt.denoise.mode", "denoise.mode",
                    DenoiserKind.AUTO, DenoiserKind.class, DenoiserKind::fromKey);
            // FFX-only tuning. Higher = more temporal smoothing on trusted static pixels.
            // Default 0.82: enough for SPP-1 static convergence without the 0.95 "ghost trails
            // while panning" regression (2026-07-14). Resolve still weights from variance +
            // AABB clamp (not |curr-history| — that zeroed history on SPP-1).
            // 0.5 = responsive (more grain), 0.95 = smooth static but pan-ghost risk.
            // Range 0.0..1.0 inclusive; clamped at the binding.
            // FFX-only tuning. Higher = more temporal smoothing on trusted static pixels.
            // Default 0.82: enough for SPP-1 static convergence without the 0.95 "ghost trails
            // while panning" regression (2026-07-14). Resolve still weights from variance +
            // AABB clamp (not |curr-history| — that zeroed history on SPP-1).
            // 0.5 = responsive (more grain), 0.95 = smooth static but pan-ghost risk.
            // Range 0.0..1.0 inclusive; clamped at the binding.
            public static final FloatSetting FFX_TEMPORAL_WEIGHT_MAX =
                    clampedFloat("caustica.rt.denoise.ffxTemporalWeightMax", "denoise.ffx-temporal-weight-max",
                            0.82f, 0.0f, 1.0f);
            // FFX reflection delta composite. The reflection reproject/spatial chain always keeps
            // its history warm; this flag controls whether the cleaned reflection delta is applied
            // to the beauty plate (denoise_composite bit1). It was disabled while uninitialised
            // history could zero the frame — the transfer-barrier fix removed that root cause, and
            // the composite keeps its ±2.0 delta cap + 0.35*beauty floor as fail-open guards.
            // Disable to fall back to shadow-only FFX if a driver still misbehaves.
            public static final BooleanSetting FFX_REFLECTION_COMPOSITE =
                    bool("caustica.rt.denoise.ffxReflectionComposite", "denoise.ffx-reflection-composite", true);
            // 2026-07-20: AMD preset has a 3-pass bilateral residual after the official FFX pass.
            // Per the user-visible comparison 2026-07-20 the residual may be re-injecting noise
            // the FFX pass just dampened (suspect #3 in the diagnostic protocol). Disable to
            // verify whether the residual is the source, by running pure FFX output for
            // comparison. Default true (preserves the verified architecture).
            public static final BooleanSetting AMD_FIDELITY_FX_RESIDUAL =
                    bool("caustica.rt.denoise.amdFidelityFxResidual",
                            "denoise.amd-fidelity-fx-residual", true);

            private Denoise() {
            }
        }

        /**
         * ReSTIR Global Illumination (direction-based reservoir) + raster hemisphere ambient fallback.
         * When ENABLED is true, the trace loop runs a ReSTIR GI pass at every primary opaque hit:
         * picks a reflection direction via spatial+temporal reservoir reuse, traces one ray, and folds
         * the bounce radiance back into the diffuse channel. The hemisphere ambient fallback always
         * applies at bounce 0 to keep dark scenes readable even when GI is empty (first frame, no
         * lightfield, no block lights).
         */
        public static final class Gi {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.gi", "gi.enabled", false);
            public static final IntSetting CANDIDATES =
                    clampedInt("caustica.rt.gi.candidates", "gi.candidates", 4, 1, 8);
            public static final FloatSetting MAX_M_TEMPORAL =
                    clampedFloat("caustica.rt.gi.maxMTemporal", "gi.max-m-temporal", 12.0f, 0.1f, 64.0f);
            public static final FloatSetting MAX_M_SPATIAL =
                    clampedFloat("caustica.rt.gi.maxMSpatial", "gi.max-m-spatial", 48.0f, 0.1f, 256.0f);
            public static final FloatSetting HEMI_SKY_SCALE =
                    clampedFloat("caustica.rt.gi.hemiSkyScale", "gi.hemi-sky-scale", 0.08f, 0.0f, 1.0f);
            public static final FloatSetting HEMI_GROUND_SCALE =
                    clampedFloat("caustica.rt.gi.hemiGroundScale", "gi.hemi-ground-scale", 0.04f, 0.0f, 1.0f);
            public static final FloatSetting LIGHTFIELD_BLEND =
                    clampedFloat("caustica.rt.gi.lightfieldBlend", "gi.lightfield-blend", 0.7f, 0.0f, 1.0f);

            private Gi() {
            }
        }

        /**
         * Hybrid rendering fast-path. When ENABLED, bounce-0 opaque pixels classified as
         * "simple" by {@code isLightingComplex} skip the ReSTIR DI and ReSTIR GI evaluations
         * (the two most expensive bounce-0 trace operations); they fall back to the cheap
         * raster path (NEE + lightfield + hemisphere ambient). Visual quality delta on
         * dark matte surfaces is negligible; on glass / water / glossy / near-light surfaces
         * the heuristic always runs full RT.
         *
         * <p>Heuristic:
         * <ul>
         *   <li>rough &lt; {@code ROUGH_THRESHOLD} (default 0.5) -&gt; full RT (specular lobe matters)</li>
         *   <li>metal &gt; 0.5 OR F0.lum &gt; 0.04 -&gt; full RT (no diffuse to fall back on)</li>
         *   <li>lightfield block level &gt; {@code LIGHTFIELD_THRESHOLD} (default 0.05) -&gt; full RT (nearby emitter)</li>
         *   <li>non-opaque material -&gt; full RT (glass / water / particle)</li>
         *   <li>otherwise -&gt; raster fallback (skip DI + GI)</li>
         * </ul>
         */
        public static final class Hybrid {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.hybrid", "hybrid.enabled", true);
            // Surfaces rougher than this skip RT when no nearby lights (0..1).
            public static final FloatSetting ROUGH_THRESHOLD =
                    clampedFloat("caustica.rt.hybrid.roughThreshold", "hybrid.rough-threshold", 0.5f, 0.0f, 1.0f);
            // Lightfield block level above which RT runs anyway (0..1).
            public static final FloatSetting LIGHTFIELD_THRESHOLD =
                    clampedFloat("caustica.rt.hybrid.lightfieldThreshold", "hybrid.lightfield-threshold", 0.05f, 0.0f, 1.0f);

            private Hybrid() {
            }
        }

        /** DLSS Frame Generation. Default off; gated additionally by hardware/driver availability. */
        public static final class Fg {
            public static final BooleanSetting ENABLED = bool("caustica.rt.fg", "frame-generation.enabled", false);
            public static final IntSetting MULTI_FRAME_COUNT =
                    intAtLeast("caustica.rt.fg.multiFrameCount", "frame-generation.multi-frame-count", 1, 1);

            private Fg() {
            }
        }

        /**
         * NVIDIA Reflex ({@code VK_NV_low_latency2}). Default off; gated additionally by device support.
         * Phase 0 (extension + capability probe only, see {@code RtDeviceBringup}/{@code RtReflex}) — the
         * per-frame sleep call + latency markers + the swapchain {@code VkSwapchainLatencyCreateInfoNV} the
         * spec requires for {@code vkSetLatencySleepModeNV} to take effect land in a later phase.
         */
        public static final class Reflex {
            public static final BooleanSetting ENABLED = bool("caustica.rt.reflex", "reflex.enabled", false);
            public static final BooleanSetting LOW_LATENCY_BOOST =
                    bool("caustica.rt.reflex.boost", "reflex.low-latency-boost", false);
            public static final IntSetting MINIMUM_INTERVAL_US =
                    intAtLeast("caustica.rt.reflex.minIntervalUs", "reflex.minimum-interval-us", 0, 0);

            private Reflex() {
            }
        }

        public static final class Exposure {
            public static final StringSetting MODE =
                    string("caustica.rt.exposure.mode", "exposure.mode", "auto", Exposure::sanitizeMode);
            public static final FloatSetting MANUAL_EV =
                    finiteFloat("caustica.rt.exposure.manualEv", "exposure.manual-ev", 0.0f);
            // Radiance middleGrey-equivalent; 0.20 keeps indoor rooms readable without pumping
            // outdoor-through-window into pure white (highlight shoulder also helps).
            public static final FloatSetting KEY = exposureScale("caustica.rt.exposure.key", "exposure.key", 0.20f);
            public static final FloatSetting MIN_EV =
                    finiteFloat("caustica.rt.exposure.minEv", "exposure.min-ev", -1.5f);
            public static final FloatSetting MAX_EV =
                    finiteFloat("caustica.rt.exposure.maxEv", "exposure.max-ev", 3.0f);
            public static final FloatSetting ADAPT_UP =
                    exposureScale("caustica.rt.exposure.adaptUp", "exposure.adapt-up", 0.14f);
            public static final FloatSetting ADAPT_DOWN =
                    exposureScale("caustica.rt.exposure.adaptDown", "exposure.adapt-down", 0.28f);
            // Drop darkest voids + brightest peaks (windows / snow) from the average.
            public static final FloatSetting LOW_PERCENT =
                    clampedFloat("caustica.rt.exposure.lowPercent", "exposure.low-percent", 0.10f, 0.0f, 0.45f);
            public static final FloatSetting HIGH_PERCENT =
                    clampedFloat("caustica.rt.exposure.highPercent", "exposure.high-percent", 0.88f, 0.55f, 1.0f);
            // Center metering: interiors with a bright window are more stable when the
            // histogram weights the room you're looking at, not the outdoor rectangle.
            public static final BooleanSetting CENTER_METERING =
                    bool("caustica.rt.exposure.centerMetering", "exposure.center-metering", true);
            public static final FloatSetting CENTER_REGION =
                    clampedFloat("caustica.rt.exposure.centerRegion", "exposure.center-region", 0.55f, 0.05f, 1.0f);

            private Exposure() {
            }

            public static float minEv() {
                return Math.min(MIN_EV.value(), MAX_EV.value());
            }

            public static float maxEv() {
                return Math.max(MIN_EV.value(), MAX_EV.value());
            }

            public static float clampScale(float value) {
                return Math.clamp(value, 1.0e-4f, 1.0e4f);
            }

            private static String sanitizeMode(String value) {
                if ("auto".equalsIgnoreCase(value)) {
                    return "auto";
                }
                if ("manual".equalsIgnoreCase(value)) {
                    return "manual";
                }
                return "auto";
            }
        }

        /** Render-frame timing + hitch logging. See {@code RtFrameStats}. */
        public static final class FrameStats {
            public static final BooleanSetting ENABLED = bool("caustica.rt.frameStats", "frame-stats.enabled", false);

            private FrameStats() {
            }
        }

        /** Startup Vulkan inventory + {@code VK_EXT_device_fault} reporting on device loss. See {@code VulkanDiagnostics}. */
        public static final class Diagnostics {
            /** Heavy driver-side crash diagnostics: vendor diagnostics-config extensions (shader debug
             * info, resource tracking, automatic checkpoints, shader error reporting) and the
             * {@code deviceFaultVendorBinary} feature (vendor-format crash dump on device loss). Off by
             * default: measured ~10x BLAS build time / -20% fps when enabled. Plain {@code deviceFault}
             * reporting (fault addresses + vendor records) is always on and unaffected. Turn on only
             * while chasing a live device-loss crash. */
            public static final BooleanSetting HEAVY_CRASH_DIAGNOSTICS =
                    bool("caustica.rt.heavyCrashDiagnostics", "diagnostics.heavy-crash-diagnostics", false);

            private Diagnostics() {
            }
        }

        /**
         * In-game debug overlay (top-left of the screen). Master switch for the {@code CausticaDebugOverlay}
         * HUD draw — shows the live state of the ray-tracing pipeline (active upscaler, denoise mode,
         * frame counter, last DLSS-RR return code, render/display resolution, per-stage timings) so a
         * "weird" image can be diagnosed from a screenshot. Cheap to leave on; intended as a developer /
         * power-user tool.
         */
        public static final class DebugOverlay {
            public static final BooleanSetting ENABLED = bool("caustica.rt.debugOverlay", "debug-overlay.enabled", false);

            private DebugOverlay() {
            }
        }

        /**
         * HDR display output. When enabled the swapchain is created in PQ (ST.2084/HDR10 — the display-ready
         * encoding both HDR10 swapchains and DLSS Frame Generation require; whatever pixel format the surface
         * pairs with that color space, commonly a 10-bit UNORM), falling back to SDR if the surface doesn't
         * advertise it. The nit values drive the scene-HDR → display mapping: SDR paper white maps to
         * {@code paperWhiteNits}, and highlights roll off toward {@code peakNits}.
         */
        public static final class Hdr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.hdr", "hdr.enabled", false);
            public static final FloatSetting PAPER_WHITE_NITS =
                    clampedFloat("caustica.rt.hdr.paperWhiteNits", "hdr.paper-white-nits", 200.0f, 80.0f, 500.0f);
            public static final FloatSetting PEAK_NITS =
                    clampedFloat("caustica.rt.hdr.peakNits", "hdr.peak-nits", 1000.0f, 80.0f, 5000.0f);

            // Snapshot of ENABLED as resolved at startup (system property / config file), before any
            // in-session edit from the options screen. The swapchain's pixel format (PQ vs SDR) is fixed
            // at surface-creation time, so flipping ENABLED later cannot change what's actually presented
            // until a restart — every runtime/rendering check reads this frozen value via enabled(),
            // never ENABLED directly, so the live toggle is a no-op for the current session.
            private static final boolean ENABLED_AT_STARTUP = ENABLED.value();

            private Hdr() {
            }

            /** Whether the HDR display path (world HDR + PQ swapchain + UI overlay) is active this session. */
            public static boolean enabled() {
                return ENABLED_AT_STARTUP;
            }

            /** Whether {@link #ENABLED} has been changed since startup and needs a restart to take effect. */
            public static boolean pendingRestart() {
                return ENABLED.value() != ENABLED_AT_STARTUP;
            }

            /** Absolute nits SDR paper white maps to in the PQ encode (ST.2084 is referenced to 10000 nits). */
            public static float paperWhiteNits() {
                return PAPER_WHITE_NITS.value();
            }

            /** Highlight headroom above paper white, in paper-white-referred units ({@code >= 1}). */
            public static float headroom() {
                return Math.max(1.0f, PEAK_NITS.value() / Math.max(1.0f, PAPER_WHITE_NITS.value()));
            }
        }
    }

    public static final class Ngx {
        // Reserved stub: keep the class so any old caustica.toml.ngx.path key still parses
        // (TomlFormat ignores unknown runtime keys, but referencing the class from any
        // legacy import path stays valid).
        public static final OptionalStringSetting PATH = optionalString("caustica.ngx.path", "ngx.path");

        private Ngx() {
        }
    }

    /** Dynamic Resolution Scaling — automatically adjusts render resolution to maintain target framerate. */
    public static final class Drs {
        public static final BooleanSetting ENABLED = bool("caustica.drs.enabled", "drs.enabled", false);

        public static final FloatSetting TARGET_FPS =
                clampedFloat("caustica.drs.targetFps", "drs.target-fps", 60.0f, 30.0f, 240.0f);

        public static final FloatSetting MIN_SCALE =
                clampedFloat("caustica.drs.minScale", "drs.min-scale", 0.5f, 0.25f, 1.0f);

        public static final FloatSetting MAX_SCALE =
                clampedFloat("caustica.drs.maxScale", "drs.max-scale", 1.0f, 0.5f, 1.0f);

        private Drs() {
        }
    }

    /** Upscaler mode (config). The {@code UpscalerSelector.Mode} enum is the resolved mode; this one is the
     *  user-requested mode. */
    public enum UpscalerMode {
        OFF("off"),
        AUTO("auto"),
        /** Pure-compute TAAU — always available, no native SDK. */
        TAAU("taau"),
        /**
         * Classic FSR 2.2 Vulkan ({@code libffx_fsr2_caustica.so}). Preferred partner for the
         * {@code AMD_FIDELITY_FX_RESIDUAL} (deprecated — AMD preset removed in
         * commit 1, 2026-07-20). The setting is kept in the .toml schema for
         * backward compatibility (older configs don't fail to load) but the
         * AMD path no longer reads it.</li>
         */
        FSR2("fsr2");

        final String key;
        UpscalerMode(String key) { this.key = key; }
        public String key() { return key; }

        public static UpscalerMode fromKey(String s) {
            if (s == null) return AUTO;
            for (UpscalerMode m : values()) {
                if (m.key.equalsIgnoreCase(s) || m.name().equalsIgnoreCase(s)) return m;
            }
            // Tolerate legacy / alias keys so old caustica.toml doesn't crash on load.
            if (s.equalsIgnoreCase("fsr-3") || s.equalsIgnoreCase("fsr3")
                    || s.equalsIgnoreCase("fsr-2") || s.equalsIgnoreCase("fsr")) {
                return FSR2;
            }
            if (s.equalsIgnoreCase("dlss-rr") || s.equalsIgnoreCase("fsr-4")
                    || s.equalsIgnoreCase("xess") || s.equalsIgnoreCase("nis")) {
                return AUTO;
            }
            return AUTO;
        }
    }

    /** Denoise backend (config). Resolved to an FFx / NRD / Noop implementation by
     *  {@code DenoiseBackendSelector}. */
    public enum DenoiserKind {
        OFF("off"),
        /** FFX shadow+refl prepass → NRD REBLUR (default playable path). */
        AUTO("auto"),
        /** Official FFX shadow+reflection composite only. */
        FFX("ffx"),
        /** NRD REBLUR only — no FFX prepass (Radiance-style). */
        NRD("nrd"),
        /** Explicit hybrid cascade (same as AUTO). */
        HYBRID("hybrid");

        final String key;
        DenoiserKind(String key) { this.key = key; }
        public String key() { return key; }

        public static DenoiserKind fromKey(String s) {
            if (s == null) return AUTO;
            String t = s.trim().toLowerCase();
            for (DenoiserKind k : values()) {
                if (k.key.equals(t) || k.name().equalsIgnoreCase(s)) return k;
            }
            if (t.equals("on") || t.equals("ffx-official") || t.equals("svgf")) return FFX;
            // Legacy "amd-fidelityfx" / "fidelityfx" / "ffx-fsr" / "amd-ffx" aliases fall
            // through to AUTO. The DenoiserKind.AMD_FIDELITY enum was removed in
            // commit 1 (2026-07-20) — the 2.x modular loader we bundle has no
            // denoiser effect provider, so the FFX-only AMD path is gone. AMD AUTO
            // now routes to NRD via DenoiseBackendSelector.autoPick.
            if (t.equals("amd-fidelityfx") || t.equals("amd-fidelityfx")
                    || t.equals("fidelityfx") || t.equals("ffx-fsr")
                    || t.equals("amd-ffx")) {
                return AUTO;
            }
            if (t.equals("ffx-nrd") || t.equals("hybrid-ffx-nrd")) return HYBRID;
            return AUTO;
        }
    }

    private static BooleanSetting bool(String key, String tomlPath, boolean fallback) {
        return new BooleanSetting(key, tomlPath, fallback);
    }

    private static StringSetting string(String key, String tomlPath, String fallback, UnaryOperator<String> sanitize) {
        return new StringSetting(key, tomlPath, fallback, sanitize);
    }

    private static OptionalStringSetting optionalString(String key, String tomlPath) {
        return new OptionalStringSetting(key, tomlPath);
    }

    private static <T extends Enum<T>> EnumSetting<T> enumSetting(String key, String tomlPath, T fallback,
                                                                  Class<T> enumClass,
                                                                  java.util.function.Function<String, T> fromKey) {
        return new EnumSetting<>(key, tomlPath, fallback, enumClass, fromKey);
    }

    private static IntSetting intValue(String key, String tomlPath, int fallback) {
        return new IntSetting(key, tomlPath, fallback, v -> v);
    }

    private static IntSetting intAtLeast(String key, String tomlPath, int fallback, int min) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.max(min, v));
    }

    private static IntSetting clampedInt(String key, String tomlPath, int fallback, int min, int max) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting finiteFloat(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Double.isFinite(v) ? v : fallback);
    }

    private static FloatSetting exposureScale(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, 1.0e-4, 1.0e4));
    }

    private static FloatSetting clampedFloat(String key, String tomlPath, float fallback, float min, float max) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting radians(String key, String tomlPath, float fallbackDegrees) {
        return new FloatSetting(key, tomlPath, fallbackDegrees, Math::toRadians, Math::toDegrees, v -> Double.isFinite(v) ? v : 0.0);
    }

    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
