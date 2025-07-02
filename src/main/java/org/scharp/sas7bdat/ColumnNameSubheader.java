package org.scharp.sas7bdat;

import java.util.List;

import static org.scharp.sas7bdat.WriteUtil.write2;
import static org.scharp.sas7bdat.WriteUtil.write4;
import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A subheader that contains the names of columns
 */
class ColumnNameSubheader extends Subheader {

    /**
     * No subheader can be larger than Short.MAX_VALUE, so we only have room for (Short.MAX_VALUE - 28) / 8 = 4092
     * variables in each one.  However, datasets which SAS creates puts a maximum of 4089 in each subheader.
     */
    private static final int MAX_VARIABLES_PER_SUBHEADER = 4089;

    final List<Variable> variables;
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

    @Override
    int size() {
        return variables.size() * 8 + 28;
    }

    @Override
    void writeSubheader(byte[] page, int subheaderOffset) {
        write8(page, subheaderOffset, SIGNATURE_COLUMN_NAME); // signature

        write8(page, subheaderOffset + 8, (short) (size() - 20)); // remaining bytes in subheader

        int offsetWithinSubheader = 16;
        for (Variable variable : variables) {
            // Locate the text subheader that has this variable's name.
            final String variableName = variable.name();

            // subheader index/offset/length of name.
            columnText.writeTextLocation(page, subheaderOffset + offsetWithinSubheader, variableName);

            // padding
            write2(page, subheaderOffset + offsetWithinSubheader + 6, (short) 0);

            offsetWithinSubheader += 8;
        }

        // There is some padding at the end.
        assert size() == offsetWithinSubheader + 12;
        write4(page, subheaderOffset + offsetWithinSubheader, 0);
        write8(page, subheaderOffset + offsetWithinSubheader + 4, 0);
    }

    @Override
    byte typeCode() {
        return SUBHEADER_TYPE_B;
    }

    @Override
    byte compressionCode() {
        return COMPRESSION_UNCOMPRESSED;
    }
}