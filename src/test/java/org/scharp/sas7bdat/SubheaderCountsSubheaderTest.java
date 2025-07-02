package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;
import org.scharp.sas7bdat.Sas7bdatExporter.Sas7bdatUnix64bitMetadata;
import org.scharp.sas7bdat.Sas7bdatExporter.Sas7bdatUnix64bitVariables;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_UNCOMPRESSED;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_A;

/** Unit tests for {@link SubheaderCountsSubheader}. */
public class SubheaderCountsSubheaderTest {

    @Test
    void testTypeCode() {
        // Create a SubheaderCountsSubheader
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        List<Variable> variableList = List.of();
        Sas7bdatUnix64bitVariables variables = new Sas7bdatUnix64bitVariables(variableList);
        Sas7bdatUnix64bitMetadata metadata = new Sas7bdatUnix64bitMetadata(pageSequenceGenerator, 0x10000, variables);
        SubheaderCountsSubheader subheaderCountsSubheader = new SubheaderCountsSubheader(variableList, metadata);

        assertEquals(SUBHEADER_TYPE_A, subheaderCountsSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        // Create a SubheaderCountsSubheader
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        List<Variable> variableList = List.of();
        Sas7bdatUnix64bitVariables variables = new Sas7bdatUnix64bitVariables(variableList);
        Sas7bdatUnix64bitMetadata metadata = new Sas7bdatUnix64bitMetadata(pageSequenceGenerator, 0x10000, variables);
        SubheaderCountsSubheader subheaderCountsSubheader = new SubheaderCountsSubheader(variableList, metadata);

        assertEquals(COMPRESSION_UNCOMPRESSED, subheaderCountsSubheader.compressionCode());
    }

    @Test
    void basicTest() {
        // Create a SubheaderCountsSubheader
        List<Variable> variableList = List.of(
            new Variable(
                "VAR1",
                1, // variable 1
                VariableType.NUMERIC,
                8,
                "A label",
                new Format("$OUTPUT", 8, 2),
                new Format("$INPUT", 9, 6),
                StrictnessMode.SAS_ANY),
            new Variable(
                "TEXT1",
                2, // variable 2
                VariableType.CHARACTER,
                256,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED,
                StrictnessMode.SAS_ANY),
            new Variable(
                "LONGTEXT2", //
                3, // variable 3
                VariableType.CHARACTER,
                101,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED,
                StrictnessMode.SAS_ANY),
            new Variable(
                "NUMBER 1", //
                4, // variable 4
                VariableType.NUMERIC,
                8,
                "label",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED,
                StrictnessMode.SAS_ANY));
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatUnix64bitVariables variables = new Sas7bdatUnix64bitVariables(variableList);
        Sas7bdatUnix64bitMetadata metadata = new Sas7bdatUnix64bitMetadata(pageSequenceGenerator, 0x10000, variables);
        SubheaderCountsSubheader subheaderCountsSubheader = new SubheaderCountsSubheader(variableList, metadata);

        // Add the subheaders that get counted to the metadata.
        // This is intentionally not a realistically constructed metadata; many of the repeated
        // subheaders are identical.  This helps to insulate this unit test from implementation changes
        // to the other subheader types.

        // Add two column text subheaders.
        metadata.addSubheader(new ColumnTextSubheader((short) 0, (short) 500));
        metadata.addSubheader(new FillerSubheader(metadata.currentMetadataPage.totalBytesRemainingForNewSubheader()));
        metadata.addSubheader(new ColumnTextSubheader((short) 1, (short) 500));

        // Add one column attributes subheader
        metadata.addSubheader(new FillerSubheader(metadata.currentMetadataPage.totalBytesRemainingForNewSubheader()));
        metadata.addSubheader(new ColumnAttributesSubheader(variables, 0, (short) 500));

        // Add three column name subheaders
        ColumnText columnText = new ColumnText(metadata);
        metadata.addSubheader(new ColumnNameSubheader(variableList, 0, columnText));
        metadata.addSubheader(new ColumnNameSubheader(variableList, 0, columnText));
        metadata.addSubheader(new ColumnNameSubheader(variableList, 0, columnText));

        // Add four column list subheaders
        metadata.addSubheader(new ColumnListSubheader(variables, 0));
        metadata.addSubheader(new FillerSubheader(metadata.currentMetadataPage.totalBytesRemainingForNewSubheader()));
        metadata.addSubheader(new ColumnListSubheader(variables, 0));
        metadata.addSubheader(new ColumnListSubheader(variables, 0));
        metadata.addSubheader(new FillerSubheader(metadata.currentMetadataPage.totalBytesRemainingForNewSubheader()));
        metadata.addSubheader(new ColumnListSubheader(variables, 0));

        final byte[] expectedSubheaderData = new byte[] {
            0, -4, -1, -1, -1, -1, -1, -1,  // signature

            72, 0, 0, 0, 0, 0, 0, 0, // max subheader data size
            4, 0, 0, 0, 0, 0, 0, 0, // unknown
            7, 0, 0, 0, 0, 0, 0, 0, // unknown, maybe a count

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            12, 7, 0, 0, 0, 0, 0, 0, // unknown

            // ColumnAttributeSubheader information (offset 120)
            -4, -1, -1, -1, -1, -1, -1, -1, // signature
            3, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            1, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            3, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            1, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // ColumnTextSubheader information (offset 160)
            -3, -1, -1, -1, -1, -1, -1, -1, // signature
            1, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            1, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            2, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            1, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // ColumnNameSubheader information (offset 200)
            -1, -1, -1, -1, -1, -1, -1, -1, // signature
            3, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            2, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            3, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            4, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // ColumnListSubheader information (offset 240)
            -2, -1, -1, -1, -1, -1, -1, -1, // signature
            3, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            5, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            5, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            1, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // unknown A information (offset 280)
            -5, -1, -1, -1, -1, -1, -1, -1, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // unknown B information (offset 320)
            -6, -1, -1, -1, -1, -1, -1, -1, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // unknown C information (offset 360)
            -7, -1, -1, -1, -1, -1, -1, -1, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // reserved #1 information (offset 400)
            0, 0, 0, 0, 0, 0, 0, 0, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // reserved #2 information (offset 440)
            0, 0, 0, 0, 0, 0, 0, 0, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // reserved #3 information (offset 480)
            0, 0, 0, 0, 0, 0, 0, 0, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // reserved #4 information (offset 520)
            0, 0, 0, 0, 0, 0, 0, 0, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // reserved #5 information (offset 560)
            0, 0, 0, 0, 0, 0, 0, 0, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance
        };

        int size = subheaderCountsSubheader.size();
        assertEquals(expectedSubheaderData.length, subheaderCountsSubheader.size());

        // Get the subheader with a non-zero offset.
        int offset = 10;
        byte[] actualSubheaderData = new byte[size + offset];
        subheaderCountsSubheader.writeSubheader(actualSubheaderData, offset);

        // Determine the expected return value.
        byte[] expectedArray = new byte[expectedSubheaderData.length + offset];
        System.arraycopy(expectedSubheaderData, 0, expectedArray, offset, expectedSubheaderData.length);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedArray, actualSubheaderData);
    }

