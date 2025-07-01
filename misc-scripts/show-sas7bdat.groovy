#!/usr/bin/env groovy

import java.nio.charset.StandardCharsets

///////////////////////////////////////////////////////////////////////////////
// This script prints the structure of a SAS7BDAT file.
///////////////////////////////////////////////////////////////////////////////

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

static short readShort(byte[] data, long offset) {
    ((0xFF00) & data[offset + 1] << 8) | (0x00FF & data[offset])
}

static int readUnsignedShort(byte[] data, long offset) {
    readShort(data, offset) & 0xFFFF
}

static int readInt(byte[] data, long offset) {
    (0xFF000000 & (data[offset + 3] << 24)) |
            (0x00FF0000 & (data[offset + 2] << 16)) |
            (0x0000FF00 & (data[offset + 1] << 8)) |
            (0x000000FF & (data[offset + 0]))
}

static long readLong(byte[] data, long offset) {
    (0xFF00000000000000L & (data[offset + 7] << 56)) |
            (0x00FF000000000000L & (data[offset + 6] << 48)) |
            (0x0000FF0000000000L & (data[offset + 5] << 40)) |
            (0x000000FF00000000L & (data[offset + 4] << 32)) |
            (0x00000000FF000000L & (data[offset + 3] << 24)) |
            (0x0000000000FF0000L & (data[offset + 2] << 16)) |
            (0x000000000000FF00L & (data[offset + 1] << 8)) |
            (0x00000000000000FFL & (data[offset + 0]))
}

static String formatOffset(offset) {
    "(0x%X)".formatted(offset)
}

static void printSubheaderField(fileOffset, String text, value) {
    String spacer = text.length() < 40 ? ' '.repeat(40 - text.length()) : ''
    println "    ${formatOffset(fileOffset)} $text$spacer= $value"
}

static void printSubheaderField8(byte[] page, fileOffset, long pageOffset, String text) {
    long value = readLong(page, pageOffset)
    printSubheaderField(fileOffset + pageOffset, text, value)
}

static void printSubheaderField4(byte[] page, fileOffset, long pageOffset, String text) {
    int value = readInt(page, pageOffset)
    printSubheaderField(fileOffset + pageOffset, text, "0x%X".formatted(value))
}

static void printSubheaderField2(byte[] page, fileOffset, long pageOffset, String text) {
    short value = readShort(page, pageOffset)
    printSubheaderField(fileOffset + pageOffset, text, value)
}

static void printSubheaderFieldU2(byte[] page, fileOffset, long pageOffset, String text) {
    int value = readUnsignedShort(page, pageOffset)
    printSubheaderField(fileOffset + pageOffset, text, value)
}


class ColumnText {
    List<byte[]> columnTextSubheaders = []

    void add(byte[] page, long offset, long length) {
        def columnTextSubheader = Arrays.copyOfRange(page, (int) offset, (int) (offset + length))
        columnTextSubheaders.add(columnTextSubheader)
    }

    String getText(int textSubheaderIndex, int textSubheaderOffset, int textLength) {
        String text
        if (0 <= textSubheaderIndex && textSubheaderIndex < columnTextSubheaders.size()) {
            byte[] columnTextSubheader = columnTextSubheaders[textSubheaderIndex]
            int offsetInColumnTextSubheader = textSubheaderOffset + 8 // offset doesn't include signature
            if (0 <= offsetInColumnTextSubheader && offsetInColumnTextSubheader + textLength <= columnTextSubheader.length) {
                text = new String(columnTextSubheader, offsetInColumnTextSubheader, textLength, StandardCharsets.UTF_8)
            }
        }
        return text
    }
}

static void printColumnText(ColumnText columnText, byte[] page, long fileOffset, long subheaderOffset, String textLabel) {
    // Determine the location of the text.
    short textSubheaderIndex = readShort(page, subheaderOffset + 0)
    short textSubheaderOffset = readShort(page, subheaderOffset + 2)
    short length = readShort(page, subheaderOffset + 4)

    // Read text from the Column Text subheader.
    String text = columnText.getText(textSubheaderIndex, textSubheaderOffset, length)

    // Print the text.
    String displayedText = text != null ? "\"$text\"" : '<malformed>'
    println "    $textLabel = ${displayedText}"
    println "      ${formatOffset(fileOffset + subheaderOffset + 0)} $textLabel Text Subheader Index     = ${textSubheaderIndex}"
    println "      ${formatOffset(fileOffset + subheaderOffset + 2)} $textLabel Offset In Text Subheader = ${textSubheaderOffset}"
    println "      ${formatOffset(fileOffset + subheaderOffset + 4)} $textLabel Length                   = ${length}"
}

