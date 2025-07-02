package org.scharp.sas7bdat;

/**
 * A mock subheader of a fixed size.  This can be used to reserve space on a metadata page.
 */
class FillerSubheader extends Subheader {

    private final int size;

    FillerSubheader(int size) {
        this.size = size;
    }

    @Override
    int size() {
        return size;
    }

    @Override
    void writeSubheader(byte[] page, int subheaderOffset) {
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