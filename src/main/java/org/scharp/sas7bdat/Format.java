package org.scharp.sas7bdat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Objects;

/**
 * A simple class for describing a format of a SAS variable.
 *
 * <p>
 * Instances of this class are immutable.
 * </p>
 *
 * <p>
 * This class supports {@code equals()} and {@code hashCode()} in a way that makes them suitable for use in a HashMap.
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
    private final short width;
    private final short numberOfDigits;

    /**
     * Creates a new Format specification.
     *
     * @param name
     *     The name of format applied to a variable. For example "COMMA", "$CHAR", "DOLLAR", or "". This must not be
     *     {@code null} and must not be larger than 32 bytes in UTF-8.
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
     *     if {@code name} is too long, if {@code width} is out of range, or if {@code numberOfDigits} is out of range.
     */
    public Format(String name, int width, int numberOfDigits) {
        // Validate arguments to maintain class invariant.
        ArgumentUtil.checkNotNull(name, "format name");
        ArgumentUtil.checkMaximumLength(name, StandardCharsets.UTF_8, 32, "format name");

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

        this.name = name;
        this.width = (short) width;
        this.numberOfDigits = (short) numberOfDigits;
    }

    /**
     * Creates a new Format specification.
     *
     * @param name
     *     The name of format applied to a variable. For example "COMMA", "$CHAR", "DOLLAR", or "". This must not be
     *     {@code null} and must not be larger than 32 bytes in UTF-8.
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
     *     if {@code name} is too long, if {@code width} is out of range, or if {@code numberOfDigits} is out of range.
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
    public short width() {
        return width;
    }

    /**
     * @return The number of digits. If this is zero, then no explicit number of digits is set.
     */
    public short numberOfDigits() {
        return numberOfDigits;
    }

    /**
     * Gets a hash code for this format.
     * <p>
     * This method is supported for the benefit of hash tables such as those provided by {@link HashMap}.
     * </p>
     *
     * @return this format's hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(name, width, numberOfDigits);
    }

    /**
     * Determines if this format is equal to another object.
     * <p>
     * Two formats are equal if their format name, width, and numberOfDigits are all equal.
     * </p>
     *
     * @param other
     *     The object with which to compare this format
     *
     * @return {@code true}, if this format is equal to {@code other}.  {@code false}, otherwise.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Format otherFormat)) {
            return false;
        }

        return name.equals(otherFormat.name) &&
            width == otherFormat.width &&
            numberOfDigits == otherFormat.numberOfDigits;
    }

    /**
     * Shows this format as a string in the same way that SAS would.
     *
     * <p>
     * For example "$ASCII4." or "BESTD5.2".
     * </p>
     *
     * @return A string representing this format.
     */
    @Override
    public String toString() {
        if (UNSPECIFIED.equals(this)) {
            return "";
        }

        // SAS displays formats in the form: <name> <width> '.' <digits>.
        // If <width> or <digits> is zero, then the component isn't included in the string.
        StringBuilder builder = new StringBuilder(name);
        if (width != 0) {
            builder.append(width);
        }
        builder.append('.');
        if (numberOfDigits != 0) {
            builder.append(numberOfDigits);
        }
        return builder.toString();
    }
}