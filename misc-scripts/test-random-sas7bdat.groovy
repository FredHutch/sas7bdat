#!/usr/bin/env groovy
///////////////////////////////////////////////////////////////////////////////
// This script generates random SAS7BDAT files using the sas7bdat library
// and reads them with SAS to make sure that they were generated properly.
///////////////////////////////////////////////////////////////////////////////

@Grab('org.apache.commons:commons-csv:1.10.0')
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord

import com.fasterxml.jackson.core.*

import java.time.*
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.util.regex.Pattern
import java.lang.reflect.Constructor

class TestRandomSas7Bdat {

    private static final Class Sas7bdatExporter
    private static final Class Sas7bdatMetadata
    private static final Class Variable
    private static final Class VariableType
    private static final Class Format

    private static final Constructor FormatConstructor
    private static final Constructor VariableConstructor
    private static final Constructor Sas7bdatExporterConstructor

    // Make sure the library has been compiled.
    // It should be relative to this script's location.
    private static final Path scriptPath = Paths.get(TestRandomSas7Bdat.protectionDomain.codeSource.location.path)
    static {
        def jarFile
        def targetDir = scriptPath.parent.parent.resolve("target")
        if (Files.exists(targetDir)) {
            targetDir.eachFileMatch(~/^sas7bdat-\d+\.\d+\.\d+\.jar$/) { jarFile = it }
        }
        if (jarFile == null) {
            println "ERROR: sas-transport jar does not exist.  Run 'mvn package -DskipTests' to build it"
            System.exit(1)
        }

        TestRandomSas7Bdat.classLoader.rootLoader.addURL(new URL(jarFile.toUri().toString()))
        Sas7bdatExporter = Class.forName("org.scharp.sas7bdat.Sas7bdatExporter")
        Sas7bdatMetadata = Class.forName("org.scharp.sas7bdat.Sas7bdatMetadata")
        Variable         = Class.forName("org.scharp.sas7bdat.Variable")
        VariableType     = Class.forName("org.scharp.sas7bdat.VariableType")
        Format           = Class.forName("org.scharp.sas7bdat.Format")

        FormatConstructor = Format.getDeclaredConstructor(String.class, int.class, int.class)

        VariableConstructor = Variable.getDeclaredConstructor(
            String.class,   // variableName
            VariableType,   // type
            int.class,      // variableLength
            String.class,   // label
            Format,         // outputFormat
            Format)         // inputFormat

        Sas7bdatExporterConstructor = Sas7bdatExporter.getDeclaredConstructor(
            Path.class,           // targetLocation
            Sas7bdatMetadata,     // metadata
            int.class)            // totalObservations
    }

    /**
     * A class for grouping reading/writing test data.
     * The interface supports datasets with an arbitrary number of observations.
     */
    static class Dataset {

        static class Metadata {
            LocalDateTime creationDate
            String datasetLabel
            String datasetType
            List variables
            int totalObservations
        }

