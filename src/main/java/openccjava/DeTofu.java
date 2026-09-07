package openccjava;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static openccjava.Utils.readUtf8;

/**
 * Utility methods for replacing selected rare CJK extension characters with
 * display-compatible fallback characters.
 *
 * <p>Some fonts, devices, or output formats cannot display every CJK Unified
 * Ideographs extension block. When such a character is rendered as an empty box
 * or "tofu", DeTofu can replace mapped characters with more widely supported
 * fallback characters.</p>
 *
 * <p>This class is independent of the normal OpenCC conversion pipeline. It
 * does not change dictionaries, regional phrase conversion, punctuation
 * conversion, or script detection. Apply it as a post-processing step when
 * display compatibility is more important than preserving the exact original
 * code point.</p>
 *
 * <p>The built-in table is loaded from the classpath resource
 * {@code /dicts/CharactersTofu.txt}. If the resource is unavailable or cannot
 * be read, the built-in map is empty and conversions preserve the input text.</p>
 *
 * <p>This class cannot be instantiated.</p>
 *
 * @see Level
 * @see Map
 * @since 1.4.0
 */
public final class DeTofu {
    private static final String BUILTIN_RESOURCE = "/dicts/CharactersTofu.txt";

    private static final List<Entry> BUILTIN_ENTRIES = loadBuiltinEntries();

    private DeTofu() {
    }

    /**
     * Threshold for selecting which CJK extension fallback mappings are enabled.
     *
     * <p>The levels are ordered from the broadest set ({@link #ExtB}) to the
     * narrowest set ({@link #ExtI}). A level accepts entries from itself and all
     * later extension blocks. For example, {@code ExtD} accepts mappings tagged
     * {@code ExtD}, {@code ExtE}, {@code ExtF}, {@code ExtG}, {@code ExtH}, and
     * {@code ExtI}, while {@code ExtI} accepts only {@code ExtI} mappings.</p>
     */
    public enum Level {
        /**
         * Enables mappings for extension B and later extension blocks.
         */
        ExtB,

        /**
         * Enables mappings for extension C and later extension blocks.
         */
        ExtC,

        /**
         * Enables mappings for extension D and later extension blocks.
         */
        ExtD,

        /**
         * Enables mappings for extension E and later extension blocks.
         */
        ExtE,

        /**
         * Enables mappings for extension F and later extension blocks.
         */
        ExtF,

        /**
         * Enables mappings for extension G and later extension blocks.
         */
        ExtG,

        /**
         * Enables mappings for extension H and later extension blocks.
         */
        ExtH,

        /**
         * Enables mappings for extension I only.
         */
        ExtI;

        /**
         * Returns whether this threshold includes a mapping tagged with the
         * supplied extension level.
         *
         * <p>The comparison is ordinal-based and follows the declaration order
         * of this enum. Lower thresholds include more mappings.</p>
         *
         * @param entryLevel the extension level attached to a mapping entry
         * @return {@code true} if an entry at {@code entryLevel} should be used
         * by this threshold; otherwise {@code false}
         * @throws NullPointerException if {@code entryLevel} is {@code null}
         */
        public boolean accepts(Level entryLevel) {
            return entryLevel.ordinal() >= this.ordinal();
        }

