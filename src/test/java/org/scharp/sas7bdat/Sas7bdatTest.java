package org.scharp.sas7bdat;

import com.epam.parso.CSVDataWriter;
import com.epam.parso.SasFileReader;
import com.epam.parso.impl.CSVDataWriterImpl;
import com.epam.parso.impl.SasFileReaderImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Sas7bdatTest {

    private static void writeDataset(Path targetLocation) throws IOException {
        Sas7bdatMetadata metadata = Sas7bdatMetadata.builder().
            datasetLabel("A sample dataset").
            variables(
                List.of(
                    new Variable(
                        "TEXT",
                        VariableType.CHARACTER,
                        20,
                        "Some simple text",
                        Format.UNSPECIFIED,
                        new Format("$", 10),
                        StrictnessMode.SAS_ANY),

                    new Variable(
                        "AVERYLONG_0123456789_123456789VR",
                        VariableType.CHARACTER,
                        20,
                        "A second text variable with a long name",
                        new Format("$CHAR", 200),
                        Format.UNSPECIFIED,
                        StrictnessMode.SAS_ANY),

                    new Variable(
                        "TEXT3",
                        VariableType.CHARACTER,
                        5,
                        "", // no label
                        new Format("$UPCASE", 10),
                        Format.UNSPECIFIED,
                        StrictnessMode.SAS_ANY),

                    new Variable(
                        "Letter",
                        VariableType.CHARACTER,
                        1, // len
                        "A single letter", // label
                        new Format("$ASCII", 1),
                        Format.UNSPECIFIED,
                        StrictnessMode.SAS_ANY),

                    new Variable(
                        "MY_NUMBER",
                        VariableType.NUMERIC,
                        8, // len
                        "A number", // label
                        Format.UNSPECIFIED,
                        new Format("d", 10),
                        StrictnessMode.SAS_ANY))).build();

        List<List<Object>> observations = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            observations.add(Arrays.asList("Value #1 for Var #1!", "Value #1 for Var #2$", "Text3", "A", i));
            observations.add(Arrays.asList("Value #2 for Var #1@", "Value #2 for Var #2$", "Text3", "B", i));
            observations.add(Arrays.asList("Value #3 for Var #1@", "Value #3 for Var #2$", "Text3", "C", i));
        }

        // Write a data set.
        Sas7bdatExporter.writeDataset(targetLocation, metadata, observations);
    }

    @Test
    public void runSampleCode() throws IOException {

        Path targetLocation = Files.createTempFile("sas7bdat-sample-", ".sas7bdat");
        try {
            // Execute the sample code to create a dataset.
            writeDataset(targetLocation);

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