        /** Generates a random test case and persists it as JSON so that it can be reprocessed later (perhaps after a bug has been fixed)
         * This is more reliable that re-running the program with a seed.
         */
        static void generateRandomDataset(long seed, Path datasetFile) {
            def randomNumberGenerator = new Random(seed)

            def randomStringGenerator = new RandomStringGenerator(
                randomNumberGenerator,
                'ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz 0123456789 []!@' +
                    '\u0394' + // GREEK CAPITAL LETTER DELTA
                    '\u03C8' + // GREEK SMALL LETTER PSI
                    '\uD83D\uDE00') // GRINNING FACE

            def randomElement = { List elements ->
                // return a random element from the list
                elements[randomNumberGenerator.nextInt(elements.size())]
            }

            def normalizeVariableName = { String name ->
                // In SAS, variable names are case-insensitive.
                // Also, SAS seems to ignore when a variable name ends in spaces.
                return name.toUpperCase().replaceAll(~/ +$/, '')
            }

            def randomVariable = { Set<String> usedNames, int variableNumber ->

                // Because all variable names within a dataset must be unique, we keep
                // retrying until we generate a name that is not in use.
                int maxVariableNameLength = 32
                String name = randomStringGenerator.nextRandomString(maxVariableNameLength, ~/\w.*/)
                while (usedNames.contains(normalizeVariableName(name))) {
                    name = randomStringGenerator.nextRandomString(maxVariableNameLength, ~/\w.*/)
                }

                def type = randomNumberGenerator.nextBoolean() ? VariableType.NUMERIC : VariableType.CHARACTER

                def length
                if (type == VariableType.CHARACTER) {
                    // variations toward weighted toward smaller variables
                    int randomNumber = randomNumberGenerator.nextInt(100)
                    if (randomNumber < 75) { // 75% chance of a small text variable
                        length = randomNumberGenerator.nextInt(10) + 1
                    } else if (randomNumber < 95) { // 20% chance of a medium-sized string (like a comment field)
                        length = randomNumberGenerator.nextInt(200) + 1
                    } else { // 5% chance of a large text field
                        length = randomNumberGenerator.nextInt(32767) + 1
                    }
                } else {
                    length = 8 // size of a double
                }

                String label = randomStringGenerator.nextRandomString(256)

                // TODO: figure out how to create a random format and still check it.
                def outputFormat = Format.UNSPECIFIED

                // Generate an input format (ignored by SAS for this case)
                def inputFormat
                if (type == VariableType.CHARACTER) {
                    def inputFormatName = randomElement(['', '$CHAR', '$UPCASE', '$ASCII', '$'])
                    def inputFormatWidth = randomNumberGenerator.nextInt(length) + 1
                    inputFormat = FormatConstructor.newInstance(inputFormatName, inputFormatWidth, 0)
                } else {
                    def inputFormatName = randomElement(['', 'F', 'D', 'NEGPAREN'])
                    def inputFormatWidth = randomNumberGenerator.nextInt(10) + 10
                    def inputFormatNumberOfDigitsRightOfDecimalPoint = randomNumberGenerator.nextInt(Math.max(10, inputFormatWidth))

                    inputFormat = FormatConstructor.newInstance(inputFormatName, inputFormatWidth, inputFormatNumberOfDigitsRightOfDecimalPoint)
                }

                return VariableConstructor.newInstance(
                    name,
                    variableNumber,
                    type,
                    length,
                    label,
                    outputFormat,
                    inputFormat)
            }

            def datasetLabel = randomStringGenerator.nextRandomString(256)
            def datasetType = randomStringGenerator.nextRandomString(8)

            // Random creation date, choosing from the range of value dates that SAS supports (1960 - 2020)
            def minSasDate = LocalDateTime.parse("1960-01-01T00:00:00")
            int secondsInSupportedRange = (2020 - 1960) * 365 * 24 * 60 * 60
            def creationDate = minSasDate.plusSeconds(randomNumberGenerator.nextLong(secondsInSupportedRange))

            // Determine how many variables the dataset will have.
            def totalVariables
            def randomNumber = randomNumberGenerator.nextInt(100)
            if (randomNumber < 70) { // 70% chance of having a narrow dataset
                totalVariables = randomNumberGenerator.nextInt(10) + 1
            } else if (randomNumber < 70 + 26) { // 26% chance of having a wide dataset
                totalVariables = randomNumberGenerator.nextInt(100) + 1
            } else { // 4% chance of having a super wide dataset
                totalVariables = randomNumberGenerator.nextInt(32767) + 1
            }

            // Create variables at random
            def variableNames = new HashSet()
            def variables = []
            for (int i = 1; i <= totalVariables; i++) {
                // Add a new, randomly-generated variable
                def newVariable = randomVariable(variableNames, i)
                variables << newVariable

                // Track the new variable's name so that we don't create two variables with the same name.
                variableNames << normalizeVariableName(newVariable.name)
            }


            // 50-50 chance of having a small dataset or a large dataset.
            def totalObservations
            if (randomNumberGenerator.nextBoolean()) {
                totalObservations = randomNumberGenerator.nextInt(10)
            } else {
                totalObservations = randomNumberGenerator.nextInt(10000)

                // Depending on how large a single observation is, a large dataset has the potential to be
                // very, very large, so large that testing them requires >80 GB.  I doubt that the test
                // coverage given by a random dataset of 100GB is better than that of an 8 GB dataset.
                // However, testing datasets of 100GB certainly causes problems, including filling up
                // the storage and time to execute the test.  Therefore, we limit the number
                // of observation so that the dataset doesn't exceed a size that is assumed to
                // be sufficiently large for the purpose of test variations.
                //
                // Note that limiting the observations to 8GiB can still lead to a dataset
                // that is considerably larger than 8GiB, depending on how much space is
                // wasted by on each page.
                long eightGiB = 8L * 1024 * 1024 * 1024
                int bytesPerObservation = variables.collect { it.length() } sum()
                long observationsIn8GiB = eightGiB / bytesPerObservation
                totalObservations = Math.min(totalObservations, observationsIn8GiB)
            }

            // write the dataset file
            JsonFactory factory = new JsonFactory()
            datasetFile.withOutputStream { outputStream ->
                def generator = factory.createGenerator(outputStream)

                generator.useDefaultPrettyPrinter()
                generator.writeStartObject() // start of dataset object

                // Add a description of this test case.
                generator.writeStringField('testDescription', "Random dataset generated by ${scriptPath.fileName} --seed $seed")

                // write the metadata
                generator.writeStringField('label',        datasetLabel)
                generator.writeStringField('type',         datasetType)
                generator.writeStringField('creationDate', creationDate.toString())

                def writeFormat = { format ->
                    generator.writeStartObject()
                    generator.writeStringField('name',           format.name())
                    generator.writeNumberField('width',          format.width())
                    generator.writeNumberField('numberOfDigits', format.numberOfDigits())
                    generator.writeEndObject()
                }

                generator.writeArrayFieldStart('variables')
                variables.each { variable ->
                    generator.writeStartObject() // start of variable object
                    generator.writeStringField('name',   variable.name())
                    generator.writeNumberField('number', variable.number())
                    generator.writeStringField('type',   variable.type().toString())
                    generator.writeStringField('label',  variable.label())
                    generator.writeNumberField('length', variable.length())

                    generator.writeFieldName('outputFormat')
                    writeFormat(variable.outputFormat())

                    generator.writeFieldName('inputFormat')
                    writeFormat(variable.inputFormat())

                    generator.writeEndObject() // end of variable object
                }
                generator.writeEndArray() // end of all variables


                // Stream the observations to the JSON file without holding more than one in memory at a time.
                // This enables support for very large datasets.
                generator.writeArrayFieldStart("observations")
                for (int i = 0; i < totalObservations; i++) {
                    generator.writeStartArray() // start of observation

                    variables.each { variable ->
                        if (variable.type() == VariableType.CHARACTER) {
                            // Prohibit blanks at the beginning of a value because random.sas can't preserve leading spaces.
                            def value = randomStringGenerator.nextRandomString(variable.length(), ~/^[^ ].*|$/)
                            generator.writeString(value)
                        } else {
                            // 1% chance of null (MISSING VALUE)
                            // TODO: more variations in number values
                            def value = randomNumberGenerator.nextInt(100) == 0 ? null : randomNumberGenerator.nextInt()
                            generator.writeNumber(value)
                        }
                    }

                    generator.writeEndArray() // end of observation
                }
                generator.writeEndArray() // end of all observations

                generator.writeEndObject() // end of dataset object

                generator.flush()
            }
        }

        private static JsonToken checkFieldName(JsonParser parser, String expectedName) {
            String actualFieldName = parser.nextFieldName()
            if (actualFieldName == null) {
                println "ERROR: object is missing '$expectedName' field"
                System.exit(1)
            }
            if (actualFieldName != expectedName) {
                println "ERROR: found '$actualFieldName' where '$expectedName' was expected"
                System.exit(1)
            }

            // advance to the value
            def token = parser.nextToken()
            if (token == null) {
                println "ERROR: object is missing the value for '$expectedName'"
                System.exit(1)
            }

            return token
        }

        private static JsonToken checkObjectEnd(JsonParser parser, String locationDescription) {
            def token = parser.nextToken()
            if (token != JsonToken.END_OBJECT) {
                println "ERROR: unexpected fields in $locationDescription"
                System.exit(1)
            }

            return token
        }

