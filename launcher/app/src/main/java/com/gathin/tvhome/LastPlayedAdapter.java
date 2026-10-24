package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

/**
 * The Continue watching tiles: app tiles (res/layout/item_app.xml, the same Holder and focus motion)
 * showing the artwork the app last played, else its banner, else its icon and name, with a thin
 * progress bar. OK resumes in that app: its own "open what is playing" intent while this process still
 * holds it, otherwise the app's TV launch intent.
 */
final class LastPlayedAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    static final class Item {
        final LastPlayed.Entry entry;
        /** The title, or the app's name when the app gave none. */
        final String label;
        final String appLabel;
        /** The saved artwork, or null: then the banner, or with no banner the icon. */
        final Bitmap art;
        final Drawable banner;
        final Drawable icon;
        /** Glow colour for the top shelf: from the artwork, or the app's banner colour until there is some. */
        final int color;

        Item(LastPlayed.Entry entry, String label, String appLabel, Bitmap art, Drawable banner, Drawable icon, int color) {
            this.entry = entry;
            this.label = label;
            this.appLabel = appLabel;
            this.art = art;
            this.banner = banner;
            this.icon = icon;
            this.color = color;
        }
    }

    private final Activity activity;
    private final AppAdapter.TileListener listener;
    private final boolean animations, advancedEffects, reducedMotion;
    private final float focusScale;
    private final float density;
    private List<Item> items = Collections.emptyList();
    private int tileW, tileH, cellW;

    LastPlayedAdapter(Activity activity, AppAdapter.TileListener listener, Config cfg, float focusScale) {
        this.activity = activity;
        this.listener = listener;
        this.animations = cfg.animations;
        this.advancedEffects = cfg.animations && cfg.advancedEffects;
        this.reducedMotion = cfg.reducedMotion;
        this.focusScale = focusScale;
        this.density = activity.getResources().getDisplayMetrics().density;
        tileW = Math.round(AppAdapter.DESIGN_W_DP * density);
        tileH = AppAdapter.tileHeightPx(tileW);
        cellW = tileW;
        // A tile keeps its view while the list reorders, so the focused one stays on its app.
        setHasStableIds(true);
    }

    /** New tile and slot sizes; true when they changed (the caller rebinds). */
    boolean setSizes(int tileWidth, int tileHeight, int cellWidth) {
        if (tileWidth == tileW && tileHeight == tileH && cellWidth == cellW) return false;
        tileW = tileWidth;
        tileH = tileHeight;
        cellW = cellWidth;
        return true;
    }

    boolean hasSizes(int tileWidth, int tileHeight, int cellWidth) {
        return tileWidth == tileW && tileHeight == tileH && cellWidth == cellW;
    }

    @SuppressLint("NotifyDataSetChanged")   // four tiles, and any of them can change
    void setItems(List<Item> fresh) {
        items = fresh;
        notifyDataSetChanged();
    }

    Item itemAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    int indexOf(String pkg) {
        if (pkg == null) return -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).entry.pkg.equals(pkg)) return i;
        return -1;
    }

    @NonNull
    @Override
    public AppAdapter.Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        AppAdapter.Holder h = new AppAdapter.Holder(v, listener, animations, advancedEffects, reducedMotion);
        // No name under these tiles: the dock band is right below, and the top shelf shows the title.
        h.name.setVisibility(View.GONE);
        // Its own drawable state, so the playing tint does not reach the other tiles.
        h.progress.getBackground().mutate();
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull AppAdapter.Holder h, int position) {
        Item it = items.get(position);
        size(h);
        h.card.setContentDescription(it.label);
        // A rebind can hit the focused tile (the list changed under it): keep it zoomed.
        boolean focused = h.card.isFocused();
        h.card.setScaleX(focused && animations ? focusScale : 1f);
        h.card.setScaleY(focused && animations ? focusScale : 1f);
        h.card.setTranslationY(focused && advancedEffects ? -4f * density : 0f);
        h.card.setTranslationZ(focused && advancedEffects ? 8f * density : 0f);
        if (it.art != null || it.banner != null) {
            if (it.art != null) h.banner.setImageBitmap(it.art);
            else h.banner.setImageDrawable(it.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(it.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(it.appLabel);
            h.label.setVisibility(View.VISIBLE);
        }
        float p = LastPlayed.progress(it.entry);
        if (p > 0f) {
            Drawable bar = h.progress.getBackground();
            bar.setLevel(Math.round(p * 10000));
            // While the app is still playing (music behind Home), the bar takes the artwork's colour.
            Drawable fill = bar instanceof LayerDrawable ? ((LayerDrawable) bar).findDrawableByLayerId(android.R.id.progress) : null;
            if (fill != null) fill.setTint(it.entry.playing ? 0xFF000000 | it.color : 0xF2FFFFFF);
            h.progress.setVisibility(View.VISIBLE);
        } else {
            h.progress.setVisibility(View.GONE);
        }
        h.card.setOnClickListener(v -> open(it));
    }

    private void size(AppAdapter.Holder h) {
        // item_app is as wide as its parent: in a horizontal row that would be one tile per screen.
        ViewGroup.LayoutParams item = h.itemView.getLayoutParams();
        if (item != null && item.width != cellW) {
            item.width = cellW;
            h.itemView.setLayoutParams(item);
        }
        ViewGroup.LayoutParams card = h.card.getLayoutParams();
        if (card.width != tileW || card.height != tileH) {
            card.width = tileW;
            card.height = tileH;
            h.card.setLayoutParams(card);
        }
    }

    /** Back into the app: where it was playing when this process still holds its session's intent. */
    private void open(Item it) {
        String pkg = it.entry.pkg;
        PendingIntent resume = PlaybackListener.sessionActivity(pkg);
        if (resume != null) {
            try {
                Bundle options = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14 starts another app's intent from the visible Home only when Home says so.
                    options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
                }
                resume.send(activity, 0, null, null, null, null, options);
                return;
            } catch (PendingIntent.CanceledException e) {
                // The app's process went away with it: fall back to launching the app.
                PlaybackListener.forgetSessionActivity(pkg, resume);
            }
        }
        PackageManager pm = activity.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) {
            try {
                // No RESET_TASK_IF_NEEDED: a running app comes back to the screen it was on.
                activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                if (advancedEffects) activity.overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(activity, activity.getString(R.string.cant_open, it.appLabel), Toast.LENGTH_SHORT).show();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).entry.pkg.hashCode();
    }
}
