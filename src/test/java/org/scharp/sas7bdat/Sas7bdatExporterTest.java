///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import com.epam.parso.Column;
import com.epam.parso.ColumnFormat;
import com.epam.parso.SasFileProperties;
import com.epam.parso.SasFileReader;
import com.epam.parso.impl.SasFileReaderImpl;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

        // I suspect there's a bug the parso library where it returns the "Date" in UTC ignoring daylight saving time.
        Instant utcCreationTime = metadata.creationTime().toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        Duration daylightSavingsTimeAdjustment = ZoneId.systemDefault().getRules().getDaylightSavings(utcCreationTime);
        Date expectedCreationDate = Date.from(utcCreationTime.minus(daylightSavingsTimeAdjustment));
        assertEquals(expectedCreationDate, sasFileProperties.getDateCreated());

        assertEquals(metadata.datasetName(), sasFileProperties.getName());
        assertEquals(metadata.datasetLabel(), sasFileProperties.getFileLabel());

        // I suspect it's a bug in the parso library that the type is not returned.
        assertEquals("DATA", sasFileProperties.getFileType());

        // Hard-coded values
        assertEquals("9.0401M2", sasFileProperties.getSasRelease());
        assertEquals("UTF-8", sasFileProperties.getEncoding());
        assertEquals(1, sasFileProperties.getEndianness());
        assertNull(sasFileProperties.getCompressionMethod());
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

        Path targetDirectory = Files.createTempDirectory("sas7bdat-smokeTest");
        Path targetFile = targetDirectory.resolve("dataset.sas7bdat");
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

            // Add missing values
            for (MissingValue missingValue : MissingValue.values()) {
                observations.add(List.of(missingValue.toString(), "", "", "", missingValue));
            }

            // Add dates and times
            for (Object date : List.of(
                LocalDate.of(1960, 1, 1),
                LocalDate.of(1959, 12, 31),
                LocalDate.of(2020, 1, 1),
                LocalTime.of(0, 0, 0),
                LocalTime.of(16, 17, 18),
                LocalTime.of(23, 59, 59, 999_999_999),
                LocalDateTime.of(1960, 1, 1, 0, 0, 0, 0),
                LocalDateTime.of(1959, 12, 31, 23, 59, 59, 999_999_999),
                LocalDateTime.of(2020, 7, 4, 9, 12, 13))) {
                observations.add(List.of(date.toString(), "", "", "", date));
            }

            // Write a dataset.
            Sas7bdatExporter.exportDataset(targetFile, metadata, observations);

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = Files.newInputStream(targetFile)) {
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
                    if (i < 1000) {
                        // values
                        assertArrayEquals(
                            new Object[] { "Value #" + i + " for Var #1!", "Var #2$", "Text3", "A", Long.valueOf(i) },
                            row);
                    } else if (i < 1000 + MissingValue.values().length) {
                        // missing value tests
                        assertArrayEquals(
                            new Object[] { MissingValue.values()[i - 1000].toString(), null, null, null, null },
                            row);
                    } else {
                        // date/time tests
                        int dateIndex = i - MissingValue.values().length - 1000;
                        switch (i - MissingValue.values().length - 1000) {
                        case 0:
                            assertArrayEquals(new Object[] { "1960-01-01", null, null, null, 0L }, row);
                            break;

                        case 1:
                            assertArrayEquals(new Object[] { "1959-12-31", null, null, null, -1L }, row);
                            break;

                        case 2:
                            assertArrayEquals(new Object[] { "2020-01-01", null, null, null, 365L * 60 + 15 }, row);
                            break;

                        case 3:
                            assertArrayEquals(new Object[] { "00:00", null, null, null, 0L }, row);
                            break;

                        case 4:
                            assertArrayEquals(new Object[] { "16:17:18", null, null, null, 58_638L }, row);
                            break;

                        case 5:
                            assertArrayEquals(new Object[] { "23:59:59.999999999", null, null, null, 86399.999999999 },
                                row);
                            break;

                        case 6:
                            assertArrayEquals(new Object[] { "1960-01-01T00:00", null, null, null, 0L }, row);
                            break;

                        case 7:
                            assertArrayEquals(new Object[] { "1959-12-31T23:59:59.999999999", null, null, null, -1E-9 },
                                row);
                            break;

                        case 8:
                            assertArrayEquals(new Object[] { "2020-07-04T09:12:13", null, null, null, 1909473133L },
                                row);
                            break;

                        default:
                            fail("Unexpected row found at " + i + " (" + dateIndex + ")");
                        }
                    }
                    i++;
                }
                assertEquals(observations.size(), i, "parso didn't return all rows");
            }

        } finally {
            // Always clean up
            Files.deleteIfExists(targetFile);
            Files.deleteIfExists(targetDirectory);
        }
    }

    @Test
    public void testExportDatasetToExistingFile() throws IOException {

        Path targetDirectory = Files.createTempDirectory("sas7bdat-testExportDatasetToExistingFile");
        Path targetFile = targetDirectory.resolve("dataset.sas7bdat");
        try {
            // Write some garbage information to the file.
            try (Writer writer = Files.newBufferedWriter(targetFile)) {
                for (int i = 0; i < 1_000_000; i++) {
                    writer.write("This is garbage " + i);
                }
            }
            long originalFileSize = Files.size(targetFile);

            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder()
                .variables(List.of(Variable.builder().name("A").type(VariableType.CHARACTER).length(1).build()))
                .build();

            // Write the dataset to a file that already has data.
            Sas7bdatExporter.exportDataset(targetFile, metadata, List.of(List.of("1"), List.of("2")));

            // Confirm that this reduced the file size (the file was opened for truncation).
            assertThat(Files.size(targetFile), Matchers.lessThan(originalFileSize));

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = Files.newInputStream(targetFile)) {
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
                assertEquals(2, sasFileProperties.getRowCount());
                assertArrayEquals(new Object[] { "1" }, sasFileReader.readNext());
                assertArrayEquals(new Object[] { "2" }, sasFileReader.readNext());
                assertNull(sasFileReader.readNext());
            }

        } finally {
            // Always clean up
            Files.deleteIfExists(targetFile);
            Files.deleteIfExists(targetDirectory);
        }
    }

    /**
     * Variations for the streaming interface:
     * <ul>
     *   <li>{@link Sas7bdatExporter#Sas7bdatExporter(Path, Sas7bdatMetadata, int)}</li>
     *   <li>{@link Sas7bdatExporter#writeObservation(List)}</li>
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

            // Write a dataset using a streaming interface.
            final int totalObservations = 1_000_000;
            try (Sas7bdatExporter exporter = new Sas7bdatExporter(targetLocation, metadata, totalObservations)) {
                for (int i = 0; i < totalObservations; i++) {
                    // The exporter should not be complete until all observations are written.
                    exporter.writeObservation(List.of("Value #1 for Var #1!", "Value #1 for Var #2$", "Text3", "A", i));
                }
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

    /**
     * Variations for the streaming interface:
     * <ul>
     *   <li>{@link Sas7bdatExporter#Sas7bdatExporter(OutputStream, Sas7bdatMetadata, int)}</li>
     *   <li>{@link Sas7bdatExporter#writeObservation(List)}</li>
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
    public void testStreamingToOutputStream() throws IOException {

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

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

            // Write a dataset using a streaming interface.
            final int totalObservations = 1_000;
            try (Sas7bdatExporter exporter = new Sas7bdatExporter(outputStream, metadata, totalObservations)) {
                for (int i = 0; i < totalObservations; i++) {
                    // The exporter should not be complete until all observations are written.
                    exporter.writeObservation(List.of("Value #1 for Var #1!", "Value #1 for Var #2$", "Text3", "A", i));
                }
            }

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
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
        }
    }

    /**
     * Tests that the IllegalStateException that is thrown by {@link Sas7bdatExporter#close()} when too few observations
     * are written doesn't replace the original exception.
     * <p>
     * This isn't really a test of Sas7bdatExporter, but that it works correctly with the JVM.
     * </p>
     */
    @Test
    public void testUnhandledExceptionWhenWritingObservations() throws IOException {

        Path targetLocation = Files.createTempFile("sas7bdat-testUnhandledExceptionWhenWritingObservations-",
            ".sas7bdat");
        try {
            Variable variable = Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(100).build();
            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().variables(List.of(variable)).build();

            Exception ioException = assertThrows(
                IOException.class,
                () -> {
                    try (Sas7bdatExporter exporter = new Sas7bdatExporter(targetLocation, metadata, 100)) {
                        // Write a few observations.
                        for (int i = 0; i < 10; i++) {
                            exporter.writeObservation(List.of("Value"));
                        }

                        // Throw an exception, simulating some kind of problem.
                        throw new IOException("my unhandled exception");
                    }
                });

            // Confirm that the IOException was unhandled and that the IllegalStateException was suppressed.
            assertEquals("my unhandled exception", ioException.getMessage());
            assertEquals(1, ioException.getSuppressed().length);
            assertEquals(
                "The constructor was told to expect 100 observation(s) but only 10 were written.",
                ioException.getSuppressed()[0].getMessage());

            // The dataset is corrupt, so there's no need to check its contents.

        } finally {
            // Always clean up
            Files.deleteIfExists(targetLocation);
        }
    }

    @Test
    public void testWritingFewerObservationsThanPromised() throws IOException {

        Path targetLocation = Files.createTempFile("sas7bdat-testWritingFewerObservationsThanPromised-", ".sas7bdat");
        try {
            Variable variable = Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(20000).build();
            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().variables(List.of(variable)).build();

            // Promise 100 observations but only write 99.
            Exception exception = assertThrows(
                IllegalStateException.class,
                () -> {
                    try (Sas7bdatExporter exporter = new Sas7bdatExporter(targetLocation, metadata, 100)) {
                        for (int i = 0; i < 99; i++) {
                            exporter.writeObservation(List.of("Value"));
                        }
                    }
                });
            assertEquals(
                "The constructor was told to expect 100 observation(s) but only 99 were written.",
                exception.getMessage());

            // The dataset is corrupt, so there's no need to check its contents.

        } finally {
            // Always clean up
            Files.deleteIfExists(targetLocation);
        }
    }

    @Test
    public void testWritingMoreObservationsThanPromised() throws IOException {

        Path targetLocation = Files.createTempFile("sas7bdat-testWritingMoreObservationsThanPromised-", ".sas7bdat");
        try {
            Variable variable = Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(100).build();
            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().variables(List.of(variable)).build();

            // Promise 1234 observations but only write 1235.
            int totalObservations = 1234;
            try (Sas7bdatExporter exporter = new Sas7bdatExporter(targetLocation, metadata, totalObservations)) {
                for (int i = 0; i < totalObservations; i++) {
                    exporter.writeObservation(List.of("Value #" + i));
                }

                // Write one more.
                Exception exception = assertThrows(
                    IllegalStateException.class,
                    () -> exporter.writeObservation(List.of("Too Many")));
                assertEquals("wrote more observations than promised in the constructor", exception.getMessage());
            }

            // The dataset should be well-formed because the exception in writeObservation should not
            // have changed any state and then close() should have closed the dataset with the original
            // observations.

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = Files.newInputStream(targetLocation)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                // Test the metadata headers
                assertMetadata(metadata, sasFileReader);

                // Assert some additional properties that are subject to change (but should not change unintentionally)
                SasFileProperties sasFileProperties = sasFileReader.getSasFileProperties();
                assertEquals(variable.length(), sasFileProperties.getRowLength());
                assertEquals(635, sasFileProperties.getMixPageRowCount());
                assertEquals(0x10000, sasFileProperties.getHeaderLength());
                assertEquals(0x10000, sasFileProperties.getPageLength());
                assertEquals(2, sasFileProperties.getPageCount());

                // Test the observations
                assertEquals(totalObservations, sasFileProperties.getRowCount());
                int i = 0;
                Object[] row;
                while (null != (row = sasFileReader.readNext())) {
                    assertArrayEquals(new Object[] { "Value #" + i }, row);
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
                assertNull(sasFileReader.readNext());
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
                assertNull(sasFileReader.readNext());
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
    public void testExportDatasetWithNullCharacterValue() throws IOException {
        Path targetPath = Path.of("testExportDatasetWithNullCharacterValue.sas7bdat");
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(200).build())).
            build();

        List<List<Object>> observations = List.of(
            Arrays.asList(new Object[] { "good value" }),
            Arrays.asList(new Object[] { null }), // bad value
            Arrays.asList(new Object[] { "good value" }));

        try {
            // Invoke the unit-under-test with an observation that contains null.
            Exception exception = assertThrows(
                NullPointerException.class,
                () -> Sas7bdatExporter.exportDataset(targetPath, metadata, observations));
            assertEquals("null given as a value to TEXT, which has a CHARACTER type", exception.getMessage());

            // In this case, the file is created.  Perhaps it should have been deleted.
            assertTrue(Files.exists(targetPath), "target file not created");
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
    public void testConstructWithNullPath() {
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(200).build())).
            build();

        // Invoke the unit-under-test with a null path.
        Exception exception = assertThrows(
            NullPointerException.class,
            () -> new Sas7bdatExporter((Path) null, metadata, 0));
        assertEquals("targetLocation must not be null", exception.getMessage());
    }

    @Test
    public void testConstructWithNullOutputStream() {
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(200).build())).
            build();

        // Invoke the unit-under-test with a null path.
        Exception exception = assertThrows(
            NullPointerException.class,
            () -> new Sas7bdatExporter((OutputStream) null, metadata, 0));
        assertEquals("outputStream must not be null", exception.getMessage());
    }

    @Test
    public void testConstructWithNullMetadata() throws IOException {
        Path targetPath = Path.of("testConstructWithNullMetadata.sas7bdat");
        try {
            // Invoke the Path constructor with a null metadata object.
            Exception exception = assertThrows(
                NullPointerException.class,
                () -> new Sas7bdatExporter(targetPath, null, 0));
            assertEquals("metadata must not be null", exception.getMessage());

            // Confirm that the file was not created.
            assertFalse(Files.exists(targetPath), "target file unexpectedly created");

        } finally {
            Files.deleteIfExists(targetPath); // cleanup
        }

        // Invoke the OutputStream constructor with a null metadata object.
        try (OutputStream outputStream = new ByteArrayOutputStream()) {
            Exception exception = assertThrows(
                NullPointerException.class,
                () -> new Sas7bdatExporter(outputStream, null, 0));
            assertEquals("metadata must not be null", exception.getMessage());
        }
    }

    @Test
    public void testConstructWithNegativeTotalObservationsInDataset() throws IOException {
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(200).build())).
            build();

        Path targetPath = Path.of("testConstructWithNullMetadata.sas7bdat");
        try {
            // Invoke the Path constructor with a negative totalObservationsInDataset.
            Exception exception = assertThrows(
                IllegalArgumentException.class,
                () -> new Sas7bdatExporter(targetPath, metadata, -1));
            assertEquals("totalObservationsInDataset must not be negative", exception.getMessage());

            // Confirm that the file was not created.
            assertFalse(Files.exists(targetPath), "target file unexpectedly created");
        } finally {
            Files.deleteIfExists(targetPath); // cleanup
        }

        // Invoke the OutputStream constructor with a negative totalObservationsInDataset.
        try (OutputStream outputStream = new ByteArrayOutputStream()) {
            Exception exception = assertThrows(
                IllegalArgumentException.class,
                () -> new Sas7bdatExporter(outputStream, metadata, -1));
            assertEquals("totalObservationsInDataset must not be negative", exception.getMessage());
        }
    }

    @Test
    public void testWriteObservationWithNullList() throws IOException {
        Path targetPath = Path.of("testWriteObservationWithNullList.sas7bdat");
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
                assertNull(sasFileReader.readNext(), "more rows were read than expected");
            }

        } finally {
            Files.deleteIfExists(targetPath); // cleanup, just in case
        }
    }

    @Test
    public void testWriteObservationWithWrongSizeList() throws IOException {
        // Create a dataset with variables of each type.
        Path targetPath = Path.of("testWriteObservationWithWrongSizeList.sas7bdat");
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(
                Variable.builder().name("V1").type(VariableType.CHARACTER).length(9).build(),
                Variable.builder().name("V2").type(VariableType.NUMERIC).length(8).build())).
            build();

        // Create an exporter
        try {
            try (Sas7bdatExporter sas7bdatExporter = new Sas7bdatExporter(targetPath, metadata, 2)) {

                sas7bdatExporter.writeObservation(List.of("BEFORE", 1));

                // Write an observation with no values (obviously too few)
                Exception exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> sas7bdatExporter.writeObservation(List.of()));
                assertEquals("observation has too many values, expected 2 but got 0", exception.getMessage());

                // Write an observation with too few values
                exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> sas7bdatExporter.writeObservation(List.of("bad list")));
                assertEquals("observation has too many values, expected 2 but got 1", exception.getMessage());

                // Write an observation with too many values, even if the excess variable is null.
                exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> sas7bdatExporter.writeObservation(Arrays.asList("bad list", 100, null)));
                assertEquals("observation has too few values, expected 2 but got 3", exception.getMessage());

                // The exceptions should not have corrupted the state of the exporter,
                // so it should be possible to continue exporting the file.
                sas7bdatExporter.writeObservation(List.of("AFTER", 2));
            }

            // Read the dataset with parso to confirm that the two observations that were successfully written.
            try (InputStream inputStream = Files.newInputStream(targetPath)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                // Test the headers
                assertMetadata(metadata, sasFileReader);

                // Test the observations
                assertEquals(2, sasFileReader.getSasFileProperties().getRowCount());
                assertArrayEquals(new Object[] { "BEFORE", 1L }, sasFileReader.readNext());
                assertArrayEquals(new Object[] { "AFTER", 2L }, sasFileReader.readNext());
                assertNull(sasFileReader.readNext(), "more rows were read than expected");
            }

        } finally {
            Files.deleteIfExists(targetPath); // always cleanup
        }
    }

    @Test
    public void testWriteObservationWithBadValueForVariableType() throws IOException {
        // Create a dataset with variables of each type.
        Path targetPath = Path.of("testWriteObservationWithBadValueForVariableType.sas7bdat");
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(
                Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(9).build(),
                Variable.builder().name("NUMBER").type(VariableType.NUMERIC).length(8).build())).
            build();

        // Create an exporter
        try {
            try (Sas7bdatExporter sas7bdatExporter = new Sas7bdatExporter(targetPath, metadata, 2)) {

                sas7bdatExporter.writeObservation(List.of("BEFORE", 1));

                // Write a null value to the CHARACTER variable
                Exception exception = assertThrows(
                    NullPointerException.class,
                    () -> sas7bdatExporter.writeObservation(Arrays.asList(null, 100)));
                assertEquals("null given as a value to TEXT, which has a CHARACTER type", exception.getMessage());

                // Write a value to the CHARACTER variable that is too long.
                exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> sas7bdatExporter.writeObservation(Arrays.asList("X".repeat(10), 100)));
                assertEquals("A value of 10 bytes was given to the variable named TEXT, which has a length of 9",
                    exception.getMessage());

                // Write a value that's not a String to the CHARACTER variable.
                exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> sas7bdatExporter.writeObservation(Arrays.asList(new StringBuilder("bad"), 100)));
                assertEquals(
                    "A java.lang.StringBuilder was given as a value to the variable named TEXT, which has a CHARACTER type "
                        + "(CHARACTER values must be of type java.lang.String)",
                    exception.getMessage());

                // Write a value that's not a Numeric to the NUMERIC variable.
                exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> sas7bdatExporter.writeObservation(List.of("ok", "100")));
                assertEquals(
                    "A java.lang.String was given as a value to the variable named NUMBER, which has a NUMERIC type " +
                        "(NUMERIC values must be null or of type org.scharp.sas7bdat.MissingValue, java.time.LocalDate, java.time.LocalTime, java.time.LocalDateTime, or java.lang.Number)",
                    exception.getMessage());

                // The exception should not have corrupted the state of the exporter,
                // so it should be possible to continue exporting the file.
                sas7bdatExporter.writeObservation(List.of("AFTER", 2));
            }

            // Read the dataset with parso to confirm that the two observations that were successfully written.
            try (InputStream inputStream = Files.newInputStream(targetPath)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                // Test the headers
                assertMetadata(metadata, sasFileReader);

                // Test the observations
                assertEquals(2, sasFileReader.getSasFileProperties().getRowCount());
                assertArrayEquals(new Object[] { "BEFORE", 1L }, sasFileReader.readNext());
                assertArrayEquals(new Object[] { "AFTER", 2L }, sasFileReader.readNext());
                assertNull(sasFileReader.readNext(), "more rows were read than expected");
            }

        } finally {
            Files.deleteIfExists(targetPath); // always cleanup
        }
    }

    /**
     * Tests what happens when the observation given to {@link Sas7bdatExporter#writeObservation(List)} is later
     * modified (for example, if the caller wants to re-use an ArrayList).
     *
     * @throws IOException
     */
    @Test
    public void testWriteObservationCopiesObservation() throws IOException {
        // Create a dataset with variables of each type.
        Path targetPath = Path.of("testWriteObservationCopiesObservation.sas7bdat");
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            variables(List.of(
                Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(9).build(),
                Variable.builder().name("NUMBER").type(VariableType.NUMERIC).length(8).build())).
            build();

        // Create an exporter
        try {
            try (Sas7bdatExporter sas7bdatExporter = new Sas7bdatExporter(targetPath, metadata, 1)) {
                // Write an observation.
                List<Object> observation = new ArrayList<>(2);
                AtomicInteger mutableNumber = new AtomicInteger(1);
                observation.add("ORIGINAL");
                observation.add(mutableNumber);
                sas7bdatExporter.writeObservation(observation);

                // Modify the numeric value.
                mutableNumber.decrementAndGet();

                // Modify the list's contents
                observation.set(0, "MODIFIED");
                observation.set(1, 2L);
            }

            // Read the dataset with parso to confirm that the two observations that were successfully written.
            try (InputStream inputStream = Files.newInputStream(targetPath)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                // Test the headers
                assertMetadata(metadata, sasFileReader);

                // Test the observations
                assertEquals(1, sasFileReader.getSasFileProperties().getRowCount());
                assertArrayEquals(new Object[] { "ORIGINAL", 1L }, sasFileReader.readNext());
                assertNull(sasFileReader.readNext(), "more rows were read than expected");
            }

        } finally {
            Files.deleteIfExists(targetPath); // always cleanup
        }
    }

    @Test
    public void testClose() throws IOException {
        Path targetPath = Path.of("testClose.sas7bdat");
        try {
            // Create an exporter
            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
                variables(List.of(Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(200).build())).
                build();
            try (Sas7bdatExporter sas7bdatExporter = new Sas7bdatExporter(targetPath, metadata, 2)) {

                // Write the observations
                sas7bdatExporter.writeObservation(List.of("ROW1"));
                sas7bdatExporter.writeObservation(List.of("ROW2"));

                // Close the exporter.
                // At this point the file should be valid.
                sas7bdatExporter.close();

                // Read the dataset with parso to confirm that the two observations that were successfully written.
                try (InputStream inputStream = Files.newInputStream(targetPath)) {
                    SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                    // Test the headers
                    assertMetadata(metadata, sasFileReader);

                    // Test the observations
                    assertEquals(2, sasFileReader.getSasFileProperties().getRowCount());
                    assertArrayEquals(new Object[] { "ROW1" }, sasFileReader.readNext());
                    assertArrayEquals(new Object[] { "ROW2" }, sasFileReader.readNext());
                    assertNull(sasFileReader.readNext(), "more rows were read than expected");
                }

                // It's not legal to write a new observation after the exporter has been closed.
                Exception exception = assertThrows(
                    IllegalStateException.class,
                    () -> sas7bdatExporter.writeObservation(List.of("ROW3")));
                assertEquals("Cannot invoke writeObservation on closed exporter", exception.getMessage());

                // Close the exporter again.  This does not throw an exception for conformance to AutoCloseable.
                sas7bdatExporter.close();

                // Nothing done above should have modified the contents of the file.
                try (InputStream inputStream = Files.newInputStream(targetPath)) {
                    SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                    // Test the headers
                    assertMetadata(metadata, sasFileReader);

                    // Test the observations
                    assertEquals(2, sasFileReader.getSasFileProperties().getRowCount());
                    assertArrayEquals(new Object[] { "ROW1" }, sasFileReader.readNext());
                    assertArrayEquals(new Object[] { "ROW2" }, sasFileReader.readNext());
                    assertNull(sasFileReader.readNext(), "more rows were read than expected");
                }
            }

        } finally {
            Files.deleteIfExists(targetPath); // always cleanup
        }
    }

    @Test
    public void testExportDatasetToLongFilename() throws IOException {

        Path targetLocation = Files.createTempFile("sas7bdat-testLongFilename-" + "x".repeat(100), ".sas7bdat");
        try {
            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder()
                .variables(List.of(Variable.builder().name("A").type(VariableType.CHARACTER).length(1).build()))
                .build();

            // Write the dataset to a file with a long name.
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
                assertNull(sasFileReader.readNext());
            }

        } finally {
            // Always clean up
            Files.deleteIfExists(targetLocation);
        }
    }

    @Test
    public void testExportDatasetToDirectory() throws IOException {

        Path targetLocation = Files.createTempDirectory("sas7bdat-testExportDatasetToDirectory");
        try {
            Sas7bdatMetadata metadata = Sas7bdatMetadata.builder()
                .variables(List.of(Variable.builder().name("A").type(VariableType.CHARACTER).length(1).build()))
                .build();

            // Write the dataset to directory location.
            Exception exception = assertThrows(
                FileSystemException.class,
                () -> Sas7bdatExporter.exportDataset(targetLocation, metadata, List.of()));
            assertEquals(targetLocation + ": Is a directory", exception.getMessage());

            // Try again with the constructor
            exception = assertThrows(
                FileSystemException.class,
                () -> new Sas7bdatExporter(targetLocation, metadata, 0));
            assertEquals(targetLocation + ": Is a directory", exception.getMessage());

        } finally {
            // Always clean up
            Files.deleteIfExists(targetLocation);
        }
    }
}