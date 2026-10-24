package tv.homelights;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The button sequences for learning the remotes, one step per button. The TV records every packet
 * it hears during a step under the step's id, so the capture file says which packet belongs to
 * which button without any timing work.
 *
 * The light remote ({@link #ALL}) switches the rows of ceiling lights. Its adjustment steps come
 * once per selection (ALL ON, ON 1, ON 2, ON 3), because the remote may put the selected row into
 * each adjustment packet, or the lights may simply remember the last row addressed; comparing the
 * four sets tells which. The K, moon, RGB and Aid buttons also carry the selected group, so they
 * come right after the all-rows adjustments, while ALL ON is still selected. SETUP is never asked
 * for: it pairs or unpairs lights.
 *
 * The cove remote ({@link #COVE}) is the "TC" touch remote of the ambient strip round the ceiling:
 * O and I at the top, a ring from white to yellow for the colour temperature, two bulbs for the
 * brightness. Nothing is known about its packets, so its plan records every packet heard and opens
 * with a quiet step in which nothing is pressed: what the room sends then is not the remote's, and
 * the later steps leave those senders, and packets of the same shape, out.
 *
 * {@link #plan} picks a sequence by name, or a shorter run, e.g. "extras" to record the four extra
 * buttons of the light remote again.
 */
public final class LearnSteps {
    public static final class Step {
        public final String id;
        /** The button on the remote, as printed on it; for a step without presses, the whole instruction. */
        public final String button;
        /** How many times to press it; 0 for a step that is no button press (keeping still, a slide). */
        public final int presses;
        /** True for the step in which nothing is pressed, so the TV learns what the room sends on its own. */
        public final boolean quiet;
        /** Why, when it is not obvious; may be empty. */
        public final String note;

        Step(String id, String button, int presses, String note) {
            this(id, button, presses, false, note);
        }

        private Step(String id, String button, int presses, boolean quiet, String note) {
            this.id = id;
            this.button = button;
            this.presses = presses;
            this.quiet = quiet;
            this.note = note;
        }

        /** The step in which nothing is pressed. */
        static Step silence(String id, String instruction, String note) {
            return new Step(id, instruction, 0, true, note);
        }
    }

    /** A remote's sequence, with how the screen names the remote and where its capture goes. */
    public static final class Plan {
        public final String id;
        /** The remote as the screen names it: "the light remote". */
        public final String remote;
        public final List<Step> steps;
        /** Record every packet heard, not only the lights' two formats: for a remote whose format is unknown. */
        public final boolean everyPacket;
        /** The capture file in the app's files directory, and the command on the computer that fetches it. */
        public final String file;
        public final String pullCommand;
        /** The line at the bottom of the screen. */
        public final String footer;

        Plan(String id, String remote, List<Step> steps, boolean everyPacket, String file, String pullCommand,
                String footer) {
            this.id = id;
            this.remote = remote;
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.everyPacket = everyPacket;
            this.file = file;
            this.pullCommand = pullCommand;
            this.footer = footer;
        }

        /** The same plan over a selection of its steps. */
        Plan with(List<Step> selection) {
            return new Plan(id, remote, selection, everyPacket, file, pullCommand, footer);
        }
    }

    /** The light remote's full sequence. */
    public static final List<Step> ALL;
    /** The cove remote's sequence. */
    public static final List<Step> COVE;
    public static final Plan LIGHT_REMOTE;
    public static final Plan COVE_REMOTE;

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

        List<Step> c = new ArrayList<>();
        c.add(Step.silence("cove-quiet", "Press nothing for ten seconds.",
                "The TV notes what the room sends on its own; the later steps leave those senders, and packets of their kind, out. Then press Next."));
        c.add(new Step("cove-on", "I (the on button, top right)", 3,
                "Heard in this step must go up with each press. If it stays at 0, the TV cannot hear this remote; see the README."));
        c.add(new Step("cove-off", "O (the off button, top left)", 3, ""));
        c.add(new Step("cove-brighter", "the upper bulb (brightness up)", 3, ""));
        c.add(new Step("cove-dimmer", "the lower bulb (brightness down)", 3, ""));
        c.add(new Step("cove-cool", "the ring at its white end (top left)", 2, "A tap, not a slide."));
        c.add(new Step("cove-neutral", "the ring at the bottom, where white meets yellow", 2, "A tap, not a slide."));
        c.add(new Step("cove-warm", "the ring at its yellow end (top right)", 2, "A tap, not a slide."));
        c.add(new Step("cove-yellow-dot", "the yellow dot in the middle of the ring", 2, "Whatever it does, note it."));
        c.add(new Step("cove-white-dot", "the white dot in the middle of the ring", 2, "Whatever it does, note it."));
        c.add(new Step("cove-slide", "Slide a finger slowly round the ring, from the white end to the yellow end, once.", 0,
                "About five seconds for the whole way round. This shows how the ring encodes the colour; it becomes no button."));
        COVE = Collections.unmodifiableList(c);

        LIGHT_REMOTE = new Plan("lights", "the light remote", ALL, false, Capture.FILE_NAME, "lights/pull-capture.sh",
                "Never press SETUP on the light remote: it pairs or unpairs the lights. "
                + "The capture is saved after every step; Back keeps what is recorded so far.");
        COVE_REMOTE = new Plan("cove", "the cove remote", COVE, true, "capture-cove.json", "lights/pull-capture.sh --cove",
                "Every packet in the room is recorded, so the quiet first step matters: press nothing then. "
                + "The capture is saved after every step; Back keeps what is recorded so far.");
    }

    /**
     * The plan by name: nothing, "all" or "lights" is the light remote's full sequence, "extras" its
     * ALL ON then K, moon, RGB and Aid, "cove" the cove remote's; a comma-separated list of step ids
     * selects those steps of whichever remote they belong to. An unknown name gives the light remote's.
     */
    public static Plan plan(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || "all".equals(n) || "lights".equals(n)) return LIGHT_REMOTE;
        if ("extras".equals(n)) return LIGHT_REMOTE.with(withIds(ALL, "select-all", "k-button", "moon", "rgb", "aid"));
        if ("cove".equals(n)) return COVE_REMOTE;
        String[] ids = n.split("\\s*,\\s*");
        List<Step> chosen = withIds(ALL, ids);
        if (!chosen.isEmpty()) return LIGHT_REMOTE.with(chosen);
        chosen = withIds(COVE, ids);
        if (!chosen.isEmpty()) return COVE_REMOTE.with(chosen);
        return LIGHT_REMOTE;
    }

    /** The steps of a sequence with these ids, in sequence order. */
    public static List<Step> withIds(List<Step> sequence, String... ids) {
        List<String> wanted = Arrays.asList(ids);
        List<Step> out = new ArrayList<>();
        for (Step s : sequence) if (wanted.contains(s.id)) out.add(s);
        return out;
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
