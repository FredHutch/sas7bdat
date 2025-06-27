package org.scharp.sas7bdat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.scharp.sas7bdat.WriteUtil.write2;
import static org.scharp.sas7bdat.WriteUtil.write4;
import static org.scharp.sas7bdat.WriteUtil.write8;
import static org.scharp.sas7bdat.WriteUtil.writeAscii;
import static org.scharp.sas7bdat.WriteUtil.writeUtf8;

public class Sas7bdatWriter implements AutoCloseable {

    // This can be anything, although it might need to be aligned.
    // sas chooses 0x10000 for small datasets and increments by 0x400 when more space is needed.
    private static final int MINIMUM_PAGE_SIZE = 0x10000;

    private static final int DATA_PAGE_HEADER_SIZE = 40;

    private static class PageSequenceGenerator {

        // The page sequence numbers are a mystery.  Somehow SAS knows they are out
        // of order if they increment by 1.  Depending on the higher bytes, the
        // least significant byte increments by a different pattern.
        //
        // Examples:
        //
        // For 0xE2677F??, it goes down by 1 four times, then up by 7.
        //   63,62,61,60, 67,66,65,64, 6B,6A,69,68, 6F, ...
        //
        // For 0xAB353E??, it goes up by one four times, then down by 7 % 16
        //  75,76,77, 70,71,72,73, 7C,7D,7E,7F, 78, ...
        //
        // For 0x3664FB??, it goes up by one, then down by 4
        //  5A,5B, 58,59, 5E,5F,  5C,5D,  52,53,  50,51,  56,57,  54,55, ...
        //
        // The pattern below starts with 0xF4A4????.
        private static final int[] pageSequenceNumbers = new int[] { //
            0x6, 0x7, //
            0x4, 0x5, //
            0x2, 0x3, //
            0x0, 0x1, //
            0xE, 0xF, //
            0xC, 0xD, //
            0xA, 0xB, //
            0x8, 0x9, //
        };

        int pageSequenceIndex;

        PageSequenceGenerator() {
            pageSequenceIndex = 0;
        }

        private static long pageSequence(int pageIndex) {
            return 0xFFFFFFFFL & (
                0xF4_A4_00_00L | // bits 16-31
                    ((0x0000FF00) & ((0xFF - (pageIndex / 256)) << 8)) | // bits 8-15
                    ((0x000000F0) & (0xF0 - ((pageIndex / 16) << 4))) |  // bits 4-7
                    ((0x0000000F) & (pageSequenceNumbers[pageIndex % 16]))); // bits 0-3
        }

        long initialPageSequence() {
            return pageSequence(0);
        }

        long currentPageSequence() {
            return pageSequence(pageSequenceIndex);
        }

        void incrementPageSequence() {
            pageSequenceIndex++;
            if (pageSequenceIndex > 0x7FFF) {
                throw new IllegalStateException("This code does not support more than " + 0x7FFF + " pages");
            }
        }
    }

    /** A collection of variables in a sas7bdat file that knows how variables are laid out */
    private static class Sas7bdatUnix64bitVariables {

        private static final byte[] MISSING_NUMERIC = { 0, 0, 0, 0, 0, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF };

        private final List<Variable> variables;
        private final int[] physicalOffsets;
        private final int rowLength;

        Sas7bdatUnix64bitVariables(List<Variable> variablesList) {
            variables = new ArrayList<>(variablesList);
            physicalOffsets = new int[variables.size()];

            // Calculate the physical offset of each variable.
            int rowOffset = 0;

            // sas generates datasets such that the numeric variables are
            // all first.  I suspect this is because SAS wants to place them according
            // to their natural alignment of 8 bytes without adding padding.  The
            // easiest way to do this is to make them all first.
            boolean hasNumericType = false;
            int i = 0;
            for (Variable variable : variables) {
                if (variable.type() == VariableType.NUMERIC) {
                    hasNumericType = true;

                    physicalOffsets[i] = rowOffset;

                    // Advance to the offset of the next variable.
                    rowOffset += variable.length();
                }
                i++;
            }
            i = 0;
            for (Variable variable : variables) {
                if (variable.type() == VariableType.CHARACTER) {
                    physicalOffsets[i] = rowOffset;

                    // Advance to the offset of the next variable.
                    rowOffset += variable.length();
                }
                i++;
            }

            // Make sure that padding is added after the last variable if the first variable needs it.
            // If there's any numeric variable, then a numeric variable is given first, and it should be aligned
            // to an 8-byte boundary.
            if (hasNumericType) {
                rowOffset = Sas7bdatUnix64bitPage.align(rowOffset, 8);
            }

            rowLength = rowOffset;
        }

        void writeObservation(byte[] buffer, int offsetOfObservation, List<Object> observation) {

            assert observation.size() == totalVariables(); // TODO: throw an exception on bad input

            for (int i = 0; i < physicalOffsets.length; i++) {
                final Variable variable = variables.get(i);
                final Object value = observation.get(i);

                // TODO: strict type checking for observation
                final byte[] valueBytes;
                if (VariableType.CHARACTER == variable.type()) {
                    // TODO: limit valueBytes to the size of the variable
                    valueBytes = value.toString().getBytes(StandardCharsets.UTF_8);
                } else {
                    // NOTE: datasets which SAS generates seem to keep numeric values aligned on byte offsets that
                    // are multiples of 8.  Sometimes it physically re-organizes the integer variables to be
                    // consecutive.  Sometimes, it adds padding between the observations.

                    if (value instanceof Number) {
                        long valueBits = Double.doubleToRawLongBits(((Number) value).doubleValue());
                        valueBytes = new byte[] { //
                            (byte) (valueBits >> 0), //
                            (byte) (valueBits >> 8), //
                            (byte) (valueBits >> 16), //
                            (byte) (valueBits >> 24), //
                            (byte) (valueBits >> 32), //
                            (byte) (valueBits >> 40), //
                            (byte) (valueBits >> 48), //
                            (byte) (valueBits >> 56), //
                        };
                    } else {
                        valueBytes = MISSING_NUMERIC;
                    }
                }

                final int offsetOfValue = offsetOfObservation + physicalOffsets[i];
                assert offsetOfValue + variable.length() < buffer.length;
                assert valueBytes.length <= variable.length();

                // Copy the data
                System.arraycopy(valueBytes, 0, buffer, offsetOfValue, valueBytes.length);

                // Pad the data
                Arrays.fill(buffer, offsetOfValue + valueBytes.length, offsetOfValue + variable.length(), (byte) ' ');
            }
        }

        int rowLength() {
            return rowLength;
        }

        int totalVariables() {
            return physicalOffsets.length;
        }
    }

    private static class Sas7bdatUnix64bitHeader {

        private static final byte ALIGNMENT_OFFSET_0 = 0x22;
        private static final byte ALIGNMENT_OFFSET_4 = 0x33;

        private static final byte BIG_ENDIAN = 0x00;
        private static final byte LITTLE_ENDIAN = 0x01;

        private static final byte OS_UNIX = '1';
        private static final byte OS_WINDOWS = '2';

        private static final byte ENCODING_UTF8 = 20;

        /**
         * The magic number for SAS7BDAT files (first 32 bytes)
         */
        private final byte[] magicNumber = new byte[] { //
            0x00, 0x00, 0x00, 0x00, //
            0x00, 0x00, 0x00, 0x00, //
            0x00, 0x00, 0x00, 0x00, //
            (byte) 0xC2, (byte) 0xEA, (byte) 0x81, 0x60, //
            (byte) 0xB3, 0x14, 0x11, (byte) 0xCF, //
            (byte) 0xBD, (byte) 0x92, (byte) 0x08, 0x00, //
            (byte) 0x09, (byte) 0xC7, 0x31, (byte) 0x8C, //
            (byte) 0x18, (byte) 0x1F, (byte) 0x10, 0x11, //
        };

        /**
         * Converts a LocalDateTime to a SAS Epoch time.
         *
         * <p>
         * SAS Epoch time is seconds since 1960 in the local time zone.
         *
         * @param localDateTime
         *     The local time.
         *
         * @return the corresponding time since the SAS Epoch.
         */
        private static double toSasEpoch(LocalDateTime localDateTime) {
            // To correctly determine the number of seconds between the SAS epoch and the given
            // LocalDateTime, we must temporarily consider the time zone, even though SAS times don't include
            // it.  The SAS Epoch is in standard time, but if we ask a LocalDateTime how many seconds have
            // passed between the SAS Epoch and a LocalDateTime that falls into Daylight Saving Time,
            // it would incorrectly include the extra hour for Daylight Saving Time because it looks like that
            // time has passed, but in fact the number of seconds that have actually passed is an hour less.
            // ZonedDateTime computes the difference correctly, with no time passing for the Daylight Saving Time
            // adjustment.
            ZoneId systemDefaultZone = ZoneId.systemDefault();
            final ZonedDateTime sasEpoch = ZonedDateTime.of(1960, 1, 1, 0, 0, 0, 0, systemDefaultZone);
            final ZonedDateTime zonedDateTime = localDateTime.atZone(systemDefaultZone);
            final long secondsSince1960 = sasEpoch.until(zonedDateTime, ChronoUnit.SECONDS);
            return secondsSince1960;
        }

