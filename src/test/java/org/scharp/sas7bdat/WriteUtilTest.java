package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link WriteUtil}. */
public class WriteUtilTest {

    /** Tests for {@link WriteUtil#write2} */
    @Test
    void testWrite2() {
        final byte[] data = new byte[9];

        // Simple write, tests endianness.
        WriteUtil.write2(data, 0, (short) 0xDDCC);
        assertArrayEquals(new byte[] { (byte) 0xCC, (byte) 0xDD, 0, 0, 0, 0, 0, 0, 0 }, data);

        // Can write 0
        WriteUtil.write2(data, 0, (short) 0);
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0 }, data);

        // Non-zero offset
        WriteUtil.write2(data, 2, (short) 0x0102);
        assertArrayEquals(new byte[] { 0, 0, 2, 1, 0, 0, 0, 0, 0 }, data);

        // negative offset
        Exception exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.write2(data, -2, (short) 0xFFFF));
        assertEquals("Index -1 out of bounds for length 9", exception.getMessage());
        assertArrayEquals(new byte[] { 0, 0, 2, 1, 0, 0, 0, 0, 0 }, data, "data changed on error");

        // offset is beyond end
        exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.write2(data, 10, (short) 0xFFFF));
        assertEquals("Index 11 out of bounds for length 9", exception.getMessage());
        assertArrayEquals(new byte[] { 0, 0, 2, 1, 0, 0, 0, 0, 0 }, data, "data changed on error");

        // offset one byte beyond end
        exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.write2(data, 8, (short) 0xFFFF));
        assertEquals("Index 9 out of bounds for length 9", exception.getMessage());
        assertArrayEquals(new byte[] { 0, 0, 2, 1, 0, 0, 0, 0, 0 }, data, "data changed on error");

        // null array
        assertThrows(NullPointerException.class, () -> WriteUtil.write2(null, 0, (short) 0xFFFF));
    }

    /** Tests for {@link WriteUtil#write4} */
    @Test
    void testWrite4() {
        final byte[] data = new byte[9];

        // Simple write, tests endianness.
        WriteUtil.write4(data, 0, 0xAABBCCDD);
        assertArrayEquals(new byte[] { (byte) 0xDD, (byte) 0xCC, (byte) 0xBB, (byte) 0xAA, 0, 0, 0, 0, 0 }, data);

        // Can write 0
        WriteUtil.write4(data, 0, 0);
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0 }, data);

        // Non-zero offset (2-byte aligned)
        WriteUtil.write4(data, 2, 0x01020304);
        assertArrayEquals(new byte[] { 0, 0, 4, 3, 2, 1, 0, 0, 0 }, data);

        // Non-zero offset (4-byte aligned)
        WriteUtil.write4(data, 4, 0x12345678);
        assertArrayEquals(new byte[] { 0, 0, 4, 3, 0x78, 0x56, 0x34, 0x12, 0 }, data);

        // negative offset
        Exception exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.write4(data, -2, (short) 0xFFFF));
        assertEquals("Index -1 out of bounds for length 9", exception.getMessage());

        // ideally, the array wouldn't change
        assertArrayEquals(new byte[] { (byte) 0xFF, (byte) 0xFF, 4, 3, 0x78, 0x56, 0x34, 0x12, 0 }, data);

        // offset is beyond end
        exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.write4(data, 10, (short) 0xFFFF));
        assertEquals("Index 13 out of bounds for length 9", exception.getMessage());
        assertArrayEquals(new byte[] { (byte) 0xFF, (byte) 0xFF, 4, 3, 0x78, 0x56, 0x34, 0x12, 0 }, data);

        // offset one byte beyond end
        exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.write4(data, 6, (short) 0xFFFF));
        assertEquals("Index 9 out of bounds for length 9", exception.getMessage());
        assertArrayEquals(new byte[] { (byte) 0xFF, (byte) 0xFF, 4, 3, 0x78, 0x56, 0x34, 0x12, 0 }, data);

        // null array
        assertThrows(NullPointerException.class, () -> WriteUtil.write4(null, 0, (short) 0xFFFF));
    }

    /** Tests for {@link WriteUtil#write8} */
    @Test
    void testWrite8() {
        final byte[] data = new byte[15];

        // Simple write, tests endianness.
        WriteUtil.write8(data, 0, 0x0123456789ABCDEFL);
        assertArrayEquals(
            new byte[] { (byte) 0xEF, (byte) 0xCD, (byte) 0xAB, (byte) 0x89, 0x67, 0x45, 0x23, 0x01, 0, 0, 0, 0, 0, 0,
                0 },
            data);

        // Can write 0
        WriteUtil.write8(data, 0, 0);
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, data);

        // Non-zero offset (2-byte aligned)
        WriteUtil.write8(data, 2, 0x0102030405060708L);
        assertArrayEquals(new byte[] { 0, 0, 8, 7, 6, 5, 4, 3, 2, 1, 0, 0, 0, 0, 0 }, data);

        // Non-zero offset (4-byte aligned)
        WriteUtil.write8(data, 4, 0x1112131415161718L);
        assertArrayEquals(new byte[] { 0, 0, 8, 7, 0x18, 0x17, 0x16, 0x15, 0x14, 0x13, 0x12, 0x11, 0, 0, 0 }, data);

        // Non-zero offset (6-byte aligned)
        WriteUtil.write8(data, 6, 0x2122232425262728L);
        assertArrayEquals(
            new byte[] { 0, 0, 8, 7, 0x18, 0x17, 0x28, 0x27, 0x26, 0x25, 0x24, 0x23, 0x22, 0x21, 0 },
            data);

        // negative offset
        Exception exception = assertThrows(ArrayIndexOutOfBoundsException.class, () -> WriteUtil.write8(data, -2, 0));
        assertEquals("Index -1 out of bounds for length 15", exception.getMessage());

        // ideally the array wouldn't be modified.
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 0, 0x28, 0x27, 0x26, 0x25, 0x24, 0x23, 0x22, 0x21, 0 }, data);

        // offset is beyond end
        exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.write8(data, 10, 0x010101010101010L));
        assertEquals("Index 17 out of bounds for length 15", exception.getMessage());
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 0, 0x28, 0x27, 0x26, 0x25, 0x24, 0x23, 0x22, 0x21, 0 }, data);

        // offset one byte beyond end
        exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.write8(data, 8, 0x0202020202020202L));
        assertEquals("Index 15 out of bounds for length 15", exception.getMessage());
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 0, 0x28, 0x27, 0x26, 0x25, 0x24, 0x23, 0x22, 0x21, 0 }, data);

        // null array
        assertThrows(NullPointerException.class, () -> WriteUtil.write8(null, 0, -1L));
    }

    /** Tests for {@link WriteUtil#writeUtf8} */
    @Test
    void testWriteUtf8() {
        final byte[] data = new byte[15];

        // Simple write
        WriteUtil.writeUtf8(data, 0, "abcdef", data.length, (byte) 1);
        assertArrayEquals(new byte[] { 'a', 'b', 'c', 'd', 'e', 'f', 1, 1, 1, 1, 1, 1, 1, 1, 1 }, data);

        // Non-zero offset (1-byte aligned), no padding
        WriteUtil.writeUtf8(data, 1, "hello world", 11, (byte) 0xFF);
        assertArrayEquals(new byte[] { 'a', 'h', 'e', 'l', 'l', 'o', ' ', 'w', 'o', 'r', 'l', 'd', 1, 1, 1 }, data);

        // non-ASCII string, padding
        final String grin = "\uD83D\uDE01"; // GRINNING FACE WITH SMILING EYES
        final String sigma = "\u03C3"; // GREEK SMALL LETTER SIGMA
        WriteUtil.writeUtf8(data, 2, grin + sigma, 9, (byte) 3);
        assertArrayEquals(
            new byte[] { 'a', 'h', (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x81, (byte) 0xCF, (byte) 0x83, 3, 3,
                3, 'd', 1, 1, 1 },
            data);

        // non-ASCII string, no padding, up to end of string
        WriteUtil.writeUtf8(data, 13, sigma, 2, (byte) 4);
        byte[] lastData = new byte[] { 'a', 'h', (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x81, (byte) 0xCF,
            (byte) 0x83, 3, 3, 3, 'd', 1, (byte) 0xCF, (byte) 0x83 };
        assertArrayEquals(lastData, data);

        // negative offset
        Exception exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.writeUtf8(data, -1, "bad", 3, (byte) 5));
        assertEquals("arraycopy: destination index -1 out of bounds for byte[15]", exception.getMessage());
        assertArrayEquals(lastData, data, "data was modified on error");

        // offset is beyond end
        exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.write8(data, 10, 0x010101010101010L));
        assertEquals("Index 17 out of bounds for length 15", exception.getMessage());
        assertArrayEquals(lastData, data, "data was modified on error");

        // offset one byte beyond end
        exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.write8(data, 8, 0x0202020202020202L));
        assertEquals("Index 15 out of bounds for length 15", exception.getMessage());
        assertArrayEquals(lastData, data, "data was modified on error");

        // null array
        assertThrows(NullPointerException.class, () -> WriteUtil.writeUtf8(null, 0, "null", 4, (byte) 1));

        // null string
        assertThrows(NullPointerException.class, () -> WriteUtil.writeUtf8(data, 0, null, 0, (byte) 1));
        assertArrayEquals(lastData, data, "data was modified on error");
    }

    /** Tests for {@link WriteUtil#writeAscii} */
    @Test
    void testWriteAscii() {
        final byte[] data = new byte[12];

        // Simple write with padding.
        WriteUtil.writeAscii(data, 0, "ABCDE", data.length);
        assertArrayEquals(new byte[] { 'A', 'B', 'C', 'D', 'E', ' ', ' ', ' ', ' ', ' ', ' ', ' ' }, data);

        // Non-zero offset (1-byte aligned), no padding
        WriteUtil.writeAscii(data, 1, "hello world", 11);
        final byte[] lastData = new byte[] { 'A', 'h', 'e', 'l', 'l', 'o', ' ', 'w', 'o', 'r', 'l', 'd' };
        assertArrayEquals(lastData, data);

        // negative offset
        Exception exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.writeAscii(data, -1, "bad", 3));
        assertEquals("arraycopy: destination index -1 out of bounds for byte[12]", exception.getMessage());
        assertArrayEquals(lastData, data, "data was modified on error");

        // offset is beyond end
        exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.writeAscii(data, 100, "bad", 3));
        assertEquals("arraycopy: last destination index 103 out of bounds for byte[12]", exception.getMessage());
        assertArrayEquals(lastData, data, "data was modified on error");

        // offset one byte beyond end
        exception = assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> WriteUtil.writeAscii(data, 10, "bad", 3));
        assertEquals("arraycopy: last destination index 13 out of bounds for byte[12]", exception.getMessage());
        assertArrayEquals(lastData, data, "data was modified on error");

        // null array
        assertThrows(NullPointerException.class, () -> WriteUtil.writeAscii(null, 0, "data", 4));

        // null string
        assertThrows(NullPointerException.class, () -> WriteUtil.writeAscii(data, 0, null, 0));
        assertArrayEquals(lastData, data, "data was modified on error");
    }
}