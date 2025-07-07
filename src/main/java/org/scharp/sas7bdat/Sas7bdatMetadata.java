package org.scharp.sas7bdat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A class that represents the metadata (creation date, variables, etc.) of a SAS7BDAT file.
 * <p>
 * Instances of this class are immutable.
 * </p>
 */
public class Sas7bdatMetadata {
    private final LocalDateTime creationTime;
    private final String datasetType;
    private final String datasetLabel;
    private final List<Variable> variables;

    /**
     * A builder class for {@link Sas7bdatMetadata}.
     */
    public static class Builder {
        private LocalDateTime creationTime;
        private String datasetType;
        private String datasetLabel;
        private List<Variable> variables;

        /**
         * Creates a {@code Sas7bdatMetadata} builder.
         */
        private Builder(LocalDateTime creationTime, String datasetType, String datasetLabel, List<Variable> variables) {
            this.creationTime = creationTime;
            this.datasetType = datasetType;
            this.datasetLabel = datasetLabel;
            this.variables = variables;
        }

        /**
         * Sets the SAS7BDAT's creation time.
         *
         * @param creationTime
         *     The SAS7BDAT's new creation time.
         *
         * @return this builder
         *
         * @throws NullPointerException
         *     if {@code creationTime} is {@code null}.
         * @throws IllegalArgumentException
         *     if {@code creationTime} is before the year 1582 or after the year 19900.
         */
        public Builder creationTime(LocalDateTime creationTime) {
            ArgumentUtil.checkNotNull(creationTime, "creationTime");

            // Check that the creation time is in the range supported by SAS.
            // According to
            //    https://support.sas.com/documentation/cdl/en/lrcon/62955/HTML/default/viewer.htm#a002200738.htm
            // SAS only supports "dates ranging from A.D. 1582 to A.D. 19,900".
            int creationDateYear = creationTime.getYear();
            if (creationDateYear < 1582) {
                throw new IllegalArgumentException("creationTime must not be before the year 1582");
            } else if (19_900 < creationDateYear) {
                throw new IllegalArgumentException("creationTime must not be after the year 19900");
            }

            this.creationTime = creationTime;
            return this;
        }

        /**
         * Sets the SAS7BDAT's dataset type.
         *
         * @param datasetType
         *     The SAS7BDAT's dataset type.
         *
         * @return this builder
         *
         * @throws NullPointerException
         *     if {@code datasetType} is {@code null}.
         * @throws IllegalArgumentException
         *     if {@code datasetType} is longer than 8 bytes when encoded in UTF-8.
         */
        public Builder datasetType(String datasetType) {
            ArgumentUtil.checkNotNull(datasetType, "datasetType");

            // dataset types can't be longer than 8 bytes.
            ArgumentUtil.checkMaximumLength(datasetType, StandardCharsets.UTF_8, 8, "datasetType");

            this.datasetType = datasetType;
            return this;
        }

        /**
         * Sets the SAS7BDAT's dataset label.
         *
         * @param datasetLabel
         *     The SAS7BDAT's dataset label.
         *
         * @return this builder
         *
         * @throws NullPointerException
         *     if {@code datasetLabel} is {@code null}.
         * @throws IllegalArgumentException
         *     if {@code datasetLabel} is longer than 256 bytes when encoded in UTF-8.
         */
        public Builder datasetLabel(String datasetLabel) {
            ArgumentUtil.checkNotNull(datasetLabel, "datasetLabel");

            // dataset labels can't be longer than 256 bytes.
            ArgumentUtil.checkMaximumLength(datasetLabel, StandardCharsets.UTF_8, 256, "datasetLabel");

            this.datasetLabel = datasetLabel;
            return this;
        }

