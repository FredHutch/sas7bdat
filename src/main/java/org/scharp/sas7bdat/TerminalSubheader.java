package org.scharp.sas7bdat;

/**
 * A zero-sized (truncated) subheader that is used to indicate there are no more subheaders on a page.
 */
class TerminalSubheader extends Subheader {

    TerminalSubheader() {
    }

    @Override
    int size() {
        return 0;
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
        return COMPRESSION_TRUNCATED;
    }
}