package org.scharp.sas7bdat;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link ColumnHashTable}. */
public class ColumnHashTableTest {

    @Test
    void testMinimalHashTable() {
        // A hash table must have at least two variables.
        List<Variable> variables = List.of(
            Variable.builder().name("\u0001").type(VariableType.CHARACTER).length(1).build(),
            Variable.builder().name("\u0002").type(VariableType.CHARACTER).length(1).build());

        ColumnHashTable hashTable = new ColumnHashTable(variables);
        assertEquals(2, hashTable.totalVariables());
        assertEquals(2, hashTable.totalBuckets());
        assertEquals(2, hashTable.getHashTableBucket(0));
        assertEquals(1, hashTable.getHashTableBucket(1));
    }

    @Test
    void testFourNamesLength1() {
        // Tests:
        // * Four variales (one empty in table)
        // * No hash collisions
        // * Variables with one
        List<Variable> variables = List.of(
            Variable.builder().name("A").type(VariableType.CHARACTER).length(2).build(),
            Variable.builder().name("B").type(VariableType.CHARACTER).length(2).build(),
            Variable.builder().name("C").type(VariableType.NUMERIC).length(8).build(),
            Variable.builder().name("D").type(VariableType.NUMERIC).length(8).build());

        ColumnHashTable hashTable = new ColumnHashTable(variables);
        assertEquals(4, hashTable.totalVariables());
        assertEquals(5, hashTable.totalBuckets());
        assertEquals(1, hashTable.getHashTableBucket(0));
        assertEquals(2, hashTable.getHashTableBucket(1));
        assertEquals(3, hashTable.getHashTableBucket(2));
        assertEquals(4, hashTable.getHashTableBucket(3));
        assertEquals(0, hashTable.getHashTableBucket(4));
    }

    @Test
    void testAllHashToZero() {
        // Tests when all variables have the same hash code (0).
        // Also tests 5 variable names.
        List<Variable> variables = List.of(
            Variable.builder().name("A".repeat(8)).type(VariableType.CHARACTER).length(2).build(),
            Variable.builder().name("B".repeat(8)).type(VariableType.CHARACTER).length(2).build(),
            Variable.builder().name("C".repeat(8)).type(VariableType.NUMERIC).length(8).build(),
            Variable.builder().name("D".repeat(8)).type(VariableType.NUMERIC).length(8).build(),
            Variable.builder().name("E".repeat(8)).type(VariableType.NUMERIC).length(8).build());

        ColumnHashTable hashTable = new ColumnHashTable(variables);
        assertEquals(5, hashTable.totalVariables());
        assertEquals(7, hashTable.totalBuckets());
        assertEquals(-1, hashTable.getHashTableBucket(0));
        assertEquals(-2, hashTable.getHashTableBucket(1));
        assertEquals(-3, hashTable.getHashTableBucket(2));
        assertEquals(-4, hashTable.getHashTableBucket(3));
        assertEquals(5, hashTable.getHashTableBucket(4));
        assertEquals(0, hashTable.getHashTableBucket(5));
        assertEquals(0, hashTable.getHashTableBucket(6));
    }

    @Test
    void testNegativeHashCode() {
        List<Variable> variables = List.of(
            Variable.builder().name("A").type(VariableType.CHARACTER).length(9).build(),
            Variable.builder().name("B").type(VariableType.CHARACTER).length(7).build(),
            Variable.builder().name("\u0089").type(VariableType.CHARACTER).length(4).build()); // negative hash code

        ColumnHashTable hashTable = new ColumnHashTable(variables);
        assertEquals(3, hashTable.totalVariables());
        assertEquals(3, hashTable.totalBuckets());
        assertEquals(2, hashTable.getHashTableBucket(0));
        assertEquals(3, hashTable.getHashTableBucket(1));
        assertEquals(1, hashTable.getHashTableBucket(2));
    }

