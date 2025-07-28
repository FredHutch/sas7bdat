///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import java.util.List;

import static org.scharp.sas7bdat.WriteUtil.write2;

/**
 * A subheader that contains the names of columns
 */
class ColumnNameSubheader extends VariableSizeSubheader {

    /**
     * No subheader can be larger than Short.MAX_VALUE, so we only have room for (Short.MAX_VALUE - 28) / 8 = 4092
     * variables in each one.  However, datasets which SAS creates puts a maximum of 4089 in each subheader.
     */
    private static final int MAX_VARIABLES_PER_SUBHEADER = 4089;

    /**
     * Number of bytes in each variable name entry.
     */
    private static final int SIZE_OF_ENTRY = 8;

    private final List<Variable> variables;
    private final ColumnText columnText;

    /**
     * Creates a column name subheader, which is a subheader of most variable names.
     * <p>
     * Most datasets have only one column name subheader.  However, with more than 4089 variables, need more than one.
     * </p>
     *
     * @param variables
     *     A list of variables to put into the column name subheader (as many as will fit).
     * @param variablesOffset
     *     The offset the {@code variables} list to start.  For the first column text subheader, this should be
     *     {@code 0}.  Subsequent column text subheaders should continue where the previous one left off.
     * @param columnText
     *     The column text that holds the name of all variables in the variables list.
     */
    ColumnNameSubheader(List<Variable> variables, int variablesOffset, ColumnText columnText) {
        // Determine how many variables, starting at offset, this subheader will hold.
        assert variablesOffset < variables.size();
        int variablesRemaining = variables.size() - variablesOffset;
        int totalVariablesInSubheader = Math.min(variablesRemaining, MAX_VARIABLES_PER_SUBHEADER);

        // Copy the variables
        int limit = variablesOffset + totalVariablesInSubheader;
        this.variables = variables.subList(variablesOffset, limit);

        this.columnText = columnText;

        assert size() <= Short.MAX_VALUE : "Too many variables in ColumnNameSubheader";
    }

    /**
     * Gets the number of variables that fit into this subheader.
     *
     * @return The number of variables
     */
    int totalVariablesInSubheader() {
        return variables.size();
    }

    /**
     * The number of bytes of data in this subheader without signature or FOOTER_PADDING.
     *
     * @return The number of bytes of data in this subheader
     */
    @Override
    int sizeOfData() {
        return variables.size() * SIZE_OF_ENTRY + 8;
    }

    @Override
    void writeVariableSizedPayload(byte[] page, int subheaderOffset) {

        int offsetWithinSubheader = SIGNATURE_SIZE + PAYLOAD_DESCRIPTION_FIELD_SIZE;
        for (Variable variable : variables) {
            // Locate the text subheader that has this variable's name.
            final String variableName = variable.name();

            // subheader index/offset/length of name.
            columnText.writeTextLocation(page, subheaderOffset + offsetWithinSubheader, variableName);

            // padding
            write2(page, subheaderOffset + offsetWithinSubheader + 6, (short) 0);

            offsetWithinSubheader += SIZE_OF_ENTRY;
        }
    }

    @Override
    long signature() {
        return SIGNATURE_COLUMN_NAME;
    }
}