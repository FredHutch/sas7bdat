///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

/**
 * A class for generating the sequence numbers that appear on each page of a SAS7BDAT file.
 */
class PageSequenceGenerator {

    // The page sequence numbers are a mystery.  Somehow SAS knows they are out
    // of order if they increment by 1.  Depending on the higher bytes, the
    // least significant byte increments by a different pattern.
    //
    // Examples:
    //
    // For 0xE2677F??, it goes down by 1 four times, then up by 7.
    //   63,62,61,60, 67,66,65,64, 6B,6A,69,68, 6F, ...
    //
    // For 0xAB353E??, it goes up by one four times, then down by 7 % 16
    //  75,76,77, 70,71,72,73, 7C,7D,7E,7F, 78, ...
    //
    // For 0x3664FB??, it goes up by one, then down by 4
    //  5A,5B, 58,59, 5E,5F,  5C,5D,  52,53,  50,51,  56,57,  54,55, ...
    //
    // The pattern below starts with 0xF4A4????.
    private static final int[] pageSequenceNumbers = new int[] {
        0x6, 0x7,
        0x4, 0x5,
        0x2, 0x3,
        0x0, 0x1,
        0xE, 0xF,
        0xC, 0xD,
        0xA, 0xB,
        0x8, 0x9,
    };

    int pageSequenceIndex;

    /** Create a new page sequence generator that can be used to create legal page sequence */
    PageSequenceGenerator() {
        pageSequenceIndex = 0;
    }

    private static long pageSequence(int pageIndex) {
        return 0xFFFFFFFFL & (
            0xF4_A4_00_00L | // bits 16-31
                ((0x0000FF00) & ((0xFF - (pageIndex / 256)) << 8)) | // bits 8-15
                ((0x000000F0) & (0xF0 - ((pageIndex / 16) << 4))) |  // bits 4-7
                ((0x0000000F) & (pageSequenceNumbers[pageIndex % 16]))); // bits 0-3
    }

    /**
     * @return the first number in this page sequence.
     */
    long initialPageSequence() {
        return pageSequence(0);
    }

    /**
     * @return the current number in this page sequence.
     */
    long currentPageSequence() {
        return pageSequence(pageSequenceIndex);
    }

    /**
     * Increments this the current number of this sequence to the next number in the sequence.
     *
     * @throws IllegalStateException
     *     if the page sequence has been exhausted.
     */
    void incrementPageSequence() {
        pageSequenceIndex++;
        if (pageSequenceIndex > 0x7FFF) {
            throw new IllegalStateException("This code does not support more than " + 0x7FFF + " pages");
        }
    }
}