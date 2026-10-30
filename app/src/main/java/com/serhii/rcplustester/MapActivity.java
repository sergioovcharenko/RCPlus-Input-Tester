package com.serhii.rcplustester;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
        "SHUTTER","RECORD","RTH","PAUSE","BACK","MODE"
    };
    private final LinkedHashMap<String, Button> buttons = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> statuses = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> captured = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Float> axisLast = new LinkedHashMap<>();
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private TextView instruction, lastEvent, log, sdkStatus, counts, deviceList;
    private String selected;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        for (String id : IDS) statuses.put(id, "UNTESTED");
        createUi();
        refreshDevices();
        DjiInputBridge.attach((id, signal, detail, active) -> runOnUiThread(() -> {
            if ("SDK:STATUS".equals(signal)) {
                sdkStatus.setText("DJI SDK: " + detail);
                addEvent(detail);
            } else {
                capture(id, signal, detail, active);
            }
        }));
        sdkStatus.setText("DJI SDK: " + DjiInputBridge.status());
    }

    @Override protected void onStart() {
        super.onStart();
        DjiInputBridge.attach((id, signal, detail, active) -> runOnUiThread(() -> {
            if ("SDK:STATUS".equals(signal)) {
                sdkStatus.setText("DJI SDK: " + detail);
            } else {
                capture(id, signal, detail, active);
            }
        }));
    }

    @Override protected void onStop() {
        DjiInputBridge.detach();
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
        root.addView(text("DJI RC PLUS 2  ·  INPUT TESTER 0.3", 21, ACCENT));
        root.addView(text("Android + DJI Mobile SDK V5 · read-only diagnostic", 12, WHITE));

        LinearLayout toolbar = row();
        toolbar.addView(action("No signal", () -> {
            if (selected == null) return;
            statuses.put(selected, "NO_SIGNAL");
            selected = null;
            updateButtons();
        }), new LinearLayout.LayoutParams(0, dp(50), 1));
        toolbar.addView(action("Copy report", this::copyReport), new LinearLayout.LayoutParams(0, dp(50), 1));
        toolbar.addView(action("Devices", this::refreshDevices), new LinearLayout.LayoutParams(0, dp(50), 1));
        toolbar.addView(action("Reset", this::confirmReset), new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(toolbar);

        sdkStatus = text("DJI SDK: initializing...", 13, ACCENT);
        root.addView(sdkStatus);
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
    private void copyReport() {
        StringBuilder b = new StringBuilder("RC Plus Input Tester 0.3\nDJI SDK: " + DjiInputBridge.status() + "\n");
        for (String id : IDS) {
            b.append(id).append(": ").append(statuses.get(id));
            if (captured.containsKey(id)) b.append(" | ").append(captured.get(id));
            b.append("\n");
        }
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
            captured.clear(); events.clear(); axisLast.clear(); selected = null;
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
                            InputDevice.getAxisName(axis) + "=" + String.format(Locale.US, "%.2f", value), true);
                    }
                }
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }
}
