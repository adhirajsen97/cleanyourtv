package tv.homelights;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AdDataTest {
    @Test
    public void shapeIsTheRecordTypesWithMakerAndSubtype() {
        assertEquals("FF4C0010", AdData.shape(Hex.decode("02011A0AFF4C0010050B1C2B3C4D")));
        assertEquals("the length, the rest of the payload and a TX power record do not matter", "FF4C0010",
                AdData.shape(Hex.decode("02011A0BFF4C001007AABBCCDDEEFF020A00")));
        assertEquals("16F00810", AdData.shape(Hex.decode("0201021B16F0081000B559C42541FDEF78E0E567050CC0555F4A1AE719D07D")));
        assertEquals("FF4C0012", AdData.shape(Hex.decode("07FF4C0012020001")));
        assertEquals("two records", "030D18|095748", AdData.shape(Hex.decode("03030D180309574800")));
        assertEquals("a short record is taken whole", "0309", AdData.shape(Hex.decode("020309")));
        assertEquals("flags and TX power alone say nothing", "", AdData.shape(Hex.decode("02011A020A00")));
        assertEquals("", AdData.shape(new byte[0]));
    }

    /** Flags, then the lights' service data, then the zero padding a scanner adds. */
    static final byte[] PHONE_STYLE = Hex.decode("020102" + "1B16F008" + "1000EA2D71611DCBAA79F270FE6376E486E88FD664E3AA48" + "00000000");

    @Test
    public void significantDropsThePadding() {
        assertEquals(31, AdData.significant(PHONE_STYLE).length);
        byte[] exact = Hex.decode("0201021B16F0081000EA2D71611DCBAA79F270FE6376E486E88FD664E3AA48");
        assertArrayEquals(exact, AdData.significant(exact));
        assertEquals(0, AdData.significant(new byte[8]).length);
    }

    @Test
    public void recognisesBothFormatsAndNothingElse() {
        assertTrue(AdData.isLightPacket(PHONE_STYLE, 0x08F0, 0x5556));
        assertTrue(AdData.isLightPacket(Hex.decode("1EFF5655188752B65F2B5E00FC3151D0699F0824CBA5FC16C46CB22F9F6057"), 0x08F0, 0x5556));
        assertFalse(AdData.isLightPacket(Hex.decode("02010603031AFE"), 0x08F0, 0x5556));   // a Google Fast Pair beacon
        assertFalse(AdData.isLightPacket(Hex.decode("0516F1081000"), 0x08F0, 0x5556));      // another service UUID
        assertFalse(AdData.isLightPacket(new byte[0], 0x08F0, 0x5556));
    }

    @Test
    public void prettySeparatesTheRecords() {
        assertEquals("02 01 02 | 05 16 F0 08 10 00", AdData.pretty(Hex.decode("0201020516F0081000")));
    }
}
