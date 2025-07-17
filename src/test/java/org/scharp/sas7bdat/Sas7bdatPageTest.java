package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Sas7bdatPage}. */
public class Sas7bdatPageTest {

    @Test
    void testPureMetadataPage() {
        // Create a sas7bdat page
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of());
        final int pageSize = 0x10000;
        Sas7bdatPage page = new Sas7bdatPage(pageSequenceGenerator, pageSize, variablesLayout);

        assertEquals(pageSize - 40 - 24 * 2, page.totalBytesRemainingForNewSubheader());

        // Add some subheaders
        Subheader subheader1 = new FillerSubheader(100, (byte) 1);
        Subheader subheader2 = new FillerSubheader(300, (byte) 2);

        assertTrue(page.addSubheader(subheader1));
        assertEquals(
            pageSize - 40 - 24 * 3 - subheader1.size(),
            page.totalBytesRemainingForNewSubheader());

        assertTrue(page.addSubheader(subheader2));
        assertEquals(
            pageSize - 40 - 24 * 4 - subheader1.size() - subheader2.size(),
            page.totalBytesRemainingForNewSubheader());

        // Finalize
        page.finalizeSubheaders();

        // Write the page.
        byte[] actualData = new byte[pageSize];
        page.write(actualData);

        // Confirm that the expected data was written.
        byte[] expectedData = new byte[pageSize];
        WriteUtil.write4(expectedData, 0, 0xF4_A4_FF_F7); // page sequence number
        WriteUtil.write4(expectedData, 24, (pageSize - 40 - 24 * 3 - 100 - 300)); // total bytes free
        WriteUtil.write2(expectedData, 32, (short) 0); // type=META
        WriteUtil.write2(expectedData, 34, (short) 3); // total blocks
        WriteUtil.write2(expectedData, 36, (short) 3); // total subheaders

        WriteUtil.write8(expectedData, 40, pageSize - subheader1.size()); // subheader #1 location
        WriteUtil.write8(expectedData, 48, subheader1.size()); // subheader #1 size
        WriteUtil.write2(expectedData, 50, subheader1.compressionCode()); // subheader #1 compression
        WriteUtil.write2(expectedData, 52, subheader1.typeCode()); // subheader #1 type

        WriteUtil.write8(expectedData, 64, pageSize - subheader1.size() - subheader2.size()); // subheader #2 location
        WriteUtil.write8(expectedData, 72, subheader2.size()); // subheader #2 size
        WriteUtil.write2(expectedData, 80, subheader2.compressionCode()); // subheader #2 compression
        WriteUtil.write2(expectedData, 82, subheader2.typeCode()); // subheader #2 type

        WriteUtil.write8(expectedData, 88, pageSize - subheader1.size() - subheader2.size()); // subheader #3 location
        WriteUtil.write8(expectedData, 96, 0); // subheader #3 size
        WriteUtil.write2(expectedData, 104, Subheader.COMPRESSION_TRUNCATED); // subheader #3 compression
        WriteUtil.write2(expectedData, 106, Subheader.SUBHEADER_TYPE_A); // subheader #3 type

        Arrays.fill(expectedData, pageSize - 100 - 300, pageSize - 100, (byte) 2); // subheader #2
        Arrays.fill(expectedData, pageSize - 100, pageSize, (byte) 1); // subheader #1

        assertArrayEquals(expectedData, actualData, "Sas7bdatPage.write() wrote incorrect data");

        // The page sequence should have been incremented.
        assertNotEquals(pageSequenceGenerator.initialPageSequence(), pageSequenceGenerator.currentPageSequence());
    }

    @Test
    void testMixedPage() {
        // Create a sas7bdat page
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of(
            Variable.builder().name("VAR").type(VariableType.CHARACTER).length(4).build()));
        final int pageSize = 0x10000;
        Sas7bdatPage page = new Sas7bdatPage(pageSequenceGenerator, pageSize, variablesLayout);

        assertEquals(pageSize - 40 - 24 * 2, page.totalBytesRemainingForNewSubheader());

        // Add a subheader
        Subheader subheader = new FillerSubheader(1000, (byte) 1);
        assertTrue(page.addSubheader(subheader));
        assertEquals(pageSize - 40 - 24 * 3 - subheader.size(), page.totalBytesRemainingForNewSubheader());

        // Finalize
        page.finalizeSubheaders();

        // Add some observations
        byte[] observation1 = new byte[] { 'a', 'b', 'c', 'd' };
        assertTrue(page.addObservation(observation1));

        byte[] observation2 = new byte[] { '1', '2', '3', '4' };
        assertTrue(page.addObservation(observation2));

        // Write the page.
        byte[] actualData = new byte[pageSize];
        page.write(actualData);

        // Confirm that the expected data was written.
        byte[] expectedData = new byte[pageSize];
        WriteUtil.write4(expectedData, 0, 0xF4_A4_FF_F7); // page sequence number
        WriteUtil.write4(expectedData, 24, (pageSize - 40 - 24 * 2 - 1000 - 4 - 4 - 1)); // total bytes free
        WriteUtil.write2(expectedData, 32, (short) 0x200); // type=MIXED
        WriteUtil.write2(expectedData, 34, (short) 4); // total blocks (2 subheaders + 2 observations)
        WriteUtil.write2(expectedData, 36, (short) 2); // total subheaders

        WriteUtil.write8(expectedData, 40, pageSize - subheader.size()); // subheader #1 location
        WriteUtil.write8(expectedData, 48, subheader.size()); // subheader #1 size
        WriteUtil.write2(expectedData, 50, subheader.compressionCode()); // subheader #1 compression
        WriteUtil.write2(expectedData, 52, subheader.typeCode()); // subheader #1 type

        WriteUtil.write8(expectedData, 64, pageSize - subheader.size()); // subheader #2 location
        WriteUtil.write8(expectedData, 72, 0); // subheader #2 size
        WriteUtil.write2(expectedData, 80, Subheader.COMPRESSION_TRUNCATED); // subheader #2 compression
        WriteUtil.write2(expectedData, 82, Subheader.SUBHEADER_TYPE_A); // subheader #2 type

        WriteUtil.writeUtf8(expectedData, 88, "abcd", 4, (byte) 0); // observation #1
        WriteUtil.writeUtf8(expectedData, 92, "1234", 4, (byte) 0); // observation #2

        Arrays.fill(expectedData, pageSize - 1000, pageSize, (byte) 1); // subheader #1

        assertArrayEquals(expectedData, actualData, "Sas7bdatPage.write() wrote incorrect data");

        // The page sequence should have been incremented.
        assertNotEquals(pageSequenceGenerator.initialPageSequence(), pageSequenceGenerator.currentPageSequence());
    }
}