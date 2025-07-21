package org.scharp.sas7bdat;

import java.util.Collection;

import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A subheader that contains some additional information about the repeatable subheaders.
 */
class SubheaderCountsSubheader extends Subheader {
    /**
     * The number of bytes in a SubheaderCountsSubheader.
     */
    private static final int PAGE_SIZE = 600;

    private final Collection<Variable> variables;
    private final Sas7bdatPageLayout pageLayout;

    SubheaderCountsSubheader(Collection<Variable> variables, Sas7bdatPageLayout pageLayout) {
        this.variables = variables;
        this.pageLayout = pageLayout; // this is filled in later by the caller
    }

    private <T extends Subheader> void writeSubheaderInformation(byte[] page, int offset, Class<T> clazz,
        long signature) {
        // Determine the location of the first/last subheader of the requested type.
        // These values are initialized to something that means "not found".
        short pageOfFirstAppearance = 0;
        short positionOfFirstAppearance = 0;
        short pageOfLastAppearance = 0;
        short positionOfLastAppearance = 0;

        if (clazz != null) {
            short currentPageNumber = 0; // impossible number
            short subheaderPositionOnPage = 0;
            for (Subheader subheader : pageLayout.subheaders) {
                short subheaderPageNumber = pageLayout.subheaderLocations.get(subheader).shortValue();
                if (subheaderPageNumber == currentPageNumber) {
                    // This subheader is on the same page as the previous subheader.
                    subheaderPositionOnPage++;
                } else {
                    // This is the first subheader is on a new page.
                    assert currentPageNumber + 1 == subheaderPageNumber : "skipped a page";
                    currentPageNumber = subheaderPageNumber;
                    subheaderPositionOnPage = 1;
                }

                if (clazz.isInstance(subheader)) {
                    // If this is the first time we've seen this subheader type, note it as the first appearance.
                    if (pageOfFirstAppearance == 0) {
                        pageOfFirstAppearance = subheaderPageNumber;
                        positionOfFirstAppearance = subheaderPositionOnPage;
                    }

                    // Always log the last occurrence.
                    pageOfLastAppearance = subheaderPageNumber;
                    positionOfLastAppearance = subheaderPositionOnPage;
                }
            }
        }

        // Write the information to the page.
        write8(page, offset + 0, signature);
        write8(page, offset + 8, pageOfFirstAppearance);
        write8(page, offset + 16, positionOfFirstAppearance);
        write8(page, offset + 24, pageOfLastAppearance);
        write8(page, offset + 32, positionOfLastAppearance);
    }

    @Override
    int size() {
        return PAGE_SIZE;
    }

    @Override
    void writeSubheader(byte[] page, int subheaderOffset) {
        write8(page, subheaderOffset, SIGNATURE_SUBHEADER_COUNTS); // signature

        // The next field appears to be the maximum size of the ColumnTextSubheader or ColumnAttributesSubheader
        // blocks, as reported at their offset 8.  This doesn't include the signature or the padding.
        // This may also include all variable-length subheaders, but the rest would be smaller.
        // This may be a performance hint for how large a buffer to allocate when reading
        // the data.  However, setting this to small or negative value doesn't prevent SAS
        // from reading the dataset, so its value may be ignored.
        int maxSubheaderPayloadSize = 0;
        for (Subheader subheader : pageLayout.subheaders) {
            if (subheader instanceof ColumnTextSubheader columnTextSubheader) {
                maxSubheaderPayloadSize = Math.max(maxSubheaderPayloadSize, columnTextSubheader.sizeOfData());

            } else if (subheader instanceof ColumnAttributesSubheader columnAttributesSubheader) {
                maxSubheaderPayloadSize = Math.max(maxSubheaderPayloadSize, columnAttributesSubheader.sizeOfData());
            }
        }
        write8(page, subheaderOffset + 8, maxSubheaderPayloadSize);

        final int magicNumber = variables.size() == 1 ? 3 : 4;
        //final int magicNumber = variables.size() - 1;
        write8(page, subheaderOffset + 16, magicNumber); // unknown
        write8(page, subheaderOffset + 24, 7); // unknown, maybe a count?
        write8(page, subheaderOffset + 32, 0); // unknown
        write8(page, subheaderOffset + 40, 0); // unknown
        write8(page, subheaderOffset + 48, 0); // unknown
        write8(page, subheaderOffset + 56, 0); // unknown
        write8(page, subheaderOffset + 64, 0); // unknown
        write8(page, subheaderOffset + 72, 0); // unknown
        write8(page, subheaderOffset + 80, 0); // unknown
        write8(page, subheaderOffset + 88, 0); // unknown
        write8(page, subheaderOffset + 96, 0); // unknown
        write8(page, subheaderOffset + 104, 0); // unknown
        write8(page, subheaderOffset + 112, 1804); // unknown

        writeSubheaderInformation(page, subheaderOffset + 120, ColumnAttributesSubheader.class, SIGNATURE_COLUMN_ATTRS);
        writeSubheaderInformation(page, subheaderOffset + 160, ColumnTextSubheader.class, SIGNATURE_COLUMN_TEXT);
        writeSubheaderInformation(page, subheaderOffset + 200, ColumnNameSubheader.class, SIGNATURE_COLUMN_NAME);
        writeSubheaderInformation(page, subheaderOffset + 240, ColumnListSubheader.class, SIGNATURE_COLUMN_LIST);

        // There are three subheader types that we don't know anything about.
        writeSubheaderInformation(page, subheaderOffset + 280, null, SIGNATURE_UNKNOWN_A);
        writeSubheaderInformation(page, subheaderOffset + 320, null, SIGNATURE_UNKNOWN_B);
        writeSubheaderInformation(page, subheaderOffset + 360, null, SIGNATURE_UNKNOWN_C);

        // There are five empty slots, possibly reserved for future use.
        writeSubheaderInformation(page, subheaderOffset + 400, null, 0L);
        writeSubheaderInformation(page, subheaderOffset + 440, null, 0L);
        writeSubheaderInformation(page, subheaderOffset + 480, null, 0L);
        writeSubheaderInformation(page, subheaderOffset + 520, null, 0L);
        writeSubheaderInformation(page, subheaderOffset + 560, null, 0L);
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