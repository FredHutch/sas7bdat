package org.scharp.sas7bdat;

import static org.scharp.sas7bdat.WriteUtil.write2;
import static org.scharp.sas7bdat.WriteUtil.write4;
import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A subheader that contains information about row sizes.
 */
class RowSizeSubheader extends Subheader {

    private final String datasetType;
    private final String datasetLabel;
    private final int totalObservationsInDataset;
    private final Sas7bdatPageLayout pageLayout;
    private final long initialPageSequenceNumber;

    private final int rowSizeInBytes;
    private final int totalVariableNameLength;
    private final int maxVariableNameLength;
    private final int maxVariableLabelLength;

    private int maxObservationsPerDataPage;
    private int totalMetadataPages;
    private int totalPagesInDataset;

    /**
     * Creates a Row Size Subheader
     *
     * @param pageSequenceGenerator
     *     The page sequence generator.  This is not incremented; it is used for the initial page sequence.
     * @param datasetType
     *     The dataset type.  This string must be added to {@code pageLayout}'s ColumnText before this subheader is
     *     written.
     * @param datasetLabel
     *     The dataset label.  This string must be added to {@code pageLayout}'s ColumnText before this subheader is
     *     written.
     * @param variablesLayout
     *     Information about the variables, including the computed row length.
     * @param pageLayout
     *     Information about how the subheaders are laid out on the page.  This must be populated before this subheader
     *     is written.
     * @param totalObservationsInDataset
     *     The total number of observations in the dataset.
     */
    RowSizeSubheader(PageSequenceGenerator pageSequenceGenerator, String datasetType, String datasetLabel,
        Sas7bdatVariablesLayout variablesLayout, Sas7bdatPageLayout pageLayout, int totalObservationsInDataset) {
        this.datasetType = datasetType;
        this.datasetLabel = datasetLabel;
        this.totalObservationsInDataset = totalObservationsInDataset;
        this.pageLayout = pageLayout; // this is filled in later by the caller
        this.initialPageSequenceNumber = pageSequenceGenerator.initialPageSequence();

        // Calculate some properties from the variables layout.
        int totalVariableNameLength = 0;
        int maxVariableNameLength = 0;
        int maxVariableLabelLength = 0;
        for (Variable variable : variablesLayout.variables()) {
            totalVariableNameLength += ColumnTextSubheader.sizeof(variable.name());

            if (maxVariableNameLength < ColumnTextSubheader.sizeof(variable.name())) {
                maxVariableNameLength = ColumnTextSubheader.sizeof(variable.name());
            }
            if (maxVariableLabelLength < ColumnTextSubheader.sizeof(variable.label())) {
                maxVariableLabelLength = ColumnTextSubheader.sizeof(variable.label());
            }
        }

        this.rowSizeInBytes = variablesLayout.rowLength();
        this.totalVariableNameLength = totalVariableNameLength;
        this.maxVariableNameLength = maxVariableNameLength;
        this.maxVariableLabelLength = maxVariableLabelLength;
    }

    private void writeRecordLocation(byte[] page, int offset, long pageIndex, long recordIndex) {
        write8(page, offset, pageIndex);
        write8(page, offset + 8, recordIndex);
    }

    void setMaxObservationsPerDataPage(int maxObservationsPerDataPage) {
        assert 0 <= maxObservationsPerDataPage : "maxObservationsPerDataPage is negative: " + maxObservationsPerDataPage;
        assert maxObservationsPerDataPage < 0x10000 : "maxObservationsPerDataPage is too large: " + maxObservationsPerDataPage;

        this.maxObservationsPerDataPage = maxObservationsPerDataPage;
    }

    void setTotalMetadataPages(int totalMetadataPages) {
        this.totalMetadataPages = totalMetadataPages;
    }

    void setTotalPagesInDataset(int totalPagesInDataset) {
        this.totalPagesInDataset = totalPagesInDataset;
    }

    @Override
    int size() {
        return 808;
    }

