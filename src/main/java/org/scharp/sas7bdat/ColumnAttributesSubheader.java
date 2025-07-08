package org.scharp.sas7bdat;

import java.util.List;

import static org.scharp.sas7bdat.WriteUtil.write2;
import static org.scharp.sas7bdat.WriteUtil.write4;
import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A subheader that contains some attributes for all columns
 */
class ColumnAttributesSubheader extends Subheader {
    private static final byte COLUMN_TYPE_NUMERIC = 1;
    private static final byte COLUMN_TYPE_CHARACTER = 2;

    /** The number of bytes of blank data at the end of a subheader */
    private static final int FOOTER_PADDING = 12;

    /** The number of bytes of each column entry in a subheader */
    private static final int SIZE_OF_ENTRY = 16;

    /** The byte offset of the data for the first column */
    private static final int OFFSET_OF_FIRST_ENTRY = 16;

    /**
     * The number of bytes required to have a subheader with a single variable.
     */
    final static int MIN_SIZE = OFFSET_OF_FIRST_ENTRY + SIZE_OF_ENTRY + FOOTER_PADDING;

    private final int totalVariablesInSubheader;
    private final List<Variable> variables;
    private final List<Integer> physicalOffsets;

    /**
     * Constructs a new column attributes subheader using a sublist of a given set of variables.
     *
     * @param variables
     *     All variables in the dataset.
     * @param offset
     *     The offset within {@code variables} of the first variable to include in the new subheader.
     * @param maxLength
     *     The maximum size, in bytes, of the new subheader.  This may limit the number of variables in the new
     *     subheader.
     */
    ColumnAttributesSubheader(Sas7bdatVariables variables, int offset, int maxLength) {
        // Determine how many variables, starting at offset, this subheader will hold.
        assert offset < variables.totalVariables() : "offset is larger than the number of variables";
        assert 0 < maxLength : "maxLength isn't positive: " + maxLength;
        assert MIN_SIZE <= maxLength : "maxLength is too small: " + maxLength;
        assert maxLength <= Short.MAX_VALUE : "maxLength is too large: " + maxLength;

        final int variablesRemaining = variables.totalVariables() - offset;
        final int variablesInMaxLength = (maxLength - (OFFSET_OF_FIRST_ENTRY + FOOTER_PADDING)) / SIZE_OF_ENTRY;
        totalVariablesInSubheader = Math.min(variablesRemaining, variablesInMaxLength);

        // Copy the variables and their physical offsets.
        int limit = offset + totalVariablesInSubheader;
        this.variables = variables.variables.subList(offset, limit);
        physicalOffsets = variables.physicalOffsets().subList(offset, limit);
    }

    /**
     * The number of bytes of data in this subheader without signature or FOOTER_PADDING.
     *
     * @return The number of bytes of data in this subheader
     */
    int sizeOfData() {
        return OFFSET_OF_FIRST_ENTRY + variables.size() * SIZE_OF_ENTRY - SIGNATURE_SIZE;
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
    int size() {
        return OFFSET_OF_FIRST_ENTRY + variables.size() * SIZE_OF_ENTRY + FOOTER_PADDING;
    }

    @Override
    void writeSubheader(byte[] page, int subheaderOffset) {
        write8(page, subheaderOffset, SIGNATURE_COLUMN_ATTRS); // signature

        int lengthInSubheader = sizeOfData();
        assert lengthInSubheader <= Short.MAX_VALUE;
        write8(page, subheaderOffset + 8, lengthInSubheader);

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

        // There is some padding at the end.
        write4(page, subheaderOffset + offsetWithinSubheader, 0);
        write8(page, subheaderOffset + offsetWithinSubheader + 4, 0);

        assert size() == offsetWithinSubheader + FOOTER_PADDING;
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