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
 * Learn the light remote: the TV listens for its packets while the screen asks for one button at a
 * time ({@link LearnSteps}), so every packet heard is filed under the button that caused it. The
 * result, capture.json in the app's files directory, is saved after every step and fetched with
 * lights/pull-capture.sh. OK on the TV remote (the Next button) moves on; Back leaves, keeping
 * what was recorded. Started from the main screen, or with
 * {@code am start -n tv.homelights/.LearnActivity}; add {@code -e steps extras} for a short run
 * (ALL ON, then K, moon, RGB, Aid), or {@code -e filter none} to record every packet heard, not
 * only the ones in the lights' two formats.
 */
public final class LearnActivity extends Activity {
    static final String EXTRA_FILTER = "filter";
    static final String EXTRA_STEPS = "steps";
    private static final int REQUEST_PERMISSION = 3;
    private static final int SERVICE_UUID = 0x08F0;
    private static final int COMPANY_ID = 0x5556;

    private Capture capture;
    private List<LearnSteps.Step> plan = LearnSteps.ALL;
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
        filter = !"none".equals(getIntent().getStringExtra(EXTRA_FILTER));
        plan = LearnSteps.subset(getIntent().getStringExtra(EXTRA_STEPS));
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
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
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
                status.setText(getString(R.string.learn_scan_failed, "code " + errorCode));
            }
        };
        try {
            scanner.startScan(null, settings, callback);
            scanning = true;
            status.setText(R.string.learn_listening);
        } catch (SecurityException | IllegalStateException e) {
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
            Log.i(Advertiser.TAG, "learn " + plan.get(step).id + ": " + AdData.pretty(raw)
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
        LearnSteps.Step s = plan.get(index);
        counter.setText(getString(R.string.learn_step_counter, index + 1, plan.size()));
        String text = s.presses == 1
                ? getString(R.string.learn_press_once, s.button)
                : getString(R.string.learn_press_times, s.button, s.presses);
        instruction.setText(text);
        note.setText(s.note.isEmpty() ? getString(R.string.learn_note_default) : s.note);
        capture.beginStep(s.id, text, System.currentTimeMillis());
        updateCounts();
    }

    private void nextStep() {
        save();
        if (step + 1 >= plan.size()) {
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
        counter.setText(getString(R.string.learn_step_counter, plan.size(), plan.size()));
        int packets = 0;
        for (Capture.StepRecord s : capture.steps()) packets += s.distinct();
        instruction.setText(getString(R.string.learn_done, capture.steps().size(), packets));
        note.setText(R.string.learn_done_note);
        next.setVisibility(View.GONE);
        finishButton.setText(R.string.learn_close);
        finishButton.requestFocus();
    }

    private void save() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        File file = new File(dir, Capture.FILE_NAME);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(capture.toText().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            status.setText(getString(R.string.learn_save_failed, file.getPath(), String.valueOf(e.getMessage())));
        }
    }
}
