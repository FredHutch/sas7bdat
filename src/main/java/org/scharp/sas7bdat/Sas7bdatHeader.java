///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.scharp.sas7bdat.WriteUtil.write4;
import static org.scharp.sas7bdat.WriteUtil.write8;
import static org.scharp.sas7bdat.WriteUtil.writeAscii;
import static org.scharp.sas7bdat.WriteUtil.writeUtf8;

class Sas7bdatHeader {

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

    private final int headerSize;
    private final int pageSize;
    private final long initialPageSequenceNumber;
    private final String datasetName;
    private final LocalDateTime creationDate;
    private final int totalPages;

    Sas7bdatHeader(PageSequenceGenerator pageSequenceGenerator, int headerSize, int pageSize,
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
        writeUtf8(data, 92, datasetName, 64, (byte) ' ');

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
        write4(data, 200 + doubleAlignmentOffset, pageSize);

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