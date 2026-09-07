package openccjava;

/**
 * Internal helper for normalizing additional Unicode CJK compatibility and
 * allograph forms to their preferred canonical forms.
 *
 * <p>The built-in mapping table is loaded from
 * {@code /dicts/Unicode_Compatibility.txt}. This table complements
 * {@link CompatIdeographs} with additional compatibility, radical, and
 * allograph mappings used before OpenCC conversion.</p>
 *
 * <p>This helper is intentionally package-private. Public normalization APIs
 * are exposed through {@link OpenCC}.</p>
 */
final class UnicodeCompat {

    private static final UnicodeMappingTable TABLE =
            UnicodeMappingTable.load(
                    UnicodeCompat.class,
                    "/dicts/Unicode_Compatibility.txt"
            );

    private UnicodeCompat() {
    }

    /**
     * Normalizes additional Unicode compatibility and allograph forms in the
     * supplied text.
     *
     * @param input input text; {@code null} and empty strings return {@code ""}
     * @return normalized text
     */
    static String normalize(String input) {
        return TABLE.normalize(input);
    }

    /**
     * Maps one Unicode code point using the built-in extended Unicode
     * compatibility table.
     *
     * <p>This package-private primitive operation is used by extended
     * normalization so both built-in tables can be composed in one text scan
     * without allocating an intermediate string.</p>
     *
     * @param codePoint Unicode code point to map
     * @return mapped code point, or the original code point when unmapped
     */
    static int map(int codePoint) {
        return TABLE.map(codePoint);
    }
}
