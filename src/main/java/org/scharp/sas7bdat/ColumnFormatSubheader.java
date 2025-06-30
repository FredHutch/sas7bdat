package org.scharp.sas7bdat;

import org.scharp.sas7bdat.Sas7bdatWriter.ColumnText;

import static org.scharp.sas7bdat.WriteUtil.write2;
import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A column format subheader contains references to the column's format and label. There is one of these per column in
 * the dataset.
 */
class ColumnFormatSubheader extends Subheader {
    private final Variable variable;
    private final ColumnText columnText;

    ColumnFormatSubheader(Variable variable, ColumnText columnText) {
        this.variable = variable;
        this.columnText = columnText;
    }

    @Override
    int size() {
        return 64;
    }

    @Override
    void writeSubheader(byte[] page, int subheaderOffset) {
        write8(page, subheaderOffset, SIGNATURE_COLUMN_FORMAT); // signature
        write8(page, subheaderOffset + 8, 0); // unknown, maybe padding
        write8(page, subheaderOffset + 16, 0); // unknown, maybe padding

        write2(page, subheaderOffset + 24, (short) variable.outputFormat().width());
        write2(page, subheaderOffset + 26, (short) variable.outputFormat().numberOfDigits());
        write2(page, subheaderOffset + 28, (short) variable.inputFormat().width());
        write2(page, subheaderOffset + 30, (short) variable.inputFormat().numberOfDigits());

        write8(page, subheaderOffset + 32, 0); // unknown, maybe padding

        // Write the variable's input format offset/length.
        columnText.writeTextLocation(page, subheaderOffset + 40, variable.inputFormat().name());

        // Write the variable's output format offset/length.
        columnText.writeTextLocation(page, subheaderOffset + 46, variable.outputFormat().name());

        // Write the variable's label offset/length.
        columnText.writeTextLocation(page, subheaderOffset + 52, variable.label());

        write2(page, subheaderOffset + 58, (short) 0); // unknown
        write2(page, subheaderOffset + 60, (short) 0); // unknown
        write2(page, subheaderOffset + 62, (short) 0); // unknown
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