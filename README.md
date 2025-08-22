sas7bdat
========
A Java library for writing SAS7BDAT datasets that can be read by SAS.

Quick Start
-----------
The following code shows how to use the library to create a SAS7BDAT file for a hard-coded dataset.

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

    // Export the dataset to a SAS7BDAT file.
    Path targetLocation = Path.of(...)
    Sas7bdatExporter.exportDataset(targetLocation, metadata, observations);

Limitations
-----------
* The SAS7BDAT is a 64-bit, UNIX, little-endian.
* The strings are always encoded in UTF-8.
* Requires Java 17 or later.
* Compression is not supported.
* Reading SAS7BDAT files is not supported (use the parso library instead).

Downloading
-----------
This library is published on Maven Central at https://central.sonatype.com/artifact/org.scharp/sas7bdat

There, you will find instructions for downloading the library and referencing it your Maven application's `pom.xml`.