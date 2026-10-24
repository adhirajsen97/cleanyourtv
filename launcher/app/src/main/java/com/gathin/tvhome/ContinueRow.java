package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "Continue watching" row above the dock (config.xml, last_played): up to four tiles with what apps
 * last played, from what PlaybackListener saved. It shows only while a dock or row tile has focus (grid
 * rows scroll up into its place), only when it has something, and only when it fits between the header
 * and the dock. MainActivity keeps the glass, the top shelf and the dock; the Host calls tell it when.
 */
final class ContinueRow implements AppAdapter.TileListener {

    interface Host {
        /** A row tile took focus: the glass goes on it and its title into the top shelf. */
        void onRowTileFocused(View card, String title, int color);

        /** Down from the row, or the row is about to go while it has focus. */
        void focusDock();

        /**
         * Up from the row: the top-bar button nearest the tile. The row is placed with setY, and Android's
         * focus search goes by layout position, so left to itself it finds nothing above the row.
         */
        void focusTopBar(View from);

        /** The row appeared or went: the top shelf moves above it or back down. */
        void onRowShownChanged();
    }

    // Static like MainActivity.lastFocused, so they survive the activity being destroyed while an app is
    // open: Back from an app opened from the row returns to its tile. Home clears returnToRow.
    private static boolean returnToRow;
    private static String lastKey;

    private final Activity activity;
    private final Host host;
    private final AppAdapter apps;
    private final RecyclerView grid;
    private final RecyclerView row;
    private final TextView label;
    private final View header;
    private final View hero;
    private final LastPlayedAdapter adapter;
    private final boolean animations;
    private final boolean reducedMotion;
    private final boolean rtl;
    private final float density;
    private final File listFile;
    private final File artDir;
    private final Map<String, Art> artCache = new HashMap<>();
    private final Runnable changed = this::reload;

