#!/usr/bin/env groovy
///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
// This script prints the structure of a SAS7BDAT file.
///////////////////////////////////////////////////////////////////////////////

import java.nio.charset.StandardCharsets
import java.time.*

class Header {
    // The file starts at the header.
    final int bitSize
    final int headerSize
    final int pageSize
    final int totalPages

    Header(int bitSize, int headerSize, int pageSize, int totalPages) {
        this.bitSize    = bitSize
        this.headerSize = headerSize
        this.pageSize   = pageSize
        this.totalPages = totalPages
    }

    private static int toggleEndian(int i) {
        ((i >> 24) & 0x000000FF) |
            ((i >> 8) & 0x0000FF00) |
            ((i << 8) & 0x00FF0000) |
            ((i << 24) & 0xFF000000)
    }

    private static short toggleEndian(short s) {
        ((s >> 8) & 0x00FF) | (s << 8)
    }

    static Header parse(sas7bdatFile, InputStream inputStream) {
        try {
            // Read the "alignment" field, which indicates if the file is 32-bits or 64-bits.
            // This script only processes 64-bit SAS files.
            inputStream.skipNBytes(32)
            byte alignment = inputStream.readByte()
            int bitSize
            switch (alignment) {
                case 0x22:
                    bitSize = 32
                    break

                case 0x33:
                    bitSize = 64
                    break

                default:
                    println "ERROR: $sas7bdatFile has an alignment that this script can't handle: $alignment"
                    System.exit(1)
            }

            int headerSizeOffset = bitSize == 32 ? 196 : 200
            inputStream.skipNBytes(headerSizeOffset - 33)
            int headerSize = toggleEndian(inputStream.readInt())
            int pageSize = toggleEndian(inputStream.readInt())
            int totalPages = toggleEndian(inputStream.readInt())

            // Jump to the end of the header, which should be the first metadata page.
            int currentOffset = headerSizeOffset + 4 + 4 + 4
            inputStream.skipNBytes(headerSize - currentOffset)

            return new Header(bitSize, headerSize, pageSize, totalPages)

        } catch (EOFException exception) {
            println "ERROR: $sas7bdatFile is too small to have a legal header"
            System.exit(1)
        }
    }
}


class SubheaderSignature {
    static final long ROW_SIZE = 0x00000000F7F7F7F7L
    static final long COLUMN_SIZE = 0x00000000F6F6F6F6L
    static final long SUBHEADER_COUNTS = 0xFFFFFFFFFFFFFC00L
    static final long COLUMN_FORMAT = 0xFFFFFFFFFFFFFBFEL
    static final long COLUMN_MASK = 0xFFFFFFFFFFFFFFF8L
    static final long COLUMN_ATTRS = 0xFFFFFFFFFFFFFFFCL
    static final long COLUMN_TEXT = 0xFFFFFFFFFFFFFFFDL
    static final long COLUMN_LIST = 0xFFFFFFFFFFFFFFFEL
    static final long COLUMN_NAME = 0xFFFFFFFFFFFFFFFFL
    static final long UNKNOWN_A = 0xFFFFFFFFFFFFFFFBL
    static final long UNKNOWN_B = 0xFFFFFFFFFFFFFFFAL
    static final long UNKNOWN_C = 0xFFFFFFFFFFFFFFF9L

    static String toString(long signature) {
        switch (signature) {
            case SubheaderSignature.ROW_SIZE:
                return 'Row Size'
            case SubheaderSignature.COLUMN_SIZE:
                return 'Column Size'
            case SubheaderSignature.SUBHEADER_COUNTS:
                return 'Subheader Counts'
            case SubheaderSignature.COLUMN_FORMAT:
                return 'Column Format'
            case SubheaderSignature.COLUMN_MASK:
                return 'Column Mask'
            case SubheaderSignature.COLUMN_ATTRS:
                return 'Column Attributes'
            case SubheaderSignature.COLUMN_TEXT:
                return 'Column Text'
            case SubheaderSignature.COLUMN_LIST:
                return 'Column List'
            case SubheaderSignature.COLUMN_NAME:
                return 'Column Name'
            case SubheaderSignature.UNKNOWN_A:
                return 'Truncated A'
            case SubheaderSignature.UNKNOWN_B:
                return 'Truncated B'
            case SubheaderSignature.UNKNOWN_C:
                return 'Truncated C'
            default:
                return "Unknown (0x%X)".formatted(signature)
        }
    }
}

class PageType {
    static final short METADATA = 0x0000
    static final short DATA = 0x0100
    static final short MIXED = 0x0200
    static final short AMD = 0x0400

