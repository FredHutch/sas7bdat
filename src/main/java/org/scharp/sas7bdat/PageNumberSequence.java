///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

/**
 * A class for generating the page numbers that appear on each page of a SAS7BDAT file.
 */
class PageNumberSequence {

    private final long initialValue;
    private int pageSequenceIndex;

    /**
     * Create a new page number sequence that starts at a given value.
     *
     * @param initialValue
     *     This is also the initial value in the page number sequence.
     */
    PageNumberSequence(long initialValue) {
        this.initialValue = initialValue;
        pageSequenceIndex = 0;
    }

    /** Create a new page number sequence generator that can be used to create legal page sequence */
    PageNumberSequence() {
        this(0xF4_A4_FF_F6L); // for compatibility with v0.9 of this library
    }

    /**
     * @return the initial value in this sequence.
     */
    long initialValue() {
        return initialValue;
    }

    /**
     * @return the current page number in this sequence.
     */
    long currentPageNumber() {
        return initialValue ^ pageSequenceIndex;
    }

    /**
     * Increments the current number in this sequence to the next number in the sequence.
     */
    void incrementPageNumber() {
        pageSequenceIndex++;
    }
}