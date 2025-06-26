package org.scharp.sas7bdat;

/**
 * How strictly the input should be checked for well-formedness.
 */
public enum StrictnessMode {

    /**
     * Throw exceptions for any data that does not adhere to limitations imposed by SAS in its most lenient mode.
     * Variable names checked according the same way as setting the SAS system option VALIDVARNAME=ANY.
     */
    SAS_ANY,

    /**
     * Throw exceptions for any data loss or anything that would cause SAS to not be able to read an XPORT.
     */
    BASIC,

    /**
     * Throw exceptions for any data that does not adhere to FDA submission guidelines.
     */
    FDA_SUBMISSION,
}