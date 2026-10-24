package tv.homelights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LearnStepsTest {
    @Test
    public void idsAreUniqueAndSetupIsNeverAskedFor() {
        Set<String> ids = new HashSet<>();
        for (LearnSteps.Step s : LearnSteps.ALL) {
            assertTrue("duplicate id " + s.id, ids.add(s.id));
            assertFalse(s.id, s.button.toUpperCase().contains("SETUP"));
            assertTrue(s.presses >= 1);
            assertFalse(s.quiet);
        }
        for (LearnSteps.Step s : LearnSteps.COVE) {
            assertTrue("duplicate id " + s.id, ids.add(s.id));
            assertTrue(s.id, s.id.startsWith("cove-"));
            assertTrue(s.presses >= 0);
        }
    }

    @Test
    public void everySelectionIsFollowedByTheFourAdjustments() {
        String[] prefixes = {"all", "row1", "row2", "row3"};
        for (String p : prefixes) {
            int at = index("select-" + p);
            assertEquals(p + "-brighter", LearnSteps.ALL.get(at + 1).id);
            assertEquals(p + "-dimmer", LearnSteps.ALL.get(at + 2).id);
            assertEquals(p + "-k-plus", LearnSteps.ALL.get(at + 3).id);
            assertEquals(p + "-k-minus", LearnSteps.ALL.get(at + 4).id);
        }
        assertEquals("all-on", LearnSteps.ALL.get(0).id);
        assertEquals(34, LearnSteps.ALL.size());
        // The extra buttons carry the selected group, so they follow the all-rows adjustments.
        assertEquals("k-button", LearnSteps.ALL.get(index("all-k-minus") + 1).id);
        assertEquals("select-row1", LearnSteps.ALL.get(index("aid") + 1).id);
    }

    @Test
    public void theCoveRemoteStartsQuietAndEndsWithTheSlide() {
        assertEquals(11, LearnSteps.COVE.size());
        LearnSteps.Step quiet = LearnSteps.COVE.get(0);
        assertEquals("cove-quiet", quiet.id);
        assertTrue(quiet.quiet);
        assertEquals("nothing is pressed, so the text is the whole instruction", 0, quiet.presses);
        assertEquals("cove-on", LearnSteps.COVE.get(1).id);
        assertEquals(3, LearnSteps.COVE.get(1).presses);
        assertEquals(Arrays.asList("cove-cool", "cove-neutral", "cove-warm"),
                ids(LearnSteps.COVE.subList(5, 8)));
        LearnSteps.Step slide = LearnSteps.COVE.get(10);
        assertEquals("cove-slide", slide.id);
        assertEquals(0, slide.presses);
        assertFalse("a slide is not the quiet step", slide.quiet);
    }

    @Test
    public void plans() {
        assertEquals(LearnSteps.ALL, LearnSteps.plan(null).steps);
        assertEquals(LearnSteps.ALL, LearnSteps.plan("all").steps);
        assertEquals(LearnSteps.ALL, LearnSteps.plan("lights").steps);
        assertEquals(LearnSteps.ALL, LearnSteps.plan("no-such-step").steps);
        assertFalse(LearnSteps.plan(null).everyPacket);
        assertEquals("capture.json", LearnSteps.plan(null).file);
        assertEquals(Arrays.asList("select-all", "k-button", "moon", "rgb", "aid"), ids(LearnSteps.plan("extras").steps));
        assertEquals(Arrays.asList("all-on", "off-2"), ids(LearnSteps.plan("off-2, all-on").steps));
        assertEquals("the light remote", LearnSteps.plan("extras").remote);

        LearnSteps.Plan cove = LearnSteps.plan("cove");
        assertEquals(LearnSteps.COVE, cove.steps);
        assertTrue("its format is unknown, so every packet is recorded", cove.everyPacket);
        assertEquals("capture-cove.json", cove.file);
        assertEquals("lights/pull-capture.sh --cove", cove.pullCommand);
        assertEquals("the cove remote", cove.remote);
        // A list of its step ids keeps the cove plan's settings.
        LearnSteps.Plan two = LearnSteps.plan("cove-on,cove-quiet");
        assertEquals(Arrays.asList("cove-quiet", "cove-on"), ids(two.steps));
        assertTrue(two.everyPacket);
        assertEquals("capture-cove.json", two.file);
    }

    static List<String> ids(List<LearnSteps.Step> steps) {
        List<String> out = new ArrayList<>();
        for (LearnSteps.Step s : steps) out.add(s.id);
        return out;
    }

    static int index(String id) {
        for (int i = 0; i < LearnSteps.ALL.size(); i++) if (LearnSteps.ALL.get(i).id.equals(id)) return i;
        throw new AssertionError("no step " + id);
    }
}
