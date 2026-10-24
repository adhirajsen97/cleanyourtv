package tv.homelights;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class PacketTest {
    /** The ALL ON packets from nRF Connect: format A (manufacturer data) and format B (service data). */
    static final String FORMAT_A_ON = "188752B65F2B5E00FC3151D0699F0824CBA5FC16C46CB22F9F6057";
    static final String FORMAT_B_ON = "1000B559C42541FDEF78E0E567050CC0555F4A1AE719D07D";

    @Test
    public void formatBFitsWithRoomToSpare() {
        Packet p = new Packet(Packet.Kind.SERVICE_DATA, 0x08F0, Hex.decode(FORMAT_B_ON));
        assertEquals(24, p.payload.length);
        assertEquals(28, p.adLength());
        assertEquals("service data 08F0, 24 bytes", p.describe());
    }

    @Test
    public void formatAFillsTheLegacyAdvertisementExactly() {
        Packet p = new Packet(Packet.Kind.MANUFACTURER_DATA, 0x5556, Hex.decode(FORMAT_A_ON));
        assertEquals(27, p.payload.length);
        assertEquals(Packet.LEGACY_MAX_BYTES, p.adLength());
    }

    @Test
    public void adBytesAreLengthTypeLittleEndianIdThenPayload() {
        Packet p = new Packet(Packet.Kind.SERVICE_DATA, 0x08F0, Hex.decode("1000EA2D"));
        // length 7 = type + 2 id bytes + 4 payload bytes; 0x16 service data; UUID 0x08F0 as F0 08.
        assertArrayEquals(Hex.decode("07 16 F0 08 1000EA2D"), p.adBytes());
        Packet m = new Packet(Packet.Kind.MANUFACTURER_DATA, 0x5556, Hex.decode("AB"));
        assertArrayEquals(Hex.decode("04 FF 56 55 AB"), m.adBytes());
    }

    @Test
    public void oneByteTooMuchIsRefused() {
        byte[] max = new byte[Packet.MAX_PAYLOAD];
        new Packet(Packet.Kind.MANUFACTURER_DATA, 0x5556, max);
        byte[] over = new byte[Packet.MAX_PAYLOAD + 1];
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Packet(Packet.Kind.MANUFACTURER_DATA, 0x5556, over));
        assertEquals("manufacturer data payload is 28 bytes; a legacy advertisement fits 27 (31 with the record header)",
                e.getMessage());
    }

    @Test
    public void rejectsEmptyPayloadAndWideIds() {
        assertThrows(IllegalArgumentException.class, () -> new Packet(Packet.Kind.SERVICE_DATA, 0x08F0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Packet(Packet.Kind.SERVICE_DATA, 0x10000, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new Packet(Packet.Kind.SERVICE_DATA, -1, new byte[1]));
    }

    @Test
    public void payloadIsCopied() {
        byte[] data = Hex.decode("AABB");
        Packet p = new Packet(Packet.Kind.SERVICE_DATA, 1, data);
        data[0] = 0;
        assertEquals((byte) 0xAA, p.payload[0]);
    }
}
