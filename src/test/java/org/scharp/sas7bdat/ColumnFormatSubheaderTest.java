///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_UNCOMPRESSED;
import static org.scharp.sas7bdat.Subheader.SIGNATURE_COLUMN_FORMAT;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_A;

/** Unit tests for {@link ColumnFormatSubheader}. */
public class ColumnFormatSubheaderTest {

    private static ColumnText newColumnText(Variable variable) {
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of(variable));
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        ColumnText columnText = new ColumnText(pageLayout);

        // Populate the column text with the strings from the variable.
        columnText.add(variable.inputFormat().name());
        columnText.add(variable.outputFormat().name());
        columnText.add(variable.label());

        return columnText;
    }

    @Test
    void testSignature() {
        Variable variable = Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(20).build();
        ColumnText columnText = newColumnText(variable);

        ColumnFormatSubheader columnFormatSubheader = new ColumnFormatSubheader(variable, columnText);
        assertEquals(SIGNATURE_COLUMN_FORMAT, columnFormatSubheader.signature());
    }

    @Test
    void testTypeCode() {
        Variable variable = Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(20).build();
        ColumnText columnText = newColumnText(variable);

        ColumnFormatSubheader columnFormatSubheader = new ColumnFormatSubheader(variable, columnText);
        assertEquals(SUBHEADER_TYPE_A, columnFormatSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        Variable variable = Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(20).build();
        ColumnText columnText = newColumnText(variable);

        ColumnFormatSubheader columnFormatSubheader = new ColumnFormatSubheader(variable, columnText);
        assertEquals(COMPRESSION_UNCOMPRESSED, columnFormatSubheader.compressionCode());
    }

    @Test
    void testVariableWithAllFields() {
        // A variable with all three strings, format width, and format digits.
        Variable variable = Variable.builder().
            name("MY_VAR").
            type(VariableType.CHARACTER).
            length(8).
            label("A label").
            outputFormat(new Format("$OUTPUT", 8, 2)).
            inputFormat(new Format("$INPUT", 9, 6)).
            build();

        ColumnText columnText = newColumnText(variable);

        ColumnFormatSubheader columnFormatSubheader = new ColumnFormatSubheader(variable, columnText);

        // Write the contents of the subheader to a byte array.
        final byte[] expectedSubheaderData = new byte[] {
            -2, -5, -1, -1, -1, -1, -1, -1,  // signature
            0, 0, 0, 0, 0, 0, 0, 0, // padding
            0, 0, 0, 0, 0, 0, 0, 0, // padding

            8, 0, // output format width
            2, 0, // output format digits

            9, 0, // input format width
            6, 0, // input format digits

            0, 0, 0, 0, 0, 0, 0, 0, // padding

            0, 0, // input format name page
            8, 0, // input format name offset
            6, 0, // input format name size

            0, 0, // output format name page
            16, 0, // output format name offset
            7, 0, // output format name size

            0, 0, // label page
            24, 0, // label offset
            7, 0, // label size

            0, 0, 0, 0, 0, 0 // padding
        };

        int size = columnFormatSubheader.size();
        assertEquals(expectedSubheaderData.length, columnFormatSubheader.size());

        // Get the subheader with a non-zero offset.
        int offset = 10;
        byte[] actualSubheaderData = new byte[size + offset];
        columnFormatSubheader.writeSubheader(actualSubheaderData, offset);

        // Determine the expected return value.
        byte[] expectedArray = new byte[expectedSubheaderData.length + offset];
        System.arraycopy(expectedSubheaderData, 0, expectedArray, offset, expectedSubheaderData.length);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedArray, actualSubheaderData);
    }

    @Test
    void testVariableWithNoFields() {
        // No label, input format, or output format
        Variable variable = Variable.builder().name("MY_VAR").type(VariableType.CHARACTER).length(20).build();

        ColumnText columnText = newColumnText(variable);

        ColumnFormatSubheader columnFormatSubheader = new ColumnFormatSubheader(variable, columnText);

        final byte[] expectedSubheaderData = new byte[] {
            -2, -5, -1, -1, -1, -1, -1, -1,  // signature
            0, 0, 0, 0, 0, 0, 0, 0, // padding
            0, 0, 0, 0, 0, 0, 0, 0, // padding

            0, 0, // output format width
            0, 0, // output format digits

            0, 0, // input format width
            0, 0, // input format digits

            0, 0, 0, 0, 0, 0, 0, 0, // padding

            0, 0, // input format name page
            0, 0, // input format name offset
            0, 0, // input format name size

            0, 0, // output format name page
            0, 0, // output format name offset
            0, 0, // output format name size

            0, 0, // label page
            0, 0, // label offset
            0, 0, // label size

            0, 0, 0, 0, 0, 0 // padding
        };

        // Write the contents of the subheader to a byte array.
        assertEquals(expectedSubheaderData.length, columnFormatSubheader.size());
        byte[] actualSubheaderData = new byte[expectedSubheaderData.length];
        columnFormatSubheader.writeSubheader(actualSubheaderData, 0);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }
}