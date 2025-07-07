package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class Sas7bdatMetadataTest {

    private static void assertSas7bdatMetadata(Sas7bdatMetadata metadata, LocalDateTime expectedCreationTime,
        String expectedDatasetType, String expectedDatasetLabel, List<Variable> expectedVariables) {

        assertEquals(expectedCreationTime, metadata.creationTime(), "incorrect creationTime");
        assertEquals(expectedDatasetType, metadata.datasetType(), "incorrect datasetType");
        assertEquals(expectedDatasetLabel, metadata.datasetLabel(), "incorrect datasetLabel");

        assertEquals(expectedVariables.size(), metadata.variables().size(), "incorrect variable size");
        for (int i = 0; i < expectedVariables.size(); i++) {
            assertEquals(expectedVariables.get(i), metadata.variables().get(i), "incorrect variable #" + (i + 1));
        }
    }

    @Test
    void setEarlyCreationTime() {
        LocalDateTime goodCreationTime = LocalDateTime.of(2000, 1, 2, 3, 4, 5, 6);
        Variable variable = new Variable("VAR", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(List.of(variable))
            .creationTime(goodCreationTime);

        // Setting the creation time to a value that's before 1582 should be an error.
        LocalDateTime badCreationTime = LocalDateTime.of(1581, 12, 31, 23, 59, 59);
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.creationTime(badCreationTime));
        assertEquals("creationTime must not be before the year 1582", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertSas7bdatMetadata(metadata, goodCreationTime, "DATA", "", List.of(variable));
    }

    @Test
    void setLateCreationTime() {
        LocalDateTime goodCreationTime = LocalDateTime.of(2000, 1, 2, 3, 4, 5, 6);
        Variable variable = new Variable("VAR", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(List.of(variable))
            .creationTime(goodCreationTime);

        // Setting the creation time to a value that's before 19,900 should be an error.
        LocalDateTime badCreationTime = LocalDateTime.of(19_901, 1, 1, 0, 0, 0);
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.creationTime(badCreationTime));
        assertEquals("creationTime must not be after the year 19900", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertSas7bdatMetadata(metadata, goodCreationTime, "DATA", "", List.of(variable));
    }

    @Test
    void setNullCreationTime() {
        Variable variable = new Variable(
            "VAR",
            VariableType.NUMERIC,
            8,
            "label",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);
        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(List.of(variable));

        // Setting the creation time to null should throw an exception.
        Exception exception = assertThrows(NullPointerException.class, () -> builder.creationTime(null));
        assertEquals("creationTime must not be null", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", List.of(variable));
    }

    @Test
    void setLongDatasetType() {
        Variable variable = new Variable(
            "VAR",
            VariableType.NUMERIC,
            8,
            "label",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);
        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(List.of(variable));

        // Setting the dataset type to a value that's 8 characters but 9 bytes in UTF-8 should be an error.
        final String sigma = "\u03C3"; // GREEK SMALL LETTER SIGMA (two bytes in UTF-8)
        String badDatasetType = sigma + "3456789";
        assertEquals(8, badDatasetType.length(), "TEST BUG: not testing encoding expansion");
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.datasetType(badDatasetType));
        assertEquals("datasetType must not be longer than 8 bytes when encoded with UTF-8", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", List.of(variable));
    }

    @Test
    void setNullDatasetType() {
        Variable variable = new Variable(
            "VAR",
            VariableType.NUMERIC,
            8,
            "label",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);
        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(List.of(variable));

        // Setting the dataset type to null should throw an exception.
        Exception exception = assertThrows(NullPointerException.class, () -> builder.datasetType(null));
        assertEquals("datasetType must not be null", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", List.of(variable));
    }

    @Test
    void setLongDatasetLabel() {
        List<Variable> variables = List.of(
            new Variable(
                "VAR",
                VariableType.NUMERIC,
                8,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED));
        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(variables).datasetLabel("before");

        // Setting the dataset label a string that's > 256 bytes should throw an exception.
        final String sigma = "\u03C3"; // GREEK SMALL LETTER SIGMA (two bytes in UTF-8)
        String badDatasetLabel = sigma.repeat(128) + "x";
        assertEquals(129, badDatasetLabel.length(), "TEST BUG: not testing encoding expansion");
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.datasetLabel(badDatasetLabel));
        assertEquals("datasetLabel must not be longer than 256 bytes when encoded with UTF-8", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "before", variables);
    }

    @Test
    void setNullDatasetLabel() {
        Variable variable = new Variable(
            "VAR",
            VariableType.NUMERIC,
            8,
            "label",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);
        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(List.of(variable));

        // Setting the dataset label to null should throw an exception.
        Exception exception = assertThrows(NullPointerException.class, () -> builder.datasetLabel(null));
        assertEquals("datasetLabel must not be null", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", List.of(variable));
    }

    @Test
    void setNullVariables() {
        Variable variable = new Variable(
            "VAR",
            VariableType.NUMERIC,
            8,
            "label",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);
        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(List.of(variable));

        // Setting the variables to null should throw an exception.
        Exception exception = assertThrows(NullPointerException.class, () -> builder.variables(null));
        assertEquals("variables must not be null", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", List.of(variable));
    }

    @Test
    void setReusedVariableName() {
        Variable variable1a = new Variable("V1", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
        Variable variable2 = new Variable("V2", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
        Variable variable3 = new Variable("V3", VariableType.CHARACTER, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
        Variable variable1b = new Variable("V1", VariableType.CHARACTER, 1, "", Format.UNSPECIFIED, Format.UNSPECIFIED);

        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(List.of(variable2));

        // Setting the variables with a repeated name should throw an exception.
        List<Variable> badVariableList = List.of(variable1a, variable3, variable1b);
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.variables(badVariableList));
        assertEquals("variables contains two variables named \"V1\"", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", List.of(variable2));
    }

    @Test
    void setEmptyVariables() {
        Variable variable = new Variable(
            "VAR",
            VariableType.NUMERIC,
            8,
            "label",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);
        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(List.of(variable));

        // Setting the variables to the empty list should throw an exception.
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.variables(List.of()));
        assertEquals("variables must not be empty", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", List.of(variable));
    }

    @Test
    void setTooManyVariables() {
        // Create a variables list that is too long.
        List<Variable> tooManyVariables = new ArrayList<>(Short.MAX_VALUE + 1);
        for (int i = 0; i < Short.MAX_VALUE + 1; i++) {
            Variable variable = new Variable(
                "VARIABLE_" + i,
                VariableType.CHARACTER,
                8,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED);
            tooManyVariables.add(variable);
        }

        // Create a legal list of variables.
        List<Variable> legalVariables = List.of(tooManyVariables.get(0));

        // Create the builder.
        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(legalVariables);

        // Setting the variables to a list that's too long should throw an exception.
        // The maximum number of variables is 32767.
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.variables(tooManyVariables));
        assertEquals("A SAS7BDAT cannot have more than 32767 variables", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", legalVariables);
    }

    @Test
    void setNullVariable() {
        Variable variable1 = new Variable(
            "V1",
            VariableType.NUMERIC,
            8,
            "a number",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);

        Variable variable2 = new Variable(
            "V2",
            VariableType.CHARACTER,
            5,
            "text",
            Format.UNSPECIFIED,
            new Format("$UPCASE", 5));

        Variable variable3 = new Variable(
            "V3",
            VariableType.CHARACTER,
            100,
            "text",
            Format.UNSPECIFIED,
            new Format("$ASCII", 10));

        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().variables(List.of(variable1));

        // Setting the variables to a list that contains a null entry should be an error.
        List<Variable> variablesWithNull = Arrays.asList(variable2, null, variable3);
        Exception exception = assertThrows(NullPointerException.class, () -> builder.variables(variablesWithNull));
        assertEquals("variables cannot contain a null entry", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", List.of(variable1));
    }

    @Test
    void testVariablesIsCopied() {
        // Tests that the builder's variables is copied.
        List<Variable> mutableVariables = new ArrayList<>();
        Variable originalVariable = new Variable(
            "ORIGINAL",
            VariableType.CHARACTER,
            10,
            "label",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);
        mutableVariables.add(originalVariable);

        // Create and populate the builder.
        LocalDateTime creationTime = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder().
            creationTime(creationTime).
            variables(mutableVariables);

        // Modify the variables that we gave to the builder.
        Variable replacementVariable = new Variable(
            "REPLACEMENT",
            VariableType.CHARACTER,
            13,
            "a new variable",
            new Format("$UPCASE", 10),
            Format.UNSPECIFIED);
        mutableVariables.clear();
        mutableVariables.add(replacementVariable);

        // Finish building.
        Sas7bdatMetadata metadata = builder.build();

        // Confirm that the metadata reflects the variables that were given to the builder, not what they became.
        assertSas7bdatMetadata(metadata, creationTime, "DATA", "", List.of(originalVariable));
    }

    /** Test that the return value of {@link Sas7bdatMetadata#variables()} doesn't support modification operations. */
    @Test
    void testVariablesIsUnmodifiable() {

        List<Variable> mutableVariables = new ArrayList<>();
        Variable originalVariable = new Variable(
            "ORIGINAL",
            VariableType.CHARACTER,
            10,
            "label",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);
        mutableVariables.add(originalVariable);

        // Create the metadata
        LocalDateTime creationTime = LocalDateTime.now();
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            creationTime(creationTime).
            variables(mutableVariables).
            build();

        // Get the variables from the metadata
        List<Variable> returnedVariables = metadata.variables();

        // Attempt to modify the variables.
        assertThrows(UnsupportedOperationException.class, () -> returnedVariables.clear());
        assertThrows(UnsupportedOperationException.class, () -> returnedVariables.remove(originalVariable));
        assertThrows(UnsupportedOperationException.class, () -> returnedVariables.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> returnedVariables.add(originalVariable));

        // Confirm that the metadata still reflects the variables that were given to the builder.
        assertSas7bdatMetadata(metadata, creationTime, "DATA", "", List.of(originalVariable));
    }

    @Test
    void buildWithAllDefaults() {
        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata.Builder builder = Sas7bdatMetadata.builder();

        // building without setting variables is illegal.
        Exception exception = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("variables must be set", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to fix the error and continue building.
        Variable variable = new Variable(
            "VAR",
            VariableType.NUMERIC,
            8,
            "label",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);
        Sas7bdatMetadata metadata = builder.variables(List.of(variable)).build();

        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", List.of(variable));
    }

    @Test
    void buildWithVariablesOnly() {
        Variable variable = new Variable(
            "V1",
            VariableType.NUMERIC,
            8,
            "a number",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);

        LocalDateTime beforeBuilder = LocalDateTime.now();
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().variables(List.of(variable)).build();

        assertThat(metadata.creationTime(), greaterThanOrEqualTo(beforeBuilder));
        assertSas7bdatMetadata(metadata, metadata.creationTime(), "DATA", "", List.of(variable));
    }

    @Test
    void buildWithRedundantSetting() {
        Variable variable1 = new Variable(
            "V1",
            VariableType.NUMERIC,
            8,
            "a number",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);

        Variable variable2 = new Variable(
            "V2",
            VariableType.CHARACTER,
            5,
            "text",
            Format.UNSPECIFIED,
            new Format("$UPCASE", 5));

        final LocalDateTime finalCreationTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0, 1);

        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder()
            // Set all values to something
            .creationTime(finalCreationTime.plusYears(100))
            .datasetType("DISCARD")
            .datasetLabel("DISCARD")
            .variables(List.of(variable1))

            // set all values to something else
            .creationTime(finalCreationTime)
            .datasetType("NEW_TYPE")
            .datasetLabel("Updated Label")
            .variables(List.of(variable2))

            // build
            .build();

        assertSas7bdatMetadata(metadata, finalCreationTime, "NEW_TYPE", "Updated Label", List.of(variable2));
    }

    @Test
    void buildWithAllValueSet() {
        Variable variable1 = new Variable(
            "V1",
            VariableType.NUMERIC,
            8,
            "a number",
            Format.UNSPECIFIED,
            Format.UNSPECIFIED);

        Variable variable2 = new Variable(
            "V2",
            VariableType.CHARACTER,
            5,
            "text",
            Format.UNSPECIFIED,
            new Format("$UPCASE", 5));

        final LocalDateTime creationTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0, 1);

        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            creationTime(creationTime).
            datasetType("MY_TYPE").
            datasetLabel("My Label").
            variables(List.of(variable1, variable2)).
            build();

        assertSas7bdatMetadata(metadata, creationTime, "MY_TYPE", "My Label", List.of(variable1, variable2));
    }

    @Test
    void buildWithMinimValues() {
        List<Variable> minVariables = List.of(
            new Variable("V", VariableType.CHARACTER, 1, "", Format.UNSPECIFIED, Format.UNSPECIFIED));

        final LocalDateTime minCreationTime = LocalDateTime.of(1582, 1, 1, 0, 0, 0);

        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            creationTime(minCreationTime).
            datasetType("").
            datasetLabel("").
            variables(minVariables).
            build();

        assertSas7bdatMetadata(metadata, minCreationTime, "", "", minVariables);
    }

    @Test
    void buildWithMaximumValues() {
        // The maximum number of variables is 32767.
        List<Variable> maxVariables = new ArrayList<>(Short.MAX_VALUE);
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            Variable variable = new Variable(
                "VARIABLE_" + i,
                VariableType.CHARACTER,
                8,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED);
            maxVariables.add(variable);
        }

        final LocalDateTime maxCreationTime = LocalDateTime.of(19_900, 12, 31, 23, 59, 59);

        // The maximum length of a dataset type is 8 bytes.
        final String maxType = "DATATYPE";

        // The maximum length of a dataset label is 256 bytes.
        final String maxLabel = "X".repeat(256);

        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            creationTime(maxCreationTime).
            datasetType(maxType).
            datasetLabel(maxLabel).
            variables(maxVariables).
            build();

        assertSas7bdatMetadata(metadata, maxCreationTime, maxType, maxLabel, maxVariables);
    }
}