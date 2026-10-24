package tv.homelights;

import java.util.Arrays;
import java.util.Locale;

/**
 * One record ("AD structure") of a Bluetooth LE advertisement, in the two shapes the lights accept.
 * Both carry a 16-bit identifier ahead of the payload:
 * <pre>
 *   SERVICE_DATA        AD type 0x16, 16-bit service UUID, payload    nRF Connect: "Service Data"
 *   MANUFACTURER_DATA   AD type 0xFF, company identifier, payload     nRF Connect: "Manufacturer Data"
 * </pre>
 * A legacy advertisement holds 31 bytes in all. The app sends one record per advertisement, without
 * a device name or TX power, so the payload may be at most 27 bytes. A {@link #connectable} packet
 * goes out the way a phone app sends it: Android puts the 3-byte Flags record ({@code 02 01 02})
 * in front, which leaves 24 bytes of payload. Format B is exactly that size, so it is sent with
 * Flags; format A (27 bytes) has no room for them and goes out non-connectable, without.
 */
public final class Packet implements Action {

    public enum Kind {
        SERVICE_DATA(0x16, "service_data", "service data"),
        MANUFACTURER_DATA(0xFF, "manufacturer_data", "manufacturer data");

        /** The AD type byte on air. */
        public final int adType;
        /** The key in lights.json whose value is the 16-bit identifier. */
        public final String key;
        public final String label;

        Kind(int adType, String key, String label) {
            this.adType = adType;
            this.key = key;
            this.label = label;
        }
    }

    public static final int LEGACY_MAX_BYTES = 31;
    /** Length byte, AD type byte and the 16-bit identifier. */
    public static final int OVERHEAD = 4;
    public static final int MAX_PAYLOAD = LEGACY_MAX_BYTES - OVERHEAD;
    /** The Flags record Android adds in front of a connectable advertisement. */
    public static final int FLAGS_BYTES = 3;
    public static final int MAX_CONNECTABLE_PAYLOAD = MAX_PAYLOAD - FLAGS_BYTES;

    public final Kind kind;
    /** Service UUID or company identifier, 0 to 0xFFFF. */
    public final int id;
    public final byte[] payload;
    /** Sent connectable, so Android puts the Flags record in front; false when the payload leaves no room. */
    public final boolean connectable;
    /** How long this packet stays on air, in ms; 0 means the file's burst_ms. */
    public final int burstMs;

    public Packet(Kind kind, int id, byte[] payload, boolean connectable) {
        this(kind, id, payload, connectable, 0);
    }

    public Packet(Kind kind, int id, byte[] payload, boolean connectable, int burstMs) {
        if (kind == null) throw new IllegalArgumentException("no packet kind");
        if (id < 0 || id > 0xFFFF) throw new IllegalArgumentException("identifier is not 16-bit: " + id);
        if (payload == null || payload.length == 0) throw new IllegalArgumentException("empty payload");
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s payload is %d bytes; a legacy advertisement fits %d (%d with the record header)",
                    kind.label, payload.length, MAX_PAYLOAD, LEGACY_MAX_BYTES));
        }
        this.kind = kind;
        this.id = id;
        this.payload = payload.clone();
        this.connectable = connectable && payload.length <= MAX_CONNECTABLE_PAYLOAD;
        this.burstMs = Math.max(0, burstMs);
    }

    /** Bytes on air: the record, plus the Flags record when connectable. */
    public int airLength() {
        return adLength() + (connectable ? FLAGS_BYTES : 0);
    }

    /** Size of the record on air, header included. */
    public int adLength() {
        return OVERHEAD + payload.length;
    }

    /** The record as it goes on air: length, AD type, identifier little-endian, payload. */
    public byte[] adBytes() {
        byte[] out = new byte[adLength()];
        out[0] = (byte) (adLength() - 1);
        out[1] = (byte) kind.adType;
        out[2] = (byte) (id & 0xFF);
        out[3] = (byte) (id >> 8);
        System.arraycopy(payload, 0, out, OVERHEAD, payload.length);
        return out;
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%s %04X, %d bytes, %s", kind.label, id, payload.length,
                connectable ? "with flags" : "no flags");
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Packet)) return false;
        Packet p = (Packet) o;
        return kind == p.kind && id == p.id && connectable == p.connectable && Arrays.equals(payload, p.payload);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * kind.hashCode() + id) + (connectable ? 1 : 0)) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return describe();
    }
}
