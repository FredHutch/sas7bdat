package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;
import org.scharp.sas7bdat.Variable.Builder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link Variable}. */
public class VariableTest {

    private static void assertVariable(Variable variable, String expectedName, VariableType expectedType,
        int expectedLength, String expectedLabel, Format expectedInputFormat, Format expectedOutputFormat) {

        assertEquals(expectedName, variable.name(), "incorrect name");
        assertEquals(expectedType, variable.type(), "incorrect type");
        assertEquals(expectedLength, variable.length(), "incorrect length");
        assertEquals(expectedLabel, variable.label(), "incorrect label");
        assertEquals(expectedInputFormat, variable.inputFormat(), "incorrect input format");
        assertEquals(expectedOutputFormat, variable.outputFormat(), "incorrect output format");
    }

    @Test
    void setNullName() {
        // Create a builder with all required fields set.
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).name("ORIGINAL");

        // Setting the name to null should throw an exception.
        Exception exception = assertThrows(NullPointerException.class, () -> builder.name(null));
        assertEquals("name must not be null", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Variable variable = builder.build();
        assertVariable(variable, "ORIGINAL", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void setEmptyName() {
        // Create a builder with all required fields set.
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).name("ORIGINAL");

        // Setting the name to the empty string should throw an exception.
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.name(""));
        assertEquals("variable names cannot be blank", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Variable variable = builder.build();
        assertVariable(variable, "ORIGINAL", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void setLongName() {
        // Create a builder with all required fields set.
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).name("ORIGINAL");

        // Setting the name to a value that's 32 characters but 33 bytes in UTF-8 should be an error.
        final String sigma = "\u03C3"; // GREEK SMALL LETTER SIGMA (two bytes in UTF-8)
        String badName = sigma + "x".repeat(31);
        assertEquals(32, badName.length(), "TEST BUG: not testing encoding expansion");
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.name(badName));
        assertEquals("variable names must not be longer than 32 bytes when encoded with UTF-8", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Variable variable = builder.build();
        assertVariable(variable, "ORIGINAL", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void setNullType() {
        // Create a builder with all required fields set.
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).name("NAME");

        // Setting the type to null should throw an exception.
        Exception exception = assertThrows(NullPointerException.class, () -> builder.type(null));
        assertEquals("type must not be null", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Variable variable = builder.build();
        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void setZeroLength() {
        // Create a builder with all required fields set.
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).name("NAME");

        // Setting the length to 0 should throw an exception.
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.length(0));
        assertEquals("variable length must be positive", exception.getMessage());

        // Setting the length to -1 should also throw an exception.
        exception = assertThrows(IllegalArgumentException.class, () -> builder.length(-1));
        assertEquals("variable length must be positive", exception.getMessage());

        // The exceptions shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Variable variable = builder.build();
        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void setLongLength() {
        // Create a builder with all required fields set.
        Variable.Builder builder = Variable.builder().type(VariableType.CHARACTER).length(10).name("NAME");

        // Setting the length to 32768 should throw an exception.
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.length(Short.MAX_VALUE + 1));
        assertEquals("variable length cannot be greater than 32767", exception.getMessage());

        // The exceptions shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Variable variable = builder.build();
        assertVariable(variable, "NAME", VariableType.CHARACTER, 10, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void setNullLabel() {
        // Create a builder with all required fields set.
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).name("NAME");

        // Setting the label to null should throw an exception.
        Exception exception = assertThrows(NullPointerException.class, () -> builder.label(null));
        assertEquals("label must not be null", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Variable variable = builder.build();
        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void setLongLabel() {
        // Create a builder with all required fields set.
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).name("NAME").label("LABEL");

        // Setting the name to a value that's 256 characters but 257 bytes in UTF-8 should be an error.
        final String sigma = "\u03C3"; // GREEK SMALL LETTER SIGMA (two bytes in UTF-8)
        String badLabel = sigma + "x".repeat(255);
        assertEquals(256, badLabel.length(), "TEST BUG: not testing encoding expansion");
        Exception exception = assertThrows(IllegalArgumentException.class, () -> builder.label(badLabel));
        assertEquals("variable labels must not be longer than 256 bytes when encoded with UTF-8",
            exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Variable variable = builder.build();
        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "LABEL", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void setNullOutputFormat() {
        // Create a builder with all required fields set.
        final Format outputFormat = new Format("", 5);
        Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).name("VO").outputFormat(outputFormat);

        // Setting the output format to null should throw an exception.
        Exception exception = assertThrows(NullPointerException.class, () -> builder.outputFormat(null));
        assertEquals("outputFormat must not be null", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Variable variable = builder.build();
        assertVariable(variable, "VO", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, outputFormat);
    }

    @Test
    void setNullInputFormat() {
        // Create a builder with all required fields set.
        final Format inputFormat = new Format("", 5);
        Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8).name("NAME").inputFormat(inputFormat);

        // Setting the input format to null should throw an exception.
        Exception exception = assertThrows(NullPointerException.class, () -> builder.inputFormat(null));
        assertEquals("inputFormat must not be null", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to ignore the error and continue building.
        Variable variable = builder.build();
        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "", inputFormat, Format.UNSPECIFIED);
    }

    @Test
    void buildWithoutName() {
        Variable.Builder builder = Variable.builder().type(VariableType.NUMERIC).length(8);

        // building without setting a name is illegal.
        Exception exception = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("name must be set", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to fix the error and continue building.
        Variable variable = builder.name("NAME").build();

        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void buildWithoutType() {
        Variable.Builder builder = Variable.builder().length(8).name("NAME");

        // building without setting a type is illegal.
        Exception exception = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("type must be set", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to fix the error and continue building.
        Variable variable = builder.type(VariableType.NUMERIC).build();

        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void buildWithoutLength() {
        Variable.Builder builder = Variable.builder().type(VariableType.CHARACTER).name("NAME");

        // building without setting a length is illegal.
        Exception exception = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("length must be set", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to fix the error and continue building.
        Variable variable = builder.length(5).build();

        assertVariable(variable, "NAME", VariableType.CHARACTER, 5, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void buildNumericWithLengthNot8() {
        Builder builder = Variable.builder().name("NAME").length(7).type(VariableType.NUMERIC);

        // Building a numeric variable with a length that's not 8 is illegal.
        Exception exception = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("numeric variables must have a length of 8", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to fix the error and continue building.
        Variable variable = builder.length(8).build();

        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void buildNumericInputFormatForCharacterVariable() {
        // Create a builder for a character variable and a numeric input format.
        Format characterFormat = new Format("$", 5);
        Format numericFormat = new Format("", 10);
        Builder builder = Variable.builder().name("NAME").length(7).type(VariableType.CHARACTER)
            .inputFormat(numericFormat);

        // Building a character variable with numeric input format is illegal.
        Exception exception = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("inputFormat \"10.\" is not legal for CHARACTER variables", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to fix the error and continue building.
        Variable variable = builder.inputFormat(characterFormat).build();

        assertVariable(variable, "NAME", VariableType.CHARACTER, 7, "", characterFormat, Format.UNSPECIFIED);
    }

    @Test
    void buildCharacterInputFormatForNumericVariable() {
        // Create a builder for a numeric variable and a character input format.
        Format characterFormat = new Format("$", 5);
        Format numericFormat = new Format("BEST", 10);
        Builder builder = Variable.builder().name("NAME").length(8).type(VariableType.NUMERIC)
            .inputFormat(characterFormat);

        // Building a numeric variable with character input format is illegal.
        Exception exception = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("inputFormat \"$5.\" is not legal for NUMERIC variables", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to fix the error and continue building.
        Variable variable = builder.inputFormat(numericFormat).build();

        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "", numericFormat, Format.UNSPECIFIED);
    }

    @Test
    void buildNumericOutputFormatForCharacterVariable() {
        // Create a builder for a character variable and a numeric output format.
        Format characterFormat = new Format("$ascii", 11);
        Format numericFormat = new Format("float", 6, 2);
        Builder builder = Variable.builder().name("NAME").length(8).type(VariableType.CHARACTER)
            .outputFormat(numericFormat);

        // Building a character variable with numeric input format is illegal.
        Exception exception = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("outputFormat \"float6.2\" is not legal for CHARACTER variables", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to fix the error and continue building.
        Variable variable = builder.outputFormat(characterFormat).build();

        assertVariable(variable, "NAME", VariableType.CHARACTER, 8, "", Format.UNSPECIFIED, characterFormat);
    }

    @Test
    void buildCharacterOutputFormatForNumericVariable() {
        // Create a builder for a numeric variable and a character output format.
        Format characterFormat = new Format("$CHAR", 200);
        Format numericFormat = new Format("BESTD", 10, 2);
        Builder builder = Variable.builder().name("NAME").length(8).type(VariableType.NUMERIC)
            .outputFormat(characterFormat);

        // Building a numeric variable with character input format is illegal.
        Exception exception = assertThrows(IllegalStateException.class, builder::build);
        assertEquals("outputFormat \"$CHAR200.\" is not legal for NUMERIC variables", exception.getMessage());

        // The exception shouldn't corrupt the state of the builder.
        // I don't expect that anyone would do this, but it should be legal to fix the error and continue building.
        Variable variable = builder.outputFormat(numericFormat).build();

        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "", Format.UNSPECIFIED, numericFormat);
    }

    @Test
    void buildWithRequiredFieldsOnly() {
        Variable variable = Variable.builder().
            name("NAME").
            type(VariableType.CHARACTER).
            length(10).
            build();
        assertVariable(variable, "NAME", VariableType.CHARACTER, 10, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void buildWithRedundantSetting() {

        Variable variable = Variable.builder()
            // Set all fields to something
            .name("DISCARD")
            .length(1)
            .type(VariableType.CHARACTER)
            .label("DISCARD")
            .inputFormat(new Format("$DISCARD", 5))
            .outputFormat(new Format("$DISCARD", 10))

            // set all fields to something else
            .name("NAME")
            .length(8)
            .type(VariableType.NUMERIC)
            .label("LABEL")
            .inputFormat(new Format("IN", 1))
            .outputFormat(new Format("OUT", 2))

            // build
            .build();

        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "LABEL", new Format("IN", 1), new Format("OUT", 2));
    }

    @Test
    void buildWithAllFieldsSet() {
        Variable variable = Variable.builder()
            .name("NAME")
            .length(8)
            .type(VariableType.NUMERIC)
            .label("LABEL")
            .inputFormat(new Format("IN", 1))
            .outputFormat(new Format("OUT", 2))
            .build();

        assertVariable(variable, "NAME", VariableType.NUMERIC, 8, "LABEL", new Format("IN", 1), new Format("OUT", 2));
    }

    @Test
    void buildWithMinimumValues() {
        Variable variable = Variable.builder()
            .name("V")
            .length(1)
            .type(VariableType.CHARACTER)
            .label("")
            .inputFormat(Format.UNSPECIFIED)
            .outputFormat(Format.UNSPECIFIED)
            .build();

        assertVariable(variable, "V", VariableType.CHARACTER, 1, "", Format.UNSPECIFIED, Format.UNSPECIFIED);
    }

    @Test
    void buildWithMaximumValues() {
        final String longName = "V".repeat(32);
        final String longLabel = "L".repeat(256);
        final Format longFormat = new Format("$LONGFMT", Short.MAX_VALUE);

        Variable variable = Variable.builder()
            .name(longName)
            .length(Short.MAX_VALUE)
            .type(VariableType.CHARACTER)
            .label(longLabel)
            .inputFormat(longFormat)
            .outputFormat(longFormat)
            .build();

        assertVariable(variable, longName, VariableType.CHARACTER, Short.MAX_VALUE, longLabel, longFormat, longFormat);
    }

    /** Tests that {@link Builder#build} can be invoked multiple times.  This is weird but legal */
    @Test
    void buildMultipleTimes() {
        // Build a variable.
        Builder builder = Variable.builder().name("VAR1").length(1).type(VariableType.CHARACTER).label("label 1");
        Variable variable = builder.build();
        assertVariable(variable, "VAR1", VariableType.CHARACTER, 1, "label 1", Format.UNSPECIFIED, Format.UNSPECIFIED);

        // Build a second variable without changing anything.
        variable = builder.build();
        assertVariable(variable, "VAR1", VariableType.CHARACTER, 1, "label 1", Format.UNSPECIFIED, Format.UNSPECIFIED);

        // Change some fields and build a third variable.
        Format outputFormat = new Format("$UPCASE", 1);
        variable = builder.name("VAR2").label("label 2").outputFormat(outputFormat).build();
        assertVariable(variable, "VAR2", VariableType.CHARACTER, 1, "label 2", Format.UNSPECIFIED, outputFormat);
    }

    static private String copy(String string) {
        return new String(string);
    }

    /**
     * Tests {@link Variable#hashCode()}.
     */
    @Test
    public void testHashCode() {
        // Create a variable with all fields set to distinct values.
        Variable variable = Variable.builder().
            name("MY_VARIABLE").
            type(VariableType.CHARACTER).
            length(200).
            label("My Label").
            outputFormat(new Format("$UPCASE", 5)).
            inputFormat(new Format("$ASCII", 4)).
            build();

        // Create a copy that has the same strings but from different references.
        Variable variableCopy = Variable.builder().
            name(copy("MY_VARIABLE")).
            type(VariableType.CHARACTER).
            length(200).
            label(copy("My Label")).
            outputFormat(new Format(copy("$UPCASE"), 5)).
            inputFormat(new Format(copy("$ASCII"), 4)).
            build();

        // The copy must hash to the same value as the original.
        assertEquals(variable.hashCode(), variableCopy.hashCode());

        // Create a pair of equal variables that are as minimally set as possible.
        Variable minimalVariable = Variable.builder().name("a").type(VariableType.NUMERIC).length(8).build();
        Variable minimalVariableCopy = Variable.builder().name(copy("a")).type(VariableType.NUMERIC).length(8).build();

        // The copy must hash to the same value as the original.
        assertEquals(minimalVariable.hashCode(), minimalVariableCopy.hashCode());
    }

    /**
     * Tests {@link Variable#equals(Object)}.
     */
    @SuppressWarnings({ "unlikely-arg-type", "EqualsBetweenInconvertibleTypes" })
    @Test
    public void testEquals() {
        // Create a variable with all fields set to distinct values.
        Variable variable = Variable.builder().
            name("MY_VARIABLE").
            type(VariableType.CHARACTER).
            length(8).
            label("My Label").
            outputFormat(new Format("$UPCASE", 5)).
            inputFormat(new Format("$ASCII", 4)).
            build();

        // Create a copy that has the same strings but from different references.
        Variable variableCopy = Variable.builder().
            name(copy("MY_VARIABLE")).
            type(VariableType.CHARACTER).
            length(8).
            label(copy("My Label")).
            outputFormat(new Format(copy("$UPCASE"), 5)).
            inputFormat(new Format(copy("$ASCII"), 4)).
            build();

        // Create a pair of equal numeric variables (to test variations in numeric formats)
        Variable numericVariable = Variable.builder().
            name("MY_VARIABLE").
            type(VariableType.NUMERIC).
            length(8).
            label("My Label").
            outputFormat(new Format("f", 5, 2)).
            inputFormat(new Format("d", 4, 1)).
            build();

        // Create a copy that has the same strings but from different references.
        Variable numericVariableCopy = Variable.builder().
            name(copy("MY_VARIABLE")).
            type(VariableType.NUMERIC).
            length(8).
            label(copy("My Label")).
            outputFormat(new Format(copy("f"), 5, 2)).
            inputFormat(new Format(copy("d"), 4, 1)).
            build();

        // Create formats that only differ in exactly one field (and only by case for strings).
        Variable differentName = Variable.builder().
            name("My_VARIABLE").
            type(variable.type()).
            length(variable.length()).
            label(variable.label()).
            outputFormat(variable.outputFormat()).
            inputFormat(variable.inputFormat()).
            build();
        Variable differentLength = Variable.builder().
            name(variable.name()).
            type(variable.type()).
            length(variable.length() + 1).
            label(variable.label()).
            outputFormat(variable.outputFormat()).
            inputFormat(variable.inputFormat()).
            build();
        Variable differentLabel = Variable.builder().
            name(variable.name()).
            type(variable.type()).
            length(variable.length()).
            label("My label").
            outputFormat(variable.outputFormat()).
            inputFormat(variable.inputFormat()).
            build();
        Variable differentOutputFormatName = Variable.builder().
            name(variable.name()).
            type(variable.type()).
            length(variable.length()).
            label(variable.label()).
            outputFormat(new Format("$upcase", 5)).
            inputFormat(variable.inputFormat()).
            build();
        Variable differentOutputFormatWidth = Variable.builder().
            name(variable.name()).
            type(variable.type()).
            length(variable.length()).
            label(variable.label()).
            outputFormat(new Format(variableCopy.outputFormat().name(), variableCopy.outputFormat().width() + 1)).
            inputFormat(variable.inputFormat()).
            build();
        Variable differentOutputFormatDigits = Variable.builder().
            name(variable.name()).
            type(variable.type()).
            length(variable.length()).
            label(variable.label()).
            outputFormat(new Format(variableCopy.outputFormat().name(), variableCopy.outputFormat().width(), 1)).
            inputFormat(variable.inputFormat()).
            build();
        Variable differentInputFormatName = Variable.builder().
            name(variable.name()).
            type(variable.type()).
            length(variable.length()).
            label(variable.label()).
            outputFormat(variable.outputFormat()).
            inputFormat(new Format("$ascii", 4)).
            build();
        Variable differentInputFormatWidth = Variable.builder().
            name(variable.name()).
            type(variable.type()).
            length(variable.length()).
            label(variable.label()).
            outputFormat(variable.outputFormat()).
            inputFormat(new Format(variable.inputFormat().name(), variable.inputFormat().width() + 1)).
            build();
        Variable differentInputFormatDigits = Variable.builder().
            name(variable.name()).
            type(variable.type()).
            length(variable.length()).
            label(variable.label()).
            outputFormat(variable.outputFormat()).
            inputFormat(new Format(variable.inputFormat().name(), variable.inputFormat().width(), 2)).
            build();

        // Create a pair of equal variables that are as minimally set as possible.
        Variable minimalVariable = Variable.builder().name("a").type(VariableType.CHARACTER).length(8).build();
        Variable minimalVariableCopy = Variable.builder().name("a").type(VariableType.CHARACTER).length(8).build();

        // Create a variable that differs from minimalVariable only by type.
        // Because character formats can't be used by numeric variables, this is the only way we can test that
        // a variable's type in considered by equals().
        Variable minimalVariableWithDifferentType = Variable.builder().
            name(minimalVariable.name()).
            type(VariableType.NUMERIC).
            length(minimalVariable.length())
            .build();

        List<Variable> allVariables = List.of(
            variable,
            variableCopy,

            numericVariable,
            numericVariableCopy,

            differentName,
            differentLength,
            differentLabel,
            differentOutputFormatName,
            differentOutputFormatWidth,
            differentOutputFormatDigits,
            differentInputFormatName,
            differentInputFormatWidth,
            differentInputFormatDigits,

            minimalVariable,
            minimalVariableCopy);

        // Equals is reflexive (special case)
        for (Variable currentVariable : allVariables) {
            assertTrue(currentVariable.equals(currentVariable));
        }

        // Equivalent variables are equal.
        assertTrue(variable.equals(variableCopy));
        assertTrue(minimalVariable.equals(minimalVariableCopy));

        // Different variables are not equal.
        assertFalse(variable.equals(numericVariable));
        assertFalse(variable.equals(differentName));
        assertFalse(variable.equals(differentLength));
        assertFalse(variable.equals(differentLabel));
        assertFalse(variable.equals(differentOutputFormatName));
        assertFalse(variable.equals(differentOutputFormatWidth));
        assertFalse(variable.equals(differentOutputFormatDigits));
        assertFalse(variable.equals(differentInputFormatName));
        assertFalse(variable.equals(differentInputFormatWidth));
        assertFalse(variable.equals(differentInputFormatDigits));
        assertFalse(variable.equals(minimalVariable));
        assertFalse(minimalVariable.equals(minimalVariableWithDifferentType));

        // Equality is symmetric.
        assertTrue(variableCopy.equals(variable));
        assertTrue(numericVariableCopy.equals(numericVariable));
        assertTrue(minimalVariableCopy.equals(minimalVariable));

        // Nothing is equal to null.
        for (Variable currentVariable : allVariables) {
            assertFalse(currentVariable.equals(null));
        }

        // Test comparing against something that isn't a Variable
        assertFalse(variable.equals(variable.name()));
    }
}