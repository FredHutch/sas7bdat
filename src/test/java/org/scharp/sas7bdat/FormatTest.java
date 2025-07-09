package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link Format}. */
public class FormatTest {

    private static void assertFormat(Format format, String expectedName, int expectedWidth,
        int expectedNumberOfDigits) {
        assertEquals(expectedName, format.name(), "incorrect format name");
        assertEquals(expectedWidth, format.width(), "incorrect format width");
        assertEquals(expectedNumberOfDigits, format.numberOfDigits(), "incorrect number of digits");
    }

    @Test
    void basicTest() {
        // Test the three-argument constructor
        assertFormat(new Format("FORMAT", 1, 2), "FORMAT", 1, 2);
        assertFormat(new Format("", 5, 2), "", 5, 2);
        assertFormat(new Format("$ASCII", 5, 0), "$ASCII", 5, 0);
        assertFormat(new Format("PERCENTN", 32, 31), "PERCENTN", 32, 31);
        assertFormat(new Format("dollar", 15, 2), "dollar", 15, 2);

        // Test the two-argument constructor
        assertFormat(new Format("FORMAT", 1), "FORMAT", 1, 0);
        assertFormat(new Format("", 1), "", 1, 0);
        assertFormat(new Format("$UPCASE", 100), "$UPCASE", 100, 0);
        assertFormat(new Format("PERCENTN", 6), "PERCENTN", 6, 0);
    }

    @Test
    void testUnspecified() {
        assertFormat(Format.UNSPECIFIED, "", 0, 0);
    }
}