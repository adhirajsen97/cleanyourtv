package tv.homelights;

/** Hex text to bytes and back. Accepts upper or lower case, spaces, colons and a 0x prefix. */
public final class Hex {
    private Hex() {}

    public static byte[] decode(String text) {
        if (text == null) throw new IllegalArgumentException("no hex data");
        String clean = text.trim();
        if (clean.startsWith("0x") || clean.startsWith("0X")) clean = clean.substring(2);
        clean = clean.replaceAll("[\\s:]", "");
        if (clean.isEmpty()) throw new IllegalArgumentException("empty hex data");
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("odd number of hex digits (" + clean.length() + ")");
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i++) {
            if (Character.digit(clean.charAt(i), 16) < 0) {
                throw new IllegalArgumentException("not a hex digit: '" + clean.charAt(i) + "'");
            }
        }
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(2 * i), 16);
            int lo = Character.digit(clean.charAt(2 * i + 1), 16);
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    public static String encode(byte[] bytes) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(digits[(b >> 4) & 0xF]).append(digits[b & 0xF]);
        }
        return sb.toString();
    }
}
