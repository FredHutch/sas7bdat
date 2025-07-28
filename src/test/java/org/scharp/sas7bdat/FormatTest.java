///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Format}. */
public class FormatTest {

    private static void assertFormat(Format format, String expectedName, int expectedWidth,
        int expectedNumberOfDigits, String expectedStringForm) {
        // Test the accessor methods
        assertEquals(expectedName, format.name(), "incorrect format name");
        assertEquals(expectedWidth, format.width(), "incorrect format width");
        assertEquals(expectedNumberOfDigits, format.numberOfDigits(), "incorrect number of digits");

        // Test toString()
        assertEquals(expectedStringForm, format.toString(), "toString() returned incorrect value");
    }

    @Test
    void basicTest() {
        String longName = "f".repeat(32); // 32 bytes is the longest name possible

        // Test the three-argument constructor
        assertFormat(new Format("FORMAT", 1, 2), "FORMAT", 1, 2, "FORMAT1.2");
        assertFormat(new Format("", 5, 2), "", 5, 2, "5.2");
        assertFormat(new Format("$ASCII", 5, 0), "$ASCII", 5, 0, "$ASCII5.");
        assertFormat(new Format("PERCENTN", 32, 31), "PERCENTN", 32, 31, "PERCENTN32.31");
        assertFormat(new Format("dollar", 15, 2), "dollar", 15, 2, "dollar15.2");
        assertFormat(new Format(longName, Short.MAX_VALUE, Short.MAX_VALUE), longName, Short.MAX_VALUE, Short.MAX_VALUE,
            longName + "32767.32767");

        // Test the two-argument constructor
        assertFormat(new Format("FORMAT", 1), "FORMAT", 1, 0, "FORMAT1.");
        assertFormat(new Format("", 1), "", 1, 0, "1.");
        assertFormat(new Format("$UPCASE", 100), "$UPCASE", 100, 0, "$UPCASE100.");
        assertFormat(new Format("PERCENTN", 6), "PERCENTN", 6, 0, "PERCENTN6.");
        assertFormat(new Format(longName, Short.MAX_VALUE), longName, Short.MAX_VALUE, 0, longName + "32767.");
    }

    @Test
    void testUnspecified() {
        assertFormat(Format.UNSPECIFIED, "", 0, 0, "");
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
        // The longest allowable name is 32 bytes in UTF-8.
        // Using a name that's 32 characters but 32 bytes in UTF-8 should be an error.
        final String sigma = "\u03C3"; // GREEK SMALL LETTER SIGMA (two bytes in UTF-8)
        String longName = sigma + "X".repeat(31);
        assertEquals(32, longName.length(), "TEST BUG: not testing encoding expansion");

        // Test the three argument constructor
        Exception exception = assertThrows(IllegalArgumentException.class, () -> new Format(longName, 0, 0));
        assertEquals("format name must not be longer than 32 bytes when encoded with UTF-8", exception.getMessage());

        // Test the two argument constructor
        exception = assertThrows(IllegalArgumentException.class, () -> new Format(longName, 0));
        assertEquals("format name must not be longer than 32 bytes when encoded with UTF-8", exception.getMessage());
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

    static private String copy(String string) {
        return new String(string);
    }

    /**
     * Tests {@link Format#hashCode()}.
     */
    @Test
    public void testHashCode() {
        // Create a format.
        Format format = new Format("MyFormat", 10, 2);

        // Create a copy that has the same name but from a different String reference.
        Format formCopy = new Format(copy("MyFormat"), 10, 2);

        // The copy must hash to the same value as the original.
        assertEquals(format.hashCode(), formCopy.hashCode());

        // Create a pair of equal formats from different constructors.
        Format ascii10 = new Format("$ASCII", 10);
        Format ascii10Copy = new Format("$ASCII", 10, 0);

        // The copy must hash to the same value as the original.
        assertEquals(ascii10.hashCode(), ascii10Copy.hashCode());

        // Create a pair of formats that have empty strings.
        Format emptyFormat = new Format("", 0, 0);
        Format emptyFormatCopy = new Format(copy(""), 0);

        // The copy must hash to the same value as the original.
        assertEquals(emptyFormat.hashCode(), emptyFormatCopy.hashCode());
    }

    /**
     * Tests {@link Format#equals(Object)}.
     */
    @SuppressWarnings({ "unlikely-arg-type", "EqualsBetweenInconvertibleTypes" })
    @Test
    public void testEquals() {
        // Create a format.
        Format format = new Format("MyFormat", 10, 2);

        // Create a copy that has the same name but from a different String reference.
        Format formatCopy = new Format(copy("MyFormat"), 10, 2);

        // Create another pair for different values (differs in case)
        Format format2 = new Format("myFormat", 10, 2);
        Format format2Copy = new Format(copy("myFormat"), 10, 2);

        // Create a pair of equal formats from different constructors.
        Format ascii10 = new Format("$ASCII", 10);
        Format ascii10Copy = new Format("$ASCII", 10, 0);

        // Create a pair of formats that have empty strings.
        Format emptyFormat = new Format("", 0, 0);
        Format emptyFormatCopy = new Format(copy(""), 0);

        // Create formats that only differ in exactly one field (and only by case for strings).
        Format differentName = new Format("myFormat", 10, 2);
        Format differentWidth = new Format("MyFormat", 9, 2);
        Format differentNumberOfDigits = new Format("MyFormat", 10, 1);

        List<Format> allFormats = List.of(
            format,
            formatCopy,
            format2,
            format2Copy,
            ascii10,
            ascii10Copy,
            emptyFormat,
            emptyFormatCopy,
            differentName,
            differentWidth,
            differentNumberOfDigits);

        // Equals is reflexive (special case)
        for (Format currentFormat : allFormats) {
            assertTrue(currentFormat.equals(currentFormat));
        }

        // Equivalent formats are equal.
        assertTrue(format.equals(formatCopy));
        assertTrue(format2.equals(format2Copy));
        assertTrue(ascii10.equals(ascii10Copy));
        assertTrue(emptyFormat.equals(emptyFormatCopy));

        // Different formats are not equal.
        assertFalse(format.equals(format2));
        assertFalse(format.equals(format2Copy));
        assertFalse(format.equals(ascii10));
        assertFalse(format.equals(ascii10Copy));
        assertFalse(format.equals(emptyFormatCopy));
        assertFalse(format.equals(emptyFormatCopy));
        assertFalse(format.equals(differentName));
        assertFalse(format.equals(differentWidth));
        assertFalse(format.equals(differentNumberOfDigits));
        assertFalse(differentName.equals(format));
        assertFalse(differentWidth.equals(format));
        assertFalse(differentNumberOfDigits.equals(differentName));

        // Equality is symmetric.
        assertTrue(formatCopy.equals(format));
        assertTrue(format2Copy.equals(format2));
        assertTrue(ascii10Copy.equals(ascii10));
        assertTrue(emptyFormatCopy.equals(emptyFormat));

        // Nothing is equal to null.
        for (Format currentFormat : allFormats) {
            assertFalse(currentFormat.equals(null));
        }

        // Test comparing against something that isn't a Format
        assertFalse(format.equals(format.name()));
    }
}