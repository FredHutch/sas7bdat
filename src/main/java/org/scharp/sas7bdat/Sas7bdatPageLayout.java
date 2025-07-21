package org.scharp.sas7bdat;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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

    void finalizeSubheadersOnCurrentMetadataPage() {
        currentMetadataPage.finalizeSubheaders();

        // finalizeSubheaders() inserts a terminal subheader.  We must therefore update the
        // subheaders list to include this terminal subheader.
        Subheader terminalSubheader = currentMetadataPage.subheaders().get(
            currentMetadataPage.subheaders().size() - 1);
        subheaders.add(terminalSubheader);
        subheaderLocations.put(terminalSubheader, completeMetadataPages.size() + 1);
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
        subheaderLocations.put(subheader, completeMetadataPages.size() + 1);
    }
}