        final int headerSize;
        final int pageSize;
        final long initialPageSequenceNumber;
        final String datasetName;
        final LocalDateTime creationDate;
        final int totalPages;

        Sas7bdatUnix64bitHeader(PageSequenceGenerator pageSequenceGenerator, int headerSize, int pageSize,
            String datasetName, LocalDateTime creationDate, int totalPages) {
            this.headerSize = headerSize;
            this.pageSize = pageSize;
            this.initialPageSequenceNumber = pageSequenceGenerator.initialPageSequence();
            this.datasetName = datasetName;
            this.creationDate = creationDate;
            this.totalPages = totalPages;
        }

        void write(byte[] data) {
            final byte doubleAlignmentOffsetByte = ALIGNMENT_OFFSET_4; // a2 in sas7bdat.pdf
            final byte intAlignmentOffsetByte = ALIGNMENT_OFFSET_4; // a1 in sas7bdat.pdf

            final int doubleAlignmentOffset = doubleAlignmentOffsetByte == ALIGNMENT_OFFSET_4 ? 4 : 0;
            final byte intAlignmentOffset = intAlignmentOffsetByte == ALIGNMENT_OFFSET_4 ? 4 : 0;

            // Set the magic number in the first 32 bytes
            System.arraycopy(magicNumber, 0, data, 0, magicNumber.length);

            data[32] = doubleAlignmentOffsetByte; // if (byte==x33) a2=4 else a2=0 . u64 is true if a2=4 (unix 64 bit format).
            data[33] = 0x22; // unknown purpose
            data[34] = 0x00; // unknown purpose
            data[35] = intAlignmentOffsetByte; // if (byte==x33) a1=4 else a1=0

            data[36] = 0x33; // unknown purpose
            data[37] = LITTLE_ENDIAN;
            data[38] = 0x02; // unknown purpose
            data[39] = OS_UNIX;

            data[40] = 0x01; // unknown purpose
            data[41] = 0x00; // unknown purpose
            data[42] = 0x00; // unknown purpose
            data[43] = 0x00; // unknown purpose
            data[44] = 0x00; // unknown purpose
            data[45] = 0x00; // unknown purpose
            data[46] = 0x00; // unknown purpose
            data[47] = ENCODING_UTF8; // sas produces 00, sas_u8 produces 14

            data[48] = 0x00; // unknown purpose
            data[49] = 0x00; // unknown purpose
            data[50] = 0x03; // unknown purpose
            data[51] = 0x01; // unknown purpose
            data[52] = 0x18; // unknown purpose
            data[53] = 0x1F; // unknown purpose
            data[54] = 0x10; // unknown purpose
            data[55] = 0x11; // unknown purpose

            data[56] = 0x33; // unknown purpose
            data[57] = 0x22; // unknown purpose
            data[58] = 0x00; // unknown purpose
            data[59] = 0x33; // unknown purpose
            data[60] = 0x33; // unknown purpose
            data[61] = 0x01; // unknown purpose
            data[62] = 0x02; // unknown purpose
            data[63] = 0x31; // unknown purpose

            data[64] = 0x01; // unknown purpose
            data[65] = 0x33; // unknown purpose
            data[66] = 0x01; // unknown purpose
            data[67] = 0x23; // unknown purpose
            data[68] = 0x33; // unknown purpose
            data[69] = 0x00; // unknown purpose
            data[70] = ENCODING_UTF8; // sas writes 0x1D, sas_u8 writes 0x14
            data[71] = ENCODING_UTF8; // sas writes 0x00, sas_u8 writes 0x14

            data[72] = 0x00; // unknown purpose
            data[73] = 0x20; // unknown purpose
            data[74] = 0x03; // unknown purpose
            data[75] = 0x01; // unknown purpose

            data[76] = 0x00; // unknown purpose
            data[77] = 0x00; // unknown purpose
            data[78] = 0x00; // unknown purpose
            data[79] = 0x00; // unknown purpose

            data[80] = 0x00; // unknown purpose
            data[81] = 0x00; // unknown purpose
            data[82] = 0x00; // unknown purpose
            data[83] = 0x00; // unknown purpose

            // File type
            writeAscii(data, 84, "SAS FILE", 8);

            // dataset name or file label (64 bytes)
            // This should match the file's base name.
            assert datasetName.length() <= 64;
            writeAscii(data, 92, datasetName, 64);

            // file type
            writeAscii(data, 156, "DATA", 8);

            // created date
            double sasDate = toSasEpoch(creationDate);
            long ieee754SasDate = Double.doubleToRawLongBits(sasDate);

            write8(data, 164 + doubleAlignmentOffset, ieee754SasDate);

            // modified date
            write8(data, 172 + doubleAlignmentOffset, ieee754SasDate);

            write8(data, 180 + doubleAlignmentOffset, 0xC0_DC_20_00_00_00_00_00L); // unknown purpose
            write8(data, 188 + doubleAlignmentOffset, 0xC0_DC_20_00_00_00_00_00L); // unknown purpose

            // length of header.
            // This may be smaller than the normal page size, but sas always uses the same size.
            write4(data, 196 + doubleAlignmentOffset, headerSize);

            // page size
            write4(data, 200 + doubleAlignmentOffset, headerSize);

            // page count (would be write4 for 32-bit sas7bdat)
            write8(data, 200 + doubleAlignmentOffset + intAlignmentOffset, totalPages);

            // SAS version
            writeAscii(data, 216 + doubleAlignmentOffset + intAlignmentOffset, "9.0401M2", 8);

            // Server type.  Rave's SAS On Demand uses "X64_ESRV08".
            // New files created from cronsrv uses "Linux" and pads with null bytes (kernel name)
            // /trials/lab/data/explore_hsv2.sas7bdat uses "SunOS" and pads with spaces.
            writeAscii(data, 224 + doubleAlignmentOffset + intAlignmentOffset, "Linux", 16);

            // OS Version
            // SAS On Demand uses NULL bytes.
            // Files created from cronsrv use "4.4.104-18.44-de" (probably kernel release 4.4.104-18.44-default)
            writeAscii(data, 240 + doubleAlignmentOffset + intAlignmentOffset, "4.4.104-18.44", 16);

            // OS Maker/Version
            // SAS On Demand uses NULL bytes.
            // Files created from cronsrv use null bytes.
            writeAscii(data, 256 + doubleAlignmentOffset + intAlignmentOffset, "", 16);

            // OS name
            // SAS On Demand uses NULL bytes.
            // Files created from cronsrv use "x86_64" padded with null bytes.
            writeAscii(data, 272 + doubleAlignmentOffset + intAlignmentOffset, "x86_64", 16);

            // pattern1 and pattern2 have something to do with passwords, as changing their
            // value results in an "Invalid or missing READ password" error.  When SAS
            // generates the dataset, pattern1 is always the lowest four bytes of the
            // date, a poor implementation of a nonce.  pattern2 may be a function of
            // pattern1 and the password.  Since that function is unknown, instead of
            // using the date for pattern1, this logic uses hard-coded values for pattern1
            // and pattern2 that were taken from a SAS dataset that has no password.
            final int pattern1 = 0xD4_C8_C0_38; // should be "(int) ieee754SasDate";
            final int pattern2 = 0xB1_A7_8E_74;
            write4(data, 288 + doubleAlignmentOffset + intAlignmentOffset, pattern1);
            write4(data, 292 + doubleAlignmentOffset + intAlignmentOffset, pattern2);
            write4(data, 296 + doubleAlignmentOffset + intAlignmentOffset, pattern2);
            write4(data, 300 + doubleAlignmentOffset + intAlignmentOffset, pattern2);

            // 16 zero bytes
            write8(data, 304 + doubleAlignmentOffset + intAlignmentOffset, 0);
            write8(data, 312 + doubleAlignmentOffset + intAlignmentOffset, 0);

            // This seems to be a kind of sequence number.
            write8(data, 320 + doubleAlignmentOffset + intAlignmentOffset, initialPageSequenceNumber);

            // A third timestamp.
            write8(data, 328 + doubleAlignmentOffset + intAlignmentOffset, ieee754SasDate);
        }
    }

    static final byte COMPRESSION_UNCOMPRESSED = 0x00;
    static final byte COMPRESSION_TRUNCATED = 0x01;
    static final byte COMPRESSION_RLE_WITH_CONTROL_BYTE = 0x04;

    /**
     * Row Size, Column Size, Subheader Counts, Column Format and Label, in Uncompressed file
     */
    static final byte SUBHEADER_TYPE_A = 0x00;

    /**
     * Column Text, Column Names, Column Attributes, Column List, all subheaders (including row data), in Compressed
     * file
     */
    static final byte SUBHEADER_TYPE_B = 0x01;

    static final byte COLUMN_TYPE_NUMERIC = 1;
    static final byte COLUMN_TYPE_CHARACTER = 2;

    /** A wrapper for a collection of {@link ColumnTextSubheader} objects that abstracts their 32K limit. */
    static class ColumnText {

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
                if (currentSubheader.size() < currentSubheader.maxSize) {
                    currentSubheader.sizeOfPaddingBlockAtEnd = currentSubheader.maxSize - currentSubheader.size();
                }

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

        ColumnTextSubheader getSubheaderForText(String text) {
            // Determine the subheader to which this text was added.
            ColumnTextSubheader subheader = textToSubheader.get(text);
            assert subheader != null : "looked for text that wasn't added  " + text;
            return subheader;
        }

