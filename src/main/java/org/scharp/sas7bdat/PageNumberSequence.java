///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

/**
 * A class for generating the page numbers that appear on each page of a SAS7BDAT file.
 */
class PageNumberSequence {

    private final int mask;
    private int pageSequenceIndex;

    /**
     * Create a new page number sequence.
     *
     * @param mask
     *     The bitmask with which to XOR each page number in the sequence.
     */
    PageNumberSequence(int mask) {
        this.mask = mask;
        pageSequenceIndex = 1;
    }

    /**
     * @return the page number mask
     */
    int mask() {
        return mask;
    }

    /**
     * @return the initial value in this sequence.
     */
    int initialValue() {
        return mask ^ 1;
    }

    /**
     * @return the current page number in this sequence.
     */
    int currentPageNumber() {
        return mask ^ pageSequenceIndex;
    }

    /**
     * Increments the current number in this sequence to the next number in the sequence.
     */
    void incrementPageNumber() {
        pageSequenceIndex++;
    }
}