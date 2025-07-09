package org.scharp.sas7bdat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A collection of variables in a sas7bdat file that knows how variables are laid out */
class Sas7bdatVariables {

    private static final byte[] MISSING_NUMERIC = { 0, 0, 0, 0, 0, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF };

    private final List<Variable> variables;
    private final int[] physicalOffsets;
    private final int rowLength;

    Sas7bdatVariables(List<Variable> variablesList) {
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
            assert offsetOfValue + variable.length() <= buffer.length;
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
     * as the physical order of the variable's values do not necessarily match the logical order within the data set.
     *
     * @return An unmodifiable list of integers
     */
    List<Integer> physicalOffsets() {
        List<Integer> list = new ArrayList<>(physicalOffsets.length);
        for (int i = 0; i < physicalOffsets.length; i++) {
            list.add(physicalOffsets[i]);
        }
        return Collections.unmodifiableList(list);
    }
}