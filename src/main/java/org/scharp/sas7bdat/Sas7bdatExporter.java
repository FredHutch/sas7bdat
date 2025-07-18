package org.scharp.sas7bdat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class Sas7bdatExporter implements AutoCloseable {

    /**
     * A helper class for putting subheaders into pages. It also calculates the following:
     * <ol>
     * <li>determining the page size</li>
     * <li>how many observations fit on the mixed page</li>
     * <li>how many metadata/mixed pages are needed</li>
     * <li>the location of each subheader</li>
     * </ol>
     */
    static class Sas7bdatPageLayout {
        final PageSequenceGenerator pageSequenceGenerator;
        final int pageSize;
        final Sas7bdatVariablesLayout variablesLayout;
        final ColumnText columnText;
        final List<Subheader> subheaders;
        final List<Sas7bdatPage> completeMetadataPages;
        final Map<Subheader, Integer> subheaderLocations;

        Sas7bdatPage currentMetadataPage;

        Sas7bdatPageLayout(PageSequenceGenerator pageSequenceGenerator, Sas7bdatVariablesLayout variablesLayout) {
            this.pageSequenceGenerator = pageSequenceGenerator;
            this.pageSize = Sas7bdatPage.calculatePageSize(variablesLayout);
            this.variablesLayout = variablesLayout;
            this.columnText = new ColumnText(this);

            subheaders = new ArrayList<>();
            completeMetadataPages = new ArrayList<>();
            subheaderLocations = new IdentityHashMap<>();
            currentMetadataPage = new Sas7bdatPage(pageSequenceGenerator, pageSize, variablesLayout);
        }

        void finalizeSubheaders() {
            currentMetadataPage.finalizeSubheaders();

            // finalizeSubheaders() inserts a truncated subheader.  We must therefore update the
            // allSubheaders list to include this truncated subheader.
            Subheader truncatedSubheader = currentMetadataPage.subheaders.get(
                currentMetadataPage.subheaders.size() - 1);
            subheaders.add(truncatedSubheader);
            subheaderLocations.put(truncatedSubheader, completeMetadataPages.size() + 1);
        }

        void addSubheader(Subheader subheader) {
            if (!currentMetadataPage.addSubheader(subheader)) {
                // There's not enough space on this metadata page for this subheader.

                // Finalize the subheaders on the current page.
                finalizeSubheaders();

                // Create a new page.
                completeMetadataPages.add(currentMetadataPage);
                currentMetadataPage = new Sas7bdatPage(pageSequenceGenerator, pageSize, variablesLayout);

                // Add the subheader to the new page.
                boolean success = currentMetadataPage.addSubheader(subheader);
                assert success : "couldn't add a subheader to a new page";
            }

            // Track which page the subheader was added to.
            subheaders.add(subheader);
            subheaderLocations.put(subheader, completeMetadataPages.size() + 1);
        }
    }

    private final OutputStream outputStream;
    private final Sas7bdatVariablesLayout variablesLayout;
    private final int totalObservationsInDataset;
    private final PageSequenceGenerator pageSequenceGenerator;
    private final byte[] pageBuffer;

    int totalObservationsWritten;
    private Sas7bdatPage currentPage;

    int totalPagesAllocated; // TODO: only to assert at the end
    int totalPagesInDataset;

    private static int divideAndRoundUp(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    /**
     * Creates a {@code Sas7bdatExporter} for streaming a SAS7BDAT to a file.
     * <p>
     * After creating the exporter, you must invoke {@link Sas7bdatExporter#writeObservation writeObservation()} to
     * write each observation.  After you have provided the final observation, you must invoke
     * {@link Sas7bdatExporter#close()} to flush any buffered data.
     * </p>
     *
     * @param targetLocation
     *     The path to the file to which the SAS7BDAT should be written.
     * @param metadata
     *     The metadata for the SAS7BDAT.
     * @param totalObservationsInDataset
     *     The total number of observation that will be written to the dataset.  You must invoke
     *     {@link Sas7bdatExporter#writeObservation writeObservation} exactly this number of times before invoking
     *     {@link Sas7bdatExporter#close}, or else the SAS7BDAT may be corrupt.
     *
     * @throws IOException
     *     If an I/O problem prevented the SAS7BDAT from being created.
     */
    // The totalObservationsInDataset parameter is a kludge that enables that header and metadata pages to be completely
    // written by the time this method returns.  It would be a better API if the caller didn't have to provide this
    // information up-front, but that would require seeking backward and fixing the parts of the metadata that
    // depend on the observations count.  Perhaps a future implementation will do this.
    public Sas7bdatExporter(Path targetLocation, Sas7bdatMetadata metadata, int totalObservationsInDataset)
        throws IOException {
        ArgumentUtil.checkNotNull(targetLocation, "targetLocation");
        ArgumentUtil.checkNotNull(metadata, "metadata");

        outputStream = Files.newOutputStream(targetLocation);
        variablesLayout = new Sas7bdatVariablesLayout(metadata.variables());
        this.totalObservationsInDataset = totalObservationsInDataset;
        pageSequenceGenerator = new PageSequenceGenerator();

        //
        // Create the metadata for this dataset.
        //
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);
        pageBuffer = new byte[pageLayout.pageSize];

        // Add the subheaders in the order in which they should be listed in the subheaders index.
        // Note that this is the reverse order in which they appear on a metadata page.
        RowSizeSubheader rowSizeSubheader = new RowSizeSubheader(
            pageSequenceGenerator,
            metadata.datasetType(),
            metadata.datasetLabel(),
            variablesLayout,
            pageLayout,
            totalObservationsInDataset);
        pageLayout.addSubheader(rowSizeSubheader);

        pageLayout.addSubheader(new ColumnSizeSubheader(metadata.variables()));

        SubheaderCountsSubheader subheaderCountsSubheader = new SubheaderCountsSubheader(
            metadata.variables(),
            pageLayout);
        pageLayout.addSubheader(subheaderCountsSubheader);

        // Next, SAS adds the ColumnTextSubheaders.  Since a Subheader cannot be larger than Short.MAX_SIZE
        // bytes, if there's a lot of metadata text, multiple ColumnTextSubheaders may be needed.
        // SAS adds these in a way that is aware of how much space is left on the metadata page so that
        // a subheader is limited to fill what's left on the page.  Therefore, populating the
        // ColumnTextSubheaders must be coupled with the logic for adding subheaders to pages.
        // And since all ColumnTextSubheaders must be consecutive, all text must be added at the same time.
        //
        // I would have preferred a design where each subheader that needs to reference a string would
        // be the one to add it to the ColumnText.  I think that would have been better encapsulation.
        // However, I wasn't able to make that design work and still limit the column text subheaders
        // to fit the available space on the page.  Therefore, all strings referenced by any subheader
        // are added here.
        //
        // To assist with troubleshooting, the text is added in the same order in which SAS adds it.

        // The first ColumnTextSubheader in the datasets which SAS generates has an extra
        // four bytes of padding between the size of the header and the first string.
        // Adding the string of 0x00 0x00 0x00 0x00 matches what SAS usually generates.
        // Sometimes SAS generates 0x00 0x00 0x00 0x14 or 0x00 0x00 0x00 0x1d,
        // but I don't know if this has any meaning.
        pageLayout.columnText.add("\0\0\0\0");

        pageLayout.columnText.add(" ".repeat(8)); // unknown

        // Add the dataset type, padded with spaces.
        // We duplicateIfExists=true because if the datasetType is the empty string, we still
        // want to reserve the space for it, even though we added the same eight spaces above.
        String paddedDatasetType = metadata.datasetType() +
            " ".repeat(8 - metadata.datasetType().getBytes(StandardCharsets.UTF_8).length);
        pageLayout.columnText.add(paddedDatasetType);

        pageLayout.columnText.add("DATASTEP"); // add the PROC step which created the dataset
        pageLayout.columnText.add(metadata.datasetLabel()); // add the dataset label

        for (Variable variable : metadata.variables()) {
            pageLayout.columnText.add(variable.name());
            pageLayout.columnText.add(variable.label());

            // CONSIDER: SAS uppercases the format names before storing them.
            pageLayout.columnText.add(variable.inputFormat().name());
            pageLayout.columnText.add(variable.outputFormat().name());
        }

        // Add the partially-written column text subheader to the metadata page.
        // This is essential, as all column text subheaders must be added before
        // the next subheader type is added.
        pageLayout.columnText.noMoreText();

        int offset = 0;
        while (offset < variablesLayout.totalVariables()) {
            ColumnNameSubheader nextSubheader = new ColumnNameSubheader(
                metadata.variables(),
                offset,
                pageLayout.columnText);
            pageLayout.addSubheader(nextSubheader);
            offset += nextSubheader.totalVariablesInSubheader();
        }

        // Add the ColumnAttributesSubheaders
        offset = 0;
        while (offset < variablesLayout.totalVariables()) {
            // Datasets that are generated by SAS limit the size of this subheader to 24588 bytes.
            // In theory, it should be able to hold (Short.MAX_VALUE - 8) / 16 bytes, or 2047 variables.
            //
            // If there isn't enough space on the current metadata page for a subheader that contains all variables,
            // then split the subheader so that we use all remaining space on the metadata pages.  This is what
            // SAS does.
            final int spaceInPage = pageLayout.currentMetadataPage.totalBytesRemainingForNewSubheader();
            final int maxSize;
            if (spaceInPage < ColumnAttributesSubheader.MIN_SIZE) {
                // There's not enough space remaining for a useful header. Pick a large subheader for the next page.
                maxSize = 24588;
            } else {
                maxSize = Math.min(24588, spaceInPage);
            }

            ColumnAttributesSubheader nextSubheader = new ColumnAttributesSubheader(variablesLayout, offset, maxSize);
            pageLayout.addSubheader(nextSubheader);
            offset += nextSubheader.totalVariablesInSubheader();
        }

        if (1 < variablesLayout.totalVariables()) {
            // TODO when is this needed?  when there's more than one variable?
            offset = 0;
            while (offset < variablesLayout.totalVariables()) {
                ColumnListSubheader nextSubheader = new ColumnListSubheader(variablesLayout, offset);
                pageLayout.addSubheader(nextSubheader);
                offset += nextSubheader.totalVariablesInSubheader();
            }
        }
        for (Variable variable : metadata.variables()) {
            pageLayout.addSubheader(new ColumnFormatSubheader(variable, pageLayout.columnText));
        }

        // Finalize the subheaders on the final metadata page.
        pageLayout.finalizeSubheaders();

        // Mark the final metadata page as a "mixed" page, even if it doesn't contain data.
        // This is what SAS does.  I don't know if this is necessary.
        pageLayout.currentMetadataPage.setIsLastMetadataPage();

        final int maxObservationsOnMixedPage = pageLayout.currentMetadataPage.maxObservations();
        rowSizeSubheader.setTotalPossibleObservationOnMixedPage(pageLayout.currentMetadataPage.maxObservations());

        final int totalNumberOfMetadataPages = pageLayout.completeMetadataPages.size() + 1;
        rowSizeSubheader.setTotalMetadataPages(totalNumberOfMetadataPages);

        // Calculate how many observations fit on a data page.
        final int observationsPerDataPage = Sas7bdatPage.maxObservationsPerDataPage(
            pageLayout.pageSize,
            variablesLayout);
        rowSizeSubheader.setMaxObservationsPerDataPage(observationsPerDataPage);

        // Calculate how many pages will be needed in the dataset.
        {
            final int totalNumberOfDataPages;
            if (totalObservationsInDataset <= maxObservationsOnMixedPage) {
                // All observations can fit on the mixed page, so there's no need for data pages.
                totalNumberOfDataPages = 0;
            } else {
                int observationsOnAllDataPages = totalObservationsInDataset - maxObservationsOnMixedPage;
                // observationsOnAllDataPages / observationsPerDataPage rounded up
                totalNumberOfDataPages = divideAndRoundUp(observationsOnAllDataPages, observationsPerDataPage);
            }
            totalPagesInDataset = totalNumberOfMetadataPages + totalNumberOfDataPages;
        }
        rowSizeSubheader.setTotalPagesInDataset(totalPagesInDataset);

        // Write the file header.
        {
            Sas7bdatHeader header = new Sas7bdatHeader(
                pageSequenceGenerator,
                pageLayout.pageSize,
                pageLayout.pageSize,
                metadata.datasetName(),
                metadata.creationTime(),
                totalPagesInDataset);
            header.write(pageBuffer);
            outputStream.write(pageBuffer);
        }

        // Write out all complete metadata pages (but not the last one, which can hold observations)
        for (Sas7bdatPage currentMetadataPage : pageLayout.completeMetadataPages) {
            writePage(currentMetadataPage);
        }

        totalObservationsWritten = 0;
        totalPagesAllocated = totalNumberOfMetadataPages;
        currentPage = pageLayout.currentMetadataPage;
    }

    /**
     * Appends an observation (row) to the SAS7BDAT that is being exported.
     * <p>
     * This must be called exactly the same number of times as the {@code totalObservationInDataset} argument to this
     * exporter's constructor.
     * </p>
     *
     * @param observation
     *     The observation to write to the SAS7BDAT, given as a list of objects. The objects in the row must be given in
     *     the same order as the variables were given in the {@code Sas7bdatMetadata} that was given to this exporter's
     *     constructor.  For any variable whose type is {@link VariableType#CHARACTER}, the values must be given as a
     *     {@link String}.  For any variable whose type is {@link VariableType#NUMERIC}, the values must be given as a
     *     {@link Number} or {@code null}, which indicates a missing numeric value.
     *     <p>
     *     The observation and its data are immediately copied, so subsequent modifications to it don't change the
     *     SAS7BDAT that is exported.
     *     </p>
     *
     * @throws NullPointerException
     *     If {@code observation} is {@code null}, or if a {@code null} value is given to a variable whose type is
     *     {@code VariableType.CHARACTER}.
     * @throws IllegalStateException
     *     If writing this observation would exceed the {@code totalObservationsInDataset} argument given in the
     *     constructor or if this exporter has already been closed.
     * @throws IllegalArgumentException
     *     if {@code observation} doesn't contain values that conform to the {@code Sas7bdatMetadata} that was given to
     *     this exporter's constructor.
     * @throws IOException
     *     If an I/O error prevented the observation from being written.
     */
    public void writeObservation(List<Object> observation) throws IOException {
        ArgumentUtil.checkNotNull(observation, "observation");
        if (isClosed()) {
            throw new IllegalStateException("Cannot invoke writeObservation on closed exporter");
        }
        if (totalObservationsInDataset <= totalObservationsWritten) {
            throw new IllegalStateException("wrote more observations than promised in the constructor");
        }

        // Copy the observation to a byte array in case the caller modifies it.
        // TODO: it would be more efficient to copy it directly to the page's byte array.
        byte[] serializedObservation = new byte[variablesLayout.rowLength()];
        variablesLayout.writeObservation(serializedObservation, 0, observation);

        if (!currentPage.addObservation(serializedObservation)) {
            // The page is full.  Start the next one.

            // Write the page.
            writePage(currentPage);

            // Start a new data page.
            totalPagesAllocated++;
            currentPage = new Sas7bdatPage(pageSequenceGenerator, currentPage.pageSize, variablesLayout);
            currentPage.finalizeSubheaders(); // a data page has no subheaders

            // Write the observation to the new page.
            boolean success = currentPage.addObservation(serializedObservation);
            assert success : "couldn't write to new page";
        }

        totalObservationsWritten++;
    }

    /**
     * Determines whether all observations that were promised as the {@code totalObservations} argument to the
     * constructor have been written.
     * <p>
     *
     * @return {@code true}, if all observations that were promised have been written.  {@code false}, otherwise.
     *
     * @throws IllegalStateException
     *     if this exporter has already been closed.
     */
    public boolean isComplete() {
        if (isClosed()) {
            throw new IllegalStateException("Cannot invoke isComplete on closed exporter");
        }
        return totalObservationsInDataset == totalObservationsWritten;
    }

    private void writePage(Sas7bdatPage page) throws IOException {
        // Clear the data on the buffer so that parts of the previous page
        // don't get repeated in this page for the parts that aren't filled in.
        //
        // That said, SAS echos part of the second-to-final page on the conceptually
        // blank parts of the final data page, so using a dirty buffer may better
        // match what SAS does.  On the other hand, re-using a dirty buffer has
        // put some information on the first data page that caused SAS to skip
        // data on the second data page, so there must be some part of the
        // data footer that is significant.
        Arrays.fill(pageBuffer, (byte) 0x00);

        page.write(pageBuffer);
        outputStream.write(pageBuffer);
    }

    /**
     * Gets whether {@link #close()} has been invoked on this exporter.
     *
     * @return {@code true}, if this exporter is closed; {@code false}, otherwise.
     */
    private boolean isClosed() {
        return currentPage == null;
    }

    public void close() throws IOException {

        if (!isClosed()) {
            // It'd be nice to throw an exception if fewer observation were written than were promised in the
            // constructor, since it means that the header was written incorrectly, but since close() may be
            // invoked as a result of an exception when writing, doing so would
            // suppress the original exception with one that doesn't contain new information.
            //
            // The best we can do is for the caller to "opt in" by asserting isComplete().

            // Write the page.
            writePage(currentPage);
            currentPage = null;

            outputStream.close();

            assert totalPagesAllocated == totalPagesInDataset : "wrote " + totalPagesAllocated + " out of " + totalPagesInDataset + " pages.";
        }
    }

    /**
     * Writes a SAS7BDAT file with the given metadata and observations to the file system.
     * <p>
     * If your data set is too large to hold in memory, then you should use {@link Sas7bdatExporter#Sas7bdatExporter}
     * constructor and stream the observations using {@link Sas7bdatExporter#writeObservation writeObservation}.
     * </p>
     *
     * @param targetLocation
     *     The location where
     * @param metadata
     *     The data set's metadata.
     * @param observations
     *     A list of observations. Each element in this list is a list of objects that represents an observation (row).
     *     The objects in the row must be given in the same order as the variables are given in {@code metadata}.  For
     *     any variable whose type is {@link VariableType#CHARACTER}, the values must be given as a {@link String}.  For
     *     any variable whose type is {@link VariableType#NUMERIC}, the values must be given as a {@link Number} or
     *     {@code null}, which indicates a missing numeric value.
     *
     * @throws IOException
     *     If a file I/O error prevents the dataset from being written.
     * @throws NullPointerException
     *     If {@code targetLocation}, {@code metadata}, or {@code observations} is {@code null}.
     */
    public static void exportDataset(Path targetLocation, Sas7bdatMetadata metadata, List<List<Object>> observations)
        throws IOException {
        ArgumentUtil.checkNotNull(observations, "observations");
        ArgumentUtil.checkNotNull(targetLocation, "targetLocation");
        ArgumentUtil.checkNotNull(metadata, "metadata");

        try (Sas7bdatExporter datasetWriter = new Sas7bdatExporter(targetLocation, metadata, observations.size())) {

            // Write all observations
            for (List<Object> observation : observations) {
                if (observation == null) {
                    throw new NullPointerException("observations must not contain a null observation");
                }
                datasetWriter.writeObservation(observation);
            }
            assert datasetWriter.isComplete();
        }
    }
}