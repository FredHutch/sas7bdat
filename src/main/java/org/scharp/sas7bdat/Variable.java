///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Objects;

/**
 * A variable or column in a SAS7BDAT file.
 * <p>
 * Instances of this class are immutable.  They are created with a {@link Variable.Builder}:
 * </p>
 *
 * <pre>
 * Variable cityVariable = Variable.builder().
 *     name("CITY").
 *     type(VariableType.CHARACTER).
 *     length(20).
 *     label("Name of city").
 *     outputFormat(new Format("$CHAR", 18)).
 *     build();
 * </pre>
 *
 * <p>
 * This class supports {@code equals()} and {@code hashCode()} so that its instances suitable for use in a
 * {@code HashMap}.
 * </p>
 */
public final class Variable {

    private final VariableType type;
    private final String name;
    private final int length;
    private final String label;
    private final Format outputFormat;
    private final Format inputFormat;

    /**
     * A builder class for {@link Variable}.
     */
    public final static class Builder {
        private VariableType type;
        private String name;
        private int length;
        private String label;
        private Format outputFormat;
        private Format inputFormat;

        /**
         * Creates a {@code Variable} builder.
         */
        private Builder() {
            this.type = null; // required parameter
            this.name = null; // required parameter
            this.length = 0; // required parameter

            this.label = ""; // optional, so default to blank
            this.outputFormat = Format.UNSPECIFIED; // optional, so default to unspecified
            this.inputFormat = Format.UNSPECIFIED; // optional and rarely used, so default to unspecified
        }

        /**
         * Sets the variable's type (character or numeric).
         *
         * @param type
         *     The variable's new type.
         *
         * @return This builder
         *
         * @throws NullPointerException
         *     if {@code type} is {@code null}.
         */
        public Builder type(VariableType type) {
            ArgumentUtil.checkNotNull(type, "type");
            this.type = type;
            return this;
        }

        /**
         * Sets the variable's name.
         *
         * @param name
         *     The variable's new name.
         *
         * @return This builder
         *
         * @throws NullPointerException
         *     if {@code name} is {@code null}.
         * @throws IllegalArgumentException
         *     if {@code name} is empty or exceeds 32 bytes in UTF-8.
         */
        public Builder name(String name) {
            ArgumentUtil.checkNotNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("variable names cannot be blank");
            }
            ArgumentUtil.checkMaximumLength(name, StandardCharsets.UTF_8, 32, "variable names");

            this.name = name;
            return this;
        }

        /**
         * Sets the variable's length (the number of bytes used to store the variable's value).
         * <p>
         * Keep in mind that SAS7BDAT is a fixed-width format.  So if you set the length to a large number, like 2000,
         * because you're unsure how long string values can be, the value will occupy 2000 bytes in every row, with the
         * unused space set to a space character.  If you do this on a data set with many observations, you may end up
         * with a very large SAS7BDAT file.
         * </p>
         *
         * @param length
         *     The length of the new variable.
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *     if {@code length} is less than 1 or greater than 32,767.
         */
        public Builder length(int length) {
            // Some of the restrictions on length depend on the type.  However, we can't enforce those
            // restrictions without requiring that the type be set before the length, which would violate
            // the principle of least surprise.
            if (length <= 0) {
                throw new IllegalArgumentException("variable length must be positive");
            }
            if (Short.MAX_VALUE < length) {
                throw new IllegalArgumentException("variable length cannot be greater than " + Short.MAX_VALUE);
            }

            this.length = length;
            return this;
        }

        /**
         * Sets the variable's label.
         *
         * @param label
         *     The variable's new label.
         *
         * @return This builder
         *
         * @throws NullPointerException
         *     if {@code label} is {@code null}.
         * @throws IllegalArgumentException
         *     if {@code label} exceeds 256 bytes in UTF-8.
         */
        public Builder label(String label) {
            ArgumentUtil.checkNotNull(label, "label");
            ArgumentUtil.checkMaximumLength(label, StandardCharsets.UTF_8, 256, "variable labels");

            this.label = label;
            return this;
        }

        /**
         * Sets the variable's default output format, which is the format SAS will use when displaying the variable's
         * values.  SAS refers to this as "FORMAT".
         *
         * @param outputFormat
         *     The variable's new output format. This cannot be {@code null}, but it can be {@code Format.UNSPECIFIED}.
         *
         * @return This builder
         *
         * @throws NullPointerException
         *     if {@code outputFormat} is {@code null}.
         */
        public Builder outputFormat(Format outputFormat) {
            ArgumentUtil.checkNotNull(outputFormat, "outputFormat");

            this.outputFormat = outputFormat;
            return this;
        }

        /**
         * Sets the variable's default input format, which is the format SAS will use when reading the variable's values
         * from a text file.  SAS refers to this as "INFORMAT".
         * <p>
         * This is usually not set.
         * </p>
         *
         * @param inputFormat
         *     The variable's new input format. This cannot be {@code null}, but it can be {@code Format.UNSPECIFIED}.
         *
         * @return This builder
         *
         * @throws NullPointerException
         *     if {@code inputFormat} is {@code null}.
         */
        public Builder inputFormat(Format inputFormat) {
            ArgumentUtil.checkNotNull(inputFormat, "inputFormat");

            this.inputFormat = inputFormat;
            return this;
        }