    static String toString(short pageType) {
        String label
        switch (pageType) {
            case PageType.METADATA:
                label = 'metadata'
                break
            case PageType.DATA:
                label = 'data'
                break
            case PageType.MIXED:
                label = 'mixed'
                break
            case PageType.AMD:
                label = 'AMD'
                break
            default:
                label = 'unknown'
        }
        "%s (0x%X)".formatted(label, pageType)
    }
}

static String columnTypeToString(byte columnType) {
    String label
    switch (columnType) {
        case 1:
            label = "NUMERIC"
            break
        case 2:
            label = "CHARACTER"
            break
        default:
            label = "unknown"
            break
    }
    return '%s (%#x)'.formatted(label, columnType)
}

static String formatOffset(offset) {
    "(0x%X)".formatted(offset)
}

class PageReader {

    /**
     * The bit size of the SAS7BDAT file.  32 or 64.
     */
    final int bitSize

    /**
     * The offset into the file of the zeroth byte in the page array.
     */
    final int fileOffset

    /**
     * The page read into a byte array
     */
    final byte[] page

    PageReader(int bitSize, int fileOffset, byte[] page) {
        this.bitSize    = bitSize
        this.fileOffset = fileOffset
        this.page       = page
    }

    byte readByte(long offset32, long offset64) {
        if (bitSize == 32) {
            return page[offset32]
        } else {
            return page[offset64]
        }
    }

    short readShort(long offset32, long offset64) {
        if (bitSize == 32) {
            return ((0xFF00) & page[offset32 + 1] << 8) | (0x00FF & page[offset32])
        } else {
            return ((0xFF00) & page[offset64 + 1] << 8) | (0x00FF & page[offset64])
        }
    }

    int readUnsignedShort(long offset32, long offset64) {
        readShort(offset32, offset64) & 0xFFFF
    }

    int readInt(long offset32, long offset64) {
        if (bitSize == 32) {
            return (0xFF000000 & (page[offset32 + 3] << 24)) |
                   (0x00FF0000 & (page[offset32 + 2] << 16)) |
                   (0x0000FF00 & (page[offset32 + 1] << 8))  |
                   (0x000000FF & (page[offset32 + 0]))
        } else {
            return (0xFF000000 & (page[offset64 + 3] << 24)) |
                   (0x00FF0000 & (page[offset64 + 2] << 16)) |
                   (0x0000FF00 & (page[offset64 + 1] << 8))  |
                   (0x000000FF & (page[offset64 + 0]))
        }
    }

    long readLong(long offset32, long offset64) {
        if (bitSize == 32) {
            return (0xFF000000L & (page[offset32 + 3] << 24)) |
                   (0x00FF0000L & (page[offset32 + 2] << 16)) |
                   (0x0000FF00L & (page[offset32 + 1] << 8)) |
                   (0x000000FFL & (page[offset32 + 0]))
        } else {
            return (0xFF00000000000000L & ((long) page[offset64 + 7] << 56)) |
                   (0x00FF000000000000L & ((long) page[offset64 + 6] << 48)) |
                   (0x0000FF0000000000L & ((long) page[offset64 + 5] << 40)) |
                   (0x000000FF00000000L & ((long) page[offset64 + 4] << 32)) |
                   (0x00000000FF000000L & ((long) page[offset64 + 3] << 24)) |
                   (0x0000000000FF0000L & ((long) page[offset64 + 2] << 16)) |
                   (0x000000000000FF00L & ((long) page[offset64 + 1] << 8)) |
                   (0x00000000000000FFL & ((long) page[offset64 + 0]))
        }
    }

    long readSignature(long offset32, long offset64) {
        // Read the 4|8 byte signature in a way that will exactly match a SubheaderSignature.
        // The logic below does nothing for 64-bit but sets the upper 4 bytes to 0xFF on 32-bit except for
        // two special cases: ROW_SIZE and COLUMN_SIZE.
        long signature = readLong(offset32, offset64)
        if (bitSize == 32 && (signature & 0xFF000000L) == 0xFF000000L) {
            signature = 0xFFFFFFFF00000000L | signature
        }
        return signature
    }


    String formatOffset(long offset32, long offset64) {
        int pageOffset = bitSize == 32 ? offset32 : offset64
        return "(0x%X)".formatted(fileOffset + pageOffset)
    }

    void printSubheaderField(long offset32, long offset64, String text, value) {
        String spacer = text.length() < 40 ? ' '.repeat(40 - text.length()) : ''
        println "    ${formatOffset(offset32, offset64)} $text$spacer= $value"
    }

    LocalDateTime printSubheaderTimestampField(long offset32, long offset64, String text) {
        long longValue = readLong(offset32, offset64)
        double doubleValue = Double.longBitsToDouble(longValue)
        LocalDateTime timestamp = LocalDateTime.of(1960, 1, 1, 0, 0).plusSeconds(doubleValue.longValue())
        printSubheaderField(offset32, offset64, text, "0x%X (%s)".formatted(longValue, timestamp))
        return timestamp
    }

