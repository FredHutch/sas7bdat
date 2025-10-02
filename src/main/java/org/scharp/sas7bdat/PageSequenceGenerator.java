///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

/**
 * A class for generating the page numbers that appear on each page of a SAS7BDAT file.
 */
class PageSequenceGenerator {

    private final long mask;
    private int pageSequenceIndex;

    /**
     * Create a new page number sequence generator that starts at a given value.
     *
     * @param mask
     *     the XOR mask to apply to each value in the sequence.  Since a sequence starts at 0, this is also the initial
     *     value.
     */
    PageSequenceGenerator(long mask) {
        this.mask = mask;
        pageSequenceIndex = 0;
    }

    /** Create a new page number sequence generator that can be used to create legal page sequence */
    PageSequenceGenerator() {
        this(0xF4_A4_FF_F6L); // for compatibility with v0.9 of this library
    }

    /**
     * @return the XOR mask used by this sequence.
     */
    long mask() {
        return mask;
    }

    /**
     * @return the current number in this page sequence.
     */
    long currentPageSequence() {
        return mask ^ pageSequenceIndex;
    }

    /**
     * Increments this the current number of this sequence to the next number in the sequence.
     *
     * @throws IllegalStateException
     *     if the page sequence has been exhausted.
     */
    void incrementPageSequence() {
        pageSequenceIndex++;
    }
}