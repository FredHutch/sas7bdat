package org.scharp.sas7bdat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Unit tests for {@link Sas7bdatPageLayout}. */
public class Sas7bdatPageLayoutTest {

    @Test
    void smokeTest() {
        // Create a Sas7bdatPageLayout
        PageSequenceGenerator pageSequenceGenerator = new PageSequenceGenerator();
        Sas7bdatVariablesLayout variablesLayout = new Sas7bdatVariablesLayout(List.of(
            Variable.builder().name("VAR").type(VariableType.CHARACTER).length(10).build()));
        Sas7bdatPageLayout pageLayout = new Sas7bdatPageLayout(pageSequenceGenerator, variablesLayout);

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
}