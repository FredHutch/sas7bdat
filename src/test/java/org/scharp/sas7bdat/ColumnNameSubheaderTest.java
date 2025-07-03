package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;
import org.scharp.sas7bdat.Sas7bdatExporter.Sas7bdatPageLayout;
import org.scharp.sas7bdat.Sas7bdatExporter.Sas7bdatVariables;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_UNCOMPRESSED;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_B;

/** Unit tests for {@link ColumnNameSubheader}. */
public class ColumnNameSubheaderTest {

    private static ColumnText newColumnText(List<Variable> variableList) {
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariables variables = new Sas7bdatVariables(variableList);
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, 0x10000, variables);
        ColumnText columnText = new ColumnText(pageLayout);

        // Populate the column text with the strings from the variableList.
        for (Variable variable : variableList) {
            columnText.add(variable.name());
        }

        return columnText;
    }

    @Test
    void testTypeCode() {
        List<Variable> variables = List.of(new Variable(
            "TEXT",
            VariableType.CHARACTER,
            20,
            "A label",
            Format.UNSPECIFIED,
            new Format("$", 10)));
        ColumnText columnText = newColumnText(variables);

        ColumnNameSubheader columnNameSubheader = new ColumnNameSubheader(variables, 0, columnText);
        assertEquals(SUBHEADER_TYPE_B, columnNameSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        List<Variable> variables = List.of(new Variable(
            "TEXT",
            VariableType.CHARACTER,
            20,
            "A label",
            Format.UNSPECIFIED,
            new Format("$", 10)));
        ColumnText columnText = newColumnText(variables);

        ColumnNameSubheader columnNameSubheader = new ColumnNameSubheader(variables, 0, columnText);
        assertEquals(COMPRESSION_UNCOMPRESSED, columnNameSubheader.compressionCode());
    }

    @Test
    void testSingleVariable() {
        List<Variable> variables = List.of(new Variable(
            "MY_VAR",
            VariableType.NUMERIC,
            8,
            "A label",
            new Format("$OUTPUT", 8, 2),
            new Format("$INPUT", 9, 6)));

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
            new Variable(
                "BEFORE",
                VariableType.NUMERIC,
                8,
                "A label",
                new Format("$OUTPUT", 8, 2),
                new Format("$INPUT", 9, 6)),
            new Variable(
                "TEXT1",
                VariableType.CHARACTER,
                256,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED),
            new Variable(
                "LONGTEXT2", //
                VariableType.CHARACTER,
                101,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED),
            new Variable(
                "NUMBER 1", //
                VariableType.NUMERIC,
                8,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED));

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
        for (int variableNumber = 1; variableNumber <= Short.MAX_VALUE; variableNumber++) {
            Variable variable = new Variable(
                "VARIABLE_" + variableNumber,
                VariableType.NUMERIC,
                8,
                "label is ignored",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED);
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