package nl.tunc.blesonar;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Native BLE scanning, drawn by the existing HTML scope.
 *
 * WebView has no Web Bluetooth, so the JS never touches navigator.bluetooth here.
 * Java runs BluetoothLeScanner and pushes batches of advertisements over a
 * JavascriptInterface; the web layer keeps all of the smoothing, distance
 * estimation and rendering exactly as it does in the browser build.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 41;
    private static final int REQ_ENABLE_BT = 42;
    private static final long FLUSH_MS = 250;   // batch adverts to keep the JS bridge cheap

    private WebView web;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;

    private final List<JSONObject> pending = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFF061318);
        web.addJavascriptInterface(new Bridge(), "AndroidBLE");
        web.loadUrl("file:///android_asset/sonar.html");
        setContentView(web);
    }

    /* exposed to the web layer as window.AndroidBLE */
    private class Bridge {
        @JavascriptInterface
        public void start() {
            ui.post(new Runnable() { public void run() { attemptStart(); } });
        }

        @JavascriptInterface
        public void stop() {
            ui.post(new Runnable() { public void run() { stopScan(); } });
        }

        @JavascriptInterface
        public boolean isNative() { return true; }
    }

    /* permission + adapter gauntlet, then scan */
    private void attemptStart() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();

        if (adapter == null) {
            status("This device has no Bluetooth adapter.");
            return;
        }
        if (!adapter.isEnabled()) {
            status("Bluetooth is off. Turn it on and tap Start scan again.");
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            } catch (Exception ignored) { }
            return;
        }

        String[] missing = missingPermissions();
        if (missing.length > 0) {
            requestPermissions(missing, REQ_PERMS);
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status("Bluetooth LE scanning is unavailable on this device.");
            return;
        }
        beginScan();
    }

    private String[] missingPermissions() {
        List<String> want = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            want.add("android.permission.BLUETOOTH_SCAN");
        } else {
            want.add("android.permission.ACCESS_FINE_LOCATION");
        }
        List<String> missing = new ArrayList<>();
        for (String p : want) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        return missing.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERMS) return;
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                status("Scanning needs the Bluetooth permission. Grant it in Settings, then tap Start scan.");
                return;
            }
        }
        attemptStart();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_ENABLE_BT && result == RESULT_OK) attemptStart();
    }

    /* the scan itself */
    private void beginScan() {
        if (scanning) return;

        ScanSettings.Builder b = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0);
        if (Build.VERSION.SDK_INT >= 23) {
            b.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
             .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
             .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT);
        }

        try {
            scanner.startScan(null, b.build(), callback);
        } catch (SecurityException e) {
            status("Permission was revoked. Grant Bluetooth access and try again.");
            return;
        }

        scanning = true;
        ui.postDelayed(flusher, FLUSH_MS);
        js("window.__bleStarted && window.__bleStarted()");
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        ui.removeCallbacks(flusher);
        try { if (scanner != null) scanner.stopScan(callback); } catch (SecurityException ignored) { }
        synchronized (pending) { pending.clear(); }
        js("window.__bleStopped && window.__bleStopped()");
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { queue(r); }

        @Override public void onBatchScanResults(List<ScanResult> rs) {
            for (ScanResult r : rs) queue(r);
        }

        @Override public void onScanFailed(int code) {
            scanning = false;
            final String why;
            switch (code) {
                case SCAN_FAILED_ALREADY_STARTED:
                    why = "A scan is already running."; break;
                case SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    why = "Android refused to register the scan. Toggle Bluetooth off and on."; break;
                case SCAN_FAILED_FEATURE_UNSUPPORTED:
                    why = "This device does not support BLE scanning."; break;
                default:
                    why = "Scan failed (code " + code + ").";
            }
            ui.post(new Runnable() { public void run() { status(why); } });
        }
    };

    private void queue(ScanResult r) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", r.getDevice().getAddress());
            o.put("rssi", r.getRssi());

            ScanRecord rec = r.getScanRecord();
            String name = rec != null ? rec.getDeviceName() : null;
            if (name == null) {
                try { name = r.getDevice().getName(); } catch (SecurityException ignored) { }
            }
            if (name != null && !name.trim().isEmpty()) o.put("name", name.trim());

            if (rec != null) {
                int tx = rec.getTxPowerLevel();
                if (tx != Integer.MIN_VALUE) o.put("tx", tx);

                SparseArray<byte[]> md = rec.getManufacturerSpecificData();
                if (md != null && md.size() > 0) o.put("cid", md.keyAt(0));
            }

            synchronized (pending) {
                if (pending.size() < 400) pending.add(o);
            }
        } catch (Exception ignored) { }
    }

    private final Runnable flusher = new Runnable() {
        @Override public void run() {
            if (!scanning) return;
            JSONArray batch = null;
            synchronized (pending) {
                if (!pending.isEmpty()) {
                    batch = new JSONArray();
                    for (JSONObject o : pending) batch.put(o);
                    pending.clear();
                }
            }
            if (batch != null) {
                js("window.__ble(" + JSONObject.quote(batch.toString()) + ")");
            }
            ui.postDelayed(this, FLUSH_MS);
        }
    };

    /* plumbing */
    private void js(final String code) {
        ui.post(new Runnable() {
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private void status(String msg) {
        js("window.__bleStatus && window.__bleStatus(" + JSONObject.quote(msg) + ")");
    }

    @Override protected void onPause() {
        super.onPause();
        stopScan();   // an app scanning from the background gets throttled by Android anyway
    }

    @Override protected void onDestroy() {
        stopScan();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
