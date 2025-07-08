package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link Sas7bdatVariables}. */
public class Sas7bdatVariablesTest {

    @Test
    void testAllCharacterVariables() {
        List<Variable> variableList = List.of(
            new Variable(
                "VARIABLE_1",
                VariableType.CHARACTER,
                10, // string length
                "label 1", // label
                new Format("$CHAR", 18), // output format
                Format.UNSPECIFIED), //
            new Variable(
                "VARIABLE_2",
                VariableType.CHARACTER,
                1, // string length
                "label 2",
                new Format("$CHAR", 2),
                Format.UNSPECIFIED),
            new Variable(
                "VARIABLE_3",
                VariableType.CHARACTER,
                8,
                "label 3",
                new Format("", 5),
                Format.UNSPECIFIED));

        Sas7bdatVariables variables = new Sas7bdatVariables(variableList);
        assertEquals(3, variables.totalVariables());
        assertEquals(10 + 1 + 8, variables.rowLength());
        assertArrayEquals(new int[] { 0, 10, 11 }, variables.physicalOffsets);

        byte[] actualData = new byte[30];
        variables.writeObservation(actualData, 0, List.of("Variable 1", "2", "EightChr")); // all full-length

        byte[] expectedData = new byte[] {
            'V', 'a', 'r', 'i', 'a', 'b', 'l', 'e', ' ', '1', // 10
            '2', // 1
            'E', 'i', 'g', 'h', 't', 'C', 'h', 'r', // 8
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,  // 11
        };
        assertArrayEquals(expectedData, actualData);

        // Again at a non-zero offset, with empty string
        Arrays.fill(actualData, (byte) -1);
        variables.writeObservation(actualData, 4, List.of("", "", "")); // all empty
        expectedData = new byte[] {
            -1, -1, -1, -1, // before offset (4 bytes)
            ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', // 10 bytes
            ' ', // 1 byte
            ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', // 8
            -1, -1, -1, -1, -1, -1, -1,  // after observation (7 bytes)
        };
        assertArrayEquals(expectedData, actualData);

    }

