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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.scharp.sas7bdat.WriteUtil.write2;
import static org.scharp.sas7bdat.WriteUtil.write4;
import static org.scharp.sas7bdat.WriteUtil.write8;
import static org.scharp.sas7bdat.WriteUtil.writeAscii;

public class Sas7bdatWriter implements AutoCloseable {

    // This can be anything, although it might need to be aligned.
    // sas chooses 0x10000 for small datasets and increments by 0x400 when more space is needed.
    private static final int MINIMUM_PAGE_SIZE = 0x10000;

    private static final int DATA_PAGE_HEADER_SIZE = 40;

    /** A collection of variables in a sas7bdat file that knows how variables are laid out */
    static class Sas7bdatUnix64bitVariables {

        private static final byte[] MISSING_NUMERIC = { 0, 0, 0, 0, 0, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF };

        final List<Variable> variables;
        final int[] physicalOffsets;
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
                rowOffset = WriteUtil.align(rowOffset, 8);
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

    /**
     * A zero-sized (truncated) subheader that is used to indicate there are no more subheaders on a page.
     */
    static class TerminalSubheader extends Subheader {

        TerminalSubheader() {
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
            assert !(subheader instanceof TerminalSubheader) : "terminal subheaders should only be added by finalize";
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
            subheaders.add(new TerminalSubheader());
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
            assert pageSize >= MINIMUM_PAGE_SIZE;
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
        pageSize = WriteUtil.align(Math.max(MINIMUM_PAGE_SIZE, dataPageSizeForSingleObservation), 0x400);
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
            offset += nextSubheader.totalVariablesInSubheader();
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
            offset += nextSubheader.totalVariablesInSubheader();
        }

        if (1 < datasetVariables.totalVariables()) {
            // TODO when is this needed?  when there's more than one variable?
            offset = 0;
            while (offset < datasetVariables.totalVariables()) {
                ColumnListSubheader nextSubheader = new ColumnListSubheader(datasetVariables, offset);
                metadata.addSubheader(nextSubheader);
                offset += nextSubheader.totalVariablesInSubheader();
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