        /**
         * Parses a user-facing DeTofu level name.
         *
         * <p>The parser is case-insensitive and ignores leading and trailing
         * whitespace. Accepted values are {@code all}, {@code ext-b} through
         * {@code ext-i}, and the short forms {@code b} through {@code i}. The
         * value {@code all} is an alias for {@link #ExtB}.</p>
         *
         * @param value the level name to parse
         * @return the parsed level
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not a supported
         *                                  DeTofu level name
         */
        public static Level parse(String value) {
            if (value == null)
                throw new NullPointerException("value");

            switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "all":
                case "ext-b":
                case "extb":
                case "b":
                    return ExtB;
                case "ext-c":
                case "extc":
                case "c":
                    return ExtC;
                case "ext-d":
                case "extd":
                case "d":
                    return ExtD;
                case "ext-e":
                case "exte":
                case "e":
                    return ExtE;
                case "ext-f":
                case "extf":
                case "f":
                    return ExtF;
                case "ext-g":
                case "extg":
                case "g":
                    return ExtG;
                case "ext-h":
                case "exth":
                case "h":
                    return ExtH;
                case "ext-i":
                case "exti":
                case "i":
                    return ExtI;
                default:
                    throw new IllegalArgumentException(
                            "Supported DeTofu levels: all, ext-b, ext-c, ext-d, ext-e, ext-f, ext-g, ext-h, ext-i."
                    );
            }
        }
    }

    private static Level tryParseLevel(String value) {
        try {
            return Level.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Converts text using the built-in DeTofu fallback map for the supplied
     * extension threshold.
     *
     * <p>Only characters present in the selected map are replaced. Unmapped
     * characters are preserved. Supplementary characters are processed as full
     * Unicode code points rather than isolated UTF-16 surrogate values.</p>
     *
     * @param input the input text; {@code null} and empty strings return
     *              {@code ""}
     * @param level the DeTofu extension threshold to apply
     * @return text with mapped tofu-risk characters replaced by fallback
     * characters
     * @throws NullPointerException if {@code level} is {@code null}
     */
    public static String convert(String input, Level level) {
        return Map.builtin(level).convert(input);
    }

    /**
     * Creates a mutable DeTofu map populated with built-in mappings for the
     * supplied extension threshold.
     *
     * <p>The returned map can be extended with custom file or in-memory pairs
     * before conversion. Each call creates a new map instance; changes made to
     * one returned map do not affect maps returned by other calls.</p>
     *
     * @param level the DeTofu extension threshold to apply
     * @return a new mutable map containing the selected built-in mappings
     * @throws NullPointerException if {@code level} is {@code null}
     */
    public static Map builtinMap(Level level) {
        return Map.builtin(level);
    }

    private static List<Entry> loadBuiltinEntries() {
        InputStream stream = DeTofu.class.getResourceAsStream(BUILTIN_RESOURCE);
        if (stream == null)
            return Collections.emptyList();

        try {
            return parseEntries(readUtf8(stream));
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static List<Entry> parseEntries(String text) {
        List<Entry> entries = new ArrayList<>();

        if (text == null || text.isEmpty())
            return entries;

        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                String[] parts = line.split("\t");
                if (parts.length < 3)
                    continue;

                String tofu = readFirstScalar(parts[0].trim());
                String fallback = readFirstScalar(parts[1].trim());
                Level ext = tryParseLevel(parts[2]);

                if (tofu != null && fallback != null && ext != null)
                    entries.add(new Entry(tofu, fallback, ext));
            }
        } catch (IOException ignored) {
            // StringReader should not throw in normal use.
        }

        return entries;
    }

    private static String readFirstScalar(String value) {
        if (value == null || value.isEmpty())
            return null;

        int first = value.codePointAt(0);
        return new String(Character.toChars(first));
    }

    private static final class Entry {
        final String tofu;
        final String fallback;
        final Level extension;

        Entry(String tofu, String fallback, Level extension) {
            this.tofu = tofu;
            this.fallback = fallback;
            this.extension = extension;
        }
    }

    /**
     * Mutable DeTofu replacement map.
     *
     * <p>A map is created for a specific {@link Level} and initially contains
     * the mappings selected by that threshold. Custom mappings can then be added
     * from a UTF-8 file or from an in-memory {@link java.util.Map}. If a custom
     * mapping uses the same tofu-risk character as an existing mapping, the
     * custom fallback replaces the previous value.</p>
     *
     * <p>Instances are mutable and are not synchronized. If a map is shared
     * across threads, callers should finish configuring it before sharing or
     * provide their own synchronization.</p>
     */
    public static final class Map {
        private final Level level;
        private final HashMap<String, String> map;

        private Map(Level level, HashMap<String, String> map) {
            this.level = level;
            this.map = map;
        }

        /**
         * Creates a mutable map populated with built-in mappings for the
         * supplied extension threshold.
         *
         * <p>This is the factory used by {@link DeTofu#builtinMap(Level)} and
         * {@link DeTofu#convert(String, Level)}.</p>
         *
         * @param level the DeTofu extension threshold to apply
         * @return a new mutable map containing the selected built-in mappings
         * @throws NullPointerException if {@code level} is {@code null}
         */
        public static Map builtin(Level level) {
            if (level == null)
                throw new NullPointerException("level");

            HashMap<String, String> map = new HashMap<>();

            for (Entry entry : BUILTIN_ENTRIES) {
                if (level.accepts(entry.extension))
                    map.put(entry.tofu, entry.fallback);
            }

            return new Map(level, map);
        }

        /**
         * Adds custom mappings from a UTF-8 text file and returns this map.
         *
         * <p>The file format is one mapping per line:</p>
         *
         * <pre>{@code
         * tofu_char<TAB>fallback_char<TAB>extension
         * }</pre>
         *
         * <p>Blank lines and lines beginning with {@code #} are ignored. The
         * extension column accepts {@code B} through {@code I} or
         * {@code ExtB} through {@code ExtI}. A custom entry is added only when
         * its extension is accepted by this map's threshold. If either character
         * column contains more than one Unicode code point, only the first code
         * point is used.</p>
         *
         * @param path path to the UTF-8 custom mapping file
         * @return this map, after adding accepted custom mappings
         * @throws IOException          if the file cannot be read
         * @throws NullPointerException if {@code path} is {@code null}
         */
        public Map withCustomFile(String path) throws IOException {
            if (path == null)
                throw new NullPointerException("path");

            String text = new String(
                    java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                    StandardCharsets.UTF_8
            );

            return withEntries(parseEntries(text));
        }

        /**
         * Adds custom in-memory mappings and returns this map.
         *
         * <p>Keys are tofu-risk characters and values are display-compatible
         * fallback characters. For each key and value, only the first Unicode
         * code point is used. Entries with {@code null} or empty keys or values
         * are ignored.</p>
         *
         * <p>These pairs are applied directly and are not filtered by extension
         * level because no extension metadata is supplied.</p>
         *
         * @param pairs custom tofu-risk character to fallback character mappings
         * @return this map, after adding valid custom pairs
         * @throws NullPointerException if {@code pairs} is {@code null}
         */
        public Map withCustomPairs(java.util.Map<String, String> pairs) {
            if (pairs == null)
                throw new NullPointerException("pairs");

            for (java.util.Map.Entry<String, String> pair : pairs.entrySet()) {
                String tofu = readFirstScalar(pair.getKey());
                String fallback = readFirstScalar(pair.getValue());

                if (tofu != null && fallback != null)
                    map.put(tofu, fallback);
            }

            return this;
        }

        /**
         * Converts text using this map.
         *
         * <p>Only characters present in the map are replaced. Unmapped
         * characters are preserved. Supplementary characters are processed as
         * full Unicode code points rather than isolated UTF-16 surrogate
         * values.</p>
         *
         * @param input the input text; {@code null} and empty strings return
         *              {@code ""}
         * @return text with mapped tofu-risk characters replaced by fallback
         * characters
         */
        public String convert(String input) {
            if (input == null || input.isEmpty())
                return "";

            StringBuilder sb = new StringBuilder(input.length());

            for (int i = 0; i < input.length(); ) {
                int cp = input.codePointAt(i);
                i += Character.charCount(cp);

                String ch = new String(Character.toChars(cp));

                String replacement = map.get(ch);
                sb.append(replacement != null ? replacement : ch);
            }

            return sb.toString();
        }

        private Map withEntries(List<Entry> entries) {
            for (Entry entry : entries) {
                if (level.accepts(entry.extension))
                    map.put(entry.tofu, entry.fallback);
            }

            return this;
        }
    }
}
