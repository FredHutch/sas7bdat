package org.scharp.sas7bdat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.scharp.sas7bdat.WriteUtil.write2;
import static org.scharp.sas7bdat.WriteUtil.write8;

/**
 * A page in a sas7bdat dataset that would be generated with a 64-bit UNIX machine. This includes both metadata pages,
 * mixed pages, and data pages.
 */
class Sas7bdatPage {

    // This can be anything, although it might need to be aligned.
    // sas chooses 0x10000 for small datasets and increments by 0x400 when more space is needed.
    private static final int MINIMUM_PAGE_SIZE = 0x10000;

    private static final int DATA_PAGE_HEADER_SIZE = 40;

    private static final short PAGE_TYPE_META = 0x0000;
    private static final short PAGE_TYPE_DATA = 0x0100;
    private static final short PAGE_TYPE_MIX = 0x0200;
    private static final short PAGE_TYPE_AMD = 0x0400;
    private static final short PAGE_TYPE_MASK = 0x0F00;
    private static final short PAGE_TYPE_META2 = 0x4000;

    // For 64-bit, these are each 24 bytes long.
    private static final int SUBHEADER_OFFSET_SIZE_64BIT = 24;

    final int pageSize;
    private final long pageSequenceNumber;
    final List<Subheader> subheaders;
    private final List<byte[]> observations;
    private final Sas7bdatVariablesLayout variablesLayout;

    private short pageType;
    private int offsetOfNextSubheaderIndexEntry; // also the index of the last observation written.
    private int endOfDataSection;
    private int maxObservations; // also a flag to indicate if subheaders are finalized

    Sas7bdatPage(PageSequenceGenerator pageSequenceGenerator, int pageSize,
        Sas7bdatVariablesLayout variablesLayout) {
        this.pageSize = pageSize;

        pageSequenceGenerator.incrementPageSequence();
        this.pageSequenceNumber = pageSequenceGenerator.currentPageSequence();
        this.variablesLayout = variablesLayout;

        subheaders = new ArrayList<>();
        observations = new ArrayList<>();

        pageType = PAGE_TYPE_META;
        offsetOfNextSubheaderIndexEntry = DATA_PAGE_HEADER_SIZE;
        endOfDataSection = pageSize;
        maxObservations = -1;
    }

