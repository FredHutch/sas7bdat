package org.scharp.sas7bdat;

import java.util.List;

import static org.scharp.sas7bdat.WriteUtil.write2;
import static org.scharp.sas7bdat.WriteUtil.write4;
import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A subheader that contains some attributes for all columns
 */
class ColumnAttributesSubheader extends VariableSizeSubheader {
    private static final byte COLUMN_TYPE_NUMERIC = 1;
    private static final byte COLUMN_TYPE_CHARACTER = 2;

    /** The number of bytes of each column entry in a subheader */
    private static final int SIZE_OF_ENTRY = 16;

    /** The byte offset of the data for the first column */
    private static final int OFFSET_OF_FIRST_ENTRY = SIGNATURE_SIZE + PAYLOAD_DESCRIPTION_FIELD_SIZE;

    /**
     * The number of bytes required to have a subheader with a single variable.
     */
    final static int MIN_SIZE = VARIABLE_SUBHEADER_OVERHEAD + SIZE_OF_ENTRY;

    private final int totalVariablesInSubheader;
    private final List<Variable> variables;
    private final List<Integer> physicalOffsets;

    /**
     * Constructs a new column attributes subheader using a sublist of a given set of variables.
     *
     * @param variablesLayout
     *     All variables in the dataset.
     * @param offset
     *     The offset within {@code variables} of the first variable to include in the new subheader.
     * @param maxLength
     *     The maximum size, in bytes, of the new subheader.  This may limit the number of variables in the new
     *     subheader.
     */
    ColumnAttributesSubheader(Sas7bdatVariablesLayout variablesLayout, int offset, int maxLength) {
        // Determine how many variables, starting at offset, this subheader will hold.
        assert offset < variablesLayout.totalVariables() : "offset is larger than the number of variables";
        assert 0 < maxLength : "maxLength isn't positive: " + maxLength;
        assert MIN_SIZE <= maxLength : "maxLength is too small: " + maxLength;
        assert maxLength <= Short.MAX_VALUE : "maxLength is too large: " + maxLength;

        final int variablesRemaining = variablesLayout.totalVariables() - offset;
        final int variablesInMaxLength = (maxLength - VARIABLE_SUBHEADER_OVERHEAD) / SIZE_OF_ENTRY;
        totalVariablesInSubheader = Math.min(variablesRemaining, variablesInMaxLength);

        // Copy the variables and their physical offsets.
        int limit = offset + totalVariablesInSubheader;
        this.variables = variablesLayout.variables().subList(offset, limit);
        physicalOffsets = variablesLayout.physicalOffsets().subList(offset, limit);
    }

    /**
     * The number of bytes of data in this subheader without signature or FOOTER_PADDING.
     *
     * @return The number of bytes of data in this subheader
     */
    int sizeOfData() {
        return variables.size() * SIZE_OF_ENTRY + PAYLOAD_DESCRIPTION_FIELD_SIZE;
    }

    /**
     * Gets the number of variables that fit into this subheader.
     *
     * @return The number of variables
     */
    int totalVariablesInSubheader() {
        return totalVariablesInSubheader;
    }

    @Override
    void writeVariableSizedPayload(byte[] page, int subheaderOffset) {

        int offsetWithinSubheader = OFFSET_OF_FIRST_ENTRY;
        int i = 0;
        for (Variable variable : variables) {
            // offset of variable in bytes when in data row
            write8(page, subheaderOffset + offsetWithinSubheader, physicalOffsets.get(i));

            // column width
            write4(page, subheaderOffset + offsetWithinSubheader + 8, variable.length());

            // name flag
            short nameFlag;
            if (!variable.name().matches("[A-Za-z]\\w*")) {
                nameFlag = 0x0C00; // not a simple name; must be quoted as a name literal
            } else if (variable.name().length() <= 8) {
                nameFlag = 0x0400;
            } else {
                nameFlag = 0x0800;
            }
            write2(page, subheaderOffset + offsetWithinSubheader + 12, nameFlag);

            // column type
            write2(page, subheaderOffset + offsetWithinSubheader + 14,
                variable.type() == VariableType.NUMERIC ? COLUMN_TYPE_NUMERIC : COLUMN_TYPE_CHARACTER);

            offsetWithinSubheader += SIZE_OF_ENTRY;
            i++;
        }
    }

    @Override
    long signature() {
        return SIGNATURE_COLUMN_ATTRS;
    }
}