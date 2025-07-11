package org.scharp.sas7bdat;

import com.epam.parso.Column;
import com.epam.parso.ColumnFormat;
import com.epam.parso.SasFileProperties;
import com.epam.parso.SasFileReader;
import com.epam.parso.impl.SasFileReaderImpl;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link Sas7bdatExporter} */
public class Sas7bdatExporterTest {

    private static void assertVariable(Variable variable, int variableNumber, Column column, String description) {
        assertEquals(variable.name(), column.getName(), description + " has wrong name");
        assertEquals(variableNumber, column.getId(), description + " has wrong number");
        assertEquals(variable.length(), column.getLength(), description + " has wrong length");
        assertEquals(variable.type() == VariableType.NUMERIC ? Number.class : String.class, column.getType());
        assertEquals(variable.label(), column.getLabel(), description + " has wrong label");

        Format outputFormat = variable.outputFormat();
        ColumnFormat parsoFormat = column.getFormat();
        assertEquals(outputFormat.name(), parsoFormat.getName(), description + " has wrong output format name");
        assertEquals(outputFormat.width(), parsoFormat.getWidth(), description + " has wrong output format width");
        assertEquals(outputFormat.numberOfDigits(), parsoFormat.getPrecision(), description + " has wrong digits");
    }

    private static void assertMetadata(Sas7bdatMetadata metadata, SasFileReader sasFileReader) {
        SasFileProperties sasFileProperties = sasFileReader.getSasFileProperties();

        // I suspect there's a bug the parso library where it returns the "Date" in UTC ignoring daylight
        // savings time.
        Instant utcCreationTime = metadata.creationTime().toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        Duration daylightSavingsTimeAdjustment = ZoneId.systemDefault().getRules().getDaylightSavings(utcCreationTime);
        Date expectedCreationDate = Date.from(utcCreationTime.minus(daylightSavingsTimeAdjustment));
        assertEquals(expectedCreationDate, sasFileProperties.getDateCreated());

        assertEquals(metadata.datasetLabel(), sasFileProperties.getFileLabel());

        // I suspect it's a bug in the parso library that the type is not returned.
        assertEquals("DATA", sasFileProperties.getFileType());

        // Hard-coded values
        assertEquals("9.0401M2", sasFileProperties.getSasRelease());
        assertEquals("UTF-8", sasFileProperties.getEncoding());
        assertEquals(1, sasFileProperties.getEndianness());
        assertEquals(null, sasFileProperties.getCompressionMethod());
        assertEquals("x86_64", sasFileProperties.getOsName());
        assertEquals("4.4.104-18.44", sasFileProperties.getOsType());
        assertEquals("Linux", sasFileProperties.getServerType());
        assertEquals(0, sasFileProperties.getDeletedRowCount());

        // Test the columns
        assertEquals(metadata.variables().size(), sasFileProperties.getColumnsCount());
        List<Column> columns = sasFileReader.getColumns();
        assertEquals(metadata.variables().size(), columns.size());
        for (int i = 0; i < metadata.variables().size(); i++) {
            assertVariable(metadata.variables().get(i), i + 1, columns.get(i), "column #" + (i + 1));
        }
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

                // Test the metadata headers
                assertMetadata(metadata, sasFileReader);

                // Assert some additional properties that are subject to change (but should not change unintentionally)
                SasFileProperties sasFileProperties = sasFileReader.getSasFileProperties();
                assertEquals(240, sasFileProperties.getRowLength()); // 234 rounded up
                assertEquals(262, sasFileProperties.getMixPageRowCount());
                assertEquals(0x10000, sasFileProperties.getHeaderLength());
                assertEquals(0x10000, sasFileProperties.getPageLength());
                assertEquals(4, sasFileProperties.getPageCount());

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

    /**
     * Variations for the streaming interface:
     * <ul>
     *   <li>{@link Sas7bdatExporter#Sas7bdatExporter(Path, Sas7bdatMetadata, int)}</li>
     *   <li>{@link Sas7bdatExporter#writeObservation(List)}</li>
     *   <li>{@link Sas7bdatExporter#isComplete()}</li>
     * </ul>
     *
     * Includes:
     * <ul>
     *   <li>Writing non-ASCII characters</li>
     *   <li>Writing CHARACTER values exactly as long as the variable</li>
     *   <li>Writing both CHARACTER and NUMERIC values</li>
     * </ul>
     *
     * @throws IOException
     */
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
                    // The exporter should not be complete until all observations are written.
                    assertFalse(exporter.isComplete());
                    exporter.writeObservation(List.of("Value #1 for Var #1!", "Value #1 for Var #2$", "Text3", "A", i));
                }
                assertTrue(exporter.isComplete());
            }

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = Files.newInputStream(targetLocation)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                // Test the metadata headers
                assertMetadata(metadata, sasFileReader);

                // Assert some additional properties that are subject to change (but should not change unintentionally)
                SasFileProperties sasFileProperties = sasFileReader.getSasFileProperties();
                assertEquals(240, sasFileProperties.getRowLength()); // 234 rounded up
                assertEquals(262, sasFileProperties.getMixPageRowCount());
                assertEquals(0x10000, sasFileProperties.getHeaderLength());
                assertEquals(0x10000, sasFileProperties.getPageLength());
                assertEquals(3677, sasFileProperties.getPageCount());

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

    private static String padToSize(String string, int targetLength) {
        assertThat("TEST BUG: string is already too long", string.length(), Matchers.lessThanOrEqualTo(targetLength));
        return string + "x".repeat(targetLength - string.length());
    }

    @Test
    public void testMaxSizedMetadata() throws IOException {

        Path targetLocation = Files.createTempFile("sas7bdat-testMaxSizedMetadata-", ".sas7bdat");
        try {
            List<Variable> maxSizedVariables = new ArrayList<>(Short.MAX_VALUE);
            for (int i = 0; i < Short.MAX_VALUE; i++) {
                Variable variable = Variable.builder()
                    .name(padToSize("VAR_" + i + "_", 32))
                    .type(VariableType.CHARACTER)
                    .length(5) // parso lib limits page size to 10000000
                    .label(padToSize("Variable #" + i + " label ", 256))
                    .inputFormat(new Format(padToSize("$FORMAT_", 32), Short.MAX_VALUE))
                    .outputFormat(new Format(padToSize("$OUT_FORMAT_", 32), Short.MAX_VALUE))
                    .build();
                maxSizedVariables.add(variable);
            }

            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder()
                .creationTime(LocalDateTime.of(3000, 12, 31, 23, 59, 59)) // parso's dates aren't correct at 19900 AD
                .datasetLabel(padToSize("Very long dataset label: ", 256))
                .datasetType(padToSize("DATATYPE", 8))
                .variables(maxSizedVariables)
                .build();

            // Write the dataset with max-sized metadata and no observations.
            Sas7bdatExporter.exportDataset(targetLocation, metadata, List.of());

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = Files.newInputStream(targetLocation)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                // Test the metadata headers
                assertMetadata(metadata, sasFileReader);

                // Assert some additional properties that are subject to change (but should not change unintentionally)
                SasFileProperties sasFileProperties = sasFileReader.getSasFileProperties();
                assertEquals(Short.MAX_VALUE * 5, sasFileProperties.getRowLength());
                assertEquals(0, sasFileProperties.getMixPageRowCount()); // observation is too large
                assertEquals(0x28400, sasFileProperties.getHeaderLength());
                assertEquals(0x28400, sasFileProperties.getPageLength());
                assertEquals(94, sasFileProperties.getPageCount());

                // Test the observations
                assertEquals(0, sasFileProperties.getRowCount());
                assertEquals(null, sasFileReader.readNext());
            }

        } finally {
            // Always clean up
            Files.deleteIfExists(targetLocation);
        }
    }

    @Test
    public void testMinSizedMetadata() throws IOException {

        Path targetLocation = Files.createTempFile("sas7bdat-testMinSizedMetadata-", ".sas7bdat");
        try {
            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder()
                .creationTime(LocalDateTime.of(1900, 1, 1, 0, 0, 0)) // parso's dates aren't correct at 1584 AD
                .datasetType("")
                .variables(List.of(Variable.builder().name("A").type(VariableType.CHARACTER).length(1).build()))
                .build();

            // Write the dataset with max-sized metadata and no observations.
            Sas7bdatExporter.exportDataset(targetLocation, metadata, List.of());

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = Files.newInputStream(targetLocation)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                // Test the metadata headers
                assertMetadata(metadata, sasFileReader);

                // Assert some additional properties that are subject to change (but should not change unintentionally)
                SasFileProperties sasFileProperties = sasFileReader.getSasFileProperties();
                assertEquals(metadata.variables().size(), sasFileProperties.getRowLength());
                assertEquals(56593, sasFileProperties.getMixPageRowCount());
                assertEquals(0x10000, sasFileProperties.getHeaderLength());
                assertEquals(0x10000, sasFileProperties.getPageLength());
                assertEquals(1, sasFileProperties.getPageCount());

                // Test the observations
                assertEquals(0, sasFileProperties.getRowCount());
                assertEquals(null, sasFileReader.readNext());
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

    @Test
    public void testConstructWriteObservationWithNullList() throws IOException {
        Path targetPath = Path.of("testConstructWriteObservationWithNullList.sas7bdat");
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(200).build())).
            build();

        // Create an exporter
        try {
            try (Sas7bdatExporter sas7bdatExporter = new Sas7bdatExporter(targetPath, metadata, 2)) {

                sas7bdatExporter.writeObservation(List.of("BEFORE"));

                // Write the null observation.
                Exception exception = assertThrows(
                    NullPointerException.class,
                    () -> sas7bdatExporter.writeObservation(null));
                assertEquals("observation must not be null", exception.getMessage());

                // The exception should not have corrupted the state of the exporter,
                // so it should be possible to continue exporting the file.
                sas7bdatExporter.writeObservation(List.of("AFTER"));
            }

            // Read the dataset with parso to confirm that the two observations that were successfully written.
            try (InputStream inputStream = Files.newInputStream(targetPath)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                // Test the headers
                assertMetadata(metadata, sasFileReader);

                // Test the observations
                assertEquals(2, sasFileReader.getSasFileProperties().getRowCount());
                assertArrayEquals(new Object[] { "BEFORE" }, sasFileReader.readNext());
                assertArrayEquals(new Object[] { "AFTER" }, sasFileReader.readNext());
                assertEquals(null, sasFileReader.readNext(), "more rows were read than expected");
            }

        } finally {
            Files.deleteIfExists(targetPath); // cleanup, just in case
        }
    }
}