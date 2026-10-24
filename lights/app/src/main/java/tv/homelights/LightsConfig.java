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
import java.util.List;
import java.util.Set;

/**
 * The packet list, lights.json. The app ships one in its assets and reads an override from its
 * external files directory when a file is there (lights/push-config.sh puts it there). Format:
 * <pre>
 * {
 *   "burst_ms": 800,                      how long each packet is advertised; 100 to 10000, optional
 *   "lights": [
 *     { "id": "drawing-room", "name": "Drawing room",
 *       "on":  [ { "service_data": "08F0", "data": "1000B559..." } ],
 *       "off": [ { "service_data": "08F0", "data": "1000EA2D..." } ] }
 *   ]
 * }
 * </pre>
 * A packet names exactly one of "service_data" (16-bit service UUID, hex) or "manufacturer_data"
 * (company identifier, hex), and "data", the payload in hex. The packets of a list are advertised
 * one after another. Parsing is strict: any problem is an {@link IllegalArgumentException} whose
 * message says where, so a bad file shows on the status line instead of sending the wrong bytes.
 */
public final class LightsConfig {
    public static final String FILE_NAME = "lights.json";
    public static final int DEFAULT_BURST_MS = 800;
    public static final int MIN_BURST_MS = 100;
    public static final int MAX_BURST_MS = 10000;
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
            lights.add(new Light(id, name, packets(o, "on", id), packets(o, "off", id)));
        }
        return new LightsConfig(burstMs, lights);
    }

    private static List<Packet> packets(JSONObject light, String key, String id) {
        JSONArray array = light.optJSONArray(key);
        if (array == null || array.length() == 0) {
            throw new IllegalArgumentException("light \"" + id + "\" has no \"" + key + "\" packets");
        }
        List<Packet> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String where = "light \"" + id + "\", " + key + "[" + i + "]";
            JSONObject o = array.optJSONObject(i);
            if (o == null) throw new IllegalArgumentException(where + " is not an object");
            try {
                out.add(packet(o));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage());
            }
        }
        return out;
    }

    static Packet packet(JSONObject o) {
        Packet.Kind kind = null;
        for (Packet.Kind k : Packet.Kind.values()) {
            if (!o.has(k.key)) continue;
            if (kind != null) throw new IllegalArgumentException("has both service_data and manufacturer_data");
            kind = k;
        }
        if (kind == null) throw new IllegalArgumentException("needs service_data or manufacturer_data");
        byte[] idBytes = hex(o, kind.key);
        if (idBytes.length != 2) {
            throw new IllegalArgumentException(kind.key + " must be 2 bytes (4 hex digits), e.g. \"08F0\"");
        }
        int id = ((idBytes[0] & 0xFF) << 8) | (idBytes[1] & 0xFF);
        if (!o.has("data")) throw new IllegalArgumentException("has no \"data\"");
        return new Packet(kind, id, hex(o, "data"));
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
        if (text.startsWith("﻿")) text = text.substring(1);
        return text;
    }
}
