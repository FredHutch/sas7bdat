package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_TRUNCATED;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_A;

/** Unit tests for {@link TerminalSubheader}. */
public class TerminalSubheaderTest {

    @Test
    void testTypeCode() {
        TerminalSubheader terminalSubheader = new TerminalSubheader();
        assertEquals(SUBHEADER_TYPE_A, terminalSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        TerminalSubheader terminalSubheader = new TerminalSubheader();
        assertEquals(COMPRESSION_TRUNCATED, terminalSubheader.compressionCode());
    }

    @Test
    void basicTest() {
        TerminalSubheader terminalSubheader = new TerminalSubheader();

        final byte[] expectedSubheaderData = {};

        // Write the contents of the subheader to a byte array.
        assertEquals(expectedSubheaderData.length, terminalSubheader.size());
        byte[] actualSubheaderData = new byte[expectedSubheaderData.length];
        terminalSubheader.writeSubheader(actualSubheaderData, 0);

        // Confirm that writeSubheader() wrote nothing.
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }
}