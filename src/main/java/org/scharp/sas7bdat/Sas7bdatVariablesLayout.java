///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A collection of variables in a sas7bdat file that knows how variables are laid out */
class Sas7bdatVariablesLayout {

    private final List<Variable> variables;
    private final int[] physicalOffsets;
    private final int rowLength;

    Sas7bdatVariablesLayout(List<Variable> variablesList) {
        variables = new ArrayList<>(variablesList); // copy to a class that has O(1) random access
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

    private static long daysBetween(Temporal epoch, Temporal dateTime) {
        final long daysSinceSasEpochLong = epoch.until(dateTime, ChronoUnit.DAYS);
        final double daysSinceSasEpochDouble = Long.valueOf(daysSinceSasEpochLong).doubleValue();
        return Double.doubleToRawLongBits(daysSinceSasEpochDouble);
    }

    private static long secondsBetween(Temporal epoch, Temporal dateTime) {
        // SAS timestamps support sub-second granularity.
        final long nanoSecondsSinceSasEpochLong = epoch.until(dateTime, ChronoUnit.NANOS);
        final double unitsSinceSasEpochDouble = Long.valueOf(nanoSecondsSinceSasEpochLong).doubleValue();
        return Double.doubleToRawLongBits(unitsSinceSasEpochDouble / 1_000_000_000);
    }

    /**
     * Serializes an observation (list of variable values) to a buffer.
     *
     * @param buffer
     *     The buffer to which the values should be serialized
     * @param offsetOfObservation
     *     The offset within {@code buffer} where the observation should be written.
     * @param observation
     *     A list of values which correspond to the variables that were given in the constructor.
     *
     * @throws NullPointerException
     *     If {@code observation} has a {@code null} value is given to a variable whose type is
     *     {@code VariableType.CHARACTER}.
     * @throws IllegalArgumentException
     *     if {@code observation} doesn't contain values that conforms to the {@code variables} that was given to this
     *     object's constructor.
     */
    void writeObservation(byte[] buffer, int offsetOfObservation, List<Object> observation) {

        // Check that the observation has as many values as there are variables.
        if (totalVariables() != observation.size()) {
            throw new IllegalArgumentException(
                "observation has too " +
                    (totalVariables() < observation.size() ? "few" : "many") +
                    " values, expected " + totalVariables() + " but got " + observation.size());
        }

        // Use an iterator in case the given List doesn't have O(1) random access.
        int i = 0;
        for (Object value : observation) {
            Variable variable = variables.get(i);

            final byte[] valueBytes;
            if (VariableType.CHARACTER == variable.type()) {
                // CHARACTER types only accept String objects (not even null).
                if (value == null) {
                    throw new NullPointerException(
                        "null given as a value to " + variable.name() + ", which has a CHARACTER type");
                }
                if (!(value instanceof String stringValue)) {
                    throw new IllegalArgumentException(
                        "A " + value.getClass().getTypeName() + " was given as a value to the variable named " +
                            variable.name() + ", which has a CHARACTER type (CHARACTER values must be of type java.lang.String)");
                }

                // Check that the value's length fits into the data without truncation.
                valueBytes = stringValue.getBytes(StandardCharsets.UTF_8);
                if (variable.length() < valueBytes.length) {
                    throw new IllegalArgumentException(
                        "A value of " + valueBytes.length + " bytes was given to the variable named " +
                            variable.name() + ", which has a length of " + variable.length());
                }
            } else {
                // NUMERIC types accept null, MissingValue, Number, and LocalDate objects.
                // Note: This can be replaced with Pattern Matching for switch in Java 21.
                final long valueBits;
                if (value == null) {
                    valueBits = MissingValue.STANDARD.rawLongBits();

                } else if (value instanceof MissingValue missingValue) {
                    valueBits = missingValue.rawLongBits();

                } else if (value instanceof Number numberValue) {
                    valueBits = Double.doubleToRawLongBits(numberValue.doubleValue());

                } else if (value instanceof LocalDate localDate) {
                    // SAS dates are numeric values given as the number of days since 1960-01-01.
                    valueBits = daysBetween(LocalDate.of(1960, 1, 1), localDate);

                } else if (value instanceof LocalTime localTime) {
                    // SAS times are numeric values given as the number of seconds since midnight.
                    valueBits = secondsBetween(LocalTime.MIDNIGHT, localTime);

                } else if (value instanceof LocalDateTime localDateTime) {
                    // SAS timestamps are numeric values given as the number of seconds since 1960-01-01T00:00:00.
                    valueBits = secondsBetween(LocalDateTime.of(1960, 1, 1, 0, 0), localDateTime);

                } else {
                    throw new IllegalArgumentException(
                        "A " + value.getClass().getTypeName() + " was given as a value to the variable named " +
                            variable.name() + ", which has a NUMERIC type " +
                            "(NUMERIC values must be null or of type " +
                            MissingValue.class.getCanonicalName() + ", " +
                            LocalDate.class.getCanonicalName() + ", " +
                            LocalTime.class.getCanonicalName() + ", " +
                            LocalDateTime.class.getCanonicalName() + ", or " +
                            Number.class.getCanonicalName() + ")");
                }

                valueBytes = new byte[] {
                    (byte) (valueBits),
                    (byte) (valueBits >> 8),
                    (byte) (valueBits >> 16),
                    (byte) (valueBits >> 24),
                    (byte) (valueBits >> 32),
                    (byte) (valueBits >> 40),
                    (byte) (valueBits >> 48),
                    (byte) (valueBits >> 56),
                };
            }

            final int offsetOfValue = offsetOfObservation + physicalOffsets[i];
            assert offsetOfValue + variable.length() <= buffer.length;
            assert valueBytes.length <= variable.length();

            // Copy the data
            System.arraycopy(valueBytes, 0, buffer, offsetOfValue, valueBytes.length);

            // Pad the data
            Arrays.fill(buffer, offsetOfValue + valueBytes.length, offsetOfValue + variable.length(), (byte) ' ');

            i++;
        }
    }

    int rowLength() {
        return rowLength;
    }

    int totalVariables() {
        return physicalOffsets.length;
    }

    /**
     * Returns a list of variables that is equal to the one given in the constructor.  This list is not modifiable.
     *
     * @return An unmodifiable list of variables.  This is never {@code null}.
     */
    List<Variable> variables() {
        return Collections.unmodifiableList(variables);
    }

    /**
     * Returns the row offset of each variable's data as a list of Integers.
     * <p>
     * The list is given in the same order as the variables. Note that the offsets may not be monotonically increasing
     * as the physical order of the variable's values do not necessarily match the logical order within the dataset.
     *
     * @return An unmodifiable list of integers
     */
    List<Integer> physicalOffsets() {
        List<Integer> list = new ArrayList<>(physicalOffsets.length);
        for (int physicalOffset : physicalOffsets) {
            list.add(physicalOffset);
        }
        return Collections.unmodifiableList(list);
    }
}