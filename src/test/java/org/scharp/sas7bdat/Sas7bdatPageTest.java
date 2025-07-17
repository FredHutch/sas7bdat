package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Unit tests for {@link Sas7bdatPage}. */
public class Sas7bdatPageTest {

    @Test
    void testPureMetadataPage() {
        // Create a sas7bdat page
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of());
        final int pageSize = 0x10000;
        Sas7bdatPage page = new Sas7bdatPage(pageSequenceGenerator, pageSize, variablesLayout);

        // Add some subheaders
        Subheader subheader1 = new FillerSubheader(100, (byte) 1);
        Subheader subheader2 = new FillerSubheader(300, (byte) 2);
        page.addSubheader(subheader1);
        page.addSubheader(subheader2);

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
}