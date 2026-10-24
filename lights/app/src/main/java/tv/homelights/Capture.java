package tv.homelights;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the TV heard while learning a remote: for each step, every distinct packet with how often
 * and when it came. Written as capture.json, or the plan's own file name; lights/pull-capture.sh
 * fetches it from the TV. A quiet step, in which nothing is pressed, feeds a baseline: the packets
 * heard then, their shapes ({@link AdData#shape}) and their senders are the room's own, so every
 * later step ignores them, the way the format filter ignores packets in other formats.
 */
public final class Capture {
    public static final String FILE_NAME = "capture.json";
    /** Distinct packets kept per step; a remote sends a handful, a busy room's "other" mode many. */
    public static final int MAX_PER_STEP = 200;

    public static final class Heard {
        public final String raw;
        public final String address;
        public int count = 1;
        public final double first;
        public double last;
        public int rssi;

        Heard(String raw, String address, double at, int rssi) {
            this.raw = raw;
            this.address = address;
            this.first = at;
            this.last = at;
            this.rssi = rssi;
        }
    }

    public static final class StepRecord {
        public final String id;
        public final String instruction;
        /** Nothing is pressed in this step: what it hears is the baseline, not the remote. */
        public final boolean quiet;
        public final double began;
        public double ended;
        public int copies;
        public int ignored;
        final Map<String, Heard> heard = new LinkedHashMap<>();

        StepRecord(String id, String instruction, double began, boolean quiet) {
            this.id = id;
            this.instruction = instruction;
            this.quiet = quiet;
            this.began = began;
            this.ended = began;
        }

        public int distinct() {
            return heard.size();
        }
    }

    private final String started;
    private final String device;
    private final long startMillis;
    private final List<StepRecord> steps = new ArrayList<>();
    private final Set<String> baselineRaws = new HashSet<>();
    private final Set<String> baselineShapes = new HashSet<>();
    private final Set<String> baselineSenders = new HashSet<>();
    private StepRecord current;

    public Capture(String started, String device, long startMillis) {
        this.started = started;
        this.device = device;
        this.startMillis = startMillis;
    }

    private double seconds(long nowMillis) {
        return (nowMillis - startMillis) / 1000.0;
    }

    public void beginStep(String id, String instruction, long nowMillis) {
        beginStep(id, instruction, nowMillis, false);
    }

    /** Starts a step; a quiet one feeds the baseline instead of recording a remote. */
    public void beginStep(String id, String instruction, long nowMillis, boolean quiet) {
        endStep(nowMillis);
        current = new StepRecord(id, instruction, seconds(nowMillis), quiet);
        steps.add(current);
    }

    public void endStep(long nowMillis) {
        if (current != null) current.ended = seconds(nowMillis);
        current = null;
    }

    public StepRecord current() {
        return current;
    }

    /**
     * Records a packet under the current step; true when it is one not heard in this step before.
     * In a quiet step every packet, its shape and its sender go into the baseline, beyond the step's
     * cap too; afterwards a packet the baseline knows, by its bytes, its shape or its sender, counts
     * as ignored.
     */
    public boolean heard(byte[] raw, String address, int rssi, long nowMillis) {
        if (current == null) return false;
        String hex = Hex.encode(raw);
        String shape = AdData.shape(raw);
        String sender = address == null ? "" : address;
        if (current.quiet) {
            baselineRaws.add(hex);
            baselineShapes.add(shape);
            if (!sender.isEmpty()) baselineSenders.add(sender);
        } else if (baselineRaws.contains(hex) || baselineShapes.contains(shape)
                || (!sender.isEmpty() && baselineSenders.contains(sender))) {
            current.ignored++;
            return false;
        }
        current.copies++;
        Heard h = current.heard.get(hex);
        if (h != null) {
            h.count++;
            h.last = seconds(nowMillis);
            if (rssi > h.rssi) h.rssi = rssi;
            return false;
        }
        if (current.heard.size() >= MAX_PER_STEP) return false;
        current.heard.put(hex, new Heard(hex, sender, seconds(nowMillis), rssi));
        return true;
    }

    /** A packet from something other than the remote, counted so the screen can say so. */
    public void ignored() {
        if (current != null) current.ignored++;
    }

    public List<StepRecord> steps() {
        return steps;
    }

    public JSONObject toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("started", started);
            root.put("device", device);
            JSONArray array = new JSONArray();
            for (StepRecord s : steps) {
                JSONObject o = new JSONObject();
                o.put("step", s.id);
                o.put("instruction", s.instruction);
                if (s.quiet) o.put("quiet", true);
                o.put("began", round(s.began));
                o.put("ended", round(s.ended));
                o.put("copies", s.copies);
                o.put("ignored", s.ignored);
                JSONArray packets = new JSONArray();
                for (Heard h : s.heard.values()) {
                    JSONObject p = new JSONObject();
                    p.put("raw", h.raw);
                    p.put("records", AdData.pretty(Hex.decode(h.raw)));
                    p.put("count", h.count);
                    p.put("first", round(h.first));
                    p.put("last", round(h.last));
                    p.put("rssi", h.rssi);
                    p.put("address", h.address);
                    packets.put(p);
                }
                o.put("packets", packets);
                array.put(o);
            }
            root.put("steps", array);
            return root;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public String toText() {
        try {
            return toJson().toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double round(double seconds) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.3f", seconds));
    }
}