    LocalDateTime printSubheaderTimestampField(long baseOffset, long additionalOffset32, long additionalOffset64, String text) {
        return printSubheaderTimestampField(baseOffset + additionalOffset32, baseOffset + additionalOffset64, text)
    }

    long printSubheaderField8(long offset32, long offset64, String text) {
        long value = readLong(offset32, offset64)
        printSubheaderField(offset32, offset64, text, value)
        return value
    }

    long printSubheaderField8(long baseOffset, long additionalOffset32, long additionalOffset64, String text) {
        return printSubheaderField8(baseOffset + additionalOffset32, baseOffset + additionalOffset64, text)
    }


    int printSubheaderField4(long offset32, long offset64, String text) {
        int value = readInt(offset32, offset64)
        printSubheaderField(offset32, offset64, text, "0x%X".formatted(value))
        return value
    }

    int printSubheaderField4(long baseOffset, long additionalOffset32, long additionalOffset64, String text) {
        return printSubheaderField4(baseOffset + additionalOffset32, baseOffset + additionalOffset64, text)
    }


    short printSubheaderField2(long offset32, long offset64, String text) {
        short value = readShort(offset32, offset64)
        printSubheaderField(offset32, offset64, text, value)
        return value
    }

    short printSubheaderField2(long baseOffset, long additionalOffset32, long additionalOffset64, String text) {
        return printSubheaderField2(baseOffset + additionalOffset32, baseOffset + additionalOffset64, text)
    }


    int printSubheaderFieldU2(long offset32, long offset64, String text) {
        int value = readUnsignedShort(offset32, offset64)
        printSubheaderField(offset32, offset64, text, value)
        return value
    }

    int printSubheaderFieldU2(long baseOffset, long additionalOffset32, long additionalOffset64, String text) {
        return printSubheaderFieldU2(baseOffset + additionalOffset32, baseOffset + additionalOffset64, text)
    }
}

class ColumnText {
    List<byte[]> columnTextSubheaders = []

    void add(byte[] page, long offset, long length) {
        def columnTextSubheader = Arrays.copyOfRange(page, (int) offset, (int) (offset + length))
        columnTextSubheaders.add(columnTextSubheader)
    }

    String getText(int bitSize, int textSubheaderIndex, int textSubheaderOffset, int textLength) {
        if (0 <= textSubheaderIndex && textSubheaderIndex < columnTextSubheaders.size()) {
            byte[] columnTextSubheader = columnTextSubheaders[textSubheaderIndex]
            int offsetInColumnTextSubheader = textSubheaderOffset + (bitSize == 32 ? 4 : 8) // offset doesn't include signature
            if (0 <= offsetInColumnTextSubheader && offsetInColumnTextSubheader + textLength <= columnTextSubheader.length) {
                return new String(columnTextSubheader, offsetInColumnTextSubheader, textLength, StandardCharsets.UTF_8)
            }
        }
        return null
    }
}

static void printColumnText(ColumnText columnText, pageReader, long baseOffset, long additionalOffset32, long additionalOffset64, String textLabel) {
    // Determine the location of the text.
    int offset32 = baseOffset + additionalOffset32
    int offset64 = baseOffset + additionalOffset64
    short textSubheaderIndex  = pageReader.readShort(offset32 + 0, offset64 + 0)
    short textSubheaderOffset = pageReader.readShort(offset32 + 2, offset64 + 2)
    short length              = pageReader.readShort(offset32 + 4, offset64 + 4)

    // Read text from the Column Text subheader.
    String displayedText = getDisplayText(columnText, pageReader.bitSize, textSubheaderIndex, textSubheaderOffset, length)

    // Print the text.
    println "    $textLabel = ${displayedText}"
    println "      ${pageReader.formatOffset(offset32 + 0, offset64 + 0)} $textLabel Text Subheader Index     = ${textSubheaderIndex}"
    println "      ${pageReader.formatOffset(offset32 + 2, offset64 + 2)} $textLabel Offset In Text Subheader = ${textSubheaderOffset}"
    println "      ${pageReader.formatOffset(offset32 + 4, offset64 + 4)} $textLabel Length                   = ${length}"
}

static def toPrintingChar(byte ch) {
    // Printable ASCII characters range from 32 (space) to 126 (tilde)
    (char) (32 <= ch && ch <= 126 ? ch : '.')
}

