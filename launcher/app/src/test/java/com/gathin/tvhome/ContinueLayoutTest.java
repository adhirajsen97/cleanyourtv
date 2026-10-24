package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A 1080p TV: 1920x1080 px at density 2 (960x540dp). The grid starts 56dp from each side, so it is
 * 1696 px wide at x 112; its top sits at about 116dp (232 px) under the header, which ends at about
 * 106dp (212 px). The top shelf (48sp) is about 61dp tall and the caption (12sp) about 16dp.
 */
public class ContinueLayoutTest {
    private static final float D = 2f;
    private static final int ROOT_W = 1920, GRID_LEFT = 112, INNER = 1696, GRID_TOP = 232, GRID_H = 848;
    private static final int HEADER_BOTTOM = 212, HERO_H = 122, LABEL_H = 32;

    private static int dockTile(int columns) {
        return AppAdapter.tileWidthPx(INNER, columns, D);
    }

    private static ContinueLayout stackFor(int columns, int rowsBelowDock) {
        int padding = ContinueLayout.dockPaddingTop(GRID_H, INNER, columns, rowsBelowDock, D);
        int tileH = AppAdapter.tileHeightPx(ContinueLayout.tileWidthPx(dockTile(columns), D));
        return ContinueLayout.stack(ContinueLayout.dockCardTop(GRID_TOP, padding, D), tileH, LABEL_H, D);
    }

    @Test public void sixColumnsGetTilesLargerThanTheDock() {
        assertEquals(234, dockTile(6));                              // about 117dp
        assertEquals(288, ContinueLayout.tileWidthPx(dockTile(6), D)); // 144dp
        assertEquals(162, AppAdapter.tileHeightPx(288));
        assertEquals(341, ContinueLayout.cellWidthPx(288, INNER, 6, D));
    }

    @Test public void fourColumnsMatchTheDockExactly() {
        assertEquals(360, dockTile(4));
        assertEquals(360, ContinueLayout.tileWidthPx(dockTile(4), D));
        assertEquals(202, AppAdapter.tileHeightPx(360));
        assertEquals("the dock's own cell", 424, ContinueLayout.cellWidthPx(360, INNER, 4, D));
    }

    @Test public void firstRowTileStartsOverTheFirstDockTile() {
        for (int columns : new int[]{4, 6}) {
            int tile = ContinueLayout.tileWidthPx(dockTile(columns), D);
            int cell = ContinueLayout.cellWidthPx(tile, INNER, columns, D);
            int side = ContinueLayout.sidePaddingPx(ROOT_W, GRID_LEFT, INNER, columns, tile, cell, D);
            int dockCell = INNER / columns;
            assertEquals("columns " + columns, GRID_LEFT + (dockCell - dockTile(columns)) / 2, side + (cell - tile) / 2);
            assertTrue("four tiles fit", ROOT_W - 2 * side >= LastPlayed.MAX * cell);
        }
    }

    @Test public void fewerColumnsKeepFourTilesOnScreen() {
        int tile = ContinueLayout.tileWidthPx(dockTile(3), D);
        int cell = ContinueLayout.cellWidthPx(tile, INNER, 3, D);
        int side = ContinueLayout.sidePaddingPx(ROOT_W, GRID_LEFT, INNER, 3, tile, cell, D);
        assertEquals(424, cell);
        assertEquals(GRID_LEFT, side);
    }

    @Test public void dockParksAsBefore() {
        // The numbers MainActivity used before the formula moved here.
        assertEquals(531, ContinueLayout.dockPaddingTop(GRID_H, INNER, 6, 0, D));
        assertEquals(310, ContinueLayout.dockPaddingTop(GRID_H, INNER, 6, 1, D));
        assertEquals(460, ContinueLayout.dockPaddingTop(GRID_H, INNER, 4, 0, D));
        assertEquals(168, ContinueLayout.dockPaddingTop(GRID_H, INNER, 4, 1, D));
        assertEquals(0, ContinueLayout.dockPaddingTop(100, INNER, 4, 3, D));
        assertEquals(791, ContinueLayout.dockCardTop(GRID_TOP, 531, D));
    }

    @Test public void sixColumnsStackAboveTheDock() {
        ContinueLayout s = stackFor(6, 0);
        // dock tiles at 791: 52 px of band and gap, 162 px of tile, 16 + 32 for the caption, 20 to the shelf.
        assertEquals(577, s.tileTop);
        assertEquals(529, s.labelTop);
        assertEquals(509, s.heroBottom);
        assertTrue(ContinueLayout.fits(s.heroBottom, HERO_H, HEADER_BOTTOM));
    }

    @Test public void fourColumnsStackAboveTheDock() {
        ContinueLayout s = stackFor(4, 0);
        assertEquals(466, s.tileTop);
        assertEquals(418, s.labelTop);
        assertEquals(398, s.heroBottom);
        assertTrue(ContinueLayout.fits(s.heroBottom, HERO_H, HEADER_BOTTOM));
    }

    @Test public void aGridRowUnderTheDockLeavesNoRoom() {
        assertFalse(ContinueLayout.fits(stackFor(6, 1).heroBottom, HERO_H, HEADER_BOTTOM));
        assertFalse(ContinueLayout.fits(stackFor(4, 1).heroBottom, HERO_H, HEADER_BOTTOM));
    }

    @Test public void fitsIsInclusiveAtTheHeader() {
        assertTrue(ContinueLayout.fits(334, 122, 212));
        assertFalse(ContinueLayout.fits(333, 122, 212));
    }
}
