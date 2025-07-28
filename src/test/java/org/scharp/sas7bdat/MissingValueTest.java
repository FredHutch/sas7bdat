///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link MissingValue} */
public class MissingValueTest {

    @Test
    void testToString() {
        assertEquals(".", MissingValue.STANDARD.toString());
        assertEquals("._", MissingValue.UNDERSCORE.toString());
        assertEquals(".A", MissingValue.A.toString());
        assertEquals(".B", MissingValue.B.toString());
        assertEquals(".C", MissingValue.C.toString());
        assertEquals(".D", MissingValue.D.toString());
        assertEquals(".E", MissingValue.E.toString());
        assertEquals(".F", MissingValue.F.toString());
        assertEquals(".G", MissingValue.G.toString());
        assertEquals(".H", MissingValue.H.toString());
        assertEquals(".I", MissingValue.I.toString());
        assertEquals(".J", MissingValue.J.toString());
        assertEquals(".K", MissingValue.K.toString());
        assertEquals(".L", MissingValue.L.toString());
        assertEquals(".M", MissingValue.M.toString());
        assertEquals(".N", MissingValue.N.toString());
        assertEquals(".O", MissingValue.O.toString());
        assertEquals(".P", MissingValue.P.toString());
        assertEquals(".Q", MissingValue.Q.toString());
        assertEquals(".R", MissingValue.R.toString());
        assertEquals(".S", MissingValue.S.toString());
        assertEquals(".T", MissingValue.T.toString());
        assertEquals(".U", MissingValue.U.toString());
        assertEquals(".V", MissingValue.V.toString());
        assertEquals(".W", MissingValue.W.toString());
        assertEquals(".X", MissingValue.X.toString());
        assertEquals(".Y", MissingValue.Y.toString());
        assertEquals(".Z", MissingValue.Z.toString());
    }

    @Test
    void testRawLongBits() {
        assertEquals(0xFFFF_FE_0000000000L, MissingValue.STANDARD.rawLongBits());
        assertEquals(0xFFFF_FF_0000000000L, MissingValue.UNDERSCORE.rawLongBits());
        assertEquals(0xFFFF_FD_0000000000L, MissingValue.A.rawLongBits());
        assertEquals(0xFFFF_FC_0000000000L, MissingValue.B.rawLongBits());
        assertEquals(0xFFFF_FB_0000000000L, MissingValue.C.rawLongBits());
        assertEquals(0xFFFF_FA_0000000000L, MissingValue.D.rawLongBits());
        assertEquals(0xFFFF_F9_0000000000L, MissingValue.E.rawLongBits());
        assertEquals(0xFFFF_F8_0000000000L, MissingValue.F.rawLongBits());
        assertEquals(0xFFFF_F7_0000000000L, MissingValue.G.rawLongBits());
        assertEquals(0xFFFF_F6_0000000000L, MissingValue.H.rawLongBits());
        assertEquals(0xFFFF_F5_0000000000L, MissingValue.I.rawLongBits());
        assertEquals(0xFFFF_F4_0000000000L, MissingValue.J.rawLongBits());
        assertEquals(0xFFFF_F3_0000000000L, MissingValue.K.rawLongBits());
        assertEquals(0xFFFF_F2_0000000000L, MissingValue.L.rawLongBits());
        assertEquals(0xFFFF_F1_0000000000L, MissingValue.M.rawLongBits());
        assertEquals(0xFFFF_F0_0000000000L, MissingValue.N.rawLongBits());
        assertEquals(0xFFFF_EF_0000000000L, MissingValue.O.rawLongBits());
        assertEquals(0xFFFF_EE_0000000000L, MissingValue.P.rawLongBits());
        assertEquals(0xFFFF_ED_0000000000L, MissingValue.Q.rawLongBits());
        assertEquals(0xFFFF_EC_0000000000L, MissingValue.R.rawLongBits());
        assertEquals(0xFFFF_EB_0000000000L, MissingValue.S.rawLongBits());
        assertEquals(0xFFFF_EA_0000000000L, MissingValue.T.rawLongBits());
        assertEquals(0xFFFF_E9_0000000000L, MissingValue.U.rawLongBits());
        assertEquals(0xFFFF_E8_0000000000L, MissingValue.V.rawLongBits());
        assertEquals(0xFFFF_E7_0000000000L, MissingValue.W.rawLongBits());
        assertEquals(0xFFFF_E6_0000000000L, MissingValue.X.rawLongBits());
        assertEquals(0xFFFF_E5_0000000000L, MissingValue.Y.rawLongBits());
        assertEquals(0xFFFF_E4_0000000000L, MissingValue.Z.rawLongBits());
    }
}