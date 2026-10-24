package tv.homelights;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The screen: one row per light from lights.json with On and Off, and a status line underneath.
 * Also takes a scripted command from adb, for testing without the remote:
 * <pre>
 *   am start -n tv.homelights/.MainActivity -e light drawing-room -e state on
 * </pre>
 * The outcome shows on the status line and in logcat under the tag HomeLights.
 */
public final class MainActivity extends Activity {
    static final String EXTRA_LIGHT = "light";
    static final String EXTRA_STATE = "state";
    private static final int REQUEST_PERMISSION = 1;
    private static final int REQUEST_ENABLE_BLUETOOTH = 2;

    private Advertiser advertiser;
    private LightsConfig config;
    private TextView status;
    private final Map<String, View> rows = new HashMap<>();
    /** The command waiting for the permission dialog or the Bluetooth switch. */
    private Light pendingLight;
    private boolean pendingOn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        advertiser = new Advertiser(this);
        String note = loadConfig();
        buildRows();
        showReadiness(note);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        advertiser.cancel();
        super.onDestroy();
    }

    /**
     * lights.json: the file pushed to the TV when there is one and it parses, else the built-in list.
     * Returns a line for the status display, or null when there is nothing to say.
     */
    private String loadConfig() {
        String note = null;
        File dir = getExternalFilesDir(null);
        File file = dir == null ? null : new File(dir, LightsConfig.FILE_NAME);
        if (file != null && file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                config = LightsConfig.parse(LightsConfig.readText(in, file.length()));
                Log.i(Advertiser.TAG, "using " + file);
                return getString(R.string.status_override_in_use);
            } catch (Exception e) {
                Log.w(Advertiser.TAG, "ignoring " + file + ": " + e.getMessage());
                note = getString(R.string.status_override_ignored, e.getMessage());
            }
        }
        try (InputStream in = getAssets().open(LightsConfig.FILE_NAME)) {
            config = LightsConfig.parse(LightsConfig.readText(in, 0));
        } catch (Exception e) {
            // The unit tests check the built-in file, so this is a build problem; say so plainly.
            Log.e(Advertiser.TAG, "built-in lights.json: " + e.getMessage());
            config = null;
            note = getString(R.string.status_config_error, e.getMessage());
        }
        return note;
    }

    private void buildRows() {
        LinearLayout list = findViewById(R.id.rows);
        list.removeAllViews();
        rows.clear();
        if (config == null) return;
        for (Light light : config.lights) {
            View row = getLayoutInflater().inflate(R.layout.item_light, list, false);
            ((TextView) row.findViewById(R.id.name)).setText(light.name);
            row.findViewById(R.id.on).setOnClickListener(v -> send(light, true, true));
            row.findViewById(R.id.off).setOnClickListener(v -> send(light, false, true));
            list.addView(row);
            rows.put(light.id, row);
        }
        if (list.getChildCount() > 0) list.getChildAt(0).findViewById(R.id.on).requestFocus();
    }

    /** A command sent with adb: -e light <id> -e state on|off. */
    private void handleIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_LIGHT)) return;
        String id = intent.getStringExtra(EXTRA_LIGHT);
        String state = intent.getStringExtra(EXTRA_STATE);
        // Not replayed if the screen is rebuilt later.
        intent.removeExtra(EXTRA_LIGHT);
        intent.removeExtra(EXTRA_STATE);
        Light light = config == null || id == null ? null : config.find(id);
        if (light == null) {
            report(getString(R.string.status_unknown_light, String.valueOf(id)));
            return;
        }
        boolean on = "on".equalsIgnoreCase(state);
        if (!on && !"off".equalsIgnoreCase(state)) {
            report(getString(R.string.status_bad_state, String.valueOf(state)));
            return;
        }
        send(light, on, true);
    }

    /** Switches a light; with {@code mayPrompt}, asks for Bluetooth or the permission when missing. */
    private void send(Light light, boolean on, boolean mayPrompt) {
        pendingLight = null;
        Advertiser.Blocker blocker = advertiser.blocker();
        if (blocker != null) {
            unblock(blocker, light, on, mayPrompt);
            return;
        }
        String what = light.name + " " + getString(on ? R.string.on : R.string.off);
        markState(light, on);
        Log.i(Advertiser.TAG, "sending " + what);
        advertiser.send(light.packets(on), config.burstMs, new Advertiser.Listener() {
            @Override
            public void onProgress(int index, int count) {
                report(getString(R.string.status_sending, what, index, count));
            }

            @Override
            public void onDone() {
                report(getString(R.string.status_sent, what));
            }

            @Override
            public void onError(String reason) {
                report(getString(R.string.status_failed, what, reason));
            }
        });
    }

    /** The last command is shown as the selected button; the lights send nothing back. */
    private void markState(Light light, boolean on) {
        View row = rows.get(light.id);
        if (row == null) return;
        row.findViewById(R.id.on).setSelected(on);
        row.findViewById(R.id.off).setSelected(!on);
    }

    private void unblock(Advertiser.Blocker blocker, Light light, boolean on, boolean mayPrompt) {
        report(blockerText(blocker));
        if (!mayPrompt) return;
        switch (blocker) {
            case NO_PERMISSION:
                pendingLight = light;
                pendingOn = on;
                requestPermissions(Advertiser.RUNTIME_PERMISSIONS, REQUEST_PERMISSION);
                break;
            case BLUETOOTH_OFF:
                pendingLight = light;
                pendingOn = on;
                try {
                    startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
                } catch (SecurityException | ActivityNotFoundException e) {
                    // Without BLUETOOTH_CONNECT, or on a TV without that dialog: the settings page instead.
                    pendingLight = null;
                    report(getString(R.string.status_bluetooth_settings));
                    try {
                        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    } catch (ActivityNotFoundException ignored) {
                        // Nothing to open; the status line already says what to do.
                    }
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSION) return;
        if (advertiser.hasPermission()) {
            resumePending();
        } else {
            pendingLight = null;
            report(getString(R.string.status_permission_denied));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ENABLE_BLUETOOTH) return;
        if (resultCode == RESULT_OK) {
            // The radio takes a moment to come up after the dialog closes.
            status.postDelayed(this::resumePending, 1500);
        } else {
            pendingLight = null;
            showReadiness(null);
        }
    }

    /** Runs the command that waited for a dialog, without asking twice. */
    private void resumePending() {
        Light light = pendingLight;
        pendingLight = null;
        if (light != null) send(light, pendingOn, false);
        else showReadiness(null);
    }

    private void showReadiness(String note) {
        Advertiser.Blocker blocker = advertiser.blocker();
        String text;
        if (blocker != null) text = blockerText(blocker);
        else if (advertiser.capabilities().isEmpty()) text = getString(R.string.status_ready);
        else text = getString(R.string.status_ready_detail, advertiser.capabilities());
        report(note == null ? text : note + "\n" + text);
    }

    private String blockerText(Advertiser.Blocker blocker) {
        switch (blocker) {
            case NO_BLUETOOTH:
                return getString(R.string.status_no_bluetooth);
            case BLUETOOTH_OFF:
                return getString(R.string.status_bluetooth_off);
            case NO_ADVERTISER:
                return getString(R.string.status_no_advertiser);
            case NO_PERMISSION:
            default:
                return getString(R.string.status_no_permission);
        }
    }

    private void report(String text) {
        status.setText(text);
        Log.i(Advertiser.TAG, text.replace('\n', ' '));
    }
}
