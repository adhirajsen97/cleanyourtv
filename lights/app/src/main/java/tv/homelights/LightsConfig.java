package tv.homelights;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The packet list, lights.json. The app ships one in its assets and reads an override from its
 * external files directory when a file is there (lights/push-config.sh puts it there). Format:
 * <pre>
 * {
 *   "burst_ms": 1500,                     how long each packet is advertised; 100 to 60000, optional
 *   "connectable": true,                  send with the Flags record in front, as a phone does; optional
 *   "lights": [
 *     { "id": "all", "name": "All rows",
 *       "commands": {
 *         "on":  [ { "service_data": "08F0", "data": "1000B559..." } ],
 *         "off": [ { "service_data": "08F0", "data": "1000EA2D..." } ] } },
 *     { "id": "row-1", "name": "Row 1" },          no packets captured yet: a row without buttons
 *     { "id": "cove", "name": "Cove", "in_all": false,            a remote of its own: All rows does not switch it
 *       "commands": { "on": [ { "url": "http://192.168.1.60/button/cove_on/press" } ] } }   a request to a bridge
 *   ]
 * }
 * </pre>
 * A command is a name (lower-case letters, digits, hyphens) with one or more actions, done one
 * after another, or {"variants": [[actions], [actions], ...]}, one list per press recorded from
 * the remote, used in turn. An action is a packet, naming exactly one of "service_data" (16-bit
 * service UUID, hex) or "manufacturer_data" (company identifier, hex) and "data", the payload in
 * hex, and may set "connectable" and "burst_ms" for itself; or a request to a bridge over the
 * network, with "url" and optionally "method", "headers", "body" and "timeout_ms"
 * ({@link Request}). The first files had "on" and "off" lists directly on the light; those still
 * parse. Parsing is strict: any problem is an {@link IllegalArgumentException} whose message
 * says where, so a bad file shows on the status line instead of sending the wrong bytes.
 */
public final class LightsConfig {
    public static final String FILE_NAME = "lights.json";
    public static final int DEFAULT_BURST_MS = 1500;
    public static final int MIN_BURST_MS = 100;
    /** Long enough for a phone to catch the TV in a scanner; Android itself allows up to 180 s. */
    public static final int MAX_BURST_MS = 60000;
    public static final boolean DEFAULT_CONNECTABLE = true;
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    /** A packet list is a few hundred bytes; anything this size is the wrong file. */
    public static final long MAX_FILE_BYTES = 64 * 1024;

    public final int burstMs;
    public final List<Light> lights;

    private LightsConfig(int burstMs, List<Light> lights) {
        this.burstMs = burstMs;
        this.lights = Collections.unmodifiableList(lights);
    }

    public Light find(String id) {
        for (Light light : lights) {
            if (light.id.equals(id)) return light;
        }
        return null;
    }

