package org.scharp.sas7bdat;

import java.util.List;

import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A subheader that contains information about columns sizes.
 */
class ColumnSizeSubheader extends Subheader {
    /**
     * The number of bytes in a ColumnSizeSubheader
     */
    private static final int PAGE_SIZE = 24;

    private final int totalVariables;

    ColumnSizeSubheader(List<Variable> variables) {
        totalVariables = variables.size();
    }

    @Override
    int size() {
        return PAGE_SIZE;
    }

    @Override
    void writeSubheader(byte[] page, int subheaderOffset) {
        write8(page, subheaderOffset, SIGNATURE_COLUMN_SIZE); // signature
        write8(page, subheaderOffset + 8, totalVariables); // number of columns
        write8(page, subheaderOffset + 16, 0x00); // unknown, maybe padding
    }

    @Override
    byte typeCode() {
        return SUBHEADER_TYPE_A;
    }

    @Override
    byte compressionCode() {
        return COMPRESSION_UNCOMPRESSED;
    }
}