#!/usr/bin/env groovy
///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
// This script prints the structure of a SAS7BDAT file.
///////////////////////////////////////////////////////////////////////////////

import java.nio.charset.StandardCharsets

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


static int toggleEndian(int i) {
    ((i >> 24) & 0x000000FF) |
            ((i >> 8) & 0x0000FF00) |
            ((i << 8) & 0x00FF0000) |
            ((i << 24) & 0xFF000000)
}

static short toggleEndian(short s) {
    ((s >> 8) & 0x00FF) | (s << 8)
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
            return (0xFF00000000000000L & (page[offset64 + 7] << 56)) |
                   (0x00FF000000000000L & (page[offset64 + 6] << 48)) |
                   (0x0000FF0000000000L & (page[offset64 + 5] << 40)) |
                   (0x000000FF00000000L & (page[offset64 + 4] << 32)) |
                   (0x00000000FF000000L & (page[offset64 + 3] << 24)) |
                   (0x0000000000FF0000L & (page[offset64 + 2] << 16)) |
                   (0x000000000000FF00L & (page[offset64 + 1] << 8)) |
                   (0x00000000000000FFL & (page[offset64 + 0]))
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

    void printSubheaderField8(long offset32, long offset64, String text) {
        long value = readLong(offset32, offset64)
        printSubheaderField(offset32, offset64, text, value)
    }

    void printSubheaderField4(long offset32, long offset64, String text) {
        int value = readInt(offset32, offset64)
        printSubheaderField(offset32, offset64, text, "0x%X".formatted(value))
    }

    void printSubheaderField2(long offset32, long offset64, String text) {
        short value = readShort(offset32, offset64)
        printSubheaderField(offset32, offset64, text, value)
    }

    void printSubheaderFieldU2(long offset32, long offset64, String text) {
        int value = readUnsignedShort(offset32, offset64)
        printSubheaderField(offset32, offset64, text, value)
    }
}

class ColumnText {
    List<byte[]> columnTextSubheaders = []

    void add(byte[] page, long offset, long length) {
        def columnTextSubheader = Arrays.copyOfRange(page, (int) offset, (int) (offset + length))
        columnTextSubheaders.add(columnTextSubheader)
    }

    String getText(int bitSize, int textSubheaderIndex, int textSubheaderOffset, int textLength) {
        String text
        if (0 <= textSubheaderIndex && textSubheaderIndex < columnTextSubheaders.size()) {
            byte[] columnTextSubheader = columnTextSubheaders[textSubheaderIndex]
            int offsetInColumnTextSubheader = textSubheaderOffset + (bitSize == 32 ? 4 : 8) // offset doesn't include signature
            if (0 <= offsetInColumnTextSubheader && offsetInColumnTextSubheader + textLength <= columnTextSubheader.length) {
                text = new String(columnTextSubheader, offsetInColumnTextSubheader, textLength, StandardCharsets.UTF_8)
            }
        }
        return text
    }
}

static void printColumnText(ColumnText columnText, pageReader, long offset32, long offset64, String textLabel) {
    // Determine the location of the text.
    short textSubheaderIndex  = pageReader.readShort(offset32 + 0, offset64 + 0)
    short textSubheaderOffset = pageReader.readShort(offset32 + 2, offset64 + 2)
    short length              = pageReader.readShort(offset32 + 4, offset64 + 4)

    // Read text from the Column Text subheader.
    String text = columnText.getText(pageReader.bitSize, textSubheaderIndex, textSubheaderOffset, length)

    // Print the text.
    String displayedText = text != null ? "\"$text\"" : '<malformed>'
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
}

