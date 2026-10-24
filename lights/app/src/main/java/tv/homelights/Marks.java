package tv.homelights;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which rows the screen shows as On or Off. The lights send nothing back, so this only follows
 * what was pressed: All rows sets every row, a row's own On or Off sets that row, and All rows is
 * marked only while every row agrees with the last press.
 */
public final class Marks {
    /** The id of the light that stands for every row. */
    public static final String ALL = "all";

    private final Map<String, Boolean> state = new HashMap<>();

    /** Records a press of {@code on} (or off) on the light {@code id}; {@code rows} are the ids of the single rows. */
    public void set(String id, boolean on, List<String> rows) {
        if (ALL.equals(id)) {
            for (String row : rows) state.put(row, on);
            state.put(ALL, on);
            return;
        }
        state.put(id, on);
        boolean agree = true;
        for (String row : rows) {
            Boolean mark = state.get(row);
            if (mark == null || mark != on) {
                agree = false;
                break;
            }
        }
        if (agree) state.put(ALL, on);
        else state.remove(ALL);
    }

    /** True for On, false for Off, null when nothing is known. */
    public Boolean get(String id) {
        return state.get(id);
    }
}
