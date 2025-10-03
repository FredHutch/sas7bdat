///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

/**
 * A class for generating the page numbers that appear on each page of a SAS7BDAT file.
 */
class PageNumberSequence {

    private final long mask;
    private long pageSequenceIndex;

    /**
     * Create a new page number sequence.
     *
     * @param mask
     *     The bitmask with which to XOR each page number in the sequence.
     */
    PageNumberSequence(long mask) {
        this.mask = mask;
        pageSequenceIndex = 1;
    }

    /**
     * @return the page number mask
     */
    long mask() {
        return mask;
    }

    /**
     * @return the initial value in this sequence.
     */
    long initialValue() {
        return mask ^ 1;
    }

    /**
     * @return the current page number in this sequence.
     */
    long currentPageNumber() {
        return mask ^ pageSequenceIndex;
    }

    /**
     * Increments the current number in this sequence to the next number in the sequence.
     */
    void incrementPageNumber() {
        pageSequenceIndex++;
    }
}