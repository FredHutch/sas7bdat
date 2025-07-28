///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import java.util.HashMap;
import java.util.Map;

import static org.scharp.sas7bdat.WriteUtil.write2;

/** A wrapper for a collection of {@link ColumnTextSubheader} objects that abstracts their 32K limit. */
class ColumnText {

    private final Map<String, ColumnTextSubheader> textToSubheader;
    private final Sas7bdatPageLayout pageLayout;

    private short subheaderIndex;
    private ColumnTextSubheader currentSubheader;

    ColumnText(Sas7bdatPageLayout pageLayout) {
        textToSubheader = new HashMap<>();
        this.pageLayout = pageLayout;
        subheaderIndex = 0;

        currentSubheader = new ColumnTextSubheader(subheaderIndex, ColumnTextSubheader.MAX_SIZE);
    }

    void add(String text) {

        if (!ColumnTextSubheader.ADD_REDUNDANT_ENTRIES && textToSubheader.get(text) != null) {
            // SAS adds the same text redundantly.  We're not doing that and the text has already been added, so
            // there's nothing that needs to be done.
            return;
        }

        // Try to add the string to the current subheader.
        if (!currentSubheader.add(text)) {
            // There isn't enough space for the text in the current subheader.
            // We have to create a new subheader for it.

            // SAS appends a stylized padding block to every non-final text block subheader.
            // This padding block has a header that's 8 bytes long.
            // I don't think this is functionally significant.
            currentSubheader.padToMaxSize();

            // Now that the size of the current subheader is fixed, it can be added to the metadata.
            pageLayout.addSubheader(currentSubheader);

            // If we're near the end of the page, we create a subheader that will fill the remaining space.
            final int bytesOnPageForSubheader = pageLayout.currentMetadataPage.totalBytesRemainingForNewSubheader();
            final int minSizeOfColumnTextSubheaderWithText = ColumnTextSubheader.sizeOfSubheaderWithString(text);
            final short maxSize;
            if (bytesOnPageForSubheader <= minSizeOfColumnTextSubheaderWithText) {
                // There's not enough space for the new ColumnTextSubheader on this page, so assume that it
                // will be moved to the next page.
                //
                // Ideally, this would use a maxSize of ColumnTextSubheader.MAX_SIZE.  However, SAS selects a
                // smaller maxSize of 32676 for a ColumnTextSubheader that is the first subheader on a
                // page.  We follow what SAS does except in the rare case where "text" is so long that
                // such a ColumnTextSubheader couldn't hold it.
                maxSize = (short) Math.max(32676, minSizeOfColumnTextSubheaderWithText);
            } else {
                maxSize = (short) Math.min(ColumnTextSubheader.MAX_SIZE, bytesOnPageForSubheader);
            }

            // Allocate the new subheader.
            subheaderIndex++;
            currentSubheader = new ColumnTextSubheader(subheaderIndex, maxSize);

            // Add the string to the new subheader, which should be empty.
            boolean success = currentSubheader.add(text);
            assert success : "couldn't add text to an empty subheader";
            assert currentSubheader.size() == minSizeOfColumnTextSubheaderWithText : "calculated size incorrectly";
        }

        // Track the subheader into which this string was inserted.
        textToSubheader.put(text, currentSubheader);
    }

    void noMoreText() {
        // Add the partially-filled ColumnTextSubheader to the metadata.
        pageLayout.addSubheader(currentSubheader);
        currentSubheader = null;
    }

    private ColumnTextSubheader getSubheaderForText(String text) {
        // Determine the subheader to which this text was added.
        ColumnTextSubheader subheader = textToSubheader.get(text);
        assert subheader != null : "looked for text that wasn't added: \"" + text + "\"";
        return subheader;
    }

    /**
     * Write the location of the given text as a triple of two-byte values: index of ColumnTextSubheader, offset of text
     * from the signature, length of the text.
     *
     * @param page
     *     The array to write the text location to.
     * @param offset
     *     The offset within {@code page} to write the text location to.
     * @param text
     *     The text whose location should be added.  This must have been previously added.
     */
    void writeTextLocation(byte[] page, int offset, String text) {
        final short stringSubheaderIndex;
        final short stringOffset;
        final short stringLength;
        if (text.isEmpty()) {
            // SAS always puts three zeros for the empty string.
            stringSubheaderIndex = 0;
            stringOffset = 0;
            stringLength = 0;
        } else {
            ColumnTextSubheader columnTextSubheader = getSubheaderForText(text);
            stringSubheaderIndex = columnTextSubheader.columnTextSubheaderIndex();
            stringOffset = columnTextSubheader.offsetFromSignature(text);
            stringLength = ColumnTextSubheader.sizeof(text);
        }

        write2(page, offset, stringSubheaderIndex);
        write2(page, offset + 2, stringOffset); // the string's offset within the text subheader
        write2(page, offset + 4, stringLength); // the string's length
    }
}