        private void checkIsLegalForType(Format format, String formatDescription) {
            // Both CHARACTER and NUMERIC types can have an unspecified format.
            if (Format.UNSPECIFIED.equals(format)) {
                return;
            }

            // SAS's documentation states that if a format name starts with a "$" it's for character types,
            // otherwise it's for numeric types.  It also says that, if you put a character format on a numeric
            // type, or vice versa, it will try to find an equivalent format for the type.  Rather than encourage
            // such ill-defined behavior, it's better for the Java programmer to specify the correct format.
            // This enables a faster fail when the caller makes a mistake.
            if (type == VariableType.CHARACTER != format.name().startsWith("$")) {
                throw new IllegalStateException(
                    formatDescription + " \"" + format + "\" is not legal for " + type + " variables");
            }
        }

        /**
         * Builds an immutable {@code Variable} with the configured options.
         *
         * @return a {@code Variable}
         *
         * @throws IllegalStateException
         *     if the type, length, or name haven't been set explicitly; if type is NUMERIC and length is not 8; or if
         *     the input/output format is for a variable with a different type
         */
        public Variable build() {
            // There is no meaningful default type, length, or name; it's an error if the caller hasn't set them.
            if (type == null) {
                throw new IllegalStateException("type must be set");
            }
            if (length == 0) {
                throw new IllegalStateException("length must be set");
            }
            if (name == null) {
                throw new IllegalStateException("name must be set");
            }

            // The SAS documentation says:
            // """
            // For numeric variables, 2 to 8 bytes or 3 to 8 bytes, depending on your operating environment.
            // For character variables, 1 to 32767 bytes under all operating environments.
            // """
            //
            // The length should already be set to between 1 and 32767, so we don't need to check again
            // for CHARACTER types.
            if (type == VariableType.NUMERIC) {
                // Sas7BdatExporter only supports numeric values of size 8.
                if (8 != length) {
                    throw new IllegalStateException("numeric variables must have a length of 8");
                }
            }

            // Make sure that only character formats are used for character types (and the same for numeric types)
            checkIsLegalForType(outputFormat, "outputFormat");
            checkIsLegalForType(inputFormat, "inputFormat");

            return new Variable(name, type, length, label, outputFormat, inputFormat);
        }
    }

    /**
     * Creates a new Variable builder with a blank label and an unspecified input format and output format.
     * <p>
     * You must set the name, type, and length before invoking {@link Builder#build() build()}.
     * </p>
     *
     * @return A new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Constructs a variable object without doing any parameter validation.
     *
     * @param name
     *     The variable's name.
     * @param type
     *     The variable's type (character or numeric)
     * @param variableLength
     *     The maximum number of bytes that any value can have.
     * @param label
     *     The variable's label.
     * @param outputFormat
     *     The format to use when displaying this variable's values.
     * @param inputFormat
     *     The format to use when reading this variable's value in.
     */
    private Variable(String name, VariableType type, int variableLength, String label, Format outputFormat,
        Format inputFormat) {
        this.name = name;
        this.type = type;
        this.length = variableLength;
        this.label = label;
        this.outputFormat = outputFormat;
        this.inputFormat = inputFormat;
    }

    /**
     * Gets this variable's type (character or numeric).
     *
     * @return This variable's type. This is never {@code null}.
     */
    public VariableType type() {
        return type;
    }

    /**
     * Gets this variable's name.
     *
     * @return This variable's name. This is never {@code null}.
     */
    public String name() {
        return name;
    }

    /**
     * Gets this variable's label.
     *
     * @return This variable's label. This may be the empty string but never {@code null}.
     */
    public String label() {
        return label;
    }

    /**
     * Gets this variable's length, which is number of bytes that values of this variable can occupy within an
     * observation.
     *
     * @return This variable's length.
     */
    public int length() {
        return length;
    }

    /**
     * Gets the format to use when rendering this variable's values for display. SAS refers to this as the "FORMAT".
     *
     * @return This variable's output format. This is never {@code null}.
     */
    public Format outputFormat() {
        return outputFormat;
    }

    /**
     * Gets the format to use when rendering this variable's values into a SAS program. SAS refers to this as the
     * "INFORMAT".
     *
     * @return The variable's input format. This is never {@code null}.
     */
    public Format inputFormat() {
        return inputFormat;
    }

    /**
     * Gets a hash code for this variable.
     * <p>
     * This method is supported for the benefit of hash tables such as those provided by {@link HashMap}.
     * </p>
     *
     * @return This variable's hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(name, type, length, label, outputFormat, inputFormat);
    }

    /**
     * Determines if this variable is equal to another object.
     * <p>
     * Two variables are equal if and only if their name, type, length, label, outputFormat, and inputFormat fields are
     * all equal.
     * </p>
     *
     * @param other
     *     The object with which to compare this variable.
     *
     * @return {@code true}, if this variable is equal to {@code other}.  {@code false}, otherwise.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Variable otherVariable)) {
            return false;
        }

        return name.equals(otherVariable.name) &&
            type == otherVariable.type &&
            length == otherVariable.length &&
            label.equals(otherVariable.label) &&
            outputFormat().equals(otherVariable.outputFormat) &&
            inputFormat().equals(otherVariable.inputFormat);
    }
}