    /** A dock or row tile has focus (or the top bar, coming from one); false while a grid tile has it. */
    private boolean zone = true;
    private boolean laidOut;
    private boolean fits;
    private boolean shown;
    /** Set when the row's alpha or visibility no longer matches {@link #shown}. */
    private boolean refade;
    private int heroBottom;
    private int dockCardTop;
    private String signature;
    /** A tile to focus once the row has laid out its new list. */
    private String pendingFocusKey;
    /** Focus went back to the row before its first layout: decide once it is laid out. */
    private boolean pendingRestore;
    /** The row is shown but waits for the grid to stop scrolling before it fades in. */
    private boolean fadeWaiting;
    /** The dock slides back into place under the row's spot: fade in once it is there, not over it. */
    private final RecyclerView.OnScrollListener gridSettled = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
            if (state != RecyclerView.SCROLL_STATE_IDLE) return;
            rv.removeOnScrollListener(this);
            if (fadeWaiting && shown) fadeIn();
            fadeWaiting = false;
        }
    };

    private static final class Art {
        final long modified, length;
        final Bitmap bitmap;
        final int color;

        Art(long modified, long length, Bitmap bitmap, int color) {
            this.modified = modified;
            this.length = length;
            this.bitmap = bitmap;
            this.color = color;
        }
    }

    ContinueRow(Activity activity, Host host, AppAdapter apps, RecyclerView grid, RecyclerView row, TextView label,
                View header, View hero, Config cfg, boolean rtl) {
        this.activity = activity;
        this.host = host;
        this.apps = apps;
        this.grid = grid;
        this.row = row;
        this.label = label;
        this.header = header;
        this.hero = hero;
        this.animations = cfg.animations;
        this.reducedMotion = cfg.reducedMotion;
        this.rtl = rtl;
        this.density = activity.getResources().getDisplayMetrics().density;
        listFile = new File(activity.getFilesDir(), LastPlayed.FILE_NAME);
        artDir = new File(activity.getFilesDir(), LastPlayed.ART_DIR);

        // RecyclerView makes itself focusable: empty, it would take focus itself and the glass would go.
        row.setFocusable(false);
        row.setFocusableInTouchMode(false);
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        row.setItemAnimator(null);
        row.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false) {
            @Override
            protected boolean isLayoutRTL() {
                return rtl;   // Hebrew starts the row on the right, like the dock
            }

            /** Four tiles always fit, so the row never scrolls; the glass does not follow a scroll here. */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                return false;
            }

            @Override
            public void onLayoutCompleted(RecyclerView.State state) {
                super.onLayoutCompleted(state);
                if (pendingFocusKey != null) row.post(ContinueRow.this::focusPending);
            }
        });
        adapter = new LastPlayedAdapter(activity, this, cfg, apps.focusScale());
        row.setAdapter(adapter);
        // Invisible, not gone: the caption's height places everything, and the tiles are laid out
        // before they show, so focus can go to them at once.
        row.setAlpha(0f);
        label.setAlpha(0f);
        row.setVisibility(View.INVISIBLE);
        label.setVisibility(View.INVISIBLE);
    }

    static void forgetReturn() {
        returnToRow = false;
    }

    boolean shown() {
        return shown;
    }

    int heroBottom() {
        return heroBottom;
    }

    int dockCardTop() {
        return dockCardTop;
    }

    void resume() {
        PlaybackListener.setChangeListener(changed);
    }

    void pause() {
        PlaybackListener.setChangeListener(null);
    }

    // ---- placement -------------------------------------------------------------------------------

    /** From the grid's layout listener: the row, caption and top shelf stack up from the parked dock. */
    void layout(int innerWidth, int paddingTop) {
        ViewGroup root = (ViewGroup) row.getParent();
        Rect g = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, g);
        Rect head = new Rect(0, 0, header.getWidth(), header.getHeight());
        root.offsetDescendantRectToMyCoords(header, head);
        int columns = MainActivity.COLUMNS;
        int tileW = ContinueLayout.tileWidthPx(AppAdapter.tileWidthPx(innerWidth, columns, density), density);
        int tileH = AppAdapter.tileHeightPx(tileW);
        int cellW = ContinueLayout.cellWidthPx(tileW, innerWidth, columns, density);
        int side = ContinueLayout.sidePaddingPx(root.getWidth(), g.left + grid.getPaddingLeft(), innerWidth,
                columns, tileW, cellW, density);
        dockCardTop = ContinueLayout.dockCardTop(g.top, paddingTop, density);
        ContinueLayout stack = ContinueLayout.stack(dockCardTop, tileH, label.getHeight(), density);
        heroBottom = stack.heroBottom;
        row.setY(stack.tileTop - Math.round(8 * density));   // item_app's padding above the tile
        label.setY(stack.labelTop);
        boolean nowFits = ContinueLayout.fits(stack.heroBottom, hero.getHeight(), head.bottom);
        if (laidOut && nowFits == fits && adapter.hasSizes(tileW, tileH, cellW) && row.getPaddingLeft() == side) return;
        // Padding and tile sizes ask for another layout pass, so not from inside this one.
        root.post(() -> applyLayout(tileW, tileH, cellW, side, nowFits));
    }

    private void applyLayout(int tileW, int tileH, int cellW, int side, boolean nowFits) {
        if (row.getPaddingLeft() != side || row.getPaddingRight() != side) row.setPadding(side, 0, side, 0);
        if (adapter.setSizes(tileW, tileH, cellW)) adapter.setItems(currentItems());
        fits = nowFits;
        laidOut = true;
        if (!fits && row.hasFocus()) host.focusDock();
        update();
        if (pendingRestore) {
            pendingRestore = false;
            if (!restoreNow()) host.focusDock();
        }
    }

    private List<LastPlayedAdapter.Item> currentItems() {
        List<LastPlayedAdapter.Item> out = new ArrayList<>();
        for (int i = 0; i < adapter.getItemCount(); i++) out.add(adapter.itemAt(i));
        return out;
    }

    // ---- data ------------------------------------------------------------------------------------

    /**
     * Reads what was saved and keeps the apps that are tiles here (hidden_apps and uninstalled apps go),
     * at most four, so the row never scrolls. Cheap when nothing changed: the pictures are decoded once.
     */
    void reload() {
        List<LastPlayed.Entry> saved = LastPlayed.read(listFile);
        List<LastPlayed.Entry> shownEntries = new ArrayList<>();
        List<String> appLabels = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (LastPlayed.Entry e : saved) {
            int pos = apps.positionOfPackage(e.pkg);
            if (pos < 0) continue;
            String appLabel = apps.labelAt(pos);
            shownEntries.add(e);
            appLabels.add(appLabel);
            File art = artFile(e.pkg);
            sig.append(appLabel).append('\u0000').append(e.hasArt ? art.lastModified() + ":" + art.length() : "-").append('\n');
            if (shownEntries.size() == LastPlayed.MAX) break;
        }
        sig.append(LastPlayed.encode(shownEntries));
        if (sig.toString().equals(signature)) return;
        signature = sig.toString();

        Set<String> keep = new HashSet<>();
        List<LastPlayedAdapter.Item> items = new ArrayList<>();
        PackageManager pm = activity.getPackageManager();
        for (int i = 0; i < shownEntries.size(); i++) {
            LastPlayed.Entry e = shownEntries.get(i);
            keep.add(e.pkg);
            Art art = e.hasArt ? art(e.pkg) : null;
            Drawable banner = art == null ? AppAdapter.bannerFor(activity, e.pkg) : null;
            Drawable icon = null;
            if (art == null && banner == null) {
                try {
                    icon = pm.getApplicationIcon(e.pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            int color = art != null ? art.color : apps.ambientColorAt(apps.positionOfPackage(e.pkg));
            items.add(new LastPlayedAdapter.Item(e, LastPlayed.labelFor(e, appLabels.get(i)), appLabels.get(i),
                    art != null ? art.bitmap : null, banner, icon, color));
        }
        artCache.keySet().retainAll(keep);
        apply(items);
    }

    private void apply(List<LastPlayedAdapter.Item> items) {
        if (row.hasFocus()) {
            View focused = row.getFocusedChild();
            int pos = focused != null ? row.getChildAdapterPosition(focused) : RecyclerView.NO_POSITION;
            LastPlayedAdapter.Item was = adapter.itemAt(pos);
            if (items.isEmpty() || !fits) {
                // Before the tiles go: Android would hand focus to the first top-bar button.
                host.focusDock();
            } else {
                // The same app's tile, wherever the new list put it.
                pendingFocusKey = was != null ? was.entry.pkg : null;
                if (pendingFocusKey == null) pendingFocusKey = items.get(0).entry.pkg;
            }
        }
        adapter.setItems(items);
        update();
    }

    private File artFile(String pkg) {
        return new File(artDir, pkg + ".png");   // pkg is a checked package name (LastPlayed.decode)
    }

    /** The saved picture, decoded once per version of the file. */
    private Art art(String pkg) {
        File f = artFile(pkg);
        long modified = f.lastModified(), length = f.length();
        Art cached = artCache.get(pkg);
        if (cached != null && cached.modified == modified && cached.length == length) return cached.bitmap != null ? cached : null;
        Bitmap bmp = decode(f);
        int color = bmp != null ? AppAdapter.vividColor(new BitmapDrawable(activity.getResources(), bmp)) : 0;
        Art art = new Art(modified, length, bmp, color);
        artCache.put(pkg, art);
        return bmp != null ? art : null;
    }

    private Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int w = o.outWidth, h = o.outHeight;
            // PlaybackListener writes at most 360x203; anything much bigger is not one of ours.
            if (w <= 0 || h <= 0 || (long) w * h > 4L * LastPlayed.ART_W * LastPlayed.ART_H) return null;
            o.inJustDecodeBounds = false;
            int tileW = Math.max(1, Math.round(ContinueLayout.MIN_TILE_W_DP * density));
            o.inSampleSize = WallpaperSize.sampleSize(w, h, tileW, AppAdapter.tileHeightPx(tileW));
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- showing and hiding ----------------------------------------------------------------------

    /** A dock tile (true) or a grid tile (false) took focus. The row goes once a grid tile has it. */
    void onAppTileFocused(boolean dock) {
        returnToRow = false;
        // Focus was put there on purpose (Home's dock tile, Down from the row): the row must not take it back.
        pendingFocusKey = null;
        zone = dock;
        update();
    }

    /**
     * Home is about to restore focus after coming back: start from transparent like the top shelf, and
     * let the restored focus fade the row in again. A row tile that has focus keeps it meanwhile.
     */
    void hideForRestore() {
        row.animate().cancel();
        label.animate().cancel();
        row.setAlpha(0f);
        label.setAlpha(0f);
        if (!row.hasFocus()) {
            row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            row.setVisibility(View.INVISIBLE);
            label.setVisibility(View.INVISIBLE);
            shown = false;
        } else {
            refade = true;
        }
    }

    private void update() {
        boolean want = zone && laidOut && fits && adapter.getItemCount() > 0;
        if (want == shown && !refade) return;
        if (!want && row.hasFocus()) {
            host.focusDock();   // can come back here through onAppTileFocused
            if (want == shown && !refade) return;
        }
        refade = false;
        boolean changedState = want != shown;
        shown = want;
        fade(want);
        if (changedState) host.onRowShownChanged();
    }

    private void fade(boolean in) {
        row.animate().cancel();
        label.animate().cancel();
        if (fadeWaiting) {
            fadeWaiting = false;
            grid.removeOnScrollListener(gridSettled);
        }
        if (in) {
            // Focusable at once, so Up from the dock lands here even before it has faded in.
            row.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            row.setVisibility(View.VISIBLE);
            label.setVisibility(View.VISIBLE);
            if (animations && grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                fadeWaiting = true;
                grid.addOnScrollListener(gridSettled);
            } else {
                fadeIn();
            }
            return;
        }
        // Not focusable from now on, even while it fades.
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (!animations) {
            row.setAlpha(0f);
            label.setAlpha(0f);
            row.setVisibility(View.INVISIBLE);
            label.setVisibility(View.INVISIBLE);
            return;
        }
        long ms = reducedMotion ? 100 : 160;
        row.animate().alpha(0f).setDuration(ms).withEndAction(() -> row.setVisibility(View.INVISIBLE)).start();
        label.animate().alpha(0f).setDuration(ms).withEndAction(() -> label.setVisibility(View.INVISIBLE)).start();
    }

    private void fadeIn() {
        if (!animations) {
            row.setAlpha(1f);
            label.setAlpha(1f);
            return;
        }
        long ms = reducedMotion ? 100 : 260;
        row.animate().alpha(1f).setDuration(ms).start();
        label.animate().alpha(1f).setDuration(ms).start();
    }

    // ---- focus -----------------------------------------------------------------------------------

    /** Down from the top bar: the tile last focused in the row, else its first. False when the row is not shown. */
    boolean focusRow() {
        if (!shown) return false;
        int i = adapter.indexOf(lastKey);
        focusAt(i >= 0 ? i : 0);
        return true;
    }

    /** Up from a dock tile: the row tile nearest above it. False when the row is not shown. */
    boolean focusNearest(View dockCard) {
        if (!shown || row.getChildCount() == 0) return false;
        ViewGroup root = (ViewGroup) row.getParent();
        float x = centerX(root, dockCard);
        View best = null;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < row.getChildCount(); i++) {
            View card = row.getChildAt(i).findViewById(R.id.card);
            if (card == null) continue;
            float d = Math.abs(centerX(root, card) - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = card;
            }
        }
        if (best == null) return false;
        best.requestFocus();
        return true;
    }

    private static float centerX(ViewGroup root, View v) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        root.offsetDescendantRectToMyCoords(v, r);
        return r.exactCenterX();
    }

    /**
     * Back from an app opened from the row: focus its tile again (the list has changed since, so by app,
     * not by place). False when focus should go to the grid as before.
     */
    boolean restoreFocus() {
        if (!returnToRow || adapter.getItemCount() == 0) return false;
        if (!laidOut) {
            pendingRestore = true;
            return true;
        }
        return restoreNow();
    }

    private boolean restoreNow() {
        if (!fits || adapter.getItemCount() == 0) return false;
        zone = true;
        update();
        int i = adapter.indexOf(lastKey);
        focusAt(i >= 0 ? i : 0);
        return true;
    }

    private void focusAt(int index) {
        RecyclerView.ViewHolder vh = row.findViewHolderForAdapterPosition(index);
        LastPlayedAdapter.Item item = adapter.itemAt(index);
        if (vh == null || item == null) {
            // Not laid out with this list yet: once it is.
            pendingFocusKey = item != null ? item.entry.pkg : null;
            return;
        }
        View card = vh.itemView.findViewById(R.id.card);
        // Already focused: no focus change fires, so put the glass and the shelf back by hand.
        if (card.isFocused()) onTileFocused(index, card);
        else card.requestFocus();
    }

    private void focusPending() {
        String key = pendingFocusKey;
        pendingFocusKey = null;
        if (key == null || !shown) return;
        int i = adapter.indexOf(key);
        if (i >= 0) focusAt(i);
    }

    // ---- row tiles -------------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        LastPlayedAdapter.Item item = adapter.itemAt(position);
        if (item == null) return;
        returnToRow = true;
        lastKey = item.entry.pkg;
        zone = true;
        if (fadeWaiting) {
            // Focus got here before the grid settled: show the row now.
            fadeWaiting = false;
            grid.removeOnScrollListener(gridSettled);
            fadeIn();
        }
        host.onRowTileFocused(card, item.label, item.color);
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Swallow the key at the row's ends; in between the default focus search moves along the row.
            int count = adapter.getItemCount();
            return GridNavigation.atHorizontalEdge(position, count, count, rtl, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            host.focusDock();
            row.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            RecyclerView.ViewHolder vh = row.findViewHolderForAdapterPosition(position);
            host.focusTopBar(vh != null ? vh.itemView.findViewById(R.id.card) : null);
            row.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
            return true;
        }
        return false;
    }
}