        short offsetFromSignature(String text) {
            // Determine the subheader to which this text was added.
            ColumnTextSubheader subheader = textToSubheader.get(text);
            assert subheader != null : "looked for text that wasn't added  " + text;

            // Get the text from the subheader
            return subheader.offsetFromSignature(text);
        }

        /**
         * Write the location of the given text as a triple of two-byte values: index of ColumnTextSubheader, offset of
         * text from the signature, length of the text.
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
                stringLength = columnTextSubheader.sizeof(text);
            }

            write2(page, offset + 0, stringSubheaderIndex);
            write2(page, offset + 2, stringOffset); // the string's offset within the text subheader
            write2(page, offset + 4, stringLength); // the string's length
        }
    }

    /**
     * A column text subheader is a sort of interned string pool for column data.
     */
    static class ColumnTextSubheader extends Subheader {

        /**
         * A flag to indicate that the code should emulate the way SAS generates datasets, which is to allow the same
         * text to be added multiple times.
         */
        private static final boolean ADD_REDUNDANT_ENTRIES = true;

        static final int OFFSET_OF_FIRST_STRING = 16; // 8 byte signature + 2 byte size + 6 bytes of padding
        static final int FOOTER_PADDING = 12;

        /**
         * The minimum size of a ColumnTextSubheader.  This includes the header and footer but no text.
         */
        static final short MIN_SIZE = OFFSET_OF_FIRST_STRING + FOOTER_PADDING;

        /**
         * The maximum size of a ColumnTextSubheader.
         * <p>
         * SAS limits each subheader to 32740 byte long, although the theoretical maximum is Short.MAX_VALUE rounded
         * down to the nearest 4 bytes.  It could be that it's conservatively accounting for 24 bytes needed to add to
         * the subheader index.
         */
        static final short MAX_SIZE = 32740;

        final Map<String, Integer> stringOffsets;
        final short indexInPage;
        final short maxSize;
        int nextOffset;
        int sizeOfPaddingBlockAtEnd;

        /**
         * Creates a new column text subheader.
         *
         * @param indexInPage
         *     Where in the page this column text header will be placed.
         * @param maxSize
         *     The amount of space that this column text header should take up (in bytes). This is useful for
         *     troubleshooting when attempting to mimic a dataset that SAS created. It is also useful if the amount of
         *     space remaining on the page is known, and you want this subheader to fill the space.
         */
        ColumnTextSubheader(short indexInPage, short maxSize) {
            assert 0 <= indexInPage;
            assert MIN_SIZE < maxSize : "maxSize too small: " + maxSize;
            assert maxSize <= Short.MAX_VALUE : "maxSize too large: " + maxSize;
            assert maxSize % 4 == 0 : "maxSize " + maxSize + " is not a multiple of 4";

            this.indexInPage = indexInPage;
            this.maxSize = maxSize;
            stringOffsets = new LinkedHashMap<>(); // HACK: LinkedHashMap preserves order, making it easier to compare against what sas generates
            nextOffset = OFFSET_OF_FIRST_STRING;
            sizeOfPaddingBlockAtEnd = 0;
        }

        /**
         * Adds a string to this subheader.
         *
         * @param string
         *     The string to add.
         *
         * @return {@code true}, if the string was added. {@code false} if there wasn't enough space to hold the new
         *     string.
         */
        boolean add(String string) {
            assert string.length() <= Short.MAX_VALUE : string + " is too long to be addressable";

            if (!ADD_REDUNDANT_ENTRIES && stringOffsets.containsKey(string)) {
                // We already have a place reserved for this string,
                // so there's no need to add it again.
                //
                // Note that SAS generates datasets with duplicate text.
                return true;
            }

            // Determine the offset for the next string if this one is added.
            // Offsets must be a multiple of four.
            int newNextOffset = Sas7bdatUnix64bitPage.align(nextOffset + sizeof(string), 4);

            // Check to see if there's space for the new string.
            if (maxSize < newNextOffset + sizeOfPaddingBlockAtEnd + FOOTER_PADDING) {
                // There's not enough space for this string.
                return false;
            }

            // Reserve a place for this.
            stringOffsets.put(string, nextOffset);
            nextOffset = newNextOffset;
            return true;
        }

