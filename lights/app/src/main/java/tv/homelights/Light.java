package tv.homelights;

import java.util.Collections;
import java.util.List;

/** A light, or a group of lights that answer the same packets, with the packets for On and Off. */
public final class Light {
    public final String id;
    public final String name;
    public final List<Packet> on;
    public final List<Packet> off;

    public Light(String id, String name, List<Packet> on, List<Packet> off) {
        this.id = id;
        this.name = name;
        this.on = Collections.unmodifiableList(on);
        this.off = Collections.unmodifiableList(off);
    }

    /** The packets to advertise, one after another, to switch the light on or off. */
    public List<Packet> packets(boolean turnOn) {
        return turnOn ? on : off;
    }
}
