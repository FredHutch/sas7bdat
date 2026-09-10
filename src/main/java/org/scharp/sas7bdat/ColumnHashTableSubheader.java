///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import static org.scharp.sas7bdat.WriteUtil.write2;
import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A subheader that implements a hash table for looking up a column number by its name.
 * The hash table may be broken up across multiple ColumnHashTableSubheader objects.
 */
class ColumnHashTableSubheader extends VariableSizeSubheader {

    /**
     * Number of bytes of a bucket in the hash table.
     */
    private static final int SIZE_OF_BUCKET = 2;

    private static final int HASH_TABLE_HEADER_SIZE = 30;
    private static final int MIN_SIZE_SUBSEQUENT_SUBHEADER = VARIABLE_SUBHEADER_OVERHEAD + SIZE_OF_BUCKET; // enough for one bucket
    private static final int MIN_SIZE_FIRST_SUBHEADER = MIN_SIZE_SUBSEQUENT_SUBHEADER + HASH_TABLE_HEADER_SIZE; // enough for the table header and one bucket

    private final ColumnHashTable columnHashTable;
    private final int firstBucketIndex;
    private final int totalBucketsInSubheader;

    static int minSizeForFirstBucketIndex(int firstBucketIndex) {
        return firstBucketIndex == 0 ? MIN_SIZE_FIRST_SUBHEADER : MIN_SIZE_SUBSEQUENT_SUBHEADER;
    }

    ColumnHashTableSubheader(ColumnHashTable columnHashTable, int subheaderSize, int firstBucketIndex) {
        assert minSizeForFirstBucketIndex(firstBucketIndex) <= subheaderSize : "too small: " + subheaderSize;

        this.columnHashTable = columnHashTable;
        this.firstBucketIndex = firstBucketIndex;

        int overhead = VARIABLE_SUBHEADER_OVERHEAD + (firstBucketIndex == 0 ? HASH_TABLE_HEADER_SIZE : 0);
        int totalBucketsThatCanFitInSubheader = (subheaderSize - overhead) / SIZE_OF_BUCKET;
        int totalBucketsRemaining = columnHashTable.totalBuckets() - firstBucketIndex;
        this.totalBucketsInSubheader = Math.min(totalBucketsThatCanFitInSubheader, totalBucketsRemaining);
    }

    /** The offset of the first hash table entry within the subheader */
    private int offsetOfFirstBucket() {
        return SIGNATURE_SIZE + (firstBucketIndex == 0 ? HASH_TABLE_HEADER_SIZE : PAYLOAD_DESCRIPTION_FIELD_SIZE);
    }

    /**
     * Gets the number of hash table buckets that fit into this subheader.
     *
     * @return The number of hash table buckets
     */
    int totalBucketsInSubheader() {
        return totalBucketsInSubheader;
    }

    /**
     * The number of bytes of data in this subheader without signature or footer padding.
     *
     * @return The number of bytes of data in this subheader
     */
    @Override
    int sizeOfData() {
        return offsetOfFirstBucket() + totalBucketsInSubheader * SIZE_OF_BUCKET - SIGNATURE_SIZE;
    }

    @Override
    void writeVariableSizedPayload(byte[] page, int subheaderOffset) {

        // Write the hash table header in the first subheader.
        // Otherwise, the buckets immediately follow the "payload size" field.
        if (firstBucketIndex == 0) {
            // Hack: SAS writes this, but it might be unintentional.
            write2(page, subheaderOffset + 10, (short) 0x7FC8); // unknown

            // length remaining in subheader?
            write8(page, subheaderOffset + 16, sizeOfData() - PAYLOAD_DESCRIPTION_FIELD_SIZE);

            write2(page, subheaderOffset + 24, (short) columnHashTable.totalVariables()); // elements in hash table
            write2(page, subheaderOffset + 26, (short) columnHashTable.totalBuckets()); // capacity of hash table
            write2(page, subheaderOffset + 28, (short) 1); // unknown
            write2(page, subheaderOffset + 30, (short) columnHashTable.totalVariables()); // unknown

            write2(page, subheaderOffset + 32, (short) 0); // unknown
            write2(page, subheaderOffset + 34, (short) 0); // unknown
            write2(page, subheaderOffset + 36, (short) 0); // unknown
        }

        // Write each of the hash table buckets, starting at firstBucketIndex.
        int offsetFromSubheaderStart = offsetOfFirstBucket();
        for (int bucketIndex = firstBucketIndex; bucketIndex < firstBucketIndex + totalBucketsInSubheader; bucketIndex++) {
            final short value = columnHashTable.getHashTableBucket(bucketIndex);
            write2(page, subheaderOffset + offsetFromSubheaderStart, value);

            offsetFromSubheaderStart += SIZE_OF_BUCKET;
        }
    }

    @Override
    long signature() {
        return SIGNATURE_COLUMN_LIST;
    }
}