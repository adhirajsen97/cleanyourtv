package tv.homelights;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends a {@link Request} off the main thread and reports back on it. {@link #perform} is the
 * blocking part, which the unit tests run against a local server.
 */
public final class Http {
    public interface Callback {
        /** {@code error} is null when the bridge answered 2xx, else what went wrong, in a few words. */
        void onResult(String error);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public void send(Request request, Callback callback) {
        executor.execute(() -> {
            String error = perform(request);
            handler.post(() -> callback.onResult(error));
        });
    }

    /** Sends the request and waits for the answer: null for a 2xx, else the HTTP status or why there was no answer. */
    public static String perform(Request request) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(request.url).openConnection();
            c.setConnectTimeout(request.timeoutMs);
            c.setReadTimeout(request.timeoutMs);
            c.setRequestMethod(request.method);
            for (Map.Entry<String, String> h : request.headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (request.body != null) {
                if (c.getRequestProperty("Content-Type") == null) c.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = request.body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            if (status >= 200 && status < 300) return null;
            String message = c.getResponseMessage();
            return "HTTP " + status + (message == null || message.isEmpty() ? "" : " " + message) + " from " + request.url;
        } catch (IOException e) {
            return "no answer from " + request.url + " (" + e.getMessage() + ")";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
