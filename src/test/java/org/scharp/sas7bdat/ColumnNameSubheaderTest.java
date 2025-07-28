///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_UNCOMPRESSED;
import static org.scharp.sas7bdat.Subheader.SIGNATURE_COLUMN_NAME;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_B;

/** Unit tests for {@link ColumnNameSubheader}. */
public class ColumnNameSubheaderTest {

    private static ColumnText newColumnText(List<Variable> variableList) {
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variableList);
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        ColumnText columnText = new ColumnText(pageLayout);

        // Populate the column text with the strings from the variableList.
        for (Variable variable : variableList) {
            columnText.add(variable.name());
        }

        return columnText;
    }

    @Test
    void testSignature() {
        List<Variable> variables = List.of(
            Variable.builder().
                name("TEXT").
                type(VariableType.CHARACTER).
                length(20).
                label("A label").
                inputFormat(new Format("$", 10)).build());
        ColumnText columnText = newColumnText(variables);

        ColumnNameSubheader columnNameSubheader = new ColumnNameSubheader(variables, 0, columnText);
        assertEquals(SIGNATURE_COLUMN_NAME, columnNameSubheader.signature());
    }

    @Test
    void testTypeCode() {
        List<Variable> variables = List.of(
            Variable.builder().
                name("TEXT").
                type(VariableType.CHARACTER).
                length(20).
                label("A label").
                inputFormat(new Format("$", 10)).build());
        ColumnText columnText = newColumnText(variables);

        ColumnNameSubheader columnNameSubheader = new ColumnNameSubheader(variables, 0, columnText);
        assertEquals(SUBHEADER_TYPE_B, columnNameSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        List<Variable> variables = List.of(
            Variable.builder().
                name("TEXT").
                type(VariableType.CHARACTER).
                length(20).
                label("A label").
                inputFormat(new Format("$", 10)).build());
        ColumnText columnText = newColumnText(variables);

        ColumnNameSubheader columnNameSubheader = new ColumnNameSubheader(variables, 0, columnText);
        assertEquals(COMPRESSION_UNCOMPRESSED, columnNameSubheader.compressionCode());
    }

    @Test
    void testSingleVariable() {
        List<Variable> variables = List.of(
            Variable.builder().
                name("MY_VAR").
                type(VariableType.NUMERIC).
                length(8).
                label("A label").
                outputFormat(new Format("OUTPUT", 8, 2)).
                inputFormat(new Format("INPUT", 9, 6)).build());

        ColumnText columnText = newColumnText(variables);

        ColumnNameSubheader columnNameSubheader = new ColumnNameSubheader(variables, 0, columnText);
        assertEquals(1, columnNameSubheader.totalVariablesInSubheader());

        final byte[] expectedSubheaderData = new byte[] {
            -1, -1, -1, -1, -1, -1, -1, -1,  // signature
            16, 0, 0, 0, 0, 0, 0, 0, // size of data

            0, 0, // column text subheader index of name
            8, 0, // offset of name in subheader
            6, 0, // name length
            0, 0, // padding

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        int size = columnNameSubheader.size();
        assertEquals(expectedSubheaderData.length, columnNameSubheader.size());

        // Get the subheader with a non-zero offset.
        int offset = 10;
        byte[] actualSubheaderData = new byte[size + offset];
        columnNameSubheader.writeSubheader(actualSubheaderData, offset);

        // Determine the expected return value.
        byte[] expectedArray = new byte[expectedSubheaderData.length + offset];
        System.arraycopy(expectedSubheaderData, 0, expectedArray, offset, expectedSubheaderData.length);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedArray, actualSubheaderData);
    }

    @Test
    void testSublistOfVariables() {
        List<Variable> variables = List.of(
            Variable.builder().name("BEFORE").type(VariableType.NUMERIC).length(8).label("A label").build(),
            Variable.builder().name("TEXT1").type(VariableType.CHARACTER).length(256).label("label").build(),
            Variable.builder().name("LONGTEXT2").type(VariableType.CHARACTER).length(101).label("label").build(),
            Variable.builder().name("NUMBER 1").type(VariableType.NUMERIC).length(8).label("label").build());

        ColumnText columnText = newColumnText(variables);

        ColumnNameSubheader columnNameSubheader = new ColumnNameSubheader(variables, 1, columnText);
        assertEquals(3, columnNameSubheader.totalVariablesInSubheader());

        // Write the contents of the subheader to a byte array.
        final byte[] expectedSubheaderData = new byte[] {
            -1, -1, -1, -1, -1, -1, -1, -1,  // signature
            32, 0, 0, 0, 0, 0, 0, 0, // size of data

            // variable 2 is named "TEXT1"
            0, 0, // column text subheader index of name
            16, 0, // offset of name in subheader
            5, 0, // name length
            0, 0, // padding

            // variable 3 is named "LONGTEXT2"
            0, 0, // column text subheader index of name
            24, 0, // offset of name in subheader
            9, 0, // name length
            0, 0, // padding

            // variable 4 is named "NUMBER 1"
            0, 0, // column text subheader index of name
            36, 0, // offset of name in subheader
            8, 0, // name length
            0, 0, // padding

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        int size = columnNameSubheader.size();
        assertEquals(expectedSubheaderData.length, columnNameSubheader.size());

        // Confirm that writeSubheader() writes the expected data.
        byte[] actualSubheaderData = new byte[size];
        columnNameSubheader.writeSubheader(actualSubheaderData, 0);
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }

    @Test
    void testMaximumNumberOfVariables() {
        // Create a list with the most number of variables that a SAS7BDAT can have.
        List<Variable> variables = new ArrayList<>(Short.MAX_VALUE);
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).label("label is ignored");
        for (int variableNumber = 1; variableNumber <= Short.MAX_VALUE; variableNumber++) {
            Variable variable = builder.name("VARIABLE_" + variableNumber).build();
            variables.add(variable);
        }

        ColumnText columnText = newColumnText(variables);
        ColumnNameSubheader columnNameSubheader = new ColumnNameSubheader(variables, 0, columnText);
        assertEquals(4089, columnNameSubheader.totalVariablesInSubheader());

        int size = columnNameSubheader.size();
        assertEquals(32740, columnNameSubheader.size());

        // Confirm that writeSubheader() can write the data.
        // The data is too long to check.
        byte[] actualSubheaderData = new byte[size];
        columnNameSubheader.writeSubheader(actualSubheaderData, 0);
    }
}