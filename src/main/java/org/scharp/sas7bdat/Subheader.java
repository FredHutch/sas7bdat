package org.scharp.sas7bdat;

import static org.scharp.sas7bdat.WriteUtil.write2;
import static org.scharp.sas7bdat.WriteUtil.write4;
import static org.scharp.sas7bdat.WriteUtil.write8;

abstract class Subheader {
    static final int SIGNATURE_SIZE = 8; // 8 bytes
    static final long SIGNATURE_ROW_SIZE = 0x00000000F7F7F7F7L;
    static final long SIGNATURE_COLUMN_SIZE = 0x00000000F6F6F6F6L;
    static final long SIGNATURE_SUBHEADER_COUNTS = 0xFFFFFFFFFFFFFC00L;
    static final long SIGNATURE_COLUMN_FORMAT = 0xFFFFFFFFFFFFFBFEL;
    static final long SIGNATURE_COLUMN_ATTRS = 0xFFFFFFFFFFFFFFFCL;
    static final long SIGNATURE_COLUMN_TEXT = 0xFFFFFFFFFFFFFFFDL;
    static final long SIGNATURE_COLUMN_LIST = 0xFFFFFFFFFFFFFFFEL;
    static final long SIGNATURE_COLUMN_NAME = 0xFFFFFFFFFFFFFFFFL;

    static final long SIGNATURE_UNKNOWN_A = 0xFFFFFFFFFFFFFFFBL;
    static final long SIGNATURE_UNKNOWN_B = 0xFFFFFFFFFFFFFFFAL;
    static final long SIGNATURE_UNKNOWN_C = 0xFFFFFFFFFFFFFFF9L;

    /**
     * @return the total number of bytes in this subheader.
     */
    abstract int size();

    /**
     * Writes the contents of this subheader to an array at a given offset.
     *
     * <p>
     * The caller is responsible for allocating enough memory
     * </p>
     *
     * @param page
     *     The array of bytes which represents the enclosing data page.
     * @param subheaderOffset
     *     The offset in {@code page} to which this subheader should be written.
     */
    abstract void writeSubheader(byte[] page, int subheaderOffset);

    /**
     * @return A byte which represents the "type" of this subheader.
     */
    abstract byte typeCode();

    /**
     * @return A byte which represents how this subheader is compressed.
     */
    abstract byte compressionCode();

    void writeSubheaderIndex(byte[] page, int pageOffset, int subheaderOffset) {
        write8(page, pageOffset, subheaderOffset); // offset of subheader
        write8(page, pageOffset + 8, size()); // length of subheader
        page[pageOffset + 16] = compressionCode();
        page[pageOffset + 17] = typeCode();
        write2(page, pageOffset + 18, (short) 0); // unknown, likely padding
        write4(page, pageOffset + 20, (short) 0); // unknown, likely padding
    }
}