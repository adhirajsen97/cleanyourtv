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
        assertEquals(6, config.lights.size());
        List<String> six = Arrays.asList("on", "off", "dimmer", "brighter", "warmer", "cooler");
        for (String id : new String[] {"all", "row-1", "row-2", "row-3"}) {
            Light light = config.find(id);
            assertNotNull(id, light);
            assertEquals(id, six, light.commands());
            assertEquals(id + " brighter: three presses recorded", 3, light.variants("brighter"));
            List<Packet> press = light.packets("brighter");
            assertEquals("an adjustment is a press and a release", 2, press.size());
            assertEquals(400, press.get(0).burstMs);
            Packet on = light.packets("on").get(0);
            assertEquals(Packet.Kind.SERVICE_DATA, on.kind);
            assertEquals(0x08F0, on.id);
            assertEquals(24, on.payload.length);
            assertTrue("format B goes out with the Flags record, as the remote sends it", on.connectable);
            assertEquals(0, on.burstMs);
        }
        assertEquals(Arrays.asList("on", "off"), config.find("row-4").commands());
        assertEquals(Arrays.asList("aid", "k", "moon", "rgb"), config.find("extras").commands());
        // The first ALL ON press of the capture comes first (a fresh parse: the loop above already rotated).
        assertEquals("10006A5925EC68A2B2FAEB7BC22B0F1EDD74C0021A21EA82",
                Hex.encode(LightsConfig.parse(bundled()).find("all").packets("on").get(0).payload));
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
        assertEquals("01", Hex.encode(light.packets("on").get(0).payload));
        List<Packet> second = light.packets("on");
        assertEquals(2, second.size());
        assertEquals("02", Hex.encode(second.get(0).payload));
        assertEquals(300, second.get(0).burstMs);
        assertEquals(0, second.get(1).burstMs);
        assertEquals("01", Hex.encode(light.packets("on").get(0).payload));
        assertTrue(light.packets("off").isEmpty());
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
        assertEquals(2, light.packets("on").size());
        Packet a = light.packets("on").get(1);
        assertEquals(Packet.Kind.MANUFACTURER_DATA, a.kind);
        assertEquals(0x5556, a.id);
        assertEquals(27, a.payload.length);
        assertFalse("27 bytes leave no room for Flags", a.connectable);
        assertTrue(light.packets("on").get(0).connectable);
        assertTrue(light.packets("off").get(0).connectable);
        assertEquals(4, light.packets("off").get(0).payload.length);

        LightsConfig legacy = LightsConfig.parse("{\"lights\":[" + light("a") + "]}");
        assertEquals(Arrays.asList("on", "off"), legacy.lights.get(0).commands());
    }

    @Test
    public void connectableCanBeSwitchedOffForAllOrPerPacket() {
        LightsConfig all = LightsConfig.parse("{\"connectable\":false,\"lights\":[" + light("a") + "]}");
        assertFalse(all.lights.get(0).packets("on").get(0).connectable);
        assertFalse(all.lights.get(0).packets("off").get(0).connectable);
        LightsConfig one = LightsConfig.parse("{\"connectable\":false,\"lights\":[{\"id\":\"a\","
                + "\"on\":[{\"service_data\":\"08F0\",\"data\":\"1000EA2D\",\"connectable\":true}],"
                + "\"off\":[" + B + "]}]}");
        assertTrue(one.lights.get(0).packets("on").get(0).connectable);
        assertFalse(one.lights.get(0).packets("off").get(0).connectable);
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
        assertMessage("light \"a\", on[0]: needs service_data or manufacturer_data",
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
