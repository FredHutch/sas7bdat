package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link ColumnText}. */
public class ColumnTextTest {

    @Test
    void smokeTest() {
        // Create a ColumnText
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of());
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        ColumnText columnText = new ColumnText(pageLayout);

        // Add a long string to the ColumnTextSubheader
        String string1 = "a".repeat(pageLayout.pageSize / 2 - 100);
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
        String string3 = "b".repeat(pageLayout.pageSize / 2 - 120);
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

        // Writing beyond the end of the array should throw an exception.
        Exception exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> columnText.writeTextLocation(data, 2, "a"));
        assertEquals("Index 7 out of bounds for length 6", exception.getMessage());

        // Write to a non-zero offset.
        byte[] data2 = new byte[14];
        Arrays.fill(data2, (byte) -1);
        columnText.writeTextLocation(data2, 6, "a");
        byte[] expectedData2 = {
            -1, -1, -1, -1, -1, -1, // before offset
            expectedALocation[0], expectedALocation[1],
            expectedALocation[2], expectedALocation[3],
            expectedALocation[4], expectedALocation[5],
            -1, -1, // after
        };
        assertArrayEquals(expectedData2, data2);

        // It's always possible to find the empty string, even if it was not explicitly added.
        final byte[] expectedEmptyStringLocation = {
            0, 0, // page index
            0, 0, // offset in ColumnTextSubheader
            0, 0, // string length
        };
        columnText.writeTextLocation(data, 0, "");
        assertArrayEquals(expectedEmptyStringLocation, data);

        // The first ColumnTextSubheader should be in the metadata.
        assertEquals(List.of(), pageLayout.completeMetadataPages);
        assertEquals(1, pageLayout.subheaders.size());
        assertInstanceOf(ColumnTextSubheader.class, pageLayout.subheaders.get(0));

        // Declare that there's no more text.
        columnText.noMoreText();

        // Confirm that the half-written ColumnTextSubheader has been "flushed".
        assertEquals(List.of(), pageLayout.completeMetadataPages);
        assertEquals(2, pageLayout.subheaders.size());
        assertInstanceOf(ColumnTextSubheader.class, pageLayout.subheaders.get(0));
        assertInstanceOf(ColumnTextSubheader.class, pageLayout.subheaders.get(1));
    }

    /**
     * Programmatically determines how many UUIDs it takes to fill the first ColumnTextSubheader in a ColumnText.
     *
     * @return The number of UUIDs it takes to fill the first ColumnTextSubheader in a ColumnText
     */
    static int totalNumberUuidsToFillFirstColumnTextSubheader() {
        // Create a ColumnText
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of());
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        ColumnText columnText = new ColumnText(pageLayout);

        // We want to determine how many strings need to be added before the last ColumnTextSubheader on
        // the first page is created.
        // Keep adding UUIDs (small strings) until the first page is full.
        int totalNumberUuidsAdded = 0;
        while (pageLayout.subheaders.isEmpty()) {
            columnText.add(UUID.randomUUID().toString());
            totalNumberUuidsAdded++;
        }

        // The last UUID added was added to a new ColumnTextSubheader, so subtract 1.
        return totalNumberUuidsAdded - 1;
    }

    @Test
    void testEmptyColumnTextSubheader() {
        // The intent of this test case is to cause ColumnText to allocate a new ColumnTextSubheader when
        // there is enough space on the page for a ColumnTextSubheader but not for a ColumnTextSubheader
        // that contains the string that is being added.
        //
        // Therefore, we want a ColumnTextSubheader to be filled.

        // Create a ColumnText
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of());
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);

        // Add a subheader to the page such that, when ColumnText adds the first subheader
        // (of size ColumnTextSubheader.MAX_SIZE) there will only be a little bit of space remaining for the next
        // subheader.
        int totalBytesRemaining = pageLayout.currentMetadataPage.totalBytesRemainingForNewSubheader();
        pageLayout.addSubheader(new FillerSubheader(totalBytesRemaining - ColumnTextSubheader.MAX_SIZE - 100));

        ColumnText columnText = new ColumnText(pageLayout);

        int uuidsToAdd = totalNumberUuidsToFillFirstColumnTextSubheader();
        for (int i = 0; i < uuidsToAdd; i++) {
            columnText.add(UUID.randomUUID().toString());
        }

        assertEquals(1, pageLayout.subheaders.size(), "TEST BUG: overflowed first ColumnTextSubheader");

        // Add a long string.  Even though an empty ColumnTextSubheader can still
        // fit on the page, a ColumnTextSubheader with this string can't.
        String lastStringAdded = "a".repeat(255);
        columnText.add(lastStringAdded);

        assertEquals(2, pageLayout.subheaders.size(), "TEST BUG: lastAddedString didn't overflow ColumnTextSubheader");

        // Flush the subheader.
        columnText.noMoreText();
        assertEquals(4, pageLayout.subheaders.size());
        assertInstanceOf(FillerSubheader.class, pageLayout.subheaders.get(0));
        assertInstanceOf(ColumnTextSubheader.class, pageLayout.subheaders.get(1)); // filled with UUIDs
        assertInstanceOf(TerminalSubheader.class, pageLayout.subheaders.get(2));
        assertInstanceOf(ColumnTextSubheader.class, pageLayout.subheaders.get(3)); // with only lastStringAdded

        // If everything is as expected, the new string should be the first string in the second ColumnTextSubheader.
        final byte[] expectedStringLocation = {
            1, 0, // page index
            8, 0, // offset in ColumnTextSubheader
            (byte) lastStringAdded.length(), 0, // string length
        };
        byte[] data = new byte[6];
        columnText.writeTextLocation(data, 0, lastStringAdded);
        assertArrayEquals(expectedStringLocation, data);
    }
}