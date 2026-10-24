package tv.homelights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class MarksTest {
    static final List<String> ROWS = Arrays.asList("row-1", "row-2", "row-3");

    @Test
    public void allRowsOverridesEveryRowAndARowUnmarksAllUntilTheyAgree() {
        Marks m = new Marks();
        m.set("row-1", true, ROWS);
        assertEquals(Boolean.TRUE, m.get("row-1"));
        assertNull("other rows unknown, so All rows is not marked", m.get(Marks.ALL));

        m.set(Marks.ALL, false, ROWS);
        for (String row : ROWS) assertEquals(Boolean.FALSE, m.get(row));
        assertEquals(Boolean.FALSE, m.get(Marks.ALL));

        m.set("row-2", true, ROWS);
        assertEquals(Boolean.TRUE, m.get("row-2"));
        assertEquals(Boolean.FALSE, m.get("row-1"));
        assertNull("rows disagree", m.get(Marks.ALL));

        m.set("row-1", true, ROWS);
        m.set("row-3", true, ROWS);
        assertEquals("every row on again", Boolean.TRUE, m.get(Marks.ALL));

        m.set(Marks.ALL, true, ROWS);
        for (String row : ROWS) assertEquals(Boolean.TRUE, m.get(row));
    }
}
