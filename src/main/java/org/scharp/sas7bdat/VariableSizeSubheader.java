package org.scharp.sas7bdat;

import static org.scharp.sas7bdat.WriteUtil.write4;
import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A variable-sized subheader.
 */
abstract class VariableSizeSubheader extends Subheader {

    /** The number of bytes for the "data size" field, which is two bytes, plus other information */
    static final int PAYLOAD_DESCRIPTION_FIELD_SIZE = 8;

    /** The number of bytes of blank data at the end of a subheader */
    private static final int FOOTER_PADDING_SIZE = 12;

    /**
     * The total number of bytes of overhead that a variable subheader has (excluding the payload). This is the
     * signature, the size field, and the footer padding.
     */
    static final int VARIABLE_SUBHEADER_OVERHEAD = SIGNATURE_SIZE + PAYLOAD_DESCRIPTION_FIELD_SIZE + FOOTER_PADDING_SIZE;

    /**
     * The number of bytes of data in this subheader without signature or the footer padding.
     *
     * @return The number of bytes of data in this subheader
     */
    abstract int sizeOfData();

    /**
     * Writes the variable-sized portion of this subheader to a buffer.  This excludes the signature, the size of data
     * field, and the footer padding.
     *
     * @param page
     *     The array of bytes which represents the enclosing data page.
     * @param subheaderOffset
     *     The offset in {@code page} to which this subheader should be written. This is the offset of the overall
     *     subheader, not the variable-sized portion.
     */
    abstract void writeVariableSizedPayload(byte[] page, int subheaderOffset);

    @Override
    final void writeSubheader(byte[] page, int subheaderOffset) {
        write8(page, subheaderOffset, signature()); // signature

        // sizeOfData
        final int lengthInSubheader = sizeOfData();
        assert lengthInSubheader <= Short.MAX_VALUE;
        write8(page, subheaderOffset + SIGNATURE_SIZE, lengthInSubheader);

        // Write the subheader data (implemented by derived class)
        writeVariableSizedPayload(page, subheaderOffset);

        // There are 12 bytes of padding at the end.
        int offsetWithinSubheader = SIGNATURE_SIZE + lengthInSubheader;
        write4(page, subheaderOffset + offsetWithinSubheader, 0);
        write8(page, subheaderOffset + offsetWithinSubheader + 4, 0);
    }

    @Override
    final int size() {
        return sizeOfData() + SIGNATURE_SIZE + FOOTER_PADDING_SIZE;
    }

    @Override
    final byte typeCode() {
        return SUBHEADER_TYPE_B;
    }

    @Override
    final byte compressionCode() {
        return COMPRESSION_UNCOMPRESSED;
    }
}