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
import static org.scharp.sas7bdat.Subheader.SIGNATURE_COLUMN_LIST;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_B;

/** Unit tests for {@link ColumnHashTableSubheader}. */
public class ColumnHashTableSubheaderTest {

    @Test
    void testMinSizeForFirstBucketIndex() {
        assertEquals(60, ColumnHashTableSubheader.minSizeForFirstBucketIndex(0));
        assertEquals(30, ColumnHashTableSubheader.minSizeForFirstBucketIndex(1));
        assertEquals(30, ColumnHashTableSubheader.minSizeForFirstBucketIndex(2));
    }

    @Test
    void testSignature() {
        List<Variable> variablesList = List.of(
            Variable.builder().name("VAR1").type(VariableType.CHARACTER).length(20).build(),
            Variable.builder().name("VAR2").type(VariableType.CHARACTER).length(20).build());
        ColumnHashTable columnHashTable = new ColumnHashTable(variablesList);

        ColumnHashTableSubheader columnHashTableSubheader = new ColumnHashTableSubheader(columnHashTable, 100, 0);
        assertEquals(SIGNATURE_COLUMN_LIST, columnHashTableSubheader.signature());
    }

    @Test
    void testTypeCode() {
        List<Variable> variablesList = List.of(
            Variable.builder().name("VAR1").type(VariableType.CHARACTER).length(20).build(),
            Variable.builder().name("VAR2").type(VariableType.CHARACTER).length(20).build());
        ColumnHashTable columnHashTable = new ColumnHashTable(variablesList);

        ColumnHashTableSubheader columnHashTableSubheader = new ColumnHashTableSubheader(columnHashTable, 100, 0);
        assertEquals(SUBHEADER_TYPE_B, columnHashTableSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        List<Variable> variablesList = List.of(
            Variable.builder().name("VAR1").type(VariableType.CHARACTER).length(20).build(),
            Variable.builder().name("VAR2").type(VariableType.CHARACTER).length(20).build());
        ColumnHashTable columnHashTable = new ColumnHashTable(variablesList);

        ColumnHashTableSubheader columnHashTableSubheader = new ColumnHashTableSubheader(columnHashTable, 100, 0);
        assertEquals(COMPRESSION_UNCOMPRESSED, columnHashTableSubheader.compressionCode());
    }

    @Test
    void testSingleVariable() {
        List<Variable> variablesList = List.of(
            Variable.builder().name("A").type(VariableType.NUMERIC).length(8).build(),
            Variable.builder().name("B").type(VariableType.CHARACTER).length(2).build());
        ColumnHashTable columnHashTable = new ColumnHashTable(variablesList);

        ColumnHashTableSubheader columnHashTableSubheader = new ColumnHashTableSubheader(columnHashTable, 10000, 0);
        assertEquals(2, columnHashTableSubheader.totalBucketsInSubheader());

        final byte[] expectedSubheaderData = new byte[] {
            -2, -1, -1, -1, -1, -1, -1, -1,  // signature

            34, 0,  // size of data
            -56, 127, // unknown
            0, 0, 0, 0, // padding

            26, 0, 0, 0, 0, 0, 0, 0, // length remaining in subheader?

            2, 0, // total variables
            2, 0, // length of hash table
            1, 0, // unknown
            2, 0, // unknown

            0, 0, // unknown
            0, 0, // unknown
            0, 0, // unknown

            2, 0, // bucket #1 (variable 2)
            1, 0, // bucket #2 (variable 1)

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        int size = columnHashTableSubheader.size();
        assertEquals(expectedSubheaderData.length, columnHashTableSubheader.size());

        // Get the subheader with a non-zero offset.
        int offset = 10;
        byte[] actualSubheaderData = new byte[size + offset];
        columnHashTableSubheader.writeSubheader(actualSubheaderData, offset);

        // Determine the expected return value.
        byte[] expectedArray = new byte[expectedSubheaderData.length + offset];
        System.arraycopy(expectedSubheaderData, 0, expectedArray, offset, expectedSubheaderData.length);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedArray, actualSubheaderData);
    }

    @Test
    void testLastSubheader() {
        List<Variable> variablesList = List.of(
            Variable.builder().name("BEFORE").type(VariableType.NUMERIC).length(8).label("A label").build(),
            Variable.builder().name("TEXT1").type(VariableType.CHARACTER).length(256).label("label").build(),
            Variable.builder().name("LONGTEXT2").type(VariableType.CHARACTER).length(101).label("label").build(),
            Variable.builder().name("NUMBER 1").type(VariableType.NUMERIC).length(8).label("label").build());

        ColumnHashTable columnHashTable = new ColumnHashTable(variablesList);

        // Create the column hash table subheader starting at bucket #2 (index 1)
        // The size gives more than enough space for the rest.
        ColumnHashTableSubheader columnHashTableSubheader = new ColumnHashTableSubheader(columnHashTable, 100, 1);
        assertEquals(4, columnHashTableSubheader.totalBucketsInSubheader());

        // Write the contents of the subheader to a byte array.
        final byte[] expectedSubheaderData = new byte[] {
            -2, -1, -1, -1, -1, -1, -1, -1,  // signature

            16, 0,  // size of data
            0, 0, // unknown
            0, 0, 0, 0, // padding

            4, 0,  // bucket #2 (4)
            -2, -1, // bucket #3 (-2)
            -3, -1, // bucket #4 (-3)
            0, 0, // bucket #5 (empty)

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        int size = columnHashTableSubheader.size();
        assertEquals(expectedSubheaderData.length, columnHashTableSubheader.size());

        // Confirm that writeSubheader() writes the expected data.
        byte[] actualSubheaderData = new byte[size];
        columnHashTableSubheader.writeSubheader(actualSubheaderData, 0);
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }

    @Test
    void testMiddleSubheader() {
        List<Variable> variablesList = List.of(
            Variable.builder().name("BEFORE").type(VariableType.NUMERIC).length(8).label("A label").build(),
            Variable.builder().name("TEXT1").type(VariableType.CHARACTER).length(256).label("label").build(),
            Variable.builder().name("LONGTEXT2").type(VariableType.CHARACTER).length(101).label("label").build(),
            Variable.builder().name("NUMBER 1").type(VariableType.NUMERIC).length(8).label("label").build());

        ColumnHashTable columnHashTable = new ColumnHashTable(variablesList);

        // Create the column hash table subheader starting at bucket #3 (index 2) that can only fit 1 bucket.
        ColumnHashTableSubheader columnHashTableSubheader = new ColumnHashTableSubheader(columnHashTable, 30, 2);
        assertEquals(1, columnHashTableSubheader.totalBucketsInSubheader());

        // Write the contents of the subheader to a byte array.
        final byte[] expectedSubheaderData = new byte[] {
            -2, -1, -1, -1, -1, -1, -1, -1,  // signature

            10, 0,  // size of data
            0, 0, // unknown
            0, 0, 0, 0, // padding

            -2, -1, // bucket #3 (-2)

            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 // 12 bytes of padding at end
        };

        int size = columnHashTableSubheader.size();
        assertEquals(expectedSubheaderData.length, columnHashTableSubheader.size());

        // Confirm that writeSubheader() writes the expected data.
        byte[] actualSubheaderData = new byte[size];
        columnHashTableSubheader.writeSubheader(actualSubheaderData, 0);
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

        ColumnHashTable columnHashTable = new ColumnHashTable(variablesList);

        ColumnHashTableSubheader columnHashTableSubheader = new ColumnHashTableSubheader(columnHashTable, 32740, 0);
        assertEquals(16341, columnHashTableSubheader.totalBucketsInSubheader());

        int size = columnHashTableSubheader.size();
        assertEquals(32732, columnHashTableSubheader.size());

        // Confirm that writeSubheader() can write the data.
        // The data is too long to check.
        byte[] actualSubheaderData = new byte[size];
        columnHashTableSubheader.writeSubheader(actualSubheaderData, 0);
    }
}