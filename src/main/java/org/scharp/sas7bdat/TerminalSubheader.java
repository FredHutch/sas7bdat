///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

/**
 * A zero-sized subheader that is used to indicate there are no more subheaders on a page.
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
    long signature() {
        return 0;
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