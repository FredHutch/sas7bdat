package org.scharp.sas7bdat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.scharp.sas7bdat.Sas7bdatWriter.Sas7bdatUnix64bitMetadata;
import org.scharp.sas7bdat.Sas7bdatWriter.Sas7bdatUnix64bitVariables;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Unit tests for {@link ColumnText}. */
public class ColumnTextTest {

    @Test
    void smokeTest() {
        // Create a ColumnText
        final int pageSize = 0x10000;
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatUnix64bitVariables variables = new Sas7bdatUnix64bitVariables(List.of());
        Sas7bdatUnix64bitMetadata metadata = new Sas7bdatUnix64bitMetadata(pageSequenceGenerator, pageSize, variables);
        ColumnText columnText = new ColumnText(metadata);

        // Add a long string to the ColumnTextSubheader
        String string1 = "a".repeat(pageSize / 2 - 100);
        columnText.add(string1);

        final byte[] expectedString1Location = {
            0, 0, // page index
            8, 0, // offset in ColumnTextSubheader
            -100, 127, // string length
        };

        // Add a second string.   to the first header.
        String string2 = "second-string";
        columnText.add(string2);

        final byte[] expectedString2Location = {
            0, 0, // page index
            -92, 127, // offset in ColumnTextSubheader
            13, 0, // string length
        };

        // Add a large string, which creates a new ColumnTextSubheader.
        String string3 = "b".repeat(pageSize / 2 - 120);
        columnText.add(string3);

        final byte[] expectedString3Location = {
            1, 0, // page index
            8, 0, // offset in ColumnTextSubheader
            -120, 127 // string length
        };

        // Add a forth string to the second subheader.
        columnText.add("a");

        final byte[] expectedALocation = {
            1, 0, // page index
            -112, 127, // offset in ColumnTextSubheader
            1, 0, // string length
        };

        // Write the location of the first string to an array.
        byte[] data = new byte[6];
        columnText.writeTextLocation(data, 0, string1);
        assertArrayEquals(expectedString1Location, data);

        // Write the location of the second string to an array.
        columnText.writeTextLocation(data, 0, string2);
        assertArrayEquals(expectedString2Location, data);

        // Write the location of the third string to an array.
        // This is the first string on the second page.
        columnText.writeTextLocation(data, 0, string3);
        assertArrayEquals(expectedString3Location, data);

        // Write the location of the third string to an array.
        columnText.writeTextLocation(data, 0, "a");
        assertArrayEquals(expectedALocation, data);

        // It's always possible to find the empty string, even if it was not explicitly added.
        final byte[] expectedEmptyStringLocation = {
            0, 0, // page index
            0, 0, // offset in ColumnTextSubheader
            0, 0, // string length
        };
        columnText.writeTextLocation(data, 0, "");
        assertArrayEquals(expectedEmptyStringLocation, data);

        // The first ColumnTextSubheader should be in the metadata.
        assertEquals(List.of(), metadata.completeMetadataPages);
        assertEquals(1, metadata.subheaders.size());
        assertInstanceOf(ColumnTextSubheader.class, metadata.subheaders.get(0));

        // Declare that there's no more text.
        columnText.noMoreText();

        // Confirm that the half-written ColumnTextSubheader has been "flushed".
        assertEquals(List.of(), metadata.completeMetadataPages);
        assertEquals(2, metadata.subheaders.size());
        assertInstanceOf(ColumnTextSubheader.class, metadata.subheaders.get(0));
        assertInstanceOf(ColumnTextSubheader.class, metadata.subheaders.get(1));
    }

    @Test
    @Disabled("TODO: fix the bug that prevents this from running")
    void testEmptyColumnTextSubheader() {
        // Create a ColumnText
        final int pageSize = 0x10000;
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatUnix64bitVariables variables = new Sas7bdatUnix64bitVariables(List.of());
        Sas7bdatUnix64bitMetadata metadata = new Sas7bdatUnix64bitMetadata(pageSequenceGenerator, pageSize, variables);
        ColumnText columnText = new ColumnText(metadata);

        // Add a long string to the ColumnTextSubheader
        String string1 = "a".repeat(pageSize / 2 - 4);
        columnText.add(string1);

        final byte[] expectedString1Location = {
            0, 0, // page index
            8, 0, // offset in ColumnTextSubheader
            0, 64, // string length
        };

        // Add another string about the same size.
        // This is too big to fit on the same remaining on the first page.
        String string2 = "b".repeat(pageSize / 2 - 40);
        columnText.add(string2);

        final byte[] expectedString2Location = {
            0, 0, // page index
            8, 0, // offset in ColumnTextSubheader
            0, 64, // string length
        };

        // Write the location of the first string to an array.
        byte[] data = new byte[6];
        columnText.writeTextLocation(data, 0, string1);
        assertArrayEquals(expectedString1Location, data);

        // Write the location of the first string to an array.
        columnText.writeTextLocation(data, 0, string2);
        assertArrayEquals(expectedString2Location, data);
    }
}