package org.scharp.sas7bdat;

import java.util.Arrays;

/**
 * A mock subheader of a fixed size.  This can be used to reserve space on a metadata page.
 */
class FillerSubheader extends Subheader {

    private final int size;
    private final byte fillByte;

    FillerSubheader(int size, byte fillByte) {
        this.size = size;
        this.fillByte = fillByte;
    }

    FillerSubheader(int size) {
        this(size, (byte) 0);
    }

    @Override
    int size() {
        return size;
    }

    @Override
    void writeSubheader(byte[] page, int subheaderOffset) {
        // Write a solid block of the fill byte.
        Arrays.fill(page, subheaderOffset, subheaderOffset + size, fillByte);
    }

    @Override
    byte typeCode() {
        return SUBHEADER_TYPE_A;
    }

    @Override
    byte compressionCode() {
        return COMPRESSION_UNCOMPRESSED;
    }

    static FillerSubheader fillRestOfPage(Sas7bdatPage page) {
        return new FillerSubheader(page.totalBytesRemainingForNewSubheader());
    }
}