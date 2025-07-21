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
    final List<Subheader> subheaders;
    final List<Sas7bdatPage> completeMetadataPages;

    Sas7bdatPage currentMetadataPage;

    Sas7bdatPageLayout(PageSequenceGenerator pageSequenceGenerator, Sas7bdatVariablesLayout variablesLayout) {
        this.pageSequenceGenerator = pageSequenceGenerator;
        this.pageSize = Sas7bdatPage.calculatePageSize(variablesLayout);
        this.variablesLayout = variablesLayout;
        this.columnText = new ColumnText(this);

        subheaders = new ArrayList<>();
        completeMetadataPages = new ArrayList<>();
        currentMetadataPage = new Sas7bdatPage(pageSequenceGenerator, pageSize, variablesLayout);
    }

    void finalizeSubheadersOnCurrentMetadataPage() {
        currentMetadataPage.finalizeSubheaders();

        // finalizeSubheaders() inserts a terminal subheader.  We must therefore update the
        // subheaders list to include this terminal subheader.
        Subheader terminalSubheader = currentMetadataPage.subheaders().get(
            currentMetadataPage.subheaders().size() - 1);
        subheaders.add(terminalSubheader);
    }

    void addSubheader(Subheader subheader) {
        if (!currentMetadataPage.addSubheader(subheader)) {
            // There's not enough space on this metadata page for this subheader.

            // Finalize the subheaders on the current page.
            finalizeSubheadersOnCurrentMetadataPage();

            // Create a new page.
            completeMetadataPages.add(currentMetadataPage);
            currentMetadataPage = new Sas7bdatPage(pageSequenceGenerator, pageSize, variablesLayout);

            // Add the subheader to the new page.
            boolean success = currentMetadataPage.addSubheader(subheader);
            assert success : "couldn't add a subheader to a new page";
        }

        // Track which page the subheader was added to.
        subheaders.add(subheader);
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

        // The final metadata page is never added to completeMetadataPages, so we gave to iterate over it specially.
        // TODO: change the logic so that it's added.
        for (int subheaderIndex = 0; subheaderIndex < currentMetadataPage.subheaders().size(); ++subheaderIndex) {
            Subheader currentSubheader = currentMetadataPage.subheaders().get(subheaderIndex);
            nextSubheader.nextSubheader(currentSubheader, (short) (completeMetadataPages.size() + 1),
                (short) (subheaderIndex + 1));
        }
    }
}