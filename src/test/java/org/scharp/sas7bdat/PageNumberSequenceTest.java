///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link PageNumberSequence}. */
public class PageNumberSequenceTest {

    /** Tests a known sequence */
    @Test
    void testSequence() {
        final long[] expectedSequence = {
            0xF4A4_FFF_7L,
            0xF4A4_FFF_4L,
            0xF4A4_FFF_5L,
            0xF4A4_FFF_2L,
            0xF4A4_FFF_3L,
            0xF4A4_FFF_0L,
            0xF4A4_FFF_1L,
            0xF4A4_FFF_EL,
            0xF4A4_FFF_FL,
            0xF4A4_FFF_CL,
            0xF4A4_FFF_DL,
            0xF4A4_FFF_AL,
            0xF4A4_FFF_BL,
            0xF4A4_FFF_8L,
            0xF4A4_FFF_9L,

            0xF4A4_FFE_6L,
            0xF4A4_FFE_7L,
            0xF4A4_FFE_4L,
            0xF4A4_FFE_5L,
            0xF4A4_FFE_2L,
            0xF4A4_FFE_3L,
            0xF4A4_FFE_0L,
            0xF4A4_FFE_1L,
            0xF4A4_FFE_EL,
            0xF4A4_FFE_FL,
            0xF4A4_FFE_CL,
            0xF4A4_FFE_DL,
            0xF4A4_FFE_AL,
            0xF4A4_FFE_BL,
            0xF4A4_FFE_8L,
            0xF4A4_FFE_9L,

            0xF4A4_FFD_6L,
            0xF4A4_FFD_7L,
            0xF4A4_FFD_4L,
            0xF4A4_FFD_5L,
            0xF4A4_FFD_2L,
            0xF4A4_FFD_3L,
            0xF4A4_FFD_0L,
            0xF4A4_FFD_1L,
            0xF4A4_FFD_EL,
            0xF4A4_FFD_FL,
            0xF4A4_FFD_CL,
            0xF4A4_FFD_DL,
            0xF4A4_FFD_AL,
            0xF4A4_FFD_BL,
            0xF4A4_FFD_8L,
            0xF4A4_FFD_9L,
        };

        PageNumberSequence pageNumberSequence = new PageNumberSequence(0xF4A4_FFF_6L);
        assertEquals(0xF4A4_FFF_6L, pageNumberSequence.mask());
        assertEquals(expectedSequence[0], pageNumberSequence.initialValue());

        // Check the known portion of the sequence
        for (int i = 0; i < expectedSequence.length; i++) {
            assertEquals(expectedSequence[i], pageNumberSequence.currentPageNumber(), "page number " + i);
            pageNumberSequence.incrementPageNumber();
        }

        // The mask and the initial value in the page number sequence shouldn't have changed.
        assertEquals(0xF4A4_FFF_6L, pageNumberSequence.mask());
        assertEquals(expectedSequence[0], pageNumberSequence.initialValue());
    }

    @Test
    void testSequence0() {
        // Tests the sequence beginning at 1.
        // This should increment as a "normal" number.
        PageNumberSequence pageNumberSequence = new PageNumberSequence(0);
        for (int i = 1; i < 0x10001; i++) {
            assertEquals(i, pageNumberSequence.currentPageNumber(), "page number " + i);
            pageNumberSequence.incrementPageNumber();
        }

        assertEquals(0, pageNumberSequence.mask());
        assertEquals(1, pageNumberSequence.initialValue());
    }

    @Test
    void testSequenceMinus1() {
        // Tests the sequence whose mask is all 1 (0xFFFFFFFFFFFFFFFF).
        // This should start at -2 and decrement by 1.
        PageNumberSequence pageNumberSequence = new PageNumberSequence(0xFFFFFFFFFFFFFFFFL);
        for (long i = 1; i < 0x10001; i++) {
            assertEquals(-i - 1, pageNumberSequence.currentPageNumber(), "page number " + i);
            pageNumberSequence.incrementPageNumber();
        }

        assertEquals(-1, pageNumberSequence.mask());
        assertEquals(-2, pageNumberSequence.initialValue());
    }
}