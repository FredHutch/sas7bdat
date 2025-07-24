package org.scharp.sas7bdat;

/**
 * A class for holding utility methods.
 */
abstract class MathUtil {

    // private constructor to prevent anyone from instantiating the class.
    private MathUtil() {
    }

    /**
     * Computes dividend / divisor, but instead of truncating any remainder, it always rounds up.
     *
     * @param dividend
     *     the dividend
     * @param divisor
     *     the divisor
     *
     * @return The result of the calculation.
     */
    // This can be replaced by Math.cielDiv() in Java 18
    static int divideAndRoundUp(int dividend, int divisor) {
        assert 0 <= divisor : "divideAndRoundUp doesn't handle negative numbers";
        assert 0 <= dividend : "divideAndRoundUp doesn't handle negative numbers";

        return (dividend + divisor - 1) / divisor;
    }
}