package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers what each app last played, for the "Continue watching" row (config.xml, last_played).
 * A notification listener is the only kind of app that may follow other apps' media sessions; this one
 * never looks at a notification. When a session plays or pauses it records the app, the title and
 * position the app reports, and the artwork bitmap the app hands to the system. Artwork URIs are
 * ignored: nothing here goes over the network. The list goes to files/last-played.tsv and the pictures
 * to files/last-played/, written on one background thread at most every two seconds.
 *
 * Granted once over adb (build-and-install.sh does it, and it survives reinstalls):
 *   cmd notification allow_listener com.gathin.tvhome/.PlaybackListener
 * Undo with disallow_listener, or "last_played": false in a theme.
 */
public class PlaybackListener extends NotificationListenerService {
    private static final String TAG = "TvHome";
    private static final long WRITE_DELAY_MS = 2000;

    /** Each app's "open what is playing" intent from its session; it works while this process lives. */
    private static final Map<String, PendingIntent> SESSION_ACTIVITIES = new ConcurrentHashMap<>();
    /** Set by a visible home screen; run on the main thread after each write. */
    private static volatile Runnable changeListener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private HandlerThread thread;
    private Handler worker;

    // Everything below belongs to the worker thread.
    private MediaSessionManager sessions;
    private boolean listening;
    private final Map<MediaSession.Token, Watch> watched = new HashMap<>();
    private List<LastPlayed.Entry> entries = new ArrayList<>();
    /** Scaled pictures waiting for the next write, by package. */
    private final Map<String, Bitmap> newArt = new HashMap<>();
    /**
     * The last picture made for each app. Every getMetadata() is a new copy over binder, so the same
     * artwork sent again is told by its pixels, not by the object.
     */
    private final Map<String, Bitmap> lastArt = new HashMap<>();
    private final Map<String, Boolean> launchable = new HashMap<>();
    private Set<String> hidden = Collections.emptySet();
    private boolean writePending;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged =
            controllers -> guarded(() -> onSessions(controllers));
    private final Runnable write = () -> guarded(this::write);

    static PendingIntent sessionActivity(String pkg) {
        return pkg == null ? null : SESSION_ACTIVITIES.get(pkg);
    }

    static void forgetSessionActivity(String pkg, PendingIntent stale) {
        SESSION_ACTIVITIES.remove(pkg, stale);
    }

    /** The home screen listens while it is resumed; null stops it. */
    static void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        if (thread == null) {
            thread = new HandlerThread("last-played");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        worker.post(() -> guarded(this::connect));
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (worker != null) worker.post(() -> guarded(this::disconnect));
    }

    @Override
    public void onDestroy() {
        if (thread != null) {
            worker.post(() -> guarded(this::disconnect));
            thread.quitSafely();   // runs the disconnect (and its last write) first
            thread = null;
            // worker stays: that disconnect still uses it on the worker thread.
        }
        super.onDestroy();
    }

