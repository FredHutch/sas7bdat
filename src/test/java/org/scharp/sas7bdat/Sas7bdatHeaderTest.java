package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests for {@link Sas7bdatHeader} */
public class Sas7bdatHeaderTest {

    @Test
    public void smokeTest() {
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        long currentPageSequence = pageSequenceGenerator.currentPageSequence();
        int headerSize = 512;
        Sas7bdatHeader header = new Sas7bdatHeader(
            pageSequenceGenerator,
            0x100, // header size
            0x10000, // page size
            "dataset_name", // dataset name
            LocalDateTime.of(2000, 3, 10, 10, 0, 5), // creation date
            100); // total pages

        // Write the header to a byte array.
        byte[] data = new byte[headerSize];
        header.write(data);

        assertArrayEquals(
            new byte[] {
                // file magic number
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, -62, -22, -127, 96,
                -77, 20, 17, -49, -67, -110, 8, 0,
                9, -57, 49, -116, 24, 31, 16, 17,

                51, 0x22, 0, 0x33, 0x33, 1, 2, 49,
                1, 0, 0, 0, 0, 0, 0, 20,
                0, 0, 3, 1, 24, 31, 16, 17,
                51, 34, 0, 51, 51, 1, 2, 49,
                1, 51, 1, 35, 51, 0, 20, 20,
                0, 32, 3, 1, 0, 0, 0, 0,
                0, 0, 0, 0,

                'S', 'A', 'S', ' ', 'F', 'I', 'L', 'E',

                // file label
                'd', 'a', 't', 'a', 's', 'e', 't', '_', 'n', 'a', 'm', 'e', ' ', ' ', ' ', ' ',
                ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
                ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
                ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ',

                // dataset type
                'D', 'A', 'T', 'A', ' ', ' ', ' ', ' ',
                0, 0, 0, 0,
                0, 0, 64, -55, 47, -26, -46, 65, // creation time
                0, 0, 64, -55, 47, -26, -46, 65, // modified time

                0, 0, 0, 0, 0, 32, -36, -64,
                0, 0, 0, 0, 0, 32, -36, -64,

                0, 1, 0, 0, // header size
                0, 0, 1, 0, // page size

                100, 0, 0, 0, 0, 0, 0, 0, // total pages
                0, 0, 0, 0, 0, 0, 0, 0,

                '9', '.', '0', '4', '0', '1', 'M', '2', // SAS Version
                'L', 'i', 'n', 'u', 'x', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', // server type
                '4', '.', '4', '.', '1', '0', '4', '-', '1', '8', '.', '4', '4', ' ', ' ', ' ', // OS version
                ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', // OS maker
                'x', '8', '6', '_', '6', '4', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', // OS name

                56, -64, -56, -44, // pattern1
                116, -114, -89, -79, // pattern2
                116, -114, -89, -79, // pattern2
                116, -114, -89, -79, // pattern2

                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,

                -10, -1, -92, -12, 0, 0, 0, 0, // initial page sequence

                0, 0, 64, -55, 47, -26, -46, 65, // creation time again

                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,

                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            },
            data);

        // The page sequence number should not have been incremented.
        assertEquals(currentPageSequence, pageSequenceGenerator.currentPageSequence());
    }
}