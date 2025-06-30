package org.scharp.sas7bdat;

import org.scharp.sas7bdat.Sas7bdatWriter.Sas7bdatUnix64bitMetadata;

import java.util.HashMap;
import java.util.Map;

import static org.scharp.sas7bdat.WriteUtil.write2;

/** A wrapper for a collection of {@link ColumnTextSubheader} objects that abstracts their 32K limit. */
class ColumnText {

    private final Map<String, ColumnTextSubheader> textToSubheader;
    private final Sas7bdatUnix64bitMetadata metadata;

    private short subheaderIndex;
    private ColumnTextSubheader currentSubheader;

    ColumnText(Sas7bdatUnix64bitMetadata metadata) {
        textToSubheader = new HashMap<>();
        this.metadata = metadata;
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
            metadata.addSubheader(currentSubheader);

            // If we're near the end of the page, we create a subheader that will fill the remaining space.
            final int bytesOnPageForSubheader = metadata.currentMetadataPage.totalBytesRemainingForNewSubheader();
            final short maxSize;
            if (bytesOnPageForSubheader <= ColumnTextSubheader.MIN_SIZE) {
                // There's not enough space for a new ColumnTextSubheader on this page, so assume that it
                // will be moved to the next page.
                // SAS selects a smaller maxSize for these, even though ColumnTextSubheader.MAX_SIZE would be
                // more efficient.
                maxSize = 32676;
            } else {
                maxSize = (short) Math.min(ColumnTextSubheader.MAX_SIZE, bytesOnPageForSubheader);
            }

            // Allocate the new subheader.
            subheaderIndex++;
            currentSubheader = new ColumnTextSubheader(subheaderIndex, maxSize);

            // Add the string to the new subheader, which should be empty.
            boolean success = currentSubheader.add(text);
            assert success : "couldn't add text to an empty subheader";
        }

        // Track the subheader into which this string was inserted.
        textToSubheader.put(text, currentSubheader);
    }

    void noMoreText() {
        // Add the partially-filled ColumnTextSubheader to the metadata.
        metadata.addSubheader(currentSubheader);
        currentSubheader = null;
    }

    private ColumnTextSubheader getSubheaderForText(String text) {
        // Determine the subheader to which this text was added.
        ColumnTextSubheader subheader = textToSubheader.get(text);
        assert subheader != null : "looked for text that wasn't added  " + text;
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
            stringSubheaderIndex = columnTextSubheader.indexInPage();
            stringOffset = columnTextSubheader.offsetFromSignature(text);
            stringLength = ColumnTextSubheader.sizeof(text);
        }

        write2(page, offset, stringSubheaderIndex);
        write2(page, offset + 2, stringOffset); // the string's offset within the text subheader
        write2(page, offset + 4, stringLength); // the string's length
    }
}