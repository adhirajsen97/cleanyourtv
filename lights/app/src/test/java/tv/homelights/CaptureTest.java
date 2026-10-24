package tv.homelights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CaptureTest {
    @Test
    public void countsCopiesPerStepAndWritesThemOut() throws Exception {   // org.json in android.jar throws a checked JSONException
        Capture c = new Capture("2026-10-24T15:00:00+05:30", "test tv", 1000);
        byte[] a = Hex.decode("0201021B16F0081000EA2D71611DCBAA79F270FE6376E486E88FD664E3AA48");
        byte[] b = Hex.decode("0201021B16F0081000B559C42541FDEF78E0E567050CC0555F4A1AE719D07D");
        c.beginStep("all-off", "Press ALL OFF 3 times", 1000);
        assertTrue(c.heard(a, "C1:00:00:00:00:01", -60, 1500));
        assertFalse(c.heard(a, "C1:00:00:00:00:01", -50, 1600));
        assertTrue(c.heard(b, "C1:00:00:00:00:01", -70, 2000));
        c.ignored();
        c.beginStep("all-on", "Press ALL ON 3 times", 3000);
        assertTrue("a new step starts counting afresh", c.heard(a, "C1:00:00:00:00:01", -55, 3200));
        c.endStep(4000);

        JSONObject json = c.toJson();
        assertEquals("test tv", json.getString("device"));
        JSONArray steps = json.getJSONArray("steps");
        assertEquals(2, steps.length());
        JSONObject off = steps.getJSONObject(0);
        assertEquals("all-off", off.getString("step"));
        assertEquals(3, off.getInt("copies"));
        assertEquals(1, off.getInt("ignored"));
        assertEquals(0.0, off.getDouble("began"), 1e-9);
        assertEquals(2.0, off.getDouble("ended"), 1e-9);
        JSONArray packets = off.getJSONArray("packets");
        assertEquals(2, packets.length());
        JSONObject first = packets.getJSONObject(0);
        assertEquals(2, first.getInt("count"));
        assertEquals(-50, first.getInt("rssi"));
        assertEquals(0.5, first.getDouble("first"), 1e-9);
        assertEquals(0.6, first.getDouble("last"), 1e-9);
        assertTrue(first.getString("records").startsWith("02 01 02 | 1B 16 F0 08 10 00 EA 2D"));
        assertEquals(3.0, steps.getJSONObject(1).getDouble("ended"), 1e-9);
        assertTrue(c.toText().contains("\"step\": \"all-on\""));
    }

    @Test
    public void aQuietStepTellsTheRoomsOwnPacketsFromTheRemotes() throws Exception {
        Capture c = new Capture("now", "tv", 0);
        byte[] phone = Hex.decode("02011A0AFF4C0010050B1C2B3C4D");
        byte[] phoneAgain = Hex.decode("02011A0AFF4C0010050B1C2B3C4E");
        byte[] anotherPhone = Hex.decode("02011A0BFF4C001007AABBCCDDEEFF020A00");   // other bytes, other address, same shape
        byte[] remote = Hex.decode("0201021B16F0081000B559C42541FDEF78E0E567050CC0555F4A1AE719D07D");
        c.beginStep("cove-quiet", "Press nothing", 0, true);
        assertTrue(c.heard(phone, "40:00:00:00:00:01", -70, 100));
        assertEquals(1, c.current().copies);
        c.beginStep("cove-on", "Press I", 10000, false);
        assertFalse("the same bytes as in the quiet step", c.heard(phone, "", -70, 10100));
        assertFalse("another packet from a sender heard in the quiet step", c.heard(phoneAgain, "40:00:00:00:00:01", -70, 10200));
        assertFalse("a packet of a shape heard in the quiet step", c.heard(anotherPhone, "5E:00:00:00:00:02", -80, 10250));
        assertTrue(c.heard(remote, "C1:00:00:00:00:01", -60, 10300));
        assertEquals(1, c.current().distinct());
        assertEquals("ignored packets are not copies", 1, c.current().copies);
        assertEquals(3, c.current().ignored);
        JSONArray steps = c.toJson().getJSONArray("steps");
        assertTrue(steps.getJSONObject(0).getBoolean("quiet"));
        assertFalse("only a quiet step says so", steps.getJSONObject(1).has("quiet"));
    }

    @Test
    public void theBaselineOutgrowsTheStepCap() {
        Capture c = new Capture("now", "tv", 0);
        c.beginStep("cove-quiet", "Press nothing", 0, true);
        for (int i = 0; i < 250; i++) {
            c.heard(new byte[] {3, 22, (byte) 0xF0, 8, (byte) i}, String.format("A0:00:00:00:00:%02X", i), -60, i);
        }
        assertEquals(Capture.MAX_PER_STEP, c.current().distinct());
        c.beginStep("cove-on", "Press I", 1000, false);
        assertFalse("the 250th packet is in the baseline though the step could not store it",
                c.heard(new byte[] {3, 22, (byte) 0xF0, 8, (byte) 249}, "", -60, 1100));
        assertFalse("so is its sender", c.heard(new byte[] {3, 22, (byte) 0xF0, 8, 1, 2}, "A0:00:00:00:00:F9", -60, 1200));
        assertTrue(c.heard(new byte[] {3, 22, (byte) 0xF0, 8, 1, 2}, "C1:00:00:00:00:01", -60, 1300));
    }

    @Test
    public void keepsAtMostTwoHundredDistinctPacketsPerStep() {
        Capture c = new Capture("now", "tv", 0);
        c.beginStep("x", "x", 0);
        for (int i = 0; i < 250; i++) c.heard(new byte[] {3, 22, (byte) 0xF0, 8, (byte) i}, "", -60, i);
        assertEquals(Capture.MAX_PER_STEP, c.current().distinct());
        assertEquals(250, c.current().copies);
    }
}
