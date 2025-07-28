///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.scharp.sas7bdat.WriteUtil.write4;
import static org.scharp.sas7bdat.WriteUtil.writeUtf8;

/**
 * A column text subheader holds all textual metadata that is associated with a dataset. This includes the dataset
 * label, creator proc, column names, column labels, and column format names.
 */
class ColumnTextSubheader extends VariableSizeSubheader {

    static private final int OFFSET_OF_FIRST_STRING = SIGNATURE_SIZE + PAYLOAD_DESCRIPTION_FIELD_SIZE;

    /**
     * A flag to indicate that the code should emulate the way SAS generates datasets, which is to allow the same text
     * to be added multiple times.
     */
    static final boolean ADD_REDUNDANT_ENTRIES = true;

    /**
     * The minimum size of a ColumnTextSubheader.  This includes the header and footer but no text.
     */
    static final short MIN_SIZE = VARIABLE_SUBHEADER_OVERHEAD;

    /**
     * The maximum size of a ColumnTextSubheader.
     * <p>
     * SAS limits each subheader to 32740 byte long, although the theoretical maximum is {@code Short.MAX_VALUE} rounded
     * down to the nearest 4 bytes.  It could be that it's conservatively accounting for 24 bytes needed to add to the
     * subheader index.
     */
    static final short MAX_SIZE = 32740;

    /**
     * A map of string within this ColumnTextSubheader to the offset of that string from the beginning of the subheader
     * (the start of the signature).
     */
    private final Map<String, Integer> stringOffsets;
    private final short columnTextSubheaderIndex;
    private final short maxSize;
    private int nextOffset;
    private int sizeOfPaddingBlockAtEnd;

    /**
     * Creates a new column text subheader.
     *
     * @param columnTextSubheaderIndex
     *     The index of this column text subheader within the array of all column text subheaders in the dataset. The
     *     first column text subheader index is 0.
     * @param maxSize
     *     The amount of space that this column text header should take up (in bytes). This is useful for
     *     troubleshooting when attempting to mimic a dataset that SAS created. It is also useful if the amount of space
     *     remaining on the page is known, and you want this subheader to fill the space.
     */
    ColumnTextSubheader(short columnTextSubheaderIndex, short maxSize) {
        assert 0 <= columnTextSubheaderIndex;
        assert MIN_SIZE < maxSize : "maxSize too small: " + maxSize;
        assert maxSize <= Short.MAX_VALUE : "maxSize too large: " + maxSize;
        assert maxSize % 4 == 0 : "maxSize " + maxSize + " is not a multiple of 4";

        this.columnTextSubheaderIndex = columnTextSubheaderIndex;
        this.maxSize = maxSize;
        stringOffsets = new LinkedHashMap<>(); // LinkedHashMap preserves order, making it easier to compare against what sas generates
        nextOffset = OFFSET_OF_FIRST_STRING;
        sizeOfPaddingBlockAtEnd = 0;
    }

    /**
     * Adds a string to this subheader.
     *
     * @param string
     *     The string to add. This must be fewer than {@code Short.MAX_VALUE} bytes when represented as UTF-8.
     *
     * @return {@code true}, if the string was added. {@code false} if there isn't enough space to hold the new string.
     */
    boolean add(String string) {
        assert sizeof(string) <= Short.MAX_VALUE : "string is too long to be addressable in a text reference";

        if (!ADD_REDUNDANT_ENTRIES && stringOffsets.containsKey(string)) {
            // We already have a place reserved for this string,
            // so there's no need to add it again.
            //
            // Note that SAS generates datasets with duplicate text.
            return true;
        }

        // Determine the offset for the next string if this one is added.
        // Offsets must be a multiple of four.
        int newNextOffset = WriteUtil.align(nextOffset + sizeof(string), 4);

        // Check to see if there's space for the new string.
        if (maxSize < newNextOffset + sizeOfPaddingBlockAtEnd + VARIABLE_SUBHEADER_OVERHEAD - SIGNATURE_SIZE - PAYLOAD_DESCRIPTION_FIELD_SIZE) {
            // There's not enough space for this string.
            return false;
        }

        // Reserve a place for this.
        stringOffsets.put(string, nextOffset);
        nextOffset = newNextOffset;
        return true;
    }

