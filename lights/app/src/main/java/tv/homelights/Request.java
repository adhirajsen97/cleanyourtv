package tv.homelights;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An HTTP request to a bridge on the network, for a light the TV's own radio cannot reach: the
 * cove strip's 433 MHz receiver, say, with an ESP32 running ESPHome next to it, whose web server
 * presses a button at {@code POST /button/<id>/press}. In lights.json:
 * <pre>
 *   { "url": "http://192.168.1.60/button/cove_on/press" }                            POST, no body
 *   { "url": "http://ha.local:8123/api/services/light/turn_on", "method": "POST",
 *     "headers": { "Authorization": "Bearer …" }, "body": "{\"entity_id\": \"light.cove\"}" }
 * </pre>
 * Any 2xx answer counts as done. {@code timeout_ms} bounds connecting and waiting for the answer.
 */
public final class Request implements Action {
    public static final String DEFAULT_METHOD = "POST";
    public static final List<String> METHODS = Collections.unmodifiableList(
            Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE"));
    public static final int DEFAULT_TIMEOUT_MS = 3000;
    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 60000;

    public final String url;
    public final String method;
    public final Map<String, String> headers;
    /** The body to send, or null for none. */
    public final String body;
    public final int timeoutMs;

    public Request(String url, String method, Map<String, String> headers, String body, int timeoutMs) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("url must start with http:// or https://");
        }
        try {
            if (new URL(url).getHost().isEmpty()) throw new IllegalArgumentException("url has no host");
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("url: " + e.getMessage());
        }
        String m = method == null || method.trim().isEmpty() ? DEFAULT_METHOD : method.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(m)) throw new IllegalArgumentException("method must be one of " + METHODS);
        if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("timeout_ms must be " + MIN_TIMEOUT_MS + " to " + MAX_TIMEOUT_MS);
        }
        this.url = url;
        this.method = m;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers == null ? Collections.emptyMap() : headers));
        this.body = body;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String describe() {
        return method + " " + url + (body == null ? "" : ", " + body.length() + " chars of body");
    }

    @Override
    public String toString() {
        return describe();
    }
}
