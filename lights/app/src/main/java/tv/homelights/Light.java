package tv.homelights;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A light, or a row of lights that answer the same packets, with the packets of each command it
 * knows. Commands are named in lights.json ("on", "off", "brighter", …); a row shows a button for
 * every command that has packets, so a row whose packets are not captured yet shows none.
 */
public final class Light {
    /** Commands with a place of their own on the screen, in this order; other names follow them. */
    public static final List<String> KNOWN_COMMANDS = Collections.unmodifiableList(
            Arrays.asList("on", "off", "dimmer", "brighter", "warmer", "cooler"));

    public final String id;
    public final String name;
    private final Map<String, List<Packet>> commands;

    public Light(String id, String name, Map<String, List<Packet>> commands) {
        this.id = id;
        this.name = name;
        Map<String, List<Packet>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Packet>> e : commands.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        this.commands = Collections.unmodifiableMap(copy);
    }

    public boolean has(String command) {
        return commands.containsKey(command);
    }

    /** The packets to advertise, one after another; empty for a command this light does not know. */
    public List<Packet> packets(String command) {
        List<Packet> list = commands.get(command);
        return list == null ? Collections.emptyList() : list;
    }

    /** Known commands first, in their fixed order, then the others as the file lists them. */
    public List<String> commands() {
        List<String> out = new ArrayList<>();
        for (String known : KNOWN_COMMANDS) if (commands.containsKey(known)) out.add(known);
        for (String name : commands.keySet()) if (!out.contains(name)) out.add(name);
        return out;
    }
}
