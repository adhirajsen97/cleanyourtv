package tv.homelights;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Learn a remote: the TV listens for its packets while the screen asks for one button at a time
 * ({@link LearnSteps}), so every packet heard is filed under the button that caused it. The
 * result, capture.json (capture-cove.json for the cove remote) in the app's files directory, is
 * saved after every step and fetched with lights/pull-capture.sh. OK on the TV remote (the Next
 * button) moves on; Back leaves, keeping what was recorded. Started from the main screen, or with
 * {@code am start -n tv.homelights/.LearnActivity}; add {@code -e steps cove} for the cove remote,
 * {@code -e steps extras} for a short run of the light remote (ALL ON, then K, moon, RGB, Aid), or
 * a comma-separated list of step ids. {@code -e filter none} records every packet heard, not only
 * the ones in the lights' two formats; the cove remote's plan does that anyway, and
 * {@code -e filter lights} puts the filter back for it.
 */
public final class LearnActivity extends Activity {
    static final String EXTRA_FILTER = "filter";
    static final String EXTRA_STEPS = "steps";
    private static final int REQUEST_PERMISSION = 3;
    private static final int SERVICE_UUID = 0x08F0;
    private static final int COMPANY_ID = 0x5556;

    private Capture capture;
    private LearnSteps.Plan plan = LearnSteps.LIGHT_REMOTE;
    private int step = -1;
    private boolean done;
    private boolean filter = true;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private boolean scanning;
    private TextView counter;
    private TextView instruction;
    private TextView note;
    private TextView heard;
    private TextView status;
    private View next;
    private TextView finishButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_learn);
        counter = findViewById(R.id.counter);
        instruction = findViewById(R.id.instruction);
        note = findViewById(R.id.note);
        heard = findViewById(R.id.heard);
        status = findViewById(R.id.status);
        next = findViewById(R.id.next);
        finishButton = findViewById(R.id.finish);
        next.setOnClickListener(v -> nextStep());
        finishButton.setOnClickListener(v -> {
            if (done) finish();
            else finishCapture();
        });
        plan = LearnSteps.plan(getIntent().getStringExtra(EXTRA_STEPS));
        String filterExtra = getIntent().getStringExtra(EXTRA_FILTER);
        filter = filterExtra == null ? !plan.everyPacket : !"none".equals(filterExtra);
        ((TextView) findViewById(R.id.title)).setText(getString(R.string.learn_title, plan.remote));
        ((TextView) findViewById(R.id.footer)).setText(plan.footer);
        String started = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date());
        capture = new Capture(started, Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + (filter ? "" : ", every packet recorded"), System.currentTimeMillis());
        showStep(0);
        next.requestFocus();
        if (hasPermission()) startScan();
        else requestPermissions(permissions(), REQUEST_PERMISSION);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!done && !scanning && hasPermission()) startScan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopScan();
        save();
    }

    /** Scanning is a runtime permission: BLUETOOTH_SCAN from Android 12, location before that. */
    private static String[] permissions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[] {Manifest.permission.BLUETOOTH_SCAN}
                : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermission() {
        for (String p : permissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSION) return;
        if (hasPermission()) startScan();
        else status.setText(R.string.learn_permission_denied);
    }

    private void startScan() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status.setText(R.string.learn_bluetooth_off);
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status.setText(R.string.learn_no_scanner);
            return;
        }
        startScan(adapter.isLeExtendedAdvertisingSupported(), adapter.isLeCodedPhySupported());
    }

    /**
     * The default scan returns legacy advertisements only, so a remote on Bluetooth 5 extended
     * advertising, or on the long-range (coded) PHY, would be invisible to it. Where the radio can,
     * this hears those too; a radio that refuses the wider scan gets the legacy one.
     */
    private void startScan(boolean extended, boolean coded) {
        ScanSettings.Builder builder = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
        if (extended) {
            builder.setLegacy(false);
            if (coded) builder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED);
        }
        callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                heard(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult r : results) heard(r);
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                if (extended && errorCode == SCAN_FAILED_FEATURE_UNSUPPORTED) {
                    Log.w(Advertiser.TAG, "the radio refuses an extended scan; legacy advertisements only");
                    startScan(false, false);
                    return;
                }
                status.setText(getString(R.string.learn_scan_failed, "code " + errorCode));
            }
        };
        try {
            scanner.startScan(null, builder.build(), callback);
            scanning = true;
            status.setText(extended ? R.string.learn_listening_extended : R.string.learn_listening);
            Log.i(Advertiser.TAG, "scanning " + (extended ? "legacy and extended advertisements" + (coded ? " on every PHY" : "")
                    : "legacy advertisements"));
        } catch (SecurityException | IllegalStateException | IllegalArgumentException e) {
            if (extended) {
                Log.w(Advertiser.TAG, "extended scan refused (" + e.getMessage() + "); legacy advertisements only");
                startScan(false, false);
                return;
            }
            status.setText(getString(R.string.learn_scan_failed, String.valueOf(e.getMessage())));
        }
    }

    private void stopScan() {
        if (!scanning || scanner == null) return;
        scanning = false;
        try {
            scanner.stopScan(callback);
        } catch (SecurityException | IllegalStateException e) {
            Log.w(Advertiser.TAG, "stopScan: " + e.getMessage());
        }
    }

    private void heard(ScanResult result) {
        if (done) return;
        ScanRecord record = result.getScanRecord();
        byte[] bytes = record == null ? null : record.getBytes();
        if (bytes == null) return;
        byte[] raw = AdData.significant(bytes);
        if (raw.length == 0) return;
        if (filter && !AdData.isLightPacket(raw, SERVICE_UUID, COMPANY_ID)) {
            capture.ignored();
            updateCounts();
            return;
        }
        String address = result.getDevice() == null ? "" : result.getDevice().getAddress();
        if (capture.heard(raw, address, result.getRssi(), System.currentTimeMillis())) {
            Log.i(Advertiser.TAG, "learn " + plan.steps.get(step).id + ": " + AdData.pretty(raw)
                    + " rssi " + result.getRssi() + " from " + address);
        }
        updateCounts();
    }

    private void updateCounts() {
        Capture.StepRecord current = capture.current();
        if (current == null) return;
        heard.setText(getString(R.string.learn_heard, current.distinct(), current.copies, current.ignored));
    }

    private void showStep(int index) {
        step = index;
        LearnSteps.Step s = plan.steps.get(index);
        counter.setText(getString(R.string.learn_step_counter, index + 1, plan.steps.size()));
        String text;
        if (s.presses == 0) text = s.button;
        else if (s.presses == 1) text = getString(R.string.learn_press_once, plan.remote, s.button);
        else text = getString(R.string.learn_press_times, plan.remote, s.button, s.presses);
        instruction.setText(text);
        note.setText(s.note.isEmpty() ? getString(R.string.learn_note_default) : s.note);
        capture.beginStep(s.id, text, System.currentTimeMillis(), s.quiet);
        updateCounts();
    }

    private void nextStep() {
        save();
        if (step + 1 >= plan.steps.size()) {
            finishCapture();
            return;
        }
        showStep(step + 1);
    }

    private void finishCapture() {
        done = true;
        capture.endStep(System.currentTimeMillis());
        stopScan();
        save();
        counter.setText(getString(R.string.learn_step_counter, plan.steps.size(), plan.steps.size()));
        int packets = 0;
        for (Capture.StepRecord s : capture.steps()) packets += s.distinct();
        instruction.setText(getString(R.string.learn_done, capture.steps().size(), packets));
        note.setText(getString(R.string.learn_done_note, plan.pullCommand, plan.file));
        next.setVisibility(View.GONE);
        finishButton.setText(R.string.learn_close);
        finishButton.requestFocus();
    }

    private void save() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        File file = new File(dir, plan.file);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(capture.toText().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            status.setText(getString(R.string.learn_save_failed, file.getPath(), String.valueOf(e.getMessage())));
        }
    }
}