    @Override
    void writeSubheader(byte[] page, int subheaderOffset) {

        var subheaderInformation = new Sas7bdatPageLayout.NextSubheader() {

            int totalSubheaders = 0;

            // The next value at offset 88 is unknown, but setting it incorrectly can cause SAS to crash.
            //
            // This seems to be
            // 1) the size of the "payload" of all ColumnListSubheaders (subheader size - 28)
            // 2) the value at offset 16 of the first ColumnListSubheader when there is only one
            // 3) 22 + 2 * the total columns (offset 26) the first ColumnListSubheader
            //
            // Calculate it with method 1.
            int offset88 = 0;

            // Calculate the number of ColumnFormatSubheaders on the first metadata page
            // that has them and the number of ColumnFormatSubheaders on the final metadata
            // page that has them.
            // SAS can't process datasets if this is incorrect.
            int firstPageWithColumnFormatSubheader = 0; // impossible value
            int totalColumnFormatSubheadersOnFirstPage = 0;
            int secondPageWithColumnFormatSubheader = 0; // impossible value
            int totalColumnFormatSubheadersOnSecondPage = 0;
            int blockOfFirstColumnFormatSubheader = 0; // impossible value

            int totalColumnTextSubheaders = 0;

            @Override
            public void nextSubheader(Subheader subheader, short pageNumberOfSubheader, short positionInPage) {
                totalSubheaders++;

                if (subheader instanceof ColumnFormatSubheader) {
                    if (firstPageWithColumnFormatSubheader == 0) {
                        // This is the first ColumnFormatSubheader in the metadata.
                        firstPageWithColumnFormatSubheader = pageNumberOfSubheader;
                        blockOfFirstColumnFormatSubheader = positionInPage;
                        totalColumnFormatSubheadersOnFirstPage++;

                    } else if (firstPageWithColumnFormatSubheader == pageNumberOfSubheader) {
                        // This ColumnFormatSubheader is on the first page of metadata with ColumnFormatSubheaders
                        totalColumnFormatSubheadersOnFirstPage++;

                    } else if (secondPageWithColumnFormatSubheader == 0) {
                        // This is the first ColumnFormatSubheader on the second page of metadata with ColumnFormatSubheaders
                        secondPageWithColumnFormatSubheader = pageNumberOfSubheader;
                        totalColumnFormatSubheadersOnSecondPage++;

                    } else if (secondPageWithColumnFormatSubheader == pageNumberOfSubheader) {
                        // This is on the second page of metadata with ColumnFormatSubheaders
                        totalColumnFormatSubheadersOnSecondPage++;
                    }

                } else if (subheader instanceof ColumnListSubheader) {
                    // Add to the size of the "payload" of all ColumnListSubheaders (subheader size - 28)
                    offset88 += subheader.size() - 28;

                } else if (subheader instanceof ColumnTextSubheader) {
                    totalColumnTextSubheaders++;
                }
            }
        };
        pageLayout.forEachSubheader(subheaderInformation);

        write8(page, subheaderOffset, SIGNATURE_ROW_SIZE); // signature

        write8(page, subheaderOffset + 8, 0xF0); // unknown
        write8(page, subheaderOffset + 16, subheaderInformation.totalSubheaders + 2); // unknown
        write8(page, subheaderOffset + 24, 0x00); // unknown
        write8(page, subheaderOffset + 32, 0x223011); // unknown

        write8(page, subheaderOffset + 40, rowSizeInBytes); // row length in bytes
        write8(page, subheaderOffset + 48, totalObservationsInDataset); // (deleted and not)
        write8(page, subheaderOffset + 56, 0); // number of deleted observations
        write8(page, subheaderOffset + 64, 0); // unknown

        // The number of ColumnFormatSubheaders on the first and second page.
        write8(page, subheaderOffset + 72, subheaderInformation.totalColumnFormatSubheadersOnFirstPage);
        write8(page, subheaderOffset + 80, subheaderInformation.totalColumnFormatSubheadersOnSecondPage);
        write8(page, subheaderOffset + 88, subheaderInformation.offset88);
        write8(page, subheaderOffset + 96, totalVariableNameLength);

        write8(page, subheaderOffset + 104, page.length); // page size
        write8(page, subheaderOffset + 112, 0); // unknown

        // How many observations can fit on a 'mix' page.
        // This may be larger than the number of observations that are actually on the page.
        final Sas7bdatPage finalMetadataPage = pageLayout.currentMetadataPage;
        final int totalPossibleObservationsOnMixedPage = finalMetadataPage.maxObservations();
        write8(page, subheaderOffset + 120, totalPossibleObservationsOnMixedPage);

        write8(page, subheaderOffset + 128, 0xFFFFFFFFFFFFFFFFL); // bit pattern
        write8(page, subheaderOffset + 136, 0xFFFFFFFFFFFFFFFFL); // bit pattern

        write8(page, subheaderOffset + 144, 0x00); // zero
        write8(page, subheaderOffset + 152, 0x00); // zero
        write8(page, subheaderOffset + 160, 0x00); // zero
        write8(page, subheaderOffset + 168, 0x00); // zero
        write8(page, subheaderOffset + 176, 0x00); // zero
        write8(page, subheaderOffset + 184, 0x00); // zero
        write8(page, subheaderOffset + 192, 0x00); // zero
        write8(page, subheaderOffset + 200, 0x00); // zero
        write8(page, subheaderOffset + 208, 0x00); // zero
        write8(page, subheaderOffset + 216, 0x00); // zero
        write8(page, subheaderOffset + 224, 0x00); // zero
        write8(page, subheaderOffset + 232, 0x00); // zero
        write8(page, subheaderOffset + 240, 0x00); // zero
        write8(page, subheaderOffset + 248, 0x00); // zero
        write8(page, subheaderOffset + 256, 0x00); // zero
        write8(page, subheaderOffset + 264, 0x00); // zero
        write8(page, subheaderOffset + 272, 0x00); // zero
        write8(page, subheaderOffset + 280, 0x00); // zero
        write8(page, subheaderOffset + 288, 0x00); // zero
        write8(page, subheaderOffset + 296, 0x00); // zero
        write8(page, subheaderOffset + 304, 0x00); // zero
        write8(page, subheaderOffset + 312, 0x00); // zero
        write8(page, subheaderOffset + 320, 0x00); // zero
        write8(page, subheaderOffset + 328, 0x00); // zero
        write8(page, subheaderOffset + 336, 0x00); // zero
        write8(page, subheaderOffset + 344, 0x00); // zero
        write8(page, subheaderOffset + 352, 0x00); // zero
        write8(page, subheaderOffset + 360, 0x00); // zero
        write8(page, subheaderOffset + 368, 0x00); // zero
        write8(page, subheaderOffset + 376, 0x00); // zero
        write8(page, subheaderOffset + 384, 0x00); // zero
        write8(page, subheaderOffset + 392, 0x00); // zero
        write8(page, subheaderOffset + 400, 0x00); // zero
        write8(page, subheaderOffset + 408, 0x00); // zero
        write8(page, subheaderOffset + 416, 0x00); // zero
        write8(page, subheaderOffset + 424, 0x00); // zero
        write8(page, subheaderOffset + 432, 0x00); // zero

        write8(page, subheaderOffset + 440, initialPageSequenceNumber); // initial page sequence

        write8(page, subheaderOffset + 448, 0x00);
        write8(page, subheaderOffset + 456, 0x00);
        write8(page, subheaderOffset + 464, 0x00);
        write8(page, subheaderOffset + 472, 0x00);
        write8(page, subheaderOffset + 480, 0x00);
        write8(page, subheaderOffset + 488, 0x00); // after repair, this is 0x01
        write8(page, subheaderOffset + 496, 0x00); // after repair, this is a timestamp
        write8(page, subheaderOffset + 504, 0x00); // after repair, this is a timestamp

        // Unknown, but could be the location of ColumnSizeSubheader, which is always
        // the second subheader added to the first page.
        writeRecordLocation(page, subheaderOffset + 512, 1, 2);

        // Unknown, but could be the location of the last Subheader block, in which case
        // the -1 doesn't include the terminal subheader.
        writeRecordLocation(page, subheaderOffset + 528, totalMetadataPages,
            finalMetadataPage.subheaders().size() - 1);

        // The location of the first data record.
        int totalObservationsOnMixedPage = Math.min(totalPossibleObservationsOnMixedPage, totalObservationsInDataset);
        if (totalObservationsInDataset == 0) {
            writeRecordLocation(page, subheaderOffset + 544, 0, 3); // why 3?
        } else {
            final int pageOfFirstDataRecord;
            final int blockOfFirstDataRecord;
            if (totalObservationsOnMixedPage == 0) {
                // There is no mixed page.
                pageOfFirstDataRecord = totalMetadataPages + 1;
                blockOfFirstDataRecord = 1;
            } else {
                // There is a mixed page.
                pageOfFirstDataRecord = totalMetadataPages;
                blockOfFirstDataRecord = finalMetadataPage.subheaders().size() + 1;
            }
            writeRecordLocation(page, subheaderOffset + 544, pageOfFirstDataRecord, blockOfFirstDataRecord);
        }

        // The location of the last data record.
        if (totalObservationsInDataset == 0) {
            writeRecordLocation(page, subheaderOffset + 560, 0, 3); // why 3?
        } else {
            // If this is obviously corrupt, for example if it's zero or out
            // of the possible range, then SAS won't load the dataset.  However,
            // SAS still loads the dataset if it's legal but incorrect.
            // I don't know what this is used for.
            final int lastRecordIndex;
            if (totalObservationsInDataset == totalObservationsOnMixedPage) {
                // There are no data pages, so the last data record is the last index on the mixed page.
                lastRecordIndex = finalMetadataPage.subheaders().size() + totalObservationsOnMixedPage;
            } else {
                // The last index on the last page is however many are left over after removing all
                // the whole pages.
                int totalObservationsOnAllDataPages = totalObservationsInDataset - totalObservationsOnMixedPage;
                int lastIndex = totalObservationsOnAllDataPages % maxObservationsPerDataPage;
                if (lastIndex == 0) {
                    // This happens when the all data pages are completely full.
                    // In this case, the index of the last record isn't 0, it's last index
                    // on the previous page.
                    lastRecordIndex = maxObservationsPerDataPage;
                } else {
                    lastRecordIndex = lastIndex;
                }
            }

            writeRecordLocation(page, subheaderOffset + 560, totalPagesInDataset, lastRecordIndex);
        }

        // The location of the first ColumnFormatSubheader block.
        // SAS may not be able to process the dataset if this is incorrect.
        writeRecordLocation(
            page,
            subheaderOffset + 576,
            subheaderInformation.firstPageWithColumnFormatSubheader,
            subheaderInformation.blockOfFirstColumnFormatSubheader);

        write8(page, subheaderOffset + 592, 0); // unknown
        write8(page, subheaderOffset + 600, 0); // unknown
        write8(page, subheaderOffset + 608, 0); // unknown
        write8(page, subheaderOffset + 616, 0); // unknown
        write8(page, subheaderOffset + 624, 0); // unknown
        write8(page, subheaderOffset + 632, 0); // unknown
        write8(page, subheaderOffset + 640, 0); // unknown
        write8(page, subheaderOffset + 648, 0); // unknown
        write8(page, subheaderOffset + 656, 0); // unknown
        write8(page, subheaderOffset + 664, 0); // unknown

        // Unknown: possibly the reference to the compression (first entry in ColumnText)
        write2(page, subheaderOffset + 672, (short) 0); // unknown
        write2(page, subheaderOffset + 674, (short) 8); // unknown
        write2(page, subheaderOffset + 676, (short) 4); // unknown

        // The reference to the dataset label in the column text.
        pageLayout.columnText.writeTextLocation(page, subheaderOffset + 678, datasetLabel);

        // The reference to the dataset type string in the column text.
        pageLayout.columnText.writeTextLocation(page, subheaderOffset + 684, datasetType);

        // Unknown
        write2(page, subheaderOffset + 690, (short) 0x00);
        write2(page, subheaderOffset + 692, (short) 0x00);
        write2(page, subheaderOffset + 694, (short) 0x00);

        // Unknown: possibly a reference to the eight spaces that are the second entry in ColumnText.
        write2(page, subheaderOffset + 696, (short) 0x00); // unknown
        write2(page, subheaderOffset + 698, (short) 0x0C); // unknown
        write2(page, subheaderOffset + 700, (short) 0x08); // unknown

        // Unknown: possibly a reference to the creator proc
        //metadata.columnText.writeTextLocation(page, subheaderOffset + 702, "DATASTEP");
        write2(page, subheaderOffset + 702, (short) 0x00);
        write2(page, subheaderOffset + 704, (short) 0x1C);
        write2(page, subheaderOffset + 706, (short) 0x08);

        write2(page, subheaderOffset + 708, (short) 0x00); // unknown
        write2(page, subheaderOffset + 710, (short) 0x00); // unknown

        write8(page, subheaderOffset + 712, 0); // unknown
        write8(page, subheaderOffset + 720, 0); // unknown
        write8(page, subheaderOffset + 728, 0); // unknown
        write8(page, subheaderOffset + 736, 0); // unknown

        write2(page, subheaderOffset + 744, (short) 0x04); // unknown
        write2(page, subheaderOffset + 746, (short) 0x01); // unknown

        // The value at 748 can cause SAS to crash if it's too small.
        // This makes me think it's an offset or count.
        write2(page, subheaderOffset + 748, (short) subheaderInformation.totalColumnTextSubheaders);

        write2(page, subheaderOffset + 750, (short) maxVariableNameLength);

        write8(page, subheaderOffset + 752, maxVariableLabelLength);

        write4(page, subheaderOffset + 760, 0x00); // unknown
        write2(page, subheaderOffset + 764, (short) 0x00); // unknown

        // In looking at datasets which SAS generates, the following field exactly matches
        // the number of observations (blocks) on a full data page.  However, it's not know
        // how SAS determines this value, since it sometimes leaves extra space on a page
        // that could be used for an observation.
        // I don't know how this field is used by SAS, but it doesn't crash if this value
        // is incorrect.
        write2(page, subheaderOffset + 766, (short) maxObservationsPerDataPage);

        write8(page, subheaderOffset + 768, 0); // unknown
        write8(page, subheaderOffset + 776, totalObservationsInDataset);
        write8(page, subheaderOffset + 784, 0); // unknown

        write4(page, subheaderOffset + 792, 0x01000000); // unknown
        write4(page, subheaderOffset + 796, 0); // unknown

        write8(page, subheaderOffset + 800, 0x0); // unknown
    }

    @Override
    byte typeCode() {
        return SUBHEADER_TYPE_A;
    }

    @Override
    byte compressionCode() {
        return COMPRESSION_UNCOMPRESSED;
    }
}