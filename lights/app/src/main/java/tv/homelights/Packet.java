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
 * A legacy advertisement holds 31 bytes in all. The app sends one record per advertisement,
 * non-connectable and without a device name or TX power, so the record is the whole advertisement
 * and the payload may be at most 27 bytes. (Android adds a 3-byte Flags record to connectable
 * advertisements only, which is why they are sent non-connectable.)
 */
public final class Packet {

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

    public final Kind kind;
    /** Service UUID or company identifier, 0 to 0xFFFF. */
    public final int id;
    public final byte[] payload;

    public Packet(Kind kind, int id, byte[] payload) {
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

    public String describe() {
        return String.format(Locale.ROOT, "%s %04X, %d bytes", kind.label, id, payload.length);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Packet)) return false;
        Packet p = (Packet) o;
        return kind == p.kind && id == p.id && Arrays.equals(payload, p.payload);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * kind.hashCode() + id) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return describe();
    }
}
