///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import java.util.List;

import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A subheader that contains information about the number of columns in a dataset.
 */
class ColumnSizeSubheader extends FixedSizeSubheader {

    private final int totalVariables;

    ColumnSizeSubheader(List<Variable> variables) {
        totalVariables = variables.size();
    }

    @Override
    int size() {
        return 24;
    }

    @Override
    void writeSubheader(byte[] page, int subheaderOffset) {
        write8(page, subheaderOffset, signature()); // signature
        write8(page, subheaderOffset + 8, totalVariables); // number of columns
        write8(page, subheaderOffset + 16, 0x00); // unknown, maybe padding
    }

    @Override
    long signature() {
        return SIGNATURE_COLUMN_SIZE;
    }
}