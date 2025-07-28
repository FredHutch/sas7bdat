///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Utility methods for writing data to a byte array */
final class WriteUtil {

    // private constructor to prevent anyone from instantiating the class.
    private WriteUtil() {
    }

    /**
     * Writes a two byte numeric value as little endian to an array.
     *
     * @param data
     *     The array to write to.
     * @param offset
     *     The offset in the array to write to.
     * @param number
     *     The number to write.
     *
     * @return The number of bytes written.
     */
    static int write2(byte[] data, int offset, short number) {
        // serialized as little-endian
        assert offset % 2 == 0 : "offset is not 2-byte aligned";
        data[offset + 1] = (byte) (number >> 8);
        data[offset] = (byte) number;
        return 2;
    }

    /**
     * Writes a four byte numeric value as little endian to an array.
     *
     * @param data
     *     The array to write to.
     * @param offset
     *     The offset in the array to write to.
     * @param number
     *     The number to write.
     *
     * @return The number of bytes written.
     */
    static int write4(byte[] data, int offset, int number) {
        // serialized as little-endian
        assert offset % 2 == 0 : "offset is not 2-byte aligned";
        data[offset + 3] = (byte) (number >> 24);
        data[offset + 2] = (byte) (number >> 16);
        data[offset + 1] = (byte) (number >> 8);
        data[offset] = (byte) number;
        return 4;
    }

    /**
     * Writes an eight byte numeric value as little endian to an array.
     *
     * @param data
     *     The array to write to.
     * @param offset
     *     The offset in the array to write to.
     * @param number
     *     The number to write.
     *
     * @return The number of bytes written.
     */
    static int write8(byte[] data, int offset, long number) {
        // serialized as little-endian
        assert offset % 2 == 0 : "offset is not 2-byte aligned";
        data[offset + 7] = (byte) (number >> 56);
        data[offset + 6] = (byte) (number >> 48);
        data[offset + 5] = (byte) (number >> 40);
        data[offset + 4] = (byte) (number >> 32);
        data[offset + 3] = (byte) (number >> 24);
        data[offset + 2] = (byte) (number >> 16);
        data[offset + 1] = (byte) (number >> 8);
        data[offset] = (byte) number;
        return 8;
    }

    /**
     * Writes a string to a binary array as UTF-8.
     *
     * @param data
     *     The array to write to.
     * @param offset
     *     The offset of the array to which the first byte of the string is written.
     * @param string
     *     The string to write.
     * @param length
     *     How many bytes should be written.  If {@code string} is less than the number of bytes, then the bytes after
     *     the end of the string are set to {@code paddingByte}.  This must be at least the number of bytes in
     *     {@code string} when encoded in UTF-8.
     * @param paddingByte
     *     What to write in the extra space after {@code string}.
     */
    static void writeUtf8(byte[] data, int offset, String string, int length, byte paddingByte) {
        byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
        assert utf8.length <= length;

        // copy the string
        System.arraycopy(utf8, 0, data, offset, utf8.length);

        // pad the rest
        Arrays.fill(data, offset + utf8.length, offset + length, paddingByte);
    }

    /**
     * Writes an ASCII string to a binary array.
     *
     * @param data
     *     The array to write to.
     * @param offset
     *     The offset of the array to which the first byte of the string is written.
     * @param string
     *     The string to write.  This must be entirely composed of ASCII characters.
     * @param length
     *     The number of bytes to write. If the length of {@code string} is less than this, then the bytes after the end
     *     of the string are set to an SPACE character (' ').
     */
    static void writeAscii(byte[] data, int offset, String string, int length) {
        assert string.matches("^\\p{ASCII}*$");
        assert string.length() <= length;
        writeUtf8(data, offset, string, length, (byte) ' '); // pad with spaces
    }

    /**
     * Computes an offset that is aligned to a given alignment size.
     * <p>
     * The returned value is the smallest number greater than or equal to {@code number} that is a multiple of
     * {@code alignmentSize}.
     * </p>
     *
     * @param number
     *     The number to align.
     * @param alignmentSize
     *     The desired alignment.
     *
     * @return An aligned number.
     */
    static int align(int number, int alignmentSize) {
        int excess = number % alignmentSize;
        return excess == 0 ? number : number + alignmentSize - excess;
    }
}