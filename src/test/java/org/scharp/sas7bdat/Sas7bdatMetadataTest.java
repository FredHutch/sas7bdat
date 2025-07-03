package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class Sas7bdatMetadataTest {

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
        assertFalse(metadata.creationTime().isBefore(beforeBuilder));
        assertEquals("DATA", metadata.datasetType());
        assertEquals("", metadata.datasetLabel());
        assertEquals(1, metadata.variables().size());
        assertEquals(variable, metadata.variables().get(0));
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
        assertNotNull(metadata.creationTime());
        assertFalse(metadata.creationTime().isBefore(beforeBuilder));
        assertEquals("DATA", metadata.datasetType());
        assertEquals("", metadata.datasetLabel());
        assertEquals(1, metadata.variables().size());
        assertEquals(variable, metadata.variables().get(0));
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
        assertNotNull(metadata.creationTime());
        assertFalse(metadata.creationTime().isBefore(beforeBuilder));
        assertEquals("DATA", metadata.datasetType());
        assertEquals("", metadata.datasetLabel());
        assertEquals(1, metadata.variables().size());
        assertEquals(variable, metadata.variables().get(0));
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
        assertNotNull(metadata.creationTime());
        assertFalse(metadata.creationTime().isBefore(beforeBuilder));
        assertEquals("DATA", metadata.datasetType());
        assertEquals("", metadata.datasetLabel());
        assertEquals(1, metadata.variables().size());
        assertEquals(variable, metadata.variables().get(0));
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

        // Setting the variables to null should throw an exception.
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.variables(List.of()));
        assertEquals("variables must not be empty", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Sas7bdatMetadata metadata = builder.build();
        assertNotNull(metadata.creationTime());
        assertFalse(metadata.creationTime().isBefore(beforeBuilder));
        assertEquals("DATA", metadata.datasetType());
        assertEquals("", metadata.datasetLabel());
        assertEquals(1, metadata.variables().size());
        assertEquals(variable, metadata.variables().get(0));
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
        assertNotNull(metadata.creationTime());
        assertFalse(metadata.creationTime().isBefore(beforeBuilder));
        assertEquals("DATA", metadata.datasetType());
        assertEquals("", metadata.datasetLabel());
        assertEquals(1, metadata.variables().size());
        assertEquals(variable1, metadata.variables().get(0));
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

        assertNotNull(metadata.creationTime());
        assertFalse(metadata.creationTime().isBefore(beforeBuilder));
        assertEquals("DATA", metadata.datasetType());
        assertEquals("", metadata.datasetLabel());
        assertEquals(1, metadata.variables().size());
        assertEquals(variable, metadata.variables().get(0));
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

        assertNotNull(metadata.creationTime());
        assertFalse(metadata.creationTime().isBefore(beforeBuilder));
        assertEquals("DATA", metadata.datasetType());
        assertEquals(1, metadata.variables().size());
        assertEquals(variable, metadata.variables().get(0));
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

        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder()
            // Set all values to something
            .creationTime(LocalDateTime.of(2020, 1, 1, 0, 0, 0, 1))
            .datasetType("DISCARD")
            .datasetLabel("DISCARD")
            .variables(List.of(variable1))

            // set all values to something else
            .creationTime(LocalDateTime.of(1999, 12, 31, 23, 59, 59, 999_999_999))
            .datasetType("NEW_TYPE")
            .datasetLabel("Updated Label")
            .variables(List.of(variable2))

            // build
            .build();

        assertEquals(LocalDateTime.of(1999, 12, 31, 23, 59, 59, 999_999_999), metadata.creationTime());
        assertEquals("NEW_TYPE", metadata.datasetType());
        assertEquals("Updated Label", metadata.datasetLabel());
        assertEquals(1, metadata.variables().size());
        assertEquals(variable2, metadata.variables().get(0));
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

        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            creationTime(LocalDateTime.of(2020, 1, 1, 0, 0, 0, 1)).
            datasetType("MY_TYPE").
            datasetLabel("My Label").
            variables(List.of(variable1, variable2)).
            build();

        assertEquals(LocalDateTime.of(2020, 1, 1, 0, 0, 0, 1), metadata.creationTime());
        assertEquals(2, metadata.variables().size());
        assertEquals("MY_TYPE", metadata.datasetType());
        assertEquals("My Label", metadata.datasetLabel());
        assertEquals(variable1, metadata.variables().get(0));
        assertEquals(variable2, metadata.variables().get(1));
    }
}