    private static int divideAndRoundUp(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    private boolean subheadersAreFinalized() {
        return 0 <= maxObservations;
    }

    /**
     * Gets the maximum number of observations that can fit on this page.
     *
     * @return
     */
    int maxObservations() {
        assert subheadersAreFinalized() : "can't determine the maximum observations until subheaders are finalized";
        return maxObservations;
    }

    boolean addSubheader(Subheader subheader) {
        assert !subheadersAreFinalized() : "cannot add subheaders after they have been finalized";
        assert !(subheader instanceof TerminalSubheader) : "terminal subheaders should only be added by finalize";
        assert observations.isEmpty() : "adding a subheader after data is written";

        // Determine if the page has enough space left to hold the subheader.
        // This requires space for the index (SUBHEADER_OFFSET_SIZE_64BIT) and the subheader itself.
        // We must also leave enough space to add the "deleted" index entry at the end.
        int spaceRemainingInPage = totalBytesRemaining();
        int spaceRequiredBySubheader = 2 * SUBHEADER_OFFSET_SIZE_64BIT + subheader.size();
        if (spaceRemainingInPage < spaceRequiredBySubheader) {
            // There isn't enough space left for this subheader.
            return false;
        }

        // Reserve space for the subheader in the index at the beginning of the page.
        offsetOfNextSubheaderIndexEntry += SUBHEADER_OFFSET_SIZE_64BIT;

        // Reserve space for the subheader at the end of the page.
        endOfDataSection = endOfDataSection - subheader.size();

        subheaders.add(subheader);
        return true;
    }

    void finalizeSubheaders() {
        assert !subheadersAreFinalized() : "cannot finalize subheaders multiple times";

        // Add a zero-length, deleted subheader to the end of the index, as SAS does, to
        // indicate the end of the index.  I don't know SAS needs this or if it's just something
        // that it does.

        // Because we were careful to reserve space for this in addSubheader(), it should exist.
        // This won't add a terminal subheader index if there are no subheaders (that is, this is a data page).
        assert SUBHEADER_OFFSET_SIZE_64BIT <= totalBytesRemaining() : "not enough space to write truncated subheader at end of section";
        if (!subheaders.isEmpty()) {
            subheaders.add(new TerminalSubheader());
            offsetOfNextSubheaderIndexEntry += SUBHEADER_OFFSET_SIZE_64BIT;
        }

        // An observation takes up space equal to its size in bytes, plus one bit.
        // The extra bit is for an "is deleted" flag that is added at the end of the observations.
        final int totalBitsRemaining = 8 * totalBytesRemaining();
        final int totalBitsPerObservation = 8 * variablesLayout.rowLength() + 1;
        maxObservations = totalBitsRemaining / totalBitsPerObservation;
    }

    boolean addObservation(byte[] observation) {
        assert subheadersAreFinalized() : "can't add an observation until subheaders are finalized";
        assert observation.length == variablesLayout.rowLength();

        if (maxObservations <= observations.size()) {
            // There isn't enough space between the end of the subheaders and the last subheader written
            // to hold an observation.
            return false;
        }

        // There's space for the observation.
        // It will be written just after the last subheader index entry or the previous observation written.
        offsetOfNextSubheaderIndexEntry += variablesLayout.rowLength();
        observations.add(observation);

        // metadata pages that also have data are "mixed" pages.
        pageType = PAGE_TYPE_MIX;

        assert subheaders.size() + observations.size() < 0x10000 : "too many blocks on page";
        return true;
    }

    void setIsFinalMetadataPage() {
        // SAS always marks the final metadata page as a mixed page, even if it has no data.
        pageType = PAGE_TYPE_MIX;
    }

    int totalBytesRemaining() {
        assert offsetOfNextSubheaderIndexEntry <= endOfDataSection : "negative space remaining";
        return endOfDataSection - offsetOfNextSubheaderIndexEntry;
    }

    /**
     * Calculates the maximum size of a subheader that can be added to this page, accounting for the space required by
     * the subheader, the index to the subheader, and space for the "truncated subheader" that is added to the index
     * when the subheaders are finalized.
     *
     * @return the maximum size of a subheader that can be added.
     */
    int totalBytesRemainingForNewSubheader() {
        // Return 0, not a negative number, when there isn't enough space remaining for a new subheader.
        return Math.max(0, totalBytesRemaining() - 2 * SUBHEADER_OFFSET_SIZE_64BIT);
    }

    void write(byte[] data) {
        assert data.length == pageSize : "data is not sized correctly: " + data.length;
        write8(data, 0, pageSequenceNumber);

        write8(data, 8, 0); // unknown purpose
        write8(data, 16, 0); // unknown purpose

        // The value at offset 24 is a positive integer in the range from 0 to pageSize.
        // I suspect it's either the number of unused bytes on the page or an offset to something.
        // The calculation below doesn't always match what SAS generates for similar datasets.
        // SAS uses numbers that are 0% - 5% bytes smaller than the ones calculated below.
        int totalBytesFree = pageSize - (
            DATA_PAGE_HEADER_SIZE + // standard page header
                subheaders.size() * SUBHEADER_OFFSET_SIZE_64BIT + // subheader index
                subheaders.stream().map(Subheader::size).reduce(0, Integer::sum) + // subheaders
                observations.size() * variablesLayout.rowLength() + // observations
                divideAndRoundUp(observations.size(), 8)); // observation deleted flags
        write8(data, 24, totalBytesFree);

        write2(data, 32, subheaders.isEmpty() ? PAGE_TYPE_DATA : pageType);
        write2(data, 34, (short) (subheaders.size() + observations.size())); // data block count
        write2(data, 36, (short) subheaders.size()); // number of subheaders on page
        write2(data, 38, (short) 0); // unknown purpose (possibly padding)

        int offset = DATA_PAGE_HEADER_SIZE; // The subheaders index immediately follows the header.
        int subheaderOffset = data.length; // The subheaders are written at the end of the page.
        for (Subheader subheader : subheaders) {
            subheaderOffset -= subheader.size();
            subheader.writeSubheaderIndex(data, offset, subheaderOffset);
            subheader.writeSubheader(data, subheaderOffset);
            offset += SUBHEADER_OFFSET_SIZE_64BIT;
        }
        assert endOfDataSection == subheaderOffset;

        // Write the observations.
        for (byte[] observation : observations) {
            System.arraycopy(observation, 0, data, offset, observation.length);
            offset += observation.length;
        }
        assert offsetOfNextSubheaderIndexEntry == offset;

        // Immediately before the endOfDataSecond are the "is deleted" flags.
        // If the first bit of the first byte were set, it would indicate that observation #1 is deleted.
        // Since this API doesn't support adding deleted observations, these are all 0.

        // Initialize the rest of the page.
        Arrays.fill(data, offset, endOfDataSection, (byte) 0);
    }

    static int maxObservationsPerDataPage(int pageSize, Sas7bdatVariablesLayout variablesLayout) {
        // An observation takes up space equal to its size in bytes, plus one bit.
        // The extra bit is for an "is deleted" flag that is added at the end of the observations.
        final int totalBitsRemaining = 8 * (pageSize - DATA_PAGE_HEADER_SIZE);
        final int totalBitsPerObservation = 8 * variablesLayout.rowLength() + 1;
        return totalBitsRemaining / totalBitsPerObservation;
    }

    static int calculatePageSize(Sas7bdatVariablesLayout variablesLayout) {
        // The minimum possible page size is determined by how many bytes it takes to
        // hold a single observation on a data page.
        int dataPageSizeForSingleObservation = DATA_PAGE_HEADER_SIZE + variablesLayout.rowLength() + 1; // +1 is for the "is deleted" flag

        // When SAS generates a dataset, it seems to pick page sizes that are multiples of 1KiB (0x400).
        return WriteUtil.align(Math.max(MINIMUM_PAGE_SIZE, dataPageSizeForSingleObservation), 0x400);
    }
}