def hexdumpCv(int fileOffset, byte[] page, long pageOffset, long length) {
    // Dump the data in lines of 16 bytes
    for (int lineOffset = pageOffset; lineOffset < pageOffset + length; lineOffset += 16) {
        int totalBytesRemaining = pageOffset + length - lineOffset
        int lineLength = Math.min(totalBytesRemaining, 16)

        // The output from hexdump -Cv looks like
        // """
        //   (0x2FF44) 79 53 41 40 31 4b 6d cf  88 35 00 00 4c 41 53 54  ySA@1Km..5..LAST
        // """

        // Print the file offset
        print "      ${formatOffset(fileOffset + lineOffset)}"

        // Print the hexadecimal portion
        for (int i = 0; i < 16; i++) {

            // Print the extra space between the first 8 bytes and the second 8 bytes.
            if (i == 8) {
                print " "
            }

            // Print the first 8 bytes
            if (i < lineLength) {
                print " %02x".formatted(page[lineOffset + i])
            } else {
                print "   "
            }
        }

        // Print the ASCII portion
        print "  "
        for (int i = 0; i < lineLength; i++) {
            print toPrintingChar(page[lineOffset + i])
        }

        print "\n"
    }
}

class ParsedState {
    int rowSizeInBytes = 0
    ColumnText columnText = new ColumnText()
    int totalVariableNameSubheaders = 0
    int lastColumnAttribute = 0
    int lastColumnFormat = 0
    int lastRecordNumber = 0

    // Fields from Row Size subheader which are parsed before Column Text has been.
    int compressionIndex
    int compressionOffset
    int compressionLength

    int datasetLabelIndex
    int datasetLabelOffset
    int datasetLabelLength

    int datasetTypeIndex
    int datasetTypeOffset
    int datasetTypeLength

    int unknownStringIndex
    int unknownStringOffset
    int unknownStringLength
}

static String getDisplayText(ColumnText columnText, int bitSize, int textSubheaderIndex, int textSubheaderOffset, int textLength) {
    String text = columnText.getText(bitSize, textSubheaderIndex, textSubheaderOffset, textLength)
    return text != null ? "\"${text.replace("\0", "\\0")}\"" : '<malformed>'
}

