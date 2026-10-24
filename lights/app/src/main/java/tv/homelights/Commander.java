package tv.homelights;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a command: its actions one after another, packets on air through the {@link Advertiser}
 * (a run of consecutive packets as one advertising sequence) and requests through {@link Http}.
 * One command at a time; a new one stops the one still running, so the last button pressed wins.
 */
public final class Commander {
    public interface Listener {
        /** Action {@code index} of {@code count} is being done (1-based). */
        void onProgress(int index, int count);

        void onDone();

        void onError(String reason);
    }

    private final Advertiser advertiser;
    private final Http http;
    private Run current;

    public Commander(Advertiser advertiser, Http http) {
        this.advertiser = advertiser;
        this.http = http;
    }

    public void run(List<Action> actions, int burstMs, Listener listener) {
        cancel();
        current = new Run(new ArrayList<>(actions), burstMs, listener);
        current.next();
    }

    public void cancel() {
        if (current != null) {
            current.cancelled = true;
            current = null;
        }
        advertiser.cancel();
    }

    private final class Run {
        private final List<Action> actions;
        private final int burstMs;
        private final Listener listener;
        private int index;
        boolean cancelled;

        Run(List<Action> actions, int burstMs, Listener listener) {
            this.actions = actions;
            this.burstMs = burstMs;
            this.listener = listener;
        }

        void next() {
            if (cancelled) return;
            if (index >= actions.size()) {
                finish();
                listener.onDone();
                return;
            }
            if (actions.get(index) instanceof Packet) {
                // The Advertiser reports each packet as it goes on air; nothing to say before that.
                List<Packet> packets = new ArrayList<>();
                int end = index;
                while (end < actions.size() && actions.get(end) instanceof Packet) packets.add((Packet) actions.get(end++));
                final int first = index;
                final int after = end;
                advertiser.send(packets, burstMs, new Advertiser.Listener() {
                    @Override
                    public void onProgress(int i, int count) {
                        if (!cancelled) listener.onProgress(first + i, actions.size());
                    }

                    @Override
                    public void onDone() {
                        if (cancelled) return;
                        index = after;
                        next();
                    }

                    @Override
                    public void onError(String reason) {
                        if (!cancelled) fail(reason);
                    }
                });
                return;
            }
            Request request = (Request) actions.get(index);
            listener.onProgress(index + 1, actions.size());
            Log.i(Advertiser.TAG, "requesting " + request.describe());
            http.send(request, error -> {
                if (cancelled) return;
                if (error != null) {
                    fail(error);
                    return;
                }
                index++;
                next();
            });
        }

        private void fail(String reason) {
            Log.w(Advertiser.TAG, "command failed: " + reason);
            finish();
            listener.onError(reason);
        }

        private void finish() {
            if (current == this) current = null;
        }
    }
}
