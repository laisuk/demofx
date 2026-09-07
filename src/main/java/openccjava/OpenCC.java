package openccjava;

import openccjava.DictionaryMaxlength.DictEntry;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.IntStream;
import java.util.logging.Logger;
import java.util.logging.Level;

import static openccjava.DictRefs.isDelimiter;


/**
 * OpenCC is a pure Java implementation of the Open Chinese Convert (OpenCC) system.
 * It provides simplified-traditional Chinese text conversion using preloaded dictionaries.
 *
 * <p>This class supports multiple configurations such as s2t, t2s, s2tw, tw2s, etc.
 * It loads dictionaries from a bundled JSON file or falls back to raw dict text files.
 *
 * <p>Instances are mutable because the active configuration and diagnostic
 * error state can change. An {@code OpenCC} instance must not be shared across
 * threads while that state may be modified unless callers provide external
 * synchronization. For concurrent applications, prefer one configured instance
 * per thread and treat its dictionary as immutable after construction.</p>
 *
 * <p>Conversion uses a thread-local {@link StringBuilder} to reduce temporary
 * allocations.</p>
 */
public class OpenCC {
    /**
     * Global logger for diagnostic and fallback messages.
     *
     * <p>This logger is shared across all {@link OpenCC} instances and is used to report
     * non-critical events such as dictionary loading, resource fallbacks, or
     * configuration details. Logging is <strong>disabled by default</strong> to prevent
     * unwanted console output in GUI or end-user environments.</p>
     *
     * <p>To enable logging for debugging or integration testing, call
     * {@link #setVerboseLogging(boolean)} with {@code true}.</p>
     */
    static final Logger LOGGER = Logger.getLogger(OpenCC.class.getName());

    static {
        // Disable logging by default to keep console output clean
        LOGGER.setLevel(Level.OFF);
    }

    /**
     * Enables or disables verbose logging for OpenCC.
     *
     * <p>When enabled, the global {@link #LOGGER} will emit informational messages
     * related to dictionary loading, resource fallbacks, and runtime diagnostics.
     * When disabled (the default), all log output is suppressed.</p>
     *
     * <p>This setting affects all {@link OpenCC} instances in the current JVM.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * OpenCC.setVerboseLogging(true);
     * OpenCC converter = new OpenCC("s2t");
     * converter.convert("汉字"); // Logs diagnostic info
     * }</pre>
     *
     * @param enabled {@code true} to enable verbose logging; {@code false} to disable it
     */
    public static void setVerboseLogging(boolean enabled) {
        LOGGER.setLevel(enabled ? Level.INFO : Level.OFF);
    }

    /**
     * The loaded dictionary data, including all phrase and character maps
     * along with their maximum lengths.
     */
    private final DictionaryMaxlength dictionary;

    /**
     * Conversion plans shared for the lifetime of this converter and its dictionary.
     */
    private final ConversionPlanCache conversionPlanCache;

    /**
     * Stores the last error message encountered, if any.
     */
    private String lastError;

    /**
     * Default conversion configuration for OpenCC
     */
    private static final OpenccConfig DEFAULT_CONFIG = OpenccConfig.S2T;

    /**
     * Backing field for default config
     */
    private OpenccConfig configId = DEFAULT_CONFIG;   // ✅ single source of truth

    /**
     * Maximum capacity for thread-local StringBuilder (used during conversion).
     */
    private static final int MAX_SB_CAPACITY = 1024;

    /**
     * Thread-local StringBuilder to minimize memory allocations.
     */
    private static final ThreadLocal<StringBuilder> threadLocalSb =
            ThreadLocal.withInitial(() -> new StringBuilder(MAX_SB_CAPACITY));

    /**
     * Provides a lazily initialized, thread-safe singleton instance of
     * {@link DictionaryMaxlength}.
     *
     * <p>This holder follows the <em>Initialization-on-demand holder idiom</em>:
     * the dictionary is not loaded until the first call to {@link #get()}.
     * This guarantees that:</p>
     * <ul>
     *   <li>The dictionary is loaded exactly once per JVM.</li>
     *   <li>Access is thread-safe without requiring explicit synchronization.</li>
     *   <li>Subsequent calls to {@code get()} return the same shared instance.</li>
     * </ul>
     *
     * <p>The dictionary is loaded in the following order:</p>
     * <ol>
     *   <li><b>JSON file from the file system:</b>
     *       {@code dicts/dictionary_maxlength.json} in the current working directory.</li>
     *   <li><b>Embedded JSON resource:</b>
     *       {@code /dicts/dictionary_maxlength.json} from the application’s classpath
     *       (e.g. inside the JAR).</li>
     *   <li><b>Plain text fallback:</b>
     *       If neither JSON source is found, falls back to loading individual
     *       dictionary text files via {@link DictionaryMaxlength#fromDicts()}.</li>
     * </ol>
     *
     * <p>If all attempts fail, a {@link RuntimeException} is thrown.</p>
     *
     * <b>Usage Example:</b>
     * <pre>{@code
     * DictionaryMaxlength dict = OpenCC.DictionaryHolder.get();
     * }</pre>
     */
    public static final class DictionaryHolder {
        private DictionaryHolder() {
        }

        /**
         * Internal holder for the lazily initialized singleton.
         */
        private static class Holder {
            private static final DictionaryMaxlength DEFAULT = load();
        }

        /**
         * Returns the shared singleton {@link DictionaryMaxlength} instance.
         * The instance is created on first invocation.
         *
         * @return the shared dictionary instance
         * @throws RuntimeException if dictionary loading fails
         */
        public static DictionaryMaxlength get() {
            return Holder.DEFAULT;
        }

        /**
         * Attempts to load the dictionary from JSON (file system or classpath),
         * falling back to plain-text sources if necessary.
         *
         * @return a fully loaded {@link DictionaryMaxlength}
         * @throws RuntimeException if no dictionary source can be loaded
         */
        private static DictionaryMaxlength load() {
            try {
                Path jsonPath = Paths.get("dicts", "dictionary_maxlength.json");
                if (Files.exists(jsonPath)) {
                    return DictionaryMaxlength.fromJsonNoDeps(jsonPath);
                }
                try (InputStream in = DictionaryMaxlength.class.getResourceAsStream(
                        "/dicts/dictionary_maxlength.json")) {
                    if (in != null) return DictionaryMaxlength.fromJsonNoDeps(in);
                    return DictionaryMaxlength.fromDicts();
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to load dictionaries", e);
            }
        }
    }

    // ---------- Static helpers ----------

    /**
     * Creates a new {@link OpenCC} instance from a configuration string.
     *
     * <p>The provided {@code config} string is parsed in a case-insensitive manner
     * using {@link OpenccConfig#tryParse(String)}. If the string does not correspond
     * to any supported configuration, the default configuration
     * ({@code s2t}) is used instead.</p>
     *
     * <p>This method never throws due to an invalid configuration string.
     * The effective configuration can be queried via {@link #getConfig()}
     * or {@link #getConfigId()}.</p>
     *
     * @param config the configuration key (e.g. {@code "s2t"}, {@code "S2TWP"}, {@code "tw2s"});
     *               may be {@code null}
     * @return a new {@link OpenCC} instance using the parsed configuration,
     * or the default configuration if parsing fails
     * @since 1.1.0
     */
    public static OpenCC fromConfig(String config) {
        return new OpenCC(OpenccConfig.tryParse(config));
    }

    /**
     * Creates a new {@link OpenCC} instance with the specified configuration ID.
     *
     * <p>If {@code configId} is {@code null}, the default configuration
     * ({@code s2t}) is used.</p>
     *
     * @param configId the configuration ID, or {@code null} to use the default
     * @return a new {@link OpenCC} instance using the specified (or defaulted) configuration
     * @since 1.1.0
     */
    public static OpenCC fromConfig(OpenccConfig configId) {
        return new OpenCC(configId);
    }

