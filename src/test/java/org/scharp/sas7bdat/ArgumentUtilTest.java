package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ArgumentUtilTest {

    /** Tests for {@link ArgumentUtil#checkMaximumLength(String, Charset, int, String)} */
    @Test
    void testCheckMaximumLength() {
        final String grin = "\uD83D\uDE01"; // GRINNING FACE WITH SMILING EYES (four bytes in UTF-8)
        final String sigma = "\u03C3"; // GREEK SMALL LETTER SIGMA (two bytes in UTF-8)

        // empty string fits into 0 bytes.
        ArgumentUtil.checkMaximumLength("", StandardCharsets.US_ASCII, 0, "arg");
        ArgumentUtil.checkMaximumLength("", StandardCharsets.UTF_8, 0, "arg");

        // empty string fits into 1 byte.
        ArgumentUtil.checkMaximumLength("", StandardCharsets.US_ASCII, 1, "arg");
        ArgumentUtil.checkMaximumLength("", StandardCharsets.UTF_8, 1, "arg");

        // Normally string arguments are ASCII.
        ArgumentUtil.checkMaximumLength("hello", StandardCharsets.US_ASCII, 5, "arg");
        ArgumentUtil.checkMaximumLength("hello", StandardCharsets.UTF_8, 5, "arg");

        Exception exception = assertThrows(
            IllegalArgumentException.class,
            () -> ArgumentUtil.checkMaximumLength("hello", StandardCharsets.US_ASCII, 4, "arg"));
        assertEquals("arg must not be longer than 4 bytes when encoded with US-ASCII", exception.getMessage());

        exception = assertThrows(
            IllegalArgumentException.class,
            () -> ArgumentUtil.checkMaximumLength("hello", StandardCharsets.UTF_8, 3, "myArg"));
        assertEquals("myArg must not be longer than 3 bytes when encoded with UTF-8", exception.getMessage());

        // sigma is 1 character but 2 in UTF-8; in ASCII it's just ?.
        ArgumentUtil.checkMaximumLength(sigma, StandardCharsets.US_ASCII, 1, "arg");
        ArgumentUtil.checkMaximumLength(sigma, StandardCharsets.UTF_8, 2, "arg");

        exception = assertThrows(
            IllegalArgumentException.class,
            () -> ArgumentUtil.checkMaximumLength(sigma, StandardCharsets.US_ASCII, 0, "arg"));
        assertEquals("arg must not be longer than 0 bytes when encoded with US-ASCII", exception.getMessage());

        exception = assertThrows(
            IllegalArgumentException.class,
            () -> ArgumentUtil.checkMaximumLength(sigma, StandardCharsets.UTF_8, 1, "myArg"));
        assertEquals("myArg must not be longer than 1 byte when encoded with UTF-8", exception.getMessage());

        // sigma+sigma is 2 chars and 4 bytes.
        ArgumentUtil.checkMaximumLength(sigma, StandardCharsets.US_ASCII, 2, "arg");
        ArgumentUtil.checkMaximumLength(sigma, StandardCharsets.UTF_8, 4, "arg");

        exception = assertThrows(
            IllegalArgumentException.class,
            () -> ArgumentUtil.checkMaximumLength(sigma + sigma, StandardCharsets.UTF_8, 0, "the argument"));
        assertEquals("the argument must not be longer than 0 bytes when encoded with UTF-8", exception.getMessage());

        // A grin is 2 chars, 4 bytes in UTF-8, and 1 byte in ASCII.
        ArgumentUtil.checkMaximumLength(grin, StandardCharsets.US_ASCII, 2, "arg");
        ArgumentUtil.checkMaximumLength(grin, StandardCharsets.UTF_8, 4, "arg");

        exception = assertThrows(
            IllegalArgumentException.class,
            () -> ArgumentUtil.checkMaximumLength(grin, StandardCharsets.US_ASCII, 0, "arg"));
        assertEquals("arg must not be longer than 0 bytes when encoded with US-ASCII", exception.getMessage());

        exception = assertThrows(
            IllegalArgumentException.class,
            () -> ArgumentUtil.checkMaximumLength(grin, StandardCharsets.UTF_8, 3, "argument"));
        assertEquals("argument must not be longer than 3 bytes when encoded with UTF-8", exception.getMessage());
    }
}