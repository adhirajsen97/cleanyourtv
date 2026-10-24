package tv.homelights;

import static org.junit.Assert.assertEquals;
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

public class LightsConfigTest {
    /** Gradle runs unit tests from the module directory; the shipped file sits in src/main/assets. */
    static final Path BUNDLED = Paths.get("src/main/assets/lights.json");

    static String bundled() throws IOException {
        return new String(Files.readAllBytes(BUNDLED), StandardCharsets.UTF_8);
    }

    @Test
    public void bundledFileParsesToTheDrawingRoomPackets() throws IOException {
        LightsConfig config = LightsConfig.parse(bundled());
        assertEquals(800, config.burstMs);
        assertEquals(1, config.lights.size());
        Light light = config.find("drawing-room");
        assertNotNull(light);
        assertEquals("Drawing room", light.name);
        assertEquals(1, light.on.size());
        assertEquals(1, light.off.size());
        Packet on = light.packets(true).get(0);
        assertEquals(Packet.Kind.SERVICE_DATA, on.kind);
        assertEquals(0x08F0, on.id);
        assertEquals("1000B559C42541FDEF78E0E567050CC0555F4A1AE719D07D", Hex.encode(on.payload));
        assertEquals("1000EA2D71611DCBAA79F270FE6376E486E88FD664E3AA48", Hex.encode(light.packets(false).get(0).payload));
        assertNull(config.find("kitchen"));
    }

    @Test
    public void manufacturerDataAndSeveralPacketsPerCommand() {
        LightsConfig config = LightsConfig.parse("{\"lights\":[{\"id\":\"all\",\"on\":["
                + "{\"service_data\":\"08F0\",\"data\":\"1000B559C42541FDEF78E0E567050CC0555F4A1AE719D07D\"},"
                + "{\"manufacturer_data\":\"0x5556\",\"data\":\"188752B65F2B5E00FC3151D0699F0824CBA5FC16C46CB22F9F6057\"}],"
                + "\"off\":[{\"manufacturer_data\":\"5556\",\"data\":\"18 87 52 B6\"}]}]}");
        assertEquals(LightsConfig.DEFAULT_BURST_MS, config.burstMs);
        Light light = config.lights.get(0);
        assertEquals("all", light.name);   // name falls back to the id
        assertEquals(2, light.on.size());
        Packet a = light.on.get(1);
        assertEquals(Packet.Kind.MANUFACTURER_DATA, a.kind);
        assertEquals(0x5556, a.id);
        assertEquals(27, a.payload.length);
        assertEquals(4, light.off.get(0).payload.length);
    }

    @Test
    public void errorsSayWhere() {
        assertMessage("not valid JSON", "nonsense");
        assertMessage("\"lights\" is missing or empty", "{}");
        assertMessage("\"lights\" is missing or empty", "{\"lights\":[]}");
        assertMessage("burst_ms must be 100 to 10000", "{\"burst_ms\":50,\"lights\":[]}");
        assertMessage("lights[0] has no \"id\"", "{\"lights\":[{\"name\":\"x\"}]}");
        assertMessage("two lights have the id \"a\"", "{\"lights\":[" + light("a") + "," + light("a") + "]}");
        assertMessage("light \"a\" has no \"off\" packets", "{\"lights\":[{\"id\":\"a\",\"on\":[" + B + "]}]}");
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
        byte[] withBom = ("﻿{\"a\":1}").getBytes(StandardCharsets.UTF_8);
        assertEquals("{\"a\":1}", LightsConfig.readText(new ByteArrayInputStream(withBom), withBom.length));
        assertThrows(IOException.class, () -> LightsConfig.readText(new ByteArrayInputStream(new byte[0]), LightsConfig.MAX_FILE_BYTES + 1));
        byte[] big = new byte[(int) LightsConfig.MAX_FILE_BYTES + 1];
        assertThrows(IOException.class, () -> LightsConfig.readText(new ByteArrayInputStream(big), 0));
    }
}
