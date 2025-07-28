///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A subheader that contains some additional information about the repeatable subheaders.
 */
class SubheaderCountsSubheader extends FixedSizeSubheader {
    /**
     * The number of bytes in a SubheaderCountsSubheader.
     */
    private static final int PAGE_SIZE = 600;

    private final Sas7bdatPageLayout pageLayout;

    private static class SubheaderCount {
        short pageOfFirstAppearance = 0;
        short positionOfFirstAppearance = 0;
        short pageOfLastAppearance = 0;
        short positionOfLastAppearance = 0;

        private void writeInformation(byte[] page, int offset, long signature) {
            write8(page, offset, signature);
            write8(page, offset + 8, pageOfFirstAppearance);
            write8(page, offset + 16, positionOfFirstAppearance);
            write8(page, offset + 24, pageOfLastAppearance);
            write8(page, offset + 32, positionOfLastAppearance);
        }
    }

    SubheaderCountsSubheader(Sas7bdatPageLayout pageLayout) {
        this.pageLayout = pageLayout; // this is filled in later by the caller
    }

    @Override
    int size() {
        return PAGE_SIZE;
    }

    @Override
    void writeSubheader(byte[] page, int subheaderOffset) {
        // The subheader types that are counted
        final long[] subheaderSignatures = new long[] {
            SIGNATURE_COLUMN_ATTRS,
            SIGNATURE_COLUMN_TEXT,
            SIGNATURE_COLUMN_NAME,
            SIGNATURE_COLUMN_LIST,

            // There are three subheader types that we don't know anything about.
            SIGNATURE_UNKNOWN_A,
            SIGNATURE_UNKNOWN_B,
            SIGNATURE_UNKNOWN_C,
        };

        // Initialize a map that counts the occurrence of each subheader type that we're tracking.
        final Map<Long, SubheaderCount> subheaderCounts = new HashMap<>();
        for (long subheaderSignature : subheaderSignatures) {
            subheaderCounts.put(subheaderSignature, new SubheaderCount());
        }

        var fieldCalculator = new Sas7bdatPageLayout.NextSubheader() {
            // This first field appears to be the maximum size of the ColumnTextSubheader or ColumnAttributesSubheader
            // blocks, as reported at their offset 8.  This doesn't include the signature or the padding.
            // This may also include all variable-length subheaders, but the rest would be smaller.
            //
            // This may be a performance hint for how large a buffer to allocate when reading
            // the data.  However, setting this to small or negative value doesn't prevent SAS
            // from reading the dataset, so its value may be ignored.
            int maxSubheaderPayloadSize = 0;

            final Set<Long> subheaderTypesPresent = new HashSet<>();

            @Override
            public void nextSubheader(Subheader subheader, short pageNumberOfSubheader, short positionInPage) {

                if (subheader instanceof VariableSizeSubheader variableSizeSubheader) {
                    maxSubheaderPayloadSize = Math.max(maxSubheaderPayloadSize, variableSizeSubheader.sizeOfData());
                }

                // If this is a subheader type that we count, note its location.
                SubheaderCount subheaderCount = subheaderCounts.get(subheader.signature());
                if (subheaderCount != null) {
                    // Note that this subheader type exists.
                    subheaderTypesPresent.add(subheader.signature());

                    // If this is the first time we've seen this subheader type, note it as the first appearance.
                    if (subheaderCount.pageOfFirstAppearance == 0) {
                        subheaderCount.pageOfFirstAppearance = pageNumberOfSubheader;
                        subheaderCount.positionOfFirstAppearance = positionInPage;
                    }

                    // Always log the last occurrence.
                    subheaderCount.pageOfLastAppearance = pageNumberOfSubheader;
                    subheaderCount.positionOfLastAppearance = positionInPage;
                }
            }
        };
        pageLayout.forEachSubheader(fieldCalculator);

        write8(page, subheaderOffset, signature()); // signature

        // The next field appears to be the maximum size of the ColumnTextSubheader or ColumnAttributesSubheader
        // blocks, as reported at their offset 8.  This doesn't include the signature or the padding.
        // This may also include all variable-length subheaders, but the rest would be smaller.
        // This may be a performance hint for how large a buffer to allocate when reading
        // the data.  However, setting this to small or negative value doesn't prevent SAS
        // from reading the dataset, so its value may be ignored.
        write8(page, subheaderOffset + 8, fieldCalculator.maxSubheaderPayloadSize);

        write8(page, subheaderOffset + 16, fieldCalculator.subheaderTypesPresent.size());
        write8(page, subheaderOffset + 24, subheaderSignatures.length); // how many non-zero vectors exist
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

        // Write each of the subheader counts that were calculated above.
        for (int i = 0; i < subheaderSignatures.length; i++) {
            final long signature = subheaderSignatures[i];
            final SubheaderCount subheaderCount = subheaderCounts.get(signature);
            subheaderCount.writeInformation(page, subheaderOffset + 120 + i * 40, signature);
        }

        // There are five empty slots, possibly reserved for future use.
        SubheaderCount emptyCount = new SubheaderCount();
        emptyCount.writeInformation(page, subheaderOffset + 400, 0);
        emptyCount.writeInformation(page, subheaderOffset + 440, 0);
        emptyCount.writeInformation(page, subheaderOffset + 480, 0);
        emptyCount.writeInformation(page, subheaderOffset + 520, 0);
        emptyCount.writeInformation(page, subheaderOffset + 560, 0);
    }

    @Override
    long signature() {
        return SIGNATURE_SUBHEADER_COUNTS;
    }
}