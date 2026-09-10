package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
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
            Rt.ENABLED, Rt.Renderer.BACKEND, Rt.Composite.SPP, Rt.Composite.MAX_BOUNCES, Rt.Composite.MAX_RAY_DISTANCE, Rt.Composite.TEMPORAL_ACCUM, Rt.Composite.TEMPORAL_ALPHA, Rt.Composite.TEMPORAL_DISOCCLUSION, Rt.Composite.TILE_JITTER, Rt.Terrain.ASYNC_DISPATCH_PER_TICK, Rt.Ser.ENABLED, Rt.Omm.ENABLED,
            Rt.Entities.ENABLED, Rt.Entities.GLOW_ENABLED, Rt.EntityTextures.MAX_TEXTURES, Rt.AsyncCompute.ENABLED, Rt.SubgroupOps.ENABLED, Rt.Denoise.MODE, Rt.Denoise.NRD_MAX_ACCUMULATED_FRAMES, Rt.Denoise.NRD_RESIDUAL_BILATERAL,
            Rt.Entities.RT_ENTITY_DISTANCE_BLOCKS,
            Rt.Gi.ENABLED, Rt.Gi.CANDIDATES, Rt.Gi.MAX_M_TEMPORAL, Rt.Gi.MAX_M_SPATIAL, Rt.Gi.HEMI_SKY_SCALE, Rt.Gi.HEMI_GROUND_SCALE, Rt.Gi.LIGHTFIELD_BLEND,
            Rt.Hybrid.ENABLED, Rt.Hybrid.ROUGH_THRESHOLD, Rt.Hybrid.LIGHTFIELD_THRESHOLD,
            Rt.Exposure.MODE, Rt.FrameStats.ENABLED, Rt.DebugOverlay.ENABLED, Rt.Probe.ENABLED,
            Rt.Probe.INTERVAL,
            Rt.Hdr.ENABLED, Rt.Upscaler.MODE, Rt.Upscaler.QUALITY, Rt.Upscaler.SHARPEN, Rt.Upscaler.SHARPNESS,
            Rt.Fsr.PATH,
            Rt.DynamicLights.ENABLED, Rt.DynamicLights.HELD_ITEMS, Rt.DynamicLights.DROPPED_ITEMS,
            Rt.RestirEnhanced.PAIRED_REUSE_ENABLED, Rt.RestirEnhanced.PAIRED_REUSE_SHUFFLE_PERIOD,
            Rt.RestirEnhanced.DUPLICATION_MAP_ENABLED, Rt.RestirEnhanced.DUPLICATION_MAP_RADIUS,
            Rt.RestirEnhanced.FOOTPRINT_RECONNECTION, Rt.RestirEnhanced.FOOTPRINT_MOTION_CAP_PX,
            Rt.RestirEnhanced.VISIBILITY_REUSE_DI,
            Rt.RestirEnhanced.QUARTER_RES_RESERVOIR,
            Rt.RestirEnhanced.UNIFIED_RESERVOIR, Rt.RestirEnhanced.VECTOR_VALUED_WEIGHTS,
            Rt.RestirEnhanced.RR_PSS_SPLIT, Rt.RestirEnhanced.STREAM_COMPACTION,
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
                " Built-in Vulkan motion/depth frame generation. Default off; no vendor SDK is required.\n"
                        + " multi-frame-count: frames generated per rendered frame (1 = 2x, 2 = 3x, ...); the\n"
                        + " built-in provider clamps this to 3 generated frames.");
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
        // Selects which compiled .spv variant of world.rgen the composite path dispatches.
        // FULL          — every light channel (NEE + ReSTIR DI + ReSTIR GI + SSS + emissive + specular).
        //                 Default for NVIDIA RTX-class hardware where the hardware RT pipeline
        //                 sustains the full trace depth at interactive rates.
        // SPECULAR_ONLY — only the specular reflection + GGX bounce survive; diffuse NEE /
        //                 ReSTIR DI / ReSTIR GI / SSS / block-light NEE are #ifdef'd out at
        //                 compile time. Designed for AMD RDNA / Intel Xe-HPG / any card
        //                 where the RT pipeline can afford one reflection trace per pixel but
        //                 not a full path. The 268 KB SPIR-V variant (vs 351 KB for FULL)
        //                 is materially cheaper to push through the Vulkan driver.
        public static final EnumSetting<RtMode> MODE = enumSetting(
                "caustica.rt.mode", "mode", RtMode.FULL, RtMode.class, RtMode::fromKey);

        private Rt() {
        }

        public static final class Renderer {
            public static final EnumSetting<RendererBackend> BACKEND = enumSetting(
                    "caustica.rt.renderer", "renderer.backend", RendererBackend.AUTO,
                    RendererBackend.class, RendererBackend::fromKey);

            private Renderer() {
            }
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
            // Per-tile stochastic jitter is a traversal-coherence optimization, not a quality feature.
            // It adds a small spatially quantized sample pattern which the temporal denoiser must undo.
            // Default ON (2026-09): the NRD pre-warp + TAAU reproject both consume gJitterGuide,
            // so the pattern is undone downstream; the first-bounce coherence win on
            // non-SER hardware (RDNA2/3, Arc, RADV) outweighs the warm-up cost.
            public static final BooleanSetting TILE_JITTER =
                    bool("caustica.rt.tileJitter", "composite.tile-jitter", true);
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
            // the accumulated result). Disabled when a temporal upscaler is active because stacking two
            // history filters is wasted work and increases ghosting.
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

        /**
         * Shader Execution Reordering (VK_EXT_ray_tracing_invocation_reorder). Optional scheduling
         * optimisation only — devices without the extension (or with this flag off) fall back to the
         * {@code world_noser.rgen.spv} raygen and remain fully RT-capable. Default ON; toggle off to
         * bisect a driver hang (some AMD LLPC builds fault with SER enabled at runtime).
         */
        public static final class Ser {
            public static final BooleanSetting ENABLED = bool("caustica.rt.ser", "ser.enabled", true);

            private Ser() {
            }
        }

        public static final class Omm {
            // Vendor-neutral default: OFF. OMM is HW-accelerated on RTX 40+ but
            // software-emulated elsewhere (extra BLAS memory + build cost, RADV
            // bugs pending). The any-hit alpha-test fallback is always live.
            public static final BooleanSetting ENABLED = bool("caustica.rt.omm", "omm.enabled", false);
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
            // RT entity budget: entities beyond MAX_ENTITIES stay in vanilla raster
            // instead of entering the TLAS. Captures are sorted by camera distance
            // first, so the budget keeps the nearest (most visible) geometry in RT
            // and drops only far/small casters (villagers, drops, XP orbs at range).
            public static final IntSetting RT_ENTITY_DISTANCE_BLOCKS =
                    intAtLeast("caustica.rt.entityDistanceBlocks", "entities.rt-distance-blocks", 48, 0);
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

        /**
         * Async-compute scheduling for the standalone denoise / temporal-accumulation
         * compute passes. When ENABLED is true and the device exposes a dedicated
         * compute queue family (see {@code RtDeviceBringup.asyncComputeAvailable}),
         * {@code RtAsyncCompute} submits the compute-segment command buffer on the
         * compute queue and synchronises the graphics queue back via timeline
         * semaphores after the work lands. Devices without a separate compute family
         * (and frames where the queue family is shared with the present queue) fall
         * back to the legacy single-queue path inside {@code RtAsyncCompute.submit}
         * — the runtime never silently drops the work. Default OFF: turning it on
         * changes frame pacing (compute work may overlap the next frame's setup),
         * which a performance-focused player may want exposed via the explicit flag.
         */
        public static final class AsyncCompute {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.asyncCompute", "async-compute.enabled", false);

            private AsyncCompute() {
            }
        }

        /**
         * Subgroup-scheduling optimisation hooks. When ENABLED is true the renderer
         * prefers the {@code depth_pyramid_group.comp.spv} variant of the denoise
         * depth-hierarchy shader (built with {@code -DCAUSTICA_SUBGROUP_OPS=1}) which
         * routes its 4-tap min reduction through {@code subgroupMin()} /
         * {@code subgroupBallotBitCount()}. Subgroup support is core in Vulkan 1.1;
         * the project's Vulkan 1.2 baseline already includes it, so this is a
         * scheduler-style annotation rather than a capability gate. Default ON —
         * the renderer always builds both variants at compile time and picks the
         * subgroup one when this flag is true. Toggle off (then rebuild if the
         * variant you need was excluded) to bisect a driver bug to subgroup
         * scheduling.
         */
        public static final class SubgroupOps {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.subgroupOps", "subgroup-ops.enabled", true);

            private SubgroupOps() {
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

        /**
         * ReSTIR PT Enhanced algorithmic features. Each sub-flag is independently defaulted so older
         * configs (which lack the {@code [restir-enhanced]} section) fall through to safe behaviour:
         * <ul>
         *   <li>P0 (paired reuse, duplication map): enabled by default; old 9-tap spatial path remains as fallback.</li>
         *   <li>P1 (footprint reconnection, visibility reuse): enabled by default; existing heuristics are the fallback.</li>
         *   <li>P2 (unified reservoir, vector-valued weights, RR-PSS split, streaming compaction): off by default;
         *       the unified reservoir is the highest-risk change and requires lab validation before default-on.</li>
         * </ul>
         * See docs/superpowers/specs/2026-07-29-restir-pt-enhanced.md for the upstream paper mapping.
         */
        public static final class RestirEnhanced {
            // P0-1 Paired spatial reuse textures (Lin et al. §3). Default ON; the inline 9-tap merge
            // in world.rgen stays as the fallback when this is disabled.
            public static final BooleanSetting PAIRED_REUSE_ENABLED =
                    bool("caustica.rt.restirEnhanced.pairedReuse", "restir-enhanced.paired-reuse-enabled", true);
            // Paired reuse table shuffle period (frames). Paper recommends 4–8; 4 is the default.
            public static final IntSetting PAIRED_REUSE_SHUFFLE_PERIOD =
                    intAtLeast("caustica.rt.restirEnhanced.pairedReuseShufflePeriod",
                            "restir-enhanced.paired-reuse-shuffle-period", 4, 1);
            // P0-2 Duplication map (Lin et al. §5). Default ON; disabling reverts temporal merge to the
            // hard cap c_default (Gi.MAX_M_TEMPORAL) without D-driven cap reduction.
            public static final BooleanSetting DUPLICATION_MAP_ENABLED =
                    bool("caustica.rt.restirEnhanced.duplicationMap", "restir-enhanced.duplication-map-enabled", true);
            public static final IntSetting DUPLICATION_MAP_RADIUS =
                    intAtLeast("caustica.rt.restirEnhanced.duplicationMapRadius",
                            "restir-enhanced.duplication-map-radius", 17, 3);
            // P1-1 Footprint-based reconnection (Lin et al. §4). Default ON; disabling reverts to the
            // existing roughness/distance heuristic in temporalValid().
            public static final BooleanSetting FOOTPRINT_RECONNECTION =
                    bool("caustica.rt.restirEnhanced.footprintReconnection",
                            "restir-enhanced.footprint-reconnection", true);
            // Footprint-rejection screen-space motion cap (pixels). Pixels with motion > this are
            // rejected in the temporal merge because the projection-radius test (Lin et al. §4
            // equation 7) is unlikely to pass. Default 25 px — well below the legacy 40 px hard
            // cap, so more reuse events are rejected when the cap is enabled.
            public static final FloatSetting FOOTPRINT_MOTION_CAP_PX =
                    clampedFloat("caustica.rt.restirEnhanced.footprintMotionCapPx",
                            "restir-enhanced.footprint-motion-cap-px", 25.0f, 1.0f, 200.0f);
            // P1-2 Visibility reuse on the DI channel. Default ON once cachedVisibility is populated
            // (currently GI-only; DI opt-in is a follow-up).
            public static final BooleanSetting VISIBILITY_REUSE_DI =
                    bool("caustica.rt.restirEnhanced.visibilityReuseDi",
                            "restir-enhanced.visibility-reuse-di", false);
            // P2-1 Unified DI+GI single reservoir (Lin et al. §6.1). Experimental; off by default.
            public static final BooleanSetting UNIFIED_RESERVOIR =
                    bool("caustica.rt.restirEnhanced.unifiedReservoir",
                            "restir-enhanced.unified-reservoir", false);
            // P2-2 Vector-valued weights, RR-PSS split, streaming compaction. Off until lab-validated.
            public static final BooleanSetting VECTOR_VALUED_WEIGHTS =
                    bool("caustica.rt.restirEnhanced.vectorValuedWeights",
                            "restir-enhanced.vector-valued-weights", false);
            // VRAM/stability-first path for RDNA2/RDNA3/Arc/RADV (2026-09): the DI
            // + hit-position reservoirs are stored at quarter resolution (1 per 2x2
            // tile) instead of 1 per pixel. Minecraft's large flat surfaces make
            // per-pixel reservoirs redundant; expect ~-60% reservoir VRAM and
            // ~-40% temporal/spatial merge cost. Default ON; disable to restore
            // the legacy full-resolution reservoirs.
            public static final BooleanSetting QUARTER_RES_RESERVOIR =
                    bool("caustica.rt.restirEnhanced.quarterResReservoir",
                            "restir-enhanced.quarter-res-reservoir", true);
            public static final BooleanSetting RR_PSS_SPLIT =
                    bool("caustica.rt.restirEnhanced.rrPssSplit",
                            "restir-enhanced.rr-pss-split", false);
            public static final BooleanSetting STREAM_COMPACTION =
                    bool("caustica.rt.restirEnhanced.streamCompaction",
                            "restir-enhanced.stream-compaction", false);

            private RestirEnhanced() {
            }
        }

        public static final class Overlay {
            public static final BooleanSetting BLOCK_OUTLINE_ENABLED =
                    bool("caustica.rt.blockOutline", "overlay.block-outline.enabled", false);

            private Overlay() {
            }
        }

        /**
         * Upscaler selection. AUTO resolves to the portable compute TAAU provider. FSR modes select classic
         * FSR2 when its native bridge is available and otherwise fall back to TAAU. XeSS and legacy mode keys
         * currently resolve to TAAU compatibility fallback; OFF selects the no-op provider. Quality uses the
         * shared 0=NATIVE..4=ULTRA_PERF scale interpreted by each active provider.
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
            // NRD REBLUR max accumulated-frame count. Borrowed from Sundial-Lite's
            // VB_MAX_BLEDED_FRAMES=20 — exposed here so users on iGPU / Apple Silicon / RDNA2
            // can trade temporal stability for memory + latency. NRD's stock is 32 (the hardcoded
            // value in caustica_nrd_shim.cpp until native rebuild); lowering to 8-16 saves
            // ~viewport * 4 bytes * 2 of history-texture memory per pixel and shortens the
            // anti-lag window. Range [1, 63] — NRD's documented max is 63.
            // 2026-08-06: range loosened from [1, 63] to [0, 63]. 0 = disable the NRD REBLUR
            // anti-lag cap (NRD native treats 0 as "no cap" and accumulates forever, eliminating
            // the periodic brightness pulse when the cap triggers at low FPS). 1 still means
            // "reset every frame" (worst-case constant noise), 2-63 is the normal cap range.
            public static final IntSetting NRD_MAX_ACCUMULATED_FRAMES =
                    clampedInt("caustica.rt.denoise.nrdMaxAccumulatedFrames",
                            "denoise.nrd-max-accumulated-frames", 32, 0, 63);
            // Optional post-NRD spatial polish. NRD already performs temporal and spatial filtering;
            // leaving this off avoids a second edge-aware pass preserving a quantized residual pattern.
            // Enable for a strict spatial A/B comparison only.
            public static final BooleanSetting NRD_RESIDUAL_BILATERAL =
                    bool("caustica.rt.denoise.nrdResidualBilateral",
                            "denoise.nrd-residual-bilateral", false);

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

        /** Vendor-neutral Vulkan motion-vector frame generation. Default off. */
        public static final class Fg {
            public static final BooleanSetting ENABLED = bool("caustica.rt.fg", "frame-generation.enabled", false);
            public static final IntSetting MULTI_FRAME_COUNT =
                    intAtLeast("caustica.rt.fg.multiFrameCount", "frame-generation.multi-frame-count", 1, 1);

            private Fg() {
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

        /**
         * Visual-data capture ("black box flight recorder"). When ENABLED, every Nth
         * frame the composite records per-stage numbers (plate means/variances,
         * firefly-kill effectiveness, denoise energy, upscale gain, motion /
         * disocclusion health, NaN poisoning) into {@code <gameDir>/rt-probe/probe.csv}.
         * Feed the CSV (+ a screenshot taken at the same time) to
         * {@code scripts/analyze_probe.py} to find which stage is out of range —
         * no visual guessing needed.
         */
        public static final class Probe {
            public static final BooleanSetting ENABLED = bool("caustica.rt.probe", "probe.enabled", false);
            public static final IntSetting INTERVAL =
                    intAtLeast("caustica.rt.probe.interval", "probe.interval-frames", 30, 1);

            private Probe() {
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
         * frame counter, last upscaler result, render/display resolution, per-stage timings) so a
         * "weird" image can be diagnosed from a screenshot. Cheap to leave on; intended as a developer /
         * power-user tool.
         */
        public static final class DebugOverlay {
            public static final BooleanSetting ENABLED = bool("caustica.rt.debugOverlay", "debug-overlay.enabled", false);

            private DebugOverlay() {
            }
        }

        /**
         * HDR display output. When enabled the swapchain is created in PQ (ST.2084/HDR10 — a display-ready
         * encoding suitable for HDR presentation and frame-generation providers; whatever pixel format the surface
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

    public enum RendererBackend {
        JAVA("java"),
        NATIVE("native"),
        AUTO("auto");

        final String key;
        RendererBackend(String key) { this.key = key; }
        public String key() { return key; }

        public static RendererBackend fromKey(String value) {
            if (value == null) return AUTO;
            for (RendererBackend backend : values()) {
                if (backend.key.equalsIgnoreCase(value) || backend.name().equalsIgnoreCase(value)) {
                    return backend;
                }
            }
            return AUTO;
        }
    }

    /** World RT raygen dispatch mode. Selects which compiled world.rgen SPIR-V variant the
     * composite path actually dispatches (full vs specular-only, see caustica.toml [rt] mode). */
    public enum RtMode {
        FULL("full"),
        /** Only specular reflection + GGX bounce survive; diffuse NEE / ReSTIR DI / GI / SSS
         *  are compiled out. Designed for AMD RDNA + Mesa RADV where the hardware RT
         *  pipeline can afford a reflection trace but not a full path trace. */
        SPECULAR_ONLY("specular-only");

        final String key;
        RtMode(String key) { this.key = key; }
        public String key() { return key; }

        public static RtMode fromKey(String value) {
            if (value == null) return FULL;
            for (RtMode mode : values()) {
                if (mode.key.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return FULL;
        }
    }

    /** User-requested upscaler mode. Runtime providers expose capabilities through {@code Upscaler}. */
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
        FSR2("fsr2"),
        /**
         * Classic FSR 3.4 / FFX 3.x upscaler ({@code libcaustica_ffx_fsr3_upscaler.so}).
         * Loaded via Panama {@code Fsr3Bridge}. Frame generation is NOT enabled —
         * this is upscaling only, same input contract as classic FSR 2 but with
         * extra inputs the bridge handles: reactive mask v2, 1×1 R32F exposure
         * image, view-space-to-meters factor.
         * RADV/NAVI33 routes to TAAU (see {@link dev.comfyfluffy.caustica.upscale.UpscalerSelector}).
         */
        FSR3("fsr3"),
        /**
         * FSR 4.1 / FFX 4.x modular upscaler ({@code libffx_fsr41_caustica.so}).
         * Preferred when {@code libffx_fsr41_linux.a} is present and the FSR 4.1
         * Linux port's Vulkan backend is shipped. The shim is currently
         * PROBE_ONLY: the wiring is in place but every dispatch returns -100
         * and the renderer falls back to a 1:1 blit. Rebuild the shim with
         * {@code -DCAUSTICA_FFX_FSR41_FULL=ON} once the FSR 4.1 Linux port's
         * static lib is available.
         */
        FSR41("fsr41");

        final String key;
        UpscalerMode(String key) { this.key = key; }
        public String key() { return key; }

        public static UpscalerMode fromKey(String s) {
            if (s == null) return AUTO;
            for (UpscalerMode m : values()) {
                if (m.key.equalsIgnoreCase(s) || m.name().equalsIgnoreCase(s)) return m;
            }
            // Tolerate legacy / alias keys so old caustica.toml doesn't crash on load.
            if (s.equalsIgnoreCase("fsr-4") || s.equalsIgnoreCase("fsr4")
                    || s.equalsIgnoreCase("fsr-41")) {
                return FSR41;
            }
            if (s.equalsIgnoreCase("fsr-2") || s.equalsIgnoreCase("fsr")) {
                return FSR2;
            }
            if (s.equalsIgnoreCase("fsr-3") || s.equalsIgnoreCase("fsr3")) {
                return FSR3;
            }
            if (s.equalsIgnoreCase("dlss-rr")
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
        HYBRID("hybrid"),
        /** Spatial-only 3x3 joint bilateral. Pure SPIR-V, ~0.5 ms regardless of vendor.
         *  No temporal history (no ghost trails) but also no temporal convergence —
         *  leaves some grain. The performance floor for RDNA3 / Intel Arc users when
         *  NRD's ~6-8 ms cost on a budget GPU is unacceptable. 2026-07-21. */
        BILATERAL("bilateral"),
        /** NRD RELAX_DIFFUSE_SPECULAR (NRD 4.x attention-based denoiser). Slightly higher
         *  cost than REBLUR (~7-9 ms on RX 7600 at 1080p vs REBLUR's ~6-8 ms) but ~15-20%
         *  better quality on fine geometry and material boundaries. Same vendor-portable
         *  story as NRD REBLUR. Requires the bundled NRD shim to be built with RELAX
         *  support (caustica_nrd_create_relax_v2 symbol). 2026-07-21. */
        RELAX("relax"),
        /** Official FidelityFX Denoiser 1.2 native provider via the bundled
         *  shim ({@code libffx_denoiser_caustica.so}). Wires through AMD's
         *  standalone denoiser SDK (the same effect the FSR 4.1 Linux port
         *  uses for its trace-validation path). The shim is PROBE_ONLY until
         *  AMD's shader blobs are built; while that is the case the
         *  provider falls back to {@link OfficialFfxDenoiseBackend}'s
         *  SPIR-V path internally and the user-visible behaviour matches
         *  {@code FFX}. */
        FFX_NATIVE("ffx-native");

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
            // commit 16287e5 (2026-07-20) — the 2.x modular loader we bundle has no
            // denoiser effect provider, so the FFX-only AMD path is gone. AMD AUTO
            // now routes to NRD via DenoiseBackendSelector.autoPick.
            //
            // Bedrock-RTX-aligned rationale (2026-07-21): Bedrock RTX uses NRD REBLUR
            // (diffuse+specular) + SIGMA_SHADOW with sky/emissive composited outside the
            // noisy path. Whole-radiance denoising (the AMD FFX whole-radiance path this
            // alias used to point at) does not match what Mojang ships and produced
            // colored fireflies on emissives + flat-face halos. The NRD path here mirrors
            // that contract: prepare_nrd_inputs.comp produces split diffuse/specular
            // signals and the compositor adds emissive/sky post-denoise.
            if (t.equals("amd-fidelityfx") || t.equals("amd-fidelityfx")
                    || t.equals("fidelityfx") || t.equals("ffx-fsr")
                    || t.equals("amd-ffx")) {
                dev.comfyfluffy.caustica.CausticaMod.LOGGER.warn(
                        "denoise.mode='{}' is a legacy AMD-FidelityFX whole-radiance alias; the bundled "
                                + "AMD FFX 2.x modular loader has no denoiser effect provider, so this mode "
                                + "now resolves to AUTO (NRD REBLUR + SIGMA — Bedrock-RTX-aligned). To "
                                + "silence this warning, set denoise.mode='auto' (recommended) or 'off'.",
                        t);
                return AUTO;
            }
            if (t.equals("ffx-nrd") || t.equals("hybrid-ffx-nrd")) return HYBRID;
            // "relax" and "nrd-relax" → explicit RELAX (NRD 4.x attention-based). Falls
            // back to AUTO (REBLUR) at runtime if the bundled NRD shim predates RELAX support.
            if (t.equals("relax") || t.equals("nrd-relax") || t.equals("relax-diffuse-specular")) {
                return RELAX;
            }
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
