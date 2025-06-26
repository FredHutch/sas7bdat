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

    static int write2(byte[] data, int offset, short number) {
        // serialized as little-endian
        assert offset % 2 == 0 : "offset is not 2-byte aligned";
        data[offset + 1] = (byte) (number >> 8);
        data[offset + 0] = (byte) number;
        return 2;
    }

    static int write4(byte[] data, int offset, int number) {
        // serialized as little-endian
        assert offset % 2 == 0 : "offset is not 2-byte aligned";
        data[offset + 3] = (byte) (number >> 24);
        data[offset + 2] = (byte) (number >> 16);
        data[offset + 1] = (byte) (number >> 8);
        data[offset + 0] = (byte) number;
        return 4;
    }

    static int write8(byte[] data, int offset, long number) {
        // serialized as little-endian
        assert offset % 2 == 0 : "offset is not 2-byte aligned";
        data[offset + 7] = (byte) (number >> 56);
        data[offset + 6] = (byte) (number >> 48);
        data[offset + 5] = (byte) (number >> 40);
        data[offset + 4] = (byte) (number >> 32);
        data[offset + 3] = (byte) (number >> 24);
        data[offset + 2] = (byte) (number >> 16);
        data[offset + 1] = (byte) (number >> 8);
        data[offset + 0] = (byte) number;
        return 8;
    }

    static void writeUtf8(byte[] data, int offset, String string, int length, byte paddingByte) {
        byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
        assert utf8.length <= length;

        // copy the string
        System.arraycopy(utf8, 0, data, offset, utf8.length);

        // pad the rest
        Arrays.fill(data, offset + utf8.length, offset + length, paddingByte);
    }

    static void writeAscii(byte[] data, int offset, String string, int length) {
        assert string.matches("^\\p{ASCII}*$");
        assert string.length() <= length;
        writeUtf8(data, offset, string, length, (byte) ' '); // pad with spaces
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

    static final int SUBHEADER_SIGNATURE_SIZE = 8; // 8 bytes
    static final long SUBHEADER_SIGNATURE_ROW_SIZE = 0x00000000F7F7F7F7L;
    static final long SUBHEADER_SIGNATURE_COLUMN_SIZE = 0x00000000F6F6F6F6L;
    static final long SUBHEADER_SIGNATURE_SUBHEADER_COUNTS = 0xFFFFFFFFFFFFFC00L;
    static final long SUBHEADER_SIGNATURE_COLUMN_FORMAT = 0xFFFFFFFFFFFFFBFEL;
    static final long SUBHEADER_SIGNATURE_COLUMN_MASK = 0xFFFFFFFFFFFFFFF8L;
    static final long SUBHEADER_SIGNATURE_COLUMN_ATTRS = 0xFFFFFFFFFFFFFFFCL;
    static final long SUBHEADER_SIGNATURE_COLUMN_TEXT = 0xFFFFFFFFFFFFFFFDL;
    static final long SUBHEADER_SIGNATURE_COLUMN_LIST = 0xFFFFFFFFFFFFFFFEL;
    static final long SUBHEADER_SIGNATURE_COLUMN_NAME = 0xFFFFFFFFFFFFFFFFL;

    static final long SUBHEADER_SIGNATURE_UNKNOWN_A = 0xFFFFFFFFFFFFFFFBL;
    static final long SUBHEADER_SIGNATURE_UNKNOWN_B = 0xFFFFFFFFFFFFFFFAL;
    static final long SUBHEADER_SIGNATURE_UNKNOWN_C = 0xFFFFFFFFFFFFFFF9L;

    static final byte COLUMN_TYPE_NUMERIC = 1;
    static final byte COLUMN_TYPE_CHARACTER = 2;

    static abstract class Subheader {
        /**
         * @return the total number of bytes in this subheader.
         */
        abstract int size();

        /**
         * Writes the contents of this subheader to an array at a given offset.
         *
         * <p>
         * The caller is responsible for allocating enough memory
         * </p>
         *
         * @param page
         *     The array of bytes which represents the enclosing data page.
         * @param subheaderOffset
         *     The offset in {@code page} to which this subheader should be written.
         */
        abstract void writeSubheader(byte[] page, int subheaderOffset);

        /**
         * @return A byte which represents the "type" of this subheader.
         */
        abstract byte typeCode();

        /**
         * @return A byte which represents how this subheader is compressed.
         */
        abstract byte compressionCode();

        void writeSubheaderIndex(byte[] page, int pageOffset, int subheaderOffset) {
            write8(page, pageOffset, subheaderOffset); // offset of subheader
            write8(page, pageOffset + 8, size()); // length of subheader
            page[pageOffset + 16] = compressionCode();
            page[pageOffset + 17] = typeCode();
            write2(page, pageOffset + 18, (short) 0); // unknown, likely padding
            write4(page, pageOffset + 20, (short) 0); // unknown, likely padding
        }
    }

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
            return (short) (offset - SUBHEADER_SIGNATURE_SIZE);
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
            return nextOffset + sizeOfPaddingBlockAtEnd - SUBHEADER_SIGNATURE_SIZE;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {

            write8(page, subheaderOffset, SUBHEADER_SIGNATURE_COLUMN_TEXT); // signature
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
            write8(page, subheaderOffset, SUBHEADER_SIGNATURE_COLUMN_FORMAT); // signature
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
            return OFFSET_OF_FIRST_ENTRY + variables.size() * SIZE_OF_ENTRY - SUBHEADER_SIGNATURE_SIZE;
        }

        @Override
        int size() {
            return OFFSET_OF_FIRST_ENTRY + variables.size() * SIZE_OF_ENTRY + FOOTER_PADDING;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {
            write8(page, subheaderOffset, SUBHEADER_SIGNATURE_COLUMN_ATTRS); // signature

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
            write8(page, subheaderOffset, SUBHEADER_SIGNATURE_COLUMN_NAME); // signature

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
            return totalColumns * 2 + OFFSET_OF_FIRST_COLUMN - SUBHEADER_SIGNATURE_SIZE;
        }

        @Override
        int size() {
            return totalColumns * 2 + OFFSET_OF_FIRST_COLUMN + FOOTER_PADDING;
        }

        @Override
        void writeSubheader(byte[] page, int subheaderOffset) {
            write8(page, subheaderOffset, SUBHEADER_SIGNATURE_COLUMN_LIST); // signature

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
            write8(page, subheaderOffset, SUBHEADER_SIGNATURE_SUBHEADER_COUNTS); // signature

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
                SUBHEADER_SIGNATURE_COLUMN_ATTRS);

            writeSubheaderInformation(page, subheaderOffset + 160, ColumnTextSubheader.class,
                SUBHEADER_SIGNATURE_COLUMN_TEXT);

            writeSubheaderInformation(page, subheaderOffset + 200, ColumnNameSubheader.class,
                SUBHEADER_SIGNATURE_COLUMN_NAME);

            writeSubheaderInformation(page, subheaderOffset + 240, ColumnListSubheader.class,
                SUBHEADER_SIGNATURE_COLUMN_LIST);

            // There are three subheader types that we don't know anything about.
            writeSubheaderInformation(page, subheaderOffset + 280, null, SUBHEADER_SIGNATURE_UNKNOWN_A);
            writeSubheaderInformation(page, subheaderOffset + 320, null, SUBHEADER_SIGNATURE_UNKNOWN_B);
            writeSubheaderInformation(page, subheaderOffset + 360, null, SUBHEADER_SIGNATURE_UNKNOWN_C);

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
            write8(page, subheaderOffset, SUBHEADER_SIGNATURE_COLUMN_SIZE); // signature
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

        static class ValueAtOffset88 {
            // I doubt SAS has a similar array in its source code, so there should be a simpler function.
            // I don't see a pattern, aside from monotonically increasing with many steps.
            // This array was constructed by looking at sas7bdat files with all legal numbers of variables (1-32767).
            // Due to java limitation of 64K of byte code per class, it had to be condensed into a compact
            // representation that alternates between run-length (the length of the step) and the delta between
            // the previous step.  There was one delta that was 144, which prevented it from being a byte array.
            // After observing that all values were even, I divided all values by two.
            static final byte[] variableCountToRow88Data = { //
                2, 0, // 0 - 1 variables -> 0
                1, 13, // 2 variables -> 26
                1, 1, // 3 variables -> 28
                1, 2, // 4 variables -> 32
                2, 2, // 5 - 6 variables -> 36
                3, 4, // 7 - 9 variables -> 44
                1, 2, // 10 variables -> 48
                3, 4, // 11 - 13 variables -> 56
                2, 2, // 14 - 15 variables -> 60
                3, 4, // 16 - 18 variables -> 68
                5, 6, // 19 - 23 variables -> 80
                1, 2, // 24 variables -> 84
                5, 6, // 25 - 29 variables -> 96
                3, 4, // 30 - 32 variables -> 104
                1, 2, // 33 variables -> 108
                3, 4, // 34 - 36 variables -> 116
                5, 6, // 37 - 41 variables -> 128
                5, 6, // 42 - 46 variables -> 140
                1, 2, // 47 variables -> 144
                5, 6, // 48 - 52 variables -> 156
                3, 4, // 53 - 55 variables -> 164
                1, 2, // 56 variables -> 168
                5, 6, // 57 - 61 variables -> 180
                3, 4, // 62 - 64 variables -> 188
                5, 6, // 65 - 69 variables -> 200
                6, 8, // 70 - 75 variables -> 216
                3, 4, // 76 - 78 variables -> 224
                1, 2, // 79 variables -> 228
                4, 4, // 80 - 83 variables -> 236
                1, 2, // 84 variables -> 240
                3, 4, // 85 - 87 variables -> 248
                11, 14, // 88 - 98 variables -> 276
                3, 4, // 99 - 101 variables -> 284
                5, 6, // 102 - 106 variables -> 296
                1, 2, // 107 variables -> 300
                8, 10, // 108 - 115 variables -> 320
                1, 2, // 116 variables -> 324
                5, 6, // 117 - 121 variables -> 336
                5, 6, // 122 - 126 variables -> 348
                3, 4, // 127 - 129 variables -> 356
                4, 6, // 130 - 133 variables -> 368
                5, 6, // 134 - 138 variables -> 380
                1, 2, // 139 variables -> 384
                8, 10, // 140 - 147 variables -> 404
                2, 2, // 148 - 149 variables -> 408
                3, 4, // 150 - 152 variables -> 416
                1, 2, // 153 variables -> 420
                10, 12, // 154 - 163 variables -> 444
                9, 12, // 164 - 172 variables -> 468
                3, 4, // 173 - 175 variables -> 476
                1, 2, // 176 variables -> 480
                3, 4, // 177 - 179 variables -> 488
                5, 6, // 180 - 184 variables -> 500
                2, 2, // 185 - 186 variables -> 504
                7, 10, // 187 - 193 variables -> 524
                5, 6, // 194 - 198 variables -> 536
                5, 6, // 199 - 203 variables -> 548
                4, 6, // 204 - 207 variables -> 560
                2, 2, // 208 - 209 variables -> 564
                4, 6, // 210 - 213 variables -> 576
                3, 4, // 214 - 216 variables -> 584
                2, 2, // 217 - 218 variables -> 588
                8, 10, // 219 - 226 variables -> 608
                10, 14, // 227 - 236 variables -> 636
                3, 4, // 237 - 239 variables -> 644
                2, 2, // 240 - 241 variables -> 648
                3, 4, // 242 - 244 variables -> 656
                11, 14, // 245 - 255 variables -> 684
                4, 6, // 256 - 259 variables -> 696
                8, 10, // 260 - 267 variables -> 716
                2, 2, // 268 - 269 variables -> 720
                3, 4, // 270 - 272 variables -> 728
                4, 6, // 273 - 276 variables -> 740
                7, 8, // 277 - 283 variables -> 756
                4, 6, // 284 - 287 variables -> 768
                5, 6, // 288 - 292 variables -> 780
                3, 4, // 293 - 295 variables -> 788
                4, 6, // 296 - 299 variables -> 800
                7, 8, // 300 - 306 variables -> 816
                3, 4, // 307 - 309 variables -> 824
                6, 8, // 310 - 315 variables -> 840
                8, 10, // 316 - 323 variables -> 860
                1, 2, // 324 variables -> 864
                8, 10, // 325 - 332 variables -> 884
                1, 2, // 333 variables -> 888
                5, 6, // 334 - 338 variables -> 900
                3, 4, // 339 - 341 variables -> 908
                5, 6, // 342 - 346 variables -> 920
                6, 8, // 347 - 352 variables -> 936
                3, 4, // 353 - 355 variables -> 944
                1, 2, // 356 variables -> 948
                3, 4, // 357 - 359 variables -> 956
                10, 12, // 360 - 369 variables -> 980
                6, 8, // 370 - 375 variables -> 996
                3, 4, // 376 - 378 variables -> 1004
                6, 8, // 379 - 384 variables -> 1020
                3, 4, // 385 - 387 variables -> 1028
                5, 6, // 388 - 392 variables -> 1040
                9, 12, // 393 - 401 variables -> 1064
                2, 2, // 402 - 403 variables -> 1068
                13, 18, // 404 - 416 variables -> 1104
                5, 6, // 417 - 421 variables -> 1116
                8, 10, // 422 - 429 variables -> 1136
                4, 6, // 430 - 433 variables -> 1148
                5, 6, // 434 - 438 variables -> 1160
                1, 2, // 439 variables -> 1164
                5, 6, // 440 - 444 variables -> 1176
                8, 10, // 445 - 452 variables -> 1196
                4, 6, // 453 - 456 variables -> 1208
                5, 6, // 457 - 461 variables -> 1220
                2, 2, // 462 - 463 variables -> 1224
                4, 6, // 464 - 467 variables -> 1236
                5, 6, // 468 - 472 variables -> 1248
                3, 4, // 473 - 475 variables -> 1256
                1, 2, // 476 variables -> 1260
                10, 12, // 477 - 486 variables -> 1284
                7, 10, // 487 - 493 variables -> 1304
                2, 2, // 494 - 495 variables -> 1308
                3, 4, // 496 - 498 variables -> 1316
                5, 6, // 499 - 503 variables -> 1328
                4, 6, // 504 - 507 variables -> 1340
                2, 2, // 508 - 509 variables -> 1344
                9, 12, // 510 - 518 variables -> 1368
                3, 4, // 519 - 521 variables -> 1376
                5, 6, // 522 - 526 variables -> 1388
                6, 8, // 527 - 532 variables -> 1404
                7, 10, // 533 - 539 variables -> 1424
                7, 8, // 540 - 546 variables -> 1440
                7, 10, // 547 - 553 variables -> 1460
                6, 8, // 554 - 559 variables -> 1476
                5, 6, // 560 - 564 variables -> 1488
                5, 6, // 565 - 569 variables -> 1500
                3, 4, // 570 - 572 variables -> 1508
                6, 8, // 573 - 578 variables -> 1524
                5, 6, // 579 - 583 variables -> 1536
                3, 4, // 584 - 586 variables -> 1544
                6, 8, // 587 - 592 variables -> 1560
                3, 4, // 593 - 595 variables -> 1568
                11, 14, // 596 - 606 variables -> 1596
                7, 10, // 607 - 613 variables -> 1616
                10, 12, // 614 - 623 variables -> 1640
                1, 2, // 624 variables -> 1644
                8, 10, // 625 - 632 variables -> 1664
                1, 2, // 633 variables -> 1668
                3, 4, // 634 - 636 variables -> 1676
                2, 2, // 637 - 638 variables -> 1680
                8, 10, // 639 - 646 variables -> 1700
                10, 14, // 647 - 656 variables -> 1728
                3, 4, // 657 - 659 variables -> 1736
                2, 2, // 660 - 661 variables -> 1740
                3, 4, // 662 - 664 variables -> 1748
                11, 14, // 665 - 675 variables -> 1776
                3, 4, // 676 - 678 variables -> 1784
                1, 2, // 679 variables -> 1788
                4, 4, // 680 - 683 variables -> 1796
                15, 20, // 684 - 698 variables -> 1836
                3, 4, // 699 - 701 variables -> 1844
                6, 8, // 702 - 707 variables -> 1860
                8, 10, // 708 - 715 variables -> 1880
                6, 8, // 716 - 721 variables -> 1896
                3, 4, // 722 - 724 variables -> 1904
                5, 6, // 725 - 729 variables -> 1916
                4, 6, // 730 - 733 variables -> 1928
                11, 14, // 734 - 744 variables -> 1956
                3, 4, // 745 - 747 variables -> 1964
                5, 6, // 748 - 752 variables -> 1976
                4, 6, // 753 - 756 variables -> 1988
                7, 8, // 757 - 763 variables -> 2004
                4, 6, // 764 - 767 variables -> 2016
                9, 12, // 768 - 776 variables -> 2040
                3, 4, // 777 - 779 variables -> 2048
                5, 6, // 780 - 784 variables -> 2060
                2, 2, // 785 - 786 variables -> 2064
                7, 10, // 787 - 793 variables -> 2084
                2, 2, // 794 - 795 variables -> 2088
                4, 6, // 796 - 799 variables -> 2100
                8, 10, // 800 - 807 variables -> 2120
                2, 2, // 808 - 809 variables -> 2124
                7, 10, // 810 - 816 variables -> 2144
                2, 2, // 817 - 818 variables -> 2148
                5, 6, // 819 - 823 variables -> 2160
                13, 18, // 824 - 836 variables -> 2196
                3, 4, // 837 - 839 variables -> 2204
                2, 2, // 840 - 841 variables -> 2208
                3, 4, // 842 - 844 variables -> 2216
                5, 6, // 845 - 849 variables -> 2228
                4, 6, // 850 - 853 variables -> 2240
                6, 8, // 854 - 859 variables -> 2256
                5, 6, // 860 - 864 variables -> 2268
                5, 6, // 865 - 869 variables -> 2280
                17, 22, // 870 - 886 variables -> 2324
                1, 2, // 887 variables -> 2328
                8, 10, // 888 - 895 variables -> 2348
                6, 8, // 896 - 901 variables -> 2364
                8, 10, // 902 - 909 variables -> 2384
                4, 6, // 910 - 913 variables -> 2396
                5, 6, // 914 - 918 variables -> 2408
                6, 8, // 919 - 924 variables -> 2424
                9, 12, // 925 - 933 variables -> 2448
                3, 4, // 934 - 936 variables -> 2456
                5, 6, // 937 - 941 variables -> 2468
                5, 6, // 942 - 946 variables -> 2480
                1, 2, // 947 variables -> 2484
                5, 6, // 948 - 952 variables -> 2496
                9, 12, // 953 - 961 variables -> 2520
                8, 10, // 962 - 969 variables -> 2540
                14, 18, // 970 - 983 variables -> 2576
                1, 2, // 984 variables -> 2580
                3, 4, // 985 - 987 variables -> 2588
                5, 6, // 988 - 992 variables -> 2600
                1, 2, // 993 variables -> 2604
                5, 6, // 994 - 998 variables -> 2616
                3, 4, // 999 - 1001 variables -> 2624
                2, 2, // 1002 - 1003 variables -> 2628
                3, 4, // 1004 - 1006 variables -> 2636
                9, 12, // 1007 - 1015 variables -> 2660
                1, 2, // 1016 variables -> 2664
                5, 6, // 1017 - 1021 variables -> 2676
                26, 34, // 1022 - 1047 variables -> 2744
                5, 6, // 1048 - 1052 variables -> 2756
                4, 6, // 1053 - 1056 variables -> 2768
                7, 8, // 1057 - 1063 variables -> 2784
                13, 18, // 1064 - 1076 variables -> 2820
                8, 10, // 1077 - 1084 variables -> 2840
                11, 14, // 1085 - 1095 variables -> 2868
                3, 4, // 1096 - 1098 variables -> 2876
                1, 2, // 1099 variables -> 2880
                4, 4, // 1100 - 1103 variables -> 2888
                4, 6, // 1104 - 1107 variables -> 2900
                6, 8, // 1108 - 1113 variables -> 2916
                3, 4, // 1114 - 1116 variables -> 2924
                2, 2, // 1117 - 1118 variables -> 2928
                5, 6, // 1119 - 1123 variables -> 2940
                9, 12, // 1124 - 1132 variables -> 2964
                7, 10, // 1133 - 1139 variables -> 2984
                2, 2, // 1140 - 1141 variables -> 2988
                3, 4, // 1142 - 1144 variables -> 2996
                2, 2, // 1145 - 1146 variables -> 3000
                3, 4, // 1147 - 1149 variables -> 3008
                4, 6, // 1150 - 1153 variables -> 3020
                10, 12, // 1154 - 1163 variables -> 3044
                9, 12, // 1164 - 1172 variables -> 3068
                6, 8, // 1173 - 1178 variables -> 3084
                9, 12, // 1179 - 1187 variables -> 3108
                5, 6, // 1188 - 1192 variables -> 3120
                3, 4, // 1193 - 1195 variables -> 3128
                4, 6, // 1196 - 1199 variables -> 3140
                7, 8, // 1200 - 1206 variables -> 3156
                3, 4, // 1207 - 1209 variables -> 3164
                6, 8, // 1210 - 1215 variables -> 3180
                3, 4, // 1216 - 1218 variables -> 3188
                11, 14, // 1219 - 1229 variables -> 3216
                3, 4, // 1230 - 1232 variables -> 3224
                4, 6, // 1233 - 1236 variables -> 3236
                2, 2, // 1237 - 1238 variables -> 3240
                3, 4, // 1239 - 1241 variables -> 3248
                5, 6, // 1242 - 1246 variables -> 3260
                1, 2, // 1247 variables -> 3264
                5, 6, // 1248 - 1252 variables -> 3276
                7, 10, // 1253 - 1259 variables -> 3296
                16, 20, // 1260 - 1275 variables -> 3336
                4, 6, // 1276 - 1279 variables -> 3348
                4, 4, // 1280 - 1283 variables -> 3356
                1, 2, // 1284 variables -> 3360
                19, 24, // 1285 - 1303 variables -> 3408
                3, 4, // 1304 - 1306 variables -> 3416
                1, 2, // 1307 variables -> 3420
                8, 10, // 1308 - 1315 variables -> 3440
                9, 12, // 1316 - 1324 variables -> 3464
                2, 2, // 1325 - 1326 variables -> 3468
                7, 10, // 1327 - 1333 variables -> 3488
                6, 8, // 1334 - 1339 variables -> 3504
                5, 6, // 1340 - 1344 variables -> 3516
                5, 6, // 1345 - 1349 variables -> 3528
                4, 6, // 1350 - 1353 variables -> 3540
                14, 18, // 1354 - 1367 variables -> 3576
                5, 6, // 1368 - 1372 variables -> 3588
                3, 4, // 1373 - 1375 variables -> 3596
                1, 2, // 1376 variables -> 3600
                10, 12, // 1377 - 1386 variables -> 3624
                7, 10, // 1387 - 1393 variables -> 3644
                10, 12, // 1394 - 1403 variables -> 3668
                6, 8, // 1404 - 1409 variables -> 3684
                12, 16, // 1410 - 1421 variables -> 3716
                11, 14, // 1422 - 1432 variables -> 3744
                4, 6, // 1433 - 1436 variables -> 3756
                3, 4, // 1437 - 1439 variables -> 3764
                2, 2, // 1440 - 1441 variables -> 3768
                3, 4, // 1442 - 1444 variables -> 3776
                2, 2, // 1445 - 1446 variables -> 3780
                7, 10, // 1447 - 1453 variables -> 3800
                10, 12, // 1454 - 1463 variables -> 3824
                4, 6, // 1464 - 1467 variables -> 3836
                5, 6, // 1468 - 1472 variables -> 3848
                14, 18, // 1473 - 1486 variables -> 3884
                1, 2, // 1487 variables -> 3888
                12, 16, // 1488 - 1499 variables -> 3920
                2, 2, // 1500 - 1501 variables -> 3924
                17, 22, // 1502 - 1518 variables -> 3968
                5, 6, // 1519 - 1523 variables -> 3980
                6, 8, // 1524 - 1529 variables -> 3996
                4, 6, // 1530 - 1533 variables -> 4008
                3, 4, // 1534 - 1536 variables -> 4016
                2, 2, // 1537 - 1538 variables -> 4020
                3, 4, // 1539 - 1541 variables -> 4028
                6, 8, // 1542 - 1547 variables -> 4044
                5, 6, // 1548 - 1552 variables -> 4056
                7, 10, // 1553 - 1559 variables -> 4076
                2, 2, // 1560 - 1561 variables -> 4080
                8, 10, // 1562 - 1569 variables -> 4100
                10, 14, // 1570 - 1579 variables -> 4128
                8, 10, // 1580 - 1587 variables -> 4148
                5, 6, // 1588 - 1592 variables -> 4160
                9, 12, // 1593 - 1601 variables -> 4184
                2, 2, // 1602 - 1603 variables -> 4188
                3, 4, // 1604 - 1606 variables -> 4196
                1, 2, // 1607 variables -> 4200
                8, 10, // 1608 - 1615 variables -> 4220
                9, 12, // 1616 - 1624 variables -> 4244
                2, 2, // 1625 - 1626 variables -> 4248
                12, 16, // 1627 - 1638 variables -> 4280
                1, 2, // 1639 variables -> 4284
                5, 6, // 1640 - 1644 variables -> 4296
                3, 4, // 1645 - 1647 variables -> 4304
                2, 2, // 1648 - 1649 variables -> 4308
                7, 10, // 1650 - 1656 variables -> 4328
                7, 8, // 1657 - 1663 variables -> 4344
                13, 18, // 1664 - 1676 variables -> 4380
                19, 24, // 1677 - 1695 variables -> 4428
                3, 4, // 1696 - 1698 variables -> 4436
                5, 6, // 1699 - 1703 variables -> 4448
                6, 8, // 1704 - 1709 variables -> 4464
                12, 16, // 1710 - 1721 variables -> 4496
                2, 2, // 1722 - 1723 variables -> 4500
                3, 4, // 1724 - 1726 variables -> 4508
                6, 8, // 1727 - 1732 variables -> 4524
                12, 16, // 1733 - 1744 variables -> 4556
                2, 2, // 1745 - 1746 variables -> 4560
                3, 4, // 1747 - 1749 variables -> 4568
                6, 8, // 1750 - 1755 variables -> 4584
                4, 6, // 1756 - 1759 variables -> 4596
                5, 6, // 1760 - 1764 variables -> 4608
                3, 4, // 1765 - 1767 variables -> 4616
                9, 12, // 1768 - 1776 variables -> 4640
                2, 2, // 1777 - 1778 variables -> 4644
                17, 22, // 1779 - 1795 variables -> 4688
                4, 6, // 1796 - 1799 variables -> 4700
                2, 2, // 1800 - 1801 variables -> 4704
                5, 6, // 1802 - 1806 variables -> 4716
                3, 4, // 1807 - 1809 variables -> 4724
                4, 6, // 1810 - 1813 variables -> 4736
                11, 14, // 1814 - 1824 variables -> 4764
                5, 6, // 1825 - 1829 variables -> 4776
                3, 4, // 1830 - 1832 variables -> 4784
                1, 2, // 1833 variables -> 4788
                5, 6, // 1834 - 1838 variables -> 4800
                3, 4, // 1839 - 1841 variables -> 4808
                5, 6, // 1842 - 1846 variables -> 4820
                9, 12, // 1847 - 1855 variables -> 4844
                4, 6, // 1856 - 1859 variables -> 4856
                5, 6, // 1860 - 1864 variables -> 4868
                11, 14, // 1865 - 1875 variables -> 4896
                3, 4, // 1876 - 1878 variables -> 4904
                5, 6, // 1879 - 1883 variables -> 4916
                9, 12, // 1884 - 1892 variables -> 4940
                6, 8, // 1893 - 1898 variables -> 4956
                5, 6, // 1899 - 1903 variables -> 4968
                3, 4, // 1904 - 1906 variables -> 4976
                20, 26, // 1907 - 1926 variables -> 5028
                13, 18, // 1927 - 1939 variables -> 5064
                8, 10, // 1940 - 1947 variables -> 5084
                6, 8, // 1948 - 1953 variables -> 5100
                3, 4, // 1954 - 1956 variables -> 5108
                5, 6, // 1957 - 1961 variables -> 5120
                2, 2, // 1962 - 1963 variables -> 5124
                4, 6, // 1964 - 1967 variables -> 5136
                17, 22, // 1968 - 1984 variables -> 5180
                9, 12, // 1985 - 1993 variables -> 5204
                2, 2, // 1994 - 1995 variables -> 5208
                12, 16, // 1996 - 2007 variables -> 5240
                6, 8, // 2008 - 2013 variables -> 5256
                3, 4, // 2014 - 2016 variables -> 5264
                10, 12, // 2017 - 2026 variables -> 5288
                10, 14, // 2027 - 2036 variables -> 5316
                8, 10, // 2037 - 2044 variables -> 5336
                2, 2, // 2045 - 2046 variables -> 5340
                3, 4, // 2047 - 2049 variables -> 5348
                6, 8, // 2050 - 2055 variables -> 5364
                4, 6, // 2056 - 2059 variables -> 5376
                5, 6, // 2060 - 2064 variables -> 5388
                3, 4, // 2065 - 2067 variables -> 5396
                2, 2, // 2068 - 2069 variables -> 5400
                3, 4, // 2070 - 2072 variables -> 5408
                4, 6, // 2073 - 2076 variables -> 5420
                7, 8, // 2077 - 2083 variables -> 5436
                3, 4, // 2084 - 2086 variables -> 5444
                1, 2, // 2087 variables -> 5448
                5, 6, // 2088 - 2092 variables -> 5460
                7, 10, // 2093 - 2099 variables -> 5480
                2, 2, // 2100 - 2101 variables -> 5484
                8, 10, // 2102 - 2109 variables -> 5504
                6, 8, // 2110 - 2115 variables -> 5520
                3, 4, // 2116 - 2118 variables -> 5528
                11, 14, // 2119 - 2129 variables -> 5556
                7, 10, // 2130 - 2136 variables -> 5576
                10, 12, // 2137 - 2146 variables -> 5600
                1, 2, // 2147 variables -> 5604
                5, 6, // 2148 - 2152 variables -> 5616
                3, 4, // 2153 - 2155 variables -> 5624
                1, 2, // 2156 variables -> 5628
                13, 16, // 2157 - 2169 variables -> 5660
                10, 14, // 2170 - 2179 variables -> 5688
                4, 4, // 2180 - 2183 variables -> 5696
                4, 6, // 2184 - 2187 variables -> 5708
                6, 8, // 2188 - 2193 variables -> 5724
                5, 6, // 2194 - 2198 variables -> 5736
                3, 4, // 2199 - 2201 variables -> 5744
                14, 18, // 2202 - 2215 variables -> 5780
                6, 8, // 2216 - 2221 variables -> 5796
                8, 10, // 2222 - 2229 variables -> 5816
                4, 6, // 2230 - 2233 variables -> 5828
                5, 6, // 2234 - 2238 variables -> 5840
                6, 8, // 2239 - 2244 variables -> 5856
                8, 10, // 2245 - 2252 variables -> 5876
                9, 12, // 2253 - 2261 variables -> 5900
                11, 14, // 2262 - 2272 variables -> 5928
                3, 4, // 2273 - 2275 variables -> 5936
                4, 6, // 2276 - 2279 variables -> 5948
                5, 6, // 2280 - 2284 variables -> 5960
                2, 2, // 2285 - 2286 variables -> 5964
                21, 28, // 2287 - 2307 variables -> 6020
                2, 2, // 2308 - 2309 variables -> 6024
                7, 10, // 2310 - 2316 variables -> 6044
                7, 8, // 2317 - 2323 variables -> 6060
                3, 4, // 2324 - 2326 variables -> 6068
                10, 14, // 2327 - 2336 variables -> 6096
                3, 4, // 2337 - 2339 variables -> 6104
                7, 8, // 2340 - 2346 variables -> 6120
                9, 12, // 2347 - 2355 variables -> 6144
                4, 6, // 2356 - 2359 variables -> 6156
                10, 12, // 2360 - 2369 variables -> 6180
                3, 4, // 2370 - 2372 variables -> 6188
                4, 6, // 2373 - 2376 variables -> 6200
                16, 20, // 2377 - 2392 variables -> 6240
                7, 10, // 2393 - 2399 variables -> 6260
                2, 2, // 2400 - 2401 variables -> 6264
                12, 16, // 2402 - 2413 variables -> 6296
                20, 26, // 2414 - 2433 variables -> 6348
                3, 4, // 2434 - 2436 variables -> 6356
                2, 2, // 2437 - 2438 variables -> 6360
                9, 12, // 2439 - 2447 variables -> 6384
                5, 6, // 2448 - 2452 variables -> 6396
                3, 4, // 2453 - 2455 variables -> 6404
                9, 12, // 2456 - 2464 variables -> 6428
                5, 6, // 2465 - 2469 variables -> 6440
                6, 8, // 2470 - 2475 variables -> 6456
                3, 4, // 2476 - 2478 variables -> 6464
                6, 8, // 2479 - 2484 variables -> 6480
                17, 22, // 2485 - 2501 variables -> 6524
                2, 2, // 2502 - 2503 variables -> 6528
                3, 4, // 2504 - 2506 variables -> 6536
                1, 2, // 2507 variables -> 6540
                9, 12, // 2508 - 2516 variables -> 6564
                22, 28, // 2517 - 2538 variables -> 6620
                1, 2, // 2539 variables -> 6624
                5, 6, // 2540 - 2544 variables -> 6636
                5, 6, // 2545 - 2549 variables -> 6648
                4, 6, // 2550 - 2553 variables -> 6660
                3, 4, // 2554 - 2556 variables -> 6668
                5, 6, // 2557 - 2561 variables -> 6680
                2, 2, // 2562 - 2563 variables -> 6684
                9, 12, // 2564 - 2572 variables -> 6708
                3, 4, // 2573 - 2575 variables -> 6716
                9, 12, // 2576 - 2584 variables -> 6740
                2, 2, // 2585 - 2586 variables -> 6744
                7, 10, // 2587 - 2593 variables -> 6764
                2, 2, // 2594 - 2595 variables -> 6768
                12, 16, // 2596 - 2607 variables -> 6800
                2, 2, // 2608 - 2609 variables -> 6804
                12, 16, // 2610 - 2621 variables -> 6836
                5, 6, // 2622 - 2626 variables -> 6848
                15, 20, // 2627 - 2641 variables -> 6888
                12, 16, // 2642 - 2653 variables -> 6920
                6, 8, // 2654 - 2659 variables -> 6936
                4, 4, // 2660 - 2663 variables -> 6944
                1, 2, // 2664 variables -> 6948
                3, 4, // 2665 - 2667 variables -> 6956
                2, 2, // 2668 - 2669 variables -> 6960
                17, 22, // 2670 - 2686 variables -> 7004
                6, 8, // 2687 - 2692 variables -> 7020
                9, 12, // 2693 - 2701 variables -> 7044
                5, 6, // 2702 - 2706 variables -> 7056
                7, 10, // 2707 - 2713 variables -> 7076
                2, 2, // 2714 - 2715 variables -> 7080
                3, 4, // 2716 - 2718 variables -> 7088
                5, 6, // 2719 - 2723 variables -> 7100
                1, 2, // 2724 variables -> 7104
                5, 6, // 2725 - 2729 variables -> 7116
                7, 10, // 2730 - 2736 variables -> 7136
                2, 2, // 2737 - 2738 variables -> 7140
                9, 12, // 2739 - 2747 variables -> 7164
                8, 10, // 2748 - 2755 variables -> 7184
                1, 2, // 2756 variables -> 7188
                8, 10, // 2757 - 2764 variables -> 7208
                11, 14, // 2765 - 2775 variables -> 7236
                4, 6, // 2776 - 2779 variables -> 7248
                4, 4, // 2780 - 2783 variables -> 7256
                4, 6, // 2784 - 2787 variables -> 7268
                6, 8, // 2788 - 2793 variables -> 7284
                5, 6, // 2794 - 2798 variables -> 7296
                5, 6, // 2799 - 2803 variables -> 7308
                12, 16, // 2804 - 2815 variables -> 7340
                9, 12, // 2816 - 2824 variables -> 7364
                2, 2, // 2825 - 2826 variables -> 7368
                3, 4, // 2827 - 2829 variables -> 7376
                10, 14, // 2830 - 2839 variables -> 7404
                5, 6, // 2840 - 2844 variables -> 7416
                3, 4, // 2845 - 2847 variables -> 7424
                6, 8, // 2848 - 2853 variables -> 7440
                8, 10, // 2854 - 2861 variables -> 7460
                6, 8, // 2862 - 2867 variables -> 7476
                5, 6, // 2868 - 2872 variables -> 7488
                4, 6, // 2873 - 2876 variables -> 7500
                17, 22, // 2877 - 2893 variables -> 7544
                5, 6, // 2894 - 2898 variables -> 7556
                1, 2, // 2899 variables -> 7560
                8, 10, // 2900 - 2907 variables -> 7580
                11, 14, // 2908 - 2918 variables -> 7608
                3, 4, // 2919 - 2921 variables -> 7616
                5, 6, // 2922 - 2926 variables -> 7628
                13, 18, // 2927 - 2939 variables -> 7664
                2, 2, // 2940 - 2941 variables -> 7668
                8, 10, // 2942 - 2949 variables -> 7688
                10, 14, // 2950 - 2959 variables -> 7716
                4, 4, // 2960 - 2963 variables -> 7724
                1, 2, // 2964 variables -> 7728
                8, 10, // 2965 - 2972 variables -> 7748
                11, 14, // 2973 - 2983 variables -> 7776
                3, 4, // 2984 - 2986 variables -> 7784
                6, 8, // 2987 - 2992 variables -> 7800
                14, 18, // 2993 - 3006 variables -> 7836
                3, 4, // 3007 - 3009 variables -> 7844
                4, 6, // 3010 - 3013 variables -> 7856
                2, 2, // 3014 - 3015 variables -> 7860
                3, 4, // 3016 - 3018 variables -> 7868
                5, 6, // 3019 - 3023 variables -> 7880
                1, 2, // 3024 variables -> 7884
                9, 12, // 3025 - 3033 variables -> 7908
                3, 4, // 3034 - 3036 variables -> 7916
                16, 20, // 3037 - 3052 variables -> 7956
                17, 22, // 3053 - 3069 variables -> 8000
                9, 12, // 3070 - 3078 variables -> 8024
                1, 2, // 3079 variables -> 8028
                4, 4, // 3080 - 3083 variables -> 8036
                4, 6, // 3084 - 3087 variables -> 8048
                5, 6, // 3088 - 3092 variables -> 8060
                1, 2, // 3093 variables -> 8064
                5, 6, // 3094 - 3098 variables -> 8076
                17, 22, // 3099 - 3115 variables -> 8120
                1, 2, // 3116 variables -> 8124
                5, 6, // 3117 - 3121 variables -> 8136
                12, 16, // 3122 - 3133 variables -> 8168
                5, 6, // 3134 - 3138 variables -> 8180
                9, 12, // 3139 - 3147 variables -> 8204
                2, 2, // 3148 - 3149 variables -> 8208
                4, 6, // 3150 - 3153 variables -> 8220
                10, 12, // 3154 - 3163 variables -> 8244
                12, 16, // 3164 - 3175 variables -> 8276
                1, 2, // 3176 variables -> 8280
                3, 4, // 3177 - 3179 variables -> 8288
                5, 6, // 3180 - 3184 variables -> 8300
                11, 14, // 3185 - 3195 variables -> 8328
                3, 4, // 3196 - 3198 variables -> 8336
                1, 2, // 3199 variables -> 8340
                14, 18, // 3200 - 3213 variables -> 8376
                19, 24, // 3214 - 3232 variables -> 8424
                7, 10, // 3233 - 3239 variables -> 8444
                5, 6, // 3240 - 3244 variables -> 8456
                2, 2, // 3245 - 3246 variables -> 8460
                7, 10, // 3247 - 3253 variables -> 8480
                2, 2, // 3254 - 3255 variables -> 8484
                8, 10, // 3256 - 3263 variables -> 8504
                1, 2, // 3264 variables -> 8508
                8, 10, // 3265 - 3272 variables -> 8528
                4, 6, // 3273 - 3276 variables -> 8540
                2, 2, // 3277 - 3278 variables -> 8544
                8, 10, // 3279 - 3286 variables -> 8564
                1, 2, // 3287 variables -> 8568
                8, 10, // 3288 - 3295 variables -> 8588
                4, 6, // 3296 - 3299 variables -> 8600
                7, 8, // 3300 - 3306 variables -> 8616
                23, 30, // 3307 - 3329 variables -> 8676
                7, 10, // 3330 - 3336 variables -> 8696
                2, 2, // 3337 - 3338 variables -> 8700
                8, 10, // 3339 - 3346 variables -> 8720
                6, 8, // 3347 - 3352 variables -> 8736
                4, 6, // 3353 - 3356 variables -> 8748
                8, 10, // 3357 - 3364 variables -> 8768
                14, 18, // 3365 - 3378 variables -> 8804
                5, 6, // 3379 - 3383 variables -> 8816
                9, 12, // 3384 - 3392 variables -> 8840
                9, 12, // 3393 - 3401 variables -> 8864
                2, 2, // 3402 - 3403 variables -> 8868
                13, 18, // 3404 - 3416 variables -> 8904
                5, 6, // 3417 - 3421 variables -> 8916
                3, 4, // 3422 - 3424 variables -> 8924
                5, 6, // 3425 - 3429 variables -> 8936
                4, 6, // 3430 - 3433 variables -> 8948
                14, 18, // 3434 - 3447 variables -> 8984
                2, 2, // 3448 - 3449 variables -> 8988
                7, 10, // 3450 - 3456 variables -> 9008
                11, 14, // 3457 - 3467 variables -> 9036
                5, 6, // 3468 - 3472 variables -> 9048
                3, 4, // 3473 - 3475 variables -> 9056
                1, 2, // 3476 variables -> 9060
                3, 4, // 3477 - 3479 variables -> 9068
                19, 24, // 3480 - 3498 variables -> 9116
                1, 2, // 3499 variables -> 9120
                10, 12, // 3500 - 3509 variables -> 9144
                4, 6, // 3510 - 3513 variables -> 9156
                13, 16, // 3514 - 3526 variables -> 9188
                6, 8, // 3527 - 3532 variables -> 9204
                4, 6, // 3533 - 3536 variables -> 9216
                5, 6, // 3537 - 3541 variables -> 9228
                14, 18, // 3542 - 3555 variables -> 9264
                12, 16, // 3556 - 3567 variables -> 9296
                2, 2, // 3568 - 3569 variables -> 9300
                3, 4, // 3570 - 3572 variables -> 9308
                4, 6, // 3573 - 3576 variables -> 9320
                2, 2, // 3577 - 3578 variables -> 9324
                5, 6, // 3579 - 3583 variables -> 9336
                4, 6, // 3584 - 3587 variables -> 9348
                8, 10, // 3588 - 3595 variables -> 9368
                4, 6, // 3596 - 3599 variables -> 9380
                10, 12, // 3600 - 3609 variables -> 9404
                9, 12, // 3610 - 3618 variables -> 9428
                14, 18, // 3619 - 3632 variables -> 9464
                1, 2, // 3633 variables -> 9468
                5, 6, // 3634 - 3638 variables -> 9480
                3, 4, // 3639 - 3641 variables -> 9488
                14, 18, // 3642 - 3655 variables -> 9524
                6, 8, // 3656 - 3661 variables -> 9540
                18, 24, // 3662 - 3679 variables -> 9588
                4, 4, // 3680 - 3683 variables -> 9596
                1, 2, // 3684 variables -> 9600
                3, 4, // 3685 - 3687 variables -> 9608
                5, 6, // 3688 - 3692 variables -> 9620
                1, 2, // 3693 variables -> 9624
                10, 12, // 3694 - 3703 variables -> 9648
                3, 4, // 3704 - 3706 variables -> 9656
                10, 14, // 3707 - 3716 variables -> 9684
                23, 30, // 3717 - 3739 variables -> 9744
                8, 10, // 3740 - 3747 variables -> 9764
                5, 6, // 3748 - 3752 variables -> 9776
                9, 12, // 3753 - 3761 variables -> 9800
                11, 14, // 3762 - 3772 variables -> 9828
                4, 6, // 3773 - 3776 variables -> 9840
                8, 10, // 3777 - 3784 variables -> 9860
                9, 12, // 3785 - 3793 variables -> 9884
                2, 2, // 3794 - 3795 variables -> 9888
                3, 4, // 3796 - 3798 variables -> 9896
                5, 6, // 3799 - 3803 variables -> 9908
                6, 8, // 3804 - 3809 variables -> 9924
                4, 6, // 3810 - 3813 variables -> 9936
                8, 10, // 3814 - 3821 variables -> 9956
                2, 2, // 3822 - 3823 variables -> 9960
                3, 4, // 3824 - 3826 variables -> 9968
                10, 14, // 3827 - 3836 variables -> 9996
                5, 6, // 3837 - 3841 variables -> 10008
                5, 6, // 3842 - 3846 variables -> 10020
                3, 4, // 3847 - 3849 variables -> 10028
                4, 6, // 3850 - 3853 variables -> 10040
                2, 2, // 3854 - 3855 variables -> 10044
                8, 10, // 3856 - 3863 variables -> 10064
                1, 2, // 3864 variables -> 10068
                12, 16, // 3865 - 3876 variables -> 10100
                10, 12, // 3877 - 3886 variables -> 10124
                6, 8, // 3887 - 3892 variables -> 10140
                14, 18, // 3893 - 3906 variables -> 10176
                3, 4, // 3907 - 3909 variables -> 10184
                4, 6, // 3910 - 3913 variables -> 10196
                10, 12, // 3914 - 3923 variables -> 10220
                1, 2, // 3924 variables -> 10224
                5, 6, // 3925 - 3929 variables -> 10236
                4, 6, // 3930 - 3933 variables -> 10248
                5, 6, // 3934 - 3938 variables -> 10260
                21, 28, // 3939 - 3959 variables -> 10316
                5, 6, // 3960 - 3964 variables -> 10328
                11, 14, // 3965 - 3975 variables -> 10356
                3, 4, // 3976 - 3978 variables -> 10364
                6, 8, // 3979 - 3984 variables -> 10380
                8, 10, // 3985 - 3992 variables -> 10400
                6, 8, // 3993 - 3998 variables -> 10416
                9, 12, // 3999 - 4007 variables -> 10440
                14, 18, // 4008 - 4021 variables -> 10476
                3, 4, // 4022 - 4024 variables -> 10484
                2, 2, // 4025 - 4026 variables -> 10488
                3, 4, // 4027 - 4029 variables -> 10496
                18, 24, // 4030 - 4047 variables -> 10544
                9, 12, // 4048 - 4056 variables -> 10568
                5, 6, // 4057 - 4061 variables -> 10580
                2, 2, // 4062 - 4063 variables -> 10584
                12, 16, // 4064 - 4075 variables -> 10616
                4, 6, // 4076 - 4079 variables -> 10628
                5, 6, // 4080 - 4084 variables -> 10640
                11, 14, // 4085 - 4095 variables -> 10668
                8, 10, // 4096 - 4103 variables -> 10688
                10, 14, // 4104 - 4113 variables -> 10716
                3, 4, // 4114 - 4116 variables -> 10724
                23, 30, // 4117 - 4139 variables -> 10784
                5, 6, // 4140 - 4144 variables -> 10796
                5, 6, // 4145 - 4149 variables -> 10808
                4, 6, // 4150 - 4153 variables -> 10820
                6, 8, // 4154 - 4159 variables -> 10836
                5, 6, // 4160 - 4164 variables -> 10848
                3, 4, // 4165 - 4167 variables -> 10856
                2, 2, // 4168 - 4169 variables -> 10860
                9, 12, // 4170 - 4178 variables -> 10884
                5, 6, // 4179 - 4183 variables -> 10896
                3, 4, // 4184 - 4186 variables -> 10904
                1, 2, // 4187 variables -> 10908
                5, 6, // 4188 - 4192 variables -> 10920
                17, 22, // 4193 - 4209 variables -> 10964
                4, 6, // 4210 - 4213 variables -> 10976
                2, 2, // 4214 - 4215 variables -> 10980
                3, 4, // 4216 - 4218 variables -> 10988
                14, 18, // 4219 - 4232 variables -> 11024
                1, 2, // 4233 variables -> 11028
                3, 4, // 4234 - 4236 variables -> 11036
                10, 12, // 4237 - 4246 variables -> 11060
                1, 2, // 4247 variables -> 11064
                5, 6, // 4248 - 4252 variables -> 11076
                3, 4, // 4253 - 4255 variables -> 11084
                20, 26, // 4256 - 4275 variables -> 11136
                4, 6, // 4276 - 4279 variables -> 11148
                5, 6, // 4280 - 4284 variables -> 11160
                3, 4, // 4285 - 4287 variables -> 11168
                6, 8, // 4288 - 4293 variables -> 11184
                8, 10, // 4294 - 4301 variables -> 11204
                25, 32, // 4302 - 4326 variables -> 11268
                12, 16, // 4327 - 4338 variables -> 11300
                1, 2, // 4339 variables -> 11304
                5, 6, // 4340 - 4344 variables -> 11316
                3, 4, // 4345 - 4347 variables -> 11324
                2, 2, // 4348 - 4349 variables -> 11328
                3, 4, // 4350 - 4352 variables -> 11336
                1, 2, // 4353 variables -> 11340
                8, 10, // 4354 - 4361 variables -> 11360
                11, 14, // 4362 - 4372 variables -> 11388
                4, 6, // 4373 - 4376 variables -> 11400
                3, 4, // 4377 - 4379 variables -> 11408
                7, 8, // 4380 - 4386 variables -> 11424
                7, 10, // 4387 - 4393 variables -> 11444
                5, 6, // 4394 - 4398 variables -> 11456
                15, 20, // 4399 - 4413 variables -> 11496
                3, 4, // 4414 - 4416 variables -> 11504
                2, 2, // 4417 - 4418 variables -> 11508
                5, 6, // 4419 - 4423 variables -> 11520
                23, 30, // 4424 - 4446 variables -> 11580
                3, 4, // 4447 - 4449 variables -> 11588
                6, 8, // 4450 - 4455 variables -> 11604
                8, 10, // 4456 - 4463 variables -> 11624
                4, 6, // 4464 - 4467 variables -> 11636
                5, 6, // 4468 - 4472 variables -> 11648
                6, 8, // 4473 - 4478 variables -> 11664
                5, 6, // 4479 - 4483 variables -> 11676
                9, 12, // 4484 - 4492 variables -> 11700
                3, 4, // 4493 - 4495 variables -> 11708
                4, 6, // 4496 - 4499 variables -> 11720
                2, 2, // 4500 - 4501 variables -> 11724
                5, 6, // 4502 - 4506 variables -> 11736
                3, 4, // 4507 - 4509 variables -> 11744
                4, 6, // 4510 - 4513 variables -> 11756
                2, 2, // 4514 - 4515 variables -> 11760
                8, 10, // 4516 - 4523 variables -> 11780
                1, 2, // 4524 variables -> 11784
                12, 16, // 4525 - 4536 variables -> 11816
                5, 6, // 4537 - 4541 variables -> 11828
                15, 20, // 4542 - 4556 variables -> 11868
                3, 4, // 4557 - 4559 variables -> 11876
                10, 12, // 4560 - 4569 variables -> 11900
                10, 14, // 4570 - 4579 variables -> 11928
                22, 28, // 4580 - 4601 variables -> 11984
                5, 6, // 4602 - 4606 variables -> 11996
                15, 20, // 4607 - 4621 variables -> 12036
                3, 4, // 4622 - 4624 variables -> 12044
                14, 18, // 4625 - 4638 variables -> 12080
                6, 8, // 4639 - 4644 variables -> 12096
                5, 6, // 4645 - 4649 variables -> 12108
                3, 4, // 4650 - 4652 variables -> 12116
                4, 6, // 4653 - 4656 variables -> 12128
                11, 14, // 4657 - 4667 variables -> 12156
                5, 6, // 4668 - 4672 variables -> 12168
                4, 6, // 4673 - 4676 variables -> 12180
                8, 10, // 4677 - 4684 variables -> 12200
                2, 2, // 4685 - 4686 variables -> 12204
                7, 10, // 4687 - 4693 variables -> 12224
                10, 12, // 4694 - 4703 variables -> 12248
                6, 8, // 4704 - 4709 variables -> 12264
                7, 10, // 4710 - 4716 variables -> 12284
                2, 2, // 4717 - 4718 variables -> 12288
                8, 10, // 4719 - 4726 variables -> 12308
                6, 8, // 4727 - 4732 variables -> 12324
                9, 12, // 4733 - 4741 variables -> 12348
                8, 10, // 4742 - 4749 variables -> 12368
                18, 24, // 4750 - 4767 variables -> 12416
                2, 2, // 4768 - 4769 variables -> 12420
                3, 4, // 4770 - 4772 variables -> 12428
                6, 8, // 4773 - 4778 variables -> 12444
                5, 6, // 4779 - 4783 variables -> 12456
                3, 4, // 4784 - 4786 variables -> 12464
                6, 8, // 4787 - 4792 variables -> 12480
                14, 18, // 4793 - 4806 variables -> 12516
                7, 10, // 4807 - 4813 variables -> 12536
                5, 6, // 4814 - 4818 variables -> 12548
                5, 6, // 4819 - 4823 variables -> 12560
                1, 2, // 4824 variables -> 12564
                5, 6, // 4825 - 4829 variables -> 12576
                7, 10, // 4830 - 4836 variables -> 12596
                10, 12, // 4837 - 4846 variables -> 12620
                1, 2, // 4847 variables -> 12624
                8, 10, // 4848 - 4855 variables -> 12644
                4, 6, // 4856 - 4859 variables -> 12656
                5, 6, // 4860 - 4864 variables -> 12668
                5, 6, // 4865 - 4869 variables -> 12680
                6, 8, // 4870 - 4875 variables -> 12696
                4, 6, // 4876 - 4879 variables -> 12708
                8, 10, // 4880 - 4887 variables -> 12728
                5, 6, // 4888 - 4892 variables -> 12740
                1, 2, // 4893 variables -> 12744
                5, 6, // 4894 - 4898 variables -> 12756
                5, 6, // 4899 - 4903 variables -> 12768
                4, 6, // 4904 - 4907 variables -> 12780
                8, 10, // 4908 - 4915 variables -> 12800
                6, 8, // 4916 - 4921 variables -> 12816
                18, 24, // 4922 - 4939 variables -> 12864
                5, 6, // 4940 - 4944 variables -> 12876
                17, 22, // 4945 - 4961 variables -> 12920
                2, 2, // 4962 - 4963 variables -> 12924
                13, 18, // 4964 - 4976 variables -> 12960
                3, 4, // 4977 - 4979 variables -> 12968
                7, 8, // 4980 - 4986 variables -> 12984
                7, 10, // 4987 - 4993 variables -> 13004
                23, 30, // 4994 - 5016 variables -> 13064
                7, 8, // 5017 - 5023 variables -> 13080
                13, 18, // 5024 - 5036 variables -> 13116
                3, 4, // 5037 - 5039 variables -> 13124
                2, 2, // 5040 - 5041 variables -> 13128
                8, 10, // 5042 - 5049 variables -> 13148
                4, 6, // 5050 - 5053 variables -> 13160
                2, 2, // 5054 - 5055 variables -> 13164
                4, 6, // 5056 - 5059 variables -> 13176
                4, 4, // 5060 - 5063 variables -> 13184
                13, 18, // 5064 - 5076 variables -> 13220
                7, 8, // 5077 - 5083 variables -> 13236
                9, 12, // 5084 - 5092 variables -> 13260
                14, 18, // 5093 - 5106 variables -> 13296
                12, 16, // 5107 - 5118 variables -> 13328
                5, 6, // 5119 - 5123 variables -> 13340
                1, 2, // 5124 variables -> 13344
                9, 12, // 5125 - 5133 variables -> 13368
                5, 6, // 5134 - 5138 variables -> 13380
                8, 10, // 5139 - 5146 variables -> 13400
                1, 2, // 5147 variables -> 13404
                8, 10, // 5148 - 5155 variables -> 13424
                1, 2, // 5156 variables -> 13428
                5, 6, // 5157 - 5161 variables -> 13440
                8, 10, // 5162 - 5169 variables -> 13460
                10, 14, // 5170 - 5179 variables -> 13488
                4, 4, // 5180 - 5183 variables -> 13496
                18, 24, // 5184 - 5201 variables -> 13544
                2, 2, // 5202 - 5203 variables -> 13548
                12, 16, // 5204 - 5215 variables -> 13580
                1, 2, // 5216 variables -> 13584
                8, 10, // 5217 - 5224 variables -> 13604
                2, 2, // 5225 - 5226 variables -> 13608
                7, 10, // 5227 - 5233 variables -> 13628
                16, 20, // 5234 - 5249 variables -> 13668
                3, 4, // 5250 - 5252 variables -> 13676
                1, 2, // 5253 variables -> 13680
                3, 4, // 5254 - 5256 variables -> 13688
                7, 8, // 5257 - 5263 variables -> 13704
                12, 16, // 5264 - 5275 variables -> 13736
                4, 6, // 5276 - 5279 variables -> 13748
                5, 6, // 5280 - 5284 variables -> 13760
                2, 2, // 5285 - 5286 variables -> 13764
                9, 12, // 5287 - 5295 variables -> 13788
                12, 16, // 5296 - 5307 variables -> 13820
                6, 8, // 5308 - 5313 variables -> 13836
                3, 4, // 5314 - 5316 variables -> 13844
                5, 6, // 5317 - 5321 variables -> 13856
                23, 30, // 5322 - 5344 variables -> 13916
                2, 2, // 5345 - 5346 variables -> 13920
                7, 10, // 5347 - 5353 variables -> 13940
                2, 2, // 5354 - 5355 variables -> 13944
                4, 6, // 5356 - 5359 variables -> 13956
                4, 4, // 5360 - 5363 variables -> 13964
                4, 6, // 5364 - 5367 variables -> 13976
                5, 6, // 5368 - 5372 variables -> 13988
                6, 8, // 5373 - 5378 variables -> 14004
                5, 6, // 5379 - 5383 variables -> 14016
                3, 4, // 5384 - 5386 variables -> 14024
                9, 12, // 5387 - 5395 variables -> 14048
                4, 6, // 5396 - 5399 variables -> 14060
                7, 8, // 5400 - 5406 variables -> 14076
                9, 12, // 5407 - 5415 variables -> 14100
                3, 4, // 5416 - 5418 variables -> 14108
                11, 14, // 5419 - 5429 variables -> 14136
                9, 12, // 5430 - 5438 variables -> 14160
                8, 10, // 5439 - 5446 variables -> 14180
                18, 24, // 5447 - 5464 variables -> 14228
                5, 6, // 5465 - 5469 variables -> 14240
                9, 12, // 5470 - 5478 variables -> 14264
                5, 6, // 5479 - 5483 variables -> 14276
                1, 2, // 5484 variables -> 14280
                17, 22, // 5485 - 5501 variables -> 14324
                6, 8, // 5502 - 5507 variables -> 14340
                14, 18, // 5508 - 5521 variables -> 14376
                8, 10, // 5522 - 5529 variables -> 14396
                4, 6, // 5530 - 5533 variables -> 14408
                11, 14, // 5534 - 5544 variables -> 14436
                3, 4, // 5545 - 5547 variables -> 14444
                2, 2, // 5548 - 5549 variables -> 14448
                4, 6, // 5550 - 5553 variables -> 14460
                8, 10, // 5554 - 5561 variables -> 14480
                6, 8, // 5562 - 5567 variables -> 14496
                5, 6, // 5568 - 5572 variables -> 14508
                3, 4, // 5573 - 5575 variables -> 14516
                4, 6, // 5576 - 5579 variables -> 14528
                24, 30, // 5580 - 5603 variables -> 14588
                10, 14, // 5604 - 5613 variables -> 14616
                8, 10, // 5614 - 5621 variables -> 14636
                2, 2, // 5622 - 5623 variables -> 14640
                9, 12, // 5624 - 5632 variables -> 14664
                7, 10, // 5633 - 5639 variables -> 14684
                2, 2, // 5640 - 5641 variables -> 14688
                12, 16, // 5642 - 5653 variables -> 14720
                2, 2, // 5654 - 5655 variables -> 14724
                14, 18, // 5656 - 5669 variables -> 14760
                18, 24, // 5670 - 5687 variables -> 14808
                14, 18, // 5688 - 5701 variables -> 14844
                5, 6, // 5702 - 5706 variables -> 14856
                12, 16, // 5707 - 5718 variables -> 14888
                14, 18, // 5719 - 5732 variables -> 14924
                4, 6, // 5733 - 5736 variables -> 14936
                2, 2, // 5737 - 5738 variables -> 14940
                14, 18, // 5739 - 5752 variables -> 14976
                3, 4, // 5753 - 5755 variables -> 14984
                4, 6, // 5756 - 5759 variables -> 14996
                2, 2, // 5760 - 5761 variables -> 15000
                8, 10, // 5762 - 5769 variables -> 15020
                6, 8, // 5770 - 5775 variables -> 15036
                8, 10, // 5776 - 5783 variables -> 15056
                4, 6, // 5784 - 5787 variables -> 15068
                5, 6, // 5788 - 5792 variables -> 15080
                6, 8, // 5793 - 5798 variables -> 15096
                3, 4, // 5799 - 5801 variables -> 15104
                5, 6, // 5802 - 5806 variables -> 15116
                1, 2, // 5807 variables -> 15120
                8, 10, // 5808 - 5815 variables -> 15140
                1, 2, // 5816 variables -> 15144
                10, 12, // 5817 - 5826 variables -> 15168
                3, 4, // 5827 - 5829 variables -> 15176
                4, 6, // 5830 - 5833 variables -> 15188
                5, 6, // 5834 - 5838 variables -> 15200
                1, 2, // 5839 variables -> 15204
                10, 12, // 5840 - 5849 variables -> 15228
                3, 4, // 5850 - 5852 variables -> 15236
                11, 14, // 5853 - 5863 variables -> 15264
                13, 18, // 5864 - 5876 variables -> 15300
                3, 4, // 5877 - 5879 variables -> 15308
                5, 6, // 5880 - 5884 variables -> 15320
                15, 20, // 5885 - 5899 variables -> 15360
                4, 4, // 5900 - 5903 variables -> 15368
                6, 8, // 5904 - 5909 variables -> 15384
                4, 6, // 5910 - 5913 variables -> 15396
                3, 4, // 5914 - 5916 variables -> 15404
                7, 8, // 5917 - 5923 variables -> 15420
                3, 4, // 5924 - 5926 variables -> 15428
                10, 14, // 5927 - 5936 variables -> 15456
                5, 6, // 5937 - 5941 variables -> 15468
                3, 4, // 5942 - 5944 variables -> 15476
                11, 14, // 5945 - 5955 variables -> 15504
                9, 12, // 5956 - 5964 variables -> 15528
                3, 4, // 5965 - 5967 variables -> 15536
                2, 2, // 5968 - 5969 variables -> 15540
                23, 30, // 5970 - 5992 variables -> 15600
                3, 4, // 5993 - 5995 variables -> 15608
                18, 24, // 5996 - 6013 variables -> 15656
                5, 6, // 6014 - 6018 variables -> 15668
                5, 6, // 6019 - 6023 variables -> 15680
                9, 12, // 6024 - 6032 variables -> 15704
                9, 12, // 6033 - 6041 variables -> 15728
                11, 14, // 6042 - 6052 variables -> 15756
                4, 6, // 6053 - 6056 variables -> 15768
                3, 4, // 6057 - 6059 variables -> 15776
                2, 2, // 6060 - 6061 variables -> 15780
                3, 4, // 6062 - 6064 variables -> 15788
                14, 18, // 6065 - 6078 variables -> 15824
                5, 6, // 6079 - 6083 variables -> 15836
                9, 12, // 6084 - 6092 variables -> 15860
                6, 8, // 6093 - 6098 variables -> 15876
                5, 6, // 6099 - 6103 variables -> 15888
                3, 4, // 6104 - 6106 variables -> 15896
                9, 12, // 6107 - 6115 variables -> 15920
                1, 2, // 6116 variables -> 15924
                10, 12, // 6117 - 6126 variables -> 15948
                23, 30, // 6127 - 6149 variables -> 16008
                12, 16, // 6150 - 6161 variables -> 16040
                2, 2, // 6162 - 6163 variables -> 16044
                4, 6, // 6164 - 6167 variables -> 16056
                17, 22, // 6168 - 6184 variables -> 16100
                11, 14, // 6185 - 6195 variables -> 16128
                4, 6, // 6196 - 6199 variables -> 16140
                8, 10, // 6200 - 6207 variables -> 16160
                9, 12, // 6208 - 6216 variables -> 16184
                5, 6, // 6217 - 6221 variables -> 16196
                2, 2, // 6222 - 6223 variables -> 16200
                3, 4, // 6224 - 6226 variables -> 16208
                6, 8, // 6227 - 6232 variables -> 16224
                7, 10, // 6233 - 6239 variables -> 16244
                5, 6, // 6240 - 6244 variables -> 16256
                5, 6, // 6245 - 6249 variables -> 16268
                18, 24, // 6250 - 6267 variables -> 16316
                11, 14, // 6268 - 6278 variables -> 16344
                5, 6, // 6279 - 6283 variables -> 16356
                3, 4, // 6284 - 6286 variables -> 16364
                6, 8, // 6287 - 6292 variables -> 16380
                9, 12, // 6293 - 6301 variables -> 16404
                14, 18, // 6302 - 6315 variables -> 16440
                8, 10, // 6316 - 6323 variables -> 16460
                1, 2, // 6324 variables -> 16464
                8, 10, // 6325 - 6332 variables -> 16484
                1, 2, // 6333 variables -> 16488
                3, 4, // 6334 - 6336 variables -> 16496
                5, 6, // 6337 - 6341 variables -> 16508
                15, 20, // 6342 - 6356 variables -> 16548
                5, 6, // 6357 - 6361 variables -> 16560
                3, 4, // 6362 - 6364 variables -> 16568
                11, 14, // 6365 - 6375 variables -> 16596
                3, 4, // 6376 - 6378 variables -> 16604
                1, 2, // 6379 variables -> 16608
                4, 4, // 6380 - 6383 variables -> 16616
                10, 14, // 6384 - 6393 variables -> 16644
                5, 6, // 6394 - 6398 variables -> 16656
                9, 12, // 6399 - 6407 variables -> 16680
                19, 24, // 6408 - 6426 variables -> 16728
                7, 10, // 6427 - 6433 variables -> 16748
                5, 6, // 6434 - 6438 variables -> 16760
                6, 8, // 6439 - 6444 variables -> 16776
                8, 10, // 6445 - 6452 variables -> 16796
                1, 2, // 6453 variables -> 16800
                23, 30, // 6454 - 6476 variables -> 16860
                3, 4, // 6477 - 6479 variables -> 16868
                5, 6, // 6480 - 6484 variables -> 16880
                2, 2, // 6485 - 6486 variables -> 16884
                9, 12, // 6487 - 6495 variables -> 16908
                3, 4, // 6496 - 6498 variables -> 16916
                11, 14, // 6499 - 6509 variables -> 16944
                4, 6, // 6510 - 6513 variables -> 16956
                26, 34, // 6514 - 6539 variables -> 17024
                10, 12, // 6540 - 6549 variables -> 17048
                6, 8, // 6550 - 6555 variables -> 17064
                4, 6, // 6556 - 6559 variables -> 17076
                8, 10, // 6560 - 6567 variables -> 17096
                2, 2, // 6568 - 6569 variables -> 17100
                3, 4, // 6570 - 6572 variables -> 17108
                15, 20, // 6573 - 6587 variables -> 17148
                8, 10, // 6588 - 6595 variables -> 17168
                6, 8, // 6596 - 6601 variables -> 17184
                12, 16, // 6602 - 6613 variables -> 17216
                2, 2, // 6614 - 6615 variables -> 17220
                8, 10, // 6616 - 6623 variables -> 17240
                10, 14, // 6624 - 6633 variables -> 17268
                3, 4, // 6634 - 6636 variables -> 17276
                2, 2, // 6637 - 6638 variables -> 17280
                9, 12, // 6639 - 6647 variables -> 17304
                5, 6, // 6648 - 6652 variables -> 17316
                12, 16, // 6653 - 6664 variables -> 17348
                5, 6, // 6665 - 6669 variables -> 17360
                6, 8, // 6670 - 6675 variables -> 17376
                3, 4, // 6676 - 6678 variables -> 17384
                6, 8, // 6679 - 6684 variables -> 17400
                3, 4, // 6685 - 6687 variables -> 17408
                5, 6, // 6688 - 6692 variables -> 17420
                6, 8, // 6693 - 6698 variables -> 17436
                5, 6, // 6699 - 6703 variables -> 17448
                4, 6, // 6704 - 6707 variables -> 17460
                9, 12, // 6708 - 6716 variables -> 17484
                5, 6, // 6717 - 6721 variables -> 17496
                3, 4, // 6722 - 6724 variables -> 17504
                5, 6, // 6725 - 6729 variables -> 17516
                4, 6, // 6730 - 6733 variables -> 17528
                6, 8, // 6734 - 6739 variables -> 17544
                14, 18, // 6740 - 6753 variables -> 17580
                3, 4, // 6754 - 6756 variables -> 17588
                16, 20, // 6757 - 6772 variables -> 17628
                3, 4, // 6773 - 6775 variables -> 17636
                9, 12, // 6776 - 6784 variables -> 17660
                2, 2, // 6785 - 6786 variables -> 17664
                7, 10, // 6787 - 6793 variables -> 17684
                5, 6, // 6794 - 6798 variables -> 17696
                1, 2, // 6799 variables -> 17700
                8, 10, // 6800 - 6807 variables -> 17720
                9, 12, // 6808 - 6816 variables -> 17744
                2, 2, // 6817 - 6818 variables -> 17748
                3, 4, // 6819 - 6821 variables -> 17756
                15, 20, // 6822 - 6836 variables -> 17796
                5, 6, // 6837 - 6841 variables -> 17808
                23, 30, // 6842 - 6864 variables -> 17868
                5, 6, // 6865 - 6869 variables -> 17880
                3, 4, // 6870 - 6872 variables -> 17888
                6, 8, // 6873 - 6878 variables -> 17904
                8, 10, // 6879 - 6886 variables -> 17924
                9, 12, // 6887 - 6895 variables -> 17948
                4, 6, // 6896 - 6899 variables -> 17960
                2, 2, // 6900 - 6901 variables -> 17964
                22, 28, // 6902 - 6923 variables -> 18020
                1, 2, // 6924 variables -> 18024
                5, 6, // 6925 - 6929 variables -> 18036
                3, 4, // 6930 - 6932 variables -> 18044
                1, 2, // 6933 variables -> 18048
                13, 16, // 6934 - 6946 variables -> 18080
                9, 12, // 6947 - 6955 variables -> 18104
                1, 2, // 6956 variables -> 18108
                5, 6, // 6957 - 6961 variables -> 18120
                8, 10, // 6962 - 6969 variables -> 18140
                6, 8, // 6970 - 6975 variables -> 18156
                18, 24, // 6976 - 6993 variables -> 18204
                10, 12, // 6994 - 7003 variables -> 18228
                4, 6, // 7004 - 7007 variables -> 18240
                14, 18, // 7008 - 7021 variables -> 18276
                5, 6, // 7022 - 7026 variables -> 18288
                3, 4, // 7027 - 7029 variables -> 18296
                10, 14, // 7030 - 7039 variables -> 18324
                5, 6, // 7040 - 7044 variables -> 18336
                3, 4, // 7045 - 7047 variables -> 18344
                9, 12, // 7048 - 7056 variables -> 18368
                7, 8, // 7057 - 7063 variables -> 18384
                4, 6, // 7064 - 7067 variables -> 18396
                9, 12, // 7068 - 7076 variables -> 18420
                3, 4, // 7077 - 7079 variables -> 18428
                5, 6, // 7080 - 7084 variables -> 18440
                9, 12, // 7085 - 7093 variables -> 18464
                5, 6, // 7094 - 7098 variables -> 18476
                9, 12, // 7099 - 7107 variables -> 18500
                2, 2, // 7108 - 7109 variables -> 18504
                12, 16, // 7110 - 7121 variables -> 18536
                15, 20, // 7122 - 7136 variables -> 18576
                3, 4, // 7137 - 7139 variables -> 18584
                2, 2, // 7140 - 7141 variables -> 18588
                8, 10, // 7142 - 7149 variables -> 18608
                14, 18, // 7150 - 7163 variables -> 18644
                6, 8, // 7164 - 7169 variables -> 18660
                3, 4, // 7170 - 7172 variables -> 18668
                11, 14, // 7173 - 7183 variables -> 18696
                3, 4, // 7184 - 7186 variables -> 18704
                1, 2, // 7187 variables -> 18708
                5, 6, // 7188 - 7192 variables -> 18720
                17, 22, // 7193 - 7209 variables -> 18764
                4, 6, // 7210 - 7213 variables -> 18776
                11, 14, // 7214 - 7224 variables -> 18804
                5, 6, // 7225 - 7229 variables -> 18816
                4, 6, // 7230 - 7233 variables -> 18828
                8, 10, // 7234 - 7241 variables -> 18848
                5, 6, // 7242 - 7246 variables -> 18860
                1, 2, // 7247 variables -> 18864
                8, 10, // 7248 - 7255 variables -> 18884
                1, 2, // 7256 variables -> 18888
                3, 4, // 7257 - 7259 variables -> 18896
                2, 2, // 7260 - 7261 variables -> 18900
                17, 22, // 7262 - 7278 variables -> 18944
                1, 2, // 7279 variables -> 18948
                4, 4, // 7280 - 7283 variables -> 18956
                4, 6, // 7284 - 7287 variables -> 18968
                5, 6, // 7288 - 7292 variables -> 18980
                9, 12, // 7293 - 7301 variables -> 19004
                5, 6, // 7302 - 7306 variables -> 19016
                10, 14, // 7307 - 7316 variables -> 19044
                8, 10, // 7317 - 7324 variables -> 19064
                9, 12, // 7325 - 7333 variables -> 19088
                5, 6, // 7334 - 7338 variables -> 19100
                6, 8, // 7339 - 7344 variables -> 19116
                3, 4, // 7345 - 7347 variables -> 19124
                28, 36, // 7348 - 7375 variables -> 19196
                11, 14, // 7376 - 7386 variables -> 19224
                9, 12, // 7387 - 7395 variables -> 19248
                4, 6, // 7396 - 7399 variables -> 19260
                4, 4, // 7400 - 7403 variables -> 19268
                4, 6, // 7404 - 7407 variables -> 19280
                2, 2, // 7408 - 7409 variables -> 19284
                9, 12, // 7410 - 7418 variables -> 19308
                5, 6, // 7419 - 7423 variables -> 19320
                9, 12, // 7424 - 7432 variables -> 19344
                12, 16, // 7433 - 7444 variables -> 19376
                2, 2, // 7445 - 7446 variables -> 19380
                7, 10, // 7447 - 7453 variables -> 19400
                6, 8, // 7454 - 7459 variables -> 19416
                17, 22, // 7460 - 7476 variables -> 19460
                2, 2, // 7477 - 7478 variables -> 19464
                9, 12, // 7479 - 7487 variables -> 19488
                5, 6, // 7488 - 7492 variables -> 19500
                3, 4, // 7493 - 7495 variables -> 19508
                4, 6, // 7496 - 7499 variables -> 19520
                14, 18, // 7500 - 7513 variables -> 19556
                2, 2, // 7514 - 7515 variables -> 19560
                9, 12, // 7516 - 7524 variables -> 19584
                5, 6, // 7525 - 7529 variables -> 19596
                3, 4, // 7530 - 7532 variables -> 19604
                9, 12, // 7533 - 7541 variables -> 19628
                6, 8, // 7542 - 7547 variables -> 19644
                5, 6, // 7548 - 7552 variables -> 19656
                9, 12, // 7553 - 7561 variables -> 19680
                3, 4, // 7562 - 7564 variables -> 19688
                5, 6, // 7565 - 7569 variables -> 19700
                9, 12, // 7570 - 7578 variables -> 19724
                5, 6, // 7579 - 7583 variables -> 19736
                1, 2, // 7584 variables -> 19740
                9, 12, // 7585 - 7593 variables -> 19764
                10, 12, // 7594 - 7603 variables -> 19788
                3, 4, // 7604 - 7606 variables -> 19796
                10, 14, // 7607 - 7616 variables -> 19824
                5, 6, // 7617 - 7621 variables -> 19836
                12, 16, // 7622 - 7633 variables -> 19868
                5, 6, // 7634 - 7638 variables -> 19880
                1, 2, // 7639 variables -> 19884
                8, 10, // 7640 - 7647 variables -> 19904
                6, 8, // 7648 - 7653 variables -> 19920
                14, 18, // 7654 - 7667 variables -> 19956
                5, 6, // 7668 - 7672 variables -> 19968
                26, 34, // 7673 - 7698 variables -> 20036
                1, 2, // 7699 variables -> 20040
                22, 28, // 7700 - 7721 variables -> 20096
                2, 2, // 7722 - 7723 variables -> 20100
                16, 22, // 7724 - 7739 variables -> 20144
                5, 6, // 7740 - 7744 variables -> 20156
                2, 2, // 7745 - 7746 variables -> 20160
                7, 10, // 7747 - 7753 variables -> 20180
                10, 12, // 7754 - 7763 variables -> 20204
                1, 2, // 7764 variables -> 20208
                5, 6, // 7765 - 7769 variables -> 20220
                3, 4, // 7770 - 7772 variables -> 20228
                6, 8, // 7773 - 7778 variables -> 20244
                17, 22, // 7779 - 7795 variables -> 20288
                4, 6, // 7796 - 7799 variables -> 20300
                2, 2, // 7800 - 7801 variables -> 20304
                8, 10, // 7802 - 7809 variables -> 20324
                6, 8, // 7810 - 7815 variables -> 20340
                3, 4, // 7816 - 7818 variables -> 20348
                5, 6, // 7819 - 7823 variables -> 20360
                6, 8, // 7824 - 7829 variables -> 20376
                3, 4, // 7830 - 7832 variables -> 20384
                9, 12, // 7833 - 7841 variables -> 20408
                14, 18, // 7842 - 7855 variables -> 20444
                9, 12, // 7856 - 7864 variables -> 20468
                15, 20, // 7865 - 7879 variables -> 20508
                4, 4, // 7880 - 7883 variables -> 20516
                4, 6, // 7884 - 7887 variables -> 20528
                5, 6, // 7888 - 7892 variables -> 20540
                6, 8, // 7893 - 7898 variables -> 20556
                3, 4, // 7899 - 7901 variables -> 20564
                2, 2, // 7902 - 7903 variables -> 20568
                12, 16, // 7904 - 7915 variables -> 20600
                9, 12, // 7916 - 7924 variables -> 20624
                2, 2, // 7925 - 7926 variables -> 20628
                7, 10, // 7927 - 7933 variables -> 20648
                6, 8, // 7934 - 7939 variables -> 20664
                8, 10, // 7940 - 7947 variables -> 20684
                2, 2, // 7948 - 7949 variables -> 20688
                3, 4, // 7950 - 7952 variables -> 20696
                4, 6, // 7953 - 7956 variables -> 20708
                11, 14, // 7957 - 7967 variables -> 20736
                9, 12, // 7968 - 7976 variables -> 20760
                17, 22, // 7977 - 7993 variables -> 20804
                6, 8, // 7994 - 7999 variables -> 20820
                22, 28, // 8000 - 8021 variables -> 20876
                2, 2, // 8022 - 8023 variables -> 20880
                3, 4, // 8024 - 8026 variables -> 20888
                15, 20, // 8027 - 8041 variables -> 20928
                3, 4, // 8042 - 8044 variables -> 20936
                2, 2, // 8045 - 8046 variables -> 20940
                3, 4, // 8047 - 8049 variables -> 20948
                10, 14, // 8050 - 8059 variables -> 20976
                8, 10, // 8060 - 8067 variables -> 20996
                9, 12, // 8068 - 8076 variables -> 21020
                2, 2, // 8077 - 8078 variables -> 21024
                9, 12, // 8079 - 8087 variables -> 21048
                12, 16, // 8088 - 8099 variables -> 21080
                2, 2, // 8100 - 8101 variables -> 21084
                22, 28, // 8102 - 8123 variables -> 21140
                6, 8, // 8124 - 8129 variables -> 21156
                17, 22, // 8130 - 8146 variables -> 21200
                6, 8, // 8147 - 8152 variables -> 21216
                3, 4, // 8153 - 8155 variables -> 21224
                4, 6, // 8156 - 8159 variables -> 21236
                5, 6, // 8160 - 8164 variables -> 21248
                11, 14, // 8165 - 8175 variables -> 21276
                3, 4, // 8176 - 8178 variables -> 21284
                6, 8, // 8179 - 8184 variables -> 21300
                9, 12, // 8185 - 8193 variables -> 21324
                5, 6, // 8194 - 8198 variables -> 21336
                5, 6, // 8199 - 8203 variables -> 21348
                3, 4, // 8204 - 8206 variables -> 21356
                15, 20, // 8207 - 8221 variables -> 21396
                3, 4, // 8222 - 8224 variables -> 21404
                14, 18, // 8225 - 8238 variables -> 21440
                1, 2, // 8239 variables -> 21444
                10, 12, // 8240 - 8249 variables -> 21468
                4, 6, // 8250 - 8253 variables -> 21480
                3, 4, // 8254 - 8256 variables -> 21488
                5, 6, // 8257 - 8261 variables -> 21500
                11, 14, // 8262 - 8272 variables -> 21528
                14, 18, // 8273 - 8286 variables -> 21564
                7, 10, // 8287 - 8293 variables -> 21584
                6, 8, // 8294 - 8299 variables -> 21600
                8, 10, // 8300 - 8307 variables -> 21620
                25, 32, // 8308 - 8332 variables -> 21684
                4, 6, // 8333 - 8336 variables -> 21696
                8, 10, // 8337 - 8344 variables -> 21716
                5, 6, // 8345 - 8349 variables -> 21728
                4, 6, // 8350 - 8353 variables -> 21740
                2, 2, // 8354 - 8355 variables -> 21744
                4, 6, // 8356 - 8359 variables -> 21756
                13, 16, // 8360 - 8372 variables -> 21788
                4, 6, // 8373 - 8376 variables -> 21800
                2, 2, // 8377 - 8378 variables -> 21804
                9, 12, // 8379 - 8387 variables -> 21828
                5, 6, // 8388 - 8392 variables -> 21840
                21, 28, // 8393 - 8413 variables -> 21896
                2, 2, // 8414 - 8415 variables -> 21900
                8, 10, // 8416 - 8423 variables -> 21920
                6, 8, // 8424 - 8429 variables -> 21936
                12, 16, // 8430 - 8441 variables -> 21968
                5, 6, // 8442 - 8446 variables -> 21980
                6, 8, // 8447 - 8452 variables -> 21996
                4, 6, // 8453 - 8456 variables -> 22008
                8, 10, // 8457 - 8464 variables -> 22028
                19, 24, // 8465 - 8483 variables -> 22076
                15, 20, // 8484 - 8498 variables -> 22116
                8, 10, // 8499 - 8506 variables -> 22136
                1, 2, // 8507 variables -> 22140
                8, 10, // 8508 - 8515 variables -> 22160
                1, 2, // 8516 variables -> 22164
                10, 12, // 8517 - 8526 variables -> 22188
                3, 4, // 8527 - 8529 variables -> 22196
                4, 6, // 8530 - 8533 variables -> 22208
                16, 20, // 8534 - 8549 variables -> 22248
                3, 4, // 8550 - 8552 variables -> 22256
                1, 2, // 8553 variables -> 22260
                10, 12, // 8554 - 8563 variables -> 22284
                13, 18, // 8564 - 8576 variables -> 22320
                8, 10, // 8577 - 8584 variables -> 22340
                2, 2, // 8585 - 8586 variables -> 22344
                7, 10, // 8587 - 8593 variables -> 22364
                2, 2, // 8594 - 8595 variables -> 22368
                3, 4, // 8596 - 8598 variables -> 22376
                15, 20, // 8599 - 8613 variables -> 22416
                13, 16, // 8614 - 8626 variables -> 22448
                20, 26, // 8627 - 8646 variables -> 22500
                3, 4, // 8647 - 8649 variables -> 22508
                6, 8, // 8650 - 8655 variables -> 22524
                4, 6, // 8656 - 8659 variables -> 22536
                4, 4, // 8660 - 8663 variables -> 22544
                9, 12, // 8664 - 8672 variables -> 22568
                4, 6, // 8673 - 8676 variables -> 22580
                7, 8, // 8677 - 8683 variables -> 22596
                9, 12, // 8684 - 8692 variables -> 22620
                9, 12, // 8693 - 8701 variables -> 22644
                5, 6, // 8702 - 8706 variables -> 22656
                3, 4, // 8707 - 8709 variables -> 22664
                6, 8, // 8710 - 8715 variables -> 22680
                17, 22, // 8716 - 8732 variables -> 22724
                1, 2, // 8733 variables -> 22728
                13, 16, // 8734 - 8746 variables -> 22760
                10, 14, // 8747 - 8756 variables -> 22788
                8, 10, // 8757 - 8764 variables -> 22808
                5, 6, // 8765 - 8769 variables -> 22820
                9, 12, // 8770 - 8778 variables -> 22844
                9, 12, // 8779 - 8787 variables -> 22868
                11, 14, // 8788 - 8798 variables -> 22896
                5, 6, // 8799 - 8803 variables -> 22908
                3, 4, // 8804 - 8806 variables -> 22916
                15, 20, // 8807 - 8821 variables -> 22956
                3, 4, // 8822 - 8824 variables -> 22964
                9, 12, // 8825 - 8833 variables -> 22988
                5, 6, // 8834 - 8838 variables -> 23000
                1, 2, // 8839 variables -> 23004
                5, 6, // 8840 - 8844 variables -> 23016
                5, 6, // 8845 - 8849 variables -> 23028
                12, 16, // 8850 - 8861 variables -> 23060
                6, 8, // 8862 - 8867 variables -> 23076
                17, 22, // 8868 - 8884 variables -> 23120
                2, 2, // 8885 - 8886 variables -> 23124
                21, 28, // 8887 - 8907 variables -> 23180
                6, 8, // 8908 - 8913 variables -> 23196
                5, 6, // 8914 - 8918 variables -> 23208
                3, 4, // 8919 - 8921 variables -> 23216
                15, 20, // 8922 - 8936 variables -> 23256
                3, 4, // 8937 - 8939 variables -> 23264
                10, 12, // 8940 - 8949 variables -> 23288
                18, 24, // 8950 - 8967 variables -> 23336
                16, 20, // 8968 - 8983 variables -> 23376
                3, 4, // 8984 - 8986 variables -> 23384
                6, 8, // 8987 - 8992 variables -> 23400
                7, 10, // 8993 - 8999 variables -> 23420
                2, 2, // 9000 - 9001 variables -> 23424
                12, 16, // 9002 - 9013 variables -> 23456
                2, 2, // 9014 - 9015 variables -> 23460
                9, 12, // 9016 - 9024 variables -> 23484
                9, 12, // 9025 - 9033 variables -> 23508
                26, 34, // 9034 - 9059 variables -> 23576
                2, 2, // 9060 - 9061 variables -> 23580
                3, 4, // 9062 - 9064 variables -> 23588
                5, 6, // 9065 - 9069 variables -> 23600
                9, 12, // 9070 - 9078 variables -> 23624
                5, 6, // 9079 - 9083 variables -> 23636
                4, 6, // 9084 - 9087 variables -> 23648
                6, 8, // 9088 - 9093 variables -> 23664
                5, 6, // 9094 - 9098 variables -> 23676
                3, 4, // 9099 - 9101 variables -> 23684
                2, 2, // 9102 - 9103 variables -> 23688
                4, 6, // 9104 - 9107 variables -> 23700
                19, 24, // 9108 - 9126 variables -> 23748
                3, 4, // 9127 - 9129 variables -> 23756
                15, 20, // 9130 - 9144 variables -> 23796
                8, 10, // 9145 - 9152 variables -> 23816
                4, 6, // 9153 - 9156 variables -> 23828
                5, 6, // 9157 - 9161 variables -> 23840
                11, 14, // 9162 - 9172 variables -> 23868
                3, 4, // 9173 - 9175 variables -> 23876
                4, 6, // 9176 - 9179 variables -> 23888
                5, 6, // 9180 - 9184 variables -> 23900
                2, 2, // 9185 - 9186 variables -> 23904
                9, 12, // 9187 - 9195 variables -> 23928
                4, 6, // 9196 - 9199 variables -> 23940
                8, 10, // 9200 - 9207 variables -> 23960
                2, 2, // 9208 - 9209 variables -> 23964
                7, 10, // 9210 - 9216 variables -> 23984
                5, 6, // 9217 - 9221 variables -> 23996
                15, 20, // 9222 - 9236 variables -> 24036
                3, 4, // 9237 - 9239 variables -> 24044
                20, 26, // 9240 - 9259 variables -> 24096
                4, 4, // 9260 - 9263 variables -> 24104
                1, 2, // 9264 variables -> 24108
                5, 6, // 9265 - 9269 variables -> 24120
                17, 22, // 9270 - 9286 variables -> 24164
                1, 2, // 9287 variables -> 24168
                19, 24, // 9288 - 9306 variables -> 24216
                3, 4, // 9307 - 9309 variables -> 24224
                4, 6, // 9310 - 9313 variables -> 24236
                2, 2, // 9314 - 9315 variables -> 24240
                3, 4, // 9316 - 9318 variables -> 24248
                5, 6, // 9319 - 9323 variables -> 24260
                18, 24, // 9324 - 9341 variables -> 24308
                5, 6, // 9342 - 9346 variables -> 24320
                6, 8, // 9347 - 9352 variables -> 24336
                3, 4, // 9353 - 9355 variables -> 24344
                1, 2, // 9356 variables -> 24348
                27, 34, // 9357 - 9383 variables -> 24416
                4, 6, // 9384 - 9387 variables -> 24428
                6, 8, // 9388 - 9393 variables -> 24444
                13, 16, // 9394 - 9406 variables -> 24476
                9, 12, // 9407 - 9415 variables -> 24500
                1, 2, // 9416 variables -> 24504
                8, 10, // 9417 - 9424 variables -> 24524
                2, 2, // 9425 - 9426 variables -> 24528
                7, 10, // 9427 - 9433 variables -> 24548
                5, 6, // 9434 - 9438 variables -> 24560
                6, 8, // 9439 - 9444 variables -> 24576
                3, 4, // 9445 - 9447 variables -> 24584
                6, 8, // 9448 - 9453 variables -> 24600
                10, 12, // 9454 - 9463 variables -> 24624
                16, 22, // 9464 - 9479 variables -> 24668
                5, 6, // 9480 - 9484 variables -> 24680
                11, 14, // 9485 - 9495 variables -> 24708
                3, 4, // 9496 - 9498 variables -> 24716
                20, 26, // 9499 - 9518 variables -> 24768
                3, 4, // 9519 - 9521 variables -> 24776
                2, 2, // 9522 - 9523 variables -> 24780
                9, 12, // 9524 - 9532 variables -> 24804
                7, 10, // 9533 - 9539 variables -> 24824
                7, 8, // 9540 - 9546 variables -> 24840
                3, 4, // 9547 - 9549 variables -> 24848
                6, 8, // 9550 - 9555 variables -> 24864
                9, 12, // 9556 - 9564 variables -> 24888
                3, 4, // 9565 - 9567 variables -> 24896
                11, 14, // 9568 - 9578 variables -> 24924
                5, 6, // 9579 - 9583 variables -> 24936
                12, 16, // 9584 - 9595 variables -> 24968
                4, 6, // 9596 - 9599 variables -> 24980
                7, 8, // 9600 - 9606 variables -> 24996
                3, 4, // 9607 - 9609 variables -> 25004
                4, 6, // 9610 - 9613 variables -> 25016
                5, 6, // 9614 - 9618 variables -> 25028
                6, 8, // 9619 - 9624 variables -> 25044
                5, 6, // 9625 - 9629 variables -> 25056
                7, 10, // 9630 - 9636 variables -> 25076
                10, 12, // 9637 - 9646 variables -> 25100
                1, 2, // 9647 variables -> 25104
                5, 6, // 9648 - 9652 variables -> 25116
                4, 6, // 9653 - 9656 variables -> 25128
                13, 16, // 9657 - 9669 variables -> 25160
                6, 8, // 9670 - 9675 variables -> 25176
                4, 6, // 9676 - 9679 variables -> 25188
                5, 6, // 9680 - 9684 variables -> 25200
                9, 12, // 9685 - 9693 variables -> 25224
                8, 10, // 9694 - 9701 variables -> 25244
                2, 2, // 9702 - 9703 variables -> 25248
                4, 6, // 9704 - 9707 variables -> 25260
                14, 18, // 9708 - 9721 variables -> 25296
                3, 4, // 9722 - 9724 variables -> 25304
                5, 6, // 9725 - 9729 variables -> 25316
                4, 6, // 9730 - 9733 variables -> 25328
                5, 6, // 9734 - 9738 variables -> 25340
                9, 12, // 9739 - 9747 variables -> 25364
                14, 18, // 9748 - 9761 variables -> 25400
                6, 8, // 9762 - 9767 variables -> 25416
                5, 6, // 9768 - 9772 variables -> 25428
                7, 10, // 9773 - 9779 variables -> 25448
                7, 8, // 9780 - 9786 variables -> 25464
                13, 18, // 9787 - 9799 variables -> 25500
                4, 4, // 9800 - 9803 variables -> 25508
                10, 14, // 9804 - 9813 variables -> 25536
                5, 6, // 9814 - 9818 variables -> 25548
                14, 18, // 9819 - 9832 variables -> 25584
                7, 10, // 9833 - 9839 variables -> 25604
                7, 8, // 9840 - 9846 variables -> 25620
                7, 10, // 9847 - 9853 variables -> 25640
                10, 12, // 9854 - 9863 variables -> 25664
                1, 2, // 9864 variables -> 25668
                5, 6, // 9865 - 9869 variables -> 25680
                9, 12, // 9870 - 9878 variables -> 25704
                9, 12, // 9879 - 9887 variables -> 25728
                28, 36, // 9888 - 9915 variables -> 25800
                3, 4, // 9916 - 9918 variables -> 25808
                5, 6, // 9919 - 9923 variables -> 25820
                6, 8, // 9924 - 9929 variables -> 25836
                3, 4, // 9930 - 9932 variables -> 25844
                4, 6, // 9933 - 9936 variables -> 25856
                2, 2, // 9937 - 9938 variables -> 25860
                3, 4, // 9939 - 9941 variables -> 25868
                14, 18, // 9942 - 9955 variables -> 25904
                9, 12, // 9956 - 9964 variables -> 25928
                5, 6, // 9965 - 9969 variables -> 25940
                6, 8, // 9970 - 9975 variables -> 25956
                4, 6, // 9976 - 9979 variables -> 25968
                5, 6, // 9980 - 9984 variables -> 25980
                3, 4, // 9985 - 9987 variables -> 25988
                14, 18, // 9988 - 10001 variables -> 26024
                2, 2, // 10002 - 10003 variables -> 26028
                3, 4, // 10004 - 10006 variables -> 26036
                1, 2, // 10007 variables -> 26040
                19, 24, // 10008 - 10026 variables -> 26088
                3, 4, // 10027 - 10029 variables -> 26096
                4, 6, // 10030 - 10033 variables -> 26108
                5, 6, // 10034 - 10038 variables -> 26120
                11, 14, // 10039 - 10049 variables -> 26148
                23, 30, // 10050 - 10072 variables -> 26208
                4, 6, // 10073 - 10076 variables -> 26220
                3, 4, // 10077 - 10079 variables -> 26228
                5, 6, // 10080 - 10084 variables -> 26240
                9, 12, // 10085 - 10093 variables -> 26264
                5, 6, // 10094 - 10098 variables -> 26276
                15, 20, // 10099 - 10113 variables -> 26316
                3, 4, // 10114 - 10116 variables -> 26324
                7, 8, // 10117 - 10123 variables -> 26340
                3, 4, // 10124 - 10126 variables -> 26348
                6, 8, // 10127 - 10132 variables -> 26364
                4, 6, // 10133 - 10136 variables -> 26376
                5, 6, // 10137 - 10141 variables -> 26388
                3, 4, // 10142 - 10144 variables -> 26396
                23, 30, // 10145 - 10167 variables -> 26456
                2, 2, // 10168 - 10169 variables -> 26460
                7, 10, // 10170 - 10176 variables -> 26480
                10, 12, // 10177 - 10186 variables -> 26504
                6, 8, // 10187 - 10192 variables -> 26520
                7, 10, // 10193 - 10199 variables -> 26540
                7, 8, // 10200 - 10206 variables -> 26556
                18, 24, // 10207 - 10224 variables -> 26604
                5, 6, // 10225 - 10229 variables -> 26616
                9, 12, // 10230 - 10238 variables -> 26640
                3, 4, // 10239 - 10241 variables -> 26648
                11, 14, // 10242 - 10252 variables -> 26676
                3, 4, // 10253 - 10255 variables -> 26684
                4, 6, // 10256 - 10259 variables -> 26696
                2, 2, // 10260 - 10261 variables -> 26700
                22, 28, // 10262 - 10283 variables -> 26756
                10, 14, // 10284 - 10293 variables -> 26784
                13, 16, // 10294 - 10306 variables -> 26816
                1, 2, // 10307 variables -> 26820
                9, 12, // 10308 - 10316 variables -> 26844
                5, 6, // 10317 - 10321 variables -> 26856
                3, 4, // 10322 - 10324 variables -> 26864
                15, 20, // 10325 - 10339 variables -> 26904
                8, 10, // 10340 - 10347 variables -> 26924
                5, 6, // 10348 - 10352 variables -> 26936
                4, 6, // 10353 - 10356 variables -> 26948
                5, 6, // 10357 - 10361 variables -> 26960
                6, 8, // 10362 - 10367 variables -> 26976
                8, 10, // 10368 - 10375 variables -> 26996
                9, 12, // 10376 - 10384 variables -> 27020
                11, 14, // 10385 - 10395 variables -> 27048
                8, 10, // 10396 - 10403 variables -> 27068
                10, 14, // 10404 - 10413 variables -> 27096
                13, 16, // 10414 - 10426 variables -> 27128
                10, 14, // 10427 - 10436 variables -> 27156
                8, 10, // 10437 - 10444 variables -> 27176
                11, 14, // 10445 - 10455 variables -> 27204
                4, 6, // 10456 - 10459 variables -> 27216
                13, 16, // 10460 - 10472 variables -> 27248
                4, 6, // 10473 - 10476 variables -> 27260
                7, 8, // 10477 - 10483 variables -> 27276
                4, 6, // 10484 - 10487 variables -> 27288
                12, 16, // 10488 - 10499 variables -> 27320
                16, 20, // 10500 - 10515 variables -> 27360
                8, 10, // 10516 - 10523 variables -> 27380
                1, 2, // 10524 variables -> 27384
                5, 6, // 10525 - 10529 variables -> 27396
                3, 4, // 10530 - 10532 variables -> 27404
                1, 2, // 10533 variables -> 27408
                3, 4, // 10534 - 10536 variables -> 27416
                10, 12, // 10537 - 10546 variables -> 27440
                1, 2, // 10547 variables -> 27444
                8, 10, // 10548 - 10555 variables -> 27464
                1, 2, // 10556 variables -> 27468
                5, 6, // 10557 - 10561 variables -> 27480
                17, 22, // 10562 - 10578 variables -> 27524
                5, 6, // 10579 - 10583 variables -> 27536
                1, 2, // 10584 variables -> 27540
                3, 4, // 10585 - 10587 variables -> 27548
                14, 18, // 10588 - 10601 variables -> 27584
                6, 8, // 10602 - 10607 variables -> 27600
                8, 10, // 10608 - 10615 variables -> 27620
                6, 8, // 10616 - 10621 variables -> 27636
                17, 22, // 10622 - 10638 variables -> 27680
                1, 2, // 10639 variables -> 27684
                8, 10, // 10640 - 10647 variables -> 27704
                14, 18, // 10648 - 10661 variables -> 27740
                11, 14, // 10662 - 10672 variables -> 27768
                3, 4, // 10673 - 10675 variables -> 27776
                1, 2, // 10676 variables -> 27780
                3, 4, // 10677 - 10679 variables -> 27788
                14, 18, // 10680 - 10693 variables -> 27824
                2, 2, // 10694 - 10695 variables -> 27828
                3, 4, // 10696 - 10698 variables -> 27836
                5, 6, // 10699 - 10703 variables -> 27848
                6, 8, // 10704 - 10709 variables -> 27864
                7, 10, // 10710 - 10716 variables -> 27884
                2, 2, // 10717 - 10718 variables -> 27888
                23, 30, // 10719 - 10741 variables -> 27948
                3, 4, // 10742 - 10744 variables -> 27956
                23, 30, // 10745 - 10767 variables -> 28016
                2, 2, // 10768 - 10769 variables -> 28020
                7, 10, // 10770 - 10776 variables -> 28040
                2, 2, // 10777 - 10778 variables -> 28044
                14, 18, // 10779 - 10792 variables -> 28080
                3, 4, // 10793 - 10795 variables -> 28088
                14, 18, // 10796 - 10809 variables -> 28124
                4, 6, // 10810 - 10813 variables -> 28136
                11, 14, // 10814 - 10824 variables -> 28164
                8, 10, // 10825 - 10832 variables -> 28184
                1, 2, // 10833 variables -> 28188
                3, 4, // 10834 - 10836 variables -> 28196
                16, 20, // 10837 - 10852 variables -> 28236
                27, 36, // 10853 - 10879 variables -> 28308
                5, 6, // 10880 - 10884 variables -> 28320
                3, 4, // 10885 - 10887 variables -> 28328
                5, 6, // 10888 - 10892 variables -> 28340
                11, 14, // 10893 - 10903 variables -> 28368
                3, 4, // 10904 - 10906 variables -> 28376
                15, 20, // 10907 - 10921 variables -> 28416
                8, 10, // 10922 - 10929 variables -> 28436
                10, 14, // 10930 - 10939 variables -> 28464
                17, 22, // 10940 - 10956 variables -> 28508
                5, 6, // 10957 - 10961 variables -> 28520
                2, 2, // 10962 - 10963 variables -> 28524
                23, 30, // 10964 - 10986 variables -> 28584
                9, 12, // 10987 - 10995 variables -> 28608
                8, 10, // 10996 - 11003 variables -> 28628
                13, 18, // 11004 - 11016 variables -> 28664
                2, 2, // 11017 - 11018 variables -> 28668
                3, 4, // 11019 - 11021 variables -> 28676
                11, 14, // 11022 - 11032 variables -> 28704
                4, 6, // 11033 - 11036 variables -> 28716
                17, 22, // 11037 - 11053 variables -> 28760
                14, 18, // 11054 - 11067 variables -> 28796
                2, 2, // 11068 - 11069 variables -> 28800
                9, 12, // 11070 - 11078 variables -> 28824
                5, 6, // 11079 - 11083 variables -> 28836
                3, 4, // 11084 - 11086 variables -> 28844
                6, 8, // 11087 - 11092 variables -> 28860
                3, 4, // 11093 - 11095 variables -> 28868
                6, 8, // 11096 - 11101 variables -> 28884
                5, 6, // 11102 - 11106 variables -> 28896
                7, 10, // 11107 - 11113 variables -> 28916
                2, 2, // 11114 - 11115 variables -> 28920
                9, 12, // 11116 - 11124 variables -> 28944
                14, 18, // 11125 - 11138 variables -> 28980
                8, 10, // 11139 - 11146 variables -> 29000
                10, 14, // 11147 - 11156 variables -> 29028
                13, 16, // 11157 - 11169 variables -> 29060
                10, 14, // 11170 - 11179 variables -> 29088
                4, 4, // 11180 - 11183 variables -> 29096
                4, 6, // 11184 - 11187 variables -> 29108
                5, 6, // 11188 - 11192 variables -> 29120
                1, 2, // 11193 variables -> 29124
                5, 6, // 11194 - 11198 variables -> 29136
                3, 4, // 11199 - 11201 variables -> 29144
                2, 2, // 11202 - 11203 variables -> 29148
                21, 28, // 11204 - 11224 variables -> 29204
                2, 2, // 11225 - 11226 variables -> 29208
                21, 28, // 11227 - 11247 variables -> 29264
                5, 6, // 11248 - 11252 variables -> 29276
                1, 2, // 11253 variables -> 29280
                3, 4, // 11254 - 11256 variables -> 29288
                5, 6, // 11257 - 11261 variables -> 29300
                11, 14, // 11262 - 11272 variables -> 29328
                3, 4, // 11273 - 11275 variables -> 29336
                9, 12, // 11276 - 11284 variables -> 29360
                11, 14, // 11285 - 11295 variables -> 29388
                12, 16, // 11296 - 11307 variables -> 29420
                11, 14, // 11308 - 11318 variables -> 29448
                3, 4, // 11319 - 11321 variables -> 29456
                5, 6, // 11322 - 11326 variables -> 29468
                6, 8, // 11327 - 11332 variables -> 29484
                4, 6, // 11333 - 11336 variables -> 29496
                3, 4, // 11337 - 11339 variables -> 29504
                5, 6, // 11340 - 11344 variables -> 29516
                5, 6, // 11345 - 11349 variables -> 29528
                4, 6, // 11350 - 11353 variables -> 29540
                6, 8, // 11354 - 11359 variables -> 29556
                4, 4, // 11360 - 11363 variables -> 29564
                6, 8, // 11364 - 11369 variables -> 29580
                3, 4, // 11370 - 11372 variables -> 29588
                11, 14, // 11373 - 11383 variables -> 29616
                12, 16, // 11384 - 11395 variables -> 29648
                6, 8, // 11396 - 11401 variables -> 29664
                5, 6, // 11402 - 11406 variables -> 29676
                3, 4, // 11407 - 11409 variables -> 29684
                9, 12, // 11410 - 11418 variables -> 29708
                6, 8, // 11419 - 11424 variables -> 29724
                12, 16, // 11425 - 11436 variables -> 29756
                2, 2, // 11437 - 11438 variables -> 29760
                8, 10, // 11439 - 11446 variables -> 29780
                6, 8, // 11447 - 11452 variables -> 29796
                3, 4, // 11453 - 11455 variables -> 29804
                4, 6, // 11456 - 11459 variables -> 29816
                20, 26, // 11460 - 11479 variables -> 29868
                5, 6, // 11480 - 11484 variables -> 29880
                8, 10, // 11485 - 11492 variables -> 29900
                6, 8, // 11493 - 11498 variables -> 29916
                3, 4, // 11499 - 11501 variables -> 29924
                5, 6, // 11502 - 11506 variables -> 29936
                9, 12, // 11507 - 11515 variables -> 29960
                11, 14, // 11516 - 11526 variables -> 29988
                23, 30, // 11527 - 11549 variables -> 30048
                3, 4, // 11550 - 11552 variables -> 30056
                11, 14, // 11553 - 11563 variables -> 30084
                16, 22, // 11564 - 11579 variables -> 30128
                7, 8, // 11580 - 11586 variables -> 30144
                9, 12, // 11587 - 11595 variables -> 30168
                3, 4, // 11596 - 11598 variables -> 30176
                5, 6, // 11599 - 11603 variables -> 30188
                6, 8, // 11604 - 11609 variables -> 30204
                7, 10, // 11610 - 11616 variables -> 30224
                5, 6, // 11617 - 11621 variables -> 30236
                11, 14, // 11622 - 11632 variables -> 30264
                7, 10, // 11633 - 11639 variables -> 30284
                5, 6, // 11640 - 11644 variables -> 30296
                2, 2, // 11645 - 11646 variables -> 30300
                7, 10, // 11647 - 11653 variables -> 30320
                10, 12, // 11654 - 11663 variables -> 30344
                9, 12, // 11664 - 11672 variables -> 30368
                11, 14, // 11673 - 11683 variables -> 30396
                4, 6, // 11684 - 11687 variables -> 30408
                5, 6, // 11688 - 11692 variables -> 30420
                14, 18, // 11693 - 11706 variables -> 30456
                7, 10, // 11707 - 11713 variables -> 30476
                5, 6, // 11714 - 11718 variables -> 30488
                6, 8, // 11719 - 11724 variables -> 30504
                14, 18, // 11725 - 11738 variables -> 30540
                3, 4, // 11739 - 11741 variables -> 30548
                5, 6, // 11742 - 11746 variables -> 30560
                1, 2, // 11747 variables -> 30564
                5, 6, // 11748 - 11752 variables -> 30576
                7, 10, // 11753 - 11759 variables -> 30596
                2, 2, // 11760 - 11761 variables -> 30600
                8, 10, // 11762 - 11769 variables -> 30620
                6, 8, // 11770 - 11775 variables -> 30636
                4, 6, // 11776 - 11779 variables -> 30648
                5, 6, // 11780 - 11784 variables -> 30660
                8, 10, // 11785 - 11792 variables -> 30680
                1, 2, // 11793 variables -> 30684
                14, 18, // 11794 - 11807 variables -> 30720
                8, 10, // 11808 - 11815 variables -> 30740
                1, 2, // 11816 variables -> 30744
                10, 12, // 11817 - 11826 variables -> 30768
                3, 4, // 11827 - 11829 variables -> 30776
                4, 6, // 11830 - 11833 variables -> 30788
                6, 8, // 11834 - 11839 variables -> 30804
                8, 10, // 11840 - 11847 variables -> 30824
                9, 12, // 11848 - 11856 variables -> 30848
                11, 14, // 11857 - 11867 variables -> 30876
                9, 12, // 11868 - 11876 variables -> 30900
                3, 4, // 11877 - 11879 variables -> 30908
                7, 8, // 11880 - 11886 variables -> 30924
                7, 10, // 11887 - 11893 variables -> 30944
                5, 6, // 11894 - 11898 variables -> 30956
                5, 6, // 11899 - 11903 variables -> 30968
                15, 20, // 11904 - 11918 variables -> 31008
                3, 4, // 11919 - 11921 variables -> 31016
                11, 14, // 11922 - 11932 variables -> 31044
                12, 16, // 11933 - 11944 variables -> 31076
                11, 14, // 11945 - 11955 variables -> 31104
                8, 10, // 11956 - 11963 variables -> 31124
                6, 8, // 11964 - 11969 variables -> 31140
                7, 10, // 11970 - 11976 variables -> 31160
                10, 12, // 11977 - 11986 variables -> 31184
                1, 2, // 11987 variables -> 31188
                14, 18, // 11988 - 12001 variables -> 31224
                5, 6, // 12002 - 12006 variables -> 31236
                9, 12, // 12007 - 12015 variables -> 31260
                8, 10, // 12016 - 12023 variables -> 31280
                9, 12, // 12024 - 12032 variables -> 31304
                1, 2, // 12033 variables -> 31308
                3, 4, // 12034 - 12036 variables -> 31316
                2, 2, // 12037 - 12038 variables -> 31320
                9, 12, // 12039 - 12047 variables -> 31344
                5, 6, // 12048 - 12052 variables -> 31356
                3, 4, // 12053 - 12055 variables -> 31364
                6, 8, // 12056 - 12061 variables -> 31380
                3, 4, // 12062 - 12064 variables -> 31388
                34, 44, // 12065 - 12098 variables -> 31476
                3, 4, // 12099 - 12101 variables -> 31484
                2, 2, // 12102 - 12103 variables -> 31488
                3, 4, // 12104 - 12106 variables -> 31496
                1, 2, // 12107 variables -> 31500
                8, 10, // 12108 - 12115 variables -> 31520
                9, 12, // 12116 - 12124 variables -> 31544
                5, 6, // 12125 - 12129 variables -> 31556
                4, 6, // 12130 - 12133 variables -> 31568
                11, 14, // 12134 - 12144 variables -> 31596
                3, 4, // 12145 - 12147 variables -> 31604
                5, 6, // 12148 - 12152 variables -> 31616
                4, 6, // 12153 - 12156 variables -> 31628
                5, 6, // 12157 - 12161 variables -> 31640
                6, 8, // 12162 - 12167 variables -> 31656
                5, 6, // 12168 - 12172 variables -> 31668
                27, 36, // 12173 - 12199 variables -> 31740
                14, 18, // 12200 - 12213 variables -> 31776
                3, 4, // 12214 - 12216 variables -> 31784
                5, 6, // 12217 - 12221 variables -> 31796
                2, 2, // 12222 - 12223 variables -> 31800
                9, 12, // 12224 - 12232 variables -> 31824
                4, 6, // 12233 - 12236 variables -> 31836
                5, 6, // 12237 - 12241 variables -> 31848
                5, 6, // 12242 - 12246 variables -> 31860
                3, 4, // 12247 - 12249 variables -> 31868
                10, 14, // 12250 - 12259 variables -> 31896
                17, 22, // 12260 - 12276 variables -> 31940
                10, 12, // 12277 - 12286 variables -> 31964
                1, 2, // 12287 variables -> 31968
                14, 18, // 12288 - 12301 variables -> 32004
                8, 10, // 12302 - 12309 variables -> 32024
                4, 6, // 12310 - 12313 variables -> 32036
                20, 26, // 12314 - 12333 variables -> 32088
                19, 24, // 12334 - 12352 variables -> 32136
                3, 4, // 12353 - 12355 variables -> 32144
                1, 2, // 12356 variables -> 32148
                3, 4, // 12357 - 12359 variables -> 32156
                2, 2, // 12360 - 12361 variables -> 32160
                3, 4, // 12362 - 12364 variables -> 32168
                11, 14, // 12365 - 12375 variables -> 32196
                3, 4, // 12376 - 12378 variables -> 32204
                5, 6, // 12379 - 12383 variables -> 32216
                4, 6, // 12384 - 12387 variables -> 32228
                6, 8, // 12388 - 12393 variables -> 32244
                13, 16, // 12394 - 12406 variables -> 32276
                9, 12, // 12407 - 12415 variables -> 32300
                1, 2, // 12416 variables -> 32304
                33, 42, // 12417 - 12449 variables -> 32388
                3, 4, // 12450 - 12452 variables -> 32396
                1, 2, // 12453 variables -> 32400
                3, 4, // 12454 - 12456 variables -> 32408
                19, 24, // 12457 - 12475 variables -> 32456
                4, 6, // 12476 - 12479 variables -> 32468
                5, 6, // 12480 - 12484 variables -> 32480
                2, 2, // 12485 - 12486 variables -> 32484
                13, 18, // 12487 - 12499 variables -> 32520
                4, 4, // 12500 - 12503 variables -> 32528
                10, 14, // 12504 - 12513 variables -> 32556
                5, 6, // 12514 - 12518 variables -> 32568
                21, 28, // 12519 - 12539 variables -> 32624
                14, 18, // 12540 - 12553 variables -> 32660
                11, 14, // 12554 - 12564 variables -> 32688
                5, 6, // 12565 - 12569 variables -> 32700
                7, 10, // 12570 - 12576 variables -> 32720
                10, 12, // 12577 - 12586 variables -> 32744
                1, 2, // 12587 variables -> 32748
                5, 6, // 12588 - 12592 variables -> 32760
                9, 12, // 12593 - 12601 variables -> 32784
                23, 30, // 12602 - 12624 variables -> 32844
                5, 6, // 12625 - 12629 variables -> 32856
                3, 4, // 12630 - 12632 variables -> 32864
                4, 6, // 12633 - 12636 variables -> 32876
                5, 6, // 12637 - 12641 variables -> 32888
                11, 14, // 12642 - 12652 variables -> 32916
                3, 4, // 12653 - 12655 variables -> 32924
                1, 2, // 12656 variables -> 32928
                19, 24, // 12657 - 12675 variables -> 32976
                3, 4, // 12676 - 12678 variables -> 32984
                5, 6, // 12679 - 12683 variables -> 32996
                4, 6, // 12684 - 12687 variables -> 33008
                20, 26, // 12688 - 12707 variables -> 33060
                8, 10, // 12708 - 12715 variables -> 33080
                14, 18, // 12716 - 12729 variables -> 33116
                4, 6, // 12730 - 12733 variables -> 33128
                6, 8, // 12734 - 12739 variables -> 33144
                5, 6, // 12740 - 12744 variables -> 33156
                5, 6, // 12745 - 12749 variables -> 33168
                23, 30, // 12750 - 12772 variables -> 33228
                3, 4, // 12773 - 12775 variables -> 33236
                9, 12, // 12776 - 12784 variables -> 33260
                9, 12, // 12785 - 12793 variables -> 33284
                2, 2, // 12794 - 12795 variables -> 33288
                12, 16, // 12796 - 12807 variables -> 33320
                2, 2, // 12808 - 12809 variables -> 33324
                4, 6, // 12810 - 12813 variables -> 33336
                3, 4, // 12814 - 12816 variables -> 33344
                10, 12, // 12817 - 12826 variables -> 33368
                13, 18, // 12827 - 12839 variables -> 33404
                2, 2, // 12840 - 12841 variables -> 33408
                5, 6, // 12842 - 12846 variables -> 33420
                3, 4, // 12847 - 12849 variables -> 33428
                20, 26, // 12850 - 12869 variables -> 33480
                9, 12, // 12870 - 12878 variables -> 33504
                5, 6, // 12879 - 12883 variables -> 33516
                9, 12, // 12884 - 12892 variables -> 33540
                3, 4, // 12893 - 12895 variables -> 33548
                18, 24, // 12896 - 12913 variables -> 33596
                19, 24, // 12914 - 12932 variables -> 33644
                9, 12, // 12933 - 12941 variables -> 33668
                5, 6, // 12942 - 12946 variables -> 33680
                1, 2, // 12947 variables -> 33684
                9, 12, // 12948 - 12956 variables -> 33708
                22, 28, // 12957 - 12978 variables -> 33764
                6, 8, // 12979 - 12984 variables -> 33780
                3, 4, // 12985 - 12987 variables -> 33788
                5, 6, // 12988 - 12992 variables -> 33800
                9, 12, // 12993 - 13001 variables -> 33824
                2, 2, // 13002 - 13003 variables -> 33828
                13, 18, // 13004 - 13016 variables -> 33864
                5, 6, // 13017 - 13021 variables -> 33876
                3, 4, // 13022 - 13024 variables -> 33884
                5, 6, // 13025 - 13029 variables -> 33896
                4, 6, // 13030 - 13033 variables -> 33908
                16, 20, // 13034 - 13049 variables -> 33948
                12, 16, // 13050 - 13061 variables -> 33980
                2, 2, // 13062 - 13063 variables -> 33984
                4, 6, // 13064 - 13067 variables -> 33996
                5, 6, // 13068 - 13072 variables -> 34008
                14, 18, // 13073 - 13086 variables -> 34044
                7, 10, // 13087 - 13093 variables -> 34064
                5, 6, // 13094 - 13098 variables -> 34076
                1, 2, // 13099 variables -> 34080
                4, 4, // 13100 - 13103 variables -> 34088
                6, 8, // 13104 - 13109 variables -> 34104
                4, 6, // 13110 - 13113 variables -> 34116
                5, 6, // 13114 - 13118 variables -> 34128
                18, 24, // 13119 - 13136 variables -> 34176
                13, 16, // 13137 - 13149 variables -> 34208
                4, 6, // 13150 - 13153 variables -> 34220
                6, 8, // 13154 - 13159 variables -> 34236
                8, 10, // 13160 - 13167 variables -> 34256
                5, 6, // 13168 - 13172 variables -> 34268
                11, 14, // 13173 - 13183 variables -> 34296
                16, 22, // 13184 - 13199 variables -> 34340
                7, 8, // 13200 - 13206 variables -> 34356
                12, 16, // 13207 - 13218 variables -> 34388
                5, 6, // 13219 - 13223 variables -> 34400
                1, 2, // 13224 variables -> 34404
                9, 12, // 13225 - 13233 variables -> 34428
                3, 4, // 13234 - 13236 variables -> 34436
                2, 2, // 13237 - 13238 variables -> 34440
                17, 22, // 13239 - 13255 variables -> 34484
                6, 8, // 13256 - 13261 variables -> 34500
                14, 18, // 13262 - 13275 variables -> 34536
                26, 34, // 13276 - 13301 variables -> 34604
                2, 2, // 13302 - 13303 variables -> 34608
                4, 6, // 13304 - 13307 variables -> 34620
                14, 18, // 13308 - 13321 variables -> 34656
                3, 4, // 13322 - 13324 variables -> 34664
                5, 6, // 13325 - 13329 variables -> 34676
                4, 6, // 13330 - 13333 variables -> 34688
                6, 8, // 13334 - 13339 variables -> 34704
                8, 10, // 13340 - 13347 variables -> 34724
                6, 8, // 13348 - 13353 variables -> 34740
                14, 18, // 13354 - 13367 variables -> 34776
                5, 6, // 13368 - 13372 variables -> 34788
                3, 4, // 13373 - 13375 variables -> 34796
                1, 2, // 13376 variables -> 34800
                3, 4, // 13377 - 13379 variables -> 34808
                7, 8, // 13380 - 13386 variables -> 34824
                12, 16, // 13387 - 13398 variables -> 34856
                1, 2, // 13399 variables -> 34860
                10, 12, // 13400 - 13409 variables -> 34884
                9, 12, // 13410 - 13418 variables -> 34908
                5, 6, // 13419 - 13423 variables -> 34920
                13, 18, // 13424 - 13436 variables -> 34956
                3, 4, // 13437 - 13439 variables -> 34964
                5, 6, // 13440 - 13444 variables -> 34976
                5, 6, // 13445 - 13449 variables -> 34988
                4, 6, // 13450 - 13453 variables -> 35000
                2, 2, // 13454 - 13455 variables -> 35004
                4, 6, // 13456 - 13459 variables -> 35016
                10, 12, // 13460 - 13469 variables -> 35040
                7, 10, // 13470 - 13476 variables -> 35060
                16, 20, // 13477 - 13492 variables -> 35100
                9, 12, // 13493 - 13501 variables -> 35124
                14, 18, // 13502 - 13515 variables -> 35160
                3, 4, // 13516 - 13518 variables -> 35168
                5, 6, // 13519 - 13523 variables -> 35180
                1, 2, // 13524 variables -> 35184
                12, 16, // 13525 - 13536 variables -> 35216
                2, 2, // 13537 - 13538 variables -> 35220
                8, 10, // 13539 - 13546 variables -> 35240
                10, 14, // 13547 - 13556 variables -> 35268
                3, 4, // 13557 - 13559 variables -> 35276
                24, 30, // 13560 - 13583 variables -> 35336
                1, 2, // 13584 variables -> 35340
                8, 10, // 13585 - 13592 variables -> 35360
                9, 12, // 13593 - 13601 variables -> 35384
                2, 2, // 13602 - 13603 variables -> 35388
                18, 24, // 13604 - 13621 variables -> 35436
                5, 6, // 13622 - 13626 variables -> 35448
                12, 16, // 13627 - 13638 variables -> 35480
                6, 8, // 13639 - 13644 variables -> 35496
                8, 10, // 13645 - 13652 variables -> 35516
                1, 2, // 13653 variables -> 35520
                10, 12, // 13654 - 13663 variables -> 35544
                16, 22, // 13664 - 13679 variables -> 35588
                5, 6, // 13680 - 13684 variables -> 35600
                2, 2, // 13685 - 13686 variables -> 35604
                12, 16, // 13687 - 13698 variables -> 35636
                15, 20, // 13699 - 13713 variables -> 35676
                8, 10, // 13714 - 13721 variables -> 35696
                2, 2, // 13722 - 13723 variables -> 35700
                9, 12, // 13724 - 13732 variables -> 35724
                9, 12, // 13733 - 13741 variables -> 35748
                14, 18, // 13742 - 13755 variables -> 35784
                8, 10, // 13756 - 13763 variables -> 35804
                9, 12, // 13764 - 13772 variables -> 35828
                4, 6, // 13773 - 13776 variables -> 35840
                2, 2, // 13777 - 13778 variables -> 35844
                8, 10, // 13779 - 13786 variables -> 35864
                1, 2, // 13787 variables -> 35868
                5, 6, // 13788 - 13792 variables -> 35880
                7, 10, // 13793 - 13799 variables -> 35900
                14, 18, // 13800 - 13813 variables -> 35936
                2, 2, // 13814 - 13815 variables -> 35940
                9, 12, // 13816 - 13824 variables -> 35964
                5, 6, // 13825 - 13829 variables -> 35976
                3, 4, // 13830 - 13832 variables -> 35984
                4, 6, // 13833 - 13836 variables -> 35996
                2, 2, // 13837 - 13838 variables -> 36000
                18, 24, // 13839 - 13856 variables -> 36048
                22, 28, // 13857 - 13878 variables -> 36104
                1, 2, // 13879 variables -> 36108
                4, 4, // 13880 - 13883 variables -> 36116
                1, 2, // 13884 variables -> 36120
                8, 10, // 13885 - 13892 variables -> 36140
                1, 2, // 13893 variables -> 36144
                13, 16, // 13894 - 13906 variables -> 36176
                9, 12, // 13907 - 13915 variables -> 36200
                6, 8, // 13916 - 13921 variables -> 36216
                17, 22, // 13922 - 13938 variables -> 36260
                1, 2, // 13939 variables -> 36264
                5, 6, // 13940 - 13944 variables -> 36276
                3, 4, // 13945 - 13947 variables -> 36284
                2, 2, // 13948 - 13949 variables -> 36288
                7, 10, // 13950 - 13956 variables -> 36308
                5, 6, // 13957 - 13961 variables -> 36320
                15, 20, // 13962 - 13976 variables -> 36360
                10, 12, // 13977 - 13986 variables -> 36384
                7, 10, // 13987 - 13993 variables -> 36404
                6, 8, // 13994 - 13999 variables -> 36420
                10, 12, // 14000 - 14009 variables -> 36444
                4, 6, // 14010 - 14013 variables -> 36456
                5, 6, // 14014 - 14018 variables -> 36468
                5, 6, // 14019 - 14023 variables -> 36480
                3, 4, // 14024 - 14026 variables -> 36488
                13, 18, // 14027 - 14039 variables -> 36524
                2, 2, // 14040 - 14041 variables -> 36528
                3, 4, // 14042 - 14044 variables -> 36536
                9, 12, // 14045 - 14053 variables -> 36560
                14, 18, // 14054 - 14067 variables -> 36596
                2, 2, // 14068 - 14069 variables -> 36600
                9, 12, // 14070 - 14078 variables -> 36624
                5, 6, // 14079 - 14083 variables -> 36636
                3, 4, // 14084 - 14086 variables -> 36644
                1, 2, // 14087 variables -> 36648
                12, 16, // 14088 - 14099 variables -> 36680
                10, 12, // 14100 - 14109 variables -> 36704
                9, 12, // 14110 - 14118 variables -> 36728
                11, 14, // 14119 - 14129 variables -> 36756
                3, 4, // 14130 - 14132 variables -> 36764
                6, 8, // 14133 - 14138 variables -> 36780
                14, 18, // 14139 - 14152 variables -> 36816
                3, 4, // 14153 - 14155 variables -> 36824
                9, 12, // 14156 - 14164 variables -> 36848
                11, 14, // 14165 - 14175 variables -> 36876
                4, 6, // 14176 - 14179 variables -> 36888
                5, 6, // 14180 - 14184 variables -> 36900
                3, 4, // 14185 - 14187 variables -> 36908
                6, 8, // 14188 - 14193 variables -> 36924
                5, 6, // 14194 - 14198 variables -> 36936
                3, 4, // 14199 - 14201 variables -> 36944
                15, 20, // 14202 - 14216 variables -> 36984
                10, 12, // 14217 - 14226 variables -> 37008
                7, 10, // 14227 - 14233 variables -> 37028
                11, 14, // 14234 - 14244 variables -> 37056
                3, 4, // 14245 - 14247 variables -> 37064
                2, 2, // 14248 - 14249 variables -> 37068
                12, 16, // 14250 - 14261 variables -> 37100
                2, 2, // 14262 - 14263 variables -> 37104
                9, 12, // 14264 - 14272 variables -> 37128
                23, 30, // 14273 - 14295 variables -> 37188
                3, 4, // 14296 - 14298 variables -> 37196
                5, 6, // 14299 - 14303 variables -> 37208
                18, 24, // 14304 - 14321 variables -> 37256
                15, 20, // 14322 - 14336 variables -> 37296
                19, 24, // 14337 - 14355 variables -> 37344
                8, 10, // 14356 - 14363 variables -> 37364
                6, 8, // 14364 - 14369 variables -> 37380
                9, 12, // 14370 - 14378 variables -> 37404
                8, 10, // 14379 - 14386 variables -> 37424
                9, 12, // 14387 - 14395 variables -> 37448
                4, 6, // 14396 - 14399 variables -> 37460
                10, 12, // 14400 - 14409 variables -> 37484
                9, 12, // 14410 - 14418 variables -> 37508
                5, 6, // 14419 - 14423 variables -> 37520
                6, 8, // 14424 - 14429 variables -> 37536
                12, 16, // 14430 - 14441 variables -> 37568
                11, 14, // 14442 - 14452 variables -> 37596
                4, 6, // 14453 - 14456 variables -> 37608
                3, 4, // 14457 - 14459 variables -> 37616
                5, 6, // 14460 - 14464 variables -> 37628
                28, 36, // 14465 - 14492 variables -> 37700
                15, 20, // 14493 - 14507 variables -> 37740
                8, 10, // 14508 - 14515 variables -> 37760
                23, 30, // 14516 - 14538 variables -> 37820
                9, 12, // 14539 - 14547 variables -> 37844
                2, 2, // 14548 - 14549 variables -> 37848
                3, 4, // 14550 - 14552 variables -> 37856
                1, 2, // 14553 variables -> 37860
                22, 28, // 14554 - 14575 variables -> 37916
                9, 12, // 14576 - 14584 variables -> 37940
                11, 14, // 14585 - 14595 variables -> 37968
                4, 6, // 14596 - 14599 variables -> 37980
                17, 22, // 14600 - 14616 variables -> 38024
                7, 8, // 14617 - 14623 variables -> 38040
                3, 4, // 14624 - 14626 variables -> 38048
                13, 18, // 14627 - 14639 variables -> 38084
                5, 6, // 14640 - 14644 variables -> 38096
                11, 14, // 14645 - 14655 variables -> 38124
                14, 18, // 14656 - 14669 variables -> 38160
                3, 4, // 14670 - 14672 variables -> 38168
                4, 6, // 14673 - 14676 variables -> 38180
                2, 2, // 14677 - 14678 variables -> 38184
                5, 6, // 14679 - 14683 variables -> 38196
                26, 34, // 14684 - 14709 variables -> 38264
                14, 18, // 14710 - 14723 variables -> 38300
                1, 2, // 14724 variables -> 38304
                12, 16, // 14725 - 14736 variables -> 38336
                5, 6, // 14737 - 14741 variables -> 38348
                14, 18, // 14742 - 14755 variables -> 38384
                1, 2, // 14756 variables -> 38388
                19, 24, // 14757 - 14775 variables -> 38436
                3, 4, // 14776 - 14778 variables -> 38444
                1, 2, // 14779 variables -> 38448
                5, 6, // 14780 - 14784 variables -> 38460
                9, 12, // 14785 - 14793 variables -> 38484
                5, 6, // 14794 - 14798 variables -> 38496
                9, 12, // 14799 - 14807 variables -> 38520
                8, 10, // 14808 - 14815 variables -> 38540
                6, 8, // 14816 - 14821 variables -> 38556
                5, 6, // 14822 - 14826 variables -> 38568
                12, 16, // 14827 - 14838 variables -> 38600
                9, 12, // 14839 - 14847 variables -> 38624
                6, 8, // 14848 - 14853 variables -> 38640
                8, 10, // 14854 - 14861 variables -> 38660
                11, 14, // 14862 - 14872 variables -> 38688
                31, 40, // 14873 - 14903 variables -> 38768
                4, 6, // 14904 - 14907 variables -> 38780
                2, 2, // 14908 - 14909 variables -> 38784
                4, 6, // 14910 - 14913 variables -> 38796
                3, 4, // 14914 - 14916 variables -> 38804
                10, 12, // 14917 - 14926 variables -> 38828
                10, 14, // 14927 - 14936 variables -> 38856
                3, 4, // 14937 - 14939 variables -> 38864
                2, 2, // 14940 - 14941 variables -> 38868
                3, 4, // 14942 - 14944 variables -> 38876
                2, 2, // 14945 - 14946 variables -> 38880
                3, 4, // 14947 - 14949 variables -> 38888
                6, 8, // 14950 - 14955 variables -> 38904
                4, 6, // 14956 - 14959 variables -> 38916
                8, 10, // 14960 - 14967 variables -> 38936
                5, 6, // 14968 - 14972 variables -> 38948
                4, 6, // 14973 - 14976 variables -> 38960
                2, 2, // 14977 - 14978 variables -> 38964
                5, 6, // 14979 - 14983 variables -> 38976
                4, 6, // 14984 - 14987 variables -> 38988
                5, 6, // 14988 - 14992 variables -> 39000
                9, 12, // 14993 - 15001 variables -> 39024
                5, 6, // 15002 - 15006 variables -> 39036
                18, 24, // 15007 - 15024 variables -> 39084
                8, 10, // 15025 - 15032 variables -> 39104
                1, 2, // 15033 variables -> 39108
                8, 10, // 15034 - 15041 variables -> 39128
                5, 6, // 15042 - 15046 variables -> 39140
                9, 12, // 15047 - 15055 variables -> 39164
                4, 6, // 15056 - 15059 variables -> 39176
                5, 6, // 15060 - 15064 variables -> 39188
                11, 14, // 15065 - 15075 variables -> 39216
                4, 6, // 15076 - 15079 variables -> 39228
                5, 6, // 15080 - 15084 variables -> 39240
                40, 52, // 15085 - 15124 variables -> 39344
                15, 20, // 15125 - 15139 variables -> 39384
                5, 6, // 15140 - 15144 variables -> 39396
                8, 10, // 15145 - 15152 variables -> 39416
                1, 2, // 15153 variables -> 39420
                8, 10, // 15154 - 15161 variables -> 39440
                6, 8, // 15162 - 15167 variables -> 39456
                8, 10, // 15168 - 15175 variables -> 39476
                9, 12, // 15176 - 15184 variables -> 39500
                9, 12, // 15185 - 15193 variables -> 39524
                2, 2, // 15194 - 15195 variables -> 39528
                4, 6, // 15196 - 15199 variables -> 39540
                4, 4, // 15200 - 15203 variables -> 39548
                10, 14, // 15204 - 15213 variables -> 39576
                13, 16, // 15214 - 15226 variables -> 39608
                6, 8, // 15227 - 15232 variables -> 39624
                9, 12, // 15233 - 15241 variables -> 39648
                5, 6, // 15242 - 15246 variables -> 39660
                17, 22, // 15247 - 15263 variables -> 39704
                1, 2, // 15264 variables -> 39708
                8, 10, // 15265 - 15272 variables -> 39728
                6, 8, // 15273 - 15278 variables -> 39744
                5, 6, // 15279 - 15283 variables -> 39756
                16, 22, // 15284 - 15299 variables -> 39800
                2, 2, // 15300 - 15301 variables -> 39804
                17, 22, // 15302 - 15318 variables -> 39848
                5, 6, // 15319 - 15323 variables -> 39860
                6, 8, // 15324 - 15329 variables -> 39876
                7, 10, // 15330 - 15336 variables -> 39896
                10, 12, // 15337 - 15346 variables -> 39920
                9, 12, // 15347 - 15355 variables -> 39944
                1, 2, // 15356 variables -> 39948
                8, 10, // 15357 - 15364 variables -> 39968
                5, 6, // 15365 - 15369 variables -> 39980
                9, 12, // 15370 - 15378 variables -> 40004
                1, 2, // 15379 variables -> 40008
                4, 4, // 15380 - 15383 variables -> 40016
                10, 14, // 15384 - 15393 variables -> 40044
                8, 10, // 15394 - 15401 variables -> 40064
                2, 2, // 15402 - 15403 variables -> 40068
                4, 6, // 15404 - 15407 variables -> 40080
                14, 18, // 15408 - 15421 variables -> 40116
                3, 4, // 15422 - 15424 variables -> 40124
                9, 12, // 15425 - 15433 variables -> 40148
                6, 8, // 15434 - 15439 variables -> 40164
                14, 18, // 15440 - 15453 variables -> 40200
                10, 12, // 15454 - 15463 variables -> 40224
                4, 6, // 15464 - 15467 variables -> 40236
                5, 6, // 15468 - 15472 variables -> 40248
                3, 4, // 15473 - 15475 variables -> 40256
                4, 6, // 15476 - 15479 variables -> 40268
                5, 6, // 15480 - 15484 variables -> 40280
                11, 14, // 15485 - 15495 variables -> 40308
                3, 4, // 15496 - 15498 variables -> 40316
                1, 2, // 15499 variables -> 40320
                10, 12, // 15500 - 15509 variables -> 40344
                9, 12, // 15510 - 15518 variables -> 40368
                3, 4, // 15519 - 15521 variables -> 40376
                5, 6, // 15522 - 15526 variables -> 40388
                13, 18, // 15527 - 15539 variables -> 40424
                14, 18, // 15540 - 15553 variables -> 40460
                10, 12, // 15554 - 15563 variables -> 40484
                1, 2, // 15564 variables -> 40488
                12, 16, // 15565 - 15576 variables -> 40520
                10, 12, // 15577 - 15586 variables -> 40544
                6, 8, // 15587 - 15592 variables -> 40560
                14, 18, // 15593 - 15606 variables -> 40596
                7, 10, // 15607 - 15613 variables -> 40616
                20, 26, // 15614 - 15633 variables -> 40668
                3, 4, // 15634 - 15636 variables -> 40676
                5, 6, // 15637 - 15641 variables -> 40688
                6, 8, // 15642 - 15647 variables -> 40704
                5, 6, // 15648 - 15652 variables -> 40716
                4, 6, // 15653 - 15656 variables -> 40728
                3, 4, // 15657 - 15659 variables -> 40736
                2, 2, // 15660 - 15661 variables -> 40740
                8, 10, // 15662 - 15669 variables -> 40760
                15, 20, // 15670 - 15684 variables -> 40800
                3, 4, // 15685 - 15687 variables -> 40808
                5, 6, // 15688 - 15692 variables -> 40820
                6, 8, // 15693 - 15698 variables -> 40836
                3, 4, // 15699 - 15701 variables -> 40844
                15, 20, // 15702 - 15716 variables -> 40884
                8, 10, // 15717 - 15724 variables -> 40904
                2, 2, // 15725 - 15726 variables -> 40908
                26, 34, // 15727 - 15752 variables -> 40976
                1, 2, // 15753 variables -> 40980
                3, 4, // 15754 - 15756 variables -> 40988
                19, 24, // 15757 - 15775 variables -> 41036
                1, 2, // 15776 variables -> 41040
                10, 12, // 15777 - 15786 variables -> 41064
                9, 12, // 15787 - 15795 variables -> 41088
                8, 10, // 15796 - 15803 variables -> 41108
                4, 6, // 15804 - 15807 variables -> 41120
                2, 2, // 15808 - 15809 variables -> 41124
                9, 12, // 15810 - 15818 variables -> 41148
                23, 30, // 15819 - 15841 variables -> 41208
                5, 6, // 15842 - 15846 variables -> 41220
                9, 12, // 15847 - 15855 variables -> 41244
                12, 16, // 15856 - 15867 variables -> 41276
                9, 12, // 15868 - 15876 variables -> 41300
                2, 2, // 15877 - 15878 variables -> 41304
                17, 22, // 15879 - 15895 variables -> 41348
                14, 18, // 15896 - 15909 variables -> 41384
                9, 12, // 15910 - 15918 variables -> 41408
                11, 14, // 15919 - 15929 variables -> 41436
                7, 10, // 15930 - 15936 variables -> 41456
                2, 2, // 15937 - 15938 variables -> 41460
                9, 12, // 15939 - 15947 variables -> 41484
                9, 12, // 15948 - 15956 variables -> 41508
                3, 4, // 15957 - 15959 variables -> 41516
                2, 2, // 15960 - 15961 variables -> 41520
                3, 4, // 15962 - 15964 variables -> 41528
                5, 6, // 15965 - 15969 variables -> 41540
                9, 12, // 15970 - 15978 variables -> 41564
                1, 2, // 15979 variables -> 41568
                13, 16, // 15980 - 15992 variables -> 41600
                14, 18, // 15993 - 16006 variables -> 41636
                1, 2, // 16007 variables -> 41640
                31, 40, // 16008 - 16038 variables -> 41720
                6, 8, // 16039 - 16044 variables -> 41736
                12, 16, // 16045 - 16056 variables -> 41768
                5, 6, // 16057 - 16061 variables -> 41780
                6, 8, // 16062 - 16067 variables -> 41796
                8, 10, // 16068 - 16075 variables -> 41816
                1, 2, // 16076 variables -> 41820
                3, 4, // 16077 - 16079 variables -> 41828
                14, 18, // 16080 - 16093 variables -> 41864
                6, 8, // 16094 - 16099 variables -> 41880
                8, 10, // 16100 - 16107 variables -> 41900
                6, 8, // 16108 - 16113 variables -> 41916
                10, 12, // 16114 - 16123 variables -> 41940
                3, 4, // 16124 - 16126 variables -> 41948
                13, 18, // 16127 - 16139 variables -> 41984
                2, 2, // 16140 - 16141 variables -> 41988
                14, 18, // 16142 - 16155 variables -> 42024
                8, 10, // 16156 - 16163 variables -> 42044
                1, 2, // 16164 variables -> 42048
                3, 4, // 16165 - 16167 variables -> 42056
                2, 2, // 16168 - 16169 variables -> 42060
                3, 4, // 16170 - 16172 variables -> 42068
                6, 8, // 16173 - 16178 variables -> 42084
                21, 28, // 16179 - 16199 variables -> 42140
                2, 2, // 16200 - 16201 variables -> 42144
                5, 6, // 16202 - 16206 variables -> 42156
                17, 22, // 16207 - 16223 variables -> 42200
                9, 12, // 16224 - 16232 variables -> 42224
                4, 6, // 16233 - 16236 variables -> 42236
                11, 14, // 16237 - 16247 variables -> 42264
                14, 18, // 16248 - 16261 variables -> 42300
                3, 4, // 16262 - 16264 variables -> 42308
                5, 6, // 16265 - 16269 variables -> 42320
                6, 8, // 16270 - 16275 variables -> 42336
                4, 6, // 16276 - 16279 variables -> 42348
                5, 6, // 16280 - 16284 variables -> 42360
                8, 10, // 16285 - 16292 variables -> 42380
                6, 8, // 16293 - 16298 variables -> 42396
                3, 4, // 16299 - 16301 variables -> 42404
                2, 2, // 16302 - 16303 variables -> 42408
                13, 18, // 16304 - 16316 variables -> 42444
                8, 10, // 16317 - 16324 variables -> 42464
                5, 6, // 16325 - 16329 variables -> 42476
                15, 20, // 16330 - 16344 variables -> 42516
                17, 22, // 16345 - 16361 variables -> 42560
                6, 8, // 16362 - 16367 variables -> 42576
                5, 6, // 16368 - 16372 variables -> 42588
                23, 30, // 16373 - 16395 variables -> 42648
                3, 4, // 16396 - 16398 variables -> 42656
                1, 2, // 16399 variables -> 42660
                4, 4, // 16400 - 16403 variables -> 42668
                13, 18, // 16404 - 16416 variables -> 42704
                5, 6, // 16417 - 16421 variables -> 42716
                23, 30, // 16422 - 16444 variables -> 42776
                2, 2, // 16445 - 16446 variables -> 42780
                3, 4, // 16447 - 16449 variables -> 42788
                6, 8, // 16450 - 16455 variables -> 42804
                4, 6, // 16456 - 16459 variables -> 42816
                4, 4, // 16460 - 16463 variables -> 42824
                4, 6, // 16464 - 16467 variables -> 42836
                9, 12, // 16468 - 16476 variables -> 42860
                11, 14, // 16477 - 16487 variables -> 42888
                26, 34, // 16488 - 16513 variables -> 42956
                11, 14, // 16514 - 16524 variables -> 42984
                5, 6, // 16525 - 16529 variables -> 42996
                3, 4, // 16530 - 16532 variables -> 43004
                1, 2, // 16533 variables -> 43008
                5, 6, // 16534 - 16538 variables -> 43020
                3, 4, // 16539 - 16541 variables -> 43028
                11, 14, // 16542 - 16552 variables -> 43056
                3, 4, // 16553 - 16555 variables -> 43064
                1, 2, // 16556 variables -> 43068
                5, 6, // 16557 - 16561 variables -> 43080
                22, 28, // 16562 - 16583 variables -> 43136
                1, 2, // 16584 variables -> 43140
                3, 4, // 16585 - 16587 variables -> 43148
                5, 6, // 16588 - 16592 variables -> 43160
                6, 8, // 16593 - 16598 variables -> 43176
                8, 10, // 16599 - 16606 variables -> 43196
                1, 2, // 16607 variables -> 43200
                8, 10, // 16608 - 16615 variables -> 43220
                1, 2, // 16616 variables -> 43224
                8, 10, // 16617 - 16624 variables -> 43244
                2, 2, // 16625 - 16626 variables -> 43248
                3, 4, // 16627 - 16629 variables -> 43256
                23, 30, // 16630 - 16652 variables -> 43316
                1, 2, // 16653 variables -> 43320
                10, 12, // 16654 - 16663 variables -> 43344
                9, 12, // 16664 - 16672 variables -> 43368
                7, 10, // 16673 - 16679 variables -> 43388
                14, 18, // 16680 - 16693 variables -> 43424
                10, 12, // 16694 - 16703 variables -> 43448
                10, 14, // 16704 - 16713 variables -> 43476
                8, 10, // 16714 - 16721 variables -> 43496
                2, 2, // 16722 - 16723 variables -> 43500
                9, 12, // 16724 - 16732 variables -> 43524
                4, 6, // 16733 - 16736 variables -> 43536
                8, 10, // 16737 - 16744 variables -> 43556
                5, 6, // 16745 - 16749 variables -> 43568
                10, 14, // 16750 - 16759 variables -> 43596
                10, 12, // 16760 - 16769 variables -> 43620
                3, 4, // 16770 - 16772 variables -> 43628
                11, 14, // 16773 - 16783 variables -> 43656
                3, 4, // 16784 - 16786 variables -> 43664
                13, 18, // 16787 - 16799 variables -> 43700
                2, 2, // 16800 - 16801 variables -> 43704
                8, 10, // 16802 - 16809 variables -> 43724
                6, 8, // 16810 - 16815 variables -> 43740
                3, 4, // 16816 - 16818 variables -> 43748
                6, 8, // 16819 - 16824 variables -> 43764
                8, 10, // 16825 - 16832 variables -> 43784
                9, 12, // 16833 - 16841 variables -> 43808
                14, 18, // 16842 - 16855 variables -> 43844
                14, 18, // 16856 - 16869 variables -> 43880
                6, 8, // 16870 - 16875 variables -> 43896
                4, 6, // 16876 - 16879 variables -> 43908
                14, 18, // 16880 - 16893 variables -> 43944
                13, 16, // 16894 - 16906 variables -> 43976
                10, 14, // 16907 - 16916 variables -> 44004
                5, 6, // 16917 - 16921 variables -> 44016
                5, 6, // 16922 - 16926 variables -> 44028
                7, 10, // 16927 - 16933 variables -> 44048
                11, 14, // 16934 - 16944 variables -> 44076
                3, 4, // 16945 - 16947 variables -> 44084
                5, 6, // 16948 - 16952 variables -> 44096
                1, 2, // 16953 variables -> 44100
                10, 12, // 16954 - 16963 variables -> 44124
                9, 12, // 16964 - 16972 variables -> 44148
                3, 4, // 16973 - 16975 variables -> 44156
                4, 6, // 16976 - 16979 variables -> 44168
                5, 6, // 16980 - 16984 variables -> 44180
                9, 12, // 16985 - 16993 variables -> 44204
                2, 2, // 16994 - 16995 variables -> 44208
                12, 16, // 16996 - 17007 variables -> 44240
                2, 2, // 17008 - 17009 variables -> 44244
                9, 12, // 17010 - 17018 variables -> 44268
                5, 6, // 17019 - 17023 variables -> 44280
                3, 4, // 17024 - 17026 variables -> 44288
                10, 14, // 17027 - 17036 variables -> 44316
                5, 6, // 17037 - 17041 variables -> 44328
                3, 4, // 17042 - 17044 variables -> 44336
                2, 2, // 17045 - 17046 variables -> 44340
                9, 12, // 17047 - 17055 variables -> 44364
                14, 18, // 17056 - 17069 variables -> 44400
                3, 4, // 17070 - 17072 variables -> 44408
                27, 36, // 17073 - 17099 variables -> 44480
                14, 18, // 17100 - 17113 variables -> 44516
                10, 12, // 17114 - 17123 variables -> 44540
                9, 12, // 17124 - 17132 variables -> 44564
                1, 2, // 17133 variables -> 44568
                3, 4, // 17134 - 17136 variables -> 44576
                2, 2, // 17137 - 17138 variables -> 44580
                3, 4, // 17139 - 17141 variables -> 44588
                6, 8, // 17142 - 17147 variables -> 44604
                9, 12, // 17148 - 17156 variables -> 44628
                3, 4, // 17157 - 17159 variables -> 44636
                28, 36, // 17160 - 17187 variables -> 44708
                5, 6, // 17188 - 17192 variables -> 44720
                14, 18, // 17193 - 17206 variables -> 44756
                1, 2, // 17207 variables -> 44760
                9, 12, // 17208 - 17216 variables -> 44784
                8, 10, // 17217 - 17224 variables -> 44804
                5, 6, // 17225 - 17229 variables -> 44816
                9, 12, // 17230 - 17238 variables -> 44840
                18, 24, // 17239 - 17256 variables -> 44888
                7, 8, // 17257 - 17263 variables -> 44904
                4, 6, // 17264 - 17267 variables -> 44916
                5, 6, // 17268 - 17272 variables -> 44928
                12, 16, // 17273 - 17284 variables -> 44960
                9, 12, // 17285 - 17293 variables -> 44984
                2, 2, // 17294 - 17295 variables -> 44988
                14, 18, // 17296 - 17309 variables -> 45024
                7, 10, // 17310 - 17316 variables -> 45044
                16, 20, // 17317 - 17332 variables -> 45084
                7, 10, // 17333 - 17339 variables -> 45104
                2, 2, // 17340 - 17341 variables -> 45108
                5, 6, // 17342 - 17346 variables -> 45120
                13, 18, // 17347 - 17359 variables -> 45156
                4, 4, // 17360 - 17363 variables -> 45164
                1, 2, // 17364 variables -> 45168
                31, 40, // 17365 - 17395 variables -> 45248
                4, 6, // 17396 - 17399 variables -> 45260
                2, 2, // 17400 - 17401 variables -> 45264
                12, 16, // 17402 - 17413 variables -> 45296
                2, 2, // 17414 - 17415 variables -> 45300
                3, 4, // 17416 - 17418 variables -> 45308
                6, 8, // 17419 - 17424 variables -> 45324
                14, 18, // 17425 - 17438 variables -> 45360
                8, 10, // 17439 - 17446 variables -> 45380
                9, 12, // 17447 - 17455 variables -> 45404
                4, 6, // 17456 - 17459 variables -> 45416
                2, 2, // 17460 - 17461 variables -> 45420
                8, 10, // 17462 - 17469 variables -> 45440
                6, 8, // 17470 - 17475 variables -> 45456
                3, 4, // 17476 - 17478 variables -> 45464
                5, 6, // 17479 - 17483 variables -> 45476
                9, 12, // 17484 - 17492 variables -> 45500
                1, 2, // 17493 variables -> 45504
                8, 10, // 17494 - 17501 variables -> 45524
                14, 18, // 17502 - 17515 variables -> 45560
                6, 8, // 17516 - 17521 variables -> 45576
                5, 6, // 17522 - 17526 variables -> 45588
                3, 4, // 17527 - 17529 variables -> 45596
                15, 20, // 17530 - 17544 variables -> 45636
                3, 4, // 17545 - 17547 variables -> 45644
                5, 6, // 17548 - 17552 variables -> 45656
                27, 36, // 17553 - 17579 variables -> 45728
                5, 6, // 17580 - 17584 variables -> 45740
                2, 2, // 17585 - 17586 variables -> 45744
                7, 10, // 17587 - 17593 variables -> 45764
                5, 6, // 17594 - 17598 variables -> 45776
                18, 24, // 17599 - 17616 variables -> 45824
                5, 6, // 17617 - 17621 variables -> 45836
                11, 14, // 17622 - 17632 variables -> 45864
                12, 16, // 17633 - 17644 variables -> 45896
                5, 6, // 17645 - 17649 variables -> 45908
                14, 18, // 17650 - 17663 variables -> 45944
                1, 2, // 17664 variables -> 45948
                8, 10, // 17665 - 17672 variables -> 45968
                15, 20, // 17673 - 17687 variables -> 46008
                8, 10, // 17688 - 17695 variables -> 46028
                6, 8, // 17696 - 17701 variables -> 46044
                5, 6, // 17702 - 17706 variables -> 46056
                3, 4, // 17707 - 17709 variables -> 46064
                4, 6, // 17710 - 17713 variables -> 46076
                2, 2, // 17714 - 17715 variables -> 46080
                8, 10, // 17716 - 17723 variables -> 46100
                1, 2, // 17724 variables -> 46104
                9, 12, // 17725 - 17733 variables -> 46128
                3, 4, // 17734 - 17736 variables -> 46136
                2, 2, // 17737 - 17738 variables -> 46140
                3, 4, // 17739 - 17741 variables -> 46148
                6, 8, // 17742 - 17747 variables -> 46164
                8, 10, // 17748 - 17755 variables -> 46184
                4, 6, // 17756 - 17759 variables -> 46196
                10, 12, // 17760 - 17769 variables -> 46220
                14, 18, // 17770 - 17783 variables -> 46256
                10, 14, // 17784 - 17793 variables -> 46284
                10, 12, // 17794 - 17803 variables -> 46308
                12, 16, // 17804 - 17815 variables -> 46340
                6, 8, // 17816 - 17821 variables -> 46356
                5, 6, // 17822 - 17826 variables -> 46368
                12, 16, // 17827 - 17838 variables -> 46400
                6, 8, // 17839 - 17844 variables -> 46416
                3, 4, // 17845 - 17847 variables -> 46424
                2, 2, // 17848 - 17849 variables -> 46428
                4, 6, // 17850 - 17853 variables -> 46440
                14, 18, // 17854 - 17867 variables -> 46476
                19, 24, // 17868 - 17886 variables -> 46524
                13, 18, // 17887 - 17899 variables -> 46560
                8, 10, // 17900 - 17907 variables -> 46580
                9, 12, // 17908 - 17916 variables -> 46604
                2, 2, // 17917 - 17918 variables -> 46608
                3, 4, // 17919 - 17921 variables -> 46616
                11, 14, // 17922 - 17932 variables -> 46644
                7, 10, // 17933 - 17939 variables -> 46664
                5, 6, // 17940 - 17944 variables -> 46676
                5, 6, // 17945 - 17949 variables -> 46688
                4, 6, // 17950 - 17953 variables -> 46700
                14, 18, // 17954 - 17967 variables -> 46736
                9, 12, // 17968 - 17976 variables -> 46760
                2, 2, // 17977 - 17978 variables -> 46764
                21, 28, // 17979 - 17999 variables -> 46820
                14, 18, // 18000 - 18013 variables -> 46856
                11, 14, // 18014 - 18024 variables -> 46884
                12, 16, // 18025 - 18036 variables -> 46916
                10, 12, // 18037 - 18046 variables -> 46940
                10, 14, // 18047 - 18056 variables -> 46968
                19, 24, // 18057 - 18075 variables -> 47016
                9, 12, // 18076 - 18084 variables -> 47040
                17, 22, // 18085 - 18101 variables -> 47084
                5, 6, // 18102 - 18106 variables -> 47096
                1, 2, // 18107 variables -> 47100
                8, 10, // 18108 - 18115 variables -> 47120
                6, 8, // 18116 - 18121 variables -> 47136
                3, 4, // 18122 - 18124 variables -> 47144
                2, 2, // 18125 - 18126 variables -> 47148
                3, 4, // 18127 - 18129 variables -> 47156
                10, 14, // 18130 - 18139 variables -> 47184
                10, 12, // 18140 - 18149 variables -> 47208
                4, 6, // 18150 - 18153 variables -> 47220
                3, 4, // 18154 - 18156 variables -> 47228
                5, 6, // 18157 - 18161 variables -> 47240
                11, 14, // 18162 - 18172 variables -> 47268
                3, 4, // 18173 - 18175 variables -> 47276
                1, 2, // 18176 variables -> 47280
                3, 4, // 18177 - 18179 variables -> 47288
                24, 30, // 18180 - 18203 variables -> 47348
                4, 6, // 18204 - 18207 variables -> 47360
                2, 2, // 18208 - 18209 variables -> 47364
                4, 6, // 18210 - 18213 variables -> 47376
                8, 10, // 18214 - 18221 variables -> 47396
                2, 2, // 18222 - 18223 variables -> 47400
                23, 30, // 18224 - 18246 variables -> 47460
                17, 22, // 18247 - 18263 variables -> 47504
                1, 2, // 18264 variables -> 47508
                3, 4, // 18265 - 18267 variables -> 47516
                5, 6, // 18268 - 18272 variables -> 47528
                6, 8, // 18273 - 18278 variables -> 47544
                5, 6, // 18279 - 18283 variables -> 47556
                4, 6, // 18284 - 18287 variables -> 47568
                12, 16, // 18288 - 18299 variables -> 47600
                10, 12, // 18300 - 18309 variables -> 47624
                9, 12, // 18310 - 18318 variables -> 47648
                5, 6, // 18319 - 18323 variables -> 47660
                6, 8, // 18324 - 18329 variables -> 47676
                3, 4, // 18330 - 18332 variables -> 47684
                1, 2, // 18333 variables -> 47688
                19, 24, // 18334 - 18352 variables -> 47736
                9, 12, // 18353 - 18361 variables -> 47760
                3, 4, // 18362 - 18364 variables -> 47768
                5, 6, // 18365 - 18369 variables -> 47780
                6, 8, // 18370 - 18375 variables -> 47796
                4, 6, // 18376 - 18379 variables -> 47808
                5, 6, // 18380 - 18384 variables -> 47820
                8, 10, // 18385 - 18392 variables -> 47840
                1, 2, // 18393 variables -> 47844
                5, 6, // 18394 - 18398 variables -> 47856
                9, 12, // 18399 - 18407 variables -> 47880
                22, 28, // 18408 - 18429 variables -> 47936
                10, 14, // 18430 - 18439 variables -> 47964
                5, 6, // 18440 - 18444 variables -> 47976
                3, 4, // 18445 - 18447 variables -> 47984
                9, 12, // 18448 - 18456 variables -> 48008
                7, 8, // 18457 - 18463 variables -> 48024
                4, 6, // 18464 - 18467 variables -> 48036
                9, 12, // 18468 - 18476 variables -> 48060
                3, 4, // 18477 - 18479 variables -> 48068
                5, 6, // 18480 - 18484 variables -> 48080
                11, 14, // 18485 - 18495 variables -> 48108
                4, 6, // 18496 - 18499 variables -> 48120
                10, 12, // 18500 - 18509 variables -> 48144
                7, 10, // 18510 - 18516 variables -> 48164
                5, 6, // 18517 - 18521 variables -> 48176
                5, 6, // 18522 - 18526 variables -> 48188
                6, 8, // 18527 - 18532 variables -> 48204
                4, 6, // 18533 - 18536 variables -> 48216
                5, 6, // 18537 - 18541 variables -> 48228
                3, 4, // 18542 - 18544 variables -> 48236
                2, 2, // 18545 - 18546 variables -> 48240
                3, 4, // 18547 - 18549 variables -> 48248
                6, 8, // 18550 - 18555 variables -> 48264
                9, 12, // 18556 - 18564 variables -> 48288
                3, 4, // 18565 - 18567 variables -> 48296
                11, 14, // 18568 - 18578 variables -> 48324
                14, 18, // 18579 - 18592 variables -> 48360
                7, 10, // 18593 - 18599 variables -> 48380
                2, 2, // 18600 - 18601 variables -> 48384
                12, 16, // 18602 - 18613 variables -> 48416
                5, 6, // 18614 - 18618 variables -> 48428
                15, 20, // 18619 - 18633 variables -> 48468
                5, 6, // 18634 - 18638 variables -> 48480
                8, 10, // 18639 - 18646 variables -> 48500
                6, 8, // 18647 - 18652 variables -> 48516
                3, 4, // 18653 - 18655 variables -> 48524
                23, 30, // 18656 - 18678 variables -> 48584
                28, 36, // 18679 - 18706 variables -> 48656
                9, 12, // 18707 - 18715 variables -> 48680
                6, 8, // 18716 - 18721 variables -> 48696
                17, 22, // 18722 - 18738 variables -> 48740
                9, 12, // 18739 - 18747 variables -> 48764
                2, 2, // 18748 - 18749 variables -> 48768
                4, 6, // 18750 - 18753 variables -> 48780
                10, 12, // 18754 - 18763 variables -> 48804
                12, 16, // 18764 - 18775 variables -> 48836
                4, 6, // 18776 - 18779 variables -> 48848
                5, 6, // 18780 - 18784 variables -> 48860
                2, 2, // 18785 - 18786 variables -> 48864
                13, 18, // 18787 - 18799 variables -> 48900
                4, 4, // 18800 - 18803 variables -> 48908
                20, 26, // 18804 - 18823 variables -> 48960
                3, 4, // 18824 - 18826 variables -> 48968
                6, 8, // 18827 - 18832 variables -> 48984
                14, 18, // 18833 - 18846 variables -> 49020
                7, 10, // 18847 - 18853 variables -> 49040
                6, 8, // 18854 - 18859 variables -> 49056
                8, 10, // 18860 - 18867 variables -> 49076
                5, 6, // 18868 - 18872 variables -> 49088
                11, 14, // 18873 - 18883 variables -> 49116
                3, 4, // 18884 - 18886 variables -> 49124
                15, 20, // 18887 - 18901 variables -> 49164
                17, 22, // 18902 - 18918 variables -> 49208
                14, 18, // 18919 - 18932 variables -> 49244
                9, 12, // 18933 - 18941 variables -> 49268
                6, 8, // 18942 - 18947 variables -> 49284
                22, 28, // 18948 - 18969 variables -> 49340
                9, 12, // 18970 - 18978 variables -> 49364
                5, 6, // 18979 - 18983 variables -> 49376
                4, 6, // 18984 - 18987 variables -> 49388
                6, 8, // 18988 - 18993 variables -> 49404
                5, 6, // 18994 - 18998 variables -> 49416
                9, 12, // 18999 - 19007 variables -> 49440
                19, 24, // 19008 - 19026 variables -> 49488
                12, 16, // 19027 - 19038 variables -> 49520
                11, 14, // 19039 - 19049 variables -> 49548
                3, 4, // 19050 - 19052 variables -> 49556
                11, 14, // 19053 - 19063 variables -> 49584
                9, 12, // 19064 - 19072 variables -> 49608
                4, 6, // 19073 - 19076 variables -> 49620
                8, 10, // 19077 - 19084 variables -> 49640
                9, 12, // 19085 - 19093 variables -> 49664
                16, 20, // 19094 - 19109 variables -> 49704
                4, 6, // 19110 - 19113 variables -> 49716
                3, 4, // 19114 - 19116 variables -> 49724
                7, 8, // 19117 - 19123 variables -> 49740
                13, 18, // 19124 - 19136 variables -> 49776
                10, 12, // 19137 - 19146 variables -> 49800
                13, 18, // 19147 - 19159 variables -> 49836
                8, 10, // 19160 - 19167 variables -> 49856
                2, 2, // 19168 - 19169 variables -> 49860
                3, 4, // 19170 - 19172 variables -> 49868
                15, 20, // 19173 - 19187 variables -> 49908
                8, 10, // 19188 - 19195 variables -> 49928
                11, 14, // 19196 - 19206 variables -> 49956
                3, 4, // 19207 - 19209 variables -> 49964
                4, 6, // 19210 - 19213 variables -> 49976
                2, 2, // 19214 - 19215 variables -> 49980
                8, 10, // 19216 - 19223 variables -> 50000
                18, 24, // 19224 - 19241 variables -> 50048
                14, 18, // 19242 - 19255 variables -> 50084
                1, 2, // 19256 variables -> 50088
                3, 4, // 19257 - 19259 variables -> 50096
                16, 20, // 19260 - 19275 variables -> 50136
                12, 16, // 19276 - 19287 variables -> 50168
                11, 14, // 19288 - 19298 variables -> 50196
                8, 10, // 19299 - 19306 variables -> 50216
                10, 14, // 19307 - 19316 variables -> 50244
                5, 6, // 19317 - 19321 variables -> 50256
                3, 4, // 19322 - 19324 variables -> 50264
                5, 6, // 19325 - 19329 variables -> 50276
                15, 20, // 19330 - 19344 variables -> 50316
                5, 6, // 19345 - 19349 variables -> 50328
                7, 10, // 19350 - 19356 variables -> 50348
                5, 6, // 19357 - 19361 variables -> 50360
                2, 2, // 19362 - 19363 variables -> 50364
                9, 12, // 19364 - 19372 variables -> 50388
                4, 6, // 19373 - 19376 variables -> 50400
                23, 30, // 19377 - 19399 variables -> 50460
                8, 10, // 19400 - 19407 variables -> 50480
                6, 8, // 19408 - 19413 variables -> 50496
                5, 6, // 19414 - 19418 variables -> 50508
                3, 4, // 19419 - 19421 variables -> 50516
                5, 6, // 19422 - 19426 variables -> 50528
                6, 8, // 19427 - 19432 variables -> 50544
                31, 40, // 19433 - 19463 variables -> 50624
                1, 2, // 19464 variables -> 50628
                3, 4, // 19465 - 19467 variables -> 50636
                2, 2, // 19468 - 19469 variables -> 50640
                9, 12, // 19470 - 19478 variables -> 50664
                14, 18, // 19479 - 19492 variables -> 50700
                3, 4, // 19493 - 19495 variables -> 50708
                4, 6, // 19496 - 19499 variables -> 50720
                7, 8, // 19500 - 19506 variables -> 50736
                7, 10, // 19507 - 19513 variables -> 50756
                5, 6, // 19514 - 19518 variables -> 50768
                14, 18, // 19519 - 19532 variables -> 50804
                14, 18, // 19533 - 19546 variables -> 50840
                1, 2, // 19547 variables -> 50844
                9, 12, // 19548 - 19556 variables -> 50868
                13, 16, // 19557 - 19569 variables -> 50900
                6, 8, // 19570 - 19575 variables -> 50916
                4, 6, // 19576 - 19579 variables -> 50928
                4, 4, // 19580 - 19583 variables -> 50936
                4, 6, // 19584 - 19587 variables -> 50948
                5, 6, // 19588 - 19592 variables -> 50960
                1, 2, // 19593 variables -> 50964
                40, 52, // 19594 - 19633 variables -> 51068
                11, 14, // 19634 - 19644 variables -> 51096
                3, 4, // 19645 - 19647 variables -> 51104
                16, 20, // 19648 - 19663 variables -> 51144
                12, 16, // 19664 - 19675 variables -> 51176
                1, 2, // 19676 variables -> 51180
                3, 4, // 19677 - 19679 variables -> 51188
                5, 6, // 19680 - 19684 variables -> 51200
                9, 12, // 19685 - 19693 variables -> 51224
                2, 2, // 19694 - 19695 variables -> 51228
                4, 6, // 19696 - 19699 variables -> 51240
                10, 12, // 19700 - 19709 variables -> 51264
                9, 12, // 19710 - 19718 variables -> 51288
                5, 6, // 19719 - 19723 variables -> 51300
                3, 4, // 19724 - 19726 variables -> 51308
                10, 14, // 19727 - 19736 variables -> 51336
                8, 10, // 19737 - 19744 variables -> 51356
                5, 6, // 19745 - 19749 variables -> 51368
                4, 6, // 19750 - 19753 variables -> 51380
                11, 14, // 19754 - 19764 variables -> 51408
                8, 10, // 19765 - 19772 variables -> 51428
                11, 14, // 19773 - 19783 variables -> 51456
                12, 16, // 19784 - 19795 variables -> 51488
                6, 8, // 19796 - 19801 variables -> 51504
                5, 6, // 19802 - 19806 variables -> 51516
                9, 12, // 19807 - 19815 variables -> 51540
                3, 4, // 19816 - 19818 variables -> 51548
                6, 8, // 19819 - 19824 variables -> 51564
                17, 22, // 19825 - 19841 variables -> 51608
                5, 6, // 19842 - 19846 variables -> 51620
                1, 2, // 19847 variables -> 51624
                14, 18, // 19848 - 19861 variables -> 51660
                17, 22, // 19862 - 19878 variables -> 51704
                5, 6, // 19879 - 19883 variables -> 51716
                1, 2, // 19884 variables -> 51720
                14, 18, // 19885 - 19898 variables -> 51756
                5, 6, // 19899 - 19903 variables -> 51768
                12, 16, // 19904 - 19915 variables -> 51800
                11, 14, // 19916 - 19926 variables -> 51828
                7, 10, // 19927 - 19933 variables -> 51848
                5, 6, // 19934 - 19938 variables -> 51860
                9, 12, // 19939 - 19947 variables -> 51884
                2, 2, // 19948 - 19949 variables -> 51888
                4, 6, // 19950 - 19953 variables -> 51900
                3, 4, // 19954 - 19956 variables -> 51908
                7, 8, // 19957 - 19963 variables -> 51924
                13, 18, // 19964 - 19976 variables -> 51960
                10, 12, // 19977 - 19986 variables -> 51984
                12, 16, // 19987 - 19998 variables -> 52016
                1, 2, // 19999 variables -> 52020
                4, 4, // 20000 - 20003 variables -> 52028
                10, 14, // 20004 - 20013 variables -> 52056
                3, 4, // 20014 - 20016 variables -> 52064
                7, 8, // 20017 - 20023 variables -> 52080
                9, 12, // 20024 - 20032 variables -> 52104
                9, 12, // 20033 - 20041 variables -> 52128
                23, 30, // 20042 - 20064 variables -> 52188
                12, 16, // 20065 - 20076 variables -> 52220
                7, 8, // 20077 - 20083 variables -> 52236
                3, 4, // 20084 - 20086 variables -> 52244
                1, 2, // 20087 variables -> 52248
                5, 6, // 20088 - 20092 variables -> 52260
                17, 22, // 20093 - 20109 variables -> 52304
                9, 12, // 20110 - 20118 variables -> 52328
                6, 8, // 20119 - 20124 variables -> 52344
                8, 10, // 20125 - 20132 variables -> 52364
                4, 6, // 20133 - 20136 variables -> 52376
                5, 6, // 20137 - 20141 variables -> 52388
                5, 6, // 20142 - 20146 variables -> 52400
                10, 14, // 20147 - 20156 variables -> 52428
                5, 6, // 20157 - 20161 variables -> 52440
                14, 18, // 20162 - 20175 variables -> 52476
                8, 10, // 20176 - 20183 variables -> 52496
                9, 12, // 20184 - 20192 variables -> 52520
                1, 2, // 20193 variables -> 52524
                8, 10, // 20194 - 20201 variables -> 52544
                2, 2, // 20202 - 20203 variables -> 52548
                3, 4, // 20204 - 20206 variables -> 52556
                20, 26, // 20207 - 20226 variables -> 52608
                3, 4, // 20227 - 20229 variables -> 52616
                9, 12, // 20230 - 20238 variables -> 52640
                6, 8, // 20239 - 20244 variables -> 52656
                3, 4, // 20245 - 20247 variables -> 52664
                14, 18, // 20248 - 20261 variables -> 52700
                6, 8, // 20262 - 20267 variables -> 52716
                8, 10, // 20268 - 20275 variables -> 52736
                11, 14, // 20276 - 20286 variables -> 52764
                12, 16, // 20287 - 20298 variables -> 52796
                5, 6, // 20299 - 20303 variables -> 52808
                4, 6, // 20304 - 20307 variables -> 52820
                6, 8, // 20308 - 20313 variables -> 52836
                8, 10, // 20314 - 20321 variables -> 52856
                5, 6, // 20322 - 20326 variables -> 52868
                6, 8, // 20327 - 20332 variables -> 52884
                4, 6, // 20333 - 20336 variables -> 52896
                10, 12, // 20337 - 20346 variables -> 52920
                7, 10, // 20347 - 20353 variables -> 52940
                16, 20, // 20354 - 20369 variables -> 52980
                7, 10, // 20370 - 20376 variables -> 53000
                7, 8, // 20377 - 20383 variables -> 53016
                3, 4, // 20384 - 20386 variables -> 53024
                9, 12, // 20387 - 20395 variables -> 53048
                20, 26, // 20396 - 20415 variables -> 53100
                14, 18, // 20416 - 20429 variables -> 53136
                3, 4, // 20430 - 20432 variables -> 53144
                9, 12, // 20433 - 20441 variables -> 53168
                14, 18, // 20442 - 20455 variables -> 53204
                4, 6, // 20456 - 20459 variables -> 53216
                24, 30, // 20460 - 20483 variables -> 53276
                4, 6, // 20484 - 20487 variables -> 53288
                6, 8, // 20488 - 20493 variables -> 53304
                5, 6, // 20494 - 20498 variables -> 53316
                17, 22, // 20499 - 20515 variables -> 53360
                9, 12, // 20516 - 20524 variables -> 53384
                2, 2, // 20525 - 20526 variables -> 53388
                3, 4, // 20527 - 20529 variables -> 53396
                4, 6, // 20530 - 20533 variables -> 53408
                5, 6, // 20534 - 20538 variables -> 53420
                1, 2, // 20539 variables -> 53424
                8, 10, // 20540 - 20547 variables -> 53444
                2, 2, // 20548 - 20549 variables -> 53448
                3, 4, // 20550 - 20552 variables -> 53456
                4, 6, // 20553 - 20556 variables -> 53468
                5, 6, // 20557 - 20561 variables -> 53480
                2, 2, // 20562 - 20563 variables -> 53484
                4, 6, // 20564 - 20567 variables -> 53496
                17, 22, // 20568 - 20584 variables -> 53540
                14, 18, // 20585 - 20598 variables -> 53576
                5, 6, // 20599 - 20603 variables -> 53588
                13, 18, // 20604 - 20616 variables -> 53624
                10, 12, // 20617 - 20626 variables -> 53648
                6, 8, // 20627 - 20632 variables -> 53664
                9, 12, // 20633 - 20641 variables -> 53688
                5, 6, // 20642 - 20646 variables -> 53700
                7, 10, // 20647 - 20653 variables -> 53720
                10, 12, // 20654 - 20663 variables -> 53744
                1, 2, // 20664 variables -> 53748
                12, 16, // 20665 - 20676 variables -> 53780
                2, 2, // 20677 - 20678 variables -> 53784
                8, 10, // 20679 - 20686 variables -> 53804
                1, 2, // 20687 variables -> 53808
                8, 10, // 20688 - 20695 variables -> 53828
                14, 18, // 20696 - 20709 variables -> 53864
                4, 6, // 20710 - 20713 variables -> 53876
                16, 20, // 20714 - 20729 variables -> 53916
                3, 4, // 20730 - 20732 variables -> 53924
                1, 2, // 20733 variables -> 53928
                5, 6, // 20734 - 20738 variables -> 53940
                17, 22, // 20739 - 20755 variables -> 53984
                4, 6, // 20756 - 20759 variables -> 53996
                5, 6, // 20760 - 20764 variables -> 54008
                14, 18, // 20765 - 20778 variables -> 54044
                5, 6, // 20779 - 20783 variables -> 54056
                10, 14, // 20784 - 20793 variables -> 54084
                10, 12, // 20794 - 20803 variables -> 54108
                12, 16, // 20804 - 20815 variables -> 54140
                1, 2, // 20816 variables -> 54144
                5, 6, // 20817 - 20821 variables -> 54156
                5, 6, // 20822 - 20826 variables -> 54168
                3, 4, // 20827 - 20829 variables -> 54176
                10, 14, // 20830 - 20839 variables -> 54204
                10, 12, // 20840 - 20849 variables -> 54228
                3, 4, // 20850 - 20852 variables -> 54236
                1, 2, // 20853 variables -> 54240
                14, 18, // 20854 - 20867 variables -> 54276
                12, 16, // 20868 - 20879 variables -> 54308
                28, 36, // 20880 - 20907 variables -> 54380
                9, 12, // 20908 - 20916 variables -> 54404
                5, 6, // 20917 - 20921 variables -> 54416
                11, 14, // 20922 - 20932 variables -> 54444
                21, 28, // 20933 - 20953 variables -> 54500
                2, 2, // 20954 - 20955 variables -> 54504
                9, 12, // 20956 - 20964 variables -> 54528
                5, 6, // 20965 - 20969 variables -> 54540
                9, 12, // 20970 - 20978 variables -> 54564
                5, 6, // 20979 - 20983 variables -> 54576
                3, 4, // 20984 - 20986 variables -> 54584
                1, 2, // 20987 variables -> 54588
                12, 16, // 20988 - 20999 variables -> 54620
                24, 30, // 21000 - 21023 variables -> 54680
                6, 8, // 21024 - 21029 variables -> 54696
                18, 24, // 21030 - 21047 variables -> 54744
                5, 6, // 21048 - 21052 variables -> 54756
                23, 30, // 21053 - 21075 variables -> 54816
                8, 10, // 21076 - 21083 variables -> 54836
                1, 2, // 21084 variables -> 54840
                14, 18, // 21085 - 21098 variables -> 54876
                3, 4, // 21099 - 21101 variables -> 54884
                5, 6, // 21102 - 21106 variables -> 54896
                9, 12, // 21107 - 21115 variables -> 54920
                6, 8, // 21116 - 21121 variables -> 54936
                17, 22, // 21122 - 21138 variables -> 54980
                1, 2, // 21139 variables -> 54984
                5, 6, // 21140 - 21144 variables -> 54996
                17, 22, // 21145 - 21161 variables -> 55040
                14, 18, // 21162 - 21175 variables -> 55076
                1, 2, // 21176 variables -> 55080
                8, 10, // 21177 - 21184 variables -> 55100
                2, 2, // 21185 - 21186 variables -> 55104
                7, 10, // 21187 - 21193 variables -> 55124
                23, 30, // 21194 - 21216 variables -> 55184
                2, 2, // 21217 - 21218 variables -> 55188
                21, 28, // 21219 - 21239 variables -> 55244
                5, 6, // 21240 - 21244 variables -> 55256
                11, 14, // 21245 - 21255 variables -> 55284
                12, 16, // 21256 - 21267 variables -> 55316
                5, 6, // 21268 - 21272 variables -> 55328
                15, 20, // 21273 - 21287 variables -> 55368
                12, 16, // 21288 - 21299 variables -> 55400
                2, 2, // 21300 - 21301 variables -> 55404
                5, 6, // 21302 - 21306 variables -> 55416
                3, 4, // 21307 - 21309 variables -> 55424
                24, 32, // 21310 - 21333 variables -> 55488
                3, 4, // 21334 - 21336 variables -> 55496
                2, 2, // 21337 - 21338 variables -> 55500
                3, 4, // 21339 - 21341 variables -> 55508
                5, 6, // 21342 - 21346 variables -> 55520
                1, 2, // 21347 variables -> 55524
                9, 12, // 21348 - 21356 variables -> 55548
                3, 4, // 21357 - 21359 variables -> 55556
                5, 6, // 21360 - 21364 variables -> 55568
                5, 6, // 21365 - 21369 variables -> 55580
                9, 12, // 21370 - 21378 variables -> 55604
                1, 2, // 21379 variables -> 55608
                5, 6, // 21380 - 21384 variables -> 55620
                3, 4, // 21385 - 21387 variables -> 55628
                5, 6, // 21388 - 21392 variables -> 55640
                6, 8, // 21393 - 21398 variables -> 55656
                5, 6, // 21399 - 21403 variables -> 55668
                3, 4, // 21404 - 21406 variables -> 55676
                15, 20, // 21407 - 21421 variables -> 55716
                3, 4, // 21422 - 21424 variables -> 55724
                25, 32, // 21425 - 21449 variables -> 55788
                7, 10, // 21450 - 21456 variables -> 55808
                7, 8, // 21457 - 21463 variables -> 55824
                12, 16, // 21464 - 21475 variables -> 55856
                1, 2, // 21476 variables -> 55860
                17, 22, // 21477 - 21493 variables -> 55904
                2, 2, // 21494 - 21495 variables -> 55908
                3, 4, // 21496 - 21498 variables -> 55916
                5, 6, // 21499 - 21503 variables -> 55928
                6, 8, // 21504 - 21509 variables -> 55944
                4, 6, // 21510 - 21513 variables -> 55956
                13, 16, // 21514 - 21526 variables -> 55988
                10, 14, // 21527 - 21536 variables -> 56016
                3, 4, // 21537 - 21539 variables -> 56024
                14, 18, // 21540 - 21553 variables -> 56060
                6, 8, // 21554 - 21559 variables -> 56076
                4, 4, // 21560 - 21563 variables -> 56084
                15, 20, // 21564 - 21578 variables -> 56124
                5, 6, // 21579 - 21583 variables -> 56136
                9, 12, // 21584 - 21592 variables -> 56160
                9, 12, // 21593 - 21601 variables -> 56184
                5, 6, // 21602 - 21606 variables -> 56196
                7, 10, // 21607 - 21613 variables -> 56216
                2, 2, // 21614 - 21615 variables -> 56220
                8, 10, // 21616 - 21623 variables -> 56240
                1, 2, // 21624 variables -> 56244
                9, 12, // 21625 - 21633 variables -> 56268
                22, 28, // 21634 - 21655 variables -> 56324
                9, 12, // 21656 - 21664 variables -> 56348
                14, 18, // 21665 - 21678 variables -> 56384
                1, 2, // 21679 variables -> 56388
                14, 18, // 21680 - 21693 variables -> 56424
                8, 10, // 21694 - 21701 variables -> 56444
                6, 8, // 21702 - 21707 variables -> 56460
                8, 10, // 21708 - 21715 variables -> 56480
                37, 48, // 21716 - 21752 variables -> 56576
                1, 2, // 21753 variables -> 56580
                3, 4, // 21754 - 21756 variables -> 56588
                5, 6, // 21757 - 21761 variables -> 56600
                6, 8, // 21762 - 21767 variables -> 56616
                8, 10, // 21768 - 21775 variables -> 56636
                1, 2, // 21776 variables -> 56640
                8, 10, // 21777 - 21784 variables -> 56660
                23, 30, // 21785 - 21807 variables -> 56720
                2, 2, // 21808 - 21809 variables -> 56724
                27, 36, // 21810 - 21836 variables -> 56796
                5, 6, // 21837 - 21841 variables -> 56808
                8, 10, // 21842 - 21849 variables -> 56828
                4, 6, // 21850 - 21853 variables -> 56840
                2, 2, // 21854 - 21855 variables -> 56844
                14, 18, // 21856 - 21869 variables -> 56880
                3, 4, // 21870 - 21872 variables -> 56888
                4, 6, // 21873 - 21876 variables -> 56900
                7, 8, // 21877 - 21883 variables -> 56916
                12, 16, // 21884 - 21895 variables -> 56948
                11, 14, // 21896 - 21906 variables -> 56976
                12, 16, // 21907 - 21918 variables -> 57008
                5, 6, // 21919 - 21923 variables -> 57020
                10, 14, // 21924 - 21933 variables -> 57048
                3, 4, // 21934 - 21936 variables -> 57056
                16, 20, // 21937 - 21952 variables -> 57096
                3, 4, // 21953 - 21955 variables -> 57104
                4, 6, // 21956 - 21959 variables -> 57116
                2, 2, // 21960 - 21961 variables -> 57120
                8, 10, // 21962 - 21969 variables -> 57140
                9, 12, // 21970 - 21978 variables -> 57164
                1, 2, // 21979 variables -> 57168
                5, 6, // 21980 - 21984 variables -> 57180
                9, 12, // 21985 - 21993 variables -> 57204
                5, 6, // 21994 - 21998 variables -> 57216
                5, 6, // 21999 - 22003 variables -> 57228
                3, 4, // 22004 - 22006 variables -> 57236
                9, 12, // 22007 - 22015 variables -> 57260
                1, 2, // 22016 variables -> 57264
                5, 6, // 22017 - 22021 variables -> 57276
                3, 4, // 22022 - 22024 variables -> 57284
                9, 12, // 22025 - 22033 variables -> 57308
                5, 6, // 22034 - 22038 variables -> 57320
                6, 8, // 22039 - 22044 variables -> 57336
                3, 4, // 22045 - 22047 variables -> 57344
                2, 2, // 22048 - 22049 variables -> 57348
                4, 6, // 22050 - 22053 variables -> 57360
                14, 18, // 22054 - 22067 variables -> 57396
                8, 10, // 22068 - 22075 variables -> 57416
                4, 6, // 22076 - 22079 variables -> 57428
                7, 8, // 22080 - 22086 variables -> 57444
                9, 12, // 22087 - 22095 variables -> 57468
                4, 6, // 22096 - 22099 variables -> 57480
                17, 22, // 22100 - 22116 variables -> 57524
                2, 2, // 22117 - 22118 variables -> 57528
                5, 6, // 22119 - 22123 variables -> 57540
                9, 12, // 22124 - 22132 variables -> 57564
                14, 18, // 22133 - 22146 variables -> 57600
                3, 4, // 22147 - 22149 variables -> 57608
                10, 14, // 22150 - 22159 variables -> 57636
                5, 6, // 22160 - 22164 variables -> 57648
                3, 4, // 22165 - 22167 variables -> 57656
                16, 20, // 22168 - 22183 variables -> 57696
                4, 6, // 22184 - 22187 variables -> 57708
                12, 16, // 22188 - 22199 variables -> 57740
                7, 8, // 22200 - 22206 variables -> 57756
                3, 4, // 22207 - 22209 variables -> 57764
                6, 8, // 22210 - 22215 variables -> 57780
                17, 22, // 22216 - 22232 variables -> 57824
                6, 8, // 22233 - 22238 variables -> 57840
                9, 12, // 22239 - 22247 variables -> 57864
                5, 6, // 22248 - 22252 variables -> 57876
                4, 6, // 22253 - 22256 variables -> 57888
                13, 16, // 22257 - 22269 variables -> 57920
                9, 12, // 22270 - 22278 variables -> 57944
                14, 18, // 22279 - 22292 variables -> 57980
                23, 30, // 22293 - 22315 variables -> 58040
                6, 8, // 22316 - 22321 variables -> 58056
                3, 4, // 22322 - 22324 variables -> 58064
                2, 2, // 22325 - 22326 variables -> 58068
                3, 4, // 22327 - 22329 variables -> 58076
                4, 6, // 22330 - 22333 variables -> 58088
                20, 26, // 22334 - 22353 variables -> 58140
                3, 4, // 22354 - 22356 variables -> 58148
                11, 14, // 22357 - 22367 variables -> 58176
                19, 24, // 22368 - 22386 variables -> 58224
                17, 22, // 22387 - 22403 variables -> 58268
                4, 6, // 22404 - 22407 variables -> 58280
                2, 2, // 22408 - 22409 variables -> 58284
                4, 6, // 22410 - 22413 variables -> 58296
                8, 10, // 22414 - 22421 variables -> 58316
                5, 6, // 22422 - 22426 variables -> 58328
                10, 14, // 22427 - 22436 variables -> 58356
                5, 6, // 22437 - 22441 variables -> 58368
                5, 6, // 22442 - 22446 variables -> 58380
                9, 12, // 22447 - 22455 variables -> 58404
                8, 10, // 22456 - 22463 variables -> 58424
                4, 6, // 22464 - 22467 variables -> 58436
                2, 2, // 22468 - 22469 variables -> 58440
                9, 12, // 22470 - 22478 variables -> 58464
                8, 10, // 22479 - 22486 variables -> 58484
                9, 12, // 22487 - 22495 variables -> 58508
                6, 8, // 22496 - 22501 variables -> 58524
                14, 18, // 22502 - 22515 variables -> 58560
                14, 18, // 22516 - 22529 variables -> 58596
                7, 10, // 22530 - 22536 variables -> 58616
                5, 6, // 22537 - 22541 variables -> 58628
                6, 8, // 22542 - 22547 variables -> 58644
                12, 16, // 22548 - 22559 variables -> 58676
                5, 6, // 22560 - 22564 variables -> 58688
                5, 6, // 22565 - 22569 variables -> 58700
                6, 8, // 22570 - 22575 variables -> 58716
                12, 16, // 22576 - 22587 variables -> 58748
                16, 20, // 22588 - 22603 variables -> 58788
                3, 4, // 22604 - 22606 variables -> 58796
                1, 2, // 22607 variables -> 58800
                8, 10, // 22608 - 22615 variables -> 58820
                1, 2, // 22616 variables -> 58824
                8, 10, // 22617 - 22624 variables -> 58844
                9, 12, // 22625 - 22633 variables -> 58868
                5, 6, // 22634 - 22638 variables -> 58880
                6, 8, // 22639 - 22644 variables -> 58896
                5, 6, // 22645 - 22649 variables -> 58908
                7, 10, // 22650 - 22656 variables -> 58928
                16, 20, // 22657 - 22672 variables -> 58968
                7, 10, // 22673 - 22679 variables -> 58988
                14, 18, // 22680 - 22693 variables -> 59024
                20, 26, // 22694 - 22713 variables -> 59076
                3, 4, // 22714 - 22716 variables -> 59084
                5, 6, // 22717 - 22721 variables -> 59096
                23, 30, // 22722 - 22744 variables -> 59156
                2, 2, // 22745 - 22746 variables -> 59160
                3, 4, // 22747 - 22749 variables -> 59168
                6, 8, // 22750 - 22755 variables -> 59184
                4, 6, // 22756 - 22759 variables -> 59196
                10, 12, // 22760 - 22769 variables -> 59220
                9, 12, // 22770 - 22778 variables -> 59244
                14, 18, // 22779 - 22792 variables -> 59280
                3, 4, // 22793 - 22795 variables -> 59288
                6, 8, // 22796 - 22801 variables -> 59304
                17, 22, // 22802 - 22818 variables -> 59348
                5, 6, // 22819 - 22823 variables -> 59360
                1, 2, // 22824 variables -> 59364
                9, 12, // 22825 - 22833 variables -> 59388
                26, 34, // 22834 - 22859 variables -> 59456
                5, 6, // 22860 - 22864 variables -> 59468
                14, 18, // 22865 - 22878 variables -> 59504
                9, 12, // 22879 - 22887 variables -> 59528
                5, 6, // 22888 - 22892 variables -> 59540
                1, 2, // 22893 variables -> 59544
                22, 28, // 22894 - 22915 variables -> 59600
                11, 14, // 22916 - 22926 variables -> 59628
                12, 16, // 22927 - 22938 variables -> 59660
                11, 14, // 22939 - 22949 variables -> 59688
                3, 4, // 22950 - 22952 variables -> 59696
                11, 14, // 22953 - 22963 variables -> 59724
                9, 12, // 22964 - 22972 variables -> 59748
                3, 4, // 22973 - 22975 variables -> 59756
                4, 6, // 22976 - 22979 variables -> 59768
                5, 6, // 22980 - 22984 variables -> 59780
                2, 2, // 22985 - 22986 variables -> 59784
                27, 36, // 22987 - 23013 variables -> 59856
                3, 4, // 23014 - 23016 variables -> 59864
                5, 6, // 23017 - 23021 variables -> 59876
                15, 20, // 23022 - 23036 variables -> 59916
                10, 12, // 23037 - 23046 variables -> 59940
                18, 24, // 23047 - 23064 variables -> 59988
                5, 6, // 23065 - 23069 variables -> 60000
                17, 22, // 23070 - 23086 variables -> 60044
                1, 2, // 23087 variables -> 60048
                12, 16, // 23088 - 23099 variables -> 60080
                14, 18, // 23100 - 23113 variables -> 60116
                10, 12, // 23114 - 23123 variables -> 60140
                9, 12, // 23124 - 23132 variables -> 60164
                14, 18, // 23133 - 23146 variables -> 60200
                1, 2, // 23147 variables -> 60204
                5, 6, // 23148 - 23152 variables -> 60216
                4, 6, // 23153 - 23156 variables -> 60228
                5, 6, // 23157 - 23161 variables -> 60240
                3, 4, // 23162 - 23164 variables -> 60248
                5, 6, // 23165 - 23169 variables -> 60260
                10, 14, // 23170 - 23179 variables -> 60288
                4, 4, // 23180 - 23183 variables -> 60296
                1, 2, // 23184 variables -> 60300
                17, 22, // 23185 - 23201 variables -> 60344
                6, 8, // 23202 - 23207 variables -> 60360
                9, 12, // 23208 - 23216 variables -> 60384
                5, 6, // 23217 - 23221 variables -> 60396
                8, 10, // 23222 - 23229 variables -> 60416
                4, 6, // 23230 - 23233 variables -> 60428
                6, 8, // 23234 - 23239 variables -> 60444
                10, 12, // 23240 - 23249 variables -> 60468
                14, 18, // 23250 - 23263 variables -> 60504
                9, 12, // 23264 - 23272 variables -> 60528
                4, 6, // 23273 - 23276 variables -> 60540
                8, 10, // 23277 - 23284 variables -> 60560
                2, 2, // 23285 - 23286 variables -> 60564
                17, 22, // 23287 - 23303 variables -> 60608
                10, 14, // 23304 - 23313 variables -> 60636
                5, 6, // 23314 - 23318 variables -> 60648
                5, 6, // 23319 - 23323 variables -> 60660
                3, 4, // 23324 - 23326 variables -> 60668
                13, 18, // 23327 - 23339 variables -> 60704
                5, 6, // 23340 - 23344 variables -> 60716
                15, 20, // 23345 - 23359 variables -> 60756
                17, 22, // 23360 - 23376 variables -> 60800
                2, 2, // 23377 - 23378 variables -> 60804
                9, 12, // 23379 - 23387 variables -> 60828
                19, 24, // 23388 - 23406 variables -> 60876
                3, 4, // 23407 - 23409 variables -> 60884
                14, 18, // 23410 - 23423 variables -> 60920
                13, 18, // 23424 - 23436 variables -> 60956
                2, 2, // 23437 - 23438 variables -> 60960
                17, 22, // 23439 - 23455 variables -> 61004
                1, 2, // 23456 variables -> 61008
                3, 4, // 23457 - 23459 variables -> 61016
                10, 12, // 23460 - 23469 variables -> 61040
                6, 8, // 23470 - 23475 variables -> 61056
                9, 12, // 23476 - 23484 variables -> 61080
                8, 10, // 23485 - 23492 variables -> 61100
                11, 14, // 23493 - 23503 variables -> 61128
                3, 4, // 23504 - 23506 variables -> 61136
                1, 2, // 23507 variables -> 61140
                14, 18, // 23508 - 23521 variables -> 61176
                12, 16, // 23522 - 23533 variables -> 61208
                30, 38, // 23534 - 23563 variables -> 61284
                4, 6, // 23564 - 23567 variables -> 61296
                5, 6, // 23568 - 23572 variables -> 61308
                4, 6, // 23573 - 23576 variables -> 61320
                10, 12, // 23577 - 23586 variables -> 61344
                7, 10, // 23587 - 23593 variables -> 61364
                5, 6, // 23594 - 23598 variables -> 61376
                9, 12, // 23599 - 23607 variables -> 61400
                6, 8, // 23608 - 23613 variables -> 61416
                5, 6, // 23614 - 23618 variables -> 61428
                3, 4, // 23619 - 23621 variables -> 61436
                5, 6, // 23622 - 23626 variables -> 61448
                10, 14, // 23627 - 23636 variables -> 61476
                23, 30, // 23637 - 23659 variables -> 61536
                5, 6, // 23660 - 23664 variables -> 61548
                8, 10, // 23665 - 23672 variables -> 61568
                6, 8, // 23673 - 23678 variables -> 61584
                17, 22, // 23679 - 23695 variables -> 61628
                4, 6, // 23696 - 23699 variables -> 61640
                7, 8, // 23700 - 23706 variables -> 61656
                9, 12, // 23707 - 23715 variables -> 61680
                8, 10, // 23716 - 23723 variables -> 61700
                1, 2, // 23724 variables -> 61704
                8, 10, // 23725 - 23732 variables -> 61724
                1, 2, // 23733 variables -> 61728
                5, 6, // 23734 - 23738 variables -> 61740
                8, 10, // 23739 - 23746 variables -> 61760
                1, 2, // 23747 variables -> 61764
                8, 10, // 23748 - 23755 variables -> 61784
                9, 12, // 23756 - 23764 variables -> 61808
                14, 18, // 23765 - 23778 variables -> 61844
                15, 20, // 23779 - 23793 variables -> 61884
                5, 6, // 23794 - 23798 variables -> 61896
                3, 4, // 23799 - 23801 variables -> 61904
                6, 8, // 23802 - 23807 variables -> 61920
                17, 22, // 23808 - 23824 variables -> 61964
                5, 6, // 23825 - 23829 variables -> 61976
                4, 6, // 23830 - 23833 variables -> 61988
                23, 30, // 23834 - 23856 variables -> 62048
                5, 6, // 23857 - 23861 variables -> 62060
                11, 14, // 23862 - 23872 variables -> 62088
                4, 6, // 23873 - 23876 variables -> 62100
                10, 12, // 23877 - 23886 variables -> 62124
                9, 12, // 23887 - 23895 variables -> 62148
                4, 6, // 23896 - 23899 variables -> 62160
                8, 10, // 23900 - 23907 variables -> 62180
                2, 2, // 23908 - 23909 variables -> 62184
                7, 10, // 23910 - 23916 variables -> 62204
                23, 30, // 23917 - 23939 variables -> 62264
                2, 2, // 23940 - 23941 variables -> 62268
                12, 16, // 23942 - 23953 variables -> 62300
                6, 8, // 23954 - 23959 variables -> 62316
                4, 4, // 23960 - 23963 variables -> 62324
                1, 2, // 23964 variables -> 62328
                5, 6, // 23965 - 23969 variables -> 62340
                14, 18, // 23970 - 23983 variables -> 62376
                3, 4, // 23984 - 23986 variables -> 62384
                1, 2, // 23987 variables -> 62388
                5, 6, // 23988 - 23992 variables -> 62400
                3, 4, // 23993 - 23995 variables -> 62408
                20, 26, // 23996 - 24015 variables -> 62460
                3, 4, // 24016 - 24018 variables -> 62468
                6, 8, // 24019 - 24024 variables -> 62484
                5, 6, // 24025 - 24029 variables -> 62496
                7, 10, // 24030 - 24036 variables -> 62516
                2, 2, // 24037 - 24038 variables -> 62520
                3, 4, // 24039 - 24041 variables -> 62528
                5, 6, // 24042 - 24046 variables -> 62540
                6, 8, // 24047 - 24052 variables -> 62556
                3, 4, // 24053 - 24055 variables -> 62564
                4, 6, // 24056 - 24059 variables -> 62576
                24, 30, // 24060 - 24083 variables -> 62636
                9, 12, // 24084 - 24092 variables -> 62660
                1, 2, // 24093 variables -> 62664
                5, 6, // 24094 - 24098 variables -> 62676
                5, 6, // 24099 - 24103 variables -> 62688
                3, 4, // 24104 - 24106 variables -> 62696
                15, 20, // 24107 - 24121 variables -> 62736
                17, 22, // 24122 - 24138 variables -> 62780
                6, 8, // 24139 - 24144 variables -> 62796
                3, 4, // 24145 - 24147 variables -> 62804
                2, 2, // 24148 - 24149 variables -> 62808
                3, 4, // 24150 - 24152 variables -> 62816
                55, 72, // 24153 - 24207 variables -> 62960
                6, 8, // 24208 - 24213 variables -> 62976
                3, 4, // 24214 - 24216 variables -> 62984
                7, 8, // 24217 - 24223 variables -> 63000
                16, 22, // 24224 - 24239 variables -> 63044
                2, 2, // 24240 - 24241 variables -> 63048
                3, 4, // 24242 - 24244 variables -> 63056
                11, 14, // 24245 - 24255 variables -> 63084
                8, 10, // 24256 - 24263 variables -> 63104
                1, 2, // 24264 variables -> 63108
                3, 4, // 24265 - 24267 variables -> 63116
                16, 20, // 24268 - 24283 variables -> 63156
                4, 6, // 24284 - 24287 variables -> 63168
                8, 10, // 24288 - 24295 variables -> 63188
                14, 18, // 24296 - 24309 variables -> 63224
                4, 6, // 24310 - 24313 variables -> 63236
                16, 20, // 24314 - 24329 variables -> 63276
                12, 16, // 24330 - 24341 variables -> 63308
                5, 6, // 24342 - 24346 variables -> 63320
                6, 8, // 24347 - 24352 variables -> 63336
                4, 6, // 24353 - 24356 variables -> 63348
                3, 4, // 24357 - 24359 variables -> 63356
                16, 20, // 24360 - 24375 variables -> 63396
                9, 12, // 24376 - 24384 variables -> 63420
                17, 22, // 24385 - 24401 variables -> 63464
                2, 2, // 24402 - 24403 variables -> 63468
                3, 4, // 24404 - 24406 variables -> 63476
                1, 2, // 24407 variables -> 63480
                9, 12, // 24408 - 24416 variables -> 63504
                8, 10, // 24417 - 24424 variables -> 63524
                14, 18, // 24425 - 24438 variables -> 63560
                1, 2, // 24439 variables -> 63564
                17, 22, // 24440 - 24456 variables -> 63608
                5, 6, // 24457 - 24461 variables -> 63620
                14, 18, // 24462 - 24475 variables -> 63656
                23, 30, // 24476 - 24498 variables -> 63716
                1, 2, // 24499 variables -> 63720
                8, 10, // 24500 - 24507 variables -> 63740
                11, 14, // 24508 - 24518 variables -> 63768
                8, 10, // 24519 - 24526 variables -> 63788
                6, 8, // 24527 - 24532 variables -> 63804
                12, 16, // 24533 - 24544 variables -> 63836
                39, 50, // 24545 - 24583 variables -> 63936
                4, 6, // 24584 - 24587 variables -> 63948
                8, 10, // 24588 - 24595 variables -> 63968
                6, 8, // 24596 - 24601 variables -> 63984
                8, 10, // 24602 - 24609 variables -> 64004
                9, 12, // 24610 - 24618 variables -> 64028
                5, 6, // 24619 - 24623 variables -> 64040
                13, 18, // 24624 - 24636 variables -> 64076
                2, 2, // 24637 - 24638 variables -> 64080
                17, 22, // 24639 - 24655 variables -> 64124
                4, 6, // 24656 - 24659 variables -> 64136
                2, 2, // 24660 - 24661 variables -> 64140
                3, 4, // 24662 - 24664 variables -> 64148
                5, 6, // 24665 - 24669 variables -> 64160
                6, 8, // 24670 - 24675 variables -> 64176
                4, 6, // 24676 - 24679 variables -> 64188
                5, 6, // 24680 - 24684 variables -> 64200
                8, 10, // 24685 - 24692 variables -> 64220
                14, 18, // 24693 - 24706 variables -> 64256
                1, 2, // 24707 variables -> 64260
                17, 22, // 24708 - 24724 variables -> 64304
                2, 2, // 24725 - 24726 variables -> 64308
                12, 16, // 24727 - 24738 variables -> 64340
                11, 14, // 24739 - 24749 variables -> 64368
                7, 10, // 24750 - 24756 variables -> 64388
                5, 6, // 24757 - 24761 variables -> 64400
                2, 2, // 24762 - 24763 variables -> 64404
                9, 12, // 24764 - 24772 variables -> 64428
                7, 10, // 24773 - 24779 variables -> 64448
                16, 20, // 24780 - 24795 variables -> 64488
                3, 4, // 24796 - 24798 variables -> 64496
                11, 14, // 24799 - 24809 variables -> 64524
                4, 6, // 24810 - 24813 variables -> 64536
                3, 4, // 24814 - 24816 variables -> 64544
                28, 36, // 24817 - 24844 variables -> 64616
                2, 2, // 24845 - 24846 variables -> 64620
                3, 4, // 24847 - 24849 variables -> 64628
                4, 6, // 24850 - 24853 variables -> 64640
                10, 12, // 24854 - 24863 variables -> 64664
                1, 2, // 24864 variables -> 64668
                3, 4, // 24865 - 24867 variables -> 64676
                11, 14, // 24868 - 24878 variables -> 64704
                9, 12, // 24879 - 24887 variables -> 64728
                5, 6, // 24888 - 24892 variables -> 64740
                3, 4, // 24893 - 24895 variables -> 64748
                4, 6, // 24896 - 24899 variables -> 64760
                2, 2, // 24900 - 24901 variables -> 64764
                5, 6, // 24902 - 24906 variables -> 64776
                3, 4, // 24907 - 24909 variables -> 64784
                15, 20, // 24910 - 24924 variables -> 64824
                8, 10, // 24925 - 24932 variables -> 64844
                1, 2, // 24933 variables -> 64848
                8, 10, // 24934 - 24941 variables -> 64868
                5, 6, // 24942 - 24946 variables -> 64880
                9, 12, // 24947 - 24955 variables -> 64904
                1, 2, // 24956 variables -> 64908
                19, 24, // 24957 - 24975 variables -> 64956
                9, 12, // 24976 - 24984 variables -> 64980
                9, 12, // 24985 - 24993 variables -> 65004
                5, 6, // 24994 - 24998 variables -> 65016
                5, 6, // 24999 - 25003 variables -> 65028
                3, 4, // 25004 - 25006 variables -> 65036
                18, 24, // 25007 - 25024 variables -> 65084
                2, 2, // 25025 - 25026 variables -> 65088
                3, 4, // 25027 - 25029 variables -> 65096
                18, 24, // 25030 - 25047 variables -> 65144
                2, 2, // 25048 - 25049 variables -> 65148
                4, 6, // 25050 - 25053 variables -> 65160
                3, 4, // 25054 - 25056 variables -> 65168
                5, 6, // 25057 - 25061 variables -> 65180
                6, 8, // 25062 - 25067 variables -> 65196
                12, 16, // 25068 - 25079 variables -> 65228
                5, 6, // 25080 - 25084 variables -> 65240
                2, 2, // 25085 - 25086 variables -> 65244
                7, 10, // 25087 - 25093 variables -> 65264
                10, 12, // 25094 - 25103 variables -> 65288
                10, 14, // 25104 - 25113 variables -> 65316
                5, 6, // 25114 - 25118 variables -> 65328
                26, 34, // 25119 - 25144 variables -> 65396
                5, 6, // 25145 - 25149 variables -> 65408
                10, 14, // 25150 - 25159 variables -> 65436
                5, 6, // 25160 - 25164 variables -> 65448
                3, 4, // 25165 - 25167 variables -> 65456
                2, 2, // 25168 - 25169 variables -> 65460
                23, 30, // 25170 - 25192 variables -> 65520
                17, 22, // 25193 - 25209 variables -> 65564
                6, 8, // 25210 - 25215 variables -> 65580
                3, 4, // 25216 - 25218 variables -> 65588
                5, 6, // 25219 - 25223 variables -> 65600
                6, 8, // 25224 - 25229 variables -> 65616
                3, 4, // 25230 - 25232 variables -> 65624
                1, 2, // 25233 variables -> 65628
                22, 28, // 25234 - 25255 variables -> 65684
                1, 2, // 25256 variables -> 65688
                5, 6, // 25257 - 25261 variables -> 65700
                3, 4, // 25262 - 25264 variables -> 65708
                20, 26, // 25265 - 25284 variables -> 65760
                14, 18, // 25285 - 25298 variables -> 65796
                17, 22, // 25299 - 25315 variables -> 65840
                1, 2, // 25316 variables -> 65844
                5, 6, // 25317 - 25321 variables -> 65856
                12, 16, // 25322 - 25333 variables -> 65888
                5, 6, // 25334 - 25338 variables -> 65900
                1, 2, // 25339 variables -> 65904
                13, 16, // 25340 - 25352 variables -> 65936
                9, 12, // 25353 - 25361 variables -> 65960
                2, 2, // 25362 - 25363 variables -> 65964
                9, 12, // 25364 - 25372 variables -> 65988
                3, 4, // 25373 - 25375 variables -> 65996
                4, 6, // 25376 - 25379 variables -> 66008
                5, 6, // 25380 - 25384 variables -> 66020
                11, 14, // 25385 - 25395 variables -> 66048
                8, 10, // 25396 - 25403 variables -> 66068
                4, 6, // 25404 - 25407 variables -> 66080
                6, 8, // 25408 - 25413 variables -> 66096
                10, 12, // 25414 - 25423 variables -> 66120
                3, 4, // 25424 - 25426 variables -> 66128
                13, 18, // 25427 - 25439 variables -> 66164
                2, 2, // 25440 - 25441 variables -> 66168
                8, 10, // 25442 - 25449 variables -> 66188
                6, 8, // 25450 - 25455 variables -> 66204
                12, 16, // 25456 - 25467 variables -> 66236
                5, 6, // 25468 - 25472 variables -> 66248
                4, 6, // 25473 - 25476 variables -> 66260
                23, 30, // 25477 - 25499 variables -> 66320
                2, 2, // 25500 - 25501 variables -> 66324
                8, 10, // 25502 - 25509 variables -> 66344
                14, 18, // 25510 - 25523 variables -> 66380
                1, 2, // 25524 variables -> 66384
                8, 10, // 25525 - 25532 variables -> 66404
                6, 8, // 25533 - 25538 variables -> 66420
                3, 4, // 25539 - 25541 variables -> 66428
                6, 8, // 25542 - 25547 variables -> 66444
                9, 12, // 25548 - 25556 variables -> 66468
                19, 24, // 25557 - 25575 variables -> 66516
                31, 40, // 25576 - 25606 variables -> 66596
                1, 2, // 25607 variables -> 66600
                9, 12, // 25608 - 25616 variables -> 66624
                8, 10, // 25617 - 25624 variables -> 66644
                5, 6, // 25625 - 25629 variables -> 66656
                9, 12, // 25630 - 25638 variables -> 66680
                1, 2, // 25639 variables -> 66684
                10, 12, // 25640 - 25649 variables -> 66708
                3, 4, // 25650 - 25652 variables -> 66716
                1, 2, // 25653 variables -> 66720
                3, 4, // 25654 - 25656 variables -> 66728
                5, 6, // 25657 - 25661 variables -> 66740
                14, 18, // 25662 - 25675 variables -> 66776
                11, 14, // 25676 - 25686 variables -> 66804
                9, 12, // 25687 - 25695 variables -> 66828
                4, 6, // 25696 - 25699 variables -> 66840
                4, 4, // 25700 - 25703 variables -> 66848
                10, 14, // 25704 - 25713 variables -> 66876
                23, 30, // 25714 - 25736 variables -> 66936
                3, 4, // 25737 - 25739 variables -> 66944
                7, 8, // 25740 - 25746 variables -> 66960
                7, 10, // 25747 - 25753 variables -> 66980
                6, 8, // 25754 - 25759 variables -> 66996
                5, 6, // 25760 - 25764 variables -> 67008
                8, 10, // 25765 - 25772 variables -> 67028
                14, 18, // 25773 - 25786 variables -> 67064
                6, 8, // 25787 - 25792 variables -> 67080
                3, 4, // 25793 - 25795 variables -> 67088
                11, 14, // 25796 - 25806 variables -> 67116
                12, 16, // 25807 - 25818 variables -> 67148
                5, 6, // 25819 - 25823 variables -> 67160
                6, 8, // 25824 - 25829 variables -> 67176
                3, 4, // 25830 - 25832 variables -> 67184
                4, 6, // 25833 - 25836 variables -> 67196
                2, 2, // 25837 - 25838 variables -> 67200
                8, 10, // 25839 - 25846 variables -> 67220
                1, 2, // 25847 variables -> 67224
                9, 12, // 25848 - 25856 variables -> 67248
                3, 4, // 25857 - 25859 variables -> 67256
                2, 2, // 25860 - 25861 variables -> 67260
                3, 4, // 25862 - 25864 variables -> 67268
                5, 6, // 25865 - 25869 variables -> 67280
                6, 8, // 25870 - 25875 variables -> 67296
                3, 4, // 25876 - 25878 variables -> 67304
                5, 6, // 25879 - 25883 variables -> 67316
                24, 32, // 25884 - 25907 variables -> 67380
                19, 24, // 25908 - 25926 variables -> 67428
                7, 10, // 25927 - 25933 variables -> 67448
                6, 8, // 25934 - 25939 variables -> 67464
                14, 18, // 25940 - 25953 variables -> 67500
                8, 10, // 25954 - 25961 variables -> 67520
                2, 2, // 25962 - 25963 variables -> 67524
                4, 6, // 25964 - 25967 variables -> 67536
                8, 10, // 25968 - 25975 variables -> 67556
                1, 2, // 25976 variables -> 67560
                3, 4, // 25977 - 25979 variables -> 67568
                14, 18, // 25980 - 25993 variables -> 67604
                5, 6, // 25994 - 25998 variables -> 67616
                9, 12, // 25999 - 26007 variables -> 67640
                2, 2, // 26008 - 26009 variables -> 67644
                12, 16, // 26010 - 26021 variables -> 67676
                2, 2, // 26022 - 26023 variables -> 67680
                16, 22, // 26024 - 26039 variables -> 67724
                5, 6, // 26040 - 26044 variables -> 67736
                5, 6, // 26045 - 26049 variables -> 67748
                6, 8, // 26050 - 26055 variables -> 67764
                14, 18, // 26056 - 26069 variables -> 67800
                3, 4, // 26070 - 26072 variables -> 67808
                14, 18, // 26073 - 26086 variables -> 67844
                9, 12, // 26087 - 26095 variables -> 67868
                6, 8, // 26096 - 26101 variables -> 67884
                5, 6, // 26102 - 26106 variables -> 67896
                3, 4, // 26107 - 26109 variables -> 67904
                15, 20, // 26110 - 26124 variables -> 67944
                5, 6, // 26125 - 26129 variables -> 67956
                23, 30, // 26130 - 26152 variables -> 68016
                17, 22, // 26153 - 26169 variables -> 68060
                9, 12, // 26170 - 26178 variables -> 68084
                1, 2, // 26179 variables -> 68088
                5, 6, // 26180 - 26184 variables -> 68100
                14, 18, // 26185 - 26198 variables -> 68136
                3, 4, // 26199 - 26201 variables -> 68144
                48, 62, // 26202 - 26249 variables -> 68268
                3, 4, // 26250 - 26252 variables -> 68276
                1, 2, // 26253 variables -> 68280
                10, 12, // 26254 - 26263 variables -> 68304
                4, 6, // 26264 - 26267 variables -> 68316
                8, 10, // 26268 - 26275 variables -> 68336
                1, 2, // 26276 variables -> 68340
                10, 12, // 26277 - 26286 variables -> 68364
                9, 12, // 26287 - 26295 variables -> 68388
                21, 28, // 26296 - 26316 variables -> 68444
                2, 2, // 26317 - 26318 variables -> 68448
                3, 4, // 26319 - 26321 variables -> 68456
                11, 14, // 26322 - 26332 variables -> 68484
                17, 22, // 26333 - 26349 variables -> 68528
                4, 6, // 26350 - 26353 variables -> 68540
                2, 2, // 26354 - 26355 variables -> 68544
                4, 6, // 26356 - 26359 variables -> 68556
                5, 6, // 26360 - 26364 variables -> 68568
                8, 10, // 26365 - 26372 variables -> 68588
                11, 14, // 26373 - 26383 variables -> 68616
                3, 4, // 26384 - 26386 variables -> 68624
                1, 2, // 26387 variables -> 68628
                8, 10, // 26388 - 26395 variables -> 68648
                4, 6, // 26396 - 26399 variables -> 68660
                7, 8, // 26400 - 26406 variables -> 68676
                7, 10, // 26407 - 26413 variables -> 68696
                11, 14, // 26414 - 26424 variables -> 68724
                8, 10, // 26425 - 26432 variables -> 68744
                4, 6, // 26433 - 26436 variables -> 68756
                2, 2, // 26437 - 26438 variables -> 68760
                9, 12, // 26439 - 26447 variables -> 68784
                17, 22, // 26448 - 26464 variables -> 68828
                14, 18, // 26465 - 26478 variables -> 68864
                6, 8, // 26479 - 26484 variables -> 68880
                8, 10, // 26485 - 26492 variables -> 68900
                14, 18, // 26493 - 26506 variables -> 68936
                9, 12, // 26507 - 26515 variables -> 68960
                1, 2, // 26516 variables -> 68964
                10, 12, // 26517 - 26526 variables -> 68988
                3, 4, // 26527 - 26529 variables -> 68996
                9, 12, // 26530 - 26538 variables -> 69020
                1, 2, // 26539 variables -> 69024
                8, 10, // 26540 - 26547 variables -> 69044
                2, 2, // 26548 - 26549 variables -> 69048
                4, 6, // 26550 - 26553 variables -> 69060
                14, 18, // 26554 - 26567 variables -> 69096
                5, 6, // 26568 - 26572 variables -> 69108
                4, 6, // 26573 - 26576 variables -> 69120
                27, 34, // 26577 - 26603 variables -> 69188
                4, 6, // 26604 - 26607 variables -> 69200
                2, 2, // 26608 - 26609 variables -> 69204
                9, 12, // 26610 - 26618 variables -> 69228
                3, 4, // 26619 - 26621 variables -> 69236
                5, 6, // 26622 - 26626 variables -> 69248
                13, 18, // 26627 - 26639 variables -> 69284
                14, 18, // 26640 - 26653 variables -> 69320
                2, 2, // 26654 - 26655 variables -> 69324
                12, 16, // 26656 - 26667 variables -> 69356
                5, 6, // 26668 - 26672 variables -> 69368
                4, 6, // 26673 - 26676 variables -> 69380
                7, 8, // 26677 - 26683 variables -> 69396
                4, 6, // 26684 - 26687 variables -> 69408
                8, 10, // 26688 - 26695 variables -> 69428
                14, 18, // 26696 - 26709 variables -> 69464
                6, 8, // 26710 - 26715 variables -> 69480
                8, 10, // 26716 - 26723 variables -> 69500
                6, 8, // 26724 - 26729 variables -> 69516
                7, 10, // 26730 - 26736 variables -> 69536
                2, 2, // 26737 - 26738 variables -> 69540
                3, 4, // 26739 - 26741 variables -> 69548
                14, 18, // 26742 - 26755 variables -> 69584
                20, 26, // 26756 - 26775 variables -> 69636
                9, 12, // 26776 - 26784 variables -> 69660
                17, 22, // 26785 - 26801 variables -> 69704
                2, 2, // 26802 - 26803 variables -> 69708
                3, 4, // 26804 - 26806 variables -> 69716
                1, 2, // 26807 variables -> 69720
                17, 22, // 26808 - 26824 variables -> 69764
                5, 6, // 26825 - 26829 variables -> 69776
                4, 6, // 26830 - 26833 variables -> 69788
                11, 14, // 26834 - 26844 variables -> 69816
                12, 16, // 26845 - 26856 variables -> 69848
                5, 6, // 26857 - 26861 variables -> 69860
                15, 20, // 26862 - 26876 variables -> 69900
                8, 10, // 26877 - 26884 variables -> 69920
                9, 12, // 26885 - 26893 variables -> 69944
                2, 2, // 26894 - 26895 variables -> 69948
                14, 18, // 26896 - 26909 variables -> 69984
                32, 42, // 26910 - 26941 variables -> 70068
                3, 4, // 26942 - 26944 variables -> 70076
                19, 24, // 26945 - 26963 variables -> 70124
                1, 2, // 26964 variables -> 70128
                5, 6, // 26965 - 26969 variables -> 70140
                7, 10, // 26970 - 26976 variables -> 70160
                10, 12, // 26977 - 26986 variables -> 70184
                1, 2, // 26987 variables -> 70188
                5, 6, // 26988 - 26992 variables -> 70200
                7, 10, // 26993 - 26999 variables -> 70220
                7, 8, // 27000 - 27006 variables -> 70236
                3, 4, // 27007 - 27009 variables -> 70244
                4, 6, // 27010 - 27013 variables -> 70256
                10, 12, // 27014 - 27023 variables -> 70280
                9, 12, // 27024 - 27032 variables -> 70304
                6, 8, // 27033 - 27038 variables -> 70320
                3, 4, // 27039 - 27041 variables -> 70328
                5, 6, // 27042 - 27046 variables -> 70340
                9, 12, // 27047 - 27055 variables -> 70364
                23, 30, // 27056 - 27078 variables -> 70424
                15, 20, // 27079 - 27093 variables -> 70464
                5, 6, // 27094 - 27098 variables -> 70476
                18, 24, // 27099 - 27116 variables -> 70524
                5, 6, // 27117 - 27121 variables -> 70536
                8, 10, // 27122 - 27129 variables -> 70556
                9, 12, // 27130 - 27138 variables -> 70580
                1, 2, // 27139 variables -> 70584
                8, 10, // 27140 - 27147 variables -> 70604
                16, 20, // 27148 - 27163 variables -> 70644
                4, 6, // 27164 - 27167 variables -> 70656
                5, 6, // 27168 - 27172 variables -> 70668
                3, 4, // 27173 - 27175 variables -> 70676
                9, 12, // 27176 - 27184 variables -> 70700
                11, 14, // 27185 - 27195 variables -> 70728
                8, 10, // 27196 - 27203 variables -> 70748
                13, 18, // 27204 - 27216 variables -> 70784
                10, 12, // 27217 - 27226 variables -> 70808
                6, 8, // 27227 - 27232 variables -> 70824
                4, 6, // 27233 - 27236 variables -> 70836
                10, 12, // 27237 - 27246 variables -> 70860
                3, 4, // 27247 - 27249 variables -> 70868
                10, 14, // 27250 - 27259 variables -> 70896
                8, 10, // 27260 - 27267 variables -> 70916
                2, 2, // 27268 - 27269 variables -> 70920
                9, 12, // 27270 - 27278 variables -> 70944
                23, 30, // 27279 - 27301 variables -> 71004
                12, 16, // 27302 - 27313 variables -> 71036
                2, 2, // 27314 - 27315 variables -> 71040
                9, 12, // 27316 - 27324 variables -> 71064
                5, 6, // 27325 - 27329 variables -> 71076
                3, 4, // 27330 - 27332 variables -> 71084
                1, 2, // 27333 variables -> 71088
                3, 4, // 27334 - 27336 variables -> 71096
                5, 6, // 27337 - 27341 variables -> 71108
                20, 26, // 27342 - 27361 variables -> 71160
                3, 4, // 27362 - 27364 variables -> 71168
                14, 18, // 27365 - 27378 variables -> 71204
                1, 2, // 27379 variables -> 71208
                4, 4, // 27380 - 27383 variables -> 71216
                4, 6, // 27384 - 27387 variables -> 71228
                11, 14, // 27388 - 27398 variables -> 71256
                41, 54, // 27399 - 27439 variables -> 71364
                5, 6, // 27440 - 27444 variables -> 71376
                40, 52, // 27445 - 27484 variables -> 71480
                2, 2, // 27485 - 27486 variables -> 71484
                12, 16, // 27487 - 27498 variables -> 71516
                5, 6, // 27499 - 27503 variables -> 71528
                4, 6, // 27504 - 27507 variables -> 71540
                9, 12, // 27508 - 27516 variables -> 71564
                20, 26, // 27517 - 27536 variables -> 71616
                3, 4, // 27537 - 27539 variables -> 71624
                2, 2, // 27540 - 27541 variables -> 71628
                5, 6, // 27542 - 27546 variables -> 71640
                17, 22, // 27547 - 27563 variables -> 71684
                4, 6, // 27564 - 27567 variables -> 71696
                2, 2, // 27568 - 27569 variables -> 71700
                9, 12, // 27570 - 27578 variables -> 71724
                9, 12, // 27579 - 27587 variables -> 71748
                5, 6, // 27588 - 27592 variables -> 71760
                7, 10, // 27593 - 27599 variables -> 71780
                14, 18, // 27600 - 27613 variables -> 71816
                2, 2, // 27614 - 27615 variables -> 71820
                9, 12, // 27616 - 27624 variables -> 71844
                9, 12, // 27625 - 27633 variables -> 71868
                8, 10, // 27634 - 27641 variables -> 71888
                14, 18, // 27642 - 27655 variables -> 71924
                9, 12, // 27656 - 27664 variables -> 71948
                5, 6, // 27665 - 27669 variables -> 71960
                6, 8, // 27670 - 27675 variables -> 71976
                4, 6, // 27676 - 27679 variables -> 71988
                8, 10, // 27680 - 27687 variables -> 72008
                5, 6, // 27688 - 27692 variables -> 72020
                6, 8, // 27693 - 27698 variables -> 72036
                3, 4, // 27699 - 27701 variables -> 72044
                2, 2, // 27702 - 27703 variables -> 72048
                3, 4, // 27704 - 27706 variables -> 72056
                15, 20, // 27707 - 27721 variables -> 72096
                18, 24, // 27722 - 27739 variables -> 72144
                5, 6, // 27740 - 27744 variables -> 72156
                5, 6, // 27745 - 27749 variables -> 72168
                7, 10, // 27750 - 27756 variables -> 72188
                11, 14, // 27757 - 27767 variables -> 72216
                8, 10, // 27768 - 27775 variables -> 72236
                1, 2, // 27776 variables -> 72240
                17, 22, // 27777 - 27793 variables -> 72284
                5, 6, // 27794 - 27798 variables -> 72296
                11, 14, // 27799 - 27809 variables -> 72324
                7, 10, // 27810 - 27816 variables -> 72344
                20, 26, // 27817 - 27836 variables -> 72396
                3, 4, // 27837 - 27839 variables -> 72404
                14, 18, // 27840 - 27853 variables -> 72440
                6, 8, // 27854 - 27859 variables -> 72456
                10, 12, // 27860 - 27869 variables -> 72480
                9, 12, // 27870 - 27878 variables -> 72504
                8, 10, // 27879 - 27886 variables -> 72524
                9, 12, // 27887 - 27895 variables -> 72548
                4, 6, // 27896 - 27899 variables -> 72560
                7, 8, // 27900 - 27906 variables -> 72576
                12, 16, // 27907 - 27918 variables -> 72608
                5, 6, // 27919 - 27923 variables -> 72620
                6, 8, // 27924 - 27929 variables -> 72636
                4, 6, // 27930 - 27933 variables -> 72648
                5, 6, // 27934 - 27938 variables -> 72660
                17, 22, // 27939 - 27955 variables -> 72704
                1, 2, // 27956 variables -> 72708
                8, 10, // 27957 - 27964 variables -> 72728
                15, 20, // 27965 - 27979 variables -> 72768
                8, 10, // 27980 - 27987 variables -> 72788
                5, 6, // 27988 - 27992 variables -> 72800
                34, 44, // 27993 - 28026 variables -> 72888
                13, 18, // 28027 - 28039 variables -> 72924
                5, 6, // 28040 - 28044 variables -> 72936
                8, 10, // 28045 - 28052 variables -> 72956
                1, 2, // 28053 variables -> 72960
                3, 4, // 28054 - 28056 variables -> 72968
                5, 6, // 28057 - 28061 variables -> 72980
                11, 14, // 28062 - 28072 variables -> 73008
                3, 4, // 28073 - 28075 variables -> 73016
                20, 26, // 28076 - 28095 variables -> 73068
                3, 4, // 28096 - 28098 variables -> 73076
                1, 2, // 28099 variables -> 73080
                10, 12, // 28100 - 28109 variables -> 73104
                7, 10, // 28110 - 28116 variables -> 73124
                7, 8, // 28117 - 28123 variables -> 73140
                3, 4, // 28124 - 28126 variables -> 73148
                6, 8, // 28127 - 28132 variables -> 73164
                9, 12, // 28133 - 28141 variables -> 73188
                3, 4, // 28142 - 28144 variables -> 73196
                9, 12, // 28145 - 28153 variables -> 73220
                6, 8, // 28154 - 28159 variables -> 73236
                17, 22, // 28160 - 28176 variables -> 73280
                7, 8, // 28177 - 28183 variables -> 73296
                4, 6, // 28184 - 28187 variables -> 73308
                8, 10, // 28188 - 28195 variables -> 73328
                14, 18, // 28196 - 28209 variables -> 73364
                4, 6, // 28210 - 28213 variables -> 73376
                5, 6, // 28214 - 28218 variables -> 73388
                6, 8, // 28219 - 28224 variables -> 73404
                5, 6, // 28225 - 28229 variables -> 73416
                9, 12, // 28230 - 28238 variables -> 73440
                3, 4, // 28239 - 28241 variables -> 73448
                6, 8, // 28242 - 28247 variables -> 73464
                14, 18, // 28248 - 28261 variables -> 73500
                8, 10, // 28262 - 28269 variables -> 73520
                9, 12, // 28270 - 28278 variables -> 73544
                5, 6, // 28279 - 28283 variables -> 73556
                9, 12, // 28284 - 28292 variables -> 73580
                1, 2, // 28293 variables -> 73584
                5, 6, // 28294 - 28298 variables -> 73596
                3, 4, // 28299 - 28301 variables -> 73604
                2, 2, // 28302 - 28303 variables -> 73608
                12, 16, // 28304 - 28315 variables -> 73640
                9, 12, // 28316 - 28324 variables -> 73664
                9, 12, // 28325 - 28333 variables -> 73688
                11, 14, // 28334 - 28344 variables -> 73716
                8, 10, // 28345 - 28352 variables -> 73736
                11, 14, // 28353 - 28363 variables -> 73764
                4, 6, // 28364 - 28367 variables -> 73776
                8, 10, // 28368 - 28375 variables -> 73796
                9, 12, // 28376 - 28384 variables -> 73820
                2, 2, // 28385 - 28386 variables -> 73824
                9, 12, // 28387 - 28395 variables -> 73848
                4, 6, // 28396 - 28399 variables -> 73860
                4, 4, // 28400 - 28403 variables -> 73868
                4, 6, // 28404 - 28407 variables -> 73880
                2, 2, // 28408 - 28409 variables -> 73884
                9, 12, // 28410 - 28418 variables -> 73908
                3, 4, // 28419 - 28421 variables -> 73916
                20, 26, // 28422 - 28441 variables -> 73968
                5, 6, // 28442 - 28446 variables -> 73980
                13, 18, // 28447 - 28459 variables -> 74016
                5, 6, // 28460 - 28464 variables -> 74028
                8, 10, // 28465 - 28472 variables -> 74048
                4, 6, // 28473 - 28476 variables -> 74060
                2, 2, // 28477 - 28478 variables -> 74064
                14, 18, // 28479 - 28492 variables -> 74100
                7, 10, // 28493 - 28499 variables -> 74120
                7, 8, // 28500 - 28506 variables -> 74136
                3, 4, // 28507 - 28509 variables -> 74144
                20, 26, // 28510 - 28529 variables -> 74196
                7, 10, // 28530 - 28536 variables -> 74216
                16, 20, // 28537 - 28552 variables -> 74256
                4, 6, // 28553 - 28556 variables -> 74268
                13, 16, // 28557 - 28569 variables -> 74300
                15, 20, // 28570 - 28584 variables -> 74340
                9, 12, // 28585 - 28593 variables -> 74364
                8, 10, // 28594 - 28601 variables -> 74384
                6, 8, // 28602 - 28607 variables -> 74400
                8, 10, // 28608 - 28615 variables -> 74420
                1, 2, // 28616 variables -> 74424
                13, 16, // 28617 - 28629 variables -> 74456
                4, 6, // 28630 - 28633 variables -> 74468
                16, 20, // 28634 - 28649 variables -> 74508
                7, 10, // 28650 - 28656 variables -> 74528
                16, 20, // 28657 - 28672 variables -> 74568
                3, 4, // 28673 - 28675 variables -> 74576
                23, 30, // 28676 - 28698 variables -> 74636
                1, 2, // 28699 variables -> 74640
                4, 4, // 28700 - 28703 variables -> 74648
                6, 8, // 28704 - 28709 variables -> 74664
                12, 16, // 28710 - 28721 variables -> 74696
                2, 2, // 28722 - 28723 variables -> 74700
                13, 18, // 28724 - 28736 variables -> 74736
                3, 4, // 28737 - 28739 variables -> 74744
                2, 2, // 28740 - 28741 variables -> 74748
                5, 6, // 28742 - 28746 variables -> 74760
                7, 10, // 28747 - 28753 variables -> 74780
                14, 18, // 28754 - 28767 variables -> 74816
                9, 12, // 28768 - 28776 variables -> 74840
                11, 14, // 28777 - 28787 variables -> 74868
                14, 18, // 28788 - 28801 variables -> 74904
                5, 6, // 28802 - 28806 variables -> 74916
                12, 16, // 28807 - 28818 variables -> 74948
                15, 20, // 28819 - 28833 variables -> 74988
                5, 6, // 28834 - 28838 variables -> 75000
                3, 4, // 28839 - 28841 variables -> 75008
                6, 8, // 28842 - 28847 variables -> 75024
                5, 6, // 28848 - 28852 variables -> 75036
                3, 4, // 28853 - 28855 variables -> 75044
                4, 6, // 28856 - 28859 variables -> 75056
                10, 12, // 28860 - 28869 variables -> 75080
                6, 8, // 28870 - 28875 variables -> 75096
                8, 10, // 28876 - 28883 variables -> 75116
                1, 2, // 28884 variables -> 75120
                9, 12, // 28885 - 28893 variables -> 75144
                5, 6, // 28894 - 28898 variables -> 75156
                3, 4, // 28899 - 28901 variables -> 75164
                2, 2, // 28902 - 28903 variables -> 75168
                4, 6, // 28904 - 28907 variables -> 75180
                8, 10, // 28908 - 28915 variables -> 75200
                1, 2, // 28916 variables -> 75204
                13, 16, // 28917 - 28929 variables -> 75236
                9, 12, // 28930 - 28938 variables -> 75260
                11, 14, // 28939 - 28949 variables -> 75288
                7, 10, // 28950 - 28956 variables -> 75308
                5, 6, // 28957 - 28961 variables -> 75320
                6, 8, // 28962 - 28967 variables -> 75336
                5, 6, // 28968 - 28972 variables -> 75348
                21, 28, // 28973 - 28993 variables -> 75404
                2, 2, // 28994 - 28995 variables -> 75408
                4, 6, // 28996 - 28999 variables -> 75420
                14, 18, // 29000 - 29013 variables -> 75456
                23, 30, // 29014 - 29036 variables -> 75516
                27, 34, // 29037 - 29063 variables -> 75584
                1, 2, // 29064 variables -> 75588
                12, 16, // 29065 - 29076 variables -> 75620
                10, 12, // 29077 - 29086 variables -> 75644
                1, 2, // 29087 variables -> 75648
                14, 18, // 29088 - 29101 variables -> 75684
                12, 16, // 29102 - 29113 variables -> 75716
                5, 6, // 29114 - 29118 variables -> 75728
                6, 8, // 29119 - 29124 variables -> 75744
                8, 10, // 29125 - 29132 variables -> 75764
                6, 8, // 29133 - 29138 variables -> 75780
                8, 10, // 29139 - 29146 variables -> 75800
                6, 8, // 29147 - 29152 variables -> 75816
                7, 10, // 29153 - 29159 variables -> 75836
                34, 44, // 29160 - 29193 variables -> 75924
                5, 6, // 29194 - 29198 variables -> 75936
                5, 6, // 29199 - 29203 variables -> 75948
                3, 4, // 29204 - 29206 variables -> 75956
                15, 20, // 29207 - 29221 variables -> 75996
                3, 4, // 29222 - 29224 variables -> 76004
                2, 2, // 29225 - 29226 variables -> 76008
                3, 4, // 29227 - 29229 variables -> 76016
                10, 14, // 29230 - 29239 variables -> 76044
                22, 28, // 29240 - 29261 variables -> 76100
                6, 8, // 29262 - 29267 variables -> 76116
                5, 6, // 29268 - 29272 variables -> 76128
                12, 16, // 29273 - 29284 variables -> 76160
                11, 14, // 29285 - 29295 variables -> 76188
                23, 30, // 29296 - 29318 variables -> 76248
                5, 6, // 29319 - 29323 variables -> 76260
                23, 30, // 29324 - 29346 variables -> 76320
                3, 4, // 29347 - 29349 variables -> 76328
                10, 14, // 29350 - 29359 variables -> 76356
                8, 10, // 29360 - 29367 variables -> 76376
                5, 6, // 29368 - 29372 variables -> 76388
                4, 6, // 29373 - 29376 variables -> 76400
                7, 8, // 29377 - 29383 variables -> 76416
                3, 4, // 29384 - 29386 variables -> 76424
                13, 18, // 29387 - 29399 variables -> 76460
                10, 12, // 29400 - 29409 variables -> 76484
                4, 6, // 29410 - 29413 variables -> 76496
                2, 2, // 29414 - 29415 variables -> 76500
                17, 22, // 29416 - 29432 variables -> 76544
                9, 12, // 29433 - 29441 variables -> 76568
                6, 8, // 29442 - 29447 variables -> 76584
                5, 6, // 29448 - 29452 variables -> 76596
                9, 12, // 29453 - 29461 variables -> 76620
                3, 4, // 29462 - 29464 variables -> 76628
                11, 14, // 29465 - 29475 variables -> 76656
                3, 4, // 29476 - 29478 variables -> 76664
                5, 6, // 29479 - 29483 variables -> 76676
                1, 2, // 29484 variables -> 76680
                3, 4, // 29485 - 29487 variables -> 76688
                14, 18, // 29488 - 29501 variables -> 76724
                15, 20, // 29502 - 29516 variables -> 76764
                5, 6, // 29517 - 29521 variables -> 76776
                12, 16, // 29522 - 29533 variables -> 76808
                30, 38, // 29534 - 29563 variables -> 76884
                12, 16, // 29564 - 29575 variables -> 76916
                1, 2, // 29576 variables -> 76920
                3, 4, // 29577 - 29579 variables -> 76928
                5, 6, // 29580 - 29584 variables -> 76940
                2, 2, // 29585 - 29586 variables -> 76944
                30, 40, // 29587 - 29616 variables -> 77024
                33, 42, // 29617 - 29649 variables -> 77108
                10, 14, // 29650 - 29659 variables -> 77136
                4, 4, // 29660 - 29663 variables -> 77144
                4, 6, // 29664 - 29667 variables -> 77156
                2, 2, // 29668 - 29669 variables -> 77160
                18, 24, // 29670 - 29687 variables -> 77208
                8, 10, // 29688 - 29695 variables -> 77228
                4, 6, // 29696 - 29699 variables -> 77240
                2, 2, // 29700 - 29701 variables -> 77244
                14, 18, // 29702 - 29715 variables -> 77280
                8, 10, // 29716 - 29723 variables -> 77300
                9, 12, // 29724 - 29732 variables -> 77324
                1, 2, // 29733 variables -> 77328
                13, 16, // 29734 - 29746 variables -> 77360
                1, 2, // 29747 variables -> 77364
                5, 6, // 29748 - 29752 variables -> 77376
                12, 16, // 29753 - 29764 variables -> 77408
                5, 6, // 29765 - 29769 variables -> 77420
                6, 8, // 29770 - 29775 variables -> 77436
                3, 4, // 29776 - 29778 variables -> 77444
                1, 2, // 29779 variables -> 77448
                8, 10, // 29780 - 29787 variables -> 77468
                5, 6, // 29788 - 29792 variables -> 77480
                6, 8, // 29793 - 29798 variables -> 77496
                8, 10, // 29799 - 29806 variables -> 77516
                1, 2, // 29807 variables -> 77520
                14, 18, // 29808 - 29821 variables -> 77556
                12, 16, // 29822 - 29833 variables -> 77588
                6, 8, // 29834 - 29839 variables -> 77604
                10, 12, // 29840 - 29849 variables -> 77628
                14, 18, // 29850 - 29863 variables -> 77664
                9, 12, // 29864 - 29872 variables -> 77688
                4, 6, // 29873 - 29876 variables -> 77700
                10, 12, // 29877 - 29886 variables -> 77724
                7, 10, // 29887 - 29893 variables -> 77744
                5, 6, // 29894 - 29898 variables -> 77756
                5, 6, // 29899 - 29903 variables -> 77768
                13, 18, // 29904 - 29916 variables -> 77804
                10, 12, // 29917 - 29926 variables -> 77828
                10, 14, // 29927 - 29936 variables -> 77856
                3, 4, // 29937 - 29939 variables -> 77864
                2, 2, // 29940 - 29941 variables -> 77868
                8, 10, // 29942 - 29949 variables -> 77888
                15, 20, // 29950 - 29964 variables -> 77928
                5, 6, // 29965 - 29969 variables -> 77940
                9, 12, // 29970 - 29978 variables -> 77964
                5, 6, // 29979 - 29983 variables -> 77976
                12, 16, // 29984 - 29995 variables -> 78008
                20, 26, // 29996 - 30015 variables -> 78060
                3, 4, // 30016 - 30018 variables -> 78068
                14, 18, // 30019 - 30032 variables -> 78104
                1, 2, // 30033 variables -> 78108
                3, 4, // 30034 - 30036 variables -> 78116
                25, 32, // 30037 - 30061 variables -> 78180
                8, 10, // 30062 - 30069 variables -> 78200
                6, 8, // 30070 - 30075 variables -> 78216
                4, 6, // 30076 - 30079 variables -> 78228
                4, 4, // 30080 - 30083 variables -> 78236
                4, 6, // 30084 - 30087 variables -> 78248
                5, 6, // 30088 - 30092 variables -> 78260
                11, 14, // 30093 - 30103 variables -> 78288
                4, 6, // 30104 - 30107 variables -> 78300
                14, 18, // 30108 - 30121 variables -> 78336
                3, 4, // 30122 - 30124 variables -> 78344
                2, 2, // 30125 - 30126 variables -> 78348
                13, 18, // 30127 - 30139 variables -> 78384
                8, 10, // 30140 - 30147 variables -> 78404
                6, 8, // 30148 - 30153 variables -> 78420
                8, 10, // 30154 - 30161 variables -> 78440
                6, 8, // 30162 - 30167 variables -> 78456
                8, 10, // 30168 - 30175 variables -> 78476
                1, 2, // 30176 variables -> 78480
                3, 4, // 30177 - 30179 variables -> 78488
                5, 6, // 30180 - 30184 variables -> 78500
                2, 2, // 30185 - 30186 variables -> 78504
                7, 10, // 30187 - 30193 variables -> 78524
                33, 42, // 30194 - 30226 variables -> 78608
                6, 8, // 30227 - 30232 variables -> 78624
                9, 12, // 30233 - 30241 variables -> 78648
                3, 4, // 30242 - 30244 variables -> 78656
                5, 6, // 30245 - 30249 variables -> 78668
                14, 18, // 30250 - 30263 variables -> 78704
                1, 2, // 30264 variables -> 78708
                12, 16, // 30265 - 30276 variables -> 78740
                7, 8, // 30277 - 30283 variables -> 78756
                3, 4, // 30284 - 30286 variables -> 78764
                1, 2, // 30287 variables -> 78768
                8, 10, // 30288 - 30295 variables -> 78788
                11, 14, // 30296 - 30306 variables -> 78816
                9, 12, // 30307 - 30315 variables -> 78840
                8, 10, // 30316 - 30323 variables -> 78860
                15, 20, // 30324 - 30338 variables -> 78900
                3, 4, // 30339 - 30341 variables -> 78908
                6, 8, // 30342 - 30347 variables -> 78924
                8, 10, // 30348 - 30355 variables -> 78944
                29, 38, // 30356 - 30384 variables -> 79020
                3, 4, // 30385 - 30387 variables -> 79028
                5, 6, // 30388 - 30392 variables -> 79040
                1, 2, // 30393 variables -> 79044
                8, 10, // 30394 - 30401 variables -> 79064
                15, 20, // 30402 - 30416 variables -> 79104
                8, 10, // 30417 - 30424 variables -> 79124
                9, 12, // 30425 - 30433 variables -> 79148
                5, 6, // 30434 - 30438 variables -> 79160
                9, 12, // 30439 - 30447 variables -> 79184
                20, 26, // 30448 - 30467 variables -> 79236
                9, 12, // 30468 - 30476 variables -> 79260
                3, 4, // 30477 - 30479 variables -> 79268
                7, 8, // 30480 - 30486 variables -> 79284
                21, 28, // 30487 - 30507 variables -> 79340
                6, 8, // 30508 - 30513 variables -> 79356
                3, 4, // 30514 - 30516 variables -> 79364
                7, 8, // 30517 - 30523 variables -> 79380
                18, 24, // 30524 - 30541 variables -> 79428
                5, 6, // 30542 - 30546 variables -> 79440
                7, 10, // 30547 - 30553 variables -> 79460
                6, 8, // 30554 - 30559 variables -> 79476
                5, 6, // 30560 - 30564 variables -> 79488
                12, 16, // 30565 - 30576 variables -> 79520
                10, 12, // 30577 - 30586 variables -> 79544
                6, 8, // 30587 - 30592 variables -> 79560
                7, 10, // 30593 - 30599 variables -> 79580
                10, 12, // 30600 - 30609 variables -> 79604
                6, 8, // 30610 - 30615 variables -> 79620
                17, 22, // 30616 - 30632 variables -> 79664
                4, 6, // 30633 - 30636 variables -> 79676
                2, 2, // 30637 - 30638 variables -> 79680
                8, 10, // 30639 - 30646 variables -> 79700
                1, 2, // 30647 variables -> 79704
                5, 6, // 30648 - 30652 variables -> 79716
                7, 10, // 30653 - 30659 variables -> 79736
                5, 6, // 30660 - 30664 variables -> 79748
                5, 6, // 30665 - 30669 variables -> 79760
                6, 8, // 30670 - 30675 variables -> 79776
                4, 6, // 30676 - 30679 variables -> 79788
                4, 4, // 30680 - 30683 variables -> 79796
                10, 14, // 30684 - 30693 variables -> 79824
                22, 28, // 30694 - 30715 variables -> 79880
                6, 8, // 30716 - 30721 variables -> 79896
                12, 16, // 30722 - 30733 variables -> 79928
                14, 18, // 30734 - 30747 variables -> 79964
                6, 8, // 30748 - 30753 variables -> 79980
                3, 4, // 30754 - 30756 variables -> 79988
                5, 6, // 30757 - 30761 variables -> 80000
                15, 20, // 30762 - 30776 variables -> 80040
                3, 4, // 30777 - 30779 variables -> 80048
                14, 18, // 30780 - 30793 variables -> 80084
                5, 6, // 30794 - 30798 variables -> 80096
                1, 2, // 30799 variables -> 80100
                19, 24, // 30800 - 30818 variables -> 80148
                18, 24, // 30819 - 30836 variables -> 80196
                5, 6, // 30837 - 30841 variables -> 80208
                5, 6, // 30842 - 30846 variables -> 80220
                9, 12, // 30847 - 30855 variables -> 80244
                9, 12, // 30856 - 30864 variables -> 80268
                3, 4, // 30865 - 30867 variables -> 80276
                2, 2, // 30868 - 30869 variables -> 80280
                17, 22, // 30870 - 30886 variables -> 80324
                1, 2, // 30887 variables -> 80328
                8, 10, // 30888 - 30895 variables -> 80348
                4, 6, // 30896 - 30899 variables -> 80360
                7, 8, // 30900 - 30906 variables -> 80376
                9, 12, // 30907 - 30915 variables -> 80400
                3, 4, // 30916 - 30918 variables -> 80408
                15, 20, // 30919 - 30933 variables -> 80448
                14, 18, // 30934 - 30947 variables -> 80484
                5, 6, // 30948 - 30952 variables -> 80496
                3, 4, // 30953 - 30955 variables -> 80504
                9, 12, // 30956 - 30964 variables -> 80528
                19, 24, // 30965 - 30983 variables -> 80576
                4, 6, // 30984 - 30987 variables -> 80588
                5, 6, // 30988 - 30992 variables -> 80600
                41, 54, // 30993 - 31033 variables -> 80708
                6, 8, // 31034 - 31039 variables -> 80724
                5, 6, // 31040 - 31044 variables -> 80736
                3, 4, // 31045 - 31047 variables -> 80744
                20, 26, // 31048 - 31067 variables -> 80796
                28, 36, // 31068 - 31095 variables -> 80868
                3, 4, // 31096 - 31098 variables -> 80876
                1, 2, // 31099 variables -> 80880
                4, 4, // 31100 - 31103 variables -> 80888
                20, 26, // 31104 - 31123 variables -> 80940
                9, 12, // 31124 - 31132 variables -> 80964
                9, 12, // 31133 - 31141 variables -> 80988
                3, 4, // 31142 - 31144 variables -> 80996
                5, 6, // 31145 - 31149 variables -> 81008
                4, 6, // 31150 - 31153 variables -> 81020
                6, 8, // 31154 - 31159 variables -> 81036
                10, 12, // 31160 - 31169 variables -> 81060
                7, 10, // 31170 - 31176 variables -> 81080
                2, 2, // 31177 - 31178 variables -> 81084
                9, 12, // 31179 - 31187 variables -> 81108
                12, 16, // 31188 - 31199 variables -> 81140
                14, 18, // 31200 - 31213 variables -> 81176
                5, 6, // 31214 - 31218 variables -> 81188
                6, 8, // 31219 - 31224 variables -> 81204
                5, 6, // 31225 - 31229 variables -> 81216
                9, 12, // 31230 - 31238 variables -> 81240
                14, 18, // 31239 - 31252 variables -> 81276
                7, 10, // 31253 - 31259 variables -> 81296
                2, 2, // 31260 - 31261 variables -> 81300
                42, 54, // 31262 - 31303 variables -> 81408
                3, 4, // 31304 - 31306 variables -> 81416
                1, 2, // 31307 variables -> 81420
                8, 10, // 31308 - 31315 variables -> 81440
                23, 30, // 31316 - 31338 variables -> 81500
                9, 12, // 31339 - 31347 variables -> 81524
                6, 8, // 31348 - 31353 variables -> 81540
                3, 4, // 31354 - 31356 variables -> 81548
                7, 8, // 31357 - 31363 variables -> 81564
                12, 16, // 31364 - 31375 variables -> 81596
                11, 14, // 31376 - 31386 variables -> 81624
                9, 12, // 31387 - 31395 variables -> 81648
                4, 6, // 31396 - 31399 variables -> 81660
                4, 4, // 31400 - 31403 variables -> 81668
                4, 6, // 31404 - 31407 variables -> 81680
                9, 12, // 31408 - 31416 variables -> 81704
                5, 6, // 31417 - 31421 variables -> 81716
                2, 2, // 31422 - 31423 variables -> 81720
                3, 4, // 31424 - 31426 variables -> 81728
                10, 14, // 31427 - 31436 variables -> 81756
                10, 12, // 31437 - 31446 variables -> 81780
                3, 4, // 31447 - 31449 variables -> 81788
                10, 14, // 31450 - 31459 variables -> 81816
                5, 6, // 31460 - 31464 variables -> 81828
                19, 24, // 31465 - 31483 variables -> 81876
                4, 6, // 31484 - 31487 variables -> 81888
                5, 6, // 31488 - 31492 variables -> 81900
                7, 10, // 31493 - 31499 variables -> 81920
                10, 12, // 31500 - 31509 variables -> 81944
                9, 12, // 31510 - 31518 variables -> 81968
                15, 20, // 31519 - 31533 variables -> 82008
                14, 18, // 31534 - 31547 variables -> 82044
                5, 6, // 31548 - 31552 variables -> 82056
                4, 6, // 31553 - 31556 variables -> 82068
                13, 16, // 31557 - 31569 variables -> 82100
                6, 8, // 31570 - 31575 variables -> 82116
                3, 4, // 31576 - 31578 variables -> 82124
                5, 6, // 31579 - 31583 variables -> 82136
                15, 20, // 31584 - 31598 variables -> 82176
                3, 4, // 31599 - 31601 variables -> 82184
                25, 32, // 31602 - 31626 variables -> 82248
                3, 4, // 31627 - 31629 variables -> 82256
                10, 14, // 31630 - 31639 variables -> 82284
                8, 10, // 31640 - 31647 variables -> 82304
                2, 2, // 31648 - 31649 variables -> 82308
                4, 6, // 31650 - 31653 variables -> 82320
                10, 12, // 31654 - 31663 variables -> 82344
                12, 16, // 31664 - 31675 variables -> 82376
                1, 2, // 31676 variables -> 82380
                3, 4, // 31677 - 31679 variables -> 82388
                5, 6, // 31680 - 31684 variables -> 82400
                9, 12, // 31685 - 31693 variables -> 82424
                2, 2, // 31694 - 31695 variables -> 82428
                8, 10, // 31696 - 31703 variables -> 82448
                6, 8, // 31704 - 31709 variables -> 82464
                4, 6, // 31710 - 31713 variables -> 82476
                3, 4, // 31714 - 31716 variables -> 82484
                2, 2, // 31717 - 31718 variables -> 82488
                8, 10, // 31719 - 31726 variables -> 82508
                10, 14, // 31727 - 31736 variables -> 82536
                5, 6, // 31737 - 31741 variables -> 82548
                5, 6, // 31742 - 31746 variables -> 82560
                9, 12, // 31747 - 31755 variables -> 82584
                14, 18, // 31756 - 31769 variables -> 82620
                26, 34, // 31770 - 31795 variables -> 82688
                6, 8, // 31796 - 31801 variables -> 82704
                8, 10, // 31802 - 31809 variables -> 82724
                4, 6, // 31810 - 31813 variables -> 82736
                19, 24, // 31814 - 31832 variables -> 82784
                4, 6, // 31833 - 31836 variables -> 82796
                2, 2, // 31837 - 31838 variables -> 82800
                8, 10, // 31839 - 31846 variables -> 82820
                9, 12, // 31847 - 31855 variables -> 82844
                1, 2, // 31856 variables -> 82848
                23, 30, // 31857 - 31879 variables -> 82908
                8, 10, // 31880 - 31887 variables -> 82928
                11, 14, // 31888 - 31898 variables -> 82956
                9, 12, // 31899 - 31907 variables -> 82980
                9, 12, // 31908 - 31916 variables -> 83004
                13, 16, // 31917 - 31929 variables -> 83036
                4, 6, // 31930 - 31933 variables -> 83048
                5, 6, // 31934 - 31938 variables -> 83060
                1, 2, // 31939 variables -> 83064
                14, 18, // 31940 - 31953 variables -> 83100
                3, 4, // 31954 - 31956 variables -> 83108
                5, 6, // 31957 - 31961 variables -> 83120
                23, 30, // 31962 - 31984 variables -> 83180
                11, 14, // 31985 - 31995 variables -> 83208
                3, 4, // 31996 - 31998 variables -> 83216
                5, 6, // 31999 - 32003 variables -> 83228
                4, 6, // 32004 - 32007 variables -> 83240
                2, 2, // 32008 - 32009 variables -> 83244
                4, 6, // 32010 - 32013 variables -> 83256
                3, 4, // 32014 - 32016 variables -> 83264
                5, 6, // 32017 - 32021 variables -> 83276
                11, 14, // 32022 - 32032 variables -> 83304
                4, 6, // 32033 - 32036 variables -> 83316
                3, 4, // 32037 - 32039 variables -> 83324
                7, 8, // 32040 - 32046 variables -> 83340
                7, 10, // 32047 - 32053 variables -> 83360
                10, 12, // 32054 - 32063 variables -> 83384
                4, 6, // 32064 - 32067 variables -> 83396
                25, 32, // 32068 - 32092 variables -> 83460
                7, 10, // 32093 - 32099 variables -> 83480
                7, 8, // 32100 - 32106 variables -> 83496
                17, 22, // 32107 - 32123 variables -> 83540
                1, 2, // 32124 variables -> 83544
                8, 10, // 32125 - 32132 variables -> 83564
                4, 6, // 32133 - 32136 variables -> 83576
                19, 24, // 32137 - 32155 variables -> 83624
                6, 8, // 32156 - 32161 variables -> 83640
                3, 4, // 32162 - 32164 variables -> 83648
                23, 30, // 32165 - 32187 variables -> 83708
                5, 6, // 32188 - 32192 variables -> 83720
                1, 2, // 32193 variables -> 83724
                10, 12, // 32194 - 32203 variables -> 83748
                12, 16, // 32204 - 32215 variables -> 83780
                6, 8, // 32216 - 32221 variables -> 83796
                5, 6, // 32222 - 32226 variables -> 83808
                3, 4, // 32227 - 32229 variables -> 83816
                4, 6, // 32230 - 32233 variables -> 83828
                6, 8, // 32234 - 32239 variables -> 83844
                13, 16, // 32240 - 32252 variables -> 83876
                11, 14, // 32253 - 32263 variables -> 83904
                4, 6, // 32264 - 32267 variables -> 83916
                5, 6, // 32268 - 32272 variables -> 83928
                3, 4, // 32273 - 32275 variables -> 83936
                1, 2, // 32276 variables -> 83940
                8, 10, // 32277 - 32284 variables -> 83960
                9, 12, // 32285 - 32293 variables -> 83984
                2, 2, // 32294 - 32295 variables -> 83988
                12, 16, // 32296 - 32307 variables -> 84020
                11, 14, // 32308 - 32318 variables -> 84048
                3, 4, // 32319 - 32321 variables -> 84056
                2, 2, // 32322 - 32323 variables -> 84060
                3, 4, // 32324 - 32326 variables -> 84068
                15, 20, // 32327 - 32341 variables -> 84108
                14, 18, // 32342 - 32355 variables -> 84144
                8, 10, // 32356 - 32363 variables -> 84164
                1, 2, // 32364 variables -> 84168
                8, 10, // 32365 - 32372 variables -> 84188
                4, 6, // 32373 - 32376 variables -> 84200
                10, 12, // 32377 - 32386 variables -> 84224
                23, 30, // 32387 - 32409 variables -> 84284
                6, 8, // 32410 - 32415 variables -> 84300
                14, 18, // 32416 - 32429 variables -> 84336
                9, 12, // 32430 - 32438 variables -> 84360
                8, 10, // 32439 - 32446 variables -> 84380
                1, 2, // 32447 variables -> 84384
                5, 6, // 32448 - 32452 variables -> 84396
                4, 6, // 32453 - 32456 variables -> 84408
                3, 4, // 32457 - 32459 variables -> 84416
                10, 12, // 32460 - 32469 variables -> 84440
                9, 12, // 32470 - 32478 variables -> 84464
                1, 2, // 32479 variables -> 84468
                4, 4, // 32480 - 32483 variables -> 84476
                9, 12, // 32484 - 32492 variables -> 84500
                14, 18, // 32493 - 32506 variables -> 84536
                18, 24, // 32507 - 32524 variables -> 84584
                2, 2, // 32525 - 32526 variables -> 84588
                7, 10, // 32527 - 32533 variables -> 84608
                5, 6, // 32534 - 32538 variables -> 84620
                6, 8, // 32539 - 32544 variables -> 84636
                12, 16, // 32545 - 32556 variables -> 84668
                7, 8, // 32557 - 32563 variables -> 84684
                4, 6, // 32564 - 32567 variables -> 84696
                9, 12, // 32568 - 32576 variables -> 84720
                8, 10, // 32577 - 32584 variables -> 84740
                11, 14, // 32585 - 32595 variables -> 84768
                4, 6, // 32596 - 32599 variables -> 84780
                10, 12, // 32600 - 32609 variables -> 84804
                4, 6, // 32610 - 32613 variables -> 84816
                5, 6, // 32614 - 32618 variables -> 84828
                3, 4, // 32619 - 32621 variables -> 84836
                2, 2, // 32622 - 32623 variables -> 84840
                18, 24, // 32624 - 32641 variables -> 84888
                3, 4, // 32642 - 32644 variables -> 84896
                5, 6, // 32645 - 32649 variables -> 84908
                6, 8, // 32650 - 32655 variables -> 84924
                4, 6, // 32656 - 32659 variables -> 84936
                4, 4, // 32660 - 32663 variables -> 84944
                1, 2, // 32664 variables -> 84948
                3, 4, // 32665 - 32667 variables -> 84956
                5, 6, // 32668 - 32672 variables -> 84968
                11, 14, // 32673 - 32683 variables -> 84996
                3, 4, // 32684 - 32686 variables -> 85004
                6, 8, // 32687 - 32692 variables -> 85020
                7, 10, // 32693 - 32699 variables -> 85040
                19, 24, // 32700 - 32718 variables -> 85088
                18, 24, // 32719 - 32736 variables -> 85136
                10, 12, // 32737 - 32746 variables -> 85160
                1, 2, // 32747 variables -> 85164
                5, 6, // 32748 - 32752 variables -> 85176
                9, 12, // 32753 - 32761 variables -> 85200
                6, 22, // 32762 - 32767 variables -> 85244
            };

            /**
             * The value at offset 88 is a mystery.
             * <p>
             * It seems to be function of the number of variables.  Adding/removing/changing labels, input formats,
             * output formats, does not change the value that SAS uses.  Changing the number of bytes of each variable
             * in the observation also does not impact the value.
             * <p>
             * Setting this value to a value that's too low can cause SAS to crash.  This makes me think that this is a
             * size of something that SAS allocates and writes to.
             *
             * @param totalVariables
             *     The number of variables in the dataset.
             *
             * @return The value that should be set at offset 88.
             */
            static int compute(short totalVariables) {
                assert 0 < totalVariables : "totalVariables isn't positive: " + totalVariables;
                assert totalVariables <= Short.MAX_VALUE : "totalVariables is too large: " + totalVariables;

                int computedVariableNumber = 0;
                int halfValue = 0;
                int i = 0;
                while (computedVariableNumber <= totalVariables) {
                    final int runLength = variableCountToRow88Data[i];
                    final int halfDelta = variableCountToRow88Data[i + 1];

                    computedVariableNumber += runLength;
                    halfValue += halfDelta;

                    i += 2; // because alternates between run length and delta.
                }

                return halfValue * 2;
            }
        }

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
            write8(page, subheaderOffset, SUBHEADER_SIGNATURE_ROW_SIZE); // signature

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