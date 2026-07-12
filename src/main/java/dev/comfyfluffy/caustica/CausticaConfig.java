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
            Rt.ENABLED, Rt.Composite.SPP, Rt.Composite.MAX_BOUNCES, Rt.Terrain.ASYNC_DISPATCH_PER_TICK, Rt.Omm.ENABLED,
            Rt.Entities.ENABLED, Rt.Entities.GLOW_ENABLED, Rt.EntityTextures.MAX_TEXTURES, Rt.DlssRr.ENABLED, Rt.Fg.ENABLED, Rt.Denoise.MODE, Rt.Denoise.SIGMA_DEPTH, Rt.Denoise.SIGMA_NORMAL, Rt.Denoise.SIGMA_COLOR, Rt.Denoise.TEMPORAL_MAX,
            Rt.Reflex.ENABLED, Rt.Exposure.MODE, Rt.FrameStats.ENABLED,
            Rt.Hdr.ENABLED, Rt.Upscaler.MODE, Rt.Upscaler.QUALITY, Rt.Upscaler.SHARPEN, Rt.Upscaler.SHARPNESS,
            Rt.Fsr.PATH, Rt.Fsr.FORCE_FSR_3, Rt.FrameGen.MODE, Rt.FrameGen.MULTI_FRAME_COUNT,
            Rt.XeSs.PATH, Rt.XeSs.MODE,
            Ngx.PATH,
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
                    case DLSS_RR -> UpscalerSelector.Mode.DLSS_RR;
                    case FSR_3 -> UpscalerSelector.Mode.FSR_3;
                    case FSR_4 -> UpscalerSelector.Mode.FSR_4;
                    case XESS -> UpscalerSelector.Mode.XESS;
                };
            }
            if (enumClass == FrameGenMode.class) {
                FrameGenMode m = (FrameGenMode) value;
                return switch (m) {
                    case OFF -> UpscalerSelector.Mode.OFF;
                    case AUTO -> UpscalerSelector.Mode.AUTO;
                    case DLSSG -> UpscalerSelector.Mode.DLSS_RR; // not used; kept for completeness
                    case FSR_3, FSR_4, XESS -> UpscalerSelector.Mode.AUTO; // framegen has its own mapping
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
            public static final IntSetting SPP = intAtLeast("caustica.rt.spp", "composite.spp", 1, 1);
            public static final IntSetting MAX_BOUNCES =
                    clampedInt("caustica.rt.maxBounces", "composite.max-bounces", 4, 2, 8);
            public static final BooleanSetting WATER_WAVES =
                    bool("caustica.rt.waterWaves", "composite.water-waves", true);
            public static final FloatSetting SUN_ANGULAR_RADIUS =
                    radians("caustica.rt.sunAngularRadius", "composite.sun-angular-radius-deg", 0.6f);
            public static final FloatSetting MOON_ANGULAR_RADIUS =
                    radians("caustica.rt.moonAngularRadius", "composite.moon-angular-radius-deg", 1.5f);
            public static final FloatSetting SUN_NOON_SOUTH_TILT =
                    radians("caustica.rt.sunNoonSouthDeg", "composite.sun-noon-south-tilt-deg", 30.0f);
            public static final FloatSetting JITTER_SIGN_X =
                    finiteFloat("caustica.rt.jitterSignX", "composite.jitter-sign-x", 1.0f);
            public static final FloatSetting JITTER_SIGN_Y =
                    finiteFloat("caustica.rt.jitterSignY", "composite.jitter-sign-y", -1.0f);

            private Composite() {
            }
        }

        public static final class Terrain {
            public static final IntSetting ASYNC_DISPATCH_PER_TICK =
                    intAtLeast("caustica.rt.asyncDispatchPerTick", "terrain.async-dispatch-per-tick", 64, 0);
            public static final IntSetting SECTION_RESULTS_PER_TICK =
                    intAtLeast("caustica.rt.sectionResultsPerTick", "terrain.section-results-per-tick", 64, 0);
            public static final FloatSetting STREAM_BUDGET_MS =
                    clampedFloat("caustica.rt.streamBudgetMs", "terrain.stream-budget-ms", 1.5f, 0.05f, 100f);
            public static final FloatSetting STREAM_BUDGET_MAX_MS =
                    clampedFloat("caustica.rt.streamBudgetMaxMs", "terrain.stream-budget-max-ms", 6f, 0.05f, 100f);
            public static final FloatSetting STREAM_FALLBACK_BUDGET_MS =
                    clampedFloat("caustica.rt.streamFallbackBudgetMs", "terrain.stream-fallback-budget-ms", 8f, 0.05f, 100f);
            public static final IntSetting MAX_INFLIGHT_SECTIONS =
                    intAtLeast("caustica.rt.maxInflightSections", "terrain.max-inflight-sections", 192, 0);
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
                    clampedInt("caustica.rt.ommSubdivision", "omm.subdivision", 4, 0, 12);
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
                    intAtLeast("caustica.rt.beBuildsPerFrame", "entities.block-entities.builds-per-frame", 8, 0);
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

        public static final class Overlay {
            public static final BooleanSetting BLOCK_OUTLINE_ENABLED =
                    bool("caustica.rt.blockOutline", "overlay.block-outline.enabled", true);

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
         * AMD FidelityFX runtime configuration. {@code path} overrides the bundled native location (the
         * mod ships the FFX SDK loader + per-feature DLLs at {@code caustica-fsr/natives/} so end users
         * usually don't need to set this). {@code forceFsr3} forces the FFX upscaler to use the FSR 3 model
         * even when the SDK bundle contains an FSR 4 model — useful for diagnosing FSR 4 instability on
         * RDNA 3.
         */
        public static final class Fsr {
            public static final OptionalStringSetting PATH = optionalString("caustica.fsr.path", "fsr.path");
            public static final EnumSetting<FsrForceMode> FORCE_FSR_3 = enumSetting("caustica.fsr.forceFsr3",
                    "fsr.force-fsr-3", FsrForceMode.AUTO, FsrForceMode.class, FsrForceMode::fromKey);

            private Fsr() {
            }
        }

        /**
         * Frame generation selection. Default AUTO; matches the active upscaler (DLSS-RR → DLSSG; FSR 4 → FSR
         * 4 FG; FSR 3 → FSR 3 FG; XeSS → XeSS-FG) for best feature parity. Set {@code mode = "off"} to
         * disable.
         */
        public static final class FrameGen {
            public static final EnumSetting<FrameGenMode> MODE = enumSetting("caustica.rt.framegen", "framegen.mode",
                    FrameGenMode.AUTO, FrameGenMode.class, FrameGenMode::fromKey);
            public static final IntSetting MULTI_FRAME_COUNT = intAtLeast("caustica.rt.framegen.multiFrameCount",
                    "framegen.multi-frame-count", 1, 1);

            private FrameGen() {
            }
        }

        /**
         * Intel XeSS runtime configuration. {@code path} overrides the bundled native location (the mod ships
         * the XeSS runtime DLLs at {@code caustica-xess/natives/}). {@code mode} selects the SDK's execution
         * path: AUTO = XMX on Arc / Xe-LPG, DP4a fallback on NVIDIA / AMD; XMX_FORCE = require XMX and fail
         * if unavailable; DP4A_FORCE = DP4a only (broader device support, slightly lower quality).
         */
        public static final class XeSs {
            public static final OptionalStringSetting PATH = optionalString("caustica.xess.path", "xess.path");
            public static final EnumSetting<XeSsMode> MODE = enumSetting("caustica.xess.mode", "xess.mode",
                    XeSsMode.AUTO, XeSsMode.class, XeSsMode::fromKey);

            private XeSs() {
            }
        }

        /**
         * Caustica's internal SVGF-lite denoise pass. Default-on for any non-DLSS-RR path (FSR / XeSS
         * don't have a path-tracing-specific denoise, so the input has to be cleaner for their
         * temporal accumulators to lock on); off for DLSS-RR (which has its own denoise and would
         * just pay the cost without quality gain). Set {@code mode = "off"} to disable; "svgf" forces
         * on regardless of the upscaler.
         */
        public static final class Denoise {
            public static final StringSetting MODE = string("caustica.rt.denoise.mode", "denoise.mode", "auto",
                    v -> {
                        String s = v == null ? "auto" : v.toLowerCase();
                        if (s.equals("auto") || s.equals("off") || s.equals("svgf") || s.equals("on")) {
                            return s.equals("on") ? "svgf" : s; // "on" is a legacy alias for "svgf"
                        }
                        return "auto";
                    });
            public static final FloatSetting SIGMA_DEPTH = clampedFloat("caustica.rt.denoise.sigmaDepth",
                    "denoise.sigma-depth", 0.05f, 0.001f, 1.0f);
            public static final FloatSetting SIGMA_NORMAL = clampedFloat("caustica.rt.denoise.sigmaNormal",
                    "denoise.sigma-normal", 0.1f, 0.01f, 1.0f);
            public static final FloatSetting SIGMA_COLOR = clampedFloat("caustica.rt.denoise.sigmaColor",
                    "denoise.sigma-color", 0.5f, 0.01f, 10.0f);
            public static final FloatSetting TEMPORAL_MAX = clampedFloat("caustica.rt.denoise.temporalMax",
                    "denoise.temporal-max", 0.85f, 0.0f, 0.99f);

            private Denoise() {
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
            public static final FloatSetting KEY = exposureScale("caustica.rt.exposure.key", "exposure.key", 0.18f);
            public static final FloatSetting MIN_EV =
                    finiteFloat("caustica.rt.exposure.minEv", "exposure.min-ev", -1.5f);
            public static final FloatSetting MAX_EV =
                    finiteFloat("caustica.rt.exposure.maxEv", "exposure.max-ev", 2.0f);
            public static final FloatSetting ADAPT_UP =
                    exposureScale("caustica.rt.exposure.adaptUp", "exposure.adapt-up", 0.12f);
            public static final FloatSetting ADAPT_DOWN =
                    exposureScale("caustica.rt.exposure.adaptDown", "exposure.adapt-down", 0.35f);

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
        public static final OptionalStringSetting PATH = optionalString("caustica.ngx.path", "ngx.path");

        private Ngx() {
        }
    }

    /** Upscaler mode (config). The {@code UpscalerSelector.Mode} enum is the resolved mode; this one is the
     *  user-requested mode (which may differ if the device can't run the requested SDK). */
    public enum UpscalerMode {
        OFF("off"),
        AUTO("auto"),
        DLSS_RR("dlss-rr"),
        FSR_3("fsr-3"),
        FSR_4("fsr-4"),
        XESS("xess");

        final String key;
        UpscalerMode(String key) { this.key = key; }
        public String key() { return key; }

        public static UpscalerMode fromKey(String s) {
            if (s == null) return AUTO;
            for (UpscalerMode m : values()) {
                if (m.key.equalsIgnoreCase(s) || m.name().equalsIgnoreCase(s)) return m;
            }
            return AUTO;
        }
    }

    /** Frame gen mode (config). Like {@link UpscalerMode} but for frame gen; the selector may pick a
     *  different resolved mode if the device can't run the requested SDK. */
    public enum FrameGenMode {
        OFF("off"),
        AUTO("auto"),
        DLSSG("dlssg"),
        FSR_3("fsr-3"),
        FSR_4("fsr-4"),
        XESS("xess");

        final String key;
        FrameGenMode(String key) { this.key = key; }
        public String key() { return key; }

        public static FrameGenMode fromKey(String s) {
            if (s == null) return AUTO;
            for (FrameGenMode m : values()) {
                if (m.key.equalsIgnoreCase(s) || m.name().equalsIgnoreCase(s)) return m;
            }
            return AUTO;
        }
    }

    /** Whether the FFX upscaler should be forced to the FSR 3 model even when the bundle ships an FSR 4
     *  model. AUTO picks per-device (RDNA 3 → FSR 4.1 INT8; RDNA 2 / older → FSR 3); FORCE pins the
     *  choice, useful for diagnosing FSR 4 instability. */
    public enum FsrForceMode {
        AUTO("auto"),
        FORCE_FSR_3("force-fsr-3"),
        FORCE_FSR_4("force-fsr-4");

        final String key;
        FsrForceMode(String key) { this.key = key; }
        public String key() { return key; }

        public static FsrForceMode fromKey(String s) {
            if (s == null) return AUTO;
            for (FsrForceMode m : values()) {
                if (m.key.equalsIgnoreCase(s) || m.name().equalsIgnoreCase(s)) return m;
            }
            return AUTO;
        }
    }

    /** Intel XeSS SDK execution path. AUTO picks the best (XMX on Intel, DP4a elsewhere); FORCE_XMX
     *  requires XMX (rejects non-XMX devices — used for diagnosing DP4a issues); FORCE_DP4A pins to DP4a
     *  (broader device support, lower quality). */
    public enum XeSsMode {
        AUTO("auto"),
        FORCE_XMX("force-xmx"),
        FORCE_DP4A("force-dp4a");

        final String key;
        XeSsMode(String key) { this.key = key; }
        public String key() { return key; }

        public static XeSsMode fromKey(String s) {
            if (s == null) return AUTO;
            for (XeSsMode m : values()) {
                if (m.key.equalsIgnoreCase(s) || m.name().equalsIgnoreCase(s)) return m;
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
