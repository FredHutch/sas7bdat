package org.scharp.sas7bdat;

/**
 * A class with utility methods for validating arguments.
 */
abstract class ArgumentUtil {
    /**
     * Throws an exception if {@code argument} is {@code null}.
     *
     * @param argument
     *     The argument to check.
     * @param argumentName
     *     The name of the argument. This is used to create a more informative exception message.
     *
     * @throws NullPointerException
     *     if {@code argument} is {@code null}.
     */
    static void checkNotNull(Object argument, String argumentName) {
        if (argument == null) {
            throw new NullPointerException(argumentName + " must not be null");
        }
    }

    /**
     * Throws an exception if {@code argument} is not composed of characters that are within the ASCII character set.
     *
     * @param argument
     *     The string to check
     * @param argumentName
     *     The name of the argument. This is used to create a more informative exception message.
     *
     * @throws IllegalArgumentException
     *     if {@code argument} contains a character that is not within the ASCII character set.
     */
    static void checkIsAscii(String argument, String argumentName) {
        for (int i = 0; i < argument.length(); i++) {
            if (0x80 <= argument.codePointAt(i)) { // ASCII is only 7-bits
                throw new IllegalArgumentException(argumentName + " must contain only ASCII (7-bit) characters");
            }
        }
    }

    /**
     * Throws an exception if {@code argument} is longer than a given number of characters.
     *
     * @param argument
     *     The string to check
     * @param maximumLength
     *     The maximum length that {@code argument} may be.
     * @param argumentName
     *     The name of the argument. This is used to create a more informative exception message.
     *
     * @throws IllegalArgumentException
     *     if {@code argument} is longer than {@code maximumLength}.
     */
    static void checkMaximumLength(String argument, int maximumLength, String argumentName) {
        if (maximumLength < argument.length()) {
            throw new IllegalArgumentException(
                argumentName + " must not be longer than " + maximumLength + " characters");
        }
    }
}