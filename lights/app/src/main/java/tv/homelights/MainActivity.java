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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The screen: one row per light from lights.json, a button for every command that has packets,
 * and a status line underneath. Also takes a scripted command from adb, for testing without the
 * remote:
 * <pre>
 *   am start -n tv.homelights/.MainActivity -e light all -e command off
 * </pre>
 * The outcome shows on the status line and in logcat under the tag HomeLights.
 */
public final class MainActivity extends Activity {
    static final String EXTRA_LIGHT = "light";
    static final String EXTRA_COMMAND = "command";
    /** The first version's name for the on/off extra; still accepted. */
    static final String EXTRA_STATE = "state";
    private static final int REQUEST_PERMISSION = 1;
    private static final int REQUEST_ENABLE_BLUETOOTH = 2;

    private Advertiser advertiser;
    private LightsConfig config;
    private TextView status;
    private final Map<String, View> rows = new HashMap<>();
    private final Marks marks = new Marks();
    /** The command waiting for the permission dialog or the Bluetooth switch. */
    private Light pendingLight;
    private String pendingCommand;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        findViewById(R.id.learn).setOnClickListener(v -> startActivity(new Intent(this, LearnActivity.class)));
        findViewById(R.id.learn_cove).setOnClickListener(v -> startActivity(
                new Intent(this, LearnActivity.class).putExtra(LearnActivity.EXTRA_STEPS, LearnSteps.COVE_REMOTE.id)));
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
        View firstButton = null;
        if (config != null) {
            for (Light light : config.lights) {
                View row = getLayoutInflater().inflate(R.layout.item_light, list, false);
                ((TextView) row.findViewById(R.id.name)).setText(light.name);
                LinearLayout buttons = row.findViewById(R.id.buttons);
                List<String> commands = light.commands();
                if (commands.isEmpty()) row.findViewById(R.id.hint).setVisibility(View.VISIBLE);
                for (String command : commands) {
                    TextView button = (TextView) getLayoutInflater().inflate(R.layout.item_command, buttons, false);
                    button.setText(label(command));
                    button.setTag(command);
                    button.setOnClickListener(v -> send(light, command, true));
                    buttons.addView(button);
                    if (firstButton == null) firstButton = button;
                }
                list.addView(row);
                rows.put(light.id, row);
            }
        }
        View focus = firstButton != null ? firstButton : findViewById(R.id.learn);
        focus.requestFocus();
    }

    /** The button text for a command: the known ones translated, any other name tidied up. */
    private String label(String command) {
        switch (command) {
            case "on": return getString(R.string.on);
            case "off": return getString(R.string.off);
            case "dimmer": return getString(R.string.dimmer);
            case "brighter": return getString(R.string.brighter);
            case "warmer": return getString(R.string.warmer);
            case "cooler": return getString(R.string.cooler);
            default:
                String words = command.replace('-', ' ');
                return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
        }
    }

    /** A command sent with adb: -e light <id> -e command <name> (or the older -e state on|off). */
    private void handleIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_LIGHT)) return;
        String id = intent.getStringExtra(EXTRA_LIGHT);
        String command = intent.getStringExtra(EXTRA_COMMAND);
        if (command == null) command = intent.getStringExtra(EXTRA_STATE);
        // Not replayed if the screen is rebuilt later.
        intent.removeExtra(EXTRA_LIGHT);
        intent.removeExtra(EXTRA_COMMAND);
        intent.removeExtra(EXTRA_STATE);
        Light light = config == null || id == null ? null : config.find(id);
        if (light == null) {
            report(getString(R.string.status_unknown_light, String.valueOf(id)));
            return;
        }
        if (command == null || !light.has(command.toLowerCase(Locale.ROOT))) {
            report(getString(R.string.status_unknown_command, light.name, String.valueOf(command)));
            return;
        }
        send(light, command.toLowerCase(Locale.ROOT), true);
    }

    /** Sends a command; with {@code mayPrompt}, asks for Bluetooth or the permission when missing. */
    private void send(Light light, String command, boolean mayPrompt) {
        pendingLight = null;
        Advertiser.Blocker blocker = advertiser.blocker();
        if (blocker != null) {
            unblock(blocker, light, command, mayPrompt);
            return;
        }
        String what = light.name + " " + label(command);
        markState(light, command);
        Log.i(Advertiser.TAG, "sending " + what);
        advertiser.send(light.packets(command), config.burstMs, new Advertiser.Listener() {
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

    /**
     * On and Off stay marked on the row that sent them; All rows marks every row it switches, and a
     * row's own change unmarks All rows until the rows agree again (see {@link Marks}). A light with
     * a remote of its own ({@code "in_all": false}) is marked by its own buttons only.
     */
    private void markState(Light light, String command) {
        boolean on = "on".equals(command);
        if (!on && !"off".equals(command)) return;
        List<String> switchable = new ArrayList<>();
        for (Light l : config.lights) {
            if (!Marks.ALL.equals(l.id) && l.inAll && l.has("on") && l.has("off")) switchable.add(l.id);
        }
        marks.set(light.id, on, switchable);
        for (Light l : config.lights) {
            View row = rows.get(l.id);
            if (row == null) continue;
            Boolean mark = marks.get(l.id);
            LinearLayout buttons = row.findViewById(R.id.buttons);
            View onButton = buttons.findViewWithTag("on");
            View offButton = buttons.findViewWithTag("off");
            if (onButton != null) onButton.setSelected(Boolean.TRUE.equals(mark));
            if (offButton != null) offButton.setSelected(Boolean.FALSE.equals(mark));
        }
    }

    private void unblock(Advertiser.Blocker blocker, Light light, String command, boolean mayPrompt) {
        report(blockerText(blocker));
        if (!mayPrompt) return;
        switch (blocker) {
            case NO_PERMISSION:
                pendingLight = light;
                pendingCommand = command;
                requestPermissions(Advertiser.RUNTIME_PERMISSIONS, REQUEST_PERMISSION);
                break;
            case BLUETOOTH_OFF:
                pendingLight = light;
                pendingCommand = command;
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
        if (light != null) send(light, pendingCommand, false);
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