    /**
     * Creates an {@link OpenCC} instance with the default configuration
     * ({@code s2t}) and custom dictionary files.
     *
     * <p>This method loads a caller-owned {@link DictionaryMaxlength} through
     * {@link DictionaryMaxlength#fromDicts(java.util.List)}. It does not use or
     * modify {@link DictionaryHolder}. If {@code specs} is {@code null} or
     * empty, the returned converter uses a separately loaded dictionary without
     * custom patches.</p>
     *
     * <p>Custom dictionary files are parsed with the same parser used for
     * built-in OpenCC text dictionaries. After construction, the converter is
     * immutable for normal conversion use and does not hot-reload custom files.</p>
     *
     * @param specs custom dictionary specs to apply; may be {@code null} or empty
     * @return a new converter using a caller-owned dictionary
     * @throws RuntimeException     if an official or custom dictionary file cannot be loaded
     * @throws NullPointerException if {@code specs} contains a {@code null} spec
     */
    public static OpenCC fromDicts(List<CustomDictSpec> specs) {
        return fromDicts(DEFAULT_CONFIG, specs);
    }

    /**
     * Creates an {@link OpenCC} instance with a typed configuration and custom
     * dictionary files.
     *
     * <p>This method loads a caller-owned {@link DictionaryMaxlength} through
     * {@link DictionaryMaxlength#fromDicts(java.util.List)}. It does not use or
     * modify {@link DictionaryHolder}. If {@code specs} is {@code null} or
     * empty, the returned converter uses a separately loaded dictionary without
     * custom patches.</p>
     *
     * <p>If {@code config} is {@code null}, the default configuration
     * ({@code s2t}) is used. Custom dictionary files are parsed with the same
     * parser used for built-in OpenCC text dictionaries. After construction,
     * the converter is immutable for normal conversion use and does not
     * hot-reload custom files.</p>
     *
     * @param config the configuration ID, or {@code null} to use the default
     * @param specs  custom dictionary specs to apply; may be {@code null} or empty
     * @return a new converter using a caller-owned dictionary
     * @throws RuntimeException     if an official or custom dictionary file cannot be loaded
     * @throws NullPointerException if {@code specs} contains a {@code null} spec
     */
    public static OpenCC fromDicts(
            OpenccConfig config,
            List<CustomDictSpec> specs
    ) {
        DictionaryMaxlength dict =
                DictionaryMaxlength.fromDicts(specs);

        return new OpenCC(config, dict);
    }

    /**
     * Creates an {@link OpenCC} instance from a custom official dictionary
     * directory and custom dictionary files.
     *
     * <p>This method loads a caller-owned {@link DictionaryMaxlength} through
     * {@link DictionaryMaxlength#fromDicts(String, java.util.List)}. It does
     * not use or modify {@link DictionaryHolder}. If {@code specs} is
     * {@code null} or empty, the returned converter uses a separately loaded
     * dictionary from {@code basePath} without custom patches.</p>
     *
     * <p>If {@code config} is {@code null}, the default configuration
     * ({@code s2t}) is used. Custom dictionary files are parsed with the same
     * parser used for built-in OpenCC text dictionaries. After construction,
     * the converter is immutable for normal conversion use and does not
     * hot-reload custom files.</p>
     *
     * @param config   the configuration ID, or {@code null} to use the default
     * @param basePath the path to the directory containing official dictionary
     *                 {@code .txt} files; must not be {@code null}
     * @param specs    custom dictionary specs to apply; may be {@code null} or empty
     * @return a new converter using a caller-owned dictionary
     * @throws RuntimeException     if an official or custom dictionary file cannot be loaded
     * @throws NullPointerException if {@code basePath} is {@code null} or
     *                              {@code specs} contains a {@code null} spec
     */
    public static OpenCC fromDicts(
            OpenccConfig config,
            String basePath,
            List<CustomDictSpec> specs
    ) {
        DictionaryMaxlength dict =
                DictionaryMaxlength.fromDicts(basePath, specs);

        return new OpenCC(config, dict);
    }

    // ---------- Instance constructors + API ----------

    /**
     * Constructs an {@code OpenCC} instance using the default configuration
     * ({@code s2t}).
     *
     * <p>This is equivalent to {@code new OpenCC(OpenccConfig.S2T)}.</p>
     *
     * @since 1.0.0
     */
    public OpenCC() {
        this(OpenccConfig.S2T);
    }

    /**
     * Constructs an {@code OpenCC} instance using a configuration string.
     *
     * <p>The provided {@code config} string is parsed in a case-insensitive manner
     * via {@link OpenccConfig#tryParse(String)}. If the string is {@code null},
     * empty, or does not correspond to any supported configuration, the default
     * configuration ({@code s2t}) is used.</p>
     *
     * <p>This constructor does <em>not</em> reload dictionaries for each instance.
     * Instead, it retrieves a shared singleton {@link DictionaryMaxlength}
     * from {@link DictionaryHolder}. The dictionary is loaded lazily on first access
     * and reused by all subsequent {@code OpenCC} instances.</p>
     *
     * <p>The shared dictionary is resolved in the following order
     * (performed only once per JVM):</p>
     *
     * <ol>
     *   <li>
     *     <b>JSON file from the file system:</b><br>
     *     {@code dicts/dictionary_maxlength.json} in the current working directory.
     *   </li>
     *   <li>
     *     <b>Embedded JSON resource:</b><br>
     *     {@code /dicts/dictionary_maxlength.json} available on the application
     *     classpath (for example, packaged inside the JAR).
     *   </li>
     *   <li>
     *     <b>Plain-text fallback:</b><br>
     *     If neither JSON source is found, individual dictionary text files are
     *     loaded from {@code dicts/} using
     *     {@link DictionaryMaxlength#fromDicts()}.
     *   </li>
     * </ol>
     *
     * <p><b>Important:</b> Because the dictionary is a shared singleton,
     * any modification to its contents (for example, adding or removing entries)
     * will affect <em>all</em> {@code OpenCC} instances within the same JVM.</p>
     *
     * <p>If no dictionary source can be loaded or parsed, a
     * {@link RuntimeException} is thrown before the instance can be created.</p>
     *
     * @param config the configuration key (for example {@code "s2t"},
     *               {@code "S2TWP"}, {@code "tw2sp"}); may be {@code null}
     * @throws RuntimeException if no dictionary source can be loaded or parsed
     * @since 1.1.0
     */
    public OpenCC(String config) {
        this(OpenccConfig.tryParse(config));
    }

    /**
     * Constructs an {@code OpenCC} instance using a typed configuration ID.
     *
     * <p>If {@code configId} is {@code null}, the default configuration
     * ({@code s2t}) is used.</p>
     *
     * <p>The underlying dictionary is obtained from {@link DictionaryHolder}
     * and shared across all {@code OpenCC} instances. Dictionary loading
     * is performed lazily on first access.</p>
     *
     * @param configId the configuration ID, or {@code null} to use the default
     * @throws RuntimeException if no dictionary source can be loaded or parsed
     * @since 1.1.0
     */
    public OpenCC(OpenccConfig configId) {
        this.dictionary = DictionaryHolder.get(); // Lazy static, loaded on first access
        this.conversionPlanCache = ConversionPlanCache.forDictionary(this.dictionary);
        setConfig(configId);
    }

    /**
     * Constructs an {@code OpenCC} instance using the default configuration
     * ({@code s2t}) and a caller-supplied dictionary.
     *
     * <p>The supplied {@link DictionaryMaxlength} is used directly. This
     * constructor does not use or modify {@link DictionaryHolder}. It is
     * intended for dictionaries built with custom files or loaded by the caller.</p>
     *
     * <p>After construction, normal conversion is immutable and fast. To change
     * custom dictionary contents, build a new {@link DictionaryMaxlength} and
     * create a new {@code OpenCC} instance.</p>
     *
     * @param dictionary the dictionary to use; must not be {@code null}
     * @throws NullPointerException if {@code dictionary} is {@code null}
     */
    public OpenCC(DictionaryMaxlength dictionary) {
        this(DEFAULT_CONFIG, dictionary);
    }

