package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;
import org.scharp.sas7bdat.Sas7bdatExporter.Sas7bdatPageLayout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.scharp.sas7bdat.Subheader.COMPRESSION_UNCOMPRESSED;
import static org.scharp.sas7bdat.Subheader.SUBHEADER_TYPE_A;

/** Unit tests for {@link RowSizeSubheader}. */
public class RowSizeSubheaderTest {

    @Test
    void testTypeCode() {
        // Create a RowSizeSubheader
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        List<Variable> variableList = List.of();
        Sas7bdatVariables variables = new Sas7bdatVariables(variableList);
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, 0x10000, variables);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "TYPE", "dataset label",
            variables, 0, pageLayout);

        assertEquals(SUBHEADER_TYPE_A, rowSizeSubheader.typeCode());
    }

    @Test
    void testCompressionCode() {
        // Create a RowSizeSubheader
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        List<Variable> variableList = List.of();
        Sas7bdatVariables variables = new Sas7bdatVariables(variableList);
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, 0x10000, variables);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "TYPE", "dataset label",
            variables, 0, pageLayout);

        assertEquals(COMPRESSION_UNCOMPRESSED, rowSizeSubheader.compressionCode());
    }

    @Test
    void basicTest() {
        // Create a RowSizeSubheader
        List<Variable> variableList = List.of(
            new Variable(
                "VAR1",
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
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariables variables = new Sas7bdatVariables(variableList);
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, 0x10000, variables);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "TYPE", "dataset label",
            variables, 0x12456, pageLayout);

        pageLayout.columnText.add("TYPE    ");
        pageLayout.columnText.add("dataset label");
        pageLayout.columnText.noMoreText();

        // Add ColumnFormatSubheader, one on first page, two on second page.
        pageLayout.addSubheader(new ColumnFormatSubheader(variableList.get(0), pageLayout.columnText));
        pageLayout.addSubheader(FillerSubheader.fillRestOfPage(pageLayout.currentMetadataPage));
        pageLayout.addSubheader(new ColumnFormatSubheader(variableList.get(1), pageLayout.columnText));
        pageLayout.addSubheader(new ColumnFormatSubheader(variableList.get(2), pageLayout.columnText));

        // Set some values that appear in the row size subheader.
        rowSizeSubheader.setTotalPossibleObservationOnMixedPage(120);
        rowSizeSubheader.setMaxObservationsPerDataPage(121);
        rowSizeSubheader.setTotalMetadataPages(122);
        rowSizeSubheader.setTotalPagesInDataset(123);

        final byte[] expectedSubheaderData = new byte[] {
            -9, -9, -9, -9, 0, 0, 0, 0,  // signature

            -16, 0, 0, 0, 0, 0, 0, 0, // unknown
            8, 0, 0, 0, 0, 0, 0, 0, // unknown (subheaders + 2)
            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            17, 48, 34, 0, 0, 0, 0, 0, // unknown

            120, 1, 0, 0, 0, 0, 0, 0, // row length in bytes
            86, 36, 1, 0, 0, 0, 0, 0, // total observations (deleted or not)
            0, 0, 0, 0, 0, 0, 0, 0, // number of deleted observations
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            1, 0, 0, 0, 0, 0, 0, 0, // total ColumnFormatSubheaders on the first page.
            2, 0, 0, 0, 0, 0, 0, 0, // total ColumnFormatSubheaders on the second page.

            0, 0, 0, 0, 0, 0, 0, 0, // unknown
            26, 0, 0, 0, 0, 0, 0, 0, // aggregate variable name length
            50, 3, 0, 0, 0, 0, 0, 0, // page size
            0, 0, 0, 0, 0, 0, 0, 0, // unknown

            120, 0, 0, 0, 0, 0, 0, 0, // max observations on mix page

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
            2, 0, 0, 0, 0, 0, 0, 0, // subheader in page of ColumnSizeSubheader

            122, 0, 0, 0, 0, 0, 0, 0, // page of last subheader
            1, 0, 0, 0, 0, 0, 0, 0, // subheader in page of last subheader

            122, 0, 0, 0, 0, 0, 0, 0, // page of first observation
            3, 0, 0, 0, 0, 0, 0, 0, // block index in page of first observation

            123, 0, 0, 0, 0, 0, 0, 0, // page of last observation
            61, 0, 0, 0, 0, 0, 0, 0, // block index in page of last observation

            1, 0, 0, 0, 0, 0, 0, 0, // page index of first ColumnFormatSubheader
            3, 0, 0, 0, 0, 0, 0, 0, // subheader in page of first ColumnFormatSubheader

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
            13, 0, // length of dataset type label

            0, 0, // column text subheader index of dataset type name
            8, 0, // offset of dataset type name
            8, 0, // length of dataset type name

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
            121, 0, // max observations per data page

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
        rowSizeSubheader.writeSubheader(actualSubheaderData, offset);

        // Determine the expected return value.
        byte[] expectedArray = new byte[expectedSubheaderData.length + offset];
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
            new Variable(
                "V",
                VariableType.NUMERIC,
                8,
                "",
                Format.UNSPECIFIED,
                Format.UNSPECIFIED));
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariables variables = new Sas7bdatVariables(variableList);
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, 0x20000, variables);
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, "", "", variables, 0,
            pageLayout);

        pageLayout.columnText.add("        "); // must add the dataset type.

        final byte[] expectedSubheaderData = new byte[] {
            -9, -9, -9, -9, 0, 0, 0, 0,  // signature

            -16, 0, 0, 0, 0, 0, 0, 0, // unknown
            2, 0, 0, 0, 0, 0, 0, 0, // unknown (subheaders + 2)
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
            2, 0, 0, 0, 0, 0, 0, 0, // subheader in page of ColumnSizeSubheader

            0, 0, 0, 0, 0, 0, 0, 0, // page of last subheader
            -1, -1, -1, -1, -1, -1, -1, -1, // subheader in page of last subheader

            0, 0, 0, 0, 0, 0, 0, 0, // page of first observation
            3, 0, 0, 0, 0, 0, 0, 0, // block index in page of first observation

            0, 0, 0, 0, 0, 0, 0, 0, // page of last observation
            3, 0, 0, 0, 0, 0, 0, 0, // block index in page of last observation

            0, 0, 0, 0, 0, 0, 0, 0, // page index of first ColumnFormatSubheader
            1, 0, 0, 0, 0, 0, 0, 0, // subheader in page of first ColumnFormatSubheader

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
            0, 0, // length of dataset type label

            0, 0, // column text subheader index of dataset type name
            8, 0, // offset of dataset type name
            8, 0, // length of dataset type name

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
            0, 0, // max observations per data page

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
}