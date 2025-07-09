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
        Variable variable = Variable.builder().name("MY_VAR").type(VariableType.CHARACTER).length(20).build();
        ColumnSizeSubheader columnSizeSubheader = new ColumnSizeSubheader(List.of(variable));
        assertEquals(SUBHEADER_TYPE_A, columnSizeSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        Variable variable = Variable.builder().name("MY_VAR").type(VariableType.CHARACTER).length(20).build();
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
            Variable.builder().
                name("VAR1").
                type(VariableType.CHARACTER).
                length(20).
                label("A label").
                outputFormat(new Format("$", 10)).
                build(),

            Variable.builder().
                name("VAR2").
                type(VariableType.NUMERIC).
                length(8).
                label("A number").
                inputFormat(new Format("", 10)).
                build(),

            Variable.builder().
                name("VAR3").
                type(VariableType.CHARACTER).
                length(20).
                label("A label").
                inputFormat(new Format("$", 10)).
                build());

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
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).label("label is ignored");
        for (int variableNumber = 1; variableNumber <= Short.MAX_VALUE; variableNumber++) {
            Variable variable = builder.name("VARIABLE_" + variableNumber).build();
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