package tv.homelights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class LightsConfigTest {
    /** Gradle runs unit tests from the module directory; the shipped file sits in src/main/assets. */
    static final Path BUNDLED = Paths.get("src/main/assets/lights.json");

    static String bundled() throws IOException {
        return new String(Files.readAllBytes(BUNDLED), StandardCharsets.UTF_8);
    }

    @Test
    public void bundledFileHasEveryRowFilledFromTheCapture() throws IOException {
        LightsConfig config = LightsConfig.parse(bundled());
        assertEquals(800, config.burstMs);
        assertEquals(7, config.lights.size());
        List<String> six = Arrays.asList("on", "off", "dimmer", "brighter", "warmer", "cooler");
        for (String id : new String[] {"all", "row-1", "row-2", "row-3"}) {
            Light light = config.find(id);
            assertNotNull(id, light);
            assertEquals(id, six, light.commands());
            assertEquals(id + " brighter: three presses recorded", 3, light.variants("brighter"));
            assertTrue(light.usesRadio("brighter"));
            List<Action> press = light.actions("brighter");
            assertEquals("an adjustment is a press and a release", 2, press.size());
            assertEquals(400, ((Packet) press.get(0)).burstMs);
            Packet on = (Packet) light.actions("on").get(0);
            assertEquals(Packet.Kind.SERVICE_DATA, on.kind);
            assertEquals(0x08F0, on.id);
            assertEquals(24, on.payload.length);
            assertTrue("format B goes out with the Flags record, as the remote sends it", on.connectable);
            assertEquals(0, on.burstMs);
        }
        assertEquals(Arrays.asList("on", "off"), config.find("row-4").commands());
        assertEquals(Arrays.asList("aid", "k", "moon", "rgb"), config.find("extras").commands());
        Light cove = config.find("cove");
        assertNotNull("the cove row is there for its bridge's requests", cove);
        assertTrue("no bridge for the cove yet", cove.commands().isEmpty());
        assertFalse("a remote of its own: All rows does not switch it", cove.inAll);
        assertTrue(config.find("row-1").inAll);
        // The first ALL ON press of the capture comes first (a fresh parse: the loop above already rotated).
        assertEquals("10006A5925EC68A2B2FAEB7BC22B0F1EDD74C0021A21EA82",
                Hex.encode(((Packet) LightsConfig.parse(bundled()).find("all").actions("on").get(0)).payload));
        assertNull(config.find("kitchen"));
    }

    @Test
    public void variantsAreHandedOutInTurn() {
        LightsConfig config = LightsConfig.parse("{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":{\"variants\":["
                + "[{\"service_data\":\"08F0\",\"data\":\"01\"}],"
                + "[{\"service_data\":\"08F0\",\"data\":\"02\",\"burst_ms\":300},{\"service_data\":\"08F0\",\"data\":\"03\"}]]}}}]}");
        Light light = config.lights.get(0);
        assertEquals(2, light.variants("on"));
        assertEquals(0, light.variants("off"));
        assertEquals("01", Hex.encode(((Packet) light.actions("on").get(0)).payload));
        List<Action> second = light.actions("on");
        assertEquals(2, second.size());
        assertEquals("02", Hex.encode(((Packet) second.get(0)).payload));
        assertEquals(300, ((Packet) second.get(0)).burstMs);
        assertEquals(0, ((Packet) second.get(1)).burstMs);
        assertEquals("01", Hex.encode(((Packet) light.actions("on").get(0)).payload));
        assertTrue(light.actions("off").isEmpty());
        assertFalse(light.usesRadio("off"));
    }

    @Test
    public void requestsToABridgeParseWithTheirDefaults() {
        LightsConfig config = LightsConfig.parse("{\"lights\":[{\"id\":\"cove\",\"in_all\":false,\"commands\":{"
                + "\"on\":[{\"url\":\"http://192.168.1.60/button/cove_on/press\"}],"
                + "\"warm\":[{\"url\":\"https://ha.local:8123/api/services/light/turn_on\",\"method\":\"post\","
                + "\"headers\":{\"Authorization\":\"Bearer abc\",\"X-Room\":\"drawing\"},"
                + "\"body\":\"{\\\"entity_id\\\":\\\"light.cove\\\"}\",\"timeout_ms\":5000}],"
                + "\"both\":[" + B + ",{\"url\":\"http://192.168.1.60/button/x/press\",\"method\":\"GET\"}]}}]}");
        Light cove = config.find("cove");
        assertEquals(Arrays.asList("on", "both", "warm"), cove.commands());
        assertFalse("requests alone need no Bluetooth", cove.usesRadio("on"));
        assertTrue("a packet in the mix does", cove.usesRadio("both"));
        Request on = (Request) cove.actions("on").get(0);
        assertEquals("http://192.168.1.60/button/cove_on/press", on.url);
        assertEquals("POST", on.method);
        assertTrue(on.headers.isEmpty());
        assertNull(on.body);
        assertEquals(Request.DEFAULT_TIMEOUT_MS, on.timeoutMs);
        assertEquals("POST http://192.168.1.60/button/cove_on/press", on.describe());
        Request warm = (Request) cove.actions("warm").get(0);
        assertEquals("the method is upper-cased", "POST", warm.method);
        assertEquals("Bearer abc", warm.headers.get("Authorization"));
        assertEquals(Arrays.asList("Authorization", "X-Room"), new java.util.ArrayList<>(warm.headers.keySet()));
        assertEquals("{\"entity_id\":\"light.cove\"}", warm.body);
        assertEquals(5000, warm.timeoutMs);
        List<Action> both = cove.actions("both");
        assertTrue(both.get(0) instanceof Packet);
        assertEquals("GET", ((Request) both.get(1)).method);
    }

    @Test
    public void requestsAreChecked() {
        assertMessage("light \"a\", on[0]: url must start with http:// or https://",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":[{\"url\":\"192.168.1.60/x\"}]}}]}");
        assertMessage("light \"a\", on[0]: url has no host",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":[{\"url\":\"http://\"}]}}]}");
        assertMessage("light \"a\", on[0]: url has no host",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":[{\"url\":\"http:///button/x/press\"}]}}]}");
        assertMessage("light \"a\", on[0]: method must be one of [GET, POST, PUT, PATCH, DELETE]",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":[{\"url\":\"http://x/\",\"method\":\"FETCH\"}]}}]}");
        assertMessage("light \"a\", on[0]: has both a url and service_data",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":[{\"url\":\"http://x/\",\"service_data\":\"08F0\",\"data\":\"10\"}]}}]}");
        assertMessage("light \"a\", on[0]: headers must be an object of names to values",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":[{\"url\":\"http://x/\",\"headers\":\"nope\"}]}}]}");
        assertMessage("light \"a\", on[0]: header \"Retries\" must be a string",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":[{\"url\":\"http://x/\",\"headers\":{\"Retries\":3}}]}}]}");
        assertMessage("light \"a\", on[0]: timeout_ms must be 100 to 60000",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":[{\"url\":\"http://x/\",\"timeout_ms\":99}]}}]}");
    }

    @Test
    public void variantsAndPerPacketBurstAreChecked() {
        assertMessage("light \"a\": \"on\" has no \"variants\"",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":{}}}]}");
        assertMessage("light \"a\": \"on variant 2\" has no packets",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":{\"variants\":[[" + B + "],[]]}}}]}");
        assertMessage("light \"a\", on[0]: burst_ms must be 100 to 60000",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"on\":[{\"service_data\":\"08F0\",\"data\":\"01\",\"burst_ms\":5}]}}]}");
    }

    @Test
    public void commandsInAnyNameKnownOnesFirstAndTheFirstFilesShapeStillParses() {
        LightsConfig config = LightsConfig.parse("{\"lights\":[{\"id\":\"all\",\"commands\":{"
                + "\"night\":[" + B + "],"
                + "\"brighter\":[" + B + "],"
                + "\"on\":[{\"service_data\":\"08F0\",\"data\":\"1000B559C42541FDEF78E0E567050CC0555F4A1AE719D07D\"},"
                + "{\"manufacturer_data\":\"0x5556\",\"data\":\"188752B65F2B5E00FC3151D0699F0824CBA5FC16C46CB22F9F6057\"}],"
                + "\"off\":[{\"manufacturer_data\":\"5556\",\"data\":\"18 87 52 B6\"}]}}]}");
        assertEquals(LightsConfig.DEFAULT_BURST_MS, config.burstMs);
        Light light = config.lights.get(0);
        assertEquals("all", light.name);   // name falls back to the id
        assertEquals(Arrays.asList("on", "off", "brighter", "night"), light.commands());
        assertEquals(2, light.actions("on").size());
        Packet a = (Packet) light.actions("on").get(1);
        assertEquals(Packet.Kind.MANUFACTURER_DATA, a.kind);
        assertEquals(0x5556, a.id);
        assertEquals(27, a.payload.length);
        assertFalse("27 bytes leave no room for Flags", a.connectable);
        assertTrue(((Packet) light.actions("on").get(0)).connectable);
        assertTrue(((Packet) light.actions("off").get(0)).connectable);
        assertEquals(4, ((Packet) light.actions("off").get(0)).payload.length);

        LightsConfig legacy = LightsConfig.parse("{\"lights\":[" + light("a") + "]}");
        assertEquals(Arrays.asList("on", "off"), legacy.lights.get(0).commands());
    }

    @Test
    public void connectableCanBeSwitchedOffForAllOrPerPacket() {
        LightsConfig all = LightsConfig.parse("{\"connectable\":false,\"lights\":[" + light("a") + "]}");
        assertFalse(((Packet) all.lights.get(0).actions("on").get(0)).connectable);
        assertFalse(((Packet) all.lights.get(0).actions("off").get(0)).connectable);
        LightsConfig one = LightsConfig.parse("{\"connectable\":false,\"lights\":[{\"id\":\"a\","
                + "\"on\":[{\"service_data\":\"08F0\",\"data\":\"1000EA2D\",\"connectable\":true}],"
                + "\"off\":[" + B + "]}]}");
        assertTrue(((Packet) one.lights.get(0).actions("on").get(0)).connectable);
        assertFalse(((Packet) one.lights.get(0).actions("off").get(0)).connectable);
    }

    @Test
    public void inAllIsTrueUnlessSaidOtherwise() {
        LightsConfig config = LightsConfig.parse("{\"lights\":[" + light("a")
                + ",{\"id\":\"b\",\"in_all\":false,\"on\":[" + B + "],\"off\":[" + B + "]}]}");
        assertTrue(config.find("a").inAll);
        assertFalse(config.find("b").inAll);
    }

    @Test
    public void commandNamesAndEmptyCommandsAreChecked() {
        assertMessage("light \"a\": command name \"Bright Up\" (use lower-case letters, digits and hyphens)",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"Bright Up\":[" + B + "]}}]}");
        assertMessage("light \"a\": \"dimmer\" has no packets",
                "{\"lights\":[{\"id\":\"a\",\"commands\":{\"dimmer\":[]}}]}");
    }

    @Test
    public void burstUpToAMinute() {
        assertEquals(60000, LightsConfig.parse("{\"burst_ms\":60000,\"lights\":[" + light("a") + "]}").burstMs);
        assertMessage("burst_ms must be 100 to 60000", "{\"burst_ms\":60001,\"lights\":[" + light("a") + "]}");
    }

    @Test
    public void errorsSayWhere() {
        assertMessage("not valid JSON", "nonsense");
        assertMessage("\"lights\" is missing or empty", "{}");
        assertMessage("\"lights\" is missing or empty", "{\"lights\":[]}");
        assertMessage("burst_ms must be 100 to 60000", "{\"burst_ms\":50,\"lights\":[]}");
        assertMessage("lights[0] has no \"id\"", "{\"lights\":[{\"name\":\"x\"}]}");
        assertMessage("two lights have the id \"a\"", "{\"lights\":[" + light("a") + "," + light("a") + "]}");
        assertMessage("light \"a\": \"off\" has no packets", "{\"lights\":[{\"id\":\"a\",\"on\":[" + B + "],\"off\":[]}]}");
        assertMessage("light \"a\", on[0]: needs service_data, manufacturer_data or url",
                "{\"lights\":[{\"id\":\"a\",\"on\":[{\"data\":\"10\"}],\"off\":[" + B + "]}]}");
        assertMessage("light \"a\", on[0]: has both service_data and manufacturer_data",
                "{\"lights\":[{\"id\":\"a\",\"on\":[{\"service_data\":\"08F0\",\"manufacturer_data\":\"5556\",\"data\":\"10\"}],\"off\":[" + B + "]}]}");
        assertMessage("light \"a\", on[0]: service_data must be 2 bytes (4 hex digits), e.g. \"08F0\"",
                "{\"lights\":[{\"id\":\"a\",\"on\":[{\"service_data\":\"F0\",\"data\":\"10\"}],\"off\":[" + B + "]}]}");
        assertMessage("light \"a\", on[0]: service_data: odd number of hex digits (3)",
                "{\"lights\":[{\"id\":\"a\",\"on\":[{\"service_data\":\"8F0\",\"data\":\"10\"}],\"off\":[" + B + "]}]}");
        assertMessage("light \"a\", off[0]: manufacturer_data: not a hex digit: 'x'",
                "{\"lights\":[{\"id\":\"a\",\"on\":[" + B + "],\"off\":[{\"manufacturer_data\":\"55x6\",\"data\":\"10\"}]}]}");
        assertMessage("light \"a\", off[1]: has no \"data\"",
                "{\"lights\":[{\"id\":\"a\",\"on\":[" + B + "],\"off\":[" + B + ",{\"service_data\":\"08F0\"}]}]}");
        assertMessage("light \"a\", on[0]: data: odd number of hex digits (3)",
                "{\"lights\":[{\"id\":\"a\",\"on\":[{\"service_data\":\"08F0\",\"data\":\"ABC\"}],\"off\":[" + B + "]}]}");
        assertMessage("light \"a\", on[0]: service data payload is 28 bytes; a legacy advertisement fits 27 (31 with the record header)",
                "{\"lights\":[{\"id\":\"a\",\"on\":[{\"service_data\":\"08F0\",\"data\":\"" + "AB".repeat(28) + "\"}],\"off\":[" + B + "]}]}");
    }

    static final String B = "{\"service_data\":\"08F0\",\"data\":\"1000EA2D\"}";

    static String light(String id) {
        return "{\"id\":\"" + id + "\",\"on\":[" + B + "],\"off\":[" + B + "]}";
    }

    static void assertMessage(String start, String json) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LightsConfig.parse(json));
        assertTrue("expected '" + start + "', got '" + e.getMessage() + "'", e.getMessage().startsWith(start));
    }

    @Test
    public void readTextDropsTheBomAndCapsTheSize() throws IOException {
        byte[] withBom = ((char) 0xFEFF + "{\"a\":1}").getBytes(StandardCharsets.UTF_8);
        assertEquals("{\"a\":1}", LightsConfig.readText(new ByteArrayInputStream(withBom), withBom.length));
        assertThrows(IOException.class, () -> LightsConfig.readText(new ByteArrayInputStream(new byte[0]), LightsConfig.MAX_FILE_BYTES + 1));
        byte[] big = new byte[(int) LightsConfig.MAX_FILE_BYTES + 1];
        assertThrows(IOException.class, () -> LightsConfig.readText(new ByteArrayInputStream(big), 0));
    }
}
