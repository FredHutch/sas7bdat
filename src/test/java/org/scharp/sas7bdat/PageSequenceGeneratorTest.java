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

    @Test
    void testSequence() {

        // The Page Sequence Generator is hard-coded to always generate the same sequence.
        // Therefore, the entire sequence can be tested.
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