    /**
     * Constructs an {@code OpenCC} instance using a typed configuration and a
     * caller-supplied dictionary.
     *
     * <p>The supplied {@link DictionaryMaxlength} is used directly. This
     * constructor does not use or modify {@link DictionaryHolder}. It is
     * intended for dictionaries built with custom files or loaded by the caller.</p>
     *
     * <p>If {@code config} is {@code null}, the default configuration
     * ({@code s2t}) is used. After construction, normal conversion is immutable
     * and fast. To change custom dictionary contents, build a new
     * {@link DictionaryMaxlength} and create a new {@code OpenCC} instance.</p>
     *
     * @param config     the configuration ID, or {@code null} to use the default
     * @param dictionary the dictionary to use; must not be {@code null}
     * @throws NullPointerException if {@code dictionary} is {@code null}
     */
    public OpenCC(OpenccConfig config, DictionaryMaxlength dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.conversionPlanCache = ConversionPlanCache.forDictionary(this.dictionary);
        setConfig(config);
    }

    /**
     * Constructs an {@code OpenCC} instance using a configuration string and
     * custom dictionary specifications.
     *
     * <p>The provided {@code config} string is parsed via
     * {@link OpenccConfig#tryParse(String)}. If the string is {@code null},
     * empty, or invalid, the default configuration ({@code s2t}) is used.</p>
     *
     * <p>The shared dictionary from {@link DictionaryHolder} is copied first,
     * then {@code customSpecs} are applied to the copy. The shared dictionary
     * itself is never modified.</p>
     *
     * @param config      the configuration key (for example {@code "s2t"} or
     *                    {@code "hk2sp"}); may be {@code null}
     * @param customSpecs custom dictionary specs to apply; may be {@code null}
     *                    or empty
     * @throws RuntimeException     if a custom dictionary file cannot be loaded
     * @throws NullPointerException if {@code customSpecs} contains a {@code null}
     *                              spec
     */
    public OpenCC(String config, List<CustomDictSpec> customSpecs) {
        this(OpenccConfig.tryParse(config), customSpecs);
    }

    /**
     * Constructs an {@code OpenCC} instance using a typed configuration and
     * custom dictionary specifications.
     *
     * <p>The shared dictionary from {@link DictionaryHolder} is copied first,
     * then {@code customSpecs} are applied to the copy. The shared dictionary
     * itself is never modified.</p>
     *
     * <p>If {@code config} is {@code null}, the default configuration
     * ({@code s2t}) is used.</p>
     *
     * @param config      the configuration ID, or {@code null} to use the default
     * @param customSpecs custom dictionary specs to apply; may be {@code null}
     *                    or empty
     * @throws RuntimeException     if a custom dictionary file cannot be loaded
     * @throws NullPointerException if {@code customSpecs} contains a {@code null}
     *                              spec
     */
    public OpenCC(OpenccConfig config, List<CustomDictSpec> customSpecs) {
        this(
                config,
                DictionaryHolder.get().withCustomDicts(customSpecs)
        );
    }

    /**
     * Constructs an OpenCC instance using plain text dictionaries from the given directory.
     * <p>
     * <strong>Deprecated:</strong> This constructor will be removed in the next major version.
     * Prefer loading dictionaries explicitly through
     * {@link DictionaryMaxlength#fromDicts(String)} and passing the resulting
     * {@link DictionaryMaxlength} to
     * {@link #OpenCC(OpenccConfig, DictionaryMaxlength)} or
     * {@link #OpenCC(DictionaryMaxlength)}.
     * </p>
     *
     * @param config   the conversion configuration key to use
     * @param dictPath the path to the text dictionary directory
     * @deprecated Prefer loading dictionaries explicitly through
     * {@link DictionaryMaxlength#fromDicts(String)} and passing the resulting
     * {@link DictionaryMaxlength} to
     * {@link #OpenCC(OpenccConfig, DictionaryMaxlength)} or
     * {@link #OpenCC(DictionaryMaxlength)}.
     * This overload will be removed in the next major version.
     */
    @Deprecated
    public OpenCC(String config, Path dictPath) {
        try {
            this.dictionary = DictionaryMaxlength.fromDicts(dictPath.toString());
        } catch (Exception e) {
            this.lastError = e.getMessage();
            throw new RuntimeException("Failed to load text dictionaries from: " + dictPath, e);
        }

        this.conversionPlanCache = ConversionPlanCache.forDictionary(this.dictionary);
        setConfig(config);
    }

    /**
     * Sets the current conversion configuration using a configuration string.
     *
     * <p>The provided {@code config} is parsed in a case-insensitive manner.
     * If {@code config} is {@code null}, empty, or invalid, the default
     * configuration ({@code s2t}) is applied and {@link #getLastError()} is set.</p>
     *
     * <p>On success, {@link #getLastError()} is cleared.</p>
     *
     * <p>This method mutates the converter and must not run concurrently with
     * conversion or state-access operations on the same instance unless callers
     * provide external synchronization.</p>
     *
     * @param config configuration key (e.g. {@code "s2t"}, {@code "S2TWP"}); may be {@code null}
     */
    public void setConfig(String config) {
        setConfig(OpenccConfig.tryParse(config));
    }

    /**
     * Sets the current conversion configuration using a typed configuration ID.
     *
     * <p>If {@code cfg} is {@code null}, the default configuration ({@code s2t})
     * is applied and {@link #getLastError()} is set.</p>
     *
     * <p>On success, {@link #getLastError()} is cleared.</p>
     *
     * <p>This method mutates the converter and must not run concurrently with
     * conversion or state-access operations on the same instance unless callers
     * provide external synchronization.</p>
     *
     * @param cfg configuration ID, or {@code null} to use default
     */
    public void setConfig(OpenccConfig cfg) {
        if (cfg != null) {
            this.configId = cfg;
            this.lastError = null;
        } else {
            this.configId = DEFAULT_CONFIG;
            this.lastError = "Invalid config: null. Using default '" + DEFAULT_CONFIG.toCanonicalName() + "'.";
        }
    }

    /**
     * Returns the current conversion configuration in canonical string form.
     *
     * <p>The returned value is the lowercase, canonical configuration name
     * (for example {@code "s2t"}, {@code "t2twp"}, {@code "tw2sp"}).
     * This method never returns {@code null}.</p>
     *
     * <p>This method is provided for compatibility with string-based APIs
     * (such as CLI options, configuration files, and legacy integrations).
     * For type-safe access, prefer {@link #getConfigId()}.</p>
     *
     * @return the canonical configuration key representing the current conversion mode
     */
    public String getConfig() {
        return configId.toCanonicalName();
    }

    /**
     * Returns the current conversion configuration as a typed enum ID.
     *
     * <p>This is the authoritative, type-safe representation of the active
     * configuration and serves as the single source of truth for all internal
     * conversion logic.</p>
     *
     * <p>The returned value is never {@code null}. If an invalid configuration
     * was previously provided, this method returns the default configuration
     * ({@code s2t}).</p>
     *
     * @return the active {@link OpenccConfig} identifier
     */
    public OpenccConfig getConfigId() {
        return configId;
    }

    /**
     * Checks whether a configuration string corresponds to a supported
     * OpenCC conversion configuration.
     *
     * <p>The check is case-insensitive and accepts both canonical names
     * (for example {@code "s2t"}, {@code "t2twp"}) and enum-style names
     * (for example {@code "S2T"}, {@code "T2TWP"}).</p>
     *
     * <p>This method performs tolerant parsing and never throws.</p>
     *
     * @param value the configuration string to check; may be {@code null}
     * @return {@code true} if the configuration is supported; {@code false} otherwise
     */
    public static boolean isSupportedConfig(String value) {
        return OpenccConfig.isValidConfig(value);
    }

    /**
     * Returns an immutable list of all supported configuration keys
     * in canonical string form.
     *
     * <p>The returned list contains lowercase configuration names such as
     * {@code "s2t"}, {@code "tw2sp"}, {@code "t2twp"}.
     * The order of the list matches the declaration order of
     * {@link OpenccConfig}.</p>
     *
     * <p>This method never returns {@code null}.</p>
     *
     * @return an unmodifiable list of supported configuration keys
     */
    public static List<String> getSupportedConfigs() {
        return OpenccConfig.supportedCanonicalNames();
    }