        /**
         * Sets the SAS7BDAT's dataset type.
         *
         * @param variables
         *     A list of variables given in the order in which they should appear in the SAS7BDAT dataset. This list is
         *     copied, so subsequent changes to the list do not impact this builder or the resulting
         *     {@code Sas7bdatMetadata}.
         *
         * @return this builder
         *
         * @throws NullPointerException
         *     if {@code variables} is {@code null} or contains a {@code null} entry.
         * @throws IllegalArgumentException
         *     if {@code variables} is empty, has more than 32767 entries, or contains two variables with the same
         *     name.
         */
        public Builder variables(List<Variable> variables) {
            // Check that the variables list is well-formed
            ArgumentUtil.checkNotNull(variables, "variables");
            if (variables.isEmpty()) {
                throw new IllegalArgumentException("variables must not be empty");
            }
            if (Short.MAX_VALUE < variables.size()) {
                throw new IllegalArgumentException(
                    "A SAS7BDAT cannot have more than " + Short.MAX_VALUE + " variables");
            }

            // Copy the variables while checking for null entries and duplicate names.
            List<Variable> newList = new ArrayList<>(variables.size());
            Set<String> variableNames = new HashSet<>(variables.size() * 2);
            for (Variable variable : variables) {
                if (variable == null) {
                    throw new NullPointerException("variables cannot contain a null entry");
                }

                // Check if this variable's name matches another variable's name.
                if (!variableNames.add(variable.name())) {
                    throw new IllegalArgumentException(
                        "variables contains two variables named \"" + variable.name() + "\"");
                }
                newList.add(variable);
            }

            // Now that the input has been validated, we can commit to using its copy.
            this.variables = newList;
            return this;
        }

        /**
         * Builds the immutable {@code Sas7bdatMetadata} with the configured options.
         *
         * @return a {@code Sas7bdatMetadata}
         *
         * @throws IllegalStateException
         *     if the variables hasn't been set explicitly.
         */
        public Sas7bdatMetadata build() {
            // There is no meaningful default variables, so it's an error if the caller hasn't set them.
            if (variables.isEmpty()) {
                throw new IllegalStateException("variables must be set");
            }
            return new Sas7bdatMetadata(creationTime, datasetType, datasetLabel, variables);
        }
    }

    /**
     * Creates a new Sas7bdatMetadata builder initialized with the current time as the creation time, a dataset type of
     * "DATA", no label, and no variables.
     * <p>
     * The variables must be set before invoking {@link Builder#build build}
     * </p>
     *
     * @return A new builder.
     */
    public static Builder builder() {
        return new Builder(LocalDateTime.now(), "DATA", "", List.of());
    }

    /**
     * A private constructor, invoked only by {@link Builder}.
     *
     * @param creationTime
     *     The creation time
     * @param datasetType
     *     The dataset type
     * @param variables
     *     A list of variables.  Builder ensures that the client has no references to it.
     */
    private Sas7bdatMetadata(LocalDateTime creationTime, String datasetType, String datasetLabel,
        List<Variable> variables) {
        this.creationTime = creationTime;
        this.datasetType = datasetType;
        this.datasetLabel = datasetLabel;
        this.variables = variables; // Builder ensures that the library client does not have a reference.
    }

    /**
     * Gets the time and date at which the associated SAS7BDAT was created.
     *
     * @return a local date time.
     */
    public LocalDateTime creationTime() {
        return creationTime;
    }

    /**
     * Gets the SAS7BDAT's dataset type.
     *
     * @return the dataset type.  This is never {@code null}.
     */
    public String datasetType() {
        return datasetType;
    }

    /**
     * Gets the SAS7BDAT's dataset label.
     *
     * @return the dataset label.  This is never {@code null}.
     */
    public String datasetLabel() {
        return datasetLabel;
    }

    /**
     * Gets associated SAS7BDAT's variables.
     * <p>
     * This returned list is not modifiable.
     * </p>
     *
     * @return the dataset's variables, in order.  This is never {@code null}.
     */
    public List<Variable> variables() {
        return Collections.unmodifiableList(variables);
    }
}