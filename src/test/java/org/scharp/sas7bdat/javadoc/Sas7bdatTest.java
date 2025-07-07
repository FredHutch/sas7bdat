package org.scharp.sas7bdat.javadoc;

import com.epam.parso.CSVDataWriter;
import com.epam.parso.SasFileReader;
import com.epam.parso.impl.CSVDataWriterImpl;
import com.epam.parso.impl.SasFileReaderImpl;
import org.junit.jupiter.api.Test;
import org.scharp.sas7bdat.Format;
import org.scharp.sas7bdat.Sas7bdatExporter;
import org.scharp.sas7bdat.Sas7bdatMetadata;
import org.scharp.sas7bdat.Variable;
import org.scharp.sas7bdat.VariableType;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * A class for executing the sample code that's within the package JavaDoc.
 */
public class Sas7bdatTest {

    private static void exportDataset(Path targetLocation) throws IOException {

        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            datasetLabel("A sample dataset").
            variables(
                List.of(
                    new Variable(
                        "CITY",
                        VariableType.CHARACTER,
                        20, // string length
                        "Name of city", // label
                        new Format("$CHAR", 18), // output format
                        Format.UNSPECIFIED), //

                    new Variable(//
                        "STATE",
                        VariableType.CHARACTER, //
                        2, // string length
                        "Postal abbreviation of state", //
                        new Format("$CHAR", 2), //
                        Format.UNSPECIFIED), //

                    new Variable(//
                        "HIGH", //
                        VariableType.NUMERIC, //
                        8, //
                        "Average daily high in F", //
                        new Format("", 5), //
                        Format.UNSPECIFIED), //

                    new Variable(//
                        "LOW", //
                        VariableType.NUMERIC, //
                        8, //
                        "Average daily low in F", //
                        new Format("", 5), //
                        Format.UNSPECIFIED))).build();

        List<List<Object>> observations = Arrays.asList(
            Arrays.asList("Atlanta", "GA", 72, 53),
            Arrays.asList("Austin", "TX", 80, 5),
            Arrays.asList("Baltimore", "MD", 65, 45),
            Arrays.asList("Birmingham", "AL", 74, 53),
            Arrays.asList("Boston", "MA", 59, null), // null means missing
            Arrays.asList("Buffalo", "NY", 56, 40),
            // ...
            Arrays.asList("Virginia Beach", "VA", 68, 52),
            Arrays.asList("Washington", "DC", 68, 52));

        // Export the data set a SAS7BDAT file.
        Sas7bdatExporter.writeDataset(targetLocation, metadata, observations);
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

                System.out.println("Created: " + sasFileReader.getSasFileProperties().getDateCreated());
                System.out.println("Label: " + sasFileReader.getSasFileProperties().getFileLabel());
                System.out.println("Type: " + sasFileReader.getSasFileProperties().getFileType());

                Writer writer = new StringWriter();
                CSVDataWriter csvDataWriter = new CSVDataWriterImpl(writer);
                csvDataWriter.writeColumnNames(sasFileReader.getColumns());
                csvDataWriter.writeRowsArray(sasFileReader.getColumns(), sasFileReader.readAll());

                System.out.println(writer);
            }

        } finally {
            // Always clean up
            Files.deleteIfExists(targetLocation);
        }
    }
}