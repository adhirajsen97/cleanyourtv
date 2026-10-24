package tv.homelights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class LearnStepsTest {
    @Test
    public void idsAreUniqueAndSetupIsNeverAskedFor() {
        Set<String> ids = new HashSet<>();
        for (LearnSteps.Step s : LearnSteps.ALL) {
            assertTrue("duplicate id " + s.id, ids.add(s.id));
            assertFalse(s.id, s.button.toUpperCase().contains("SETUP"));
            assertTrue(s.presses >= 1);
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
    public void subsets() {
        assertEquals(LearnSteps.ALL, LearnSteps.subset(null));
        assertEquals(LearnSteps.ALL, LearnSteps.subset("all"));
        assertEquals(LearnSteps.ALL, LearnSteps.subset("no-such-step"));
        assertEquals(java.util.Arrays.asList("select-all", "k-button", "moon", "rgb", "aid"), ids(LearnSteps.subset("extras")));
        assertEquals(java.util.Arrays.asList("all-on", "off-2"), ids(LearnSteps.subset("off-2, all-on")));
    }

    static java.util.List<String> ids(java.util.List<LearnSteps.Step> steps) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (LearnSteps.Step s : steps) out.add(s.id);
        return out;
    }

    static int index(String id) {
        for (int i = 0; i < LearnSteps.ALL.size(); i++) if (LearnSteps.ALL.get(i).id.equals(id)) return i;
        throw new AssertionError("no step " + id);
    }
}