        /**
         * Gets the offset of the text as measured from the end of the signature, which is how the offsets are
         * referenced within sas7bdat datasets.
         * <p>
         * That is, even though the text begins 16 bytes after the subheader begins, the first entry is at offset 8,
         * because it is 8 bytes after the subheader's signature ends.
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
            assert stringSizeInBytes <= Short.MAX_VALUE : string + " is too long to exist";
            return (short) stringSizeInBytes;
        }

        short indexInPage() {
            return indexInPage;
        }

        @Override
        int size() {
            int size = nextOffset + sizeOfPaddingBlockAtEnd + FOOTER_PADDING;
            assert size <= maxSize : "wrote too much";
            return size;
        }

        /**
         * The number of bytes of data in this subheader without signature or FOOTER_PADDING.
         *
         * @return The number of bytes of data in this subheader
         */
        int sizeOfData() {
            return nextOffset + sizeOfPaddingBlockAtEnd - SIGNATURE_SIZE;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {

            write8(page, subheaderOffset, SIGNATURE_COLUMN_TEXT); // signature
            write8(page, subheaderOffset + 8, sizeOfData()); // amount of data

            write2(page, subheaderOffset + 16, (short) 0); // unknown, maybe padding
            write2(page, subheaderOffset + 18, (short) 0); // unknown, maybe padding

            for (Map.Entry<String, Integer> entry : stringOffsets.entrySet()) {
                final String string = entry.getKey();
                final Integer offsetWithinSubheader = entry.getValue();
                final int offsetOfStringInPage = subheaderOffset + offsetWithinSubheader;
                final int stringSize = sizeof(string);

                // Pad the string with 0 bytes until the next multiple of 4, like SAS does.
                final int length = Sas7bdatUnix64bitPage.align(stringSize, 4);
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

            // There is 12 bytes of padding at the end.
            write4(page, subheaderOffset + nextOffset + sizeOfPaddingBlockAtEnd, 0);
            write8(page, subheaderOffset + nextOffset + sizeOfPaddingBlockAtEnd + 4, 0);
        }

        @Override
        byte typeCode() {
            return SUBHEADER_TYPE_B;
        }

        @Override
        byte compressionCode() {
            return COMPRESSION_UNCOMPRESSED;
        }
    }

    /**
     * A column format subheader contains references to the column's format and label. There is one of these per column
     * in the dataset.
     */
    static class ColumnFormatSubheader extends Subheader {
        final Variable variable;
        final ColumnText columnText;

        ColumnFormatSubheader(Variable variable, ColumnText columnText) {
            this.variable = variable;
            this.columnText = columnText;
        }

        @Override
        int size() {
            return 64;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {
            write8(page, subheaderOffset, SIGNATURE_COLUMN_FORMAT); // signature
            write8(page, subheaderOffset + 8, 0); // unknown, maybe padding
            write8(page, subheaderOffset + 16, 0); // unknown, maybe padding

            write2(page, subheaderOffset + 24, (short) variable.outputFormat().width());
            write2(page, subheaderOffset + 26, (short) variable.outputFormat().numberOfDigits());
            write2(page, subheaderOffset + 28, (short) variable.inputFormat().width());
            write2(page, subheaderOffset + 30, (short) variable.inputFormat().numberOfDigits());

            write8(page, subheaderOffset + 32, 0); // unknown, maybe padding

            // Write the variable's input format offset/length.
            columnText.writeTextLocation(page, subheaderOffset + 40, variable.inputFormat().name());

            // Write the variable's output format offset/length.
            columnText.writeTextLocation(page, subheaderOffset + 46, variable.outputFormat().name());

            // Write the variable's label offset/length.
            columnText.writeTextLocation(page, subheaderOffset + 52, variable.label());

            write2(page, subheaderOffset + 58, (short) 0); // unknown
            write2(page, subheaderOffset + 60, (short) 0); // unknown
            write2(page, subheaderOffset + 62, (short) 0); // unknown
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

    /**
     * A subheader that contains some attributes for all columns
     */
    static class ColumnAttributesSubheader extends Subheader {

        /** The number of bytes of blank data at the end of a subheader */
        private static final int FOOTER_PADDING = 12;

        /** The number of bytes of each column entry in a subheader */
        private static final int SIZE_OF_ENTRY = 16;

        /** The byte offset of the data for the first column */
        private static final int OFFSET_OF_FIRST_ENTRY = 16;

        /**
         * The number of bytes required to have a subheader with a single variable.
         */
        final static int MIN_SIZE = OFFSET_OF_FIRST_ENTRY + SIZE_OF_ENTRY + FOOTER_PADDING;

        final int totalVariablesInSubheader;
        final List<Variable> variables;
        final int[] physicalOffsets;

        ColumnAttributesSubheader(Sas7bdatUnix64bitVariables variables, int offset, int maxLength) {
            // Determine how many variables, starting at offset, this subheader will hold.
            assert offset < variables.totalVariables() : "maxLength is larger than the number of variables";
            assert 0 < maxLength : "maxLength isn't positive: " + maxLength;
            assert MIN_SIZE <= maxLength : "maxLength is too small: " + maxLength;
            assert maxLength <= Short.MAX_VALUE : "maxLength is too large: " + maxLength;

            final int variablesRemaining = variables.totalVariables() - offset;
            final int variablesInMaxLength = (maxLength - (OFFSET_OF_FIRST_ENTRY + FOOTER_PADDING)) / SIZE_OF_ENTRY;
            totalVariablesInSubheader = Math.min(variablesRemaining, variablesInMaxLength);

            // Copy the variables and their physical offsets.
            int limit = offset + totalVariablesInSubheader;
            this.variables = variables.variables.subList(offset, limit);
            physicalOffsets = Arrays.copyOfRange(variables.physicalOffsets, offset, limit);
        }

        /**
         * The number of bytes of data in this subheader without signature or FOOTER_PADDING.
         *
         * @return The number of bytes of data in this subheader
         */
        int sizeOfData() {
            return OFFSET_OF_FIRST_ENTRY + variables.size() * SIZE_OF_ENTRY - SIGNATURE_SIZE;
        }

        @Override
        int size() {
            return OFFSET_OF_FIRST_ENTRY + variables.size() * SIZE_OF_ENTRY + FOOTER_PADDING;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {
            write8(page, subheaderOffset, SIGNATURE_COLUMN_ATTRS); // signature

            int lengthInSubheader = sizeOfData();
            assert lengthInSubheader <= Short.MAX_VALUE;
            write8(page, subheaderOffset + 8, lengthInSubheader);

            int offsetWithinSubheader = OFFSET_OF_FIRST_ENTRY;
            int i = 0;
            for (Variable variable : variables) {
                // offset of variable in bytes when in data row
                write8(page, subheaderOffset + offsetWithinSubheader, physicalOffsets[i]);

                // column width
                write4(page, subheaderOffset + offsetWithinSubheader + 8, variable.length());

                // name flag
                short nameFlag;
                if (!variable.name().matches("[A-Za-z]\\w*")) {
                    nameFlag = 0x0C00; // not a simple name; must be quoted as a name literal
                } else if (variable.name().length() <= 8) {
                    nameFlag = 0x0400;
                } else {
                    nameFlag = 0x0800;
                }
                write2(page, subheaderOffset + offsetWithinSubheader + 12, nameFlag);

                // column type
                write2(page, subheaderOffset + offsetWithinSubheader + 14,
                    variable.type() == VariableType.NUMERIC ? COLUMN_TYPE_NUMERIC : COLUMN_TYPE_CHARACTER);

                offsetWithinSubheader += SIZE_OF_ENTRY;
                i++;
            }

            // There is some padding at the end.
            write4(page, subheaderOffset + offsetWithinSubheader, 0);
            write8(page, subheaderOffset + offsetWithinSubheader + 4, 0);

            assert size() == offsetWithinSubheader + FOOTER_PADDING;
        }

        @Override
        byte typeCode() {
            return SUBHEADER_TYPE_B;
        }

        @Override
        byte compressionCode() {
            return COMPRESSION_UNCOMPRESSED;
        }
    }

    /**
     * A subheader that contains the names of columns
     */
    static class ColumnNameSubheader extends Subheader {

        /**
         * No subheader can be larger than Short.MAX_VALUE, so we only have room for (Short.MAX_VALUE - 28) / 8 = 4092
         * variables in each one.  However, datasets which SAS creates puts a maximum of 4089 in each subheader.
         */
        static final int MAX_VARIABLES_PER_SUBHEADER = 4089;

        final List<Variable> variables;
        final ColumnText columnText;

        ColumnNameSubheader(List<Variable> variables, int variablesOffset, ColumnText columnText) {
            // Determine how many variables, starting at offset, this subheader will hold.
            assert variablesOffset < variables.size();
            int variablesRemaining = variables.size() - variablesOffset;
            int totalVariablesInSubheader = Math.min(variablesRemaining, MAX_VARIABLES_PER_SUBHEADER);

            // Copy the variables
            int limit = variablesOffset + totalVariablesInSubheader;
            this.variables = variables.subList(variablesOffset, limit);

            this.columnText = columnText;

            assert size() <= Short.MAX_VALUE : "Too many variables in ColumnNameSubheader";
        }

        @Override
        int size() {
            return variables.size() * 8 + 28;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {
            write8(page, subheaderOffset, SIGNATURE_COLUMN_NAME); // signature

            write8(page, subheaderOffset + 8, (short) (size() - 20)); // remaining bytes in subheader

            int offsetWithinSubheader = 16;
            for (Variable variable : variables) {
                // Locate the text subheader that has this variable's name.
                final String variableName = variable.name();

                // subheader index/offset/length of name.
                columnText.writeTextLocation(page, subheaderOffset + offsetWithinSubheader, variableName);

                // padding
                write2(page, subheaderOffset + offsetWithinSubheader + 6, (short) 0);

                offsetWithinSubheader += 8;
            }

            // There is some padding at the end.
            assert size() == offsetWithinSubheader + 12;
            write4(page, subheaderOffset + offsetWithinSubheader, 0);
            write8(page, subheaderOffset + offsetWithinSubheader + 4, 0);
        }

        @Override
        byte typeCode() {
            return SUBHEADER_TYPE_B;
        }

        @Override
        byte compressionCode() {
            return COMPRESSION_UNCOMPRESSED;
        }
    }

    /**
     * A subheader that contains some additional information about the columns.
     * <p>
     * sas7bdat.pdf calls this "column list", but it really corresponds to something that's a superset of columns.
     */
    static class ColumnListSubheader extends Subheader {
        /**
         * No subheader can be larger than Short.MAX_VALUE, so we only have room for (Short.MAX_VALUE - 50) / 2 = 16358
         * columns in each one.  However, SAS never creates datasets with a size larger than 32740 bytes, which is 16345
         * columns.
         */
        static final int MAX_COLUMNS_PER_SUBHEADER = 16345;

        private final static int FOOTER_PADDING = 12;

        private final static int OFFSET_OF_FIRST_COLUMN = 38;

        final int totalVariables;
        final int totalColumns;

        ColumnListSubheader(Sas7bdatUnix64bitVariables variables, int variablesOffset) {
            // Determine how many variables, starting at offset, this subheader will hold.
            assert variablesOffset < variables.totalVariables();
            int variablesRemaining = variables.totalVariables() - variablesOffset;
            int totalVariablesInSubheader = Math.min(variablesRemaining, MAX_COLUMNS_PER_SUBHEADER);

            totalVariables = totalVariablesInSubheader;

            // To make it easier match up with datasets created by sas,
            // we allow for adding extra columns so that at the size of
            // this subheader matches the size generated by SAS.
            //
            // The idea is for a programmer to manually change the 0 to some
            // other number to facilitate troubleshooting problems with sas7bdat generation.
            // Doing so can make this library create a sas7bdat with the same offsets/sizes
            // that sas would have generated, which can make seeing difference easier.
            //
            // Setting totalColumns to a number that is much larger than totalVariables
            // causes sas to crash when reading the sas7bdat.
            totalColumns = totalVariables + 0;
        }

        /**
         * The number of bytes of data in this subheader without signature or FOOTER_PADDING.
         *
         * @return The number of bytes of data in this subheader
         */
        int sizeOfData() {
            return totalColumns * 2 + OFFSET_OF_FIRST_COLUMN - SIGNATURE_SIZE;
        }

        @Override
        int size() {
            return totalColumns * 2 + OFFSET_OF_FIRST_COLUMN + FOOTER_PADDING;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {
            write8(page, subheaderOffset, SIGNATURE_COLUMN_LIST); // signature

            write2(page, subheaderOffset + 8, (short) sizeOfData());
            write2(page, subheaderOffset + 10, (short) 0x7FC8); // unknown
            write4(page, subheaderOffset + 12, 0x00); // unknown

            write8(page, subheaderOffset + 16, totalColumns * 2 + 22); // length remaining in subheader??

            write2(page, subheaderOffset + 24, (short) totalVariables);
            write2(page, subheaderOffset + 26, (short) totalColumns); // length of list
            write2(page, subheaderOffset + 28, (short) 1); // unknown
            write2(page, subheaderOffset + 30, (short) totalVariables); // unknown

            write2(page, subheaderOffset + 32, (short) 0); // unknown
            write2(page, subheaderOffset + 34, (short) 0); // unknown
            write2(page, subheaderOffset + 36, (short) 0); // unknown

            // column list values
            //
            // The purpose of this is unknown, but the values range from
            // -totalColumns to totalColumns and the absolute value of each non-zero
            // value must be unique or else SAS will treat the SAS7BDAT as malformed.
            // Therefore, it seems to be an ordering of columns with an additional
            // flag of boolean information that is a sign.
            //
            // The 0 might be a kind of flag, applied to either the variable which precedes or follows it.
            //
            // I have confirmed that this has nothing to do with the order in which variables are
            // physically ordered in the data section.
            //
            int offsetFromSubheaderStart = OFFSET_OF_FIRST_COLUMN;
            for (int i = 0; i < totalColumns; i++) {
                final short value;
                if (i < totalVariables) {
                    value = (short) (i + 1);
                } else {
                    value = (short) 0;
                }
                write2(page, subheaderOffset + offsetFromSubheaderStart, value);

                offsetFromSubheaderStart += 2;
            }

            // There is some padding at the end.
            write4(page, subheaderOffset + offsetFromSubheaderStart, 0);
            write8(page, subheaderOffset + offsetFromSubheaderStart + 4, 0);
        }

        @Override
        byte typeCode() {
            return SUBHEADER_TYPE_B;
        }

        @Override
        byte compressionCode() {
            return COMPRESSION_UNCOMPRESSED;
        }
    }

    /**
     * A subheader that contains some additional information about each subheader on the page.
     */
    static class SubheaderCountsSubheader extends Subheader {
        /**
         * The number of bytes in a SubheaderCountsSubheader.
         */
        static final int PAGE_SIZE = 600;

        final Collection<Variable> variables;
        final Sas7bdatUnix64bitMetadata metadata;

        SubheaderCountsSubheader(Collection<Variable> variables, Sas7bdatUnix64bitMetadata metadata) {
            this.variables = variables;
            this.metadata = metadata; // this is filled in later by the caller
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
                for (Subheader subheader : metadata.subheaders) {
                    short subheaderPageNumber = metadata.subheaderLocations.get(subheader).shortValue();
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
            int maxTextBlockSize = 0;
            for (Subheader subheader : metadata.subheaders) {
                if (subheader instanceof ColumnTextSubheader) {
                    maxTextBlockSize = Math.max(maxTextBlockSize, ((ColumnTextSubheader) subheader).sizeOfData());
                } else if (subheader instanceof ColumnAttributesSubheader) {
                    maxTextBlockSize = Math.max(maxTextBlockSize, ((ColumnAttributesSubheader) subheader).sizeOfData());
                }
            }
            write8(page, subheaderOffset + 8, maxTextBlockSize);

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

            writeSubheaderInformation(page, subheaderOffset + 120, ColumnAttributesSubheader.class,
                SIGNATURE_COLUMN_ATTRS);

            writeSubheaderInformation(page, subheaderOffset + 160, ColumnTextSubheader.class,
                SIGNATURE_COLUMN_TEXT);

            writeSubheaderInformation(page, subheaderOffset + 200, ColumnNameSubheader.class,
                SIGNATURE_COLUMN_NAME);

            writeSubheaderInformation(page, subheaderOffset + 240, ColumnListSubheader.class,
                SIGNATURE_COLUMN_LIST);

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

    /**
     * A subheader that contains information about columns sizes.
     */
    static class ColumnSizeSubheader extends Subheader {
        /**
         * The number of bytes in a ColumnSizeSubheader
         */
        static final int PAGE_SIZE = 24;

        int totalVariables;

        ColumnSizeSubheader(List<Variable> variables) {
            totalVariables = variables.size();
        }

        @Override
        int size() {
            return PAGE_SIZE;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {
            write8(page, subheaderOffset, SIGNATURE_COLUMN_SIZE); // signature
            write8(page, subheaderOffset + 8, totalVariables); // number of columns
            write8(page, subheaderOffset + 16, 0x00); // unknown, maybe padding
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

    /**
     * A subheader that contains information about row sizes.
     */
    static class RowSizeSubheader extends Subheader {

        /**
         * The number of bytes in a RowPageSubheader
         */
        static final int PAGE_SIZE = 808;

        final String datasetType;
        final String datasetLabel;
        final int totalObservationsInDataset;
        final Sas7bdatUnix64bitMetadata metadata;
        final long initialPageSequenceNumber;

        final int rowSizeInBytes;
        final int totalVariableNameLength;
        final int maxVariableNameLength;
        final int maxVariableLabelLength;

        int totalPossibleObservationsOnMixedPage;
        int totalObservationsOnMixedPage;
        int maxObservationsPerDataPage;
        int totalMetadataPages;
        int totalPagesInDataset;

        RowSizeSubheader(PageSequenceGenerator pageSequenceGenerator, String datasetType, String datasetLabel,
            Sas7bdatUnix64bitVariables variables, int totalObservationsInDataset, Sas7bdatUnix64bitMetadata metadata) {
            this.datasetType = datasetType;
            this.datasetLabel = datasetLabel;
            this.totalObservationsInDataset = totalObservationsInDataset;
            this.metadata = metadata; // this is filled in later by the caller
            this.initialPageSequenceNumber = pageSequenceGenerator.initialPageSequence();

            // Calculate some properties of a data row.
            int totalVariableNameLength = 0;
            int maxVariableNameLength = 0;
            int maxVariableLabelLength = 0;
            for (Variable variable : variables.variables) {
                totalVariableNameLength += ColumnTextSubheader.sizeof(variable.name());

                if (maxVariableNameLength < ColumnTextSubheader.sizeof(variable.name())) {
                    maxVariableNameLength = ColumnTextSubheader.sizeof(variable.name());
                }
                if (maxVariableLabelLength < ColumnTextSubheader.sizeof(variable.label())) {
                    maxVariableLabelLength = ColumnTextSubheader.sizeof(variable.label());
                }
            }

            this.rowSizeInBytes = variables.rowLength;
            this.totalVariableNameLength = totalVariableNameLength;
            this.maxVariableNameLength = maxVariableNameLength;
            this.maxVariableLabelLength = maxVariableLabelLength;
        }

        private void writeRecordLocation(byte[] page, int offset, long pageIndex, long recordIndex) {
            write8(page, offset, pageIndex);
            write8(page, offset + 8, recordIndex);
        }

        void setTotalPossibleObservationOnMixedPage(int totalPossibleObservationsOnMixedPage) {
            this.totalPossibleObservationsOnMixedPage = totalPossibleObservationsOnMixedPage;
            this.totalObservationsOnMixedPage = Math.min(totalPossibleObservationsOnMixedPage,
                totalObservationsInDataset);
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
            return PAGE_SIZE;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {
            write8(page, subheaderOffset, SIGNATURE_ROW_SIZE); // signature

            write8(page, subheaderOffset + 8, 0xF0); // unknown
            write8(page, subheaderOffset + 16, metadata.subheaders.size() + 2); // unknown
            write8(page, subheaderOffset + 24, 0x00); // unknown
            write8(page, subheaderOffset + 32, 0x223011); // unknown

            write8(page, subheaderOffset + 40, rowSizeInBytes); // row length in bytes
            write8(page, subheaderOffset + 48, totalObservationsInDataset); // (deleted and not)
            write8(page, subheaderOffset + 56, 0); // number of deleted observations
            write8(page, subheaderOffset + 64, 0); // unknown

            // Calculate the number of ColumnFormatSubheaders on the first metadata page
            // that has them and the number of ColumnFormatSubheaders on the final metadata
            // page that has them.
            // SAS can't process datasets if this is incorrect.
            int firstPageWithColumnFormatSubheader = 0; // impossible value
            int totalColumnFormatSubheadersOnFirstPage = 0;
            int secondPageWithColumnFormatSubheader = 0; // impossible value
            int totalColumnFormatSubheadersOnSecondPage = 0;
            for (final Subheader subheader : metadata.subheaders) {
                if (subheader instanceof ColumnFormatSubheader) {
                    final int pageNumberOfSubheader = metadata.subheaderLocations.get(subheader);

                    if (firstPageWithColumnFormatSubheader == 0) {
                        // This is the first ColumnFormatSubheader in the metadata.
                        firstPageWithColumnFormatSubheader = pageNumberOfSubheader;
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

                    } else {
                        // We went past the second page of metadata with ColumnFormatSubheaders.
                        // Since we don't care about what follows, we can break.
                        break;
                    }
                }
            }

            // The number of ColumnFormatSubheaders on the first and second page.
            write8(page, subheaderOffset + 72, totalColumnFormatSubheadersOnFirstPage);
            write8(page, subheaderOffset + 80, totalColumnFormatSubheadersOnSecondPage);

            // The next value is unknown, but setting it incorrectly can cause SAS to crash.
            //
            // This seems to be
            // 1) the size of the "payload" of all ColumnListSubheaders (subheader size - 28)
            // 2) the value at offset 16 of the first ColumnListSubheader when there is only one
            // 3) 22 + 2 * the total columns (offset 26) the first ColumnListSubheader
            //
            // Calculate it with method 1.
            int unknownNumber = 0;
            for (final Subheader subheader : metadata.subheaders) {
                if (subheader instanceof ColumnListSubheader) {
                    unknownNumber += subheader.size() - 28;
                }
            }
            write8(page, subheaderOffset + 88, unknownNumber);

            write8(page, subheaderOffset + 96, totalVariableNameLength);

            write8(page, subheaderOffset + 104, page.length); // page size
            write8(page, subheaderOffset + 112, 0); // unknown

            // How many observations could fit on the page, which is necessarily a 'mix' page.
            // This may be larger than the number of observations that are actually on the page.
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

            final Sas7bdatUnix64bitMetadataPage finalMetadataPage = metadata.currentMetadataPage;

            // Unknown, but could be the location of the last Subheader block, in which case
            // the -1 doesn't include the truncated subheader.
            writeRecordLocation(page, subheaderOffset + 528, totalMetadataPages,
                finalMetadataPage.subheaders.size() - 1);

            // The location of the first data record.
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
                    blockOfFirstDataRecord = finalMetadataPage.subheaders.size() + 1;
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
                    lastRecordIndex = finalMetadataPage.subheaders.size() + totalObservationsOnMixedPage;
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
            //
            // Since ColumnFormatSubheader are always last in the metadata, it is sufficient to count
            // the number of subheaders that aren't ColumnFormatSubheader (or the truncated subheader)
            // on the first page that has a ColumnFormatSubheader.  All of those subheaders are necessarily
            // before the ColumnFormatSubheader.
            int blockOfFirstColumnFormatSubheader = 1;
            for (Map.Entry<Subheader, Integer> entry : metadata.subheaderLocations.entrySet()) {
                final Subheader subheader = entry.getKey();
                final int pageNumber = entry.getValue();
                if (pageNumber == firstPageWithColumnFormatSubheader) {
                    if (!(subheader instanceof ColumnFormatSubheader) && !(subheader instanceof TruncatedSubheader)) {
                        blockOfFirstColumnFormatSubheader++;
                    }
                }
            }
            writeRecordLocation(page, subheaderOffset + 576, firstPageWithColumnFormatSubheader,
                blockOfFirstColumnFormatSubheader);

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
            metadata.columnText.writeTextLocation(page, subheaderOffset + 678, datasetLabel);

            // The reference to the dataset type string in the column text.
            // We pad with spaces to match what ColumnText does.
            String paddedDatasetType = datasetType +
                " ".repeat(8 - datasetType.getBytes(StandardCharsets.UTF_8).length);
            metadata.columnText.writeTextLocation(page, subheaderOffset + 684, paddedDatasetType);

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
            int totalColumnTextSubheaders = 0;
            for (Subheader subheader : metadata.subheaders) {
                if (subheader instanceof ColumnTextSubheader) {
                    totalColumnTextSubheaders++;
                }
            }
            write2(page, subheaderOffset + 748, (short) totalColumnTextSubheaders);

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

    /**
     * A zero-sized (truncated) subheader
     */
    static class TruncatedSubheader extends Subheader {

        TruncatedSubheader() {
        }

        @Override
        int size() {
            return 0;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {
        }

        @Override
        byte typeCode() {
            return SUBHEADER_TYPE_A;
        }

        @Override
        byte compressionCode() {
            return COMPRESSION_TRUNCATED;
        }
    }

    /** A page in a sas7bdat dataset that would be generated with a 64-bit UNIX machine */
    static abstract class Sas7bdatUnix64bitPage {
        static final short PAGE_TYPE_META = 0x0000;
        static final short PAGE_TYPE_DATA = 0x0100;
        static final short PAGE_TYPE_MIX = 0x0200;
        static final short PAGE_TYPE_AMD = 0x0400;
        static final short PAGE_TYPE_MASK = 0x0F00;
        static final short PAGE_TYPE_META2 = 0x4000;

        static int align(int number, int alignment) {
            int excess = number % alignment;
            return excess == 0 ? number : number + alignment - excess;
        }

        abstract boolean addObservation(List<Object> observation);

        abstract void write(byte[] data);
    }

    /** A "metadata" page contains a header and subheaders, and possibly data */
    static class Sas7bdatUnix64bitMetadataPage extends Sas7bdatUnix64bitPage {

        // For 64-bit, these are each 24 bytes long.
        static final int SUBHEADER_OFFSET_SIZE_64BIT = 24;

        final int pageSize;
        final long pageSequenceNumber;
        final List<Subheader> subheaders;
        final List<List<Object>> observations;
        final Sas7bdatUnix64bitVariables variables;

        short pageType;
        int offsetOfNextSubheaderIndexEntry; // also the index of the last observation written.
        int endOfDataSection;
        boolean subheaderFinalized;
        int maxObservations;

        Sas7bdatUnix64bitMetadataPage(PageSequenceGenerator pageSequenceGenerator, int pageSize,
            Sas7bdatUnix64bitVariables variables) {
            this.pageSize = pageSize;

            pageSequenceGenerator.incrementPageSequence();
            this.pageSequenceNumber = pageSequenceGenerator.currentPageSequence();
            this.variables = variables;

            subheaders = new ArrayList<>();
            observations = new ArrayList<>();

            pageType = PAGE_TYPE_META;
            offsetOfNextSubheaderIndexEntry = DATA_PAGE_HEADER_SIZE;
            endOfDataSection = pageSize;
            subheaderFinalized = false;
        }

        boolean addSubheader(Subheader subheader) {
            assert !subheaderFinalized : "cannot add any more subheaders after they have been finalized";
            assert !(subheader instanceof TruncatedSubheader) : "truncated subheaders should only be added by finalize";
            assert observations.isEmpty() : "adding a subheader after data is written";

            // Determine if the page has enough space left to hold the subheader.
            // This requires space for the index (SUBHEADER_OFFSET_SIZE_64BIT) and the subheader itself.
            // We must also leave enough space to add the "deleted" index entry at the end.
            int spaceRemainingInPage = totalBytesRemaining();
            int spaceRequiredBySubheader = 2 * SUBHEADER_OFFSET_SIZE_64BIT + subheader.size();
            if (spaceRemainingInPage < spaceRequiredBySubheader) {
                // There isn't enough space left for this subheader.
                return false;
            }

            // Reserve space for the subheader in the index at the beginning of the page.
            offsetOfNextSubheaderIndexEntry += SUBHEADER_OFFSET_SIZE_64BIT;

            // Reserve space for the subheader at the end of the page.
            endOfDataSection = endOfDataSection - subheader.size();

            subheaders.add(subheader);
            return true;
        }

        void finalizeSubheaders() {
            assert !subheaderFinalized : "cannot finalize subheaders multiple times";

            // Add a zero-length, deleted subheader to the end of the index, as SAS does, to
            // indicate the end of the index.  I don't know SAS needs this or if it's just something
            // that it does.

            // Because we were careful to reserve space for this in addSubheader(), it should exist.
            assert SUBHEADER_OFFSET_SIZE_64BIT <= totalBytesRemaining() : "not enough space to write truncated subheader at end of section";
            subheaders.add(new TruncatedSubheader());
            offsetOfNextSubheaderIndexEntry += SUBHEADER_OFFSET_SIZE_64BIT;

            subheaderFinalized = true;

            // An observation takes up space equal to its size in bytes, plus one bit.
            // The extra bit is for an "is deleted" flag that is added at the end of the observations.
            final int totalBitsRemaining = 8 * totalBytesRemaining();
            final int totalBitsPerObservation = 8 * variables.rowLength + 1;
            maxObservations = totalBitsRemaining / totalBitsPerObservation;
        }

        @Override
        boolean addObservation(List<Object> observation) {
            assert subheaderFinalized : "can't write new observation until subheaders are finalized";

            if (maxObservations <= observations.size()) {
                // There isn't enough space between the end of the subheaders and the last subheader written
                // to hold an observation.
                return false;
            }

            // There's space for the observation.
            // It will be written just after the last subheader index entry or the previous observation written.
            offsetOfNextSubheaderIndexEntry += variables.rowLength;
            observations.add(observation);

            // metadata pages that also have data are "mixed" pages.
            pageType = PAGE_TYPE_MIX;

            assert subheaders.size() + observations.size() < 0x10000 : "too many blocks on page";
            return true;
        }

        void setIsLastMetadataPage() {
            // SAS always marks the final metadata page as a mixed page, even if it has no data.
            pageType = PAGE_TYPE_MIX;
        }

        int totalBytesRemaining() {
            assert offsetOfNextSubheaderIndexEntry <= endOfDataSection : "negative space remaining";
            return endOfDataSection - offsetOfNextSubheaderIndexEntry;
        }

        /**
         * Calculates the maximum size of a subheader that can be added to this page, accounting for the space required
         * by the subheader, the index to the subheader, and space for the "truncated subheader" that is added to the
         * index when the subheaders are finalized.
         *
         * @return the maximum size of a subheader that can be added.
         */
        int totalBytesRemainingForNewSubheader() {
            // Return 0, not a negative number, when there isn't enough space remaining for a new subheader.
            return Math.max(0, totalBytesRemaining() - 2 * SUBHEADER_OFFSET_SIZE_64BIT);
        }

        @Override
        void write(byte[] data) {
            assert data.length == pageSize : "data is not sized correctly: " + data.length;
            write8(data, 0, pageSequenceNumber);

            write8(data, 8, 0); // unknown purpose
            write8(data, 16, 0); // unknown purpose

            // The value at offset 24 is a positive integer in the range from 0 to pageSize.
            // I suspect it's either the number of unused bytes on the page or an offset to something.
            // The calculation below doesn't always match what SAS generates for similar datasets.
            // SAS uses numbers that are 0% - 5% bytes smaller than the ones calculated below.
            int totalBytesFree = pageSize - (
                DATA_PAGE_HEADER_SIZE + // standard page header
                    subheaders.size() * SUBHEADER_OFFSET_SIZE_64BIT + // subheader index
                    subheaders.stream().map(Subheader::size).reduce(0, Integer::sum) + // subheaders
                    observations.size() * variables.rowLength + // observations
                    divideAndRoundUp(observations.size(), 8)); // observation deleted flags
            write8(data, 24, totalBytesFree);

            write2(data, 32, pageType);
            write2(data, 34, (short) (subheaders.size() + observations.size())); // data block count
            write2(data, 36, (short) subheaders.size()); // number of subheaders on page
            write2(data, 38, (short) 0); // unknown purpose (possibly padding)

            int offset = DATA_PAGE_HEADER_SIZE; // The subheaders index immediately follows the header.
            int subheaderOffset = data.length; // The subheaders are written at the end of the page.
            for (Subheader subheader : subheaders) {
                subheaderOffset -= subheader.size();
                subheader.writeSubheaderIndex(data, offset, subheaderOffset);
                subheader.writeSubheader(data, subheaderOffset);
                offset += SUBHEADER_OFFSET_SIZE_64BIT;
            }
            assert endOfDataSection == subheaderOffset;

            // Write the observations.
            for (List<Object> observation : observations) {
                variables.writeObservation(data, offset, observation);
                offset += variables.rowLength;
            }
            assert offsetOfNextSubheaderIndexEntry == offset;

            // Immediately before the endOfDataSecond are the "is deleted" flags.
            // If the first bit of the first byte were set, it would indicate that observation #1 is deleted.
            // Since this API doesn't support adding deleted observations, these are all 0.

            // Initialize the rest of the page.
            Arrays.fill(data, offset, endOfDataSection, (byte) 0);
        }
    }

    /** A "data" page is a page that contains both metadata and data */
    // TODO: combine with metadata page
    static class Sas7bdatUnix64bitDataPage extends Sas7bdatUnix64bitPage {

        final int pageSize;
        final Sas7bdatUnix64bitVariables variables;
        final int maxPossibleObservations;
        final long pageSequenceNumber;
        final List<List<Object>> observations;

        static int maxObservationsPerPage(int pageSize, Sas7bdatUnix64bitVariables variables) {
            // An observation takes up space equal to its size in bytes, plus one bit.
            // The extra bit is for an "is deleted" flag that is added at the end of the observations.
            final int totalBitsRemaining = 8 * (pageSize - DATA_PAGE_HEADER_SIZE);
            final int totalBitsPerObservation = 8 * variables.rowLength + 1;
            return totalBitsRemaining / totalBitsPerObservation;
        }

        Sas7bdatUnix64bitDataPage(PageSequenceGenerator pageSequenceGenerator, int pageSize,
            Sas7bdatUnix64bitVariables variables) {
            this.pageSize = pageSize;
            this.variables = variables;

            pageSequenceGenerator.incrementPageSequence();
            pageSequenceNumber = pageSequenceGenerator.currentPageSequence();

            maxPossibleObservations = maxObservationsPerPage(pageSize, variables);
            assert 0 < maxPossibleObservations : "page size too small to hold an observation";
            observations = new ArrayList<>(maxPossibleObservations);
        }

        boolean addObservation(List<Object> observation) {

            // If there isn't enough space between here and the end of the page, then the page is full.
            if (maxPossibleObservations <= observations.size()) {
                return false;
            }

            observations.add(observation);
            return true;
        }

        @Override
        void write(byte[] data) {
            write8(data, 0, pageSequenceNumber);
            write8(data, 8, 0); // unknown
            write8(data, 16, 0); // unknown

            // The value at offset 24 is a positive integer in the range from 0 to pageSize.
            // I suspect it's either the number of unused bytes on the page or an offset to something.
            // The calculation below doesn't always match what SAS generates for similar datasets.
            // SAS uses numbers that are 0% - 5% bytes smaller than the ones calculated below.
            int totalBytesFree = pageSize - (
                DATA_PAGE_HEADER_SIZE + // standard page header
                    observations.size() * variables.rowLength + // observations
                    divideAndRoundUp(observations.size(), 8)); // observation deleted flags
            write8(data, 24, totalBytesFree);

            write2(data, 32, PAGE_TYPE_DATA);
            write2(data, 34, (short) observations.size()); // data block count
            write2(data, 36, (short) 0); // number of subheaders
            write2(data, 38, (short) 0); // unknown, possibly padding

            int offset = 40;
            for (List<Object> observation : observations) {
                variables.writeObservation(data, offset, observation);
                offset += variables.rowLength;
            }

            // Immediately before the end of the page are the "is deleted" flags.
            // If the first bit of the first byte were set, it would indicate that observation #1 is deleted.
            // Since this API doesn't support adding deleted observations, these are all 0.

            // Initialize the rest of the page.
            Arrays.fill(data, offset, data.length, (byte) 0);
        }
    }

    /**
     * A helper class for putting subheaders into pages. It also calculates the following:
     * <ol>
     * <li>how many observations fit on the mixed page</li>
     * <li>how many metadata/mixed pages are needed</li>
     * <li>the location of each subheader</li>
     * </ol>
     */
    static class Sas7bdatUnix64bitMetadata {
        final PageSequenceGenerator pageSequenceGenerator;
        final int pageSize;
        final Sas7bdatUnix64bitVariables variables;
        final ColumnText columnText;
        final List<Subheader> subheaders;
        final List<Sas7bdatUnix64bitMetadataPage> completeMetadataPages;
        final Map<Subheader, Integer> subheaderLocations;

        Sas7bdatUnix64bitMetadataPage currentMetadataPage;

        Sas7bdatUnix64bitMetadata(PageSequenceGenerator pageSequenceGenerator, int pageSize,
            Sas7bdatUnix64bitVariables variables) {
            this.pageSequenceGenerator = pageSequenceGenerator;
            this.pageSize = pageSize;
            this.variables = variables;
            this.columnText = new ColumnText(this);

            subheaders = new ArrayList<>();
            completeMetadataPages = new ArrayList<>();
            subheaderLocations = new IdentityHashMap<>();
            currentMetadataPage = new Sas7bdatUnix64bitMetadataPage(pageSequenceGenerator, pageSize, variables);
        }

        void finalizeSubheaders() {
            currentMetadataPage.finalizeSubheaders();

            // finalizeSubheaders() inserts a truncated subheader.  We must therefore update the
            // allSubheaders list to include this truncated subheader.
            Subheader truncatedSubheader = currentMetadataPage.subheaders.get(
                currentMetadataPage.subheaders.size() - 1);
            subheaders.add(truncatedSubheader);
            subheaderLocations.put(truncatedSubheader, completeMetadataPages.size() + 1);
        }

        void addSubheader(Subheader subheader) {
            if (!currentMetadataPage.addSubheader(subheader)) {
                // There's not enough space on this metadata page for this subheader.

                // Finalize the subheaders on the current page.
                finalizeSubheaders();

                // Create a new page.
                completeMetadataPages.add(currentMetadataPage);
                currentMetadataPage = new Sas7bdatUnix64bitMetadataPage(pageSequenceGenerator, pageSize, variables);

                // Add the subheader to the new page.
                boolean success = currentMetadataPage.addSubheader(subheader);
                assert success : "couldn't add a subheader to a new page";
            }

            // Track which page the subheader was added to.
            subheaders.add(subheader);
            subheaderLocations.put(subheader, completeMetadataPages.size() + 1);
        }
    }

    private final OutputStream outputStream;
    private final Sas7bdatUnix64bitVariables datasetVariables;
    private final int totalObservationsInDataset;
    private final PageSequenceGenerator pageSequenceGenerator;
    private final int pageSize;
    private final byte[] pageBuffer;

    int totalObservationsWritten;
    private Sas7bdatUnix64bitPage currentPage;

    int totalPagesAllocated; // TODO: only to assert at the end
    int totalPagesInDataset;

    private static int divideAndRoundUp(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    public Sas7bdatWriter(Path targetLocation, LocalDateTime createDate, String datasetType,
        String datasetLabel, List<Variable> variables, int totalObservationsInDataset) throws IOException {

        assert datasetType.getBytes(StandardCharsets.UTF_8).length <= 8;

        outputStream = Files.newOutputStream(targetLocation);
        datasetVariables = new Sas7bdatUnix64bitVariables(variables);
        this.totalObservationsInDataset = totalObservationsInDataset;
        pageSequenceGenerator = new PageSequenceGenerator();

        //
        // Create the metadata for this dataset.
        //

        // When SAS generates a dataset, it seems to pick page sizes that are multiples of 1KiB (0x400).
        int dataPageSizeForSingleObservation = DATA_PAGE_HEADER_SIZE + datasetVariables.rowLength() + 1; // +1 is for the "is deleted" flag
        pageSize = Sas7bdatUnix64bitPage.align(Math.max(MINIMUM_PAGE_SIZE, dataPageSizeForSingleObservation), 0x400);
        pageBuffer = new byte[pageSize];

        Sas7bdatUnix64bitMetadata metadata = new Sas7bdatUnix64bitMetadata(pageSequenceGenerator, pageSize,
            datasetVariables);

        // Add the subheaders in the order in which they should be listed in the subheaders index.
        // Note that this is the reverse order in which they appear a metadata page.
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(pageSequenceGenerator, datasetType, datasetLabel,
            datasetVariables, totalObservationsInDataset, metadata);
        metadata.addSubheader(rowSizeSubheader);

        metadata.addSubheader(new ColumnSizeSubheader(variables));

        SubheaderCountsSubheader subheaderCountsSubheader = new SubheaderCountsSubheader(variables, metadata);
        metadata.addSubheader(subheaderCountsSubheader);

        // Next, SAS adds the ColumnTextSubheaders.  Since a Subheader cannot be larger than Short.MAX_SIZE
        // bytes, if there's a lot of metadata text, multiple ColumnTextSubheaders may be needed.
        // SAS adds these in a way that is aware of how much space is left on the metadata page so that
        // a subheader is limited to fill what's left on the page.  Therefore, populating the
        // ColumnTextSubheaders must be coupled with the logic for adding subheaders to pages.
        // And since all ColumnTextSubheaders must be consecutive, all text must be added at the same time.
        //
        // I would have preferred a design where each subheader that needs to reference a string would
        // be the one to add it to the ColumnText.  I think that would have been better encapsulation.
        // However, I wasn't able to make that design work and still limit the column text subheaders
        // to fit the available space on the page.  Therefore, all strings referenced by any subheader
        // are added here.
        //
        // To assist with troubleshooting, the text is added in the same order in which SAS adds it.

        // The first ColumnTextSubheader in the datasets which SAS generates has an extra
        // four bytes of padding between the size of the header and the first string.
        // Adding the string of 0x00 0x00 0x00 0x00 matches what SAS usually generates.
        // Sometimes SAS generates 0x00 0x00 0x00 0x14 or 0x00 0x00 0x00 0x1d,
        // but I don't know if this has any meaning.
        metadata.columnText.add("\0\0\0\0");

        metadata.columnText.add(" ".repeat(8)); // unknown

        // Add the dataset type, padded with spaces.
        // We duplicateIfExists=true because if the datasetType is the empty string, we still
        // want to reserve the space for it, even though we added the same eight spaces above.
        String paddedDatasetType = datasetType + " ".repeat(8 - datasetType.getBytes(StandardCharsets.UTF_8).length);
        metadata.columnText.add(paddedDatasetType);

        metadata.columnText.add("DATASTEP"); // add the PROC step which created the dataset
        metadata.columnText.add(datasetLabel); // add the dataset label

        for (Variable variable : variables) {
            metadata.columnText.add(variable.name());
            metadata.columnText.add(variable.label());

            // CONSIDER: SAS uppercases the format names before storing them.
            metadata.columnText.add(variable.inputFormat().name());
            metadata.columnText.add(variable.outputFormat().name());
        }

        // Add the partially-written column text subheader to the metadata page.
        // This is essential, as all column text subheaders must be added before
        // the next subheader type is added.
        metadata.columnText.noMoreText();

        int offset = 0;
        while (offset < datasetVariables.totalVariables()) {
            ColumnNameSubheader nextSubheader = new ColumnNameSubheader(variables, offset, metadata.columnText);
            metadata.addSubheader(nextSubheader);
            offset += nextSubheader.variables.size();
        }

        // Add the ColumnAttributesSubheaders
        offset = 0;
        while (offset < datasetVariables.totalVariables()) {
            // Datasets that are generated by SAS limit the size of this subheader to 24588 bytes.
            // In theory, it should be able to hold (Short.MAX_VALUE - 8) / 16 bytes, or 2047 variables.
            //
            // If there isn't enough space on the current metadata page for a subheader that contains all variables,
            // then split the subheader so that we use all remaining space on the metadata pages.  This is what
            // SAS does.
            final int spaceInPage = metadata.currentMetadataPage.totalBytesRemainingForNewSubheader();
            final int maxSize;
            if (spaceInPage < ColumnAttributesSubheader.MIN_SIZE) {
                // There's not enough space remaining for a useful header. Pick a large subheader for the next page.
                maxSize = 24588;
            } else {
                maxSize = Math.min(24588, spaceInPage);
            }

            ColumnAttributesSubheader nextSubheader = new ColumnAttributesSubheader(datasetVariables, offset, maxSize);
            metadata.addSubheader(nextSubheader);
            offset += nextSubheader.totalVariablesInSubheader;
        }

        if (1 < datasetVariables.totalVariables()) {
            // TODO when is this needed?  when there's more than one variable?
            offset = 0;
            while (offset < datasetVariables.totalVariables()) {
                ColumnListSubheader nextSubheader = new ColumnListSubheader(datasetVariables, offset);
                metadata.addSubheader(nextSubheader);
                offset += nextSubheader.totalVariables;
            }
        }
        for (Variable variable : variables) {
            metadata.addSubheader(new ColumnFormatSubheader(variable, metadata.columnText));
        }

        // Finalize the subheaders on the final metadata page.
        metadata.finalizeSubheaders();

        // Mark the final metadata page as a "mixed" page, even if it doesn't contain data.
        // This is what SAS does.  I don't know if this is necessary.
        metadata.currentMetadataPage.setIsLastMetadataPage();

        final int maxObservationsOnMixedPage = metadata.currentMetadataPage.maxObservations;
        rowSizeSubheader.setTotalPossibleObservationOnMixedPage(metadata.currentMetadataPage.maxObservations);

        final int totalNumberOfMetadataPages = metadata.completeMetadataPages.size() + 1;
        rowSizeSubheader.setTotalMetadataPages(totalNumberOfMetadataPages);

        // Calculate how many observations fit on a data page.
        final int observationsPerDataPage = Sas7bdatUnix64bitDataPage.maxObservationsPerPage(pageSize,
            datasetVariables);
        rowSizeSubheader.setMaxObservationsPerDataPage(observationsPerDataPage);

        // Calculate how many pages will be needed in the dataset.
        {
            final int totalNumberOfDataPages;
            if (totalObservationsInDataset <= maxObservationsOnMixedPage) {
                // All observations can fit on the mixed page, so there's no need for data pages.
                totalNumberOfDataPages = 0;
            } else {
                int observationsOnAllDataPages = totalObservationsInDataset - maxObservationsOnMixedPage;
                // observationsOnAllDataPages / observationsPerDataPage rounded up
                totalNumberOfDataPages = divideAndRoundUp(observationsOnAllDataPages, observationsPerDataPage);
            }
            totalPagesInDataset = totalNumberOfMetadataPages + totalNumberOfDataPages;
        }
        rowSizeSubheader.setTotalPagesInDataset(totalPagesInDataset);

        // Write the file header.
        {
            String datasetName = targetLocation.getFileName().toString().replace(".sas7bdat", "");
            Sas7bdatUnix64bitHeader header = new Sas7bdatUnix64bitHeader(pageSequenceGenerator, pageSize, pageSize,
                datasetName, createDate, totalPagesInDataset);
            header.write(pageBuffer);
            outputStream.write(pageBuffer);
        }

        // Write out all complete metadata pages (but not the last one, which can hold observations)
        for (Sas7bdatUnix64bitMetadataPage currentMetadataPage : metadata.completeMetadataPages) {
            writePage(currentMetadataPage);
        }

        totalObservationsWritten = 0;
        totalPagesAllocated = totalNumberOfMetadataPages;
        currentPage = metadata.currentMetadataPage;
    }

    public void writeObservation(List<Object> observation) throws IOException {
        if (totalObservationsInDataset <= totalObservationsWritten) {
            throw new IllegalStateException("wrote more observations than promised in the constructor");
        }

        if (!currentPage.addObservation(observation)) {
            // The page is full.  Start the next one.

            // Write the page.
            writePage(currentPage);

            // Start a new page.
            totalPagesAllocated++;
            currentPage = new Sas7bdatUnix64bitDataPage(pageSequenceGenerator, pageSize, datasetVariables);

            // Write the observation to the new page.
            boolean success = currentPage.addObservation(observation);
            assert success : "couldn't write to new page";
        }

        totalObservationsWritten++;
    }

    public boolean isComplete() {
        return totalObservationsInDataset == totalObservationsWritten;
    }

    void writePage(Sas7bdatUnix64bitPage page) throws IOException {
        // Clear the data on the buffer so that parts of the previous page
        // don't get repeated in this page for the parts that aren't filled in.
        //
        // That said, SAS echos part of the second-to-final page on the conceptually
        // blank parts of the final data page, so using a dirty buffer may better
        // match what SAS does.  On the other hand, re-using a dirty buffer has
        // put some information on the first data page that caused SAS to skip
        // data on the second data page, so there must be some part of the
        // data footer that is significant.
        Arrays.fill(pageBuffer, (byte) 0x00);

        page.write(pageBuffer);
        outputStream.write(pageBuffer);
    }

    public void close() throws IOException {

        if (currentPage != null) {
            // It'd be nice to throw an exception if fewer observation were written than were promised in the
            // constructor, since it means that the header was written incorrectly, but since close() may be
            // invoked as a result of an exception when writing, doing so would
            // suppress the original exception with one that doesn't contain new information.
            //
            // The best we can do is for the caller to "opt in" by asserting isComplete().

            // Write the page.
            writePage(currentPage);
            currentPage = null;

            outputStream.close();

            assert totalPagesAllocated == totalPagesInDataset : "wrote " + totalPagesAllocated + " out of " + totalPagesInDataset + " pages.";
        }
    }

    public static void writeDataset(Path targetLocation, LocalDateTime createDate, String datasetType,
        String datasetLabel, List<Variable> variables, List<List<Object>> observations) throws IOException {

        try (Sas7bdatWriter datasetWriter = new Sas7bdatWriter(targetLocation, createDate, datasetType, datasetLabel,
            variables, observations.size())) {

            // Add observations
            for (List<Object> observation : observations) {
                datasetWriter.writeObservation(observation);
            }
            assert datasetWriter.isComplete();
        }
    }
}