        private static void processTestCase(Path testCaseData, Closure haveMetadata, Closure haveObservation) {

            if (!Files.exists(testCaseData)) {
                println "ERROR: $testCaseData does not exist"
                System.exit(1)
            }

            Metadata metadata = new Metadata()

            // Parse the file
            JsonFactory factory = new JsonFactory()
            testCaseData.withInputStream { inputStream ->
                def parser = factory.createParser(inputStream)

                def parseFormat = { ->
                    checkFieldName(parser, "name")
                    def name = parser.getText()

                    checkFieldName(parser, "width")
                    def width = parser.getIntValue()

                    checkFieldName(parser, "numberOfDigits")
                    def numberOfDigits = parser.getIntValue()

                    checkObjectEnd(parser, 'format')

                    return FormatConstructor.newInstance(name, width, numberOfDigits)
                }

                def token = parser.nextToken()
                if (token != JsonToken.START_OBJECT) {
                    if (token == null) {
                        println "ERROR: $testCaseData is empty"
                    } else {
                        println "ERROR: contents of $testCaseData is not a JSON object"
                    }
                    System.exit(1)
                }

                // The test description is ignored.
                checkFieldName(parser, "testDescription")
                parser.getText()

                checkFieldName(parser, "label")
                metadata.datasetLabel = parser.getText()

                checkFieldName(parser, "type")
                metadata.datasetType = parser.getText()

                checkFieldName(parser, "creationDate")
                metadata.creationDate = LocalDateTime.parse(parser.getText())

                metadata.variables = []
                token = checkFieldName(parser, "variables")
                if (JsonToken.START_ARRAY != token) {
                    println "ERROR: The 'variables' field is not a JSON array"
                    System.exit(1)
                }
                while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
                    if (token != JsonToken.START_OBJECT) {
                        println "ERROR: variable is not a JSON object"
                        System.exit(1)
                    }

                    checkFieldName(parser, "name")
                    def name = parser.getText()

                    checkFieldName(parser, "number")
                    def number = parser.getIntValue()

                    checkFieldName(parser, "type")
                    def type = parser.getText()

                    checkFieldName(parser, "label")
                    def label = parser.getText()

                    checkFieldName(parser, "length")
                    def length = parser.getIntValue()

                    checkFieldName(parser, 'outputFormat')
                    def outputFormat = parseFormat()

                    checkFieldName(parser, 'inputFormat')
                    def inputFormat = parseFormat()

                    metadata.variables << VariableConstructor.newInstance(
                        name,
                        VariableType.valueOf(type),
                        length,
                        label,
                        outputFormat,
                        inputFormat)

                    checkObjectEnd(parser, 'variable')
                }

                // Provide the metadata to the caller
                haveMetadata(metadata)

                // Invoke the closure on each observation.
                token = checkFieldName(parser, "observations")
                if (JsonToken.START_ARRAY != token) {
                    println "ERROR: observations is not a JSON array"
                    System.exit(1)
                }

                // Loop over each observation in the observations array.
                while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
                    if (JsonToken.START_ARRAY != token) {
                        println "ERROR: observations is not an array of arrays"
                        System.exit(1)
                    }

                    def observation = []
                    while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
                        switch (token) {
                        case JsonToken.VALUE_NUMBER_FLOAT:
                            observation << parser.getDoubleValue()
                            break

                        case JsonToken.VALUE_NUMBER_INT:
                            observation << parser.getIntValue()
                            break

                        case JsonToken.VALUE_STRING:
                            observation << parser.getText()
                            break

                        case JsonToken.VALUE_NULL:
                            observation << null
                            break

                        default:
                            println "ERROR: unexpected value in observation $token"
                            System.exit(1)
                        }
                    }