static def toPrintingChar(byte ch) {
    // Printable ASCII characters range from 32 (space) to 126 (tilde)
    (char) (32 <= ch && ch <= 126 ? ch : '.')
}

def hexdumpCv(int fileOffset, byte[] page, int pageOffset, int length) {
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

void printPage(int fileOffset, byte[] page, ParsedState parsedState) {
    try {
        long unknownField = readLong(page, 24)
        short pageType = readShort(page, 32)
        int totalBlocks = readUnsignedShort(page, 34) // treat as "unsigned short"
        short totalSubheaders = readShort(page, 36)

        println "  ${formatOffset(fileOffset + 24)} Offset 24        = $unknownField"
        println "  ${formatOffset(fileOffset + 32)} Type             = ${PageType.toString(pageType)}"
        println "  ${formatOffset(fileOffset + 34)} Total Blocks     = $totalBlocks"
        println "  ${formatOffset(fileOffset + 36)} Total Subheaders = ${totalSubheaders < 0 ? "$totalSubheaders <--- malformed" : totalSubheaders}"

        // Read each subheader
        int indexOffset = 40
        if (0 < totalSubheaders) {
            for (int i = 0; i < totalSubheaders; i++) {
                long subheaderOffset = readLong(page, indexOffset + i * 24 + 0)
                long subheaderLength = readLong(page, indexOffset + i * 24 + 8)
                byte compressionCode = page[indexOffset + i * 24 + 16]
                byte typeCode = page[indexOffset + i * 24 + 17]

                // The "deleted" subheaders don't have any length, so they also don't have a signature.
                long subheaderSignature = (subheaderLength == 0) ? 0 : readLong(page, subheaderOffset)
                println "\n  ${formatOffset(fileOffset + subheaderOffset)} ${SubheaderSignature.toString(subheaderSignature)} Subheader (length=$subheaderLength, compression=$compressionCode, type=$typeCode)"

                // Ignore subheaders that are marked as deleted.
                final int COMPRESSION_CODE_DELETED = 1
                if (compressionCode != COMPRESSION_CODE_DELETED) {
                    switch (subheaderSignature) {
                        case SubheaderSignature.ROW_SIZE:
                            printSubheaderField8(page, fileOffset, subheaderOffset + 8, "Unknown Field At offset 8")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 16, "Unknown Field At offset 16")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 24, "Unknown Field At offset 24")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 32, "Unknown Field At offset 32")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 40, "Row Length")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 48, "Total Rows")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 72, "Total Column Subheaders On First Page")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 80, "Total Column Subheaders On Second Page")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 88, "Unknown Field At offset 88")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 96, "Aggregate Variable Name Size")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 104, "Page Size")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 120, "Max Row Count On Mixed Page")
                            printSubheaderField4(page, fileOffset, subheaderOffset + 440, "Page Sequence Number")
                            printSubheaderField4(page, fileOffset, subheaderOffset + 512, "Unknown Field At offset 512")
                            printSubheaderField4(page, fileOffset, subheaderOffset + 520, "Unknown Field At offset 520")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 528, "Total Metadata Pages")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 536, "Subheaders On Last Metadata Page")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 544, "First Data Record, Page Index")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 552, "First Data Record, Record Index")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 560, "Last Data Record, Page Index")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 568, "Last Data Record, Record Index")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 576, "First Column Subheader, Page Index")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 584, "First Column Subheader, Record Index")
                            printSubheaderField4(page, fileOffset, subheaderOffset + 672, "Unknown Field At offset 672")
                            printSubheaderField4(page, fileOffset, subheaderOffset + 676, "Unknown Field At offset 676")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 678, "Dataset Label, Text Subheader Index")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 680, "Dataset Label, Offset")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 682, "Dataset Label, Length")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 684, "Dataset Type, Text Subheader Index")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 686, "Dataset Type, Offset")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 688, "Dataset Type, Length")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 696, "Unknown Field At offset 696")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 698, "Unknown Field At offset 698")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 700, "Unknown Field At offset 700")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 702, "Unknown Field At offset 702")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 704, "Unknown Field At offset 704")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 706, "Unknown Field At offset 706")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 744, "Unknown Field At offset 744")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 746, "Unknown Field At offset 746")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 748, "Total ColumnText subheaders")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 750, "Max Variable Name Size")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 752, "Max Variable Label Size")
                            printSubheaderFieldU2(page, fileOffset, subheaderOffset + 766, "Max Observations On Data Page")
                            printSubheaderFieldU2(page, fileOffset, subheaderOffset + 776, "Total Observations")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 792, "Unknown Field At offset 792")

                            parsedState.rowSizeInBytes = readLong(page, subheaderOffset + 40)
                            break

                        case SubheaderSignature.COLUMN_SIZE:
                            printSubheaderField8(page, fileOffset, subheaderOffset + 8, "Total Variables")
                            break

                        case SubheaderSignature.SUBHEADER_COUNTS:
                            printSubheaderField8(page, fileOffset, subheaderOffset + 8, "Max Variable-Sized Subheader Body Size")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 16, "Unknown Field At Offset 16")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 24, "Unknown Field At Offset 24")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 32, "Unknown Field At Offset 32")
                            printSubheaderField8(page, fileOffset, subheaderOffset + 112, "Unknown Field At Offset 112")

                            for (int j = 0; j < 12; j++) {
                                long vectorOffset = subheaderOffset + 120 + j * 40
                                int signature = readLong(page, vectorOffset)
                                println("    ${formatOffset(fileOffset + vectorOffset)} Counts for ${SubheaderSignature.toString(signature)} Subheader")
                                printSubheaderField8(page, fileOffset, vectorOffset + 8, "Page Of First Appearance")
                                printSubheaderField8(page, fileOffset, vectorOffset + 16, "Position Of First Appearance")
                                printSubheaderField8(page, fileOffset, vectorOffset + 24, "Page Of Last Appearance")
                                printSubheaderField8(page, fileOffset, vectorOffset + 32, "Position Of Last Appearance")
                            }
                            break

                        case SubheaderSignature.COLUMN_FORMAT:
                            parsedState.lastColumnFormat++
                            println "    (Variable #$parsedState.lastColumnFormat)"

                            printColumnText(parsedState.columnText, page, fileOffset, subheaderOffset + 40, "Output Format Name")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 24, "Output Format Width")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 26, "Output Format Precision")

                            printColumnText(parsedState.columnText, page, fileOffset, subheaderOffset + 46, "Input Format Name")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 28, "Input Format Width")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 30, "Input Format Precision")

                            printColumnText(parsedState.columnText, page, fileOffset, subheaderOffset + 52, "Label")
                            break

                        case SubheaderSignature.COLUMN_MASK:
                            break

                        case SubheaderSignature.COLUMN_ATTRS:
                            printSubheaderField2(page, fileOffset, subheaderOffset + 8, "Length Remaining In Subheader")
                            int totalVariables = (readShort(page, subheaderOffset + 8) - 8) / 16
                            for (int variableIndex = 0; variableIndex < totalVariables; variableIndex++) {
                                int vectorOffset = subheaderOffset + 16 + variableIndex * 16
                                long columnOffsetInDataRow = readLong(page, vectorOffset + 0)
                                int columnLength = readInt(page, vectorOffset + 8)
                                short nameFlag = readShort(page, vectorOffset + 12)
                                byte columnType = page[vectorOffset + 14]

                                parsedState.lastColumnAttribute++
                                println "    ${formatOffset(fileOffset + vectorOffset)} Variable #${parsedState.lastColumnAttribute}"
                                println "      ${formatOffset(fileOffset + vectorOffset + 0)} Offset In Data Row = ${columnOffsetInDataRow}"
                                println "      ${formatOffset(fileOffset + vectorOffset + 8)} Length In Data Row = ${columnLength}"
                                println "      ${formatOffset(fileOffset + vectorOffset + 12)} Name Flag          = ${"%#x".formatted(nameFlag)}"
                                println "      ${formatOffset(fileOffset + vectorOffset + 14)} Column Type        = ${columnTypeToString(columnType)}"
                            }
                            break

                        case SubheaderSignature.COLUMN_TEXT:
                            // The variable names, labels, and formats are all concatenated into column texts.
                            printSubheaderField2(page, fileOffset, subheaderOffset + 8, "Size Of Subheader")

                            // Print the text like hexdump -Cv
                            println "    Text:"
                            int textOffset = subheaderOffset + 16
                            hexdumpCv(fileOffset, page, textOffset, (int) subheaderLength - 16)

                            // Save the text for later use.
                            parsedState.columnText.add(page, subheaderOffset, subheaderLength)
                            break

                        case SubheaderSignature.COLUMN_LIST:
                            printSubheaderField2(page, fileOffset, subheaderOffset + 8, "Size of Body")
                            printSubheaderField2(page, fileOffset, subheaderOffset + 10, "Unknown Field at offset 10")
                            printSubheaderFieldU2(page, fileOffset, subheaderOffset + 16, "Unknown Field at offset 16")
                            printSubheaderFieldU2(page, fileOffset, subheaderOffset + 18, "Unknown Field at offset 18")
                            printSubheaderFieldU2(page, fileOffset, subheaderOffset + 20, "Unknown Field at offset 20")
                            printSubheaderFieldU2(page, fileOffset, subheaderOffset + 22, "Unknown Field at offset 22")
                            printSubheaderFieldU2(page, fileOffset, subheaderOffset + 24, "Total Variables")
                            printSubheaderFieldU2(page, fileOffset, subheaderOffset + 26, "Total Columns")
                            printSubheaderFieldU2(page, fileOffset, subheaderOffset + 28, "Unknown Field at offset 28")
                            printSubheaderFieldU2(page, fileOffset, subheaderOffset + 30, "Unknown Field at offset 30")

                            int totalColumns = readUnsignedShort(page, subheaderOffset + 26)
                            for (int columnIndex = 0; columnIndex < totalColumns; columnIndex++) {
                                int vectorOffset = subheaderOffset + 38 + columnIndex * 2
                                if (subheaderLength < vectorOffset - subheaderOffset + 2) {
                                    println "     <subheader ends unexpectedly>"
                                    break
                                }
                                printSubheaderField2(page, fileOffset, vectorOffset, "Column #${columnIndex + 1} Value")
                            }
                            break

                        case SubheaderSignature.COLUMN_NAME:
                            printSubheaderField8(page, fileOffset, subheaderOffset + 8, "Length Remaining In Subheader")
                            int totalVariables = readShort(page, subheaderOffset + 8) / 8 - 1
                            for (int variableIndex = 0; variableIndex < totalVariables; variableIndex++) {
                                int vectorOffset = subheaderOffset + 16 + variableIndex * 8
                                short textSubheaderIndex = readShort(page, vectorOffset + 0)
                                short textSubheaderOffset = readShort(page, vectorOffset + 2)
                                short nameLength = readShort(page, vectorOffset + 4)

                                // Read the name from the column text blocks.
                                String name = parsedState.columnText.getText(textSubheaderIndex, textSubheaderOffset, nameLength)

                                parsedState.totalVariableNameSubheaders++
                                String displayedName = name ? "\"$name\"" : '<malformed>'
                                println "    ${formatOffset(fileOffset + vectorOffset)} Variable #${parsedState.totalVariableNameSubheaders}: $displayedName"
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
    int headerSize
    int pageSize
    int totalPages
    try {
        // Read the "alignment" field, which indicates if the file is 32-bits or 64-bits.
        // This script only processes 64-bit SAS files.
        inputStream.skipNBytes(32)
        byte alignment = inputStream.readByte()
        if (alignment != 0x33) {
            println "ERROR: $sas7BdatFile has an alignment that this script can't handle: $alignment"
            System.exit(1)
        }

        inputStream.skipNBytes(200 - 33)
        headerSize = toggleEndian(inputStream.readInt())
        pageSize = toggleEndian(inputStream.readInt())
        totalPages = toggleEndian(inputStream.readInt())
    } catch (EOFException exception) {
        println "ERROR: $sas7BdatFile is too small to have a legal header"
        System.exit(1)
    }

    println "${formatOffset(0)} Header"
    println "  ${formatOffset(200)} Header Size = $headerSize (${'%#X'.formatted(headerSize)})"
    println "  ${formatOffset(204)} Page Size   = $pageSize (${'%#X'.formatted(pageSize)})"
    println "  ${formatOffset(208)} Total Pages = $totalPages"

    // Jump to the end of the header, which should be the first metadata page.
    int currentOffset = 200 + 4 + 4 + 4
    inputStream.skipNBytes(headerSize - currentOffset)
    int fileOffset = headerSize

    // Keep reading pages until we've processed the entire file.
    ParsedState parsedState = new ParsedState()
    int pageNumber = 1
    byte[] page = new byte[pageSize]
    int bytesRead = inputStream.read(page)
    while (bytesRead == pageSize) {
        println "\n${formatOffset(fileOffset)} Page $pageNumber"
        printPage(fileOffset, page, parsedState)

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