void printPage(int fileOffset, int bitSize, byte[] page, ParsedState parsedState) {
    def pageReader = new PageReader(bitSize, fileOffset, page)
    try {
        long unknownField = pageReader.readLong(12, 24)
        short pageType = pageReader.readShort(16, 32)
        int totalBlocks = pageReader.readUnsignedShort(18, 34) // treat as "unsigned short"
        short totalSubheaders = pageReader.readShort(20, 36)

        println "  ${pageReader.formatOffset(12, 24)} Offset 24        = $unknownField"
        println "  ${pageReader.formatOffset(16, 32)} Type             = ${PageType.toString(pageType)}"
        println "  ${pageReader.formatOffset(18, 34)} Total Blocks     = $totalBlocks"
        println "  ${pageReader.formatOffset(20, 36)} Total Subheaders = ${totalSubheaders < 0 ? "$totalSubheaders <--- malformed" : totalSubheaders}"

        // Read each subheader
        int indexOffset = bitSize == 32 ? 24 : 40
        if (0 < totalSubheaders) {
            for (int i = 0; i < totalSubheaders; i++) {
                long subheaderOffset = pageReader.readLong(indexOffset + i * 12 + 0,  indexOffset + i * 24 + 0)
                long subheaderLength = pageReader.readLong(indexOffset + i * 12 + 4,  indexOffset + i * 24 + 8)
                byte compressionCode = pageReader.readByte(indexOffset + i * 12 + 8, indexOffset + i * 24 + 16)
                byte typeCode        = pageReader.readByte(indexOffset + i * 12 + 9, indexOffset + i * 24 + 17)

                // The "deleted" subheaders don't have any length, so they also don't have a signature.
                long subheaderSignature = (subheaderLength == 0) ? 0 : pageReader.readSignature(subheaderOffset, subheaderOffset)
                println "\n  ${formatOffset(fileOffset + subheaderOffset)} ${SubheaderSignature.toString(subheaderSignature)} Subheader (length=$subheaderLength, compression=$compressionCode, type=$typeCode)"

                // Ignore subheaders that are marked as deleted.
                final int COMPRESSION_CODE_DELETED = 1
                if (compressionCode != COMPRESSION_CODE_DELETED) {
                    switch (subheaderSignature) {
                        case SubheaderSignature.ROW_SIZE:
                            pageReader.printSubheaderField8(subheaderOffset,  4,   8, "Unknown Field At offset 4|8")
                            pageReader.printSubheaderField8(subheaderOffset,  8,  16, "Unknown Field At offset 8|16")
                            pageReader.printSubheaderField8(subheaderOffset, 12,  24, "Unknown Field At offset 12|24")
                            pageReader.printSubheaderField8(subheaderOffset, 16,  32, "Unknown Field At offset 16|32")

                            parsedState.rowSizeInBytes = pageReader.printSubheaderField8(subheaderOffset, 20, 40, "Row Length")

                            pageReader.printSubheaderField8(subheaderOffset,  24, 48,  "Total Rows")
                            pageReader.printSubheaderField8(subheaderOffset,  36, 72,  "Total Column Subheaders On First Page")
                            pageReader.printSubheaderField8(subheaderOffset,  40, 80,  "Total Column Subheaders On Second Page")
                            pageReader.printSubheaderField8(subheaderOffset,  44, 88,  "Unknown Field At offset 44|88")
                            pageReader.printSubheaderField8(subheaderOffset,  48, 96,  "Aggregate Variable Name Size")
                            pageReader.printSubheaderField8(subheaderOffset,  52, 104, "Page Size")
                            pageReader.printSubheaderField8(subheaderOffset,  60, 120, "Max Row Count On Mixed Page")
                            pageReader.printSubheaderField4(subheaderOffset, 220, 440, "Page Sequence Number")

                            pageReader.printSubheaderField8(subheaderOffset, 252, 488, "Total Repairs")
                            pageReader.printSubheaderTimestampField(subheaderOffset, 260, 496, "Timestamp of Repair (UTC)")
                            pageReader.printSubheaderTimestampField(subheaderOffset, 268, 504, "Timestamp of Repair (Local)")

                            pageReader.printSubheaderField4(subheaderOffset, 264, 512, "Unknown Field At offset 264|512")
                            pageReader.printSubheaderField4(subheaderOffset, 268, 520, "Unknown Field At offset 268|520")
                            pageReader.printSubheaderField8(subheaderOffset, 272, 528, "Total Metadata Pages")
                            pageReader.printSubheaderField8(subheaderOffset, 276, 536, "Subheaders On Last Metadata Page")
                            pageReader.printSubheaderField8(subheaderOffset, 280, 544, "First Data Record, Page Index")
                            pageReader.printSubheaderField8(subheaderOffset, 284, 552, "First Data Record, Record Index")
                            pageReader.printSubheaderField8(subheaderOffset, 288, 560, "Last Data Record, Page Index")
                            pageReader.printSubheaderField8(subheaderOffset, 292, 568, "Last Data Record, Record Index")
                            pageReader.printSubheaderField8(subheaderOffset, 296, 576, "First Column Subheader, Page Index")
                            pageReader.printSubheaderField8(subheaderOffset, 300, 584, "First Column Subheader, Record Index")

                            parsedState.compressionIndex  = pageReader.printSubheaderField2(subheaderOffset, 344, 672, "Compression, Text Subheader Index")
                            parsedState.compressionOffset = pageReader.printSubheaderField2(subheaderOffset, 346, 674, "Compression, Offset")
                            parsedState.compressionLength = pageReader.printSubheaderField2(subheaderOffset, 348, 676, "Compression, Length")

                            parsedState.datasetLabelIndex  = pageReader.printSubheaderField2(subheaderOffset, 350, 678, "Dataset Label, Text Subheader Index")
                            parsedState.datasetLabelOffset = pageReader.printSubheaderField2(subheaderOffset, 352, 680, "Dataset Label, Offset")
                            parsedState.datasetLabelLength = pageReader.printSubheaderField2(subheaderOffset, 354, 682, "Dataset Label, Length")

                            parsedState.datasetTypeIndex  = pageReader.printSubheaderField2(subheaderOffset, 356, 684, "Dataset Type, Text Subheader Index")
                            parsedState.datasetTypeOffset = pageReader.printSubheaderField2(subheaderOffset, 358, 686, "Dataset Type, Offset")
                            parsedState.datasetTypeLength = pageReader.printSubheaderField2(subheaderOffset, 360, 688, "Dataset Type, Length")

                            parsedState.unknownStringIndex  = pageReader.printSubheaderField2(subheaderOffset, 362, 690, "Unknown Field At offset 362|690")
                            parsedState.unknownStringOffset = pageReader.printSubheaderField2(subheaderOffset, 364, 692, "Unknown Field At offset 364|692")
                            parsedState.unknownStringLength = pageReader.printSubheaderField2(subheaderOffset, 366, 694, "Unknown Field At offset 366|694")

                            pageReader.printSubheaderField2(subheaderOffset, 368, 696, "Unknown Field At offset 368|696")
                            pageReader.printSubheaderField2(subheaderOffset, 370, 698, "Unknown Field At offset 370|698")
                            pageReader.printSubheaderField2(subheaderOffset, 372, 700, "Unknown Field At offset 372|700")
                            pageReader.printSubheaderField2(subheaderOffset, 374, 702, "Unknown Field At offset 374|702")
                            pageReader.printSubheaderField2(subheaderOffset, 376, 704, "Unknown Field At offset 376|704")
                            pageReader.printSubheaderField2(subheaderOffset, 378, 706, "Unknown Field At offset 378|706")
                            pageReader.printSubheaderField2(subheaderOffset, 416, 744, "Unknown Field At offset 416|744")
                            pageReader.printSubheaderField2(subheaderOffset, 418, 746, "Unknown Field At offset 418|746")
                            pageReader.printSubheaderField2(subheaderOffset, 420, 748, "Total ColumnText subheaders")
                            pageReader.printSubheaderField2(subheaderOffset, 422, 750, "Max Variable Name Size")
                            pageReader.printSubheaderField2(subheaderOffset, 424, 752, "Max Variable Label Size")
                            pageReader.printSubheaderFieldU2(subheaderOffset, 438, 766, "Max Observations On Data Page")
                            pageReader.printSubheaderFieldU2(subheaderOffset, 444, 776, "Total Observations")
                            pageReader.printSubheaderField8(subheaderOffset, 464, 792, "Unknown Field At offset 464|792")
                            break

                        case SubheaderSignature.COLUMN_SIZE:
                            pageReader.printSubheaderField8(subheaderOffset, 4, 8, "Total Variables")
                            break

                        case SubheaderSignature.SUBHEADER_COUNTS:
                            pageReader.printSubheaderField8(subheaderOffset, 4,  8, "Max Variable-Sized Subheader Body Size")
                            pageReader.printSubheaderField8(subheaderOffset, 8,  16, "Unknown Field At Offset 8|16")
                            pageReader.printSubheaderField8(subheaderOffset, 12, 24, "Unknown Field At Offset 12|24")
                            pageReader.printSubheaderField8(subheaderOffset, 16, 32, "Unknown Field At Offset 16|32")
                            pageReader.printSubheaderField8(subheaderOffset, 60, 112, "Unknown Field At Offset 60|112")

                            for (int j = 0; j < 12; j++) {
                                long vectorOffset32 = subheaderOffset +  64 + j * 20
                                long vectorOffset64 = subheaderOffset + 120 + j * 40
                                int signature = pageReader.readLong(vectorOffset32, vectorOffset64)
                                println("    ${pageReader.formatOffset(vectorOffset32, vectorOffset64)} Counts for ${SubheaderSignature.toString(signature)} Subheader")
                                pageReader.printSubheaderField8(vectorOffset32 + 4,  vectorOffset64 + 8, "Page Of First Appearance")
                                pageReader.printSubheaderField8(vectorOffset32 + 8,  vectorOffset64 + 16, "Position Of First Appearance")
                                pageReader.printSubheaderField8(vectorOffset32 + 12, vectorOffset64 + 24, "Page Of Last Appearance")
                                pageReader.printSubheaderField8(vectorOffset32 + 16, vectorOffset64 + 32, "Position Of Last Appearance")
                            }
                            break

                        case SubheaderSignature.COLUMN_FORMAT:
                            parsedState.lastColumnFormat++
                            println "    (Variable #$parsedState.lastColumnFormat)"

                            printColumnText(parsedState.columnText, pageReader, subheaderOffset, 34, 46, "Output Format Name")
                            pageReader.printSubheaderField2(subheaderOffset, 26, 24, "Output Format Width")
                            pageReader.printSubheaderField2(subheaderOffset, 18, 26, "Output Format Precision")

                            printColumnText(parsedState.columnText, pageReader, subheaderOffset, 28, 40, "Input Format Name")
                            pageReader.printSubheaderField2(subheaderOffset, 20, 28, "Input Format Width")
                            pageReader.printSubheaderField2(subheaderOffset, 22, 30, "Input Format Precision")

                            printColumnText(parsedState.columnText, pageReader, subheaderOffset, 40, 52, "Label")
                            break

                        case SubheaderSignature.COLUMN_MASK:
                            break

                        case SubheaderSignature.COLUMN_ATTRS:
                            int payloadSize = pageReader.printSubheaderField2(subheaderOffset, 4, 8, "Length Remaining In Subheader")
                            int payloadSizeFieldSize = bitSize == 32 ?  4 : 8
                            int attributesEntrySize  = bitSize == 32 ? 12 : 16
                            int totalVariables = (payloadSize - payloadSizeFieldSize) / attributesEntrySize
                            for (int variableIndex = 0; variableIndex < totalVariables; variableIndex++) {
                                int vectorOffset32 = subheaderOffset + 12 + variableIndex * attributesEntrySize
                                int vectorOffset64 = subheaderOffset + 16 + variableIndex * attributesEntrySize
                                long columnOffsetInDataRow = pageReader.readLong(vectorOffset32 + 0, vectorOffset64 + 0)
                                int columnLength           = pageReader.readInt(vectorOffset32 + 4, vectorOffset64 + 8)
                                short nameFlag             = pageReader.readShort(vectorOffset32 + 8, vectorOffset64 + 12)
                                byte columnType            = pageReader.readByte(vectorOffset32 + 10, vectorOffset64 + 14)

                                parsedState.lastColumnAttribute++
                                println "    ${pageReader.formatOffset(vectorOffset32, vectorOffset64)} Variable #${parsedState.lastColumnAttribute}"
                                println "      ${pageReader.formatOffset(vectorOffset32 +  0, vectorOffset64 +  0)} Offset In Data Row = ${columnOffsetInDataRow}"
                                println "      ${pageReader.formatOffset(vectorOffset32 +  4, vectorOffset64 +  8)} Length In Data Row = ${columnLength}"
                                println "      ${pageReader.formatOffset(vectorOffset32 +  8, vectorOffset64 + 12)} Name Flag          = ${"%#x".formatted(nameFlag)}"
                                println "      ${pageReader.formatOffset(vectorOffset32 + 10, vectorOffset64 + 14)} Column Type        = ${columnTypeToString(columnType)}"
                            }
                            break

                        case SubheaderSignature.COLUMN_TEXT:
                            // The variable names, labels, and formats are all concatenated into column texts.
                            pageReader.printSubheaderField2(subheaderOffset, 4, 8, "Size Of Subheader")

                            // Print the text like hexdump -Cv
                            println "    Text:"
                            int textOffset = bitSize == 32 ? 8 : 16
                            hexdumpCv(fileOffset, page, subheaderOffset + textOffset, subheaderLength - textOffset)

                            // Save the text for later use.
                            parsedState.columnText.add(page, subheaderOffset, subheaderLength)
                            break

                        case SubheaderSignature.COLUMN_LIST:
                            pageReader.printSubheaderField2(subheaderOffset, 4, 8, "Size of Body")

                            // The field at offset 4|8 should probably be two byte fields that takes up 4|8 bytes,
                            // but SAS puts some non-zero bytes after it.  I suspect this is uninitialized memory,
                            // but it could be extra information stored where padding was used.
                            pageReader.printSubheaderField2(subheaderOffset, 6, 10, "Unknown Field at offset 6|10")

                            // The field at offset 12|16 is similar.  It only needs to be two bytes but there are
                            // non-zero bytes after it that may have signification.
                            pageReader.printSubheaderFieldU2(subheaderOffset, 12, 16, "Unknown Field at offset 12|16")
                            pageReader.printSubheaderFieldU2(subheaderOffset, 14, 18, "Unknown Field at offset 14|18")
                            if (bitSize == 64) {
                                pageReader.printSubheaderFieldU2(subheaderOffset, 0, 20, "Unknown Field at offset 20")
                                pageReader.printSubheaderFieldU2(subheaderOffset, 0, 22, "Unknown Field at offset 22")
                            }

                            pageReader.printSubheaderFieldU2(subheaderOffset, 16, 24, "Total Variables")
                            pageReader.printSubheaderFieldU2(subheaderOffset, 18, 26, "Total Columns")
                            pageReader.printSubheaderFieldU2(subheaderOffset, 20, 28, "Unknown Field at offset 20|28")
                            pageReader.printSubheaderFieldU2(subheaderOffset, 22, 30, "Unknown Field at offset 22|30")

                            int totalColumns = pageReader.readUnsignedShort(subheaderOffset + 18, subheaderOffset + 26)
                            for (int columnIndex = 0; columnIndex < totalColumns; columnIndex++) {
                                int vectorOffset32 = 30 + columnIndex * 2
                                int vectorOffset64 = 38 + columnIndex * 2
                                if (subheaderLength < vectorOffset64 + 2) {
                                    println "     <subheader ends unexpectedly>"
                                    break
                                }
                                pageReader.printSubheaderField2(subheaderOffset, vectorOffset32, vectorOffset64, "Column #${columnIndex + 1} Value")
                            }
                            break

                        case SubheaderSignature.COLUMN_NAME:
                            int payloadSize = pageReader.printSubheaderField8(subheaderOffset, 4, 8, "Length Remaining In Subheader")
                            int totalVariables = payloadSize / 8 - 1
                            for (int variableIndex = 0; variableIndex < totalVariables; variableIndex++) {
                                int vectorOffset32 = subheaderOffset + 12 + variableIndex * 8
                                int vectorOffset64 = subheaderOffset + 16 + variableIndex * 8
                                short textSubheaderIndex  = pageReader.readShort(vectorOffset32 + 0, vectorOffset64 + 0)
                                short textSubheaderOffset = pageReader.readShort(vectorOffset32 + 2, vectorOffset64 + 2)
                                short nameLength          = pageReader.readShort(vectorOffset32 + 4, vectorOffset64 + 4)

                                // Read the name from the column text blocks.
                                String displayedName = getDisplayText(parsedState.columnText, bitSize, textSubheaderIndex, textSubheaderOffset, nameLength)

                                parsedState.totalVariableNameSubheaders++
                                println "    ${pageReader.formatOffset(vectorOffset32, vectorOffset64)} Variable #${parsedState.totalVariableNameSubheaders}: $displayedName"
                                println "      Text Subheader Index      = ${textSubheaderIndex}"
                                println "      Offset In Text Subheader  = ${textSubheaderOffset}"
                                println "      Name Length               = ${nameLength}"
                            }
                            break

                        case SubheaderSignature.UNKNOWN_A:
                            break

                        case SubheaderSignature.UNKNOWN_B:
                            break

                        case SubheaderSignature.UNKNOWN_C:
                            break
                    }
                }
            }
        }

        if (pageType != PageType.METADATA) {

            // Print any delayed information from the metadata that was not known at the
            // time it was parsed.
            if (parsedState.lastRecordNumber == 0) {
                print "\n"
                println "From Row Size Subheader:"

                String displayedCompression = getDisplayText(
                    parsedState.columnText,
                    bitSize,
                    parsedState.compressionIndex,
                    parsedState.compressionOffset,
                    parsedState.compressionLength)

                String displayedDatasetType = getDisplayText(
                    parsedState.columnText,
                    bitSize,
                    parsedState.datasetTypeIndex,
                    parsedState.datasetTypeOffset,
                    parsedState.datasetTypeLength)

                String displayedDatasetLabel = getDisplayText(
                    parsedState.columnText,
                    bitSize,
                    parsedState.datasetLabelIndex,
                    parsedState.datasetLabelOffset,
                    parsedState.datasetLabelLength)

                String displayedUnknownString = getDisplayText(
                    parsedState.columnText,
                    bitSize,
                    parsedState.unknownStringIndex,
                    parsedState.unknownStringOffset,
                    parsedState.unknownStringLength)

                println "  Compression:    $displayedCompression"
                println "  Dataset Type:   $displayedDatasetType"
                println "  Dataset Label:  $displayedDatasetLabel"
                println "  Unknown String: $displayedUnknownString"
            }

            // Print the data
            print "\n"
            int rowOffsetInPage = indexOffset + 24 * totalSubheaders // start after the last index
            int totalRowsOnPage = totalBlocks - totalSubheaders
            for (int i = 0; i < totalRowsOnPage; i++) {
                parsedState.lastRecordNumber++
                println "  Record #$parsedState.lastRecordNumber:"

                hexdumpCv(fileOffset, page, rowOffsetInPage, parsedState.rowSizeInBytes)
                rowOffsetInPage += parsedState.rowSizeInBytes
            }
        }
    } catch (ArrayIndexOutOfBoundsException exception) {
        println "  <file is truncated>"
    }
}

