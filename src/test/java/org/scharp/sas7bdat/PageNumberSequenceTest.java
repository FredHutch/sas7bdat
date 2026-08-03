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
        final int[] expectedSequence = {
            0xF4A4_FFF_7,
            0xF4A4_FFF_4,
            0xF4A4_FFF_5,
            0xF4A4_FFF_2,
            0xF4A4_FFF_3,
            0xF4A4_FFF_0,
            0xF4A4_FFF_1,
            0xF4A4_FFF_E,
            0xF4A4_FFF_F,
            0xF4A4_FFF_C,
            0xF4A4_FFF_D,
            0xF4A4_FFF_A,
            0xF4A4_FFF_B,
            0xF4A4_FFF_8,
            0xF4A4_FFF_9,

            0xF4A4_FFE_6,
            0xF4A4_FFE_7,
            0xF4A4_FFE_4,
            0xF4A4_FFE_5,
            0xF4A4_FFE_2,
            0xF4A4_FFE_3,
            0xF4A4_FFE_0,
            0xF4A4_FFE_1,
            0xF4A4_FFE_E,
            0xF4A4_FFE_F,
            0xF4A4_FFE_C,
            0xF4A4_FFE_D,
            0xF4A4_FFE_A,
            0xF4A4_FFE_B,
            0xF4A4_FFE_8,
            0xF4A4_FFE_9,

            0xF4A4_FFD_6,
            0xF4A4_FFD_7,
            0xF4A4_FFD_4,
            0xF4A4_FFD_5,
            0xF4A4_FFD_2,
            0xF4A4_FFD_3,
            0xF4A4_FFD_0,
            0xF4A4_FFD_1,
            0xF4A4_FFD_E,
            0xF4A4_FFD_F,
            0xF4A4_FFD_C,
            0xF4A4_FFD_D,
            0xF4A4_FFD_A,
            0xF4A4_FFD_B,
            0xF4A4_FFD_8,
            0xF4A4_FFD_9,
        };

        PageNumberSequence pageNumberSequence = new PageNumberSequence(0xF4A4_FFF_6);
        assertEquals(0xF4A4_FFF_6, pageNumberSequence.mask());
        assertEquals(expectedSequence[0], pageNumberSequence.initialValue());

        // Check the known portion of the sequence
        for (int i = 0; i < expectedSequence.length; i++) {
            assertEquals(expectedSequence[i], pageNumberSequence.currentPageNumber(), "page number " + i);
            pageNumberSequence.incrementPageNumber();
        }

        // The mask and the initial value in the page number sequence shouldn't have changed.
        assertEquals(0xF4A4_FFF_6, pageNumberSequence.mask());
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
        // Tests the sequence whose mask is 0xFFFFFFFF.
        // This should start at -2 and decrement by 1.
        PageNumberSequence pageNumberSequence = new PageNumberSequence(-1);
        for (long i = 1; i < 0x10001; i++) {
            assertEquals(-i - 1, pageNumberSequence.currentPageNumber(), "page number " + i);
            pageNumberSequence.incrementPageNumber();
        }

        assertEquals(-1, pageNumberSequence.mask());
        assertEquals(-2, pageNumberSequence.initialValue());
    }
}