///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class for putting subheaders into metadata pages.
 *
 * <p>
 * This is also responsible for providing information about the overall dataset to each of the subheaders. As such, it
 * calculates the following:
 * <ol>
 * <li>the page size</li>
 * <li>how many observations fit on the mixed page</li>
 * <li>how many metadata/mixed pages are needed</li>
 * <li>the location of each subheader</li>
 * </ol>
 */
class Sas7bdatPageLayout {
    private final PageSequenceGenerator pageSequenceGenerator;
    final int pageSize;
    private final Sas7bdatVariablesLayout variablesLayout;
    final ColumnText columnText;
    final List<Sas7bdatPage> completeMetadataPages;

    Sas7bdatPage currentMetadataPage;

    Sas7bdatPageLayout(PageSequenceGenerator pageSequenceGenerator, Sas7bdatVariablesLayout variablesLayout) {
        this.pageSequenceGenerator = pageSequenceGenerator;
        this.pageSize = Sas7bdatPage.calculatePageSize(variablesLayout);
        this.variablesLayout = variablesLayout;
        this.columnText = new ColumnText(this);

        completeMetadataPages = new ArrayList<>();
        currentMetadataPage = new Sas7bdatPage(pageSequenceGenerator, pageSize, variablesLayout);
    }

    private void finalizeSubheadersOnCurrentMetadataPage() {
        currentMetadataPage.finalizeSubheaders();

        completeMetadataPages.add(currentMetadataPage);
    }

    void addSubheader(Subheader subheader) {
        if (!currentMetadataPage.addSubheader(subheader)) {
            // There's not enough space on this metadata page for this subheader.

            // Finalize the subheaders on the current page.
            finalizeSubheadersOnCurrentMetadataPage();

            // Create a new page.
            currentMetadataPage = new Sas7bdatPage(pageSequenceGenerator, pageSize, variablesLayout);

            // Add the subheader to the new page.
            boolean success = currentMetadataPage.addSubheader(subheader);
            assert success : "couldn't add a subheader to a new page";
        }
    }

    /**
     * Finalizes the metadata pages and returns the final metadata page, which may be able to hold observations (and is
     * therefore a mixed page).
     *
     * @return a mixed page.
     */
    Sas7bdatPage finalizeMetadata() {
        // Mark the final metadata page as a "mixed" page, even if it doesn't contain data.
        // This is what SAS does.  I don't know if this is necessary.
        currentMetadataPage.setIsFinalMetadataPage();

        // Finalize the subheader on the mixed page.
        finalizeSubheadersOnCurrentMetadataPage();

        return currentMetadataPage;
    }

    /**
     * An interface that defines a callback when iterating over all subheaders in the data set.
     */
    @FunctionalInterface
    interface NextSubheader {
        /**
         * A callback when iterating over all subheaders in the data set.
         *
         * @param subheader
         *     The next subheader in the data set.
         * @param pageNumberOfSubheader
         *     The page number where {@code subheader} is located.  1 is the first page number.
         * @param positionInPage
         *     The position of {@code subheader} within the page.  The first subheader position is 1.
         */
        void nextSubheader(Subheader subheader, short pageNumberOfSubheader, short positionInPage);
    }

    /**
     * Iterates over all subheaders in the data set, invoking a callback for each subheader.
     *
     * @param nextSubheader
     *     A callback interface
     */
    void forEachSubheader(NextSubheader nextSubheader) {
        // Iterate over all pages
        for (int pageIndex = 0; pageIndex < completeMetadataPages.size(); ++pageIndex) {
            Sas7bdatPage currentPage = completeMetadataPages.get(pageIndex);

            // Iterate over subheaders within the page
            for (int subheaderIndex = 0; subheaderIndex < currentPage.subheaders().size(); ++subheaderIndex) {
                Subheader currentSubheader = currentPage.subheaders().get(subheaderIndex);

                // Invoke the callback
                nextSubheader.nextSubheader(currentSubheader, (short) (pageIndex + 1), (short) (subheaderIndex + 1));
            }
        }
    }
}