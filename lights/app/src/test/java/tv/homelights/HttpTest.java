package tv.homelights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The blocking half of {@link Http} against a server on this machine, the way the TV talks to a bridge. */
public class HttpTest {
    @Test
    public void sendsMethodHeadersAndBodyAndReadsTheStatus() throws Exception {
        try (MiniServer server = new MiniServer()) {
            String base = server.base();
            assertNull(Http.perform(new Request(base + "/button/cove_on/press", "POST",
                    Collections.singletonMap("Authorization", "Bearer x"), "{\"a\":1}", 2000)));
            assertEquals("POST /button/cove_on/press auth=Bearer x type=application/json body={\"a\":1}", server.seen.get(0));
            assertNull(Http.perform(new Request(base + "/button/cove_on/press", "GET", null, null, 2000)));
            assertEquals("GET /button/cove_on/press auth=null type=null body=", server.seen.get(1));
            assertNull("a body keeps the Content-Type given for it", Http.perform(new Request(base + "/button/cove_on/press", "PUT",
                    Collections.singletonMap("Content-Type", "text/plain"), "hi", 2000)));
            assertEquals("PUT /button/cove_on/press auth=null type=text/plain body=hi", server.seen.get(2));
            String error = Http.perform(new Request(base + "/missing", "POST", null, null, 2000));
            assertNotNull(error);
            assertTrue(error, error.startsWith("HTTP 404 Not Found from " + base + "/missing"));
        }
    }

    @Test
    public void nothingListeningIsReportedNotThrown() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = s.getLocalPort();
        }
        String error = Http.perform(new Request("http://127.0.0.1:" + port + "/x", "POST", null, null, 1000));
        assertNotNull(error);
        assertTrue(error, error.startsWith("no answer from http://127.0.0.1:" + port + "/x ("));
    }

    /**
     * A few lines of HTTP server on java.net alone: the unit tests compile against android.jar,
     * which has no com.sun.net.httpserver. Answers 404 to /missing and 200 to anything else, and
     * keeps one line per request: method, path, the Authorization and Content-Type headers, body.
     */
    static final class MiniServer implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        final List<String> seen = Collections.synchronizedList(new ArrayList<>());

        MiniServer() throws IOException {
            Thread thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket s = socket.accept()) {
                        serve(s);
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        String base() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        private void serve(Socket s) throws IOException {
            InputStream in = s.getInputStream();
            String head = readHead(in);
            String[] lines = head.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            byte[] body = new byte[length];
            for (int got = 0; got < length; ) {
                int n = in.read(body, got, length - got);
                if (n < 0) break;
                got += n;
            }
            seen.add(requestLine[0] + " " + requestLine[1] + " auth=" + headers.get("authorization")
                    + " type=" + headers.get("content-type") + " body=" + new String(body, StandardCharsets.UTF_8));
            String status = requestLine[1].equals("/missing") ? "404 Not Found" : "200 OK";
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** The request line and headers, up to and without the blank line. */
        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                buf.write(b);
                byte[] so = buf.toByteArray();
                int n = so.length;
                if (n >= 4 && so[n - 4] == '\r' && so[n - 3] == '\n' && so[n - 2] == '\r' && so[n - 1] == '\n') break;
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