    @Test
    void testNoOtherSubheaders() {
        // Create a SubheaderCountsSubheader
        List<Variable> variableList = List.of(
            new Variable(
                "VAR1",
                1, // variable 1
                VariableType.NUMERIC,
                8,
                "A label",
                new Format("$OUTPUT", 8, 2),
                new Format("$INPUT", 9, 6),
                StrictnessMode.SAS_ANY));
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatUnix64bitVariables variables = new Sas7bdatUnix64bitVariables(variableList);
        Sas7bdatUnix64bitMetadata metadata = new Sas7bdatUnix64bitMetadata(pageSequenceGenerator, 0x10000, variables);
        SubheaderCountsSubheader subheaderCountsSubheader = new SubheaderCountsSubheader(variableList, metadata);

        final byte[] expectedSubheaderData = new byte[] {
            0, -4, -1, -1, -1, -1, -1, -1,  // signature

            0, 0, 0, 0, 0, 0, 0, 0, // max subheader data size
            3, 0, 0, 0, 0, 0, 0, 0, // unknown
            7, 0, 0, 0, 0, 0, 0, 0, // unknown, maybe a count

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            12, 7, 0, 0, 0, 0, 0, 0, // unknown

            // ColumnAttributeSubheader information (offset 120)
            -4, -1, -1, -1, -1, -1, -1, -1, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // ColumnTextSubheader information (offset 160)
            -3, -1, -1, -1, -1, -1, -1, -1, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // ColumnNameSubheader information (offset 200)
            -1, -1, -1, -1, -1, -1, -1, -1, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // ColumnListSubheader information (offset 240)
            -2, -1, -1, -1, -1, -1, -1, -1, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // unknown A information (offset 280)
            -5, -1, -1, -1, -1, -1, -1, -1, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // unknown B information (offset 320)
            -6, -1, -1, -1, -1, -1, -1, -1, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // unknown C information (offset 360)
            -7, -1, -1, -1, -1, -1, -1, -1, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // reserved #1 information (offset 400)
            0, 0, 0, 0, 0, 0, 0, 0, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // reserved #2 information (offset 440)
            0, 0, 0, 0, 0, 0, 0, 0, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // reserved #3 information (offset 480)
            0, 0, 0, 0, 0, 0, 0, 0, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // reserved #4 information (offset 520)
            0, 0, 0, 0, 0, 0, 0, 0, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance

            // reserved #5 information (offset 560)
            0, 0, 0, 0, 0, 0, 0, 0, // signature
            0, 0, 0, 0, 0, 0, 0, 0, // page of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of first appearance
            0, 0, 0, 0, 0, 0, 0, 0, // page of last appearance
            0, 0, 0, 0, 0, 0, 0, 0, // position of last appearance
        };

        assertEquals(expectedSubheaderData.length, subheaderCountsSubheader.size());

        // Confirm that writeSubheader() wrote the data to the expected location.
        byte[] actualSubheaderData = new byte[expectedSubheaderData.length];
        subheaderCountsSubheader.writeSubheader(actualSubheaderData, 0);
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }
}