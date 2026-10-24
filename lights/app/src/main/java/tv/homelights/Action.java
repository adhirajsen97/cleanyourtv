package tv.homelights;

/**
 * One thing a command does: a {@link Packet} put on air over Bluetooth LE, or a {@link Request}
 * sent to a bridge over the network. A command is a list of these, done one after another.
 */
public interface Action {
    /** A line for the log: "service data 08F0, 24 bytes, with flags", or "POST http://…". */
    String describe();
}
