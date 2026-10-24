package com.gathin.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LastPlayedTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static LastPlayed.Entry entry(String pkg, String title, long at) {
        return new LastPlayed.Entry(pkg, title, "", at, 0, 0, false, false);
    }

    private static List<String> packages(List<LastPlayed.Entry> list) {
        List<String> out = new ArrayList<>();
        for (LastPlayed.Entry e : list) out.add(e.pkg);
        return out;
    }

    // ---- merge ----------------------------------------------------------------------------------

    @Test public void newestFirstOnePerApp() {
        List<LastPlayed.Entry> old = Arrays.asList(entry("com.netflix.ninja", "A", 100), entry("in.startv.hotstar", "B", 200));
        List<LastPlayed.Entry> out = LastPlayed.merge(old, Collections.singletonList(entry("com.netflix.ninja", "C", 300)), 4);
        assertEquals(Arrays.asList("com.netflix.ninja", "in.startv.hotstar"), packages(out));
        assertEquals("C", out.get(0).title);
    }

    @Test public void capKeepsTheNewest() {
        List<LastPlayed.Entry> old = new ArrayList<>();
        for (int i = 0; i < 4; i++) old.add(entry("com.example.app" + i, "", 100 + i));
        List<LastPlayed.Entry> out = LastPlayed.merge(old, Collections.singletonList(entry("com.example.fresh", "", 500)), 4);
        assertEquals(Arrays.asList("com.example.fresh", "com.example.app3", "com.example.app2", "com.example.app1"), packages(out));
    }

    @Test public void freshEntryReplacesItsAppEvenWhenTheClockWentBack() {
        List<LastPlayed.Entry> old = Arrays.asList(entry("com.example.a", "old", 9_000), entry("com.example.b", "", 5_000));
        List<LastPlayed.Entry> out = LastPlayed.merge(old, Collections.singletonList(entry("com.example.a", "new", 1_000)), 4);
        assertEquals(Arrays.asList("com.example.b", "com.example.a"), packages(out));
        assertEquals("new", out.get(1).title);
    }

    @Test public void stoppingKeepsTheOrder() {
        LastPlayed.Entry a = new LastPlayed.Entry("com.example.a", "x", "", 300, 0, 0, true, false);
        List<LastPlayed.Entry> old = Arrays.asList(a, entry("com.example.b", "", 200));
        List<LastPlayed.Entry> out = LastPlayed.merge(old, Collections.singletonList(a.withPlaying(false)), 4);
        assertEquals(Arrays.asList("com.example.a", "com.example.b"), packages(out));
        assertFalse(out.get(0).playing);
    }

    @Test public void smallPositionMovesAreNotWorthAWrite() {
        LastPlayed.Entry a = new LastPlayed.Entry("com.example.a", "x", "", 1, 60_000, 600_000, true, true);
        assertFalse(LastPlayed.changed(a, new LastPlayed.Entry("com.example.a", "x", "", 2, 62_000, 600_000, true, true)));
        assertTrue(LastPlayed.changed(a, new LastPlayed.Entry("com.example.a", "x", "", 2, 66_000, 600_000, true, true)));
        assertTrue(LastPlayed.changed(a, a.withPlaying(false)));
        assertTrue(LastPlayed.changed(null, a));
    }

    @Test public void aNewPictureAloneKeepsThePlaceInTheList() {
        LastPlayed.Entry paused = new LastPlayed.Entry("com.example.a", "x", "", 100, 60_000, 600_000, false, true);
        LastPlayed.Entry again = new LastPlayed.Entry("com.example.a", "x", "", 900, 60_000, 600_000, false, true);
        assertNull(LastPlayed.toStore(paused, again, false));
        LastPlayed.Entry stored = LastPlayed.toStore(paused, again, true);
        assertEquals(100, stored.updatedAt);
        List<LastPlayed.Entry> list = Arrays.asList(entry("com.example.b", "", 500), paused);
        assertEquals(Arrays.asList("com.example.b", "com.example.a"),
                packages(LastPlayed.merge(list, Collections.singletonList(stored), 4)));
        // Something the tile shows changed: then it is the newest.
        LastPlayed.Entry playing = again.withPlaying(true);
        assertEquals(900, LastPlayed.toStore(paused, playing, true).updatedAt);
        assertEquals(900, LastPlayed.toStore(null, again, false).updatedAt);
    }

    @Test public void pictureCarriesOverOnlyForTheSameTitle() {
        LastPlayed.Entry before = new LastPlayed.Entry("com.example.a", "Show", "", 1, 0, 0, true, true);
        assertTrue(LastPlayed.carryArt(new LastPlayed.Entry("com.example.a", "Show", "", 2, 5, 0, false, false), before).hasArt);
        assertFalse(LastPlayed.carryArt(new LastPlayed.Entry("com.example.a", "Other", "", 2, 5, 0, false, false), before).hasArt);
        assertFalse(LastPlayed.carryArt(entry("com.example.a", "Show", 2), null).hasArt);
    }

    // ---- file format ----------------------------------------------------------------------------

    @Test public void roundTripKeepsTabsNewlinesBackslashesAndScripts() {
        List<LastPlayed.Entry> list = Arrays.asList(
                new LastPlayed.Entry("com.example.a", "Tab\there\nnew line\\back\\\\slash\r", "a\\tb", 1_700_000_000_000L,
                        123_456, 3_600_000, true, true),
                new LastPlayed.Entry("in.startv.hotstar", "\u0915\u094D\u200D\u0937 \u0938\u093F\u0928\u0947\u092E\u093E",
                        "\u05D4\u05DE\u05E9\u05DA \u05E6\u05E4\u05D9\u05D9\u05D4", 1_600_000_000_000L, 0, 0, false, false));
        String text = LastPlayed.encode(list);
        assertEquals(LastPlayed.HEADER, text.split("\n")[0]);
        assertEquals("header plus one line per entry", 3, text.split("\n").length);
        assertEquals(list, LastPlayed.decode(text));
    }

    @Test public void badLinesAreSkippedAndAWrongHeaderGivesNothing() {
        String good = "com.example.a\t5\t1\t2\t0\t1\tTitle\t";
        String text = LastPlayed.HEADER + "\n"
                + good + "\n"
                + "../../etc\t5\t1\t2\t0\t1\tx\t\n"          // not a package name: it also names a file
                + "com.example.b\t-5\t1\t2\t0\t1\tx\t\n"     // negative time
                + "com.example.c\t5\t1\t2\t2\t1\tx\t\n"      // not a flag
                + "com.example.d\t5\t1\t2\t0\t1\tbad \\q\t\n" // an escape the writer never writes
                + "com.example.e\t5\t1\t2\t0\t1\tx\n";       // a field short
        List<LastPlayed.Entry> out = LastPlayed.decode(text);
        assertEquals(Collections.singletonList("com.example.a"), packages(out));
        assertTrue(out.get(0).hasArt);
        assertTrue(LastPlayed.decode("something else\n" + good + "\n").isEmpty());
        assertTrue(LastPlayed.decode("").isEmpty());
        assertTrue(LastPlayed.decode(null).isEmpty());
    }

    @Test public void decodeCapsAndKeepsOnePerApp() {
        StringBuilder b = new StringBuilder(LastPlayed.HEADER).append('\n');
        for (int i = 0; i < 6; i++) b.append("com.example.app").append(i).append('\t').append(100 + i).append("\t0\t0\t0\t0\t\t\n");
        b.append("com.example.app5\t1\t0\t0\t0\t0\tolder copy\t\n");
        List<LastPlayed.Entry> out = LastPlayed.decode(b.toString());
        assertEquals(Arrays.asList("com.example.app5", "com.example.app4", "com.example.app3", "com.example.app2"), packages(out));
        assertEquals("", out.get(0).title);
    }

    @Test public void writeThenReadAndNoLeftoverTemporaryFile() throws Exception {
        File file = new File(tmp.getRoot(), LastPlayed.FILE_NAME);
        List<LastPlayed.Entry> list = Collections.singletonList(
                new LastPlayed.Entry("com.netflix.ninja", "", "", 42, 1000, 0, true, false));
        assertTrue(LastPlayed.write(file, list));
        assertEquals(list, LastPlayed.read(file));
        assertFalse(new File(file.getPath() + ".tmp").exists());
        // Rewritten in place: the reader gets the new list.
        assertTrue(LastPlayed.write(file, Collections.emptyList()));
        assertTrue(LastPlayed.read(file).isEmpty());
    }

    @Test public void unreadableOrMissingFileGivesAnEmptyList() throws Exception {
        assertTrue(LastPlayed.read(new File(tmp.getRoot(), "missing.tsv")).isEmpty());
        File junk = tmp.newFile("junk.tsv");
        try (FileOutputStream out = new FileOutputStream(junk)) {
            out.write("\u0000\u0001 not a list".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(LastPlayed.read(junk).isEmpty());
    }

    @Test public void failedBodyLeavesTheOldFile() throws Exception {
        File file = new File(tmp.getRoot(), "art.png");
        assertTrue(LastPlayed.writeAtomically(file, out -> out.write(1)));
        assertFalse(LastPlayed.writeAtomically(file, out -> {
            throw new java.io.IOException("compress failed");
        }));
        assertEquals(1, file.length());
        assertFalse(new File(file.getPath() + ".tmp").exists());
    }

    // ---- labels, text, progress -----------------------------------------------------------------

    @Test public void labelFallsBackToTheAppName() {
        assertEquals("Stranger Things", LastPlayed.labelFor(entry("com.netflix.ninja", "Stranger Things", 1), "Netflix"));
        assertEquals("Netflix", LastPlayed.labelFor(entry("com.netflix.ninja", "", 1), "Netflix"));
        assertEquals("Netflix", LastPlayed.labelFor(entry("com.netflix.ninja", "   ", 1), "Netflix"));
        assertEquals("com.netflix.ninja", LastPlayed.labelFor(entry("com.netflix.ninja", "", 1), null));
    }

    @Test public void subtitlePrefersTheDisplaySubtitleThenArtistThenAlbum() {
        assertEquals("S1 E2", LastPlayed.subtitleFor("S1 E2", "Artist", "Album"));
        assertEquals("Artist", LastPlayed.subtitleFor(" ", "Artist", "Album"));
        assertEquals("Album", LastPlayed.subtitleFor(null, "", "Album"));
        assertEquals("", LastPlayed.subtitleFor(null, null, null));
    }

    @Test public void cleanMakesOnePlainLineAndKeepsJoiners() {
        assertEquals("Line one line two", LastPlayed.clean("  Line one\n\tline\u2028two  "));
        assertEquals("evil txt", LastPlayed.clean("evil\u202Etxt"));
        assertEquals("a b", LastPlayed.clean("a\u2066\u2069b"));
        String devanagari = "\u0915\u094D\u200D\u0937";   // a conjunct that needs the zero width joiner
        assertEquals(devanagari, LastPlayed.clean(devanagari));
        assertEquals("\u05D4\u05DE\u05E9\u05DA", LastPlayed.clean("\u05D4\u05DE\u05E9\u05DA"));
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 200; i++) longTitle.append("\uD83C\uDFAC");   // emoji, a surrogate pair each
        String cut = LastPlayed.clean(longTitle);
        assertTrue(cut.length() <= LastPlayed.TEXT_MAX);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
        assertEquals("", LastPlayed.clean(null));
    }

    @Test public void progressIsAFractionOrUnknown() {
        assertEquals(0.25f, LastPlayed.progress(new LastPlayed.Entry("com.example.a", "", "", 1, 250, 1000, false, false)), 1e-6);
        assertEquals(1f, LastPlayed.progress(new LastPlayed.Entry("com.example.a", "", "", 1, 2000, 1000, false, false)), 1e-6);
        assertEquals(-1f, LastPlayed.progress(new LastPlayed.Entry("com.example.a", "", "", 1, 250, 0, false, false)), 1e-6);
    }

    @Test public void playingPositionMovesOnFromItsLastUpdate() {
        assertEquals(15_000, LastPlayed.positionAt(10_000, 1_000, 6_000, 1f, true, 0));
        assertEquals(10_000, LastPlayed.positionAt(10_000, 1_000, 6_000, 1f, false, 0));
        assertEquals(12_000, LastPlayed.positionAt(10_000, 1_000, 6_000, 1f, true, 12_000));
        assertEquals(0, LastPlayed.positionAt(-1, 1_000, 6_000, 1f, true, 0));
    }

    @Test public void artIsCutToTheTileShapeAndNeverScaledUp() {
        // Square album art: keep the middle band, scale it to the box.
        assertArrayEquals(new int[]{0, 218, 1000, 563, 360, 203}, LastPlayed.artCrop(1000, 1000, 360, 203));
        // A 16:9 still: barely trimmed.
        assertArrayEquals(new int[]{2, 0, 1915, 1080, 360, 203}, LastPlayed.artCrop(1920, 1080, 360, 203));
        // Smaller than the box: cut, not enlarged.
        assertArrayEquals(new int[]{6, 0, 88, 50, 88, 50}, LastPlayed.artCrop(100, 50, 360, 203));
        assertNull(LastPlayed.artCrop(0, 10, 360, 203));
    }
}
