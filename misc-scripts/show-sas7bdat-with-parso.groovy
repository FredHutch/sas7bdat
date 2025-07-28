#!/usr/bin/env groovy
///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
// This script dumps the contents of a SAS7BDAT file using parso library
///////////////////////////////////////////////////////////////////////////////

@GrabConfig(systemClassLoader=true)
@Grab('com.epam:parso:2.0.14')
@Grab('org.slf4j:slf4j-simple:1.7.36') // Use an old version to be compatible with parso

import org.slf4j.Logger.*

import com.epam.parso.CSVDataWriter
import com.epam.parso.SasFileReader
import com.epam.parso.Column
import com.epam.parso.impl.CSVDataWriterImpl
import com.epam.parso.impl.SasFileReaderImpl

import java.nio.file.Files
import java.nio.file.Path


def cli = new CliBuilder()
cli.width  = 100
cli.usage  = "show-sas7bdat-with-parso.groovy SAS7BDAT"
cli.header = "Dumps the contents of a SAS7BDAT dataset the Parso library."

cli.h(longOpt: 'help', 'Shows the usage information for this tool.')

def options = cli.parse(args)
if (!options || options.help || options.arguments().size() == 0) {
    // The options could not be parsed, help was explicitly requested, or no filename was given.
    cli.usage()
    System.exit(1)
}
if (1 < options.arguments().size()) {
    println "ERROR: extra argument given"
    cli.usage()
    System.exit(1)
}

Path filename = Path.of(options.arguments()[0])

if (!Files.exists(filename)) {
    println "ERROR: file $filename does not exist"
    System.exit(1)
}

// Read the dataset with parso
try (InputStream inputStream = Files.newInputStream(filename)) {
    SasFileReader sasFileReader = new SasFileReaderImpl(inputStream)

    sasFileReader.sasFileProperties.with {
        println """|Properties:
                   |  Bit:                ${u64 ? 64 : 32}
                   |  Compression Method: ${compressionMethod ?: "Uncompressed"}
                   |  Endianness:         ${endianness == 1 ? "Little" : "Big"} Endian
                   |  Encoding:           $encoding
                   |  Name:               $name
                   |  File Type:          $fileType
                   |  File Label:         $fileLabel
                   |  Created:            $dateCreated
                   |  Modified:           $dateModified
                   |  SAS Version:        $sasRelease
                   |  Server Type:        $serverType
                   |  OS Name:            $osName
                   |  OS Type:            $osType
                   |  Header Length:      $headerLength
                   |  Page Length:        $pageLength
                   |  Row Count:          $rowCount
                   |  Row Length:         $rowLength
                   |  Deleted Row Count:  $deletedRowCount
                   |  Mix Page Row Count: $mixPageRowCount
                   |  Column Count:       $columnsCount
                   """.stripMargin()
    }

    println "Columns:"
    sasFileReader.getColumns().each { Column column ->
        println "$column.name: label=$column.label, format=$column.format, type=$column.type.simpleName, length=$column.length"
    }

    println "\nData:"
    Writer writer = new StringWriter()
    CSVDataWriter csvDataWriter = new CSVDataWriterImpl(writer)
    csvDataWriter.writeColumnNames(sasFileReader.getColumns())
    csvDataWriter.writeRowsArray(sasFileReader.getColumns(), sasFileReader.readAll())

    System.out.println(writer)
}