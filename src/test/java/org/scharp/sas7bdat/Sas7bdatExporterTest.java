package org.scharp.sas7bdat;

import com.epam.parso.Column;
import com.epam.parso.SasFileProperties;
import com.epam.parso.SasFileReader;
import com.epam.parso.impl.SasFileReaderImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link Sas7bdatExporter} */
public class Sas7bdatExporterTest {

    private static void assertVariable(Variable variable, int variableNumber, Column column) {
        assertEquals(variable.name(), column.getName(), "incorrect variable name");
        assertEquals(variableNumber, column.getId(), "incorrect variable number");
        assertEquals(variable.length(), column.getLength(), "incorrect variable length");
        assertEquals(variable.type() == VariableType.NUMERIC ? Number.class : String.class, column.getType());
        assertEquals(variable.label(), column.getLabel(), "incorrect variable label");

        Format outputFormat = variable.outputFormat();
        assertEquals(outputFormat.name(), column.getFormat().getName(), "incorrect output format name");
        assertEquals(outputFormat.width(), column.getFormat().getWidth(), "incorrect output format width");
        assertEquals(outputFormat.numberOfDigits(), column.getFormat().getPrecision(), "incorrect output format digit");
    }

    @Test
    public void smokeTest() throws IOException {

        Path targetLocation = Files.createTempFile("sas7bdat-smokeTest-", ".sas7bdat");
        try {
            // Set a known "creation date" to make it easier to diff output from multiple runs.
            LocalDateTime creationDate = LocalDateTime.parse("2020-11-20T06:53:07");

            String greek = "\u0395\u03BB\u03BB\u03B7\u03BD\u03B9\u03BA\u03AC"; // Greek word for greek

            String datasetLabel = "A sample dataset: " + greek;

            String datasetType = "TestFile";

            Variable variable1 = Variable.builder().
                name("TEXT").
                type(VariableType.CHARACTER).
                length(200).
                label("Some simple text").
                inputFormat(new Format("$", 10)).
                build();

            Variable variable2 = Variable.builder().
                name("VERY_LONG_0123456789_123456789VR").
                type(VariableType.CHARACTER).
                length(20).
                label("A second text variable with a long name").
                outputFormat(new Format("$CHAR", 200)).
                build();

            Variable variable3 = Variable.builder().
                name("TEXT3").
                type(VariableType.CHARACTER).
                length(5).
                label("").
                outputFormat(new Format("$UPCASE", 10)).
                build();

            Variable variable4 = Variable.builder().
                name("Letter").
                type(VariableType.CHARACTER).
                length(1).
                label("A single letter").
                outputFormat(new Format("$ASCII", 1)).
                build();

            Variable variable5 = Variable.builder().
                name("MY_NUMBER").
                type(VariableType.NUMERIC).
                length(8).
                label("A number").
                inputFormat(new Format("d", 10)).
                build();

            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
                creationTime(creationDate).
                datasetLabel(datasetLabel).
                datasetType(datasetType).
                variables(List.of(variable1, variable2, variable3, variable4, variable5)).
                build();

            List<List<Object>> observations = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                observations.add(List.of("Value #" + i + " for Var #1!", "Var #2$", "Text3", "A", i));
            }

            // Write a data set.
            Sas7bdatExporter.exportDataset(targetLocation, metadata, observations);

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = Files.newInputStream(targetLocation)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);
                SasFileProperties sasFileProperties = sasFileReader.getSasFileProperties();

                // I suspect it's a bug in the parso library that it returns the "Date" in UTC.
                Date expectedCreationDate = Date.from(creationDate.atZone(ZoneOffset.UTC).toInstant());
                assertEquals(expectedCreationDate, sasFileProperties.getDateCreated());

                assertEquals(datasetLabel, sasFileProperties.getFileLabel());

                // I suspect it's a bug in the parso library that the type is not returned.
                assertEquals("DATA", sasFileProperties.getFileType());

                // Hard-coded values
                assertEquals("9.0401M2", sasFileProperties.getSasRelease());
                assertEquals("UTF-8", sasFileProperties.getEncoding());
                assertEquals(null, sasFileProperties.getCompressionMethod());
                assertEquals("x86_64", sasFileProperties.getOsName());
                assertEquals("4.4.104-18.44", sasFileProperties.getOsType());
                assertEquals("Linux", sasFileProperties.getServerType());
                assertEquals(0, sasFileProperties.getDeletedRowCount());

