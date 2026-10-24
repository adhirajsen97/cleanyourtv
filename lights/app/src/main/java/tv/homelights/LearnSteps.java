package tv.homelights;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The button sequence for learning the light remote, one step per button. The TV records every
 * packet it hears during a step under the step's id, so the capture file says which packet belongs
 * to which button without any timing work. The adjustment steps come once per selection (ALL ON, ON
 * 1, ON 2, ON 3), because the remote may put the selected row into each adjustment packet, or the
 * lights may simply remember the last row addressed; comparing the four sets tells which.
 * The K, moon, RGB and Aid buttons also carry the selected group, so they come right after the
 * all-rows adjustments, while ALL ON is still selected. SETUP is never asked for: it pairs or
 * unpairs lights. {@link #subset} gives a shorter run, e.g. "extras" to record those four again.
 */
public final class LearnSteps {
    public static final class Step {
        public final String id;
        /** The button on the light remote, as printed on it. */
        public final String button;
        public final int presses;
        /** Why, when it is not obvious; may be empty. */
        public final String note;

        Step(String id, String button, int presses, String note) {
            this.id = id;
            this.button = button;
            this.presses = presses;
            this.note = note;
        }
    }

    public static final List<Step> ALL;

    static {
        List<Step> s = new ArrayList<>();
        s.add(new Step("all-on", "ALL ON", 3, "The packets the TV already sends; a check that the capture works."));
        s.add(new Step("all-off", "ALL OFF", 3, ""));
        for (int row = 1; row <= 4; row++) {
            s.add(new Step("on-" + row, "ON " + row, 2, ""));
            s.add(new Step("off-" + row, "OFF " + row, 2, ""));
        }
        adjustments(s, "all", "ALL ON", "every row");
        s.add(new Step("k-button", "K (the round button at the top left)", 2, "Whatever it does, note it."));
        s.add(new Step("moon", "the moon (the round button at the top right)", 2, "Probably a night light setting."));
        s.add(new Step("rgb", "RGB", 2, ""));
        s.add(new Step("aid", "Aid", 2, ""));
        for (int row = 1; row <= 3; row++) adjustments(s, "row" + row, "ON " + row, "row " + row);
        ALL = Collections.unmodifiableList(s);
    }

    /** The steps with these ids, in sequence order. */
    public static List<Step> withIds(String... ids) {
        List<String> wanted = Arrays.asList(ids);
        List<Step> out = new ArrayList<>();
        for (Step s : ALL) if (wanted.contains(s.id)) out.add(s);
        return out;
    }

    /**
     * A named shorter run: "extras" is ALL ON then K, moon, RGB and Aid; anything else, or a
     * comma-separated list of step ids, selects those; unknown names give the full sequence.
     */
    public static List<Step> subset(String name) {
        if (name == null || name.trim().isEmpty() || "all".equals(name.trim())) return ALL;
        if ("extras".equals(name.trim())) return withIds("select-all", "k-button", "moon", "rgb", "aid");
        List<Step> chosen = withIds(name.trim().split("\\s*,\\s*"));
        return chosen.isEmpty() ? ALL : chosen;
    }

    private static void adjustments(List<Step> s, String prefix, String selectButton, String what) {
        s.add(new Step("select-" + prefix, selectButton, 1, "Once, to select " + what + " for the next four steps."));
        s.add(new Step(prefix + "-brighter", "sun + (brightness up)", 3, ""));
        s.add(new Step(prefix + "-dimmer", "sun - (brightness down)", 3, ""));
        s.add(new Step(prefix + "-k-plus", "K+", 3, "Note whether the light gets warmer or cooler."));
        s.add(new Step(prefix + "-k-minus", "K-", 3, ""));
    }

    private LearnSteps() {}
}
