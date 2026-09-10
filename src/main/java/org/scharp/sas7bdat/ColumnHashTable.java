package org.scharp.sas7bdat;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** A helper class for creating the hash table within {@link ColumnHashTableSubheader}. */
class ColumnHashTable {

    private final short[] hashTable;
    private final int totalVariables;

    private static boolean isPrime(int n) {
        // If there exists a number between 2 and the n - 1 that divides evenly,
        // then the number has whole-number factors and isn't prime.
        for (int i = 2; i < n; i++) {
            if (n % i == 0) {
                return false;
            }
        }

        return true;
    }

    private static int smallestPrimeLargerThan(int n) {
        // Special case for the smallest prime.
        if (n <= 2) {
            return 2;
        }

        // The only even prime number is 2, which is handled above as a special case.
        // Therefore, if the number is even, can start searching at the following odd number
        // and then only consider odd numbers.
        // This cuts our search space by 2.
        if (n % 2 == 0) {
            n++;
        }

        while (!isPrime(n)) {
            n += 2; // next odd number
        }

        return n;
    }

    private static int getByte(byte[] buffer, int offset) {
        return buffer[offset] & 0xFF;
    }

    // package-private for testing
    static int hash(String variableName) {
        byte[] normalizedName = variableName.toUpperCase().getBytes(StandardCharsets.UTF_8);

        int hash = 0;

        // XOR all values from the name as if it were an array of 32-bit integer values in little-endian format.
        int i = 0;
        while (i + 3 < normalizedName.length) {
            int val = (getByte(normalizedName, i + 3) << 24) |
                (getByte(normalizedName, i + 2) << 16) |
                (getByte(normalizedName, i + 1) << 8) |
                getByte(normalizedName, i);

            hash ^= val;
            i += 4;
        }

        // XOR the value from the partial 32-bit number remaining.
        // It behaves like the full 32-bit case above if the bytes beyond the end were filled with 0.
        if (i + 2 < normalizedName.length) {
            hash ^= getByte(normalizedName, i + 2) << 16;
        }
        if (i + 1 < normalizedName.length) {
            hash ^= getByte(normalizedName, i + 1) << 8;
        }
        if (i < normalizedName.length) {
            hash ^= getByte(normalizedName, i);
        }

        return hash;
    }

    ColumnHashTable(List<Variable> variables) {
        // Determine how many variables, starting at offset, this subheader will hold.
        assert variables != null;
        assert 1 < variables.size() : "sas doesn't include a hash table unless there are two variables";
        assert variables.size() <= Short.MAX_VALUE : "A sas dataset can't have " + variables.size() + " variables";

        // To make it easier match up with datasets created by sas, we make our hash table
        // the same size as the hash tables that it would create.
        // It appears to use the logic
        int hashTableSize = smallestPrimeLargerThan((int) (variables.size() * 1.3));

        totalVariables = variables.size();
        hashTable = new short[hashTableSize];

        // Fill the hash table.
        short variableNumber = 0;
        for (Variable variable : variables) {
            variableNumber++;
            String variableName = variable.name();

            // Compute the hash code.
            int hashCode = hash(variableName);

            // The "probe interval" is how far we should move if there's a hash collision.
            // SAS uses the hash code, divided by the table size (rounded down), mod the table size.
            // Of course, you can't have a probe interval of 0, so if this calculate is 0, use one, instead.
            int probeInterval = Integer.divideUnsigned(hashCode, hashTableSize);
            probeInterval = Integer.remainderUnsigned(probeInterval, hashTableSize);
            if (probeInterval == 0) {
                probeInterval = 1;
            }

            int bucketIndex = Integer.remainderUnsigned(hashCode, hashTableSize);
            for (int i = 0; i < hashTableSize; i++) {
                short currentBucketValue = hashTable[bucketIndex];
                if (0 == currentBucketValue) {
                    // This bucket hasn't been used before
                    hashTable[bucketIndex] = variableNumber;
                    break;
                }

                // Mark the current value in the bucket as having had a collision.
                if (0 < currentBucketValue) {
                    hashTable[bucketIndex] = (short) -currentBucketValue;
                }

                // Move by the probe interval to the next bucket.
                bucketIndex = (bucketIndex + probeInterval) % hashTableSize;
            }

            // By construction, we must have found an open slot within hashTableSize-1 probes
            // because we will have then probed the entire table.
            assert hashTable[bucketIndex] != 0 : "ERROR: could not place " + variableName;
        }
    }

    /**
     * Gets the total number of variables within this hash table.
     *
     * @return the number of variables.
     */
    int totalVariables() {
        return totalVariables;
    }

    /**
     * Gets the total number of buckets within this hash table.
     *
     * @return the number of buckets.
     */
    int totalBuckets() {
        return hashTable.length;
    }

    /**
     * Gets the value from a bucket within this hash table.
     *
     * @param index
     *      The 0-index of the bucket whose value is to be returned.
     *
     * @return The value.
     */
    short getHashTableBucket(int index) {
        return hashTable[index];
    }
}