void printPage(int fileOffset, int bitSize, byte[] page, ParsedState parsedState) {
    def pageReader = new PageReader(bitSize, fileOffset, page)
    int SIGNATURE_SIZE = bitSize == 32 ? 4 : 8
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
                            pageReader.printSubheaderField8(subheaderOffset + 4, subheaderOffset + 8,   "Unknown Field At offset 8")
                            pageReader.printSubheaderField8(subheaderOffset + 8, subheaderOffset + 16,  "Unknown Field At offset 16")
                            pageReader.printSubheaderField8(subheaderOffset + 12, subheaderOffset + 24, "Unknown Field At offset 24")
                            pageReader.printSubheaderField8(subheaderOffset + 16, subheaderOffset + 32, "Unknown Field At offset 32")
                            pageReader.printSubheaderField8(subheaderOffset + 20, subheaderOffset + 40, "Row Length")
                            pageReader.printSubheaderField8(subheaderOffset + 24, subheaderOffset + 48, "Total Rows")
                            pageReader.printSubheaderField8(subheaderOffset + 36, subheaderOffset + 72, "Total Column Subheaders On First Page")
                            pageReader.printSubheaderField8(subheaderOffset + 40, subheaderOffset + 80, "Total Column Subheaders On Second Page")
                            pageReader.printSubheaderField8(subheaderOffset + 44, subheaderOffset + 88, "Unknown Field At offset 88")
                            pageReader.printSubheaderField8(subheaderOffset + 48, subheaderOffset + 96, "Aggregate Variable Name Size") // 64 is wrong offset?
                            pageReader.printSubheaderField8(subheaderOffset + 52, subheaderOffset + 104, "Page Size")
                            pageReader.printSubheaderField8(subheaderOffset + 60, subheaderOffset + 120, "Max Row Count On Mixed Page")
                            pageReader.printSubheaderField4(subheaderOffset + 220, subheaderOffset + 440, "Page Sequence Number")
                            pageReader.printSubheaderField4(subheaderOffset + 264, subheaderOffset + 512, "Unknown Field At offset 512")
                            pageReader.printSubheaderField4(subheaderOffset + 268, subheaderOffset + 520, "Unknown Field At offset 520")
                            pageReader.printSubheaderField8(subheaderOffset + 272, subheaderOffset + 528, "Total Metadata Pages")
                            pageReader.printSubheaderField8(subheaderOffset + 276, subheaderOffset + 536, "Subheaders On Last Metadata Page")
                            pageReader.printSubheaderField8(subheaderOffset + 280, subheaderOffset + 544, "First Data Record, Page Index")
                            pageReader.printSubheaderField8(subheaderOffset + 284, subheaderOffset + 552, "First Data Record, Record Index")
                            pageReader.printSubheaderField8(subheaderOffset + 288, subheaderOffset + 560, "Last Data Record, Page Index")
                            pageReader.printSubheaderField8(subheaderOffset + 292, subheaderOffset + 568, "Last Data Record, Record Index")
                            pageReader.printSubheaderField8(subheaderOffset + 296, subheaderOffset + 576, "First Column Subheader, Page Index")
                            pageReader.printSubheaderField8(subheaderOffset + 300, subheaderOffset + 584, "First Column Subheader, Record Index")
                            pageReader.printSubheaderField4(subheaderOffset + 344, subheaderOffset + 672, "Unknown Field At offset 672") // should be compression offset
                            pageReader.printSubheaderField4(subheaderOffset + 348, subheaderOffset + 676, "Unknown Field At offset 676") // should be compression offset
                            pageReader.printSubheaderField2(subheaderOffset + 350, subheaderOffset + 678, "Dataset Label, Text Subheader Index")
                            pageReader.printSubheaderField2(subheaderOffset + 352, subheaderOffset + 680, "Dataset Label, Offset")
                            pageReader.printSubheaderField2(subheaderOffset + 354, subheaderOffset + 682, "Dataset Label, Length")
                            pageReader.printSubheaderField2(subheaderOffset + 356, subheaderOffset + 684, "Dataset Type, Text Subheader Index")
                            pageReader.printSubheaderField2(subheaderOffset + 358, subheaderOffset + 686, "Dataset Type, Offset")
                            pageReader.printSubheaderField8(subheaderOffset + 360, subheaderOffset + 688, "Dataset Type, Length")
                            pageReader.printSubheaderField2(subheaderOffset + 368, subheaderOffset + 696, "Unknown Field At offset 696")
                            pageReader.printSubheaderField2(subheaderOffset + 370, subheaderOffset + 698, "Unknown Field At offset 698")
                            pageReader.printSubheaderField2(subheaderOffset + 372, subheaderOffset + 700, "Unknown Field At offset 700")
                            pageReader.printSubheaderField2(subheaderOffset + 374, subheaderOffset + 702, "Unknown Field At offset 702")
                            pageReader.printSubheaderField2(subheaderOffset + 376, subheaderOffset + 704, "Unknown Field At offset 704")
                            pageReader.printSubheaderField2(subheaderOffset + 378, subheaderOffset + 706, "Unknown Field At offset 706")
                            pageReader.printSubheaderField2(subheaderOffset + 416, subheaderOffset + 744, "Unknown Field At offset 744")
                            pageReader.printSubheaderField2(subheaderOffset + 418, subheaderOffset + 746, "Unknown Field At offset 746")
                            pageReader.printSubheaderField2(subheaderOffset + 420, subheaderOffset + 748, "Total ColumnText subheaders")
                            pageReader.printSubheaderField2(subheaderOffset + 422, subheaderOffset + 750, "Max Variable Name Size")
                            pageReader.printSubheaderField2(subheaderOffset + 424, subheaderOffset + 752, "Max Variable Label Size")
                            pageReader.printSubheaderFieldU2(subheaderOffset + 438, subheaderOffset + 766, "Max Observations On Data Page")
                            pageReader.printSubheaderFieldU2(subheaderOffset + 444, subheaderOffset + 776, "Total Observations")
                            pageReader.printSubheaderField8(subheaderOffset + 464, subheaderOffset + 792, "Unknown Field At offset 792")

                            parsedState.rowSizeInBytes = pageReader.readLong(subheaderOffset + 20, subheaderOffset + 40)
                            break

                        case SubheaderSignature.COLUMN_SIZE:
                            pageReader.printSubheaderField8(subheaderOffset + 4, subheaderOffset + 8, "Total Variables")
                            break

                        case SubheaderSignature.SUBHEADER_COUNTS:
                            pageReader.printSubheaderField8(subheaderOffset + 4,  subheaderOffset + 8, "Max Variable-Sized Subheader Body Size")
                            pageReader.printSubheaderField8(subheaderOffset + 8,  subheaderOffset + 16, "Unknown Field At Offset 16")
                            pageReader.printSubheaderField8(subheaderOffset + 12, subheaderOffset + 24, "Unknown Field At Offset 24")
                            pageReader.printSubheaderField8(subheaderOffset + 16, subheaderOffset + 32, "Unknown Field At Offset 32")
                            pageReader.printSubheaderField8(subheaderOffset + 60, subheaderOffset + 112, "Unknown Field At Offset 112")

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

                            printColumnText(parsedState.columnText, pageReader, subheaderOffset + 28, subheaderOffset + 40, "Output Format Name")
                            pageReader.printSubheaderField2(subheaderOffset + 26, subheaderOffset + 24, "Output Format Width")
                            pageReader.printSubheaderField2(subheaderOffset + 18, subheaderOffset + 26, "Output Format Precision")

                            printColumnText(parsedState.columnText, pageReader, subheaderOffset + 34, subheaderOffset + 46, "Input Format Name")
                            pageReader.printSubheaderField2(subheaderOffset + 20, subheaderOffset + 28, "Input Format Width")
                            pageReader.printSubheaderField2(subheaderOffset + 22, subheaderOffset + 30, "Input Format Precision")

                            printColumnText(parsedState.columnText, pageReader, subheaderOffset + 40, subheaderOffset + 52, "Label")
                            break

                        case SubheaderSignature.COLUMN_MASK:
                            break

                        case SubheaderSignature.COLUMN_ATTRS:
                            pageReader.printSubheaderField2(subheaderOffset + 4, subheaderOffset + 8, "Length Remaining In Subheader")
                            int totalVariables = (pageReader.readShort(subheaderOffset + 4, subheaderOffset + 8) - SIGNATURE_SIZE) / 16
                            for (int variableIndex = 0; variableIndex < totalVariables; variableIndex++) {
                                int vectorOffset32 = subheaderOffset + 12 + variableIndex * 12
                                int vectorOffset64 = subheaderOffset + 16 + variableIndex * 16
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
                            pageReader.printSubheaderField2(subheaderOffset + 4, subheaderOffset + 8, "Size Of Subheader")

                            // Print the text like hexdump -Cv
                            println "    Text:"
                            int textOffset = bitSize == 32 ? 8 : 16
                            hexdumpCv(fileOffset, page, subheaderOffset + textOffset, subheaderLength - textOffset)

                            // Save the text for later use.
                            parsedState.columnText.add(page, subheaderOffset, subheaderLength)
                            break

                        case SubheaderSignature.COLUMN_LIST:
                            pageReader.printSubheaderField2(subheaderOffset + 4, subheaderOffset + 8, "Size of Body")
                            pageReader.printSubheaderField2(subheaderOffset + 6, subheaderOffset + 10, "Unknown Field at offset 10")
                            // TODO: 16-24 should be a Long.  The 32-bit values are repeated because of this
                            pageReader.printSubheaderFieldU2(subheaderOffset + 12, subheaderOffset + 16, "Unknown Field at offset 16")
                            pageReader.printSubheaderFieldU2(subheaderOffset + 14, subheaderOffset + 18, "Unknown Field at offset 18")
                            pageReader.printSubheaderFieldU2(subheaderOffset + 16, subheaderOffset + 20, "Unknown Field at offset 20")
                            pageReader.printSubheaderFieldU2(subheaderOffset + 18, subheaderOffset + 22, "Unknown Field at offset 22")
                            pageReader.printSubheaderFieldU2(subheaderOffset + 16, subheaderOffset + 24, "Total Variables")
                            pageReader.printSubheaderFieldU2(subheaderOffset + 18, subheaderOffset + 26, "Total Columns")
                            pageReader.printSubheaderFieldU2(subheaderOffset + 20, subheaderOffset + 28, "Unknown Field at offset 28")
                            pageReader.printSubheaderFieldU2(subheaderOffset + 22, subheaderOffset + 30, "Unknown Field at offset 30")

                            int totalColumns = pageReader.readUnsignedShort(subheaderOffset + 18, subheaderOffset + 26)
                            for (int columnIndex = 0; columnIndex < totalColumns; columnIndex++) {
                                int vectorOffset32 = subheaderOffset + 30 + columnIndex * 2
                                int vectorOffset64 = subheaderOffset + 38 + columnIndex * 2
                                if (subheaderLength < vectorOffset64 - subheaderOffset + 2) {
                                    println "     <subheader ends unexpectedly>"
                                    break
                                }
                                pageReader.printSubheaderField2(vectorOffset32, vectorOffset64, "Column #${columnIndex + 1} Value")
                            }
                            break

                        case SubheaderSignature.COLUMN_NAME:
                            pageReader.printSubheaderField8(subheaderOffset + 4, subheaderOffset + 8, "Length Remaining In Subheader")
                            int totalVariables = pageReader.readShort(subheaderOffset + 4, subheaderOffset + 8) / 8 - 1
                            for (int variableIndex = 0; variableIndex < totalVariables; variableIndex++) {
                                int vectorOffset32 = subheaderOffset + 12 + variableIndex * 8
                                int vectorOffset64 = subheaderOffset + 16 + variableIndex * 8
                                short textSubheaderIndex  = pageReader.readShort(vectorOffset32 + 0, vectorOffset64 + 0)
                                short textSubheaderOffset = pageReader.readShort(vectorOffset32 + 2, vectorOffset64 + 2)
                                short nameLength          = pageReader.readShort(vectorOffset32 + 4, vectorOffset64 + 4)

                                // Read the name from the column text blocks.
                                String name = parsedState.columnText.getText(bitSize, textSubheaderIndex, textSubheaderOffset, nameLength)

                                parsedState.totalVariableNameSubheaders++
                                String displayedName = name ? "\"$name\"" : '<malformed>'
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

        // Print the data
        if (pageType == PageType.DATA || pageType == PageType.MIXED) {
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
    int bitSize
    int headerSize
    int pageSize
    int totalPages
    try {
        // Read the "alignment" field, which indicates if the file is 32-bits or 64-bits.
        // This script only processes 64-bit SAS files.
        inputStream.skipNBytes(32)
        byte alignment = inputStream.readByte()
        switch (alignment) {
            case 0x22:
                bitSize = 32
                break

            case 0x33:
                bitSize = 64
                break

            default:
                println "ERROR: $sas7BdatFile has an alignment that this script can't handle: $alignment"
                System.exit(1)
        }

        headerSizeOffset = bitSize == 32 ? 196 : 200
        inputStream.skipNBytes(headerSizeOffset - 33)
        headerSize = toggleEndian(inputStream.readInt())
        pageSize = toggleEndian(inputStream.readInt())
        totalPages = toggleEndian(inputStream.readInt())

        // Jump to the end of the header, which should be the first metadata page.
        int currentOffset = headerSizeOffset + 4 + 4 + 4
        inputStream.skipNBytes(headerSize - currentOffset)

    } catch (EOFException exception) {
        println "ERROR: $sas7BdatFile is too small to have a legal header"
        System.exit(1)
    }

    println "${formatOffset(0)} Header"
    println "  ${formatOffset(200)} Header Size = $headerSize (${'%#X'.formatted(headerSize)})"
    println "  ${formatOffset(204)} Page Size   = $pageSize (${'%#X'.formatted(pageSize)})"
    println "  ${formatOffset(208)} Total Pages = $totalPages"

    int fileOffset = headerSize

    // Keep reading pages until we've processed the entire file.
    ParsedState parsedState = new ParsedState()
    int pageNumber = 1
    byte[] page = new byte[pageSize]
    int bytesRead = inputStream.read(page)
    while (bytesRead == pageSize) {
        println "\n${formatOffset(fileOffset)} Page $pageNumber"
        printPage(fileOffset, bitSize, page, parsedState)

        // Read the next page
        Arrays.fill(page, (byte) 0)
        bytesRead = inputStream.read(page)
        fileOffset += pageSize
        pageNumber++
    }

    if (bytesRead != -1) {
        println "file is truncated"
    }
}