    public static LightsConfig parse(String text) {
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage());
        }
        int burstMs = root.optInt("burst_ms", DEFAULT_BURST_MS);
        if (burstMs < MIN_BURST_MS || burstMs > MAX_BURST_MS) {
            throw new IllegalArgumentException("burst_ms must be " + MIN_BURST_MS + " to " + MAX_BURST_MS);
        }
        boolean connectable = root.optBoolean("connectable", DEFAULT_CONNECTABLE);
        JSONArray array = root.optJSONArray("lights");
        if (array == null || array.length() == 0) throw new IllegalArgumentException("\"lights\" is missing or empty");
        List<Light> lights = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) throw new IllegalArgumentException("lights[" + i + "] is not an object");
            String id = o.optString("id", "").trim();
            if (id.isEmpty()) throw new IllegalArgumentException("lights[" + i + "] has no \"id\"");
            if (!ids.add(id)) throw new IllegalArgumentException("two lights have the id \"" + id + "\"");
            String name = o.optString("name", "").trim();
            if (name.isEmpty()) name = id;
            lights.add(new Light(id, name, commands(o, id, connectable), o.optBoolean("in_all", true)));
        }
        return new LightsConfig(burstMs, lights);
    }

    /** "commands": {name: [actions] | {"variants": [[actions], ...]}}, plus the first files' "on" and "off". */
    private static Map<String, List<List<Action>>> commands(JSONObject light, String id, boolean connectable) {
        Map<String, List<List<Action>>> out = new LinkedHashMap<>();
        JSONObject commands = light.optJSONObject("commands");
        if (commands != null) {
            for (Iterator<String> it = commands.keys(); it.hasNext(); ) {
                String name = it.next();
                if (!COMMAND_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("light \"" + id + "\": command name \"" + name
                            + "\" (use lower-case letters, digits and hyphens)");
                }
                out.put(name, variants(commands, name, id, connectable));
            }
        }
        for (String legacy : new String[] {"on", "off"}) {
            if (light.has(legacy) && !out.containsKey(legacy)) out.put(legacy, variants(light, legacy, id, connectable));
        }
        return out;
    }

    /** A command's value: one action list, or {"variants": [list, list, ...]}. */
    private static List<List<Action>> variants(JSONObject holder, String key, String id, boolean connectable) {
        List<List<Action>> out = new ArrayList<>();
        JSONObject object = holder.optJSONObject(key);
        if (object == null) {
            out.add(actions(holder.optJSONArray(key), key, id, connectable));
            return out;
        }
        JSONArray variants = object.optJSONArray("variants");
        if (variants == null || variants.length() == 0) {
            throw new IllegalArgumentException("light \"" + id + "\": \"" + key + "\" has no \"variants\"");
        }
        for (int i = 0; i < variants.length(); i++) {
            out.add(actions(variants.optJSONArray(i), key + " variant " + (i + 1), id, connectable));
        }
        return out;
    }

    private static List<Action> actions(JSONArray array, String key, String id, boolean connectable) {
        if (array == null || array.length() == 0) {
            throw new IllegalArgumentException("light \"" + id + "\": \"" + key + "\" has no packets");
        }
        List<Action> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String where = "light \"" + id + "\", " + key + "[" + i + "]";
            JSONObject o = array.optJSONObject(i);
            if (o == null) throw new IllegalArgumentException(where + " is not an object");
            try {
                out.add(action(o, connectable));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage());
            }
        }
        return out;
    }

    /** A packet (service_data or manufacturer_data, with data) or a request (url). */
    static Action action(JSONObject o, boolean connectable) {
        boolean request = o.has("url");
        Packet.Kind kind = null;
        for (Packet.Kind k : Packet.Kind.values()) {
            if (!o.has(k.key)) continue;
            if (request) throw new IllegalArgumentException("has both a url and " + k.key);
            if (kind != null) throw new IllegalArgumentException("has both service_data and manufacturer_data");
            kind = k;
        }
        if (request) return request(o);
        if (kind == null) throw new IllegalArgumentException("needs service_data, manufacturer_data or url");
        byte[] idBytes = hex(o, kind.key);
        if (idBytes.length != 2) {
            throw new IllegalArgumentException(kind.key + " must be 2 bytes (4 hex digits), e.g. \"08F0\"");
        }
        int id = ((idBytes[0] & 0xFF) << 8) | (idBytes[1] & 0xFF);
        if (!o.has("data")) throw new IllegalArgumentException("has no \"data\"");
        int burstMs = o.optInt("burst_ms", 0);
        if (o.has("burst_ms") && (burstMs < MIN_BURST_MS || burstMs > MAX_BURST_MS)) {
            throw new IllegalArgumentException("burst_ms must be " + MIN_BURST_MS + " to " + MAX_BURST_MS);
        }
        return new Packet(kind, id, hex(o, "data"), o.optBoolean("connectable", connectable), burstMs);
    }

    /** {"url": …, "method": …, "headers": {…}, "body": …, "timeout_ms": …}: a request to a bridge. */
    static Request request(JSONObject o) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (o.has("headers")) {
            JSONObject h = o.optJSONObject("headers");
            if (h == null) throw new IllegalArgumentException("headers must be an object of names to values");
            for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                String name = it.next();
                Object value = h.opt(name);
                if (!(value instanceof String)) throw new IllegalArgumentException("header \"" + name + "\" must be a string");
                headers.put(name, (String) value);
            }
        }
        String body = o.has("body") ? o.optString("body") : null;
        int timeoutMs = o.optInt("timeout_ms", Request.DEFAULT_TIMEOUT_MS);
        if (o.has("timeout_ms") && (timeoutMs < Request.MIN_TIMEOUT_MS || timeoutMs > Request.MAX_TIMEOUT_MS)) {
            throw new IllegalArgumentException("timeout_ms must be " + Request.MIN_TIMEOUT_MS + " to " + Request.MAX_TIMEOUT_MS);
        }
        return new Request(o.optString("url", "").trim(), o.optString("method", Request.DEFAULT_METHOD), headers, body, timeoutMs);
    }

    /** The hex value under {@code key}; a problem with it names the key. */
    private static byte[] hex(JSONObject o, String key) {
        try {
            return Hex.decode(o.optString(key, ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + ": " + e.getMessage());
        }
    }

    /** Reads a small text file, dropping the byte-order mark Windows editors add. */
    public static String readText(InputStream in, long declaredSize) throws IOException {
        if (declaredSize > MAX_FILE_BYTES) {
            throw new IOException("larger than " + (MAX_FILE_BYTES / 1024) + " KB");
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_FILE_BYTES) throw new IOException("larger than " + (MAX_FILE_BYTES / 1024) + " KB");
        }
        String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        // Compared by char code: a BOM written into this source would trip lint (ByteOrderMark).
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) text = text.substring(1);
        return text;
    }
}
