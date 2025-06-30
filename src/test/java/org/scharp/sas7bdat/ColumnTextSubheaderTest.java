package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_UNCOMPRESSED;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_B;

/** Unit tests for {@link ColumnTextSubheader}. */
public class ColumnTextSubheaderTest {

    @Test
    void testTypeCode() {
        ColumnTextSubheader columnTextSubheader = new ColumnTextSubheader((short) 0, (short) 0x7F00);
        assertEquals(SUBHEADER_TYPE_B, columnTextSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        ColumnTextSubheader columnTextSubheader = new ColumnTextSubheader((short) 0, (short) 0x7F00);
        assertEquals(COMPRESSION_UNCOMPRESSED, columnTextSubheader.compressionCode());
    }

    @Test
    void basicTest() {
        final short indexInPage = 2;
        ColumnTextSubheader columnTextSubheader = new ColumnTextSubheader(indexInPage, (short) 500);

        // Add the same string twice
        assertTrue(columnTextSubheader.add("string1"));
        assertTrue(columnTextSubheader.add("string1"));

        // Add a new string
        assertTrue(columnTextSubheader.add("string2"));

        // Add strings that are substrings of each other
        assertTrue(columnTextSubheader.add("A"));
        assertTrue(columnTextSubheader.add("AB"));
        assertTrue(columnTextSubheader.add("ABC"));
        assertTrue(columnTextSubheader.add("B"));

        // Add a non-ASCII string
        final String grin = "\uD83D\uDE01"; // GRINNING FACE WITH SMILING EYES
        assertTrue(columnTextSubheader.add(grin));

        // A string that doesn't need padding
        assertTrue(columnTextSubheader.add("1234"));

        // A string that can't fit.
        // There should be no trace of this string in the subheader data.
        assertFalse(columnTextSubheader.add("x".repeat(500)));

        // The subheader should return the indexInPage that it was given.
        assertEquals(indexInPage, columnTextSubheader.columnTextSubheaderIndex());

        // The strings should be somewhere within the header.
        assertEquals(16, columnTextSubheader.offsetFromSignature("string1"));
        assertEquals(7, ColumnTextSubheader.sizeof("string1"));

        assertEquals(24, columnTextSubheader.offsetFromSignature("string2"));
        assertEquals(7, ColumnTextSubheader.sizeof("string2"));

        assertEquals(32, columnTextSubheader.offsetFromSignature("A"));
        assertEquals(1, ColumnTextSubheader.sizeof("A"));

        assertEquals(36, columnTextSubheader.offsetFromSignature("AB"));
        assertEquals(2, ColumnTextSubheader.sizeof("AB"));

        assertEquals(40, columnTextSubheader.offsetFromSignature("ABC"));
        assertEquals(3, ColumnTextSubheader.sizeof("ABC"));

        assertEquals(44, columnTextSubheader.offsetFromSignature("B"));
        assertEquals(1, ColumnTextSubheader.sizeof("B"));

        assertEquals(48, columnTextSubheader.offsetFromSignature(grin));
        assertEquals(4, ColumnTextSubheader.sizeof(grin));

        assertEquals(52, columnTextSubheader.offsetFromSignature("1234"));
        assertEquals(4, ColumnTextSubheader.sizeof("1234"));

        // Write the contents of the subheader to a byte array.
        // This tests for strict equality with a known good previous value, which is overly
        // restrictive for correctness.  For example, it's correct to reorder/combine strings.
        // However, the clearest way to test for correctness is with exact equality of a
        // known good previous value.
        final byte[] expectedSubheaderData = new byte[] {
            -3, -1, -1, -1, -1, -1, -1, -1,  // signature
            56, 0, 0, 0, 0, 0, 0, 0, // size of data
            0, 0, 0, 0,  // unknown
            0, 0, 0, 0, // possibly unneeded additional padding
            's', 't', 'r', 'i', 'n', 'g', '1', 0, // string1 (padded)
            's', 't', 'r', 'i', 'n', 'g', '2', 0, // string2 (padded)
            'A', 0, 0, 0, // A (padded)
            'A', 'B', 0, 0, // AB (padded)
            'A', 'B', 'C', 0, // ABC (padded)
            'B', 0, 0, 0, // B (padded)
            -16, -97, -104, -127, // grin
            '1', '2', '3', '4', // 1234 (no padding)
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        int size = columnTextSubheader.size();
        assertEquals(expectedSubheaderData.length, columnTextSubheader.size());
        assertEquals(expectedSubheaderData.length - 8 - 12, columnTextSubheader.sizeOfData());

        // Get the subheader with a non-zero offset.
        int offset = 10;
        byte[] actualSubheaderData = new byte[size + offset];
        columnTextSubheader.writeSubheader(actualSubheaderData, offset);

        // Determine the expected return value.
        byte[] expectedArray = new byte[expectedSubheaderData.length + offset];
        System.arraycopy(expectedSubheaderData, 0, expectedArray, offset, expectedSubheaderData.length);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedArray, actualSubheaderData);
    }

    @Test
    void testPadToMaxSize() {
        final short indexInPage = 0;
        ColumnTextSubheader columnTextSubheader = new ColumnTextSubheader(indexInPage, (short) 40);

        // See what data is returned before padding.
        final byte[] emptySubheaderData = new byte[] {
            -3, -1, -1, -1, -1, -1, -1, -1,  // signature
            8, 0, 0, 0, 0, 0, 0, 0, // size of data
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        assertEquals(emptySubheaderData.length, columnTextSubheader.size());
        assertEquals(emptySubheaderData.length - 8 - 12, columnTextSubheader.sizeOfData());

        // Get the subheader with a non-zero offset.
        byte[] actualSubheaderData = new byte[emptySubheaderData.length];
        columnTextSubheader.writeSubheader(actualSubheaderData, 0);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(emptySubheaderData, actualSubheaderData);

        // Pad the subheader to the maxSize (40)
        columnTextSubheader.padToMaxSize();

        // After padding to the maxSize, we shouldn't be able to add any strings, however small.
        assertFalse(columnTextSubheader.add("a"));

        // See what data is returned before padding.
        final byte[] paddedSubheaderData = new byte[] {
            -3, -1, -1, -1, -1, -1, -1, -1,  // signature
            20, 0, 0, 0, 0, 0, 0, 0, // size of data

            1, 0, 0, 0, // newly added padding
            12, 0, 0, 0, 0, 0, 0, 0, // newly added padding

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        assertEquals(paddedSubheaderData.length, columnTextSubheader.size());
        assertEquals(paddedSubheaderData.length - 8 - 12, columnTextSubheader.sizeOfData());

        // Get the subheader with a non-zero offset.
        byte[] actualPaddedSubheaderData = new byte[paddedSubheaderData.length];
        columnTextSubheader.writeSubheader(actualPaddedSubheaderData, 0);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(paddedSubheaderData, actualPaddedSubheaderData);
    }

    /** Tests for {@link ColumnTextSubheader#sizeof}. */
    @Test
    void testSizeOf() {
        assertEquals(0, ColumnTextSubheader.sizeof(""));
        assertEquals(1, ColumnTextSubheader.sizeof("1"));
        assertEquals(1, ColumnTextSubheader.sizeof("A"));
        assertEquals(2, ColumnTextSubheader.sizeof("AB"));
        assertEquals(3, ColumnTextSubheader.sizeof("ABC"));
        assertEquals(4, ColumnTextSubheader.sizeof("1234"));
        assertEquals(99, ColumnTextSubheader.sizeof("x".repeat(99)));
        assertEquals(Short.MAX_VALUE, ColumnTextSubheader.sizeof("x".repeat(Short.MAX_VALUE)));

        // Test non-ASCII
        final String grin = "\uD83D\uDE01"; // GRINNING FACE WITH SMILING EYES
        final String sigma = "\u03C3"; // GREEK SMALL LETTER SIGMA
        assertEquals(4, ColumnTextSubheader.sizeof(grin));
        assertEquals(2, ColumnTextSubheader.sizeof(sigma));
        assertEquals(6, ColumnTextSubheader.sizeof(grin + sigma));
    }
}