    // Notifications themselves are of no interest.
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
    }

    /** A listener that throws would take Home down with it: log and carry on instead. */
    private static void guarded(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "last played: " + e);
        }
    }

    private void connect() {
        if (listening) return;
        Config cfg = Config.load(this);
        if (!cfg.lastPlayed) return;   // off in config.xml or the theme: record nothing
        hidden = new HashSet<>(cfg.hiddenApps);
        launchable.clear();
        entries = LastPlayed.read(listFile());
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, PlaybackListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, me, worker);
            listening = true;
            onSessions(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            // Only an allowed listener may follow sessions; nothing to retry until the system connects again.
            Log.w(TAG, "last played: no access to media sessions, allow it with cmd notification allow_listener "
                    + me.flattenToShortString());
        }
    }

    private void disconnect() {
        if (sessions != null && listening) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        listening = false;
        for (Watch w : watched.values()) w.controller.unregisterCallback(w);
        watched.clear();
        if (writePending) {
            worker.removeCallbacks(write);
            write();
        }
        lastArt.clear();   // nothing to compare with until the next connect
    }

    /** Follows the sessions that are active now; an app whose session went is no longer playing. */
    private void onSessions(List<MediaController> controllers) {
        if (!listening) return;
        Set<MediaSession.Token> live = new HashSet<>();
        Set<String> livePackages = new HashSet<>();
        if (controllers != null) {
            for (MediaController c : controllers) {
                String pkg = c.getPackageName();
                if (!tracks(pkg)) continue;
                MediaSession.Token token = c.getSessionToken();
                live.add(token);
                livePackages.add(pkg);
                if (!watched.containsKey(token)) {
                    Watch w = new Watch(c);
                    c.registerCallback(w, worker);
                    watched.put(token, w);
                }
                record(c);
            }
        }
        for (Iterator<Map.Entry<MediaSession.Token, Watch>> it = watched.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<MediaSession.Token, Watch> w = it.next();
            if (live.contains(w.getKey())) continue;
            w.getValue().controller.unregisterCallback(w.getValue());
            it.remove();
        }
        // Also after this process restarted: a "playing" entry from before with no session now.
        for (LastPlayed.Entry e : new ArrayList<>(entries)) if (e.playing && !livePackages.contains(e.pkg)) stopped(e.pkg);
    }

    /**
     * Only apps that can be a tile: the TV's own sessions (Bluetooth audio, casting, the tuner) would
     * otherwise take one of the four places for nothing. hidden_apps are never recorded.
     */
    private boolean tracks(String pkg) {
        if (pkg == null || pkg.equals(getPackageName()) || hidden.contains(pkg) || !Cards.isPackageName(pkg)) return false;
        Boolean ok = launchable.get(pkg);
        if (ok == null) {
            ok = getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null;
            launchable.put(pkg, ok);
        }
        return ok;
    }

    private void record(MediaController c) {
        if (!listening) return;
        String pkg = c.getPackageName();
        PendingIntent activity = c.getSessionActivity();
        if (activity != null) SESSION_ACTIVITIES.put(pkg, activity);
        PlaybackState state = c.getPlaybackState();
        int s = state != null ? state.getState() : PlaybackState.STATE_NONE;
        MediaMetadata md = c.getMetadata();
        boolean playing = s == PlaybackState.STATE_PLAYING;
        if (s == PlaybackState.STATE_NONE || s == PlaybackState.STATE_STOPPED || s == PlaybackState.STATE_ERROR
                || (s == PlaybackState.STATE_PAUSED && md == null)) {
            stopped(pkg);
            return;
        }
        // Buffering, seeking and the like: the next playing or paused state says where it is.
        if (!playing && s != PlaybackState.STATE_PAUSED) return;

        LastPlayed.Entry previous = find(pkg);
        // Playing with no metadata at all (some apps): still worth a tile with the app's own name.
        String title = md == null ? "" : LastPlayed.clean(firstText(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_TITLE));
        String subtitle = md == null ? "" : LastPlayed.subtitleFor(md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                md.getText(MediaMetadata.METADATA_KEY_ARTIST), md.getText(MediaMetadata.METADATA_KEY_ALBUM));
        long duration = md == null ? 0 : Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        long position = LastPlayed.positionAt(state.getPosition(), state.getLastPositionUpdateTime(),
                SystemClock.elapsedRealtime(), state.getPlaybackSpeed(), playing, duration);

        Bitmap art = md == null ? null : artOf(md);
        boolean newPicture = false;
        boolean hasArt = false;
        if (art != null) {
            Bitmap scaled = scale(art);
            if (scaled != null) {
                hasArt = true;
                Bitmap before = lastArt.get(pkg);
                // A new file only for other pixels, or when the list has no picture for the app yet.
                if (before == null || previous == null || !previous.hasArt || !scaled.sameAs(before)) {
                    newArt.put(pkg, scaled);
                    lastArt.put(pkg, scaled);
                    newPicture = true;
                }
            }
        }
        LastPlayed.Entry fresh = LastPlayed.carryArt(new LastPlayed.Entry(pkg, title, subtitle,
                System.currentTimeMillis(), position, duration, playing, hasArt), previous);
        if (!fresh.hasArt) {
            newArt.remove(pkg);
            lastArt.remove(pkg);
        }
        fresh = LastPlayed.toStore(previous, fresh, newPicture);
        if (fresh == null) return;
        if (previous == null || previous.playing != fresh.playing || !previous.title.equals(fresh.title)) {
            // What it is, not the title itself: logcat is no place for what the family watches.
            Log.i(TAG, "last played " + pkg + ": " + (playing ? "playing" : "paused")
                    + ", title " + (title.isEmpty() ? "none" : title.length() + " characters")
                    + ", art " + (fresh.hasArt ? "yes" : "no") + ", " + time(position) + " of " + time(duration));
        }
        entries = LastPlayed.merge(entries, Collections.singletonList(fresh), LastPlayed.MAX);
        scheduleWrite();
    }

    private void stopped(String pkg) {
        LastPlayed.Entry e = find(pkg);
        if (e == null || !e.playing) return;
        // Same time as before, so the order stays.
        entries = LastPlayed.merge(entries, Collections.singletonList(e.withPlaying(false)), LastPlayed.MAX);
        scheduleWrite();
    }

    private LastPlayed.Entry find(String pkg) {
        for (LastPlayed.Entry e : entries) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    /** At most one write every two seconds, however often a session reports. */
    private void scheduleWrite() {
        if (writePending) return;
        writePending = true;
        worker.postDelayed(write, WRITE_DELAY_MS);
    }

    private void write() {
        writePending = false;
        File dir = new File(getFilesDir(), LastPlayed.ART_DIR);
        // Pictures first, so the list never names one that is not on disk yet.
        for (Map.Entry<String, Bitmap> art : newArt.entrySet()) {
            LastPlayed.Entry e = find(art.getKey());
            if (e == null || !e.hasArt) continue;
            Bitmap bmp = art.getValue();
            boolean saved = (dir.isDirectory() || dir.mkdirs()) && LastPlayed.writeAtomically(
                    new File(dir, e.pkg + ".png"), out -> {
                        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG");
                    });
            if (!saved) {
                entries = LastPlayed.merge(entries, Collections.singletonList(e.withArt(false)), LastPlayed.MAX);
                lastArt.remove(e.pkg);
            }
        }
        newArt.clear();
        // Pictures of apps that left the list or no longer have one (and any half-written file).
        Set<String> keep = new HashSet<>();
        for (LastPlayed.Entry e : entries) if (e.hasArt) keep.add(e.pkg + ".png");
        lastArt.keySet().removeIf(pkg -> !keep.contains(pkg + ".png"));
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                if (!keep.contains(f.getName())) f.delete();
            }
        }
        if (!LastPlayed.write(listFile(), entries)) Log.w(TAG, "last played: cannot write " + LastPlayed.FILE_NAME);
        Runnable r = changeListener;
        if (r != null) MAIN.post(() -> {
            if (changeListener == r) r.run();
        });
    }

    private File listFile() {
        return new File(getFilesDir(), LastPlayed.FILE_NAME);
    }

    private static String firstText(MediaMetadata md, String key, String fallbackKey) {
        CharSequence s = md.getText(key);
        if (s == null || s.toString().trim().isEmpty()) s = md.getText(fallbackKey);
        return s == null ? "" : s.toString();
    }

    /** The artwork as a bitmap the app put in its metadata; a URI would mean a download, which this never does. */
    private static Bitmap artOf(MediaMetadata md) {
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = md.getBitmap(key);
            if (b != null && b.getWidth() > 0 && b.getHeight() > 0) return b;
        }
        return null;
    }

    /** The tile's shape and at most its size: a few hundred KB in memory instead of a full poster. */
    private static Bitmap scale(Bitmap art) {
        try {
            int[] c = LastPlayed.artCrop(art.getWidth(), art.getHeight(), LastPlayed.ART_W, LastPlayed.ART_H);
            if (c == null) return null;
            Matrix m = new Matrix();
            m.setScale(c[4] / (float) c[2], c[5] / (float) c[3]);
            return Bitmap.createBitmap(art, c[0], c[1], c[2], c[3], m, true);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static String time(long ms) {
        if (ms <= 0) return "?";
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private final class Watch extends MediaController.Callback {
        final MediaController controller;

        Watch(MediaController controller) {
            this.controller = controller;
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            guarded(() -> record(controller));
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            guarded(() -> record(controller));
        }

        @Override
        public void onSessionDestroyed() {
            guarded(() -> {
                controller.unregisterCallback(this);
                watched.remove(controller.getSessionToken());
                stopped(controller.getPackageName());
            });
        }
    }
}
