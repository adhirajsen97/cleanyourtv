package tv.homelights;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Puts the lights' packets on air as Bluetooth LE advertisements, the way nRF Connect's Advertiser
 * does: one record per advertisement, no device name, no TX power, connectable when the packet says
 * so (Android then puts the Flags record in front, as a phone app's packet has), at the fastest
 * interval Android offers (about 100 ms), for a burst of {@code burstMs}. The packets of one command
 * go on air one after another. One command runs at a time; a new one stops the running burst, so the
 * last button pressed wins. Everything is logged under {@link #TAG}.
 */
public final class Advertiser {
    public static final String TAG = "HomeLights";

    /** What stands between the app and the first packet. */
    public enum Blocker { NO_BLUETOOTH, BLUETOOTH_OFF, NO_ADVERTISER, NO_PERMISSION }

    public interface Listener {
        /** Packet {@code index} of {@code count} is going on air (1-based). */
        void onProgress(int index, int count);

        void onDone();

        void onError(String reason);
    }

    /** Runtime permissions on Android 12 and newer; nothing to ask for before that. */
    public static final String[] RUNTIME_PERMISSIONS = {
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT };

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Burst current;

    public Advertiser(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static boolean needsRuntimePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public boolean hasPermission() {
        return !needsRuntimePermission()
                || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = ctx.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    /** Null when a packet can go on air right now. Checked before every command. */
    public Blocker blocker() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return Blocker.NO_BLUETOOTH;
        if (!adapter.isEnabled()) return Blocker.BLUETOOTH_OFF;
        // Needs no permission, and tells a radio with no advertiser role apart before asking for one.
        if (adapter.getBluetoothLeAdvertiser() == null) return Blocker.NO_ADVERTISER;
        if (!hasPermission()) return Blocker.NO_PERMISSION;
        return null;
    }

    /** A few facts for the status line, e.g. "Android 12, multi-advertising yes, extended no". */
    public String capabilities() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return "";
        return String.format(Locale.ROOT, "Android %s, multi-advertising %s, extended %s", Build.VERSION.RELEASE,
                yesNo(adapter.isMultipleAdvertisementSupported()), yesNo(adapter.isLeExtendedAdvertisingSupported()));
    }

    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    /** Advertises each packet in turn for {@code burstMs} (or the packet's own); a command still running is stopped first. */
    public void send(List<Packet> packets, int burstMs, Listener listener) {
        cancel();
        BluetoothAdapter adapter = adapter();
        BluetoothLeAdvertiser advertiser = adapter == null ? null : adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            listener.onError("Bluetooth advertiser unavailable");
            return;
        }
        current = new Burst(advertiser, new ArrayList<>(packets), burstMs, listener);
        current.next();
    }

    public void cancel() {
        if (current != null) {
            current.cancel();
            current = null;
        }
    }

    /** The one record of an advertisement, encoded by Android exactly as nRF Connect encodes it. */
    static AdvertiseData data(Packet packet) {
        AdvertiseData.Builder builder = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false);
        if (packet.kind == Packet.Kind.SERVICE_DATA) builder.addServiceData(uuid16(packet.id), packet.payload);
        else builder.addManufacturerData(packet.id, packet.payload);
        return builder.build();
    }

    /** A 16-bit UUID in the Bluetooth base; Android writes it as the 2-byte form on air. */
    static ParcelUuid uuid16(int id) {
        return ParcelUuid.fromString(String.format(Locale.ROOT, "0000%04X-0000-1000-8000-00805F9B34FB", id));
    }

    static String errorText(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "packet too large for a legacy advertisement (31 bytes)";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "no free advertiser slot (another app is advertising)";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "advertising already started";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "Bluetooth stack error";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "this TV's Bluetooth cannot advertise (FEATURE_UNSUPPORTED)";
            default:
                return "advertising failed (code " + code + ")";
        }
    }

    /** One command: its packets on air one after another, each for burstMs. */
    private final class Burst extends AdvertiseCallback {
        private final BluetoothLeAdvertiser advertiser;
        private final List<Packet> packets;
        private final int burstMs;
        private final Listener listener;
        private final Runnable finishPacket = this::finishPacket;
        private int index = -1;
        private boolean onAir;
        private boolean cancelled;

        Burst(BluetoothLeAdvertiser advertiser, List<Packet> packets, int burstMs, Listener listener) {
            this.advertiser = advertiser;
            this.packets = packets;
            this.burstMs = burstMs;
            this.listener = listener;
        }

        void next() {
            index++;
            if (index >= packets.size()) {
                finish();
                listener.onDone();
                return;
            }
            Packet packet = packets.get(index);
            int burst = packet.burstMs > 0 ? packet.burstMs : burstMs;
            listener.onProgress(index + 1, packets.size());
            Log.i(TAG, "advertising " + packet.describe() + " for " + burst + " ms: "
                    + (packet.connectable ? "[flags, added by Android] " : "") + Hex.encode(packet.adBytes()));
            // No stack timeout: the burst ends with stop() below. A timeout would also make Android
            // write the Flags as "limited discoverable" (02 01 01) instead of the usual 02 01 02.
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(packet.connectable)
                    .setTimeout(0)
                    .build();
            try {
                advertiser.startAdvertising(settings, data(packet), this);
                onAir = true;
            } catch (SecurityException e) {
                fail("no permission to advertise (" + e.getMessage() + ")");
            } catch (IllegalArgumentException | IllegalStateException e) {
                fail(String.valueOf(e.getMessage()));
            }
        }

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            if (cancelled) return;
            Packet packet = packets.get(index);
            handler.postDelayed(finishPacket, packet.burstMs > 0 ? packet.burstMs : burstMs);
        }

        @Override
        public void onStartFailure(int errorCode) {
            if (cancelled) return;
            onAir = false;
            fail(errorText(errorCode));
        }

        private void finishPacket() {
            if (cancelled) return;
            stop();
            next();
        }

        private void fail(String reason) {
            Log.w(TAG, "advertising failed: " + reason);
            finish();
            listener.onError(reason);
        }

        /** Done or failed: nothing more goes on air for this command. */
        private void finish() {
            stop();
            if (current == this) current = null;
        }

        void cancel() {
            cancelled = true;
            stop();
        }

        private void stop() {
            handler.removeCallbacks(finishPacket);
            if (!onAir) return;
            onAir = false;
            try {
                advertiser.stopAdvertising(this);
            } catch (SecurityException | IllegalStateException e) {
                Log.w(TAG, "stopAdvertising: " + e.getMessage());
            }
        }
    }
}
