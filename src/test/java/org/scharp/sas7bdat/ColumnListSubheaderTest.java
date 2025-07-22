package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_UNCOMPRESSED;
import static org.scharp.sas7bdat.Subheader.SIGNATURE_COLUMN_LIST;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_B;

/** Unit tests for {@link ColumnListSubheader}. */
public class ColumnListSubheaderTest {

    @Test
    void testSignature() {
        List<Variable> variablesList = List.of(
            Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(20).build());
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variablesList);

        ColumnListSubheader columnListSubheader = new ColumnListSubheader(variablesLayout, 0);
        assertEquals(SIGNATURE_COLUMN_LIST, columnListSubheader.signature());
    }

    @Test
    void testTypeCode() {
        List<Variable> variablesList = List.of(
            Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(20).build());
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variablesList);

        ColumnListSubheader columnListSubheader = new ColumnListSubheader(variablesLayout, 0);
        assertEquals(SUBHEADER_TYPE_B, columnListSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        List<Variable> variablesList = List.of(
            Variable.builder().name("TEXT").type(VariableType.CHARACTER).length(20).build());
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variablesList);

        ColumnListSubheader columnListSubheader = new ColumnListSubheader(variablesLayout, 0);
        assertEquals(COMPRESSION_UNCOMPRESSED, columnListSubheader.compressionCode());
    }

    @Test
    void testSingleVariable() {
        List<Variable> variablesList = List.of(
            Variable.builder().
                name("MY_VAR").
                type(VariableType.NUMERIC).
                length(8).
                label("A label").
                outputFormat(new Format("OUTPUT", 8, 2)).
                inputFormat(new Format("INPUT", 9, 6)).
                build());
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variablesList);

        ColumnListSubheader columnListSubheader = new ColumnListSubheader(variablesLayout, 0);
        assertEquals(1, columnListSubheader.totalVariablesInSubheader());

        final byte[] expectedSubheaderData = new byte[] {
            -2, -1, -1, -1, -1, -1, -1, -1,  // signature

            32, 0,  // size of data
            -56, 127, // unknown
            0, 0, 0, 0, // padding

            24, 0, 0, 0, 0, 0, 0, 0, // length remaining in subheader?

            1, 0, // total variables
            1, 0, // length of list
            1, 0, // unknown
            1, 0, // unknown

            0, 0, // unknown
            0, 0, // unknown
            0, 0, // unknown

            1, 0, // variable 1

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        int size = columnListSubheader.size();
        assertEquals(expectedSubheaderData.length, columnListSubheader.size());

        // Get the subheader with a non-zero offset.
        int offset = 10;
        byte[] actualSubheaderData = new byte[size + offset];
        columnListSubheader.writeSubheader(actualSubheaderData, offset);

        // Determine the expected return value.
        byte[] expectedArray = new byte[expectedSubheaderData.length + offset];
        System.arraycopy(expectedSubheaderData, 0, expectedArray, offset, expectedSubheaderData.length);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedArray, actualSubheaderData);
    }

    @Test
    void testSublistOfVariables() {
        List<Variable> variablesList = List.of(
            Variable.builder().name("BEFORE").type(VariableType.NUMERIC).length(8).label("A label").build(),
            Variable.builder().name("TEXT1").type(VariableType.CHARACTER).length(256).label("label").build(),
            Variable.builder().name("LONGTEXT2").type(VariableType.CHARACTER).length(101).label("label").build(),
            Variable.builder().name("NUMBER 1").type(VariableType.NUMERIC).length(8).label("label").build());

        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variablesList);

        ColumnListSubheader columnListSubheader = new ColumnListSubheader(variablesLayout, 1);
        assertEquals(3, columnListSubheader.totalVariablesInSubheader());

        // Write the contents of the subheader to a byte array.
        final byte[] expectedSubheaderData = new byte[] {
            -2, -1, -1, -1, -1, -1, -1, -1,  // signature

            36, 0,  // size of data
            -56, 127, // unknown
            0, 0, 0, 0, // padding

            28, 0, 0, 0, 0, 0, 0, 0, // length remaining in subheader?

            3, 0, // total variables
            3, 0, // length of list
            1, 0, // unknown
            3, 0, // unknown

            0, 0, // unknown
            0, 0, // unknown
            0, 0, // unknown

            1, 0, // variable 2
            2, 0, // variable 3
            3, 0, // variable 4

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        int size = columnListSubheader.size();
        assertEquals(expectedSubheaderData.length, columnListSubheader.size());

        // Confirm that writeSubheader() writes the expected data.
        byte[] actualSubheaderData = new byte[size];
        columnListSubheader.writeSubheader(actualSubheaderData, 0);
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }

    @Test
    void testMaximumNumberOfVariables() {
        // Create a list with the most number of variables that a SAS7BDAT can have.
        List<Variable> variablesList = new ArrayList<>(Short.MAX_VALUE);
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).label("label is ignored");
        for (int variableNumber = 1; variableNumber <= Short.MAX_VALUE; variableNumber++) {
            Variable variable = builder.name("VARIABLE_" + variableNumber).build();
            variablesList.add(variable);
        }

        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variablesList);
        ColumnListSubheader columnListSubheader = new ColumnListSubheader(variablesLayout, 0);
        assertEquals(16345, columnListSubheader.totalVariablesInSubheader());

        int size = columnListSubheader.size();
        assertEquals(32740, columnListSubheader.size());

        // Confirm that writeSubheader() can write the data.
        // The data is too long to check.
        byte[] actualSubheaderData = new byte[size];
        columnListSubheader.writeSubheader(actualSubheaderData, 0);
    }
}