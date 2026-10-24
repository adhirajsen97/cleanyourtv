package tv.homelights;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class HexTest {
    @Test
    public void decodesUpperLowerSpacesColonsAndPrefix() {
        byte[] want = {0x10, 0x00, (byte) 0xEA, 0x2D};
        assertArrayEquals(want, Hex.decode("1000EA2D"));
        assertArrayEquals(want, Hex.decode("1000ea2d"));
        assertArrayEquals(want, Hex.decode("10 00 EA 2D"));
        assertArrayEquals(want, Hex.decode("10:00:ea:2d"));
        assertArrayEquals(want, Hex.decode("0x1000EA2D"));
        assertArrayEquals(want, Hex.decode("  1000EA2D\n"));
    }

    @Test
    public void roundTrips() {
        String text = "188752B65F2B5E00FC315150699F0824CB25FCD705337390046057";
        assertEquals(text, Hex.encode(Hex.decode(text)));
    }

    @Test
    public void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Hex.decode(null));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode(""));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("0x"));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("ABC"));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("ZZ"));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("12-34"));
    }
}
