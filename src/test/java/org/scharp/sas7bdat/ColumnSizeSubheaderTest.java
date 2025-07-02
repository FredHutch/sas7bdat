package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_UNCOMPRESSED;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_A;

/** Unit tests for {@link ColumnSizeSubheader}. */
public class ColumnSizeSubheaderTest {

    @Test
    void testTypeCode() {
        Variable variable = new Variable(
            "MY_VAR",
            1,
            VariableType.CHARACTER,
            20,
            "A label",
            Format.UNSPECIFIED,
            new Format("$", 10),
            StrictnessMode.SAS_ANY);

        ColumnSizeSubheader columnSizeSubheader = new ColumnSizeSubheader(List.of(variable));
        assertEquals(SUBHEADER_TYPE_A, columnSizeSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        Variable variable = new Variable(
            "MY_VAR",
            1,
            VariableType.CHARACTER,
            20,
            "A label",
            Format.UNSPECIFIED,
            new Format("$", 10),
            StrictnessMode.SAS_ANY);

        ColumnSizeSubheader columnSizeSubheader = new ColumnSizeSubheader(List.of(variable));
        assertEquals(COMPRESSION_UNCOMPRESSED, columnSizeSubheader.compressionCode());
    }

    @Test
    void testNoColumns() {
        // It could be that this should be an error.
        ColumnSizeSubheader columnSizeSubheader = new ColumnSizeSubheader(List.of());

        // Write the contents of the subheader to a byte array.
        final byte[] expectedSubheaderData = new byte[] {
            -10, -10, -10, -10, 0, 0, 0, 0,  // signature
            0, 0, 0, 0, 0, 0, 0, 0, // number of columns
            0, 0, 0, 0, 0, 0, 0, 0, // padding
        };

        int size = columnSizeSubheader.size();
        assertEquals(expectedSubheaderData.length, columnSizeSubheader.size());

        // Get the subheader with a non-zero offset.
        int offset = 10;
        byte[] actualSubheaderData = new byte[size + offset];
        columnSizeSubheader.writeSubheader(actualSubheaderData, offset);

        // Determine the expected return value.
        byte[] expectedArray = new byte[expectedSubheaderData.length + offset];
        System.arraycopy(expectedSubheaderData, 0, expectedArray, offset, expectedSubheaderData.length);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedArray, actualSubheaderData);
    }

    @Test
    void testManyColumns() {
        List<Variable> variables = List.of(
            new Variable(
                "VAR1",
                1, // variable number
                VariableType.CHARACTER,
                20,
                "A label",
                Format.UNSPECIFIED,
                new Format("$", 10),
                StrictnessMode.SAS_ANY),

            new Variable(
                "VAR2",
                2, // variable number
                VariableType.NUMERIC,
                8,
                "A number",
                Format.UNSPECIFIED,
                new Format("", 10),
                StrictnessMode.SAS_ANY),

            new Variable(
                "VAR3",
                2, // variable number
                VariableType.CHARACTER,
                20,
                "A label",
                Format.UNSPECIFIED,
                new Format("$", 10),
                StrictnessMode.SAS_ANY));

        ColumnSizeSubheader columnSizeSubheader = new ColumnSizeSubheader(variables);

        final byte[] expectedSubheaderData = new byte[] {
            -10, -10, -10, -10, 0, 0, 0, 0,  // signature
            3, 0, 0, 0, 0, 0, 0, 0, // number of columns
            0, 0, 0, 0, 0, 0, 0, 0, // padding
        };

        // Write the contents of the subheader to a byte array.
        assertEquals(expectedSubheaderData.length, columnSizeSubheader.size());
        byte[] actualSubheaderData = new byte[expectedSubheaderData.length];
        columnSizeSubheader.writeSubheader(actualSubheaderData, 0);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }

    @Test
    void testMaximumNumberOfVariables() {
        // Create a list with the most number of variables that a SAS7BDAT can have.
        List<Variable> variables = new ArrayList<>(Short.MAX_VALUE);
        for (int variableNumber = 1; variableNumber <= Short.MAX_VALUE; variableNumber++) {
            Variable variable = new Variable(
                "VARIABLE_" + variableNumber,
                variableNumber, // variable number
                VariableType.NUMERIC,
                8,
                "label is ignored",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED,
                StrictnessMode.SAS_ANY);
            variables.add(variable);
        }

        final byte[] expectedSubheaderData = new byte[] {
            -10, -10, -10, -10, 0, 0, 0, 0,  // signature
            -1, 127, 0, 0, 0, 0, 0, 0, // number of columns
            0, 0, 0, 0, 0, 0, 0, 0, // padding
        };

        ColumnSizeSubheader columnSizeSubheader = new ColumnSizeSubheader(variables);
        assertEquals(expectedSubheaderData.length, columnSizeSubheader.size());

        // Confirm that writeSubheader() can write the data.
        byte[] actualSubheaderData = new byte[expectedSubheaderData.length];
        columnSizeSubheader.writeSubheader(actualSubheaderData, 0);
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }
}