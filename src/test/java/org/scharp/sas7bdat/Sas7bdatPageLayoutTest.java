///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2025 Fred Hutch Cancer Center
// Licensed under the MIT License - see LICENSE file for details
///////////////////////////////////////////////////////////////////////////////
package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Unit tests for {@link Sas7bdatPageLayout}. */
public class Sas7bdatPageLayoutTest {

    private static Sas7bdatPageLayout newSas7bdatPageLayout() {
        // Create a Sas7bdatPageLayout
        PageNumberSequence pageNumberSequence = new PageNumberSequence(0);
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of(
            Variable.builder().name("VAR").type(VariableType.CHARACTER).length(10).build()));
        return new Sas7bdatPageLayout(pageNumberSequence, variablesLayout);
    }

    @Test
    void smokeTest() {
        Sas7bdatPageLayout pageLayout = newSas7bdatPageLayout();

        assertEquals(0x10000, pageLayout.pageSize);

        // Add a large subheader
        Subheader subheader1 = new FillerSubheader(Short.MAX_VALUE);
        pageLayout.addSubheader(subheader1);

        // Add a subheader that it too large to fit into the remaining space on the first page.
        Subheader subheader2 = new FillerSubheader(Short.MAX_VALUE);
        pageLayout.addSubheader(subheader2);

        // Add a subheader that can fit onto the second page.
        Subheader subheader3 = new FillerSubheader(40);
        pageLayout.addSubheader(subheader3);

        // Add a subheader that is too large to it on the second page.
        Subheader subheader4 = new FillerSubheader(Short.MAX_VALUE);
        pageLayout.addSubheader(subheader4);

        // Finalize the metadata.
        Sas7bdatPage mixedPage = pageLayout.finalizeMetadata();

        // Confirm that the mixed page's type was set to mixed.
        assertEquals(0x10000, mixedPage.pageSize());
        byte[] mixedPageData = new byte[mixedPage.pageSize()];
        mixedPage.write(mixedPageData);
        assertEquals(0x00, mixedPageData[32], "finalizeMetadata() did not return a mixed page");
        assertEquals(0x02, mixedPageData[33], "finalizeMetadata() did not return a mixed page");

        // Confirm that we can iterate over the subheaders.
        int[] finalInvocationIndex = { 0 };
        pageLayout.forEachSubheader((Subheader subheader, short pageIndex, short subheaderPosition) -> {
            switch (finalInvocationIndex[0]) {
            case 0:
                assertSame(subheader1, subheader);
                assertEquals(1, pageIndex);
                assertEquals(1, subheaderPosition);
                break;

            case 1:
                assertInstanceOf(TerminalSubheader.class, subheader);
                assertEquals(1, pageIndex);
                assertEquals(2, subheaderPosition);
                break;

            case 2:
                assertSame(subheader2, subheader);
                assertEquals(2, pageIndex);
                assertEquals(1, subheaderPosition);
                break;

            case 3:
                assertSame(subheader3, subheader);
                assertEquals(2, pageIndex);
                assertEquals(2, subheaderPosition);
                break;

            case 4:
                assertInstanceOf(TerminalSubheader.class, subheader);
                assertEquals(2, pageIndex);
                assertEquals(3, subheaderPosition);
                break;

            case 5:
                assertSame(subheader4, subheader);
                assertEquals(3, pageIndex);
                assertEquals(1, subheaderPosition);
                break;

            case 6:
                assertInstanceOf(TerminalSubheader.class, subheader);
                assertEquals(3, pageIndex);
                assertEquals(2, subheaderPosition);
                break;
            }

            finalInvocationIndex[0]++;
        });
        assertEquals(7, finalInvocationIndex[0], "forEachSubheader callback invoked incorrect number of times");
    }

    @Test
    void testGetMaxSizeOfNextSubheader() {
        Sas7bdatPageLayout pageLayout = newSas7bdatPageLayout();

        // When the page layout doesn't have any subheaders, it acts as if the subheader
        // isn't the first subheader on the page.  This may be a bug, but it doesn't show up
        // in the product because the first subheader in the dataset has a fixed size.
        assertEquals(32740, pageLayout.getMaxSizeOfNextSubheader(0));
        assertEquals(32740, pageLayout.getMaxSizeOfNextSubheader(32741));

        // Add a subheader so that non-first subheader on a page has a smaller size.
        pageLayout.addSubheader(new FillerSubheader(10));
        assertEquals(32740, pageLayout.getMaxSizeOfNextSubheader(0));
        assertEquals(32740, pageLayout.getMaxSizeOfNextSubheader(32741));

        // When a subheader won't fit on the current page, it is sized to fix the next page.
        pageLayout.addSubheader(new FillerSubheader(Short.MAX_VALUE));
        pageLayout.addSubheader(new FillerSubheader(30_000));
        assertEquals(2599, pageLayout.currentMetadataPage.totalBytesRemainingForNewSubheader()); // just as an FYI
        assertEquals(2599, pageLayout.getMaxSizeOfNextSubheader(0));  // current page
        assertEquals(32676, pageLayout.getMaxSizeOfNextSubheader(2600)); // next page
        assertEquals(32741, pageLayout.getMaxSizeOfNextSubheader(32741)); // next page
    }
}