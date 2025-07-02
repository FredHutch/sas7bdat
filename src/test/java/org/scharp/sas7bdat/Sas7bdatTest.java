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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Unit test for {@link Sas7bdatExporter} */
public class Sas7bdatTest {

    @Test
    public void runSampleCode() throws IOException {

        String greek = "\u0395\u03BB\u03BB\u03B7\u03BD\u03B9\u03BA\u03AC"; // Greek word for greek

        String datasetLabel = "A sample dataset: " + greek;

        // Set a known "creation date" to make it easier to diff output from multiple runs.
        LocalDateTime createDate = LocalDateTime.parse("2020-11-20T06:53:07");

        List<Variable> variables = Arrays.asList(//
            new Variable(//
                "TEXT", //
                1, //
                VariableType.CHARACTER, //
                20, //
                "A label with greek characters: " + greek, //
                Format.UNSPECIFIED, //
                new Format("$", 10), //
                StrictnessMode.SAS_ANY),

            new Variable(//
                "AVERYLONG_0123456789_123456789VR", //
                2, //
                VariableType.CHARACTER, //
                20, //
                "A second text variable with a long name", //
                new Format("$CHAR", 200), //
                Format.UNSPECIFIED, //
                StrictnessMode.SAS_ANY), //

            new Variable(//
                "TEXT3", //
                3, //
                VariableType.CHARACTER, //
                5, //
                "", // no label
                new Format("$UPCASE", 10), //
                Format.UNSPECIFIED, //
                StrictnessMode.SAS_ANY), //

            new Variable(//
                "T4", //
                4, //
                VariableType.CHARACTER, //
                1, // len
                "T4 label", // label
                new Format("$ASCII", 1), //
                Format.UNSPECIFIED, //
                StrictnessMode.SAS_ANY), //

            new Variable(//
                "MY_NUMBER", //
                5, // variable number
                VariableType.NUMERIC, //
                8, // len
                "A number", // label
                Format.UNSPECIFIED, //
                new Format("d", 10), //
                StrictnessMode.SAS_ANY));

        List<List<Object>> observations = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            observations.add(Arrays.asList("Value #1 for Var #1!", "Value #1 for Var #2$", "Text3", "A", i));
            observations.add(Arrays.asList("Value #2 for Var #1@", "Value #2 for Var #2$", "Text3", "B", i));
            observations.add(Arrays.asList("Value #3 for Var #1@", "Value #3 for Var #2$", "Text3", "C", i));
        }

        Path targetLocation = Path.of("sample.sas7bdat");
        try {
            // Write a data set.
            Sas7bdatExporter.writeDataset(targetLocation, createDate, "my type", datasetLabel, variables, observations);

            // Read the dataset with parso to confirm that it was written correctly.
            try (InputStream inputStream = Files.newInputStream(targetLocation)) {
                SasFileReader sasFileReader = new SasFileReaderImpl(inputStream);

                System.out.println("Created: " + sasFileReader.getSasFileProperties().getDateCreated());

                Writer writer = new StringWriter();
                CSVDataWriter csvDataWriter = new CSVDataWriterImpl(writer);
                csvDataWriter.writeColumnNames(sasFileReader.getColumns());
                csvDataWriter.writeRowsArray(sasFileReader.getColumns(), sasFileReader.readAll());

                System.out.println(writer.toString());
            }

        } finally {
            // Always clean up
            Files.deleteIfExists(targetLocation);
        }
    }
}