    /**
     * Returns an immutable list of all supported configuration IDs.
     *
     * <p>This method exposes the full set of {@link OpenccConfig} enum constants
     * and is intended for type-safe usage such as UI bindings, configuration
     * selectors, or programmatic inspection.</p>
     *
     * <p>The returned list is backed by the enum values and should be treated
     * as read-only.</p>
     *
     * @return an unmodifiable list of supported {@link OpenccConfig} identifiers
     */
    public static List<OpenccConfig> getSupportedConfigIds() {
        return Collections.unmodifiableList(Arrays.asList(OpenccConfig.values()));
    }

    /**
     * Returns the most recent non-fatal error message recorded by this instance.
     *
     * <p>The {@code lastError} value represents the most recent recoverable
     * issue encountered during configuration changes or other operations
     * that did not throw an exception. Typical examples include:</p>
     *
     * <ul>
     *   <li>Providing an invalid or unsupported configuration string</li>
     *   <li>Falling back to the default configuration due to invalid input</li>
     * </ul>
     *
     * <p>{@code lastError} does <em>not</em> indicate a failed conversion,
     * nor does it imply that the {@code OpenCC} instance is in an invalid state.
     * All conversions proceed using a valid configuration.</p>
     *
     * <p>The value is cleared by a successful call to {@code setConfig(...)} or
     * manually via {@link #clearLastError()}. Successful conversion does not
     * clear a previously recorded diagnostic.</p>
     *
     * @return the most recent error message, or {@code null} if no error
     * has been recorded
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Clears the currently stored non-fatal error message, if any.
     *
     * <p>This method has no effect on the active configuration or conversion
     * behavior. It simply resets the error state used for diagnostic or
     * user-facing reporting.</p>
     *
     * <p>This method is typically used by UI or CLI code after an error
     * has been displayed to the user.</p>
     *
     * <p>This method mutates diagnostic state and must not run concurrently with
     * other operations on the same instance without external synchronization.</p>
     */
    public void clearLastError() {
        this.lastError = null;
    }

    /**
     * Returns whether a non-fatal error message is currently recorded.
     *
     * <p>This is a convenience method equivalent to
     * {@code getLastError() != null}.</p>
     *
     * <p>A return value of {@code true} does <em>not</em> imply that the
     * last conversion failed or that the instance is unusable; it only
     * indicates that a recoverable issue was recorded.</p>
     *
     * @return {@code true} if an error message is present; {@code false} otherwise
     */
    public boolean hasLastError() {
        return this.lastError != null;
    }

    /**
     * Converts the given input text using the current conversion configuration.
     *
     * <p>This is a convenience overload equivalent to calling
     * {@link #convert(String, boolean)} with {@code punctuation} set to {@code false}.
     * Only character and phrase conversion is applied; punctuation characters
     * are left unchanged.</p>
     *
     * <p>If the input is {@code null} or empty, no conversion is performed.
     * In that case, an explanatory message is recorded in {@link #getLastError()}
     * and returned as the result.</p>
     *
     * @param input the text to convert; may be {@code null}
     * @return the converted text, or an error message if the input is invalid
     */
    public String convert(String input) {
        return convert(input, false); // default punctuation = false
    }

    /**
     * Converts the given input text using the current conversion configuration.
     *
     * <p>The conversion behavior is determined by the active configuration
     * ({@link #getConfigId()}), such as Simplified → Traditional ({@code s2t}),
     * Traditional → Simplified ({@code t2s}), or region-specific variants
     * (Taiwan, Hong Kong, Japan).</p>
     *
     * <p>If {@code punctuation} is {@code true}, punctuation characters are
     * converted using the corresponding punctuation dictionaries where applicable.
     * If {@code false}, punctuation is preserved as-is.</p>
     *
     * <p>This method never throws due to an invalid configuration.
     * The active configuration is always valid; if an invalid configuration
     * was previously supplied, the default configuration ({@code s2t})
     * is used instead.</p>
     *
     * <p>If {@code input} is {@code null} or empty, no conversion is performed.
     * An explanatory message is recorded in {@link #getLastError()} and returned
     * as the result.</p>
     *
     * <p>The returned string always reflects the conversion result of a valid
     * configuration or a human-readable error message for invalid input.
     * A recorded error does <em>not</em> indicate that the {@code OpenCC}
     * instance is unusable.</p>
     *
     * @param input       the text to convert; may be {@code null}
     * @param punctuation whether to convert punctuation characters in addition
     *                    to text conversion
     * @return the converted text, or an error message if the input is invalid
     */
    public String convert(String input, boolean punctuation) {
        if (input == null || input.isEmpty()) {
            lastError = "Input text is null or empty";
            return lastError;
        }

        String result;
        switch (configId) {
            case S2T:
                result = s2t(input, punctuation);
                break;
            case T2S:
                result = t2s(input, punctuation);
                break;
            case S2TW:
                result = s2tw(input, punctuation);
                break;
            case TW2S:
                result = tw2s(input, punctuation);
                break;
            case S2TWP:
                result = s2twp(input, punctuation);
                break;
            case TW2SP:
                result = tw2sp(input, punctuation);
                break;
            case S2HKP:
                result = s2hkp(input, punctuation);
                break;
            case HK2SP:
                result = hk2sp(input, punctuation);
                break;
            case S2HK:
                result = s2hk(input, punctuation);
                break;
            case HK2S:
                result = hk2s(input, punctuation);
                break;

            case T2TW:
                result = t2tw(input);
                break;
            case T2TWP:
                result = t2twp(input);
                break;
            case TW2T:
                result = tw2t(input);
                break;
            case TW2TP:
                result = tw2tp(input);
                break;
            case T2HK:
                result = t2hk(input);
                break;
            case T2HKP:
                result = t2hkp(input);
                break;
            case HK2T:
                result = hk2t(input);
                break;
            case HK2TP:
                result = hk2tp(input);
                break;

            case T2JP:
                result = t2jp(input);
                break;
            case JP2T:
                result = jp2t(input);
                break;

            default:
                // Should be unreachable unless new enum values are added
                // without updating this switch.
                lastError = "Unsupported config id: " + configId;
                result = lastError;
                break;
        }

        return result;
    }

    // Static convenience methods

    /**
     * Converts text using the specified configuration.
     *
     * <p>This is a convenience API for one-off conversions. Internally it
     * creates a temporary {@code OpenCC} instance backed by the shared
     * dictionary cache provided by {@link DictionaryHolder}. Dictionaries
     * are still loaded only once per JVM.</p>
     *
     * <p>For repeated conversions, custom dictionaries, or advanced usage,
     * consider creating and reusing an {@code OpenCC} instance directly.</p>
     *
     * <p>If {@code config} is {@code null}, the default configuration
     * ({@code s2t}) is used.</p>
     *
     * <p>Because this method is overloaded with a {@link String} configuration
     * variant, an untyped {@code null} argument is ambiguous in Java source.
     * Use {@link OpenccConfig#defaultConfig()} when explicitly requesting the
     * default, or pass a variable typed as {@code OpenccConfig}.</p>
     *
     * <b>Example:</b>
     * <pre>{@code
     * String result = OpenCC.convert(
     *         "欢迎使用OpenCC",
     *         OpenccConfig.S2T);
     * }</pre>
     *
     * <p>If {@code text} is {@code null} or empty, the conversion is not
     * performed and the diagnostic string {@code "Input text is null or empty"}
     * is returned, consistent with {@link #convert(String)}.</p>
     *
     * @param text   the text to convert; may be {@code null} or empty
     * @param config the configuration ID, or {@code null} to use the default
     * @return the converted text, or a diagnostic string if {@code text} is
     * {@code null} or empty
     * @since 1.4.1
     */
    public static String convert(
            String text,
            OpenccConfig config
    ) {
        return new OpenCC(config).convert(text);
    }