                    // Provide the observation to the caller.
                    haveObservation(metadata, observation)
                }
            }
        }

        static Metadata readMetadata(Path testCaseFile) {
            // Parse the file to get the total number of observations.
            Metadata metadata
            processTestCase(testCaseFile, { metadata = it }, { ignored1, ignored2 -> metadata.totalObservations++ })
            return metadata
        }

        static void processObservations(Path testCaseFile, Closure handleObservation) {
            processTestCase(testCaseFile, { metadata -> }, { ignored, observation -> handleObservation(observation) })
        }
    }

    static def writeGroovyScriptToGenerateDataset(Path testCaseFile, File groovyScript) {

        var quoteStringLiteral = { String string -> '"' + string.replace('"', '\\"').replace('$', '\\$') + '"' }
        var quoteFormat = { format -> "FormatConstructor.newInstance(${quoteStringLiteral(format.name())}, ${format.width()}, ${format.numberOfDigits()})" }

        // Groovy has a limit of 64KB of byte code per method.  If we try to create a list literal
        // with too many values, Groovy won't be able to run.  We avoid this by creating methods
        // that can add to the list in chunks.
        int CHUNK_SIZE = 100

        // Parse the file to get the total number of observations.
        Dataset.Metadata metadata = Dataset.readMetadata(testCaseFile)

        groovyScript.withWriter('UTF-8') { writer ->

            writer.writeLine """
                |#!/scharp/xapps/s/smartenv.pl JAVA_OPTS=-ea JAVA_HOME=/scharp/xapps/s/jdk-17.0.9_9 /scharp/xapps/s/groovy-3.0.10
                |///////////////////////////////////////////////////////////////////////////////
                |// A Groovy script that can be used to generate random.sas7bdat.
                |//
                |// This program was generated from $testCaseFile
                |///////////////////////////////////////////////////////////////////////////////
                |
                |import java.nio.file.Path
                |import java.nio.file.Files
                |import java.time.LocalDateTime
                |import java.lang.reflect.Constructor
                |
                |// Make sure the library has been compiled.  It should be relative to this script's location.
                |def jarFile
                |def targetDir = Path.of('$scriptPath').parent.parent.resolve("target")
                |if (Files.exists(targetDir)) {
                |    targetDir.eachFileMatch(~/^sas7bdat-\\d+\\.\\d+\\.\\d+\\.jar\$/) { jarFile = it }
                |}
                |if (jarFile == null) {
                |    println "ERROR: sas-transport jar does not exist.  Run 'mvn package -DskipTests' to build it"
                |    System.exit(1)
                |}
                |
                |getClass().classLoader.rootLoader.addURL(new URL(jarFile.toUri().toString()))
                |Sas7bdatExporter = Class.forName("org.scharp.sas7bdat.Sas7bdatExporter")
                |Sas7bdatMetadata = Class.forName("org.scharp.sas7bdat.Sas7bdatMetadata")
                |Variable         = Class.forName("org.scharp.sas7bdat.Variable")
                |VariableType     = Class.forName("org.scharp.sas7bdat.VariableType")
                |Format           = Class.forName("org.scharp.sas7bdat.Format")
                |
                |Constructor FormatConstructor = Format.getDeclaredConstructor(String.class, int.class, int.class)
                |
                |Constructor VariableConstructor = Variable.getDeclaredConstructor(
                |    String.class,   // variableName
                |    VariableType,   // type
                |    int.class,      // variableLength
                |    String.class,   // label
                |    Format,         // outputFormat
                |    Format)         // inputFormat
                |
                |Constructor Sas7bdatExporterConstructor = Sas7bdatExporter.getDeclaredConstructor(
                |    Path.class,           // targetLocation
                |    Sas7bdatMetadata,     // metadata
                |    int.class)            // totalObservations
                |
                |def sas7BdatPath = Path.of("random.sas7bdat")
                |def creationDate = LocalDateTime.parse(${quoteStringLiteral(metadata.creationDate.toString())})
                |def datasetType  = ${quoteStringLiteral(metadata.datasetType)}
                |def datasetLabel = ${quoteStringLiteral(metadata.datasetLabel)}
                |""".trim().stripMargin()

            def writeVariable = { variable ->
                writer.writeLine """
                    |    VariableConstructor.newInstance(
                    |        ${quoteStringLiteral(variable.name())},
                    |        VariableType.${variable.type()},
                    |        ${variable.length()},
                    |        ${quoteStringLiteral(variable.label())},
                    |        ${quoteFormat(variable.outputFormat())},
                    |        ${quoteFormat(variable.inputFormat())}),
                    """.trim().stripMargin()
            }

            if (metadata.variables.size() <= CHUNK_SIZE) {
                writer.writeLine "def variables = ["
                metadata.variables.each { variable -> writeVariable(variable) }
                writer.writeLine "]\n"
            } else {
                writer.writeLine """
                    |//
                    |// Groovy has a limit of 64KB of byte code per method.
                    |// If variables were a single long list literal, then this method would exceed
                    |// that limit and the script would fail with groovyjarjarasm.asm.MethodTooLargeException.
                    |// To avoid that, we create methods that add $CHUNK_SIZE variables and invoke them.
                    |//
                    |def variables = []""".trim().stripMargin()
                for (int i = 0; i < metadata.variables.size(); i += CHUNK_SIZE) {
                    writer.writeLine "({ variables.addAll([" // start of chunk
                    for (int j = 0; j < CHUNK_SIZE && i + j < metadata.variables.size(); j++) {
                        writeVariable(metadata.variables[i + j])
                    }
                    writer.writeLine "])}).call()"  // end of chunk
                }
                writer.writeLine "\n"
            }

            writer.writeLine """
                |def datasetExporter = Sas7bdatExporterConstructor.newInstance(
                |    sas7BdatPath,
                |    Sas7bdatMetadata.builder().
                |        creationTime(creationDate).
                |        datasetType(datasetType).
                |        datasetLabel(datasetLabel).
                |        variables(variables).
                |        build(),
                |    $metadata.totalObservations)
                |
                """.trim().stripMargin()

            // Determine if there are so many observations that it needs to be chunked.
            boolean observationsAreChunked = CHUNK_SIZE < metadata.totalObservations

            // Start adding the observations
            if (observationsAreChunked) {
                // There are too many observations to write as a single list literal.
                // Add a comment to explain the chunking.
                writer.writeLine """
                    |//
                    |// Groovy has a limit of 64KB of byte code per method.
                    |// If observations were a single long list literal, then this method would exceed
                    |// that limit and the script would fail with groovyjarjarasm.asm.MethodTooLargeException.
                    |// To avoid that, we create methods that add $CHUNK_SIZE rows and invoke them.
                    |//
                    |""".trim().stripMargin()
            }

            // Parse the file again to add each of the observations.
            int currentObservationIndex = 0
            Dataset.processObservations(testCaseFile) { observation ->

                // start of observations chunk
                if (observationsAreChunked && currentObservationIndex % CHUNK_SIZE == 0) {
                    writer.writeLine "({"
                }

                // Write the observation
                writer.append "datasetExporter.writeObservation([" // start of observation
                observation.eachWithIndex { value, i ->
                    // quote character values
                    writer.append metadata.variables[i].type() == VariableType.CHARACTER ? "'$value'" : "$value"

                    // comma-separate values in an observation
                    if (i != observation.size() - 1) {
                        writer.append ', '
                    }
                }
                writer.append "])\n"  // end of observation

                // end of observations chunk
                boolean isFinalObservation = currentObservationIndex == metadata.totalObservations - 1
                if (observationsAreChunked &&
                        (isFinalObservation || currentObservationIndex % CHUNK_SIZE == CHUNK_SIZE - 1)) {
                    writer.writeLine "}).call()"
                }

                currentObservationIndex++
            }

            // We're done writing the dataset
            writer.writeLine "datasetExporter.close()"
        }

        groovyScript.setExecutable(true)
    }

    // Format an observation as a dataline
    static String formatDataline(variables, observation) {
        StringBuilder stringBuilder = new StringBuilder()
        variables.eachWithIndex { variable, i ->
            if (variable.type() == VariableType.CHARACTER) {
                // Quote any values that contain commas, using '' to quote a quote character.
                stringBuilder << (observation[i].contains(',') ? "'${observation[i].replace("'", "''")}'" : observation[i])
            } else {
                // MISSING numerics are formatted as blank, so there's no need to write them.
                if (observation[i] != null) {
                    // Other numeric are formatted according to their input format.
                    // If the number of digits is 2, then SAS divides the representation in the datalines by 100,
                    // so we must multiply it by 100 to preserve the number.
                    def number = new BigDecimal(observation[i].toString())
                    number = number.movePointRight(variable.inputFormat().numberOfDigits())
                    def string = number.toPlainString()

                    // The NEGPAREN uses parentheses around the number instead of leading minus sign to indicate
                    // a negative value.
                    if (variable.inputFormat().name() == 'NEGPAREN') {
                        string = string.replaceAll(~/-(.+)/, '($1)')
                    }

                    stringBuilder << string
                }
            }

            // comma-separate the values
            if (i < variables.size() - 1) {
                stringBuilder << ','
            }
        }
        return stringBuilder.toString()
    }

    static def writeSasProgramToGenerateDataset(File sasProgramFile, Path testCaseFile) {

        def quotedVariableName = { String name ->
            // Variable names with blanks or special characters must be quoted as a "name literal".
            // See https://documentation.sas.com/doc/en/pgmsascdc/9.4_3.5/lepg/p0ibybzsojk9u8n17a3k3vgflzzf.htm#p12sdrho3c5oc7n139b594w53n4y
            name ==~ ~/[A-Za-z]\w*/ ? name : "'$name'n"
        }

        def formatSasFormat = { format ->
            // "NAMEw.d", where ".d" is optional
            // The number of digits is appended if non-zero.  For character types, this should always be zero.
            format.name() + format.width() + '.' + (format.numberOfDigits() ? +format.numberOfDigits() : '')
        }

        def formatIsUnspecified = { format ->
            format.name() == '' && format.width() == 0 && format.numberOfDigits() == 0
        }

        sasProgramFile.withWriter('UTF-8') { writer ->

            Dataset.processTestCase(
                testCaseFile,
                { metadata ->
                    def datasetProperties
                    if (metadata.datasetLabel || metadata.datasetType) {
                        def labelProperty = metadata.datasetLabel ? "label='$metadata.datasetLabel'" : ''
                        def typeProperty  = metadata.datasetType  ? "type='$metadata.datasetType'"   : ''
                        datasetProperties = "($labelProperty $typeProperty)"
                    } else {
                        datasetProperties = ''
                    }

                    writer.writeLine """
                        |/********************************************************************
                        |* A SAS Program that can be used to generate random.sas7bdat.
                        |*
                        |* This program was generated from $testCaseFile
                        |********************************************************************/
                        |
                        |OPTIONS VALIDVARNAME=ANY;
                        |
                        |* Delete any random.sas7bdat that exists, since a corrupt dataset ;
                        |* would prevent this program from running.                        ;
                        |X rm --force random.sas7bdat;
                        |
                        |libname mylib '.';
                        |
                        |data mylib.random$datasetProperties;
                        |     /* STOPOVER results in a clear error if a dataline is too long */
                        |     /* DSD means (comma) delimited data */
                        |    infile datalines LINESIZE=32767 STOPOVER DSD;
                        |
                        |    input
                         """.trim().stripMargin()

                    metadata.variables.each { variable ->
                        String type = variable.type() == VariableType.NUMERIC ? '' : ': $' + variable.length() + "." // $ for CHARACTER
                        writer.writeLine("        ${quotedVariableName(variable.name())} $type")
                    }

                    def atLeastOneOutputFormatIsSpecified = metadata.variables.
                            collect { variable -> formatIsUnspecified(variable.outputFormat()) }.
                            any { it == false }
                    if (atLeastOneOutputFormatIsSpecified) {

                        writer.writeLine """
                            |    ;
                            |
                            |    format
                            """.trim().stripMargin()

                        for (def variable in metadata.variables) {
                            if (!formatIsUnspecified(variable.outputFormat())) {
                                writer.writeLine("        ${quotedVariableName(variable.name())} ${formatSasFormat(variable.outputFormat())}")
                            }
                        }
                    }

                    def atLeastOneInputFormatIsSpecified = metadata.variables.
                            collect { variable -> formatIsUnspecified(variable.inputFormat()) }.
                            any { it == false }
                    if (atLeastOneInputFormatIsSpecified) {

                        writer.writeLine """
                            |    ;
                            |
                            |    informat
                            """.trim().stripMargin()

                        for (def variable in metadata.variables) {
                            if (!formatIsUnspecified(variable.inputFormat())) {
                                writer.writeLine("        ${quotedVariableName(variable.name())} ${formatSasFormat(variable.inputFormat())}")
                            }
                        }
                    }

                    writer.writeLine """
                        |    ;
                        |
                        |    label
                        """.trim().stripMargin()

                    for (def variable in metadata.variables) {
                        if (!variable.label().empty) {
                            writer.writeLine("        ${quotedVariableName(variable.name())} = '${variable.label()}'")
                        }
                    }

                    writer.writeLine """
                        |    ;
                        |
                        |datalines;
                        """.trim().stripMargin()
                },
                { metadata, observation ->
                    writer.write formatDataline(metadata.variables, observation) // Format each observation as a dataline
                    writer.write "\n" // end the dataline
                })

            // Write the directive to run the DATA statement.
            writer.writeLine """
                |;
                |
                |run;
                """.trim().stripMargin()
        }
    }

    static def checkColumn(List errors, record, String columnName, String expectedValue, String message) {
        if (record[columnName] != expectedValue) {
            errors << "ERROR: $message\n" +
            "       Expected = $expectedValue\n" +
            "       Actual   = ${record[columnName]}"
        }
    }

    static def checkColumn(List errors, record, String columnName, int expectedValue, String message) {
        checkColumn(errors, record, columnName, String.valueOf(expectedValue), message)
    }

    static def runSasProgram(Path sasProgram, List<String> arguments) {

        String logFileName = sasProgram.fileName.toString().replaceAll(~/\.sas$/, ".log")

        // Delete any log file that may exist so that we don't confuse this run's results
        // with previous results in the event that no log file is generated.
        File sasLogFile = new File(logFileName)
        sasLogFile.delete()

        def process
        try {
            def commandLine = ['sas_u8', sasProgram]
            commandLine.addAll(arguments) // add optional -sysparm arguments
            process = commandLine.execute()
        } catch (IOException exception) {
            println "ERROR: $exception.message"
            System.exit(1)
        }

        process.waitFor()
        def output = process.text
        if (output) {
            // SAS only prints to stdout/stderr if something is very wrong.
            println "ERROR: \"sas_u8 $sasProgram\" printed the following output:"
            println output
            System.exit(1)
        }

        // Scan the SAS log for errors
        def errors = []
        sasLogFile.withReader('UTF-8') { reader ->
            reader.eachLine { line ->
                if (line.startsWith('ERROR')) {
                    errors << line
                }
            }
        }
        if (errors) {
            println "SAS reported the following errors (from $sasLogFile):"
            errors.each { error -> println error }
            System.exit(1)
        }
    }

    static def checkSas7BdatFile(Path sas7BdatPath, Path testCaseFile, boolean isFromRandomSas) {

        def trimBlanks = { String string -> string.replaceAll(~/^ +| +$/, '') } // strip leading and trailing blanks
        def trimTrailingBlanks = { String string -> string.replaceAll(~/ +$/, '') } // strip trailing blanks

        // Save the sas7bdat file for future reference.
        def savedFilePrefix   = isFromRandomSas ? 'sas' : 'java'
        def savedFileBaseName = testCaseFile.fileName.toString().replaceAll(~/\.[^.]+/, '') // ../my-test.json -> my-test
        def savedFile = testCaseFile.resolveSibling("${savedFilePrefix}-${savedFileBaseName}.sas7bdat")
        Files.copy(sas7BdatPath, savedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)

        runSasProgram(scriptPath.resolveSibling("show-sas7bdat.sas"), ['-sysparm', sas7BdatPath])

        // Determine the expected metadata
        Dataset.Metadata expectedMetadata = Dataset.readMetadata(testCaseFile)

        // Confirm that the metadata was read properly
        def errors = []
        def metadataCsv = Path.of('random.metadata.csv')
        metadataCsv.withReader('UTF-8') { reader ->

            // SAS prefers to keep numeric values at the beginning of the physical row.
            def expectedOffsetsInObservation = [:]
            int nextOffset = 0
            expectedMetadata.variables.each { variable ->
                if (variable.type() == VariableType.NUMERIC) {
                    expectedOffsetsInObservation[variable] = nextOffset
                    nextOffset += variable.length()
                }
            }
            expectedMetadata.variables.each { variable ->
                if (variable.type() == VariableType.CHARACTER) {
                    expectedOffsetsInObservation[variable] = nextOffset
                    nextOffset += variable.length()
                }
            }

            CSVParser csv = new CSVParser(reader, CSVFormat.DEFAULT.withHeader())
            int i = 0
            for (record in csv.iterator()) {

                // Test dataset-level metadata (on the first row only)
                if (i == 0) {
                    checkColumn(errors, record, 'MEMLABEL', trimBlanks(expectedMetadata.datasetLabel), "$metadataCsv has incorrect MEMLABEL (dataset label)")

                    // The data step in random.sas converts the type to upper-case.  The PROC EXPORT removes leading/trailing blanks.
                    def expectedDatasetType = trimBlanks(isFromRandomSas ? expectedMetadata.datasetType.toUpperCase() : expectedMetadata.datasetType)
                    checkColumn(errors, record, 'TYPEMEM', expectedDatasetType, "$metadataCsv has incorrect TYPEMEM (dataset type)")

                    if (!isFromRandomSas) {
                        // SAS dates are formatted as 01MAR99:08:28:38 in the current time zone.
                        // SAS doesn't handle Daylight Saving Time correctly and if the current time is in
                        // Daylight Savings Time but the dataset timestamp is not, SAS subtracts an hour from
                        // the timestamp.
                        ZoneId currentTimeZone = ZoneId.systemDefault()
                        ZonedDateTime creationTime = expectedMetadata.creationDate.atZone(currentTimeZone)
                        if (currentTimeZone.getRules().getDaylightSavings(creationTime.toInstant()).isZero() && // dataset is in standard time
                            !currentTimeZone.getRules().getDaylightSavings(Instant.now()).isZero()) {           // system is in daylight saving time
                            creationTime = creationTime.plusHours(1)
                        }
                        String formattedDate = creationTime.format("ddLLLyy:HH:mm:ss").toUpperCase()

                        checkColumn(errors, record, 'CRDATE', formattedDate, "$metadataCsv has incorrect CRDATE (creation timestamp)")
                        checkColumn(errors, record, 'MODATE', formattedDate, "$metadataCsv has incorrect MODATE (last modified timestamp)")
                    }

                    checkColumn(errors, record, 'NOBS', expectedMetadata.totalObservations, "$metadataCsv has incorrect NOBS (total observations)")
                    checkColumn(errors, record, 'DELOBS', 0, "$metadataCsv has incorrect DELOBS (deleted observations)")
                }

                // Get the corresponding variable from our set.
                def variable = expectedMetadata.variables[record.VARNUM.toInteger() - 1]
                if (variable == null) {
                    errors << "ERROR: $metadataCsv has unexpected VARNUM=${record.VARNUM}"
                } else {
                    // test all of the variable's properties
                    checkColumn(errors, record, 'NAME', trimTrailingBlanks(variable.name()), "$metadataCsv has incorrect NAME for VARNUM=${record.VARNUM}")
                    checkColumn(errors, record, 'TYPE', variable.type() == VariableType.NUMERIC ? 1 : 2, "$metadataCsv has incorrect TYPE for VARNUM=${record.VARNUM}")
                    checkColumn(errors, record, 'LENGTH', variable.length(), "$metadataCsv has incorrect LENGTH for VARNUM=${record.VARNUM}")
                    checkColumn(errors, record, 'LABEL', trimBlanks(variable.label()), "$metadataCsv has incorrect LABEL for VARNUM=${record.VARNUM}")
                    checkColumn(errors, record, 'FORMAT', variable.outputFormat().name(), "$metadataCsv has incorrect FORMAT for VARNUM=${record.VARNUM}")
                    checkColumn(errors, record, 'FORMATL', variable.outputFormat().width(), "$metadataCsv has incorrect FORMATL for VARNUM=${record.VARNUM}")
                    checkColumn(errors, record, 'FORMATD', variable.outputFormat().numberOfDigits(), "$metadataCsv has incorrect FORMATD for VARNUM=${record.VARNUM}")
                    checkColumn(errors, record, 'INFORMAT', variable.inputFormat().name(), "$metadataCsv has incorrect INFORMAT for VARNUM=${record.VARNUM}")
                    checkColumn(errors, record, 'INFORML', variable.inputFormat().width(), "$metadataCsv has incorrect INFORML for VARNUM=${record.VARNUM}")
                    checkColumn(errors, record, 'INFORMD', variable.inputFormat().numberOfDigits(), "$metadataCsv has incorrect INFORMD for VARNUM=${record.VARNUM}")

                    int expectedJustification = variable.type() == VariableType.NUMERIC ? 1 : 0
                    checkColumn(errors, record, 'JUST', expectedJustification, "$metadataCsv has incorrect JUST for VARNUM=${record.VARNUM}")

                    // The position of a variable in an observation is determined above.
                    checkColumn(errors, record, 'NPOS', expectedOffsetsInObservation[variable], "$metadataCsv has incorrect NPOS for VARNUM=${record.VARNUM}")
                }

                i++
            }
            if (i != expectedMetadata.variables.size()) {
                errors << "ERROR: $metadataCsv only has $i variables (expected ${expectedMetadata.variables.size()})"
            }
        }
        if (errors) {
            println "The following discrepancies were found in $metadataCsv${isFromRandomSas ? ' created by random.sas' : ''}:"
            errors.each { error -> println error }
            System.exit(1)
        }

        // Confirm that the data was read properly.
        def dataCsv = Path.of('random.csv')

        // show-sas7bdat.sas puts an extra newline at the end of the file, which can look like
        // data when there's only one variable.  We must remove that newline before parsing it.
        def truncatableFile = new RandomAccessFile(dataCsv.toFile(), "rw")
        if (truncatableFile.length() != 0) {
            truncatableFile.setLength(truncatableFile.length() - 1)
        }
        truncatableFile.close()

        dataCsv.withReader('UTF-8') { reader ->

            CSVParser csv = new CSVParser(reader, CSVFormat.DEFAULT.withHeader().withIgnoreEmptyLines(false))
            Iterator<CSVRecord> csvIterator = csv.iterator()
            int rowIndex = 0

            Dataset.processObservations(testCaseFile) { observation ->

                if (!csvIterator.hasNext()) {
                    errors << "ERROR: $dataCsv is missing observation: ${observation}"
                    return
                }

                // convert ints to strings and strip trailing whitespace for comparison with CSV
                // null values are necessarily MISSING NUMERICs and are represented as a period.
                //
                // Note: The idea of converting the observation into a list of strings predates
                // being able to run in constant memory, when it was easy to add all expected
                // observations into a list and then remove them as they were found.  This enabled
                // better reporting when an observation unexpectedly was missing/added.  Now that
                // this is not done, it might be better to compare the values in their native form.
                def stringizedObservation = []
                for (int columnIndex = 0; columnIndex < observation.size(); columnIndex++) {
                    def value = observation[columnIndex]
                    if (expectedMetadata.variables[columnIndex].type() == VariableType.CHARACTER) {
                        // Strip trailing whitespace from CHARACTER values
                        stringizedObservation << trimTrailingBlanks(value)
                    } else {
                        // For decimals, format according to the variable's output format.
                        if (value == null) {
                            stringizedObservation << '.' // MISSING VALUE is always a .
                        } else {
                            def format = expectedMetadata.variables[columnIndex].outputFormat()
                            def number = new BigDecimal(value.toString())
                            number = number.movePointLeft(format.numberOfDigits())
                            stringizedObservation << number.toPlainString()
                        }
                    }
                }
                CSVRecord record = csvIterator.next()

                for (int columnIndex = 0; columnIndex < observation.size(); columnIndex++) {
                    def actualValue = record[columnIndex]
                    def expectedValue = stringizedObservation[columnIndex]
                    if (actualValue != expectedValue) {
                        errors << """|ERROR: $dataCsv has incorrect value at row ${rowIndex + 1}, column ${columnIndex + 1}
                                     |       Expected = $expectedValue
                                     |       Actual   = $actualValue""".trim().stripMargin()
                    }
                }

                rowIndex++
            }

            while (csvIterator.hasNext()) {
                CSVRecord record = csvIterator.next()
                errors << "ERROR: $dataCsv has unexpected observation at ${rowIndex}: ${record}"
            }
        }
        if (errors) {
            println "The following discrepancies were found in $dataCsv${isFromRandomSas ? ' created by random.sas' : ''}:"
            errors.each { error -> println error }
            System.exit(1)
        }
    }

    static def testDataset(Path testCaseFile) {

        // Parse the file to determine the number of observations
        Dataset.Metadata metadata = Dataset.readMetadata(testCaseFile)

        // Parse the file to determine the approximate size of the largest observation.
        // This helps us skip optional steps that would be impractical/impossible due to the size of the dataset.
        long totalObservationLength = 0
        int maxObservationLength    = 0 // 0 used if there are no observations
        Dataset.processObservations(testCaseFile) { observation ->
            // Compute the size of the value when represented as a String in a DATALINES statement.
            int observationLength = formatDataline(metadata.variables, observation).getBytes(StandardCharsets.UTF_8).length
            totalObservationLength += observationLength
            maxObservationLength = Math.max(maxObservationLength, observationLength)
        }

        // To help with troubleshooting, generate a Groovy script that would generate this dataset.
        // The design of the Groovy script is to be as simple as possible, which hard-codes the data.
        // As a result, it takes about 8GB of Groovy to generate an 8GB dataset.  A Groovy script that's
        // 8GB long cannot be run because Groovy runs out of memory compiling it.  Therefore, we skip
        // creating the Groovy script if it would be too large.
        long eightGiB = 8L * 1024 * 1024 * 1024
        if (totalObservationLength < eightGiB) {
            writeGroovyScriptToGenerateDataset(testCaseFile, new File("random.groovy"))
        } else {
            new File("random.groovy").delete() // delete any stale random.groovy to avoid confusion
        }

        def sas7BdatPath = Path.of("random.sas7bdat")

        // To help with troubleshooting, generate a SAS program that would generate this dataset.
        // Note that there is a bug in the generated random.sas that it fail if a value in the dataset
        // has an observation which exceeds 32KiB as a dataline.  In this case, it's better not to create
        // random.sas than to create one that does something wrong.  The logic for determining the length
        // approximates the logic for creating the dataline in writeSasProgramToGenerateDataset().
        // TODO: fix random.sas so that it can handle this, perhaps with PROC IMPORT
        if (maxObservationLength < 32767) {
            writeSasProgramToGenerateDataset(new File("random.sas"), testCaseFile)

            runSasProgram(Path.of("random.sas"), [])

            // Confirm that random.sas creates an equivalent dataset to the one we expect to write.
            // Otherwise the troubleshooting tool is not so useful.
            checkSas7BdatFile(sas7BdatPath, testCaseFile, true)
        } else {
            new File("random.sas").delete() // delete any stale random.sas that exists to avoid confusion
        }


        //
        // Use sas-transport to write out the dataset as a SAS7BDAT.
        //
        def datasetExporter = Sas7bdatExporterConstructor.newInstance(
            sas7BdatPath,
            Sas7bdatMetadata.builder().
                creationTime(metadata.creationDate).
                datasetType(metadata.datasetType).
                datasetLabel(metadata.datasetLabel).
                variables(metadata.variables).
                build(),
            metadata.totalObservations)

        // Add each observation
        Dataset.processObservations(testCaseFile) { observation -> datasetExporter.writeObservation(observation) }
        assert datasetExporter.isComplete()

        datasetExporter.close()

        // Confirm that SAS reads the file in the way we expected it to be created.
        checkSas7BdatFile(sas7BdatPath, testCaseFile, false)
    }

    /**
     * This is where the main script starts
     */
    static void main(String[] args) {

        def cli = new CliBuilder()
        cli.width  = 100
        cli.usage  = "test-random-sas7bdat.groovy [--help] [--seed SEED] [--loop COUNT] [TEST_CASE_DATA ...]"
        cli.header = "Generates a random dataset, writes it as SAS7BDAT using the sas7bdat " +
                     "library, uses sas_u8 to extract the data and metadata from the dataset, " +
                     "then confirms that SAS interpreted the data as intended.\n" +
                     "\n" +
                     "If TEST_CASE_DATA is given, then the dataset is read from the file instead of " +
                     "being randomly generated.  More than one TEST_CASE_DATA may be given.\n\n"

        cli.h(longOpt: 'help', 'Shows the usage information for this tool.')

        cli.s(
            longOpt  : 'seed',
            args     : 1,
            argName  : 'SEED',
            'A numeric seed for the random number generated.  ' +
            'This can be used to re-generate the same dataset as was previously generated.  ' +
            'If no seed is given, then a seed based on the current timestamp is used.')

        cli.l(
            longOpt  : 'loop',
            args     : 1,
            argName  : 'COUNT',
            'The number of random datasets to generate and test.  This cannot be combined with --seed.')

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

        // Get the list of TEST_CASE_FILE options given.
        List<Path> testCaseDataFiles
        if (options.arguments()) {
            testCaseDataFiles = options.arguments().collect { Path.of(it) }
            testCaseDataFiles.each { testCaseDataFile ->
                if (!Files.exists(testCaseDataFile)) {
                    println "ERROR: $testCaseDataFile does not exist"
                    System.exit(1)
                }
            }

            if (options.seed) {
                println "ERROR: A seed cannot be given with a test case data file"
                System.exit(1)
            }
            if (options.loop) {
                println "ERROR: Cannot loop with a test case data file"
                System.exit(1)
            }
        }

        // Get the random number generator seed.
        long seed = System.nanoTime()
        if (options.seed) {
            try {
                seed = Long.parseLong(options.seed)
            } catch (ignored) {
                println "ERROR: invalid SEED argument (must be a whole number)"
                System.exit(1)
            }
        }

        int totalIterations = 1
        if (options.loop) {
            try {
                totalIterations = Integer.parseInt(options.loop)
                if (totalIterations <= 0) {
                    println "ERROR: invalid COUNT argument (must be a positive integer)"
                    System.exit(1)
                }
            } catch (ignored) {
                println "ERROR: invalid COUNT argument (must be a whole number)"
                System.exit(1)
            }
        }

        if (options.seed && options.loop) {
            println "ERROR: cannot use both --seed and --loop"
            System.exit(1)
        }

        if (testCaseDataFiles) {
            // Test each dataset.
            testCaseDataFiles.each { testCaseDataFile ->
                println "Testing $testCaseDataFile"
                testDataset(testCaseDataFile)
            }
        } else {
            // Generate and test datasets.
            for (int i = 0; i < totalIterations; i++) {
                if (i != 0) {
                    seed += System.nanoTime() + i
                }

                // Generate and serialize the random data as a test case.
                println "Testing random dataset with seed $seed"
                def randomDatasetFile = Path.of("random.json")
                Dataset.generateRandomDataset(seed, randomDatasetFile)

                // Test the dataset.
                testDataset(randomDatasetFile)
            }
        }
    }
}


