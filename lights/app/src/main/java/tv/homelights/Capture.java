package tv.homelights;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the TV heard while learning the remote: for each step, every distinct packet with how often
 * and when it came. Written as capture.json; lights/pull-capture.sh fetches it from the TV.
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
        public final double began;
        public double ended;
        public int copies;
        public int ignored;
        final Map<String, Heard> heard = new LinkedHashMap<>();

        StepRecord(String id, String instruction, double began) {
            this.id = id;
            this.instruction = instruction;
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
        endStep(nowMillis);
        current = new StepRecord(id, instruction, seconds(nowMillis));
        steps.add(current);
    }

    public void endStep(long nowMillis) {
        if (current != null) current.ended = seconds(nowMillis);
        current = null;
    }

    public StepRecord current() {
        return current;
    }

    /** Records a packet under the current step; true when it is one not heard in this step before. */
    public boolean heard(byte[] raw, String address, int rssi, long nowMillis) {
        if (current == null) return false;
        String hex = Hex.encode(raw);
        current.copies++;
        Heard h = current.heard.get(hex);
        if (h != null) {
            h.count++;
            h.last = seconds(nowMillis);
            if (rssi > h.rssi) h.rssi = rssi;
            return false;
        }
        if (current.heard.size() >= MAX_PER_STEP) return false;
        current.heard.put(hex, new Heard(hex, address, seconds(nowMillis), rssi));
        return true;
    }

    /** A packet from something other than the lights' remote, counted so the screen can say so. */
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
