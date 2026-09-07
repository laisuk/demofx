package openccjava;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;

import static openccjava.Utils.readUtf8;

/**
 * Internal Unicode code-point mapping table optimized for sparse normalization
 * data.
 *
 * <p>The table uses lazily allocated pages of 256 code points. This avoids the
 * boxing and hashing overhead of {@code HashMap<Integer, Integer>} while also
 * avoiding the memory cost of allocating one dense array for the entire Unicode
 * range.</p>
 *
 * <p>Each populated page stores {@code replacement + 1}; zero therefore means
 * "unmapped" while still allowing U+0000 to be represented as a valid
 * replacement value.</p>
 *
 * <p>This class is package-private and shared by the built-in normalization
 * helpers. Public normalization APIs are exposed through {@link OpenCC}.</p>
 */
final class UnicodeMappingTable {

    private static final int PAGE_SHIFT = 8;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_MASK = PAGE_SIZE - 1;
    private static final int PAGE_COUNT =
            (Character.MAX_CODE_POINT >>> PAGE_SHIFT) + 1;

    private final int[][] pages;

    private UnicodeMappingTable(int[][] pages) {
        this.pages = pages;
    }

    /**
     * Loads a UTF-8 mapping table from a classpath resource.
     *
     * <p>The expected format is one mapping per line:</p>
     *
     * <pre>{@code
     * source<TAB>replacement
     * }</pre>
     *
     * <p>Blank lines and lines beginning with {@code #} are ignored. Each side
     * must contain exactly one Unicode scalar value. Malformed lines are ignored
     * for built-in resources so that an unavailable or damaged optional
     * normalization table degrades to identity behavior instead of preventing
     * OpenCC startup.</p>
     *
     * @param owner        class used to resolve the classpath resource
     * @param resourcePath absolute classpath resource path
     * @return loaded mapping table, or an empty identity table if loading fails
     * @throws NullPointerException if {@code owner} or {@code resourcePath} is null
     */
    static UnicodeMappingTable load(Class<?> owner, String resourcePath) {
        if (owner == null)
            throw new NullPointerException("owner");
        if (resourcePath == null)
            throw new NullPointerException("resourcePath");

        InputStream stream = owner.getResourceAsStream(resourcePath);
        if (stream == null)
            return empty();

        try {
            return parse(readUtf8(stream));
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    /**
     * Maps one Unicode code point.
     *
     * @param codePoint Unicode code point to map
     * @return mapped code point, or the original code point when unmapped
     */
    int map(int codePoint) {
        int[] page = pages[codePoint >>> PAGE_SHIFT];

        if (page == null)
            return codePoint;

        int mapped = page[codePoint & PAGE_MASK];
        return mapped == 0 ? codePoint : mapped - 1;
    }

    /**
     * Normalizes text by applying this table to each Unicode code point.
     *
     * <p>Supplementary-plane characters are processed as full code points
     * rather than isolated UTF-16 surrogate values.</p>
     *
     * @param input input text; {@code null} and empty strings return {@code ""}
     * @return normalized text
     */
    String normalize(String input) {
        if (input == null || input.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder(input.length());

        for (int i = 0; i < input.length(); ) {
            int codePoint = input.codePointAt(i);
            i += Character.charCount(codePoint);

            sb.appendCodePoint(map(codePoint));
        }

        return sb.toString();
    }

    private static UnicodeMappingTable parse(String text) {
        int[][] pages = new int[PAGE_COUNT][];

        try (BufferedReader reader =
                     new BufferedReader(new StringReader(text))) {

            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                String[] parts = line.split("\t", -1);
                if (parts.length != 2)
                    continue;

                Integer source = tryReadSingleCodePoint(parts[0].trim());
                Integer replacement = tryReadSingleCodePoint(parts[1].trim());

                if (source == null || replacement == null)
                    continue;

                put(pages, source, replacement);
            }
        } catch (IOException ignored) {
            // StringReader should not throw during normal use.
        }

        return new UnicodeMappingTable(pages);
    }

    private static void put(int[][] pages, int source, int replacement) {
        int pageIndex = source >>> PAGE_SHIFT;
        int[] page = pages[pageIndex];

        if (page == null) {
            page = new int[PAGE_SIZE];
            pages[pageIndex] = page;
        }

        page[source & PAGE_MASK] = replacement + 1;
    }

    private static Integer tryReadSingleCodePoint(String value) {
        if (value == null || value.isEmpty())
            return null;

        int codePoint = value.codePointAt(0);
        int charCount = Character.charCount(codePoint);

        if (charCount != value.length()
                || (codePoint >= Character.MIN_SURROGATE
                && codePoint <= Character.MAX_SURROGATE)) {
            return null;
        }

        return codePoint;
    }

    private static UnicodeMappingTable empty() {
        return new UnicodeMappingTable(new int[PAGE_COUNT][]);
    }
}
