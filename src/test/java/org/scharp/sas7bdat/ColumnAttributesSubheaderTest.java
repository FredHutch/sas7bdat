package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;
import org.scharp.sas7bdat.Sas7bdatExporter.Sas7bdatVariables;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_UNCOMPRESSED;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_B;

/** Unit tests for {@link ColumnAttributesSubheader}. */
public class ColumnAttributesSubheaderTest {

    @Test
    void testTypeCode() {
        Variable variable = new Variable(
            "TEXT",
            VariableType.CHARACTER,
            20,
            "A label",
            Format.UNSPECIFIED,
            new Format("$", 10));
        Sas7bdatVariables variables = new Sas7bdatVariables(List.of(variable));

        ColumnAttributesSubheader subheader = new ColumnAttributesSubheader(variables, 0, Short.MAX_VALUE);
        assertEquals(SUBHEADER_TYPE_B, subheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        Variable variable = new Variable(
            "TEXT",
            VariableType.CHARACTER,
            20,
            "A label",
            Format.UNSPECIFIED,
            new Format("$", 10));
        Sas7bdatVariables variables = new Sas7bdatVariables(List.of(variable));

        ColumnAttributesSubheader subheader = new ColumnAttributesSubheader(variables, 0, Short.MAX_VALUE);
        assertEquals(COMPRESSION_UNCOMPRESSED, subheader.compressionCode());
    }

    @Test
    void testSingleVariable() {
        // A variable
        Variable variable = new Variable(
            "MY_VAR",
            VariableType.NUMERIC,
            8,
            "A label",
            new Format("$OUTPUT", 8, 2),
            new Format("$INPUT", 9, 6));
        Sas7bdatVariables variables = new Sas7bdatVariables(List.of(variable));

        ColumnAttributesSubheader subheader = new ColumnAttributesSubheader(variables, 0, Short.MAX_VALUE);
        assertEquals(1, subheader.totalVariablesInSubheader());

        // Write the contents of the subheader to a byte array.
        final byte[] expectedSubheaderData = new byte[] {
            -4, -1, -1, -1, -1, -1, -1, -1,  // signature
            24, 0, 0, 0, 0, 0, 0, 0, // size of data

            // variable 1
            0, 0, 0, 0, 0, 0, 0, 0, // offset
            8, 0, 0, 0, // width
            0, 4, // name flags
            1, 0, // column type (1=numeric, 2=character)

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        int size = subheader.size();
        assertEquals(expectedSubheaderData.length, subheader.size());
        assertEquals(expectedSubheaderData.length - 20, subheader.sizeOfData());

        // Get the subheader with a non-zero offset.
        int offset = 10;
        byte[] actualSubheaderData = new byte[size + offset];
        subheader.writeSubheader(actualSubheaderData, offset);

        // Determine the expected return value.
        byte[] expectedArray = new byte[expectedSubheaderData.length + offset];
        System.arraycopy(expectedSubheaderData, 0, expectedArray, offset, expectedSubheaderData.length);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedArray, actualSubheaderData);
    }

    @Test
    void testSublistOfVariables() {
        Sas7bdatVariables variables = new Sas7bdatVariables(List.of(
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
                "LONGTEXT2", // longer than 8 characters
                VariableType.CHARACTER,
                101,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED),
            new Variable(
                "NUMBER 1", // space in name
                VariableType.NUMERIC,
                8,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED),
            new Variable(
                "AFTER",
                VariableType.NUMERIC,
                8,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED)));

        // MIN_SIZE is for a subheader with one variable.  We add enough for 3 more (16*3) and subtract 1.
        short maxSizeForThreeVariables = ColumnAttributesSubheader.MIN_SIZE + 16 * 3 - 1;
        ColumnAttributesSubheader subheader = new ColumnAttributesSubheader(variables, 1, maxSizeForThreeVariables);
        assertEquals(3, subheader.totalVariablesInSubheader());

        final byte[] expectedSubheaderData = new byte[] {
            -4, -1, -1, -1, -1, -1, -1, -1,  // signature
            56, 0, 0, 0, 0, 0, 0, 0, // size of data

            // variable 2
            24, 0, 0, 0, 0, 0, 0, 0, // offset (after three numerics)
            0, 1, 0, 0, // width
            0, 4, // name flags
            2, 0, // column type (1=numeric, 2=character)

            // variable 3
            24, 1, 0, 0, 0, 0, 0, 0, // offset
            101, 0, 0, 0, // width
            0, 8, // name flags
            2, 0, // column type (1=numeric, 2=character)

            // variable 4
            8, 0, 0, 0, 0, 0, 0, 0, // offset (second numeric)
            8, 0, 0, 0, // width
            0, 12, // name flags
            1, 0, // column type (1=numeric, 2=character)

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        int size = subheader.size();
        assertEquals(expectedSubheaderData.length, subheader.size());
        assertEquals(expectedSubheaderData.length - 20, subheader.sizeOfData());

        // Confirm that writeSubheader() writes the expected data.
        byte[] actualSubheaderData = new byte[size];
        subheader.writeSubheader(actualSubheaderData, 0);
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }
}