    @Test
    void testAllNumericVariables() {
        List<Variable> variableList = List.of(
            new Variable(
                "num1",
                VariableType.NUMERIC,
                8, // length is always 8
                "a number",
                Format.UNSPECIFIED, // output format
                Format.UNSPECIFIED),
            new Variable(
                "number2",
                VariableType.NUMERIC,
                8, // length
                "another number",
                new Format("", 5, 2),
                Format.UNSPECIFIED),
            new Variable(
                "Number3",
                VariableType.NUMERIC,
                8,
                "a third number",
                new Format("", 5),
                Format.UNSPECIFIED),
            new Variable(
                "Number 4",
                VariableType.NUMERIC,
                8,
                "the last number",
                new Format("", 5),
                Format.UNSPECIFIED));

        Sas7bdatVariables variables = new Sas7bdatVariables(variableList);
        assertEquals(4, variables.totalVariables());
        assertEquals(8 * 4, variables.rowLength());
        assertArrayEquals(new int[] { 0, 8, 16, 24 }, variables.physicalOffsets);

        // Write 1 four different ways.
        byte[] actualData = new byte[32];
        variables.writeObservation(actualData, 0,
            List.of(Integer.valueOf(1), Long.valueOf(1), Short.valueOf((short) 1), Byte.valueOf((byte) 1)));
        assertArrayEquals(
            new byte[] {
                0, 0, 0, 0, 0, 0, -16, 63, // Integer
                0, 0, 0, 0, 0, 0, -16, 63, // Long
                0, 0, 0, 0, 0, 0, -16, 63, // Short
                0, 0, 0, 0, 0, 0, -16, 63, // Byte
            },
            actualData);

        Arrays.fill(actualData, (byte) -1);
        variables.writeObservation(actualData, 0,
            List.of(Float.valueOf(1), Double.valueOf(1), BigInteger.valueOf(1), BigDecimal.valueOf(1)));
        assertArrayEquals(
            new byte[] {
                0, 0, 0, 0, 0, 0, -16, 63, // Float
                0, 0, 0, 0, 0, 0, -16, 63, // Double
                0, 0, 0, 0, 0, 0, -16, 63, // BigInteger
                0, 0, 0, 0, 0, 0, -16, 63, // BigDecimal
            },
            actualData);

        variables.writeObservation(actualData, 0, List.of(10_000, 100_000, 1_000_000, 10_000_000_000L));
        assertArrayEquals(
            new byte[] {
                0, 0, 0, 0, 0, -120, -61, 64, // 10,000
                0, 0, 0, 0, 0, 106, -8, 64, // 100,000
                0, 0, 0, 0, -128, -124, 46, 65, // 1,000,000
                0, 0, 0, 32, 95, -96, 2, 66, // 10,000,000
            },
            actualData);

        variables.writeObservation(actualData, 0, List.of(Double.MAX_VALUE, Double.MIN_VALUE, 1E30, -1E30));
        assertArrayEquals(
            new byte[] {
                -1, -1, -1, -1, -1, -1, -17, 127, // max
                1, 0, 0, 0, 0, 0, 0, 0, // min
                -22, -116, -96, 57, 89, 62, 41, 70, // 1E30
                -22, -116, -96, 57, 89, 62, 41, -58, // -1E30
            },
            actualData);

        variables.writeObservation(actualData, 0, Arrays.asList(null, -1, -2, -3));
        assertArrayEquals(
            new byte[] {
                0, 0, 0, 0, 0, -2, -1, -1, // null
                0, 0, 0, 0, 0, 0, -16, -65, // -1
                0, 0, 0, 0, 0, 0, 0, -64, // -2
                0, 0, 0, 0, 0, 0, 8, -64, // -3
            },
            actualData);

    }

    @Test
    void testMixedTypeVariables() {
        List<Variable> variableList = List.of(
            new Variable(
                "VARIABLE_1",
                VariableType.CHARACTER,
                10, // string length
                "label 1", // label
                new Format("$CHAR", 18), // output format
                Format.UNSPECIFIED), //
            new Variable(
                "VARIABLE_2",
                VariableType.NUMERIC,
                8, // length
                "label 2",
                new Format("", 5, 2),
                Format.UNSPECIFIED),
            new Variable(
                "VARIABLE_3",
                VariableType.CHARACTER,
                13,
                "label 3",
                new Format("", 5),
                Format.UNSPECIFIED),
            new Variable(
                "NUMERIC_2",
                VariableType.NUMERIC,
                8,
                "label 3",
                new Format("", 5),
                Format.UNSPECIFIED));

        Sas7bdatVariables variables = new Sas7bdatVariables(variableList);
        assertEquals(4, variables.totalVariables());
        assertEquals(10 + 8 + 13 + 8 + 1, variables.rowLength()); // +1 rounds up to the nearest 8-byte boundary
        assertArrayEquals(new int[] { 16, 0, 26, 8 }, variables.physicalOffsets); // numerics first

        byte[] actualData = new byte[40];
        variables.writeObservation(actualData, 0, List.of("Variable 1", 2, "ThirteenChars", -1)); // all full-length

        assertArrayEquals(
            new byte[] {
                0, 0, 0, 0, 0, 0, 0, 64, // var 2 (2)
                0, 0, 0, 0, 0, 0, -16, -65, // var 4 (-1)
                'V', 'a', 'r', 'i', 'a', 'b', 'l', 'e', ' ', '1', // var 1 ("Variable 1")
                'T', 'h', 'i', 'r', 't', 'e', 'e', 'n', 'C', 'h', 'a', 'r', 's', // var 3 ("ThirteenChars")
                0, // padding
            },
            actualData);
    }
}