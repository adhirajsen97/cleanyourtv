package tv.homelights;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A light, or a row of lights that answer the same packets, with the packets of each command it
 * knows. Commands are named in lights.json ("on", "off", "brighter", …); a row shows a button for
 * every command that has packets, so a row whose packets are not captured yet shows none.
 *
 * A command can have several variants, one per press recorded from the remote. Each carries its
 * own counter, and the lights ignore a packet whose counter they just saw, so {@link #packets}
 * hands out the variants in turn: two presses in a row never send the same bytes.
 */
public final class Light {
    /** Commands with a place of their own on the screen, in this order; other names follow them. */
    public static final List<String> KNOWN_COMMANDS = Collections.unmodifiableList(
            Arrays.asList("on", "off", "dimmer", "brighter", "warmer", "cooler"));

    public final String id;
    public final String name;
    private final Map<String, List<List<Packet>>> commands;
    private final Map<String, Integer> next = new HashMap<>();

    public Light(String id, String name, Map<String, List<List<Packet>>> commands) {
        this.id = id;
        this.name = name;
        Map<String, List<List<Packet>>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<List<Packet>>> e : commands.entrySet()) {
            List<List<Packet>> variants = new ArrayList<>();
            for (List<Packet> v : e.getValue()) variants.add(Collections.unmodifiableList(new ArrayList<>(v)));
            copy.put(e.getKey(), Collections.unmodifiableList(variants));
        }
        this.commands = Collections.unmodifiableMap(copy);
    }

    public boolean has(String command) {
        return commands.containsKey(command);
    }

    /** How many recorded presses a command has to rotate through; 0 for a command it lacks. */
    public int variants(String command) {
        List<List<Packet>> list = commands.get(command);
        return list == null ? 0 : list.size();
    }

    /**
     * The packets to advertise, one after another, for the next press of a command: the variants
     * in turn, round and round. Empty for a command this light does not know.
     */
    public List<Packet> packets(String command) {
        List<List<Packet>> variants = commands.get(command);
        if (variants == null) return Collections.emptyList();
        int i = next.getOrDefault(command, 0);
        next.put(command, (i + 1) % variants.size());
        return variants.get(i);
    }

    /** Known commands first, in their fixed order, then the others alphabetically. */
    public List<String> commands() {
        List<String> out = new ArrayList<>();
        for (String known : KNOWN_COMMANDS) if (commands.containsKey(known)) out.add(known);
        List<String> others = new ArrayList<>();
        for (String name : commands.keySet()) if (!out.contains(name)) others.add(name);
        Collections.sort(others);
        out.addAll(others);
        return out;
    }
}
