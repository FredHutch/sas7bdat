package org.scharp.sas7bdat;

/**
 * A simple class for describing a format of a SAS variable.
 *
 * <p>
 * Instances of this class are immutable.
 * </p>
 *
 * <p>
 * See the SAS documentation for the {@code FORMAT} statement.
 * </p>
 */
public final class Format {

    /**
     * A variable format that means "use the default format for the type".
     */
    public static Format UNSPECIFIED = new Format("", 0, 0);

    private final String name;
    private final int width;
    private final int numberOfDigits;

    /**
     * Creates a new Format specification.
     *
     * @param name
     *     The name of format applied to a variable. For example "COMMA", "$CHAR", "DOLLAR", or "". This must not be
     *     {@code null}, must be entirely ASCII characters, and must not be larger than 8 characters.
     * @param width
     *     The maximum width to use. 0 implies the default for the format.
     *     <p>
     *     This must not be negative and must not be greater than {@code Short.MAX_VALUE}. The format selected by
     *     {@code name} may impose further restrictions.
     *     </p>
     *
     *     <p>
     *     In a SAS format like {@code FORMATw.d}, this is the "w".
     *     </p>
     * @param numberOfDigits
     *     The number of digits to the right of the decimal point. 0 implies the default for the format.
     *
     *     <p>
     *     This must not be negative and must not be greater than {@code Short.MAX_VALUE}. The format selected by
     *     {@code name} may impose further restrictions.
     *     </p>
     *
     *     <p>
     *     In a SAS format like {@code FORMATw.d}, this is the "d".
     *     </p>
     *
     * @throws NullPointerException
     *     if {@code name} is {@code null}.
     * @throws IllegalArgumentException
     *     if {@code name} is too long or contains a non-ASCII character, if {@code width} is out of range, or if
     *     {@code numberOfDigits} is out of range.
     */
    public Format(String name, int width, int numberOfDigits) {
        // Validate arguments to maintain class invariant.
        ArgumentUtil.checkNotNull(name, "format name");
        ArgumentUtil.checkMaximumLength(name, 8, "format name");
        ArgumentUtil.checkIsAscii(name, "format name");

        if (width < 0) {
            // This might be too strict.  SAS permits (and ignores) negative lengths.
            throw new IllegalArgumentException("format width must not be negative");
        }
        if (Short.MAX_VALUE < width) {
            throw new IllegalArgumentException("format width must not be greater than 32767");
        }

        if (numberOfDigits < 0) {
            throw new IllegalArgumentException("format numberOfDigits must not be negative");
        }
        if (Short.MAX_VALUE < numberOfDigits) {
            throw new IllegalArgumentException("format numberOfDigits must not be greater than 32767");
        }

        // TODO: provide an overload with a structured name for standard formats DOLLAR, CHAR, etc.
        this.name = name;
        this.width = width;
        this.numberOfDigits = numberOfDigits;
    }

    /**
     * Creates a new Variable Format specification.
     *
     * @param name
     *     The name of format applied to a variable. For example "COMMA", "$CHAR", "DOLLAR", or "". This must not be
     *     {@code null}, must be entirely ASCII characters, and must not be larger than 8 characters.
     * @param width
     *     The maximum width to use. 0 implies the default for the format.
     *     <p>
     *     This must not be negative and must not be greater than {@code Short.MAX_VALUE}. The format selected by
     *     {@code name} may impose further restrictions.
     *     </p>
     *
     *     <p>
     *     In a SAS format like {@code FORMATw.}, this is the "w".
     *     </p>
     *
     * @throws NullPointerException
     *     if {@code name} is {@code null}.
     * @throws IllegalArgumentException
     *     if {@code name} is too long or contains a non-ASCII character, if {@code width} is out of range.
     */
    public Format(String name, int width) {
        this(name, width, 0);
    }

    /**
     * @return The name of this format. This may be the empty string but never {@code null}.
     */
    public String name() {
        return name;
    }

    /**
     * @return The width of this format. If this is zero, then no explicit width is set.
     */
    public int width() {
        return width;
    }

    /**
     * @return The number of digits. If this is zero, then no explicit number of digits is set.
     */
    public int numberOfDigits() {
        return numberOfDigits;
    }

    // TODO: toString() to format like SAS.
}