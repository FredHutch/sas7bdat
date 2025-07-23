package org.scharp.sas7bdat;

/**
 * A subheader that has a fixed size.
 */
abstract class FixedSizeSubheader extends Subheader {

    @Override
    byte typeCode() {
        return SUBHEADER_TYPE_A;
    }

    @Override
    byte compressionCode() {
        return COMPRESSION_UNCOMPRESSED;
    }
}