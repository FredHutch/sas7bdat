package org.scharp.sas7bdat.javadoc;

import com.epam.parso.Column;
import com.epam.parso.SasFileProperties;
import com.epam.parso.SasFileReader;
import com.epam.parso.impl.SasFileReaderImpl;
import org.junit.jupiter.api.Test;
import org.scharp.sas7bdat.Format;
import org.scharp.sas7bdat.MissingValue;
import org.scharp.sas7bdat.Sas7bdatExporter;
import org.scharp.sas7bdat.Sas7bdatMetadata;
import org.scharp.sas7bdat.Variable;
import org.scharp.sas7bdat.VariableType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A class for executing the sample code that's within the JavaDoc.
 */
public class Sas7bdatTest {

    private static void exportDataset(Path targetLocation) throws IOException {

        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            datasetName("WEATHER").
            datasetLabel("Daily temperatures in cities across the U.S.A.").
            variables(
                List.of(
                    Variable.builder().
                        name("CITY").
                        type(VariableType.CHARACTER).
                        length(20).
                        label("Name of city").
                        outputFormat(new Format("$CHAR", 18)).
                        build(),

                    Variable.builder().
                        name("STATE").
                        type(VariableType.CHARACTER).
                        length(2).
                        label("Postal abbreviation of state").
                        outputFormat(new Format("$CHAR", 2)).
                        build(),

                    Variable.builder().
                        name("HIGH").
                        type(VariableType.NUMERIC).
                        length(8).
                        label("Average daily high in F").
                        outputFormat(new Format("", 5)).
                        build(),

                    Variable.builder().
                        name("LOW").
                        type(VariableType.NUMERIC).
                        length(8).
                        label("Average daily low in F").
                        outputFormat(new Format("", 5)).
                        build()
                )).build();

        List<List<Object>> observations = List.of(
            List.of("Atlanta", "GA", 72, 53),
            List.of("Austin", "TX", 80, 5),
            List.of("Baltimore", "MD", 65, 45),
            List.of("Birmingham", "AL", 74, 53),
            List.of("Boston", "MA", 59, MissingValue.STANDARD),
            List.of("Buffalo", "NY", 56, 40),
            // ...
            List.of("Virginia Beach", "VA", 68, 52),
            List.of("Washington", "DC", 68, 52));

        // Export the data set to a SAS7BDAT file.
        Sas7bdatExporter.exportDataset(targetLocation, metadata, observations);
    }

    @Test
    public void runSampleCode() throws IOException {

        Path targetLocation = Files.createTempFile("sas7bdat-sample-", ".sas7bdat");
        try {
            // Execute the sample code to create a dataset.
            exportDataset(targetLocation);

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = Files.newInputStream(targetLocation)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);
                SasFileProperties sasFileProperties = sasFileReader.getSasFileProperties();

                assertEquals("WEATHER", sasFileProperties.getName());
                assertEquals("Daily temperatures in cities across the U.S.A.", sasFileProperties.getFileLabel());

                assertEquals("DATA", sasFileProperties.getFileType());

                // Hard-coded values
                assertEquals("9.0401M2", sasFileProperties.getSasRelease());
                assertEquals("UTF-8", sasFileProperties.getEncoding());
                assertNull(sasFileProperties.getCompressionMethod());
                assertEquals("x86_64", sasFileProperties.getOsName());
                assertEquals("4.4.104-18.44", sasFileProperties.getOsType());
                assertEquals("Linux", sasFileProperties.getServerType());
                assertEquals(0, sasFileProperties.getDeletedRowCount());

                // Test the columns
                assertEquals(4, sasFileProperties.getColumnsCount());
                List<Column> columns = sasFileReader.getColumns();
                assertEquals("CITY", columns.get(0).getName(), "incorrect variable name");
                assertEquals(1, columns.get(0).getId(), "incorrect variable number");
                assertEquals(20, columns.get(0).getLength(), "incorrect variable length");
                assertEquals(String.class, columns.get(0).getType(), "incorrect variable type");
                assertEquals("Name of city", columns.get(0).getLabel(), "incorrect variable label");
                assertEquals("$CHAR", columns.get(0).getFormat().getName(), "incorrect format name");
                assertEquals(18, columns.get(0).getFormat().getWidth(), "incorrect format width");
                assertEquals(0, columns.get(0).getFormat().getPrecision(), "incorrect format digits");

                assertEquals("STATE", columns.get(1).getName(), "incorrect variable name");
                assertEquals(2, columns.get(1).getId(), "incorrect variable number");
                assertEquals(2, columns.get(1).getLength(), "incorrect variable length");
                assertEquals(String.class, columns.get(1).getType(), "incorrect variable type");
                assertEquals("Postal abbreviation of state", columns.get(1).getLabel(), "incorrect variable label");
                assertEquals("$CHAR", columns.get(1).getFormat().getName(), "incorrect format name");
                assertEquals(2, columns.get(1).getFormat().getWidth(), "incorrect format width");
                assertEquals(0, columns.get(1).getFormat().getPrecision(), "incorrect format digits");

                assertEquals("HIGH", columns.get(2).getName(), "incorrect variable name");
                assertEquals(3, columns.get(2).getId(), "incorrect variable number");
                assertEquals(8, columns.get(2).getLength(), "incorrect variable length");
                assertEquals(Number.class, columns.get(2).getType(), "incorrect variable type");
                assertEquals("Average daily high in F", columns.get(2).getLabel(), "incorrect variable label");
                assertEquals("", columns.get(2).getFormat().getName(), "incorrect format name");
                assertEquals(5, columns.get(2).getFormat().getWidth(), "incorrect format width");
                assertEquals(0, columns.get(2).getFormat().getPrecision(), "incorrect format digits");

                assertEquals("LOW", columns.get(3).getName(), "incorrect variable name");
                assertEquals(4, columns.get(3).getId(), "incorrect variable number");
                assertEquals(8, columns.get(3).getLength(), "incorrect variable length");
                assertEquals(Number.class, columns.get(3).getType(), "incorrect variable type");
                assertEquals("Average daily low in F", columns.get(3).getLabel(), "incorrect variable label");
                assertEquals("", columns.get(3).getFormat().getName(), "incorrect format name");
                assertEquals(5, columns.get(3).getFormat().getWidth(), "incorrect format width");
                assertEquals(0, columns.get(3).getFormat().getPrecision(), "incorrect format digits");

                // Test the observations
                assertEquals(8, sasFileProperties.getRowCount());
                Object[][] rows = sasFileReader.readAll();
                assertEquals(8, rows.length, "incorrect number of rows");
                assertArrayEquals(new Object[] { "Atlanta", "GA", 72L, 53L }, rows[0]);
                assertArrayEquals(new Object[] { "Austin", "TX", 80L, 5L }, rows[1]);
                assertArrayEquals(new Object[] { "Baltimore", "MD", 65L, 45L }, rows[2]);
                assertArrayEquals(new Object[] { "Birmingham", "AL", 74L, 53L }, rows[3]);
                assertArrayEquals(new Object[] { "Boston", "MA", 59L, null }, rows[4]);
                assertArrayEquals(new Object[] { "Buffalo", "NY", 56L, 40L }, rows[5]);
                assertArrayEquals(new Object[] { "Virginia Beach", "VA", 68L, 52L }, rows[6]);
                assertArrayEquals(new Object[] { "Washington", "DC", 68L, 52L }, rows[7]);
            }

        } finally {
            // Always clean up
            Files.deleteIfExists(targetLocation);
        }
    }
}