    @Test
    void testUnicodeNames() {
        List<Variable> variables = List.of(
            Variable.builder().name("\u0394").type(VariableType.NUMERIC).length(8).build(),// GREEK CAPITAL LETTER DELTA
            Variable.builder().name("\u03C8").type(VariableType.NUMERIC).length(8).build(),// GREEK SMALL LETTER PSI
            Variable.builder().name("\uD83D\uDE00").type(VariableType.NUMERIC).length(8).build(), // GRINNING_FACE
            Variable.builder().name("\u0394".repeat(4)).type(VariableType.NUMERIC).length(8).build(),// DELTA x4
            Variable.builder().name("\u03C8".repeat(2)).type(VariableType.NUMERIC).length(8).build()); // PSI x2

        ColumnHashTable hashTable = new ColumnHashTable(variables);
        assertEquals(5, hashTable.totalVariables());
        assertEquals(7, hashTable.totalBuckets());
        assertEquals(-1, hashTable.getHashTableBucket(0)); // GREEK CAPITAL LETTER DELTA
        assertEquals(4, hashTable.getHashTableBucket(1)); // DELTA DELTA DELTA DELTA
        assertEquals(5, hashTable.getHashTableBucket(2)); // PSI PSI
        assertEquals(2, hashTable.getHashTableBucket(3)); // GREEK SMALL LETTER PSI
        assertEquals(0, hashTable.getHashTableBucket(4));
        assertEquals(0, hashTable.getHashTableBucket(5));
        assertEquals(3, hashTable.getHashTableBucket(6)); // GRINNING_FACE
    }

    static boolean isPrime(int n) {
        if (n <= 1) {
            return false;
        }

        for (int i = 2; i < n; i++) {
            if (n % i == 0) {
                // i divides evenly with n, which means n is not prime.
                return false;
            }
        }

        // no factors found.
        return true;
    }

    static Variable lookup(ColumnHashTable hashTable, List<Variable> variables, String variableName) {
        // Calculate the hash and probe interval as the unit-under-test does.
        int hashCode = ColumnHashTable.hash(variableName);

        int probeInterval = Integer.divideUnsigned(hashCode, hashTable.totalBuckets());
        probeInterval = Integer.remainderUnsigned(probeInterval, hashTable.totalBuckets());
        if (probeInterval == 0) {
            probeInterval = 1;
        }

        // Find the name in the hash table.
        int bucketIndex = Integer.remainderUnsigned(hashCode, hashTable.totalBuckets());
        for (int i = 0; i < hashTable.totalBuckets(); i++) {
            short currentBucketValue = hashTable.getHashTableBucket(bucketIndex);
            if (0 == currentBucketValue) {
                return null; // not found
            }

            // Get the name of the current bucket.
            int currentBucketIndex = Math.abs(currentBucketValue);
            Variable bucketVariable = variables.get(currentBucketIndex - 1);

            // If the name in the current bucket equals our name, then it's a match.
            if (variableName.equalsIgnoreCase(bucketVariable.name())) {
                return bucketVariable;
            }

            if (0 < currentBucketValue) {
                // This bucket didn't have a collision, so we can keep searching.
                return null;
            }

            // Move by the probe interval to the next bucket.
            bucketIndex = (bucketIndex + probeInterval) % hashTable.totalBuckets();
        }

        throw new AssertionError("probed more times than there are buckets");
    }

