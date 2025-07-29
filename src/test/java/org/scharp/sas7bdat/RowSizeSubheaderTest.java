///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_UNCOMPRESSED;
import static org.scharp.sas7bdat.Subheader.SIGNATURE_ROW_SIZE;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_A;

/** Unit tests for {@link RowSizeSubheader}. */
public class RowSizeSubheaderTest {

    @Test
    void testSignature() {
        // Create a RowSizeSubheader
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of(
            Variable.builder().name("VAR").type(VariableType.CHARACTER).length(1).build()));
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "TYPE", "dataset label",
            variablesLayout, pageLayout, 0);

        assertEquals(SIGNATURE_ROW_SIZE, rowSizeSubheader.signature());
    }

    @Test
    void testTypeCode() {
        // Create a RowSizeSubheader
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of(
            Variable.builder().name("VAR").type(VariableType.CHARACTER).length(1).build()));
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "TYPE", "dataset label",
            variablesLayout, pageLayout, 0);

        assertEquals(SUBHEADER_TYPE_A, rowSizeSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        // Create a RowSizeSubheader
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of(
            Variable.builder().name("VAR").type(VariableType.CHARACTER).length(1).build()));
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "TYPE", "dataset label",
            variablesLayout, pageLayout, 0);

        assertEquals(COMPRESSION_UNCOMPRESSED, rowSizeSubheader.compressionCode());
    }

    @Test
    void basicTest() {
        // Create a RowSizeSubheader
        List<Variable> variableList = List.of(
            Variable.builder().
                name("VAR1").
                type(VariableType.NUMERIC).
                length(8).
                label("A label").
                outputFormat(new Format("OUTPUT", 8, 2)).
                inputFormat(new Format("INPUT", 9, 6)).
                build(),

            Variable.builder().
                name("TEXT1").
                type(VariableType.CHARACTER).
                length(256).
                label("label").
                build(),

            Variable.builder().
                name("LONGTEXT2").
                type(VariableType.CHARACTER).
                length(101).
                label("label").
                build(),

            Variable.builder().
                name("NUMBER 1").
                type(VariableType.NUMERIC).
                length(8).
                label("label").
                build());
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variableList);
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "TYPE    ", "dataset label",
            variablesLayout, pageLayout, 0x12456);

        pageLayout.addSubheader(rowSizeSubheader);

        pageLayout.columnText.add("TYPE    ");
        pageLayout.columnText.add("dataset label");
        pageLayout.columnText.noMoreText();

        // Add ColumnFormatSubheader, two on the first page, three on the second page, 1 on the third.
        // This tests the ColumnFormatSubheader counting logic.
        pageLayout.addSubheader(new ColumnFormatSubheader(variableList.get(0), pageLayout.columnText));
        pageLayout.addSubheader(new ColumnFormatSubheader(variableList.get(1), pageLayout.columnText));
        pageLayout.addSubheader(FillerSubheader.fillRestOfPage(pageLayout.currentMetadataPage));  // new page
        pageLayout.addSubheader(new ColumnFormatSubheader(variableList.get(2), pageLayout.columnText));
        pageLayout.addSubheader(new ColumnFormatSubheader(variableList.get(3), pageLayout.columnText));
        pageLayout.addSubheader(new ColumnFormatSubheader(variableList.get(3), pageLayout.columnText));
        pageLayout.addSubheader(FillerSubheader.fillRestOfPage(pageLayout.currentMetadataPage));   // new page
        pageLayout.addSubheader(new ColumnFormatSubheader(variableList.get(0), pageLayout.columnText));

        // Add some ColumnListSubheader, since their size is included in the RowSizeSubheader.
        pageLayout.addSubheader(new ColumnListSubheader(variablesLayout, 0));
        pageLayout.addSubheader(new ColumnListSubheader(variablesLayout, 1));

        pageLayout.finalizeMetadata();

        final byte[] expectedSubheaderData = new byte[] {
            -9, -9, -9, -9, 0, 0, 0, 0,  // signature

            -16, 0, 0, 0, 0, 0, 0, 0, // unknown
            17, 0, 0, 0, 0, 0, 0, 0, // unknown (subheaders + 2)
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            17, 48, 34, 0, 0, 0, 0, 0, // unknown

            120, 1, 0, 0, 0, 0, 0, 0, // row length in bytes
            86, 36, 1, 0, 0, 0, 0, 0, // total observations (deleted or not)
            0, 0, 0, 0, 0, 0, 0, 0, // number of deleted observations
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            2, 0, 0, 0, 0, 0, 0, 0, // total ColumnFormatSubheaders on the first page.
            3, 0, 0, 0, 0, 0, 0, 0, // total ColumnFormatSubheaders on the second page.

            58, 0, 0, 0, 0, 0, 0, 0, // unknown (aggregate size of ColumnListSubheader payload)
            26, 0, 0, 0, 0, 0, 0, 0, // aggregate variable name length
            50, 3, 0, 0, 0, 0, 0, 0, // page size
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            -83, 0, 0, 0, 0, 0, 0, 0, // max observations on mix page

            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // bit pattern

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
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            -10, -1, -92, -12, 0, 0, 0, 0, // initial page sequence

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // repair flag
            0, 0, 0, 0, 0, 0, 0, 0, // repair timestamp
            0, 0, 0, 0, 0, 0, 0, 0, // repair timestamp

            1, 0, 0, 0, 0, 0, 0, 0, // page index of ColumnSizeSubheader
            2, 0, 0, 0, 0, 0, 0, 0, // subheader in page with ColumnSizeSubheader

            3, 0, 0, 0, 0, 0, 0, 0, // page with final subheader
            3, 0, 0, 0, 0, 0, 0, 0, // block number of final subheader on page

            3, 0, 0, 0, 0, 0, 0, 0, // page of first observation
            5, 0, 0, 0, 0, 0, 0, 0, // block index in page with first observation

            -79, 1, 0, 0, 0, 0, 0, 0, // page of last observation
            19, 0, 0, 0, 0, 0, 0, 0, // block index in page with last observation

            1, 0, 0, 0, 0, 0, 0, 0, // page index of first ColumnFormatSubheader
            3, 0, 0, 0, 0, 0, 0, 0, // subheader in page with first ColumnFormatSubheader

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

            0, 0, // column text subheader index of compression algorithm name
            8, 0, // offset of compression algorithm name
            4, 0, // length of compression algorithm name

            0, 0, // column text subheader index of dataset label
            16, 0, // offset of dataset label
            13, 0, // length of dataset label

            0, 0, // column text subheader index of dataset type
            8, 0, // offset of dataset type
            8, 0, // length of dataset type

            0, 0, // unknown
            0, 0, // unknown
            0, 0, // unknown

            0, 0, // column text subheader index of second entry in column text
            12, 0, // offset of second entry in column text
            8, 0, // length of second entry in column text

            0, 0, // column text subheader index of creator proc
            28, 0, // offset of creator proc
            8, 0, // length of creator proc

            0, 0, 0, 0, // unknown

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            4, 0, // unknown
            1, 0, // unknown

            1, 0, // column text subheader count
            9, 0, // max variable name length
            7, 0, // max variable label length
            0, 0, 0, 0, 0, 0, // unknown/padding

            0, 0, 0, 0, 0, 0, // unknown
            -82, 0, // max observations per data page

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            86, 36, 1, 0, 0, 0, 0, 0, // observations in dataset
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            0, 0, 0, 1, // unknown
            0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
        };

        int size = rowSizeSubheader.size();
        assertEquals(expectedSubheaderData.length, rowSizeSubheader.size());

        // Get the subheader with a non-zero offset.
        int offset = 10;
        byte[] actualSubheaderData = new byte[size + offset];
        Arrays.fill(actualSubheaderData, (byte) 0xDC);
        rowSizeSubheader.writeSubheader(actualSubheaderData, offset);

        // Determine the expected return value.
        byte[] expectedArray = new byte[expectedSubheaderData.length + offset];
        Arrays.fill(expectedArray, 0, offset, (byte) 0xDC);
        System.arraycopy(expectedSubheaderData, 0, expectedArray, offset, expectedSubheaderData.length);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedArray, actualSubheaderData);
    }

    /**
     * Tests RowSizeSubheader when a dataset has no special header.  This is mostly a test that RowSizeSubheader can
     * write different values than
     */
    @Test
    void minimalTest() {
        // Create a RowSizeSubheader
        List<Variable> variableList = List.of(
            Variable.builder().name("V").type(VariableType.NUMERIC).length(8).build());
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variableList);
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "", "", variablesLayout,
            pageLayout, 0);

        pageLayout.addSubheader(rowSizeSubheader);
        pageLayout.finalizeMetadata();

        final byte[] expectedSubheaderData = new byte[] {
            -9, -9, -9, -9, 0, 0, 0, 0,  // signature

            -16, 0, 0, 0, 0, 0, 0, 0, // unknown
            4, 0, 0, 0, 0, 0, 0, 0, // unknown (subheaders + 2)
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            17, 48, 34, 0, 0, 0, 0, 0, // unknown

            8, 0, 0, 0, 0, 0, 0, 0, // row length in bytes
            0, 0, 0, 0, 0, 0, 0, 0, // total observations (deleted or not)
            0, 0, 0, 0, 0, 0, 0, 0, // number of deleted observations
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            0, 0, 0, 0, 0, 0, 0, 0, // total ColumnFormatSubheaders on the first page.
            0, 0, 0, 0, 0, 0, 0, 0, // total ColumnFormatSubheaders on the second page.

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            1, 0, 0, 0, 0, 0, 0, 0, // aggregate variable name length
            40, 3, 0, 0, 0, 0, 0, 0, // page size
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            19, 31, 0, 0, 0, 0, 0, 0, // max observations on mix page

            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // bit pattern

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
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            -10, -1, -92, -12, 0, 0, 0, 0, // initial page sequence

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // repair flag
            0, 0, 0, 0, 0, 0, 0, 0, // repair timestamp
            0, 0, 0, 0, 0, 0, 0, 0, // repair timestamp

            1, 0, 0, 0, 0, 0, 0, 0, // page index of ColumnSizeSubheader
            2, 0, 0, 0, 0, 0, 0, 0, // subheader in page with ColumnSizeSubheader

            1, 0, 0, 0, 0, 0, 0, 0, // page index of final subheader
            1, 0, 0, 0, 0, 0, 0, 0, // total subheaders in page with final subheader

            0, 0, 0, 0, 0, 0, 0, 0, // page of first observation
            3, 0, 0, 0, 0, 0, 0, 0, // block index in page with first observation

            0, 0, 0, 0, 0, 0, 0, 0, // page of last observation
            3, 0, 0, 0, 0, 0, 0, 0, // block index in page with last observation

            0, 0, 0, 0, 0, 0, 0, 0, // page index of first ColumnFormatSubheader
            0, 0, 0, 0, 0, 0, 0, 0, // subheader in page with first ColumnFormatSubheader

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

            0, 0, // column text subheader index of compression algorithm name
            8, 0, // offset of compression algorithm name
            4, 0, // length of compression algorithm name

            0, 0, // column text subheader index of dataset label
            0, 0, // offset of dataset label
            0, 0, // length of dataset label

            0, 0, // column text subheader index of dataset type
            0, 0, // offset of dataset type
            0, 0, // length of dataset type

            0, 0, // unknown
            0, 0, // unknown
            0, 0, // unknown

            0, 0, // column text subheader index of second entry in column text
            12, 0, // offset of second entry in column text
            8, 0, // length of second entry in column text

            0, 0, // column text subheader index of creator proc
            28, 0, // offset of creator proc
            8, 0, // length of creator proc

            0, 0, 0, 0, // unknown

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            4, 0, // unknown
            1, 0, // unknown

            0, 0, // column text subheader count
            1, 0, // max variable name length
            0, 0, // max variable label length
            0, 0, 0, 0, 0, 0, // unknown/padding

            0, 0, 0, 0, 0, 0, // unknown
            125, 31, // max observations per data page

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // observations in dataset
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            0, 0, 0, 1, // unknown
            0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
        };

        assertEquals(expectedSubheaderData.length, rowSizeSubheader.size());

        // Write the subheader to a data array.
        byte[] actualSubheaderData = new byte[expectedSubheaderData.length];
        rowSizeSubheader.writeSubheader(actualSubheaderData, 0);

        // Confirm that writeSubheader() wrote the data to the expected location.
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }

    /**
     * Tests the values that RowSizeSubheader when a dataset has no observation on a metadata page but still has data
     * pages.
     */
    @Test
    void testNoMixedPage() {
        // Create a RowSizeSubheader with variables that are 32K
        List<Variable> variableList = List.of(
            Variable.builder().name("VAR1").type(VariableType.CHARACTER).length(Short.MAX_VALUE).build(),
            Variable.builder().name("VAR2").type(VariableType.CHARACTER).length(1).build());
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variableList);
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "TESTDATA", "no mixed page",
            variablesLayout, pageLayout, 1);

        pageLayout.addSubheader(rowSizeSubheader);

        pageLayout.columnText.add("TESTDATA");
        pageLayout.columnText.add("no mixed page");
        pageLayout.columnText.noMoreText();

        // Fill the remaining space on the metadata page.
        pageLayout.addSubheader(FillerSubheader.fillRestOfPage(pageLayout.currentMetadataPage));
        pageLayout.finalizeMetadata();

        final byte[] expectedSubheaderData = new byte[] {
            -9, -9, -9, -9, 0, 0, 0, 0,  // signature

            -16, 0, 0, 0, 0, 0, 0, 0, // unknown
            6, 0, 0, 0, 0, 0, 0, 0, // unknown (subheaders + 2)
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            17, 48, 34, 0, 0, 0, 0, 0, // unknown

            0, -128, 0, 0, 0, 0, 0, 0, // row length in bytes
            1, 0, 0, 0, 0, 0, 0, 0, // total observations (deleted or not)
            0, 0, 0, 0, 0, 0, 0, 0, // number of deleted observations
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            0, 0, 0, 0, 0, 0, 0, 0, // total ColumnFormatSubheaders on the first page.
            0, 0, 0, 0, 0, 0, 0, 0, // total ColumnFormatSubheaders on the second page.

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            8, 0, 0, 0, 0, 0, 0, 0, // aggregate variable name length
            40, 3, 0, 0, 0, 0, 0, 0, // page size
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            0, 0, 0, 0, 0, 0, 0, 0, // max observations on mix page

            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // bit pattern

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
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            -10, -1, -92, -12, 0, 0, 0, 0, // initial page sequence

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // repair flag
            0, 0, 0, 0, 0, 0, 0, 0, // repair timestamp
            0, 0, 0, 0, 0, 0, 0, 0, // repair timestamp

            1, 0, 0, 0, 0, 0, 0, 0, // page index of ColumnSizeSubheader
            2, 0, 0, 0, 0, 0, 0, 0, // subheader in page with ColumnSizeSubheader

            1, 0, 0, 0, 0, 0, 0, 0, // page with final subheader
            3, 0, 0, 0, 0, 0, 0, 0, // block number of final subheader on page

            2, 0, 0, 0, 0, 0, 0, 0, // page of first observation
            1, 0, 0, 0, 0, 0, 0, 0, // block index in page with first observation

            2, 0, 0, 0, 0, 0, 0, 0, // page of last observation
            1, 0, 0, 0, 0, 0, 0, 0, // block index in page with last observation

            0, 0, 0, 0, 0, 0, 0, 0, // page index of first ColumnFormatSubheader
            0, 0, 0, 0, 0, 0, 0, 0, // subheader in page with first ColumnFormatSubheader

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

            0, 0, // column text subheader index of compression algorithm name
            8, 0, // offset of compression algorithm name
            4, 0, // length of compression algorithm name

            0, 0, // column text subheader index of dataset label
            16, 0, // offset of dataset label
            13, 0, // length of dataset label

            0, 0, // column text subheader index of dataset type
            8, 0, // offset of dataset type
            8, 0, // length of dataset type

            0, 0, // unknown
            0, 0, // unknown
            0, 0, // unknown

            0, 0, // column text subheader index of second entry in column text
            12, 0, // offset of second entry in column text
            8, 0, // length of second entry in column text

            0, 0, // column text subheader index of creator proc
            28, 0, // offset of creator proc
            8, 0, // length of creator proc

            0, 0, 0, 0, // unknown

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            4, 0, // unknown
            1, 0, // unknown

            1, 0, // column text subheader count
            4, 0, // max variable name length
            0, 0, // max variable label length
            0, 0, 0, 0, 0, 0, // unknown/padding

            0, 0, 0, 0, 0, 0, // unknown
            1, 0, // max observations per data page

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            1, 0, 0, 0, 0, 0, 0, 0, // observations in dataset
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            0, 0, 0, 1, // unknown
            0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
        };

        assertEquals(expectedSubheaderData.length, rowSizeSubheader.size());

        // Get the subheader.
        byte[] actualSubheaderData = new byte[rowSizeSubheader.size()];
        rowSizeSubheader.writeSubheader(actualSubheaderData, 0);

        // Confirm that writeSubheader() wrote the expected data
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }

    /**
     * Tests when all observations fit on a mixed page.
     */
    @Test
    void testMixedPageWithNoDataPages() {
        // Create a RowSizeSubheader with variables that are 1 byte.
        List<Variable> variableList = List.of(
            Variable.builder().name("variable").type(VariableType.CHARACTER).length(1).build());
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(variableList);
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "TESTDATA", "no data page",
            variablesLayout, pageLayout, 256);

        pageLayout.addSubheader(rowSizeSubheader);

        pageLayout.columnText.add("TESTDATA");
        pageLayout.columnText.add("no data page");
        pageLayout.columnText.noMoreText();

        // Finalize the metadata page with plenty of space for the observations.
        pageLayout.finalizeMetadata();

        final byte[] expectedSubheaderData = new byte[] {
            -9, -9, -9, -9, 0, 0, 0, 0,  // signature

            -16, 0, 0, 0, 0, 0, 0, 0, // unknown
            5, 0, 0, 0, 0, 0, 0, 0, // unknown (subheaders + 2)
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            17, 48, 34, 0, 0, 0, 0, 0, // unknown

            1, 0, 0, 0, 0, 0, 0, 0, // row length in bytes
            0, 1, 0, 0, 0, 0, 0, 0, // total observations (deleted or not)
            0, 0, 0, 0, 0, 0, 0, 0, // number of deleted observations
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            0, 0, 0, 0, 0, 0, 0, 0, // total ColumnFormatSubheaders on the first page.
            0, 0, 0, 0, 0, 0, 0, 0, // total ColumnFormatSubheaders on the second page.

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            8, 0, 0, 0, 0, 0, 0, 0, // aggregate variable name length
            40, 3, 0, 0, 0, 0, 0, 0, // page size
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            49, -32, 0, 0, 0, 0, 0, 0, // max observations on mix page

            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // bit pattern

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
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            -10, -1, -92, -12, 0, 0, 0, 0, // initial page sequence

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // repair flag
            0, 0, 0, 0, 0, 0, 0, 0, // repair timestamp
            0, 0, 0, 0, 0, 0, 0, 0, // repair timestamp

            1, 0, 0, 0, 0, 0, 0, 0, // page index of ColumnSizeSubheader
            2, 0, 0, 0, 0, 0, 0, 0, // subheader in page with ColumnSizeSubheader

            1, 0, 0, 0, 0, 0, 0, 0, // page with final subheader
            2, 0, 0, 0, 0, 0, 0, 0, // block number of final subheader on page

            1, 0, 0, 0, 0, 0, 0, 0, // page of first observation
            4, 0, 0, 0, 0, 0, 0, 0, // block index in page with first observation

            1, 0, 0, 0, 0, 0, 0, 0, // page of last observation
            3, 1, 0, 0, 0, 0, 0, 0, // block index in page with last observation

            0, 0, 0, 0, 0, 0, 0, 0, // page index of first ColumnFormatSubheader
            0, 0, 0, 0, 0, 0, 0, 0, // subheader in page with first ColumnFormatSubheader

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

            0, 0, // column text subheader index of compression algorithm name
            8, 0, // offset of compression algorithm name
            4, 0, // length of compression algorithm name

            0, 0, // column text subheader index of dataset label
            16, 0, // offset of dataset label
            12, 0, // length of dataset label

            0, 0, // column text subheader index of dataset type
            8, 0, // offset of dataset type
            8, 0, // length of dataset type

            0, 0, // unknown
            0, 0, // unknown
            0, 0, // unknown

            0, 0, // column text subheader index of second entry in column text
            12, 0, // offset of second entry in column text
            8, 0, // length of second entry in column text

            0, 0, // column text subheader index of creator proc
            28, 0, // offset of creator proc
            8, 0, // length of creator proc

            0, 0, 0, 0, // unknown

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            4, 0, // unknown
            1, 0, // unknown

            1, 0, // column text subheader count
            8, 0, // max variable name length
            0, 0, // max variable label length
            0, 0, 0, 0, 0, 0, // unknown/padding

            0, 0, 0, 0, 0, 0, // unknown
            106, -29, // max observations per data page

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            0, 1, 0, 0, 0, 0, 0, 0, // observations in dataset
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            0, 0, 0, 1, // unknown
            0, 0, 0, 0, // unknown
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
        };

        assertEquals(expectedSubheaderData.length, rowSizeSubheader.size());

        // Get the subheader.
        byte[] actualSubheaderData = new byte[rowSizeSubheader.size()];
        rowSizeSubheader.writeSubheader(actualSubheaderData, 0);

        // Confirm that writeSubheader() wrote the expected data
        assertArrayEquals(expectedSubheaderData, actualSubheaderData);
    }
}