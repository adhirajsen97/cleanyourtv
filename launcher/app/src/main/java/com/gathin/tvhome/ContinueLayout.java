package com.gathin.tvhome;

/**
 * Where the dock parks, and where the "Continue watching" row, its caption and the top shelf go above it,
 * in pixels. Pure arithmetic, so the numbers at 4 and 6 columns are JVM-tested (ContinueLayoutTest).
 *
 * Up from the top of the dock tiles: the dock band's 14dp inset, a 12dp gap, the row's tiles, 8dp, the
 * caption, 10dp, the top shelf.
 */
final class ContinueLayout {

    /** Row tiles are never narrower than this: at 6 columns the dock's own tiles (about 117dp) are too small for artwork. */
    static final float MIN_TILE_W_DP = 144f;

    /** Top of the row's tiles, top of the caption, bottom of the top shelf. */
    final int tileTop, labelTop, heroBottom;

    private ContinueLayout(int tileTop, int labelTop, int heroBottom) {
        this.tileTop = tileTop;
        this.labelTop = labelTop;
        this.heroBottom = heroBottom;
    }

    /**
     * The grid's top padding that pushes the dock row towards the bottom of the screen, leaving room for
     * rows_below_dock grid rows under it. Item height follows the tile size, which follows the column count.
     */
    static int dockPaddingTop(int gridHeightPx, int innerWidthPx, int columns, int rowsBelowDock, float density) {
        int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(AppAdapter.tileWidthPx(innerWidthPx, columns, density)), density);
        int dockRow = itemH + (int) ((6 + 12) * density);   // item height plus DockDecoration offsets
        int gridRows = itemH * rowsBelowDock;
        return Math.max(0, gridHeightPx - dockRow - gridRows - (int) (30 * density));
    }

    /** Top of the dock tiles in root coordinates while the dock is parked on the grid's top padding. */
    static int dockCardTop(int gridTopPx, int paddingTopPx, float density) {
        // DockDecoration's offset above the dock items, then item_app's padding above the tile.
        return gridTopPx + paddingTopPx + (int) (6 * density) + Math.round(8 * density);
    }

    static int tileWidthPx(int dockTileWidthPx, float density) {
        return Math.max(dockTileWidthPx, Math.round(MIN_TILE_W_DP * density));
    }

    /**
     * One row slot: room for the focus zoom and the glass like a grid cell, at least the dock's own cell
     * so that at 4 columns the row's tiles stand exactly over the dock's, but never so wide that four
     * slots overflow the grid (fewer than 4 columns).
     */
    static int cellWidthPx(int tileWidthPx, int innerWidthPx, int columns, float density) {
        int zoomed = Math.round(tileWidthPx * AppAdapter.FOCUS_SCALE + 12 * density);
        int dockCell = innerWidthPx / Math.max(1, columns);
        return Math.max(zoomed, Math.min(dockCell, innerWidthPx / LastPlayed.MAX));
    }

    /**
     * The row's left and right padding (it spans the screen): its first tile starts where the first dock
     * tile starts (the right end in Hebrew, by symmetry), unless four slots would then run off the screen.
     */
    static int sidePaddingPx(int rootWidthPx, int gridLeftPx, int innerWidthPx, int columns, int tileWidthPx,
                             int cellWidthPx, float density) {
        int dockTile = AppAdapter.tileWidthPx(innerWidthPx, columns, density);
        int dockCell = innerWidthPx / Math.max(1, columns);
        int aligned = gridLeftPx + (dockCell - dockTile) / 2 - (cellWidthPx - tileWidthPx) / 2;
        int limit = (rootWidthPx - LastPlayed.MAX * cellWidthPx) / 2;
        return Math.max(0, Math.min(aligned, limit));
    }

    static ContinueLayout stack(int dockCardTopPx, int tileHeightPx, int labelHeightPx, float density) {
        int tileBottom = dockCardTopPx - Math.round((14 + 12) * density);
        int tileTop = tileBottom - tileHeightPx;
        int labelTop = tileTop - Math.round(8 * density) - labelHeightPx;
        return new ContinueLayout(tileTop, labelTop, labelTop - Math.round(10 * density));
    }

    /** The top shelf still clears the header; otherwise (e.g. rows_below_dock 1 or more) the row is not shown. */
    static boolean fits(int heroBottomPx, int heroHeightPx, int headerBottomPx) {
        return heroBottomPx - heroHeightPx >= headerBottomPx;
    }
}
