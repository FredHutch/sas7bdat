package org.scharp.sas7bdat;

import java.nio.charset.Charset;

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
     * Throws an exception if {@code argument} is longer than a given number of bytes when encoded.
     *
     * @param argument
     *     The string to check
     * @param charset
     *     The character set in which to encode {@code argument}.
     * @param maximumLengthInBytes
     *     The maximum length that {@code argument} may be when encoded with {@code charset}.
     * @param argumentName
     *     The name of the argument. This is used to create a more informative exception message.
     *
     * @throws IllegalArgumentException
     *     if {@code argument} is longer than {@code maximumLengthInBytes}.
     */
    static void checkMaximumLength(String argument, Charset charset, int maximumLengthInBytes, String argumentName) {
        assert charset != null : "charset must not be null";
        assert 0 <= maximumLengthInBytes : "maximumLengthInBytes must not be negative";
        assert argumentName != null : "argumentName must not be null";

        if (maximumLengthInBytes < argument.getBytes(charset).length) {
            if (maximumLengthInBytes == 1) {
                throw new IllegalArgumentException(
                    argumentName + " must not be longer than " + maximumLengthInBytes + " byte when encoded with " + charset.name());
            } else {
                throw new IllegalArgumentException(
                    argumentName + " must not be longer than " + maximumLengthInBytes + " bytes when encoded with " + charset.name());
            }
        }
    }

    /**
     * Throws an exception if {@code argument} is negative (less than zero).
     *
     * @param argument
     *     The argument to check
     * @param argumentName
     *     The name of the argument. This is used to create a more informative exception message.
     *
     * @throws IllegalArgumentException
     *     if {@code argument} is negative
     */
    static void checkNotNegative(int argument, String argumentName) {
        assert argumentName != null : "argumentName must not be null";

        if (argument < 0) {
            throw new IllegalArgumentException(argumentName + " must not be negative");
        }
    }
}