                // Test the columns
                assertEquals(metadata.variables().size(), sasFileProperties.getColumnsCount());
                List<Column> columns = sasFileReader.getColumns();
                assertVariable(variable1, 1, columns.get(0));
                assertVariable(variable2, 2, columns.get(1));
                assertVariable(variable3, 3, columns.get(2));
                assertVariable(variable4, 4, columns.get(3));
                assertVariable(variable5, 5, columns.get(4));

                // Test the observations
                assertEquals(observations.size(), sasFileProperties.getRowCount());
                int i = 0;
                Object[] row;
                while (null != (row = sasFileReader.readNext())) {
                    assertEquals(5, row.length);
                    assertEquals("Value #" + i + " for Var #1!", row[0]);
                    assertEquals("Var #2$", row[1]);
                    assertEquals("Text3", row[2]);
                    assertEquals("A", row[3]);
                    assertEquals(Long.valueOf(i), row[4]);
                    i++;
                }
                assertEquals(observations.size(), i, "parso didn't return all rows");
            }

        } finally {
            // Always clean up
            Files.deleteIfExists(targetLocation);
        }
    }

    @Test
    public void testStreamingInterface() throws IOException {

        Path targetLocation = Files.createTempFile("sas7bdat-streaming-", ".sas7bdat");
        try {
            // Set a known "creation date" to make it easier to diff output from multiple runs.
            LocalDateTime creationDate = LocalDateTime.parse("2020-11-20T06:53:07");

            String greek = "\u0395\u03BB\u03BB\u03B7\u03BD\u03B9\u03BA\u03AC"; // Greek word for greek

            String datasetLabel = "A sample dataset: " + greek;

            String datasetType = "TestFile";

            Variable variable1 = Variable.builder().
                name("TEXT").
                type(VariableType.CHARACTER).
                length(200).
                label("Some simple text").
                inputFormat(new Format("$", 10)).
                build();

            Variable variable2 = Variable.builder().
                name("VERY_LONG_0123456789_123456789VR").
                type(VariableType.CHARACTER).
                length(20).
                label("A second text variable with a long name").
                outputFormat(new Format("$CHAR", 200)).
                build();

            Variable variable3 = Variable.builder().
                name("TEXT3").
                type(VariableType.CHARACTER).
                length(5).
                label("").
                outputFormat(new Format("$UPCASE", 10)).
                build();

            Variable variable4 = Variable.builder().
                name("Letter").
                type(VariableType.CHARACTER).
                length(1).
                label("A single letter").
                outputFormat(new Format("$ASCII", 1)).
                build();

            Variable variable5 = Variable.builder().
                name("MY_NUMBER").
                type(VariableType.NUMERIC).
                length(8).
                label("A number").
                inputFormat(new Format("d", 10)).
                build();

            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
                creationTime(creationDate).
                datasetLabel(datasetLabel).
                datasetType(datasetType).
                variables(List.of(variable1, variable2, variable3, variable4, variable5)).
                build();

            // Write a data set using a streaming interface.
            final int totalObservations = 1_000_000;
            try (Sas7bdatExporter exporter = new Sas7bdatExporter(targetLocation, metadata, totalObservations)) {
                for (int i = 0; i < totalObservations; i++) {
                    exporter.writeObservation(List.of("Value #1 for Var #1!", "Value #1 for Var #2$", "Text3", "A", i));
                }
            }

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = Files.newInputStream(targetLocation)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);
                SasFileProperties sasFileProperties = sasFileReader.getSasFileProperties();

                // I suspect it's a bug in the parso library that it returns the "Date" in UTC.
                Date expectedCreationDate = Date.from(creationDate.atZone(ZoneOffset.UTC).toInstant());
                assertEquals(expectedCreationDate, sasFileProperties.getDateCreated());

                assertEquals(datasetLabel, sasFileProperties.getFileLabel());

                // I suspect it's a bug in the parso library that the type is not returned.
                assertEquals("DATA", sasFileProperties.getFileType());

                // Hard-coded values
                assertEquals("9.0401M2", sasFileProperties.getSasRelease());
                assertEquals("UTF-8", sasFileProperties.getEncoding());
                assertEquals(null, sasFileProperties.getCompressionMethod());
                assertEquals("x86_64", sasFileProperties.getOsName());
                assertEquals("4.4.104-18.44", sasFileProperties.getOsType());
                assertEquals("Linux", sasFileProperties.getServerType());
                assertEquals(0, sasFileProperties.getDeletedRowCount());

                // Test the columns
                assertEquals(metadata.variables().size(), sasFileProperties.getColumnsCount());
                List<Column> columns = sasFileReader.getColumns();
                assertVariable(variable1, 1, columns.get(0));
                assertVariable(variable2, 2, columns.get(1));
                assertVariable(variable3, 3, columns.get(2));
                assertVariable(variable4, 4, columns.get(3));
                assertVariable(variable5, 5, columns.get(4));

                // Test the observations
                assertEquals(totalObservations, sasFileProperties.getRowCount());
                int i = 0;
                Object[] row;
                while (null != (row = sasFileReader.readNext())) {
                    assertEquals(5, row.length);
                    assertEquals("Value #1 for Var #1!", row[0]);
                    assertEquals("Value #1 for Var #2$", row[1]);
                    assertEquals("Text3", row[2]);
                    assertEquals("A", row[3]);
                    assertEquals(Long.valueOf(i), row[4]);
                    i++;
                }
                assertEquals(totalObservations, i, "parso didn't return all rows");
            }
        } finally {
            // Always clean up
            Files.deleteIfExists(targetLocation);
        }
    }

    @Test
    public void testExportDatasetWithNullTargetFile() {
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(200).build())).
            build();
        List<List<Object>> observations = List.of();

        // Invoke the unit-under-test with a null path.
        Exception exception = assertThrows(
            NullPointerException.class,
            () -> Sas7bdatExporter.exportDataset(null, metadata, observations));
        assertEquals("targetLocation must not be null", exception.getMessage());
    }

    @Test
    public void testExportDatasetWithNullMetadata() throws IOException {
        Path targetPath = Path.of("testExportDatasetWithNullMetadata.sas7bdat");
        List<List<Object>> observations = List.of();

        try {
            // Invoke the unit-under-test with a null metadata object.
            Exception exception = assertThrows(
                NullPointerException.class,
                () -> Sas7bdatExporter.exportDataset(targetPath, null, observations));
            assertEquals("metadata must not be null", exception.getMessage());

            // Confirm that the file was not created.
            assertFalse(Files.exists(targetPath), "target file unexpectedly created");
        } finally {
            Files.deleteIfExists(targetPath); // cleanup, just in case
        }
    }

    @Test
    public void testExportDatasetWithNullObservationsList() throws IOException {
        Path targetPath = Path.of("testExportDatasetWithNullObservationsList.sas7bdat");
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(200).build())).
            build();

        try {
            // Invoke the unit-under-test with a null observations list.
            Exception exception = assertThrows(
                NullPointerException.class,
                () -> Sas7bdatExporter.exportDataset(targetPath, metadata, null));
            assertEquals("observations must not be null", exception.getMessage());

            // Confirm that the file was not created.
            assertFalse(Files.exists(targetPath), "target file unexpectedly created");
        } finally {
            Files.deleteIfExists(targetPath); // cleanup, just in case
        }
    }

    @Test
    public void testExportDatasetWithNullObservationRow() throws IOException {
        Path targetPath = Path.of("testExportDatasetWithNullObservationRow.sas7bdat");
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(200).build())).
            build();
        List<List<Object>> observations = Arrays.asList(List.of("BEFORE"), null, List.of("AFTER"));

        try {
            // Invoke the unit-under-test with a null observation.
            Exception exception = assertThrows(
                NullPointerException.class,
                () -> Sas7bdatExporter.exportDataset(targetPath, metadata, observations));
            assertEquals("observations must not contain a null observation", exception.getMessage());

            // The file should be half-written
            assertTrue(Files.exists(targetPath), "target file was not created");
        } finally {
            Files.deleteIfExists(targetPath); // cleanup
        }
    }

    @Test
    public void testConstructWithNullTargetFile() throws IOException {
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(200).build())).
            build();

        // Invoke the unit-under-test with a null path.
        Exception exception = assertThrows(
            NullPointerException.class,
            () -> new Sas7bdatExporter(null, metadata, 0));
        assertEquals("targetLocation must not be null", exception.getMessage());
    }

    @Test
    public void testConstructWithNullMetadata() throws IOException {
        Path targetPath = Path.of("testConstructWithNullMetadata.sas7bdat");

        try {
            // Invoke the unit-under-test with a null metadata object.
            Exception exception = assertThrows(
                NullPointerException.class,
                () -> new Sas7bdatExporter(targetPath, null, 0));
            assertEquals("metadata must not be null", exception.getMessage());

            // Confirm that the file was not created.
            assertFalse(Files.exists(targetPath), "target file unexpectedly created");
        } finally {
            Files.deleteIfExists(targetPath); // cleanup, just in case
        }
    }
}