/** A helper class for creating a string at random */
class RandomStringGenerator {
    static class RandomCharacter {
        String character
        int    characterLengthInBytes

        RandomCharacter(String string) {
            character              = string
            characterLengthInBytes = string.getBytes(StandardCharsets.UTF_8).length
        }
    }

    Random                              randomNumberGenerator
    private final List<RandomCharacter> characters
    private final int                   totalCharacters

    RandomStringGenerator(Random randomNumberGenerator, String characters) {
        this.randomNumberGenerator = randomNumberGenerator

        // Convert the string into an array of strings, each representing a single Unicode code point.
        // The Unicode code points are bundled with their size in UTF-8.
        this.characters = []
        characters.codePoints().forEach({i ->
            String character = new String(new int[] {i}, 0, 1)
            this.characters.add(new RandomCharacter(character))
        })

        // Precompute the size of the array.
        totalCharacters = this.characters.size()
    }

    /** Generates a random String whose representation in UTF-8 is as most {@code maxLengthInBytes} */
    String nextRandomString(int maxLengthInBytes) {
        int stringLengthInBytes = randomNumberGenerator.nextInt(maxLengthInBytes + 1)
        StringBuilder stringBuilder = new StringBuilder(stringLengthInBytes)

        int currentLengthInBytes = 0
        for (;;) {
            def randomCharacter = characters[randomNumberGenerator.nextInt() % totalCharacters]

            // Make sure that we can add this character without going over the limit
            currentLengthInBytes += randomCharacter.characterLengthInBytes
            if (stringLengthInBytes < currentLengthInBytes) {
                break
            }

            stringBuilder.append(randomCharacter.character)
        }

        return stringBuilder.toString()
    }

    /** Generates a random String that matches a given regular expression */
    String nextRandomString(int maxLengthInBytes, Pattern regularExpression) {
        String string = nextRandomString(maxLengthInBytes)
        while (!(string ==~ regularExpression)) {
            string = nextRandomString(maxLengthInBytes)
        }
        return string
    }
}