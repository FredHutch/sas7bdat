package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertFormat(new Format("MaxedOut", Short.MAX_VALUE, Short.MAX_VALUE), "MaxedOut", Short.MAX_VALUE,
            Short.MAX_VALUE);

        // Test the two-argument constructor
        assertFormat(new Format("FORMAT", 1), "FORMAT", 1, 0);
        assertFormat(new Format("", 1), "", 1, 0);
        assertFormat(new Format("$UPCASE", 100), "$UPCASE", 100, 0);
        assertFormat(new Format("PERCENTN", 6), "PERCENTN", 6, 0);
        assertFormat(new Format("MaxedOut", Short.MAX_VALUE), "MaxedOut", Short.MAX_VALUE, 0);
    }

    @Test
    void testUnspecified() {
        assertFormat(Format.UNSPECIFIED, "", 0, 0);
    }

    @Test
    void testNullName() {
        // Test the three argument constructor
        Exception exception = assertThrows(NullPointerException.class, () -> new Format(null, 0, 0));
        assertEquals("format name must not be null", exception.getMessage());

        // Test the two argument constructor
        exception = assertThrows(NullPointerException.class, () -> new Format(null, 0));
        assertEquals("format name must not be null", exception.getMessage());
    }

    @Test
    void testLongName() {
        // Test the three argument constructor
        Exception exception = assertThrows(IllegalArgumentException.class, () -> new Format("TOOLONGXY", 0, 0));
        assertEquals("format name must not be longer than 8 characters", exception.getMessage());

        // Test the two argument constructor
        exception = assertThrows(IllegalArgumentException.class, () -> new Format("TOOLONGXY", 0));
        assertEquals("format name must not be longer than 8 characters", exception.getMessage());
    }

    @Test
    void testNegativeWidth() {
        // Test the three argument constructor
        Exception exception = assertThrows(IllegalArgumentException.class, () -> new Format("$ASCII", -1, 0));
        assertEquals("format width must not be negative", exception.getMessage());

        // Test the two argument constructor
        exception = assertThrows(IllegalArgumentException.class, () -> new Format("$ASCII", -1));
        assertEquals("format width must not be negative", exception.getMessage());
    }

    @Test
    void testLargeWidth() {
        // Test the three argument constructor
        Exception exception = assertThrows(IllegalArgumentException.class, () -> new Format("$ASCII", 32768, 0));
        assertEquals("format width must not be greater than 32767", exception.getMessage());

        // Test the two argument constructor
        exception = assertThrows(IllegalArgumentException.class, () -> new Format("", 32768));
        assertEquals("format width must not be greater than 32767", exception.getMessage());
    }

    @Test
    void testNegativeNumberOfDigits() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> new Format("d", 0, -1));
        assertEquals("format numberOfDigits must not be negative", exception.getMessage());
    }

    @Test
    void testLargeNumberOfDigits() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> new Format("d", 0, 32768));
        assertEquals("format numberOfDigits must not be greater than 32767", exception.getMessage());
    }
}