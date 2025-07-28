///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link MathUtil}. */
public class MathUtilTest {
    @Test
    public void testDividedAndRoundUp() {
        // divide by 3
        assertEquals(0, MathUtil.divideAndRoundUp(0, 3));
        assertEquals(1, MathUtil.divideAndRoundUp(1, 3));
        assertEquals(1, MathUtil.divideAndRoundUp(2, 3));
        assertEquals(1, MathUtil.divideAndRoundUp(3, 3));
        assertEquals(2, MathUtil.divideAndRoundUp(4, 3));
        assertEquals(2, MathUtil.divideAndRoundUp(5, 3));
        assertEquals(2, MathUtil.divideAndRoundUp(6, 3));
        assertEquals(3, MathUtil.divideAndRoundUp(7, 3));

        // divide by 100
        assertEquals(2, MathUtil.divideAndRoundUp(199, 100));
        assertEquals(2, MathUtil.divideAndRoundUp(200, 100));
        assertEquals(3, MathUtil.divideAndRoundUp(201, 100));
    }
}