    /**
     * Asserts things that should be true of every ColumnHashTable.
     *
     * @param hashTable
     *      The hash table to check
     * @param variables
     *      The variables from which the hashTable was created.
     */
    static void assertValidHashTable(ColumnHashTable hashTable, List<Variable> variables) {
        assertEquals(variables.size(), hashTable.totalVariables());

        // Two things must be true about the hash table size.
        // 1) It can't be smaller than the number of variables.
        // 2) It must be a prime number.
        assertThat(hashTable.totalBuckets(), Matchers.greaterThanOrEqualTo(variables.size()));
        assertTrue(isPrime(hashTable.totalBuckets()));

        // The hash table should have every column exactly once.
        BitSet columnsInHashTable = new BitSet();
        for (int i = 0; i < hashTable.totalBuckets(); i++) {
            // Each bucket should be between -n + 1 and n.
            // The reason -n shouldn't be in the table is that it's placed last, so nothing
            // should collide with it.
            int bucket = hashTable.getHashTableBucket(i);
            assertThat(bucket, Matchers.greaterThanOrEqualTo(1 - variables.size()));
            assertThat(bucket, Matchers.lessThanOrEqualTo(variables.size()));

            if (0 != bucket) {
                if (bucket < 0) {
                    bucket = -bucket;
                }
                assertFalse(columnsInHashTable.get(bucket), bucket + " seen twice in hash table");
                columnsInHashTable.set(bucket);
            }
        }
        for (int i = 1; i < variables.size(); i++) {
            assertTrue(columnsInHashTable.get(i), i + " not seen in hash table");
        }

        // We should be able to look up each variable.
        for (Variable currentVariable : variables) {
            Variable foundVariable = lookup(hashTable, variables, currentVariable.name());
            assertNotNull(foundVariable, "Couldn't find " + currentVariable.name());

            assertSame(currentVariable, foundVariable,
                "Found " + foundVariable.name() + ", not " + currentVariable.name());
        }
    }

    @Test
    void testHashTableSize() {
        // Start with the smallest supported list (2).
        List<Variable> variables = new ArrayList<>();
        variables.add(Variable.builder().name("VAR0").type(VariableType.NUMERIC).length(8).build());
        variables.add(Variable.builder().name("VAR1").type(VariableType.NUMERIC).length(8).build());

        // I'd like to check every size up to 32K, but that takes too long.
        while (variables.size() <= 1000) {
            // Confirm that a hash table created with this many variables is valid.
            // In particular, this checks that the hash table size is appropriate.
            int totalVariables = variables.size();
            ColumnHashTable hashTable = new ColumnHashTable(variables);
            assertValidHashTable(hashTable, variables);

            // Add a new variable.
            String newName = "VAR" + totalVariables;
            variables.add(Variable.builder().name(newName).type(VariableType.NUMERIC).length(8).build());
        }
    }

    @Test
    void testMaxNumberOfVariables() {
        // Create a variable list with 32K variables.
        List<Variable> variables = new ArrayList<>();
        for (int i = 0; i < Short.MAX_VALUE; i++) {
            variables.add(Variable.builder().name("VAR_" + i).type(VariableType.NUMERIC).length(8).build());
        }

        // Confirm that a hash table created with this many variables is valid.
        // In particular, this checks that the hash table size is appropriate.
        ColumnHashTable hashTable = new ColumnHashTable(variables);
        assertValidHashTable(hashTable, variables);
    }

    @Test
    void testNegativeProbeInterval() {
        // This is a repro for a specific bug caused by not doing unsigned division when calculating
        // the probe interval.
        String name1 = "S15Ia3Jvyq4StYGOyDClA8IOvuψOHkd";
        String name2 = "6K5 J N8J \uD83D\uDE00UC0KG837X 1y";

        assertEquals(ColumnHashTable.hash(name1) % 127, ColumnHashTable.hash(name2) % 127);

        List<Variable> variables = new ArrayList<>();
        variables.add(Variable.builder().name(name1).type(VariableType.NUMERIC).length(8).build());
        variables.add(Variable.builder().name(name2).type(VariableType.NUMERIC).length(8).build());

        while (variables.size() < 97) {
            variables.add(
                Variable.builder().name("VAR" + variables.size()).type(VariableType.NUMERIC).length(8).build());
        }

        // Confirm that a hash table created with this many variables is valid.
        // In particular, this checks that the hash table size is appropriate.
        ColumnHashTable hashTable = new ColumnHashTable(variables);
        assertValidHashTable(hashTable, variables);

        // Confirm that the first two variables were placed in the correct buckets.
        assertEquals(-1, hashTable.getHashTableBucket(116));
        assertEquals(-2, hashTable.getHashTableBucket(74));
    }
}