package com.gathin.tvhome;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What each app last played, for the "Continue watching" row (config.xml, last_played): one entry per
 * app, newest first, at most {@link #MAX}. PlaybackListener fills it from the TV's media sessions and
 * MainActivity reads it back. Pure Java, so the rules and the file format are tested on the JVM; org.json
 * is not on that classpath, which is why this has its own line format, like the one in Cards.
 *
 * last-played.tsv is a header line, then one line per entry with eight tab-separated fields: package,
 * updated at (ms since the epoch), position (ms), duration (ms), playing (0/1), has art (0/1), title,
 * subtitle. A backslash, tab, CR or newline inside the texts is written as \\, \t, \r or \n.
 */
final class LastPlayed {
    private LastPlayed() { }

    static final int MAX = 4;
    static final String FILE_NAME = "last-played.tsv";
    /** Folder next to the list with one picture per app, {@code <package>.png}. */
    static final String ART_DIR = "last-played";
    /** Saved pictures are at most this big: a 16:9 tile at 1080p never draws more. */
    static final int ART_W = 360, ART_H = 203;
    static final String HEADER = "cleanyourtv last-played 1";
    /** Longest title or subtitle kept; the top shelf ellipsizes long before this. */
    static final int TEXT_MAX = 120;
    private static final int FILE_MAX = 64 * 1024;
    /** Position changes smaller than this are not worth a write. */
    private static final long POSITION_STEP_MS = 5000;

    static final class Entry {
        final String pkg;
        final String title;
        final String subtitle;
        final long updatedAt;
        final long positionMs;
        final long durationMs;
        final boolean playing;
        final boolean hasArt;

        Entry(String pkg, String title, String subtitle, long updatedAt, long positionMs, long durationMs,
              boolean playing, boolean hasArt) {
            this.pkg = pkg == null ? "" : pkg;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.updatedAt = updatedAt;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.playing = playing;
            this.hasArt = hasArt;
        }

        Entry withPlaying(boolean now) {
            return new Entry(pkg, title, subtitle, updatedAt, positionMs, durationMs, now, hasArt);
        }

        Entry withArt(boolean art) {
            return new Entry(pkg, title, subtitle, updatedAt, positionMs, durationMs, playing, art);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Entry)) return false;
            Entry e = (Entry) o;
            return pkg.equals(e.pkg) && title.equals(e.title) && subtitle.equals(e.subtitle)
                    && updatedAt == e.updatedAt && positionMs == e.positionMs && durationMs == e.durationMs
                    && playing == e.playing && hasArt == e.hasArt;
        }

        @Override
        public int hashCode() {
            return pkg.hashCode() * 31 + title.hashCode();
        }

        @Override
        public String toString() {
            return pkg + " '" + title + "' " + positionMs + "/" + durationMs + (playing ? " playing" : "");
        }
    }

    /**
     * The list after {@code fresh} came in: a fresh entry replaces its app's old one whatever their times
     * (a clock that jumps back must not freeze an app's tile), then newest first, one per app, at most max.
     */
    static List<Entry> merge(List<Entry> existing, List<Entry> fresh, int max) {
        List<Entry> all = new ArrayList<>(fresh);
        Set<String> replaced = new HashSet<>();
        for (Entry e : fresh) replaced.add(e.pkg);
        for (Entry e : existing) if (!replaced.contains(e.pkg)) all.add(e);
        // Stable: on equal times a fresh entry stays ahead of an old one.
        Collections.sort(all, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Entry e : all) {
            if (out.size() >= max) break;
            if (seen.add(e.pkg)) out.add(e);
        }
        return out;
    }

    /** Worth writing down: something the tile shows changed, or the position moved by a few seconds. */
    static boolean changed(Entry old, Entry fresh) {
        if (old == null) return true;
        return !old.title.equals(fresh.title) || !old.subtitle.equals(fresh.subtitle)
                || old.playing != fresh.playing || old.hasArt != fresh.hasArt || old.durationMs != fresh.durationMs
                || Math.abs(old.positionMs - fresh.positionMs) >= POSITION_STEP_MS;
    }

    /**
     * What to store after a session reported: fresh when {@link #changed}, null when nothing did. A new
     * picture alone keeps the app's old time: a paused app that sends its artwork again (or is seen again
     * after a restart) must not jump ahead of what was watched since.
     */
    static Entry toStore(Entry previous, Entry fresh, boolean newPicture) {
        if (changed(previous, fresh)) return fresh;
        if (!newPicture) return null;
        return new Entry(fresh.pkg, fresh.title, fresh.subtitle, previous.updatedAt, fresh.positionMs,
                fresh.durationMs, fresh.playing, fresh.hasArt);
    }

    /** No picture this time but the same title as before: the picture saved for it still applies. */
    static Entry carryArt(Entry fresh, Entry previous) {
        if (fresh.hasArt || previous == null || !previous.hasArt) return fresh;
        if (!previous.pkg.equals(fresh.pkg) || !previous.title.equals(fresh.title)) return fresh;
        return fresh.withArt(true);
    }

    /** What the tile and the top shelf call it: the title, or the app's own name when the app gave none. */
    static String labelFor(Entry e, String appLabel) {
        if (!e.title.trim().isEmpty()) return e.title;
        return appLabel == null || appLabel.isEmpty() ? e.pkg : appLabel;
    }

    /** The display subtitle, else the artist, else the album, else nothing. */
    static String subtitleFor(CharSequence displaySubtitle, CharSequence artist, CharSequence album) {
        for (CharSequence s : new CharSequence[]{displaySubtitle, artist, album}) {
            String c = clean(s);
            if (!c.isEmpty()) return c;
        }
        return "";
    }

    /** How far in, 0 to 1, or -1 when the app reported no duration (live TV, some apps). */
    static float progress(Entry e) {
        if (e.durationMs <= 0 || e.positionMs < 0) return -1f;
        return Math.min(1f, (float) e.positionMs / e.durationMs);
    }

    /**
     * The position now: a playing session reports where it was at its last update (elapsed realtime),
     * so add the time since, at its speed. Never past the end, never negative (unknown is -1).
     */
    static long positionAt(long position, long updatedElapsed, long nowElapsed, float speed, boolean playing,
                           long duration) {
        if (position < 0) return 0;
        long at = position;
        if (playing && updatedElapsed > 0 && nowElapsed > updatedElapsed && speed > 0) {
            at += (long) ((nowElapsed - updatedElapsed) * (double) speed);
        }
        return duration > 0 ? Math.min(at, duration) : at;
    }

    /**
     * Text from another app as one plain line: control characters, line breaks and the bidi embedding,
     * override and isolate characters (which could redraw the line as something else) become spaces,
     * runs of spaces collapse, at most {@link #TEXT_MAX} characters without cutting a surrogate pair.
     * Joiners stay: Devanagari and other Indic scripts need them.
     */
    static String clean(CharSequence s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), TEXT_MAX));
        boolean space = true;   // leading spaces go too
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int type = Character.getType(c);
            boolean blank = Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩');
            if (blank) {
                if (!space) b.append(' ');
                space = true;
                continue;
            }
            boolean pair = Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
            if (b.length() + (pair ? 2 : 1) > TEXT_MAX) break;
            b.append(c);
            if (pair) b.append(s.charAt(++i));
            space = false;
        }
        return b.toString().trim();
    }

    /**
     * How to cut a picture for a tile: the largest centred part with the box's shape, scaled down to the
     * box (never up). {x, y, width, height} of that part in the source, then {width, height} to scale it to;
     * null for an empty picture.
     */
    static int[] artCrop(int srcW, int srcH, int boxW, int boxH) {
        if (srcW <= 0 || srcH <= 0 || boxW <= 0 || boxH <= 0) return null;
        int w = srcW, h = srcH;
        if ((long) srcW * boxH > (long) srcH * boxW) w = (int) Math.max(1, (long) srcH * boxW / boxH);   // wider: trim the sides
        else h = (int) Math.max(1, (long) srcW * boxH / boxW);                                            // taller: trim top and bottom
        int outW = w, outH = h;
        if (w > boxW) {
            outW = boxW;
            outH = boxH;
        }
        return new int[]{(srcW - w) / 2, (srcH - h) / 2, w, h, outW, outH};
    }

    // ---- last-played.tsv ---------------------------------------------------------------------------

    static String encode(List<Entry> entries) {
        StringBuilder b = new StringBuilder(HEADER).append('\n');
        for (Entry e : entries) {
            if (!Cards.isPackageName(e.pkg)) continue;   // the package also names its picture file
            b.append(e.pkg).append('\t').append(Math.max(0, e.updatedAt))
                    .append('\t').append(Math.max(0, e.positionMs)).append('\t').append(Math.max(0, e.durationMs))
                    .append('\t').append(e.playing ? '1' : '0').append('\t').append(e.hasArt ? '1' : '0')
                    .append('\t');
            escape(b, e.title);
            b.append('\t');
            escape(b, e.subtitle);
            b.append('\n');
        }
        return b.toString();
    }

    /** The entries in a list file; a line that does not read back exactly is skipped, a wrong header gives none. */
    static List<Entry> decode(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null || text.length() > FILE_MAX) return out;
        String[] lines = text.split("\n", -1);
        if (!lines[0].equals(HEADER)) return out;
        for (int i = 1; i < lines.length; i++) {
            Entry e = decodeLine(lines[i]);
            if (e != null) out.add(e);
        }
        return merge(Collections.emptyList(), out, MAX);
    }

    private static Entry decodeLine(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 8 || !Cards.isPackageName(f[0])) return null;
        long updatedAt = number(f[1]), position = number(f[2]), duration = number(f[3]);
        if (updatedAt < 0 || position < 0 || duration < 0) return null;
        if (!isFlag(f[4]) || !isFlag(f[5])) return null;
        String title = unescape(f[6]), subtitle = unescape(f[7]);
        if (title == null || subtitle == null) return null;
        return new Entry(f[0], title, subtitle, updatedAt, position, duration, f[4].equals("1"), f[5].equals("1"));
    }

    private static long number(String s) {
        if (s.isEmpty() || s.length() > 18) return -1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return -1;
        return Long.parseLong(s);
    }

    private static boolean isFlag(String s) {
        return s.equals("0") || s.equals("1");
    }

    private static void escape(StringBuilder b, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c == '\t') b.append("\\t");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else b.append(c);
        }
    }

    /** The text {@link #escape} wrote, or null for an escape it never writes. */
    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                if (c == '\r') return null;
                b.append(c);
                continue;
            }
            if (++i == s.length()) return null;
            char n = s.charAt(i);
            if (n == '\\') b.append('\\');
            else if (n == 't') b.append('\t');
            else if (n == 'n') b.append('\n');
            else if (n == 'r') b.append('\r');
            else return null;
        }
        return b.toString();
    }

    /** The saved list, or an empty one when there is none or it cannot be read. */
    static List<Entry> read(File file) {
        if (file == null || !file.isFile() || file.length() > FILE_MAX) return new ArrayList<>();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return decode(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    static boolean write(File file, List<Entry> entries) {
        byte[] bytes = encode(entries).getBytes(StandardCharsets.UTF_8);
        return writeAtomically(file, out -> out.write(bytes));
    }

    interface Body {
        void writeTo(OutputStream out) throws IOException;
    }

    /** Writes next to the file, then renames it over the old one: a reader sees the old file or the new one, never half. */
    static boolean writeAtomically(File file, Body body) {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            body.writeTo(out);
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        if (tmp.renameTo(file)) return true;
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return false;
    }
}
