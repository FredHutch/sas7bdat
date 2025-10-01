///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link PageSequenceGenerator}. */
public class PageSequenceGeneratorTest {

    /** Tests a known sequence (the one documented in V1.0 of the SAS7BDAT specification) */
    @Test
    void testSequence() {
        final long[] expectedSequence = {
            0xF4A4_FFF_6L,
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

        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        assertEquals(expectedSequence[0], pageSequenceGenerator.initialPageSequence());

        // Check the entire sequence
        for (int i = 0; i < expectedSequence.length; i++) {
            assertEquals(expectedSequence[i], pageSequenceGenerator.currentPageSequence(), "page number " + i);
            pageSequenceGenerator.incrementPageSequence();
        }

        // The initial page sequence shouldn't have changed.
        assertEquals(expectedSequence[0], pageSequenceGenerator.initialPageSequence());
    }

    @Test
    void testSequence0() {
        // Tests the sequence beginning at 0.
        // This should increment as a "normal" number.
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator(0);
        for (int i = 0; i < 0x7FFF; i++) {
            assertEquals(i, pageSequenceGenerator.currentPageSequence(), "page number " + i);
            pageSequenceGenerator.incrementPageSequence();
        }

        assertEquals(0, pageSequenceGenerator.initialPageSequence());
    }

    @Test
    void testSequenceMinus1() {
        // Tests the sequence beginning at 0xFFFFFFFF.
        // This should decrement with each increment.
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator(0xFFFFFFFFL);
        for (int i = 0; i < 0x7FFF; i++) {
            assertEquals(0xFFFFFFFFL - i, pageSequenceGenerator.currentPageSequence(), "page number " + i);
            pageSequenceGenerator.incrementPageSequence();
        }

        assertEquals(0xFFFFFFFFL, pageSequenceGenerator.initialPageSequence());
    }

    @Test
    void testSequenceEnd() {
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        for (int i = 0; i < 0x7FFF; i++) {
            pageSequenceGenerator.incrementPageSequence();
        }

        // The sequence should be exhausted.
        Exception exception = assertThrows(IllegalStateException.class, pageSequenceGenerator::incrementPageSequence);
        assertEquals("This code does not support more than 32767 pages", exception.getMessage());
    }
}