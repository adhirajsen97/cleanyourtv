package tv.homelights;

import java.util.Locale;

/** Reads the records ("AD structures") of a raw advertisement, as a scanner hands them over. */
public final class AdData {
    private AdData() {}

    /**
     * The meaningful prefix of raw advertisement bytes: scanners pad the buffer with zeros, and a
     * zero length byte ends the records. Returns the input itself when every byte is a record.
     */
    public static byte[] significant(byte[] raw) {
        int i = 0;
        while (i < raw.length) {
            int len = raw[i] & 0xFF;
            if (len == 0 || i + 1 + len > raw.length) break;
            i += 1 + len;
        }
        if (i == raw.length) return raw;
        byte[] out = new byte[i];
        System.arraycopy(raw, 0, out, 0, i);
        return out;
    }

    /**
     * True when a record is service data under the 16-bit UUID or manufacturer data under the
     * company id; what the lights' remote sends, in either of its two formats.
     */
    public static boolean isLightPacket(byte[] raw, int serviceUuid, int companyId) {
        int i = 0;
        while (i + 1 < raw.length) {
            int len = raw[i] & 0xFF;
            if (len == 0 || i + 1 + len > raw.length) break;
            int type = raw[i + 1] & 0xFF;
            if (len >= 3) {
                int id = (raw[i + 2] & 0xFF) | ((raw[i + 3] & 0xFF) << 8);
                if (type == Packet.Kind.SERVICE_DATA.adType && id == serviceUuid) return true;
                if (type == Packet.Kind.MANUFACTURER_DATA.adType && id == companyId) return true;
            }
            i += 1 + len;
        }
        return false;
    }

    /** "02 01 02 | 1B 16 F0 08 …": the records separated for reading. */
    public static String pretty(byte[] raw) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < raw.length) {
            int len = raw[i] & 0xFF;
            int end = Math.min(raw.length, i + 1 + len);
            if (sb.length() > 0) sb.append(" | ");
            for (int j = i; j < end; j++) {
                if (j > i) sb.append(' ');
                sb.append(String.format(Locale.ROOT, "%02X", raw[j] & 0xFF));
            }
            if (len == 0) break;
            i = end;
        }
        return sb.toString();
    }
}
