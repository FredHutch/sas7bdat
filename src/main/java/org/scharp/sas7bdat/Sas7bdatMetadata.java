package org.scharp.sas7bdat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
         */
        public Builder creationTime(LocalDateTime creationTime) {
            ArgumentUtil.checkNotNull(creationTime, "creationTime");
            // TODO: check that the creation time is in the range supported by SAS.
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
         */
        public Builder datasetType(String datasetType) {
            ArgumentUtil.checkNotNull(datasetType, "datasetType");
            // TODO: check that the type is well-formed.
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
         *     if {@code datasetlabel} is {@code null}.
         */
        public Builder datasetLabel(String datasetLabel) {
            ArgumentUtil.checkNotNull(datasetLabel, "datasetLabel");
            // TODO: check that the label is well-formed
            this.datasetLabel = datasetLabel;
            return this;
        }

        /**
         * Sets the SAS7BDAT's dataset type.
         *
         * @param variables
         *     A list of variables.  This list is copied, so subsequent changes to the list do not impact this builder
         *     or the resulting {@code Sas7bdatMetadata}.
         *
         * @return this builder
         *
         * @throws NullPointerException
         *     if {@code variables} is {@code null} or contains a {@code null} entry.
         * @throws IllegalArgumentException
         *     if {@code variables} is empty.
         */
        public Builder variables(List<Variable> variables) {
            // Check that the variables list is well-formed
            ArgumentUtil.checkNotNull(variables, "variables");
            if (variables.isEmpty()) {
                throw new IllegalArgumentException("variables must not be empty");
            }

            // Copy the variables while checking for null entries.
            List<Variable> newList = new ArrayList<>(variables.size());
            for (Variable variable : variables) {
                if (variable == null) {
                    throw new NullPointerException("variables cannot contain a null entry");
                }
                // TODO: check that the variables are sequential...or maybe remove the sequence.
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
    static Builder builder() {
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