def cli = new CliBuilder()
cli.width = 100
cli.usage = "show-sas7bdat.groovy [--help] SAS7BDAT_FILE"
cli.header = "Scans a SAS7BDAT and prints information about its structure"

cli.h(longOpt: 'help', 'Shows the usage information for this tool.')

def options = cli.parse(args)
if (!options) {
    // The options could not be parsed.
    System.exit(1)
}
if (options.help) {
    // Help was explicitly requested.
    cli.usage()
    System.exit(0)
}
if (options.arguments().size() == 0) {
    println "ERROR: missing SAS7BDAT_FILE argument"
    cli.usage()
    System.exit(1)
} else if (1 < options.arguments().size()) {
    println "ERROR: unexpected arguments"
    cli.usage()
    System.exit(1)
}

// Parse the file
def sas7BdatFile = new File(options.arguments()[0])
if (!sas7BdatFile.exists()) {
    println "ERROR: $sas7BdatFile does not exist"
    System.exit(1)
}

sas7BdatFile.withDataInputStream { inputStream ->
    // The file starts at the header.
    Header header = Header.parse(sas7BdatFile, inputStream)
    println "${formatOffset(0)} Header"
    println "  ${formatOffset(200)} Header Size = $header.headerSize (${'%#X'.formatted(header.headerSize)})"
    println "  ${formatOffset(204)} Page Size   = $header.pageSize (${'%#X'.formatted(header.pageSize)})"
    println "  ${formatOffset(208)} Total Pages = $header.totalPages"

    int fileOffset = header.headerSize

    // Keep reading pages until we've processed the entire file.
    ParsedState parsedState = new ParsedState()
    int pageNumber = 1
    byte[] page = new byte[header.pageSize]
    int bytesRead = inputStream.read(page)
    while (bytesRead == header.pageSize) {
        println "\n${formatOffset(fileOffset)} Page $pageNumber"
        printPage(fileOffset, header.bitSize, page, parsedState)

        // Read the next page
        Arrays.fill(page, (byte) 0)
        bytesRead = inputStream.read(page)
        fileOffset += header.pageSize
        pageNumber++
    }

    if (bytesRead != -1) {
        println "file is truncated"
    }
}