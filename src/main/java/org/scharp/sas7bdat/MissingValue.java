///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

/**
 * A representation of a missing numeric value in a SAS7BDAT dataset.  (Missing character values are represented as the
 * empty string).
 * <p>
 * SAS supports multiple of ways of specifying that a numeric variable's value is missing in an observation.  This can
 * be used to encode <i>why</i> a value is missing.  For example, it could be that the value is unknown, or that the
 * subject declined to report a value.  If you don't need to specify multiple ways that a value is missing, use
 * {@link MissingValue#STANDARD}.
 * </p>
 */
public enum MissingValue {
    /**
     * The missing value that is represented as "{@code .}" in SAS. This only applies to only numeric values.
     */
    STANDARD(0xFFFF_FE_0000000000L, "."),

    /**
     * The missing value that is represented as "{@code ._}" in SAS. This only applies to only numeric values.
     */
    UNDERSCORE(0xFFFF_FF_0000000000L, "._"),

    /**
     * The missing value that is represented as "{@code .A}" in SAS. This only applies to only numeric values.
     */
    A(0xFFFF_FD_0000000000L, ".A"),

    /**
     * The missing value that is represented as "{@code .B}" in SAS. This only applies to only numeric values.
     */
    B(0xFFFF_FC_0000000000L, ".B"),

    /**
     * The missing value that is represented as "{@code .C}" in SAS. This only applies to only numeric values.
     */
    C(0xFFFF_FB_0000000000L, ".C"),

    /**
     * The missing value that is represented as "{@code .D}" in SAS. This only applies to only numeric values.
     */
    D(0xFFFF_FA_0000000000L, ".D"),

    /**
     * The missing value that is represented as "{@code .E}" in SAS. This only applies to only numeric values.
     */
    E(0xFFFF_F9_0000000000L, ".E"),

    /**
     * The missing value that is represented as "{@code .F}" in SAS. This only applies to only numeric values.
     */
    F(0xFFFF_F8_0000000000L, ".F"),

    /**
     * The missing value that is represented as "{@code .G}" in SAS. This only applies to only numeric values.
     */
    G(0xFFFF_F7_0000000000L, ".G"),

    /**
     * The missing value that is represented as "{@code .H}" in SAS. This only applies to only numeric values.
     */
    H(0xFFFF_F6_0000000000L, ".H"),

    /**
     * The missing value that is represented as "{@code .I}" in SAS. This only applies to only numeric values.
     */
    I(0xFFFF_F5_0000000000L, ".I"),

    /**
     * The missing value that is represented as "{@code .J}" in SAS. This only applies to only numeric values.
     */
    J(0xFFFF_F4_0000000000L, ".J"),

    /**
     * The missing value that is represented as "{@code .K}" in SAS. This only applies to only numeric values.
     */
    K(0xFFFF_F3_0000000000L, ".K"),

    /**
     * The missing value that is represented as "{@code .L}" in SAS. This only applies to only numeric values.
     */
    L(0xFFFF_F2_0000000000L, ".L"),

    /**
     * The missing value that is represented as "{@code .M}" in SAS. This only applies to only numeric values.
     */
    M(0xFFFF_F1_0000000000L, ".M"),

    /**
     * The missing value that is represented as "{@code .N}" in SAS. This only applies to only numeric values.
     */
    N(0xFFFF_F0_0000000000L, ".N"),

    /**
     * The missing value that is represented as "{@code .O}" in SAS. This only applies to only numeric values.
     */
    O(0xFFFF_EF_0000000000L, ".O"),

    /**
     * The missing value that is represented as "{@code .P}" in SAS. This only applies to only numeric values.
     */
    P(0xFFFF_EE_0000000000L, ".P"),

    /**
     * The missing value that is represented as "{@code .Q}" in SAS. This only applies to only numeric values.
     */
    Q(0xFFFF_ED_0000000000L, ".Q"),

    /**
     * The missing value that is represented as "{@code .R}" in SAS. This only applies to only numeric values.
     */
    R(0xFFFF_EC_0000000000L, ".R"),

    /**
     * The missing value that is represented as "{@code .S}" in SAS. This only applies to only numeric values.
     */
    S(0xFFFF_EB_0000000000L, ".S"),

    /**
     * The missing value that is represented as "{@code .T}" in SAS. This only applies to only numeric values.
     */
    T(0xFFFF_EA_0000000000L, ".T"),

    /**
     * The missing value that is represented as "{@code .U}" in SAS. This only applies to only numeric values.
     */
    U(0xFFFF_E9_0000000000L, ".U"),

    /**
     * The missing value that is represented as "{@code .V}" in SAS. This only applies to only numeric values.
     */
    V(0xFFFF_E8_0000000000L, ".V"),

    /**
     * The missing value that is represented as "{@code .W}" in SAS. This only applies to only numeric values.
     */
    W(0xFFFF_E7_0000000000L, ".W"),

    /**
     * The missing value that is represented as "{@code .X}" in SAS. This only applies to only numeric values.
     */
    X(0xFFFF_E6_0000000000L, ".X"),

    /**
     * The missing value that is represented as "{@code .Y}" in SAS. This only applies to only numeric values.
     */
    Y(0xFFFF_E5_0000000000L, ".Y"),

    /**
     * The missing value that is represented as "{@code .Z}" in SAS. This only applies to only numeric values.
     */
    Z(0xFFFF_E4_0000000000L, ".Z");

    private final long rawLongBits;
    private final String stringValue;

    MissingValue(long longValue, String string) {
        rawLongBits = longValue;
        stringValue = string;
    }

    long rawLongBits() {
        return rawLongBits;
    }

    /**
     * Gets the string representation of this missing value, formatted as SAS format it.
     *
     * @return The string representation of this missing value.
     */
    public String toString() {
        return stringValue;
    }
}