    /**
     * Converts text using the specified configuration key.
     *
     * <p>This is a convenience API for one-off conversions. Internally it
     * creates a temporary {@code OpenCC} instance backed by the shared
     * dictionary cache provided by {@link DictionaryHolder}. Dictionaries
     * are still loaded only once per JVM.</p>
     *
     * <p>The provided {@code config} string is parsed in a case-insensitive
     * manner using {@link OpenccConfig#tryParse(String)}. If the string does
     * not correspond to a supported configuration, the default configuration
     * ({@code s2t}) is used instead.</p>
     *
     * <p>Because this method is overloaded with an {@link OpenccConfig}
     * variant, an untyped {@code null} argument is ambiguous in Java source.
     * Pass {@code "s2t"} when explicitly requesting the default through this
     * overload, or pass a variable typed as {@code String}.</p>
     *
     * <p>For repeated conversions, custom dictionaries, or advanced usage,
     * consider creating and reusing an {@code OpenCC} instance directly.</p>
     *
     * <b>Example:</b>
     * <pre>{@code
     * String result = OpenCC.convert(
     *         "欢迎使用OpenCC",
     *         "s2t");
     * }</pre>
     *
     * <p>If {@code text} is {@code null} or empty, the conversion is not
     * performed and the diagnostic string {@code "Input text is null or empty"}
     * is returned, consistent with {@link #convert(String)}.</p>
     *
     * @param text   the text to convert; may be {@code null} or empty
     * @param config the configuration key (e.g. {@code "s2t"},
     *               {@code "t2s"}, {@code "s2twp"}); may be {@code null}
     * @return the converted text, or a diagnostic string if {@code text} is
     * {@code null} or empty
     * @since 1.4.1
     */
    public static String convert(
            String text,
            String config
    ) {
        return new OpenCC(config).convert(text);
    }

    /**
     * Retrieves the {@link DictRefs} for the given configuration and punctuation mode,
     * including attached {@link StarterUnion}s.
     *
     * @param cfg         the conversion configuration
     * @param punctuation whether punctuation conversion is enabled
     * @return the prepared {@link DictRefs} for this configuration
     */
    private DictRefs getDictRefsUnionForConfigId(OpenccConfig cfg, boolean punctuation) {
        return conversionPlanCache.getPlan(cfg, punctuation);
    }

    /**
     * Applies dictionary-based replacements to the input text using segment-based processing.
     *
     * <p>If the text contains multiple segments (e.g., based on punctuation/delimiters),
     * each segment is processed separately. Large inputs are processed in parallel for performance.
     *
     * @param text      the original text to convert
     * @param dicts     the list of dictionaries (in order of priority) to apply
     * @param maxLength the maximum phrase length to match in the dictionaries
     * @return the converted text
     */
    public String segmentReplace(String text, List<DictEntry> dicts, int maxLength) {
        if (text == null || text.isEmpty()) return text;

        List<int[]> ranges = getSplitRanges(text, true);
        int numSegments = ranges.size();
        int textLength = text.length();

        // Fast path: entire text is one uninterrupted segment
        if (numSegments == 1 &&
                ranges.get(0)[0] == 0 &&
                ranges.get(0)[1] == textLength) {
            return convertSegment(text, dicts, maxLength);
        }

        // Use parallel stream if input is large or highly segmented
        boolean useParallel = textLength > 10_000 || numSegments > 100;
        int sbCapacity = textLength + (textLength >> 4);
        StringBuilder sb = new StringBuilder(sbCapacity);

        if (useParallel) {
            String[] segments = new String[numSegments];

            IntStream.range(0, numSegments).parallel().forEach(i -> {
                int[] range = ranges.get(i);
                String segment = text.substring(range[0], range[1]);
                segments[i] = convertSegment(segment, dicts, maxLength);
            });

            // Join all converted segments
            for (String seg : segments) {
                sb.append(seg);
            }
        } else {
            // Fallback: sequential processing
            for (int[] range : ranges) {
                String segment = text.substring(range[0], range[1]);
                sb.append(convertSegment(segment, dicts, maxLength));
            }
        }
        return sb.toString();
    }

    /**
     * Converts a single segment using the specified dictionaries with longest-match-first strategy.
     *
     * <p>If the segment is a single delimiter character, it is returned as-is.
     * Otherwise, the method performs greedy matching from left to right,
     * trying to match the longest possible substrings found in the dictionaries.
     *
     * <p>This method reuses a thread-local {@code StringBuilder} to reduce allocations.
     *
     * @param segment   the text segment to convert
     * @param dicts     the list of dictionaries to apply (highest priority first)
     * @param maxLength the maximum phrase length to consider
     * @return the converted segment
     */
    public static String convertSegment(String segment, List<DictEntry> dicts, int maxLength) {
        if (segment.length() == 1 && isDelimiter(segment.charAt(0))) {
            return segment;
        }

        int segLen = segment.length();
        StringBuilder sb = threadLocalSb.get();
        sb.setLength(0); // reset for reuse
        sb.ensureCapacity(segLen + (segLen >> 4));  // ~+6.25%

        int i = 0;
        while (i < segLen) {
            int bestLen = 0;
            String bestMatch = null;
            int maxScanLen = Math.min(maxLength, segLen - i);

            for (int len = maxScanLen; len > 0; len--) {
                final int end = i + len;
                String word = segment.substring(i, end);
                for (DictEntry entry : dicts) {
                    if (entry.maxLength < len || entry.minLength > len) continue;

                    String value = entry.dict.get(word);
                    if (value != null) {
                        bestMatch = value;
                        bestLen = len;
                        break; // found match, stop searching this word
                    }
                }
                if (bestMatch != null) break; // found match, move to next position
            }

            if (bestMatch != null) {
                sb.append(bestMatch);
                i += bestLen;
            } else {
                sb.append(segment.charAt(i));
                i++;
            }
        }

        return sb.toString();
    }

    /**
     * Splits the input text into a list of index ranges based on delimiter characters.
     *
     * <p>This method is used to divide long text into smaller segments for conversion.
     * A segment is defined as a contiguous run of non-delimiter characters, optionally
     * followed by or isolated with a delimiter depending on the {@code inclusive} flag.
     *
     * <p>Each returned {@code int[]} contains two elements:
     * [start index (inclusive), end index (exclusive)].
     *
     * @param text      the input text to segment
     * @param inclusive whether to include delimiters as part of the preceding segment ({@code true})
     *                  or as separate segments ({@code false})
     * @return a list of (start, end) index ranges representing the segments
     */
    private static List<int[]> getSplitRanges(String text, boolean inclusive) {
        List<int[]> result = new ArrayList<>();
        int start = 0;
        final int textLength = text.length(); // Cache text length

        for (int i = 0; i < textLength; i++) {
            if (isDelimiter(text.charAt(i))) {
                if (inclusive) {
                    // Optimized: Directly add the inclusive range
                    result.add(new int[]{start, i + 1});
                } else {
                    // Optimized: Avoid adding empty ranges if i == start
                    if (i > start) {
                        result.add(new int[]{start, i});     // before delimiter
                    }
                    result.add(new int[]{i, i + 1});         // delimiter itself
                }
                start = i + 1; // Update start for the next segment
            }
        }

        // Add the last segment if it exists
        if (start < textLength) {
            result.add(new int[]{start, textLength});
        }

        return result;
    }

    /**
     * Performs dictionary-based segment replacement using a starter union and
     * cached phrase/single partitioning.
     *
     * <p>This method accelerates conversion by:</p>
     * <ul>
     *   <li>Reusing the per-round partition cached in {@link DictRefs.DictPartition}</li>
     *   <li>Splitting the input text into independent ranges using
     *       {@link #getSplitRanges(String, boolean)}.</li>
     *   <li>Passing each segment to {@code convertSegmentWithUnion}, which uses
     *       the supplied {@link StarterUnion} to skip impossible starters quickly.</li>
     * </ul>
     *
     * <p>Execution mode:</p>
     * <ul>
     *   <li><b>Parallel</b> - if the input text exceeds 100,000 characters or
     *       there are more than 1,000 split segments, segments are processed
     *       in parallel using {@link IntStream#parallel()}.</li>
     *   <li><b>Sequential</b> - otherwise, segments are processed in order
     *       in a single thread.</li>
     * </ul>
     *
     * <p>If the text is {@code null} or empty, it is returned unchanged.</p>
     *
     * @param text the input text
     * @param part the cached partition metadata for the round
     * @return the converted text
     */
    public String segmentReplaceWithUnion(String text, DictRefs.DictPartition part) {
        if (text == null || text.isEmpty()) return text;
        if (part.phraseDicts.isEmpty() && part.singleDicts.isEmpty()) return text;

        int textLen = text.length();
        List<int[]> ranges = getSplitRanges(text, true);
        int numSegments = ranges.size();

        if (numSegments == 1 &&
                ranges.get(0)[0] == 0 &&
                ranges.get(0)[1] == textLen) {
            return convertSegmentWithUnion(text, part);
        }

        boolean useParallel = textLen > 100_000 || numSegments > 1_000;
        int sbCapacity = textLen + (textLen >> 4);
        StringBuilder sb = new StringBuilder(sbCapacity);

        if (useParallel) {
            String[] segments = new String[numSegments];

            IntStream.range(0, numSegments).parallel().forEach(i -> {
                int[] range = ranges.get(i);
                String seg = text.substring(range[0], range[1]);
                segments[i] = convertSegmentWithUnion(seg, part);
            });

            for (String seg : segments) sb.append(seg);
        } else {
            for (int[] range : ranges) {
                String segment = text.substring(range[0], range[1]);
                sb.append(convertSegmentWithUnion(segment, part));
            }
        }
        return sb.toString();
    }

    // Internal: faster converter for a single segment using pre-partitioned dicts

    /**
     * Converts a single text segment using pre-partitioned dictionaries and an optional {@link StarterUnion}.
     *
     * <h3>Overview</h3>
     * This method implements the OpenCC-style greedy, phrase-first replacement algorithm with several
     * optimizations to minimize unnecessary lookups. It processes the input string left to right, emitting
     * replacements or original code points as appropriate.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li><b>Starter pre-check:</b>
     *       If a {@link StarterUnion} is provided and the current code point is not present in its starter
     *       mask, the code point is copied directly without performing any dictionary lookups.</li>
     *   <li><b>Phrase-first search (greedy):</b>
     *       <ul>
     *         <li>Candidate lengths are bounded by both {@code phraseMaxLen}/{@code phraseMinLen}
     *             from {@link DictRefs.DictPartition} and the per-starter {@code lenMask} from {@code StarterUnion}.</li>
     *         <li>Lengths are tried longest-to-shortest to ensure deterministic greedy matching.</li>
     *         <li>Each dictionary entry is filtered by its {@code minLength}/{@code maxLength} to skip
     *             impossible candidates early.</li>
     *         <li>On the first hit, the replacement is appended and the cursor advances by the matched length.</li>
     *       </ul>
     *   </li>
     *   <li><b>Single-character fallback:</b>
     *       If no phrase match is found, the algorithm attempts a lookup in {@code singleDicts} using exactly
     *       the current code point (or surrogate pair). On hit, the replacement is appended and the cursor
     *       advances by one code point.</li>
     *   <li><b>No match:</b>
     *       If neither phrase nor single dictionaries contain the key, the original code point is appended and
     *       the cursor advances by one code point.</li>
     * </ol>
     *
     * @param input the text segment to convert (non-null; empty string is returned immediately)
     * @param part  partitioned dictionaries and cached round metadata
     * @return the converted string segment
     */
    private static String convertSegmentWithUnion(
            String input,
            DictRefs.DictPartition part
    ) {
        final int n = input.length();
        if (n == 0) return input;

        final StringBuilder out = new StringBuilder(n + (n >> 4));
        final StarterUnion union = part.union;
        final boolean hasPhrases = !part.phraseDicts.isEmpty();
        final boolean hasSingles = !part.singleDicts.isEmpty();

        int i = 0;
        while (i < n) {
            final int cp = input.codePointAt(i);
            final int starterLen = Character.charCount(cp);

            if (union != null && !union.hasStarter(cp)) {
                out.appendCodePoint(cp);
                i += starterLen;
                continue;
            }

            String hit = null;
            int hitLen = 0;

            if (hasPhrases) {
                final int remaining = n - i;
                if (remaining >= part.phraseMinLen) {
                    final int tryMax = Math.min(Math.min(part.phraseMaxLen, part.roundMaxLen), remaining);
                    final int tryMin = part.phraseMinLen;
                    if (tryMax >= tryMin) {
                        final long lMask = (union != null) ? union.lenMask(cp) : ~0L;
                        if (lMask != 0L) {
                            outer:
                            for (int len = tryMax; len >= tryMin; len--) {
                                if (len < 64 && ((lMask >>> len) & 1L) == 0L) continue;

                                final int j = i + len;
                                if (j > n) continue;

                                final String sub = input.substring(i, j);
                                for (DictEntry e : part.phraseDicts) {
                                    if (len < e.minLength || len > e.maxLength) continue;
                                    final String repl = e.dict.get(sub);
                                    if (repl != null) {
                                        hit = repl;
                                        hitLen = len;
                                        break outer;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (hit == null && hasSingles) {
                final int j = i + starterLen;
                if (j <= n) {
                    final String sub = input.substring(i, j);
                    for (DictEntry e : part.singleDicts) {
                        final String repl = e.dict.get(sub);
                        if (repl != null) {
                            hit = repl;
                            hitLen = starterLen;
                            break;
                        }
                    }
                }
            }

            if (hit != null) {
                out.append(hit);
                i += hitLen;
            } else {
                out.appendCodePoint(cp);
                i += starterLen;
            }
        }

        return out.toString();
    }

    /**
     * Converts Simplified Chinese to Traditional Chinese.
     *
     * @param input       the text in Simplified Chinese
     * @param punctuation whether to also convert punctuation marks
     * @return the converted text in Traditional Chinese
     */
    public String s2t(String input, boolean punctuation) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.S2T, punctuation);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Traditional Chinese to Simplified Chinese.
     *
     * @param input       the text in Traditional Chinese
     * @param punctuation whether to also convert punctuation marks
     * @return the converted text in Simplified Chinese
     */
    public String t2s(String input, boolean punctuation) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.T2S, punctuation);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Simplified Chinese to Traditional Chinese (Taiwan standard).
     *
     * @param input       the text in Simplified Chinese
     * @param punctuation whether to also convert punctuation marks
     * @return the converted text in Traditional Chinese (Taiwan)
     */
    public String s2tw(String input, boolean punctuation) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.S2TW, punctuation);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Traditional Chinese (Taiwan) to Simplified Chinese.
     *
     * @param input       the text in Traditional Chinese (Taiwan)
     * @param punctuation whether to also convert punctuation marks
     * @return the converted text in Simplified Chinese
     */
    public String tw2s(String input, boolean punctuation) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.TW2S, punctuation);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Simplified Chinese to Taiwan-style Traditional Chinese.
     *
     * <p>The conversion is applied in two rounds:</p>
     * <ol>
     *   <li>Simplified Chinese to Traditional Chinese.</li>
     *   <li>Traditional Chinese to Taiwan phrase and variant normalization.</li>
     * </ol>
     *
     * @param input       the text in Simplified Chinese
     * @param punctuation whether to also convert punctuation marks
     * @return the converted text in full Taiwan-style Traditional Chinese
     */
    public String s2twp(String input, boolean punctuation) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.S2TWP, punctuation);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Taiwan-style Traditional Chinese to Simplified Chinese.
     *
     * @param input       the text in Taiwan Traditional Chinese
     * @param punctuation whether to also convert punctuation marks
     * @return the converted text in Simplified Chinese
     */
    public String tw2sp(String input, boolean punctuation) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.TW2SP, punctuation);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Simplified Chinese to Hong Kong Traditional Chinese with
     * phrase-level Hong Kong normalization.
     *
     * <p>This applies Simplified-to-Traditional conversion first, then applies
     * Hong Kong phrase, phrase-variant, and character-variant dictionaries.
     * Optional punctuation conversion uses the same dictionary-based mechanism
     * as {@link #s2twp(String, boolean)}.</p>
     *
     * @param input       the text in Simplified Chinese
     * @param punctuation whether to also convert punctuation marks
     * @return the converted text in phrase-normalized Hong Kong Traditional Chinese
     * @since 1.4.0
     */
    public String s2hkp(String input, boolean punctuation) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.S2HKP, punctuation);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Hong Kong Traditional Chinese to Simplified Chinese with
     * phrase-level Hong Kong reverse normalization.
     *
     * <p>This applies Hong Kong phrase and variant reverse normalization first,
     * then applies Traditional-to-Simplified conversion. Optional punctuation
     * conversion uses the same dictionary-based mechanism as
     * {@link #tw2sp(String, boolean)}.</p>
     *
     * @param input       the text in Hong Kong Traditional Chinese
     * @param punctuation whether to also convert punctuation marks
     * @return the converted text in Simplified Chinese
     * @since 1.4.0
     */
    public String hk2sp(String input, boolean punctuation) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.HK2SP, punctuation);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Simplified Chinese to Traditional Chinese (Hong Kong standard).
     *
     * @param input       the text in Simplified Chinese
     * @param punctuation whether to also convert punctuation marks
     * @return the converted text in Hong Kong-style Traditional Chinese
     */
    public String s2hk(String input, boolean punctuation) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.S2HK, punctuation);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Hong Kong-style Traditional Chinese to Simplified Chinese.
     *
     * @param input       the text in Traditional Chinese (HK)
     * @param punctuation whether to also convert punctuation marks
     * @return the converted text in Simplified Chinese
     */
    public String hk2s(String input, boolean punctuation) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.HK2S, punctuation);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Traditional Chinese to Taiwan Traditional variants.
     *
     * @param input the Traditional Chinese input
     * @return the text converted to Taiwan-style Traditional Chinese
     */
    public String t2tw(String input) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.T2TW, false);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Traditional Chinese to Taiwan Traditional by applying Taiwan phrase,
     * phrase-variant, and character-variant dictionaries in one conversion round.
     *
     * @param input the Traditional Chinese input
     * @return the converted Taiwan Traditional Chinese with phrases and variants
     */
    public String t2twp(String input) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.T2TWP, false);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Taiwan Traditional Chinese to base Traditional Chinese.
     *
     * @param input the Taiwan Traditional input
     * @return the converted base Traditional Chinese text
     */
    public String tw2t(String input) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.TW2T, false);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Taiwan Traditional Chinese to base Traditional Chinese by applying
     * reverse Taiwan phrase, phrase-variant, and character-variant dictionaries in
     * one conversion round.
     *
     * @param input the Taiwan Traditional input
     * @return the fully reverted Traditional Chinese text
     */
    public String tw2tp(String input) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.TW2TP, false);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Traditional Chinese to Hong Kong Traditional variants.
     *
     * @param input the Traditional Chinese input
     * @return the converted text using HK Traditional variants
     */
    public String t2hk(String input) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.T2HK, false);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Traditional Chinese to Hong Kong Traditional by applying Hong Kong
     * phrase, phrase-variant, and character-variant dictionaries in one conversion round.
     *
     * @param input the Traditional Chinese input
     * @return the converted Hong Kong Traditional Chinese with phrases and variants
     */
    public String t2hkp(String input) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.T2HKP, false);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Hong Kong Traditional Chinese to base Traditional Chinese.
     *
     * @param input the HK Traditional Chinese input
     * @return the converted base Traditional Chinese text
     */
    public String hk2t(String input) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.HK2T, false);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Hong Kong Traditional Chinese to base Traditional Chinese by applying
     * reverse Hong Kong phrase, phrase-variant, and character-variant dictionaries in
     * one conversion round.
     *
     * @param input the Hong Kong Traditional Chinese input
     * @return the converted base Traditional Chinese text
     */
    public String hk2tp(String input) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.HK2TP, false);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Traditional Chinese to Japanese Kanji variants.
     *
     * @param input the Traditional Chinese input
     * @return the text converted to Japanese-style Kanji variants
     */
    public String t2jp(String input) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.T2JP, false);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts Japanese-style Kanji back to Traditional Chinese.
     *
     * @param input the Japanese Kanji-style Chinese input
     * @return the converted Traditional Chinese text
     */
    public String jp2t(String input) {
        DictRefs refs = getDictRefsUnionForConfigId(OpenccConfig.JP2T, false);
        return refs.applySegmentReplace(input, this::segmentReplaceWithUnion);
    }

    /**
     * Converts each character in the input text from Simplified Chinese to Traditional Chinese.
     *
     * <p>This method uses only the character-level dictionary (`st_characters`) and does not
     * apply phrase or context-based replacements.
     *
     * @param input the input text in Simplified Chinese
     * @return the text converted to Traditional Chinese, character by character
     */
    public static String st(String input) {
        return convertSegment(input, Collections.singletonList(DictionaryHolder.get().st_characters), 2); // maxLength = 2 for surrogate-paired characters
    }

    /**
     * Converts each character in the input text from Traditional Chinese to Simplified Chinese.
     *
     * <p>This method uses only the character-level dictionary (`ts_characters`) and does not
     * apply phrase or context-based replacements.
     *
     * @param input the input text in Traditional Chinese
     * @return the text converted to Simplified Chinese, character by character
     */
    public static String ts(String input) {
        return convertSegment(input, Collections.singletonList(DictionaryHolder.get().ts_characters), 2); // maxLength = 2 for surrogate-paired characters
    }

    /**
     * Attempts to detect whether the input text is written in Traditional or Simplified Chinese.
     *
     * <p>This method removes non-Chinese characters from at most the first 500 UTF-16 code units
     * of the input, then analyzes up to the first 100 Unicode code points for mismatches between
     * the input and its conversion to Simplified and Traditional Chinese.
     *
     * <p>Return codes:
     * <ul>
     *   <li>0 – Undetermined or mixed/other content</li>
     *   <li>1 – Likely Traditional Chinese</li>
     *   <li>2 – Likely Simplified Chinese</li>
     * </ul>
     *
     * @param input the input text to check
     * @return an integer code representing the detected Chinese variant
     */
    public static int zhoCheck(String input) {
        if (input == null || input.isEmpty()) return 0;

        int scanLength = Math.min(input.length(), 500);

        String stripped = DictRefs.STRIP_REGEX.matcher(input.substring(0, scanLength)).replaceAll("");
        if (stripped.isEmpty()) return 0;

        // Take first 100 code points
        int[] codePoints = stripped.codePoints()
                .limit(100)
                .toArray();

        String slice = new String(codePoints, 0, codePoints.length);

        if (!slice.equals(ts(slice))) return 1;
        if (!slice.equals(st(slice))) return 2;
        return 0;
    }

    /**
     * Attempts to detect whether the input text is written in Traditional or Simplified Chinese,
     * using this {@code OpenCC} instance.
     *
     * <p>This instance method delegates to the static {@link #zhoCheck(String)} detection
     * logic but is provided for convenience when working with an {@code OpenCC} object.</p>
     *
     * <p>Detection process:</p>
     * <ul>
     *   <li>Non-Chinese characters are stripped from the first portion of the text.</li>
     *   <li>Analysis is limited to text drawn from at most the first 500 UTF-16 code units,
     *       capped at 100 Unicode code points after stripping.</li>
     *   <li>The substring is compared against its conversions to Simplified and
     *       Traditional Chinese.</li>
     * </ul>
     *
     * <p>Return codes:</p>
     * <ul>
     *   <li><b>0</b> – Undetermined, mixed, or non-Chinese content</li>
     *   <li><b>1</b> – Likely Traditional Chinese</li>
     *   <li><b>2</b> – Likely Simplified Chinese</li>
     * </ul>
     *
     * @param input the input text to check (maybe {@code null} or empty)
     * @return an integer code representing the detected Chinese variant
     * @see #zhoCheck(String)
     * @deprecated since 1.1.0 - {@code zhoCheck} is now a static method.
     * Use {@link #zhoCheck(String)} instead.
     */
    @Deprecated
    public final int zhoCheckInstance(String input) {
        return OpenCC.zhoCheck(input);
    }

    /**
     * Normalizes CJK Compatibility Ideographs using the built-in Unicode
     * compatibility mapping table.
     *
     * <p>This is a convenience wrapper around
     * {@link CompatIdeographs#normalize(String)}.
     * It performs an optional Unicode compatibility normalization pre-pass and
     * does not modify this {@code OpenCC} instance, its selected configuration,
     * conversion dictionaries, segmentation behavior, script detection, or
     * punctuation conversion.</p>
     *
     * <p>Use this before {@link #convert(String)} when input may contain
     * CJK Compatibility Ideographs such as {@code 金} and you want
     * upstream OpenCC-compatible behavior. Unmapped compatibility ideographs
     * remain unchanged.</p>
     *
     * <p>DeToFu is the opposite side of the pipeline: compatibility ideograph
     * normalization is a pre-processing step, while
     * {@link #deTofu(String, DeTofu.Level)} is an optional post-processing
     * display fallback.</p>
     *
     * @param input the input text; {@code null} and empty strings return {@code ""}
     * @return normalized text with mapped compatibility ideographs replaced
     * by their unified forms
     * @since 1.4.1
     */
    public String normalizeCompat(String input) {
        return CompatIdeographs.normalize(input);
    }

    /**
     * Normalizes additional Unicode CJK compatibility and allograph forms.
     *
     * <p>This uses the mappings in {@code Unicode_Compatibility.txt} and is
     * independent of the CJK Compatibility Ideographs normalization performed by
     * {@link #normalizeCompat(String)}.</p>
     *
     * <p>This is an optional pre-conversion operation and is not performed
     * automatically by {@link #convert(String)}.</p>
     *
     * @param text the input text; {@code null} and empty strings return
     *             {@code ""}
     * @return normalized text
     * @since 1.4.3
     */
    public String normalizeUnicodeCompat(String text) {
        return UnicodeCompat.normalize(text);
    }

    /**
     * Applies extended Unicode compatibility normalization.
     *
     * <p>The normalization order is:</p>
     * <ol>
     *     <li>Additional Unicode compatibility/allograph mappings</li>
     *     <li>CJK Compatibility Ideographs mappings</li>
     * </ol>
     *
     * <p>This order allows text containing both non-standard allographs and
     * Unicode compatibility ideographs to be normalized in a single pass before
     * OpenCC conversion.</p>
     *
     * <p>This is an optional pre-conversion operation and is not performed
     * automatically by {@link #convert(String)}.</p>
     *
     * @param text the input text; {@code null} and empty strings return
     *             {@code ""}
     * @return text after extended compatibility normalization
     * @since 1.4.3
     */
    public String normalizeCompatExtended(String text) {
        if (text == null || text.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder(text.length());

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            codePoint = UnicodeCompat.map(codePoint);
            codePoint = CompatIdeographs.map(codePoint);

            sb.appendCodePoint(codePoint);
        }

        return sb.toString();
    }

    /**
     * Applies DeTofu display-compatible fallbacks to mapped rare CJK extension characters.
     *
     * <p>This is a convenience wrapper around {@link DeTofu#convert(String, DeTofu.Level)}.</p>
     *
     * <p>DeTofu is a display compatibility pass. It does not modify OpenCC conversion
     * dictionaries, phrase matching, regional variant selection, script detection, or
     * punctuation conversion.</p>
     *
     * <p>For converted text, apply DeTofu after {@code convert(...)}.</p>
     *
     * @param text  the input text; {@code null} is treated as empty text
     * @param level the threshold-based DeTofu extension level
     * @return text with mapped tofu-risk characters replaced and unmapped characters preserved
     * @throws NullPointerException if {@code level} is {@code null}
     * @since 1.4.0
     */
    public String deTofu(String text, DeTofu.Level level) {
        return DeTofu.convert(text, level);
    }

    /**
     * Applies DeTofu display-compatible fallbacks using the built-in mappings plus a custom fallback file.
     *
     * <p>This is a convenience wrapper around {@link DeTofu#builtinMap(DeTofu.Level)},
     * {@link DeTofu.Map#withCustomFile(String)}, and {@link DeTofu.Map#convert(String)}.</p>
     *
     * <p>Custom mappings are applied after the built-in table. If the same tofu-risk
     * character exists in both sources, the custom file mapping takes precedence.</p>
     *
     * <p>The fallback file must be UTF-8 text with one mapping per line:</p>
     *
     * <pre>{@code
     * tofu_char<TAB>fallback_char<TAB>extension
     * }</pre>
     *
     * <p>The extension column accepts either {@code B}–{@code I} or
     * {@code ExtB}–{@code ExtI}. Lines beginning with {@code #} and blank lines
     * are ignored.</p>
     *
     * @param text  the input text; {@code null} is treated as empty text
     * @param level the threshold-based DeTofu extension level
     * @param path  path to a UTF-8 custom DeTofu fallback file
     * @return text with mapped tofu-risk characters replaced and unmapped characters preserved
     * @throws IOException          if the custom fallback file cannot be read
     * @throws NullPointerException if {@code level} or {@code path} is {@code null}
     * @since 1.4.0
     */
    public String deTofuWithCustomFile(String text, DeTofu.Level level, String path) throws IOException {
        return DeTofu
                .builtinMap(level)
                .withCustomFile(path)
                .convert(text);
    }

    /**
     * Applies DeTofu display-compatible fallbacks using the built-in mappings plus
     * custom in-memory fallback mappings.
     *
     * <p>Custom mappings are applied after the built-in table. If the same tofu-risk
     * character exists in both sources, the custom mapping takes precedence.</p>
     *
     * <p>Keys are tofu-risk characters and values are their display-compatible
     * fallback characters.</p>
     *
     * @param text  the input text; {@code null} is treated as empty text
     * @param level the threshold-based DeTofu extension level
     * @param pairs custom tofu-risk character mappings
     * @return text with mapped tofu-risk characters replaced and unmapped characters preserved
     * @throws NullPointerException if {@code level} or {@code pairs} is {@code null}
     * @since 1.4.0
     */
    public String deTofuWithCustomPairs(
            String text,
            DeTofu.Level level,
            java.util.Map<String, String> pairs
    ) {
        return DeTofu
                .builtinMap(level)
                .withCustomPairs(pairs)
                .convert(text);
    }

    /**
     * Legacy helper that performed direct char-to-char punctuation substitution.
     *
     * <p><b>Deprecated since 1.1.0:</b> Punctuation conversion is now handled by
     * dictionary-driven entries ({@link DictionaryMaxlength.DictEntry}) within the
     * shared {@link DictionaryMaxlength} and applied by the normal conversion pipeline
     * (e.g., union keys that include punctuation variants). This approach supports
     * multi-code-point tokens, surrogate pairs, and locale-specific variants that a
     * simple {@code Map<Character, Character>} cannot model.</p>
     *
     * <p>This method is retained only for backward compatibility and tests and is not
     * used by the current implementation. Prefer enabling punctuation in your conversion
     * flow (via configuration/union selection or a punctuation flag on {@code convert(...)}).</p>
     *
     * @param input the original string to process
     * @param map   a mapping from source punctuation characters to their target equivalents
     * @return a new string with punctuation characters translated
     * @deprecated Use the standard dictionary-based conversion pipeline with punctuation
     * enabled (see {@link DictionaryMaxlength} and the conversion methods that
     * accept a punctuation option).
     */
    @SuppressWarnings("unused")
    @Deprecated
    private String translatePunctuation(String input, Map<Character, Character> map) {
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            sb.append(map.getOrDefault(c, c));
        }
        return sb.toString();
    }

}