    /**
     * Adds padding to the end of this subheader's binary format so that its size exactly matches the {@code maxSize}
     * parameter that was passed into the constructor.
     */
    void padToMaxSize() {
        // SAS appends a stylized padding block to every non-final text block subheader.
        // This padding block has a header that's 8 bytes long.
        // I don't think this is functionally significant.
        sizeOfPaddingBlockAtEnd = maxSize - size();
    }

    /**
     * Gets the offset of the text as measured from the end of the signature, which is how the offsets are referenced
     * within sas7bdat datasets.
     * <p>
     * That is, even though the text begins 16 bytes after the subheader begins, the first entry is at offset 8, because
     * it is 8 bytes after the subheader's signature ends.
     * </p>
     *
     * @param string
     *     The string whose offset is desired.  This string must have been previously added to this column text
     *     subheader.
     *
     * @return the offset
     */
    short offsetFromSignature(String string) {
        Integer offset = stringOffsets.get(string);
        assert offset != null : string + " was not added to this ColumnTextSubheader";
        assert offset <= Short.MAX_VALUE : "offset exceeds what is addressable";
        assert SIGNATURE_SIZE + PAYLOAD_DESCRIPTION_FIELD_SIZE <= offset : "offset is too small";
        return (short) (offset - SIGNATURE_SIZE);
    }

    /**
     * Returns the size of a string, in bytes.
     *
     * @param string
     *     The string whose size is desired.
     *
     * @return The number of bytes which {@code string} occupies in this subheader.
     */
    static short sizeof(String string) {
        int stringSizeInBytes = string.getBytes(StandardCharsets.UTF_8).length;
        assert stringSizeInBytes <= Short.MAX_VALUE : "string is too long to be addressable in a text reference";
        return (short) stringSizeInBytes;
    }

    /**
     * Gets the index of this column text subheader within the dataset's array of column text subheaders. {@code 0} is
     * the first subheader index.
     *
     * @return This column text subheader's index.
     */
    short columnTextSubheaderIndex() {
        return columnTextSubheaderIndex;
    }

    /**
     * The number of bytes of data in this subheader without signature or FOOTER_PADDING.
     *
     * @return The number of bytes of data in this subheader
     */
    @Override
    int sizeOfData() {
        return nextOffset + sizeOfPaddingBlockAtEnd - SIGNATURE_SIZE;
    }

    @Override
    void writeVariableSizedPayload(byte[] page, int subheaderOffset) {

        for (Map.Entry<String, Integer> entry : stringOffsets.entrySet()) {
            final String string = entry.getKey();
            final Integer offsetWithinSubheader = entry.getValue();
            final int offsetOfStringInPage = subheaderOffset + offsetWithinSubheader;
            final int stringSize = sizeof(string);

            // Pad the string with 0 bytes until the next multiple of 4, like SAS does.
            final int length = WriteUtil.align(stringSize, 4);
            writeUtf8(page, offsetOfStringInPage, string, length, (byte) 0);
        }

        //
        // When there's another Text Column subheader which follows this one,
        // sas puts adds more padding between the last string (aligned to four bytes) and the
        // 12 byte footer of NUL bytes.  This seems to be a variable length block.  If it's at
        // least 8 bytes long, then first four bytes are the number 1 and the second four bytes
        // is a multiple of 4 in the range 12-252 and seems to be the length of this padding block,
        // including the two numbers but not including the standard 12 bytes at the end.
        // The rest of the space is usually 0 bytes, but sometimes other values are present.
        // Sometimes, the other values exactly match what was at the same offset on a previous
        // page, which makes me think that this it's uninitialized memory and the 8-byte header
        // is a way to declare the remaining space as garbage.
        //
        // I don't know if is part of the file format or if it's ignored by SAS.
        //
        if (8 <= sizeOfPaddingBlockAtEnd) {
            write4(page, subheaderOffset + nextOffset, 1);
            write4(page, subheaderOffset + nextOffset + 4, sizeOfPaddingBlockAtEnd);
        }
    }

    /**
     * Calculates the size of a {@code ColumnTextSubheader} that holds a string.
     *
     * @param string
     *     A string to consider added to the {@code ColumnTextSubheader}.
     *
     * @return The size of a {@code ColumnTextSubheader} that holds a string.
     */
    static int sizeOfSubheaderWithString(String string) {
        return WriteUtil.align(MIN_SIZE + sizeof(string), 4);
    }

    @Override
    long signature() {
        return SIGNATURE_COLUMN_TEXT;
    }
}