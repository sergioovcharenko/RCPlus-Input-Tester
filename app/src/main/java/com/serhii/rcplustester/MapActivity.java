package com.serhii.rcplustester;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Local-only diagnostic screen: no control commands and no remote transmission. */
public final class MapActivity extends Activity {
    private static final int BG = Color.rgb(13, 21, 35);
    private static final int PANEL = Color.rgb(26, 40, 60);
    private static final int GREEN = Color.rgb(23, 128, 88);
    private static final int RED = Color.rgb(155, 55, 65);
    private static final int GRAY = Color.rgb(65, 80, 100);
    private static final int GOLD = Color.rgb(177, 117, 35);
    private static final int ACCENT = Color.rgb(92, 226, 174);
    private static final int WHITE = Color.WHITE;

    private static final String[] IDS = {
        "C1","C2","C3","C4","L1","L2","L3","R1","R2","R3",
        "5D_UP","5D_DOWN","5D_LEFT","5D_RIGHT","5D_CENTER",
        "LS_X","LS_Y","RS_X","RS_Y","LEFT_DIAL","RIGHT_DIAL","ZOOM_DIAL",
        "SHUTTER","RECORD","RTH","PAUSE","BACK","MODE","F_S_N"
    };
    private final LinkedHashMap<String, Button> buttons = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> statuses = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> captured = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Float> axisLast = new LinkedHashMap<>();
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private TextView instruction, lastEvent, log, sdkStatus, counts, deviceList, switchStatus;
    private final java.util.LinkedHashSet<String> switchValues = new java.util.LinkedHashSet<>();
    private String selected;
    private boolean bound;
    private final BroadcastReceiver sdkEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!SdkProbeService.ACTION_EVENT.equals(intent.getAction())) return;
            String signal = intent.getStringExtra(SdkProbeService.EXTRA_SIGNAL);
            String detail = intent.getStringExtra(SdkProbeService.EXTRA_DETAIL);
            if ("SDK:STATUS".equals(signal)) {
                sdkStatus.setText("DJI SDK: " + detail);
                addEvent("SDK: " + detail);
            } else {
                capture(intent.getStringExtra(SdkProbeService.EXTRA_CONTROL),
                    signal == null ? "SDK:unknown" : signal,
                    detail == null ? "" : detail,
                    intent.getBooleanExtra(SdkProbeService.EXTRA_PRESSED, false));
            }
        }
    };
    private final ServiceConnection sdkConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            sdkStatus.setText("DJI SDK: process connected; waiting for registration...");
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            sdkStatus.setText("DJI SDK: process exited/crashed. Android input tester is still active.");
            addEvent("DJI SDK process stopped unexpectedly");
            String trace = CrashNotes.read(MapActivity.this);
            String[] lines = trace.split("\\n");
            String last = lines.length > 0 ? lines[lines.length - 1] : "no checkpoint";
            addEvent("Last SDK checkpoint: " + last);
        }
    };
    private void readFsnFromSdk() {
        if (!bound) {
            addEvent("SDK: Start DJI SDK first");
            return;
        }
        switchStatus.setText("F/S/N SWITCH: requesting current state from hardware...");
        addEvent("SDK: Requesting F/S/N async getValue");
        Intent request = new Intent(SdkProbeService.ACTION_READ_FSN);
        request.setPackage(getPackageName());
        sendBroadcast(request);
    }
    private void startDjiSdk() {
        if (bound) {
            Toast.makeText(this, "DJI SDK is already starting", Toast.LENGTH_SHORT).show();
            return;
        }
        CrashNotes.record(this, "UI pressed Start DJI SDK");
        sdkStatus.setText("DJI SDK: starting isolated probe...");
        try {
            bound = bindService(new Intent(this, SdkProbeService.class), sdkConnection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                sdkStatus.setText("DJI SDK: cannot bind to probe service");
                addEvent("SDK bind failed");
            }
        } catch (Throwable e) {
            bound = false;
            sdkStatus.setText("DJI SDK: launch error: " + e);
            addEvent("SDK launch error: " + e);
        }
    }


    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        for (String id : IDS) statuses.put(id, "UNTESTED");
        createUi();
        refreshDevices();
        sdkStatus.setText("DJI SDK: OFF. Android input is ready. Press Start DJI SDK to test it.");
    }

    @Override protected void onStart() {
        super.onStart();
        registerReceiver(sdkEvents, new IntentFilter(SdkProbeService.ACTION_EVENT));
    }
    @Override protected void onStop() {
        if (bound) {
            try { unbindService(sdkConnection); } catch (Exception ignored) {}
            bound = false;
        }
        try { unregisterReceiver(sdkEvents); } catch (Exception ignored) {}
        super.onStop();
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }
    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setPadding(dp(5), dp(5), dp(5), dp(5));
        return t;
    }
    private Button action(String caption, Runnable callback) {
        Button b = new Button(this);
        b.setText(caption);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setOnClickListener(view -> callback.run());
        return b;
    }
    private void createUi() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setFocusableInTouchMode(true);
        root.addView(text("DJI RC PLUS 2  ·  INPUT TESTER / FLIGHT BRIDGE 0.2", 21, ACCENT));
        root.addView(text("Android + DJI Mobile SDK V5 · read-only diagnostic", 12, WHITE));

        LinearLayout toolbar = row();
        toolbar.addView(action("Start DJI SDK", this::startDjiSdk), new LinearLayout.LayoutParams(0, dp(50), 1.2f));
        toolbar.addView(action("No signal", () -> {
            if (selected == null) return;
            statuses.put(selected, "NO_SIGNAL");
            selected = null;
            updateButtons();
        }), new LinearLayout.LayoutParams(0, dp(50), 1));
        toolbar.addView(action("Read F/S/N", this::readFsnFromSdk), new LinearLayout.LayoutParams(0, dp(50), 1.2f));
        toolbar.addView(action("SDK log", this::showSdkLog), new LinearLayout.LayoutParams(0, dp(50), 1));
        toolbar.addView(action("Copy report", this::copyReport), new LinearLayout.LayoutParams(0, dp(50), 1));
        toolbar.addView(action("Devices", this::refreshDevices), new LinearLayout.LayoutParams(0, dp(50), 1));
        toolbar.addView(action("Reset", this::confirmReset), new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(toolbar);
        root.addView(action("VIRTUAL FLIGHT STICK BRIDGE  →  SETUP", () ->
            startActivity(new Intent(this, BridgeSetupActivity.class))));


        sdkStatus = text("DJI SDK: OFF (safe mode)", 13, ACCENT);
        root.addView(sdkStatus);
        switchStatus = text("F/S/N SWITCH: no SDK value yet. Aircraft may be required.", 13, ACCENT);
        root.addView(switchStatus);
        instruction = text("Tap a control on the left, then press the physical button or move the stick.", 14, WHITE);
        instruction.setBackgroundColor(PANEL);
        root.addView(instruction);

        LinearLayout body = row();
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout left = column();
        left.setPadding(dp(5), dp(5), dp(5), dp(5));
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 0.55f));
        counts = text("CONTROL MAP", 15, ACCENT);
        left.addView(counts);
        ScrollView sc = new ScrollView(this);
        LinearLayout list = column();
        sc.addView(list);
        left.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout line = null;
        for (int i = 0; i < IDS.length; i++) {
            if (i % 3 == 0) {
                line = row();
                list.addView(line);
            }
            final String id = IDS[i];
            Button b = action(id, () -> {
                selected = id;
                instruction.setText("Selected " + id + ". Press the matching physical control. No event? Tap No signal.");
                updateButtons();
            });
            buttons.put(id, b);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(55), 1);
            bp.setMargins(dp(2), dp(2), dp(2), dp(2));
            line.addView(b, bp);
        }

        LinearLayout right = column();
        right.setPadding(dp(5), dp(5), dp(5), dp(5));
        body.addView(right, new LinearLayout.LayoutParams(0, -1, 0.45f));
        right.addView(text("LATEST INPUT", 14, ACCENT));
        lastEvent = text("Awaiting input...", 14, WHITE);
        right.addView(lastEvent);
        right.addView(text("EVENT LOG", 14, ACCENT));
        ScrollView ls = new ScrollView(this);
        log = text("No events yet", 12, WHITE);
        log.setTextIsSelectable(true);
        ls.addView(log);
        right.addView(ls, new LinearLayout.LayoutParams(-1, 0, 0.70f));
        right.addView(text("ANDROID INPUT DEVICES", 13, ACCENT));
        ScrollView ds = new ScrollView(this);
        deviceList = text("", 11, WHITE);
        ds.addView(deviceList);
        right.addView(ds, new LinearLayout.LayoutParams(-1, 0, 0.30f));

        root.addView(text("Green: observed signal. Red: manually marked missing. Gray: untested. These are input events, NOT DJI radio channels. Test with aircraft powered off.", 11, WHITE));
        setContentView(root);
        updateButtons();
        root.requestFocus();
    }

    private void capture(String named, String signal, String detail, boolean active) {
        addEvent(signal + "  " + detail);
        if ("F_S_N".equals(named) && (signal.startsWith("SDK:MODE_SWITCH")
                || signal.startsWith("SDK:FSN_"))) {
            if (active) {
                String raw = detail.replaceFirst("^.*? = ", "").trim();
                switchValues.add(raw);
                switchStatus.setText("F/S/N SWITCH: SDK=" + raw
                    + " | distinct values=" + switchValues.size() + " | " + switchValues
                    + " (raw SDK modes; physical position mapping unverified)");
            } else {
                switchStatus.setText("F/S/N SWITCH: " + detail);
            }
        }
        if (!active) return;
        String target = named != null && buttons.containsKey(named) ? named : selected;
        if (target != null) {
            statuses.put(target, "DETECTED");
            captured.put(target, signal + " | " + detail);
            if (target.equals(selected)) selected = null;
            updateButtons();
        }
    }
    private void addEvent(String s) {
        String e = clock.format(new Date()) + "  " + s;
        events.addFirst(e);
        if (events.size() > 80) events.removeLast();
        lastEvent.setText(s);
        StringBuilder b = new StringBuilder();
        for (String item : events) b.append(item).append("\n");
        log.setText(b.toString());
    }
    private void updateButtons() {
        int ok = 0, fail = 0;
        for (Map.Entry<String, Button> item : buttons.entrySet()) {
            String id = item.getKey();
            String status = statuses.get(id);
            int color = GRAY;
            if ("DETECTED".equals(status)) { ok++; color = GREEN; }
            if ("NO_SIGNAL".equals(status)) { fail++; color = RED; }
            if (id.equals(selected)) color = GOLD;
            item.getValue().setBackgroundTintList(ColorStateList.valueOf(color));
            item.getValue().setTextColor(WHITE);
            item.getValue().setText(id + ("DETECTED".equals(status) ? " ✓" : "NO_SIGNAL".equals(status) ? " ×" : ""));
        }
        counts.setText("INPUT MAP   ✓ " + ok + "   × " + fail + "   ? " + (IDS.length - ok - fail));
    }
    private void refreshDevices() {
        if (deviceList == null) return;
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        StringBuilder b = new StringBuilder();
        if (im != null) for (int id : im.getInputDeviceIds()) {
            InputDevice dev = im.getInputDevice(id);
            if (dev != null) b.append(dev.getId()).append(": ").append(dev.getName()).append("\n");
        }
        deviceList.setText(b.length() == 0 ? "No external input devices" : b.toString());
    }
    private void showSdkLog() {
        final String trace = CrashNotes.read(this);
        ScrollView scroll = new ScrollView(this);
        TextView view = text(trace, 12, WHITE);
        view.setTextIsSelectable(true);
        view.setBackgroundColor(PANEL);
        scroll.addView(view);
        new AlertDialog.Builder(this)
           .setTitle("DJI SDK STARTUP TRACE")
           .setView(scroll)
           .setNegativeButton("Close", null)
           .setPositiveButton("Copy log", (dialog, which) -> {
               ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
               if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("DJI SDK trace", trace));
               Toast.makeText(this, "SDK diagnostic copied", Toast.LENGTH_SHORT).show();
           }).show();
    }
    private void copyReport() {
        StringBuilder b = new StringBuilder("RC Plus Input Tester 0.3.5\n" + sdkStatus.getText() + "\n");
        b.append(switchStatus.getText()).append("\n");
        for (String id : IDS) {
            b.append(id).append(": ").append(statuses.get(id));
            if (captured.containsKey(id)) b.append(" | ").append(captured.get(id));
            b.append("\n");
        }
        b.append("\nSDK startup trace:\n").append(CrashNotes.read(this));
        b.append("\nEvents:\n");
        for (String e : events) b.append(e).append("\n");
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("RC Tester report", b.toString()));
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
    }
    private void confirmReset() {
        new AlertDialog.Builder(this).setTitle("Clear test results?")
          .setNegativeButton("Cancel", null)
          .setPositiveButton("Clear", (dialog, which) -> {
            for (String id : IDS) statuses.put(id, "UNTESTED");
            captured.clear(); events.clear(); axisLast.clear(); switchValues.clear(); selected = null;
            switchStatus.setText("F/S/N SWITCH: no SDK value yet. Aircraft may be required.");
            lastEvent.setText("Awaiting input..."); log.setText("No events yet"); updateButtons();
          }).show();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
            String s = "ANDROID KEY " + (event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN " : "UP ") +
              KeyEvent.keyCodeToString(event.getKeyCode()) + " key=" + event.getKeyCode() +
              " scan=" + event.getScanCode() + " device=" + event.getDeviceId();
            capture(null, "ANDROID:KEY:" + event.getKeyCode() + ":" + event.getScanCode(), s,
                event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0);
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 || event.getAction() == MotionEvent.ACTION_SCROLL) {
            InputDevice device = event.getDevice();
            if (device != null) {
                for (InputDevice.MotionRange range : device.getMotionRanges()) {
                    int axis = range.getAxis();
                    float value = event.getAxisValue(axis);
                    float previous = axisLast.containsKey(axis) ? axisLast.get(axis) : 0f;
                    if (Math.abs(value - previous) >= Math.max(0.05f, range.getFlat())) {
                        axisLast.put(axis, value);
                        capture(null, "ANDROID:AXIS:" + axis,
                            MotionEvent.axisToString(axis) + "=" + String.format(Locale.US, "%.2f", value), true);
                    }
                }
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }
}
