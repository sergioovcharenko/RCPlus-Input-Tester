package com.serhii.rcplustester;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Configuration and opt-in for the experimental Virtual Flight touch bridge. */
public final class BridgeSetupActivity extends Activity {
    private static final int TEXT = Color.WHITE;
    private static final int MINT = Color.rgb(119, 235, 183);
    private static final int BG = Color.rgb(13, 21, 35);
    private TextView state;
    private EditText packageInput;

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
    private TextView label(String content, int size) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(TEXT);
        view.setTextSize(size);
        view.setPadding(dp(4), dp(7), dp(4), dp(7));
        return view;
    }
    private Button button(String caption, Runnable callback) {
        Button b = new Button(this);
        b.setText(caption);
        b.setAllCaps(false);
        b.setOnClickListener(v -> callback.run());
        return b;
    }
    private void row(LinearLayout parent, Button a, Button b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(a, new LinearLayout.LayoutParams(0, dp(54), 1));
        r.addView(b, new LinearLayout.LayoutParams(0, dp(54), 1));
        parent.addView(r);
    }
    private void numeric(LinearLayout parent, String name, String key,
                         int low, int high, int initial) {
        TextView current = label("", 14);
        current.setTextColor(MINT);
        SeekBar slider = new SeekBar(this);
        slider.setMax(high - low);
        int value = BridgeConfig.number(this, key, initial);
        slider.setProgress(Math.max(0, Math.min(high - low, value - low)));
        current.setText(name + ": " + value + (key.equals("raw_max") ? " (SDK units)" : "%"));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean user) {
                int val = low + progress;
                current.setText(name + ": " + val + (key.equals("raw_max") ? " (SDK units)" : "%"));
                if (user) BridgeConfig.prefs(BridgeSetupActivity.this)
                    .edit().putInt(key, val).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seek) {}
            @Override public void onStopTrackingTouch(SeekBar seek) {}
        });
        parent.addView(current);
        parent.addView(slider);
    }
    private void invert(LinearLayout parent, String axis) {
        CheckBox b = new CheckBox(this);
        b.setText("Invert " + axis);
        b.setTextColor(TEXT);
        b.setChecked(BridgeConfig.invert(this, axis));
        b.setOnCheckedChangeListener((view, checked) ->
            BridgeConfig.prefs(this).edit().putBoolean("invert_" + axis, checked).apply());
        parent.addView(b);
    }
    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && (enabled.contains(getPackageName() +
            "/" + VirtualFlightAccessibilityService.class.getName()) ||
            enabled.contains(getPackageName() + "/.VirtualFlightAccessibilityService"));
    }
    private void updateState() {
        if (state == null) return;
        state.setText("Accessibility: " + (accessibilityEnabled() ? "ON" : "OFF") +
            "     Bridge armed: " + (BridgeConfig.armed(this) ? "YES" : "NO") +
            "\nTarget application: " + BridgeConfig.target(this) +
            "\nLast bridge stop: " + BridgeConfig.prefs(this).getString(
                "last_stop_reason", "No automatic stop recorded") +
            "\nLong gesture result: " + BridgeConfig.prefs(this).getString(
                "last_diagnostic_result", "Not tested yet") +
            "\nScreen calibration: " + BridgeConfig.prefs(this).getString(
                "last_calibration", "Not measured yet"));
    }
    @Override public void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setPadding(dp(20), dp(15), dp(20), dp(15));
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(BG);
        scroll.addView(content);
        TextView headline = label("DJI VIRTUAL FLIGHT  ·  STICK BRIDGE 0.7 AUTO CAL TEST", 23);
        headline.setTextColor(MINT);
        content.addView(headline);
        content.addView(label("EXPERIMENTAL · Touch control of the ORIGINAL DJI Virtual Flight. No real drone, MAVLink or radio output. Turn on only in the simulator.", 14));
        state = label("", 14);
        state.setTextColor(MINT);
        content.addView(state);
        row(content,
            button("1. Enable Accessibility", () -> {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }),
            button("2. START BRIDGE", this::startBridge));
        row(content,
            button("STOP BRIDGE", this::stopBridge),
            button("3. Open Virtual Flight", this::openVirtualFlight));
        content.addView(label("v0.7 AUTO CAL TEST: find virtual joystick centers & ring radii from an Android 11 screenshot. The app applies calibration only when both circles are detected confidently. Live physical stick control remains EXPERIMENTAL and OFF by default.", 13));
        CheckBox autoCal = new CheckBox(this);
        autoCal.setText("AUTO DETECT SCREEN JOYSTICKS (every 16s while idle)");
        autoCal.setTextColor(MINT);
        autoCal.setChecked(BridgeConfig.prefs(this).getBoolean("auto_calibrate", true));
        autoCal.setOnCheckedChangeListener((button, enabled) ->
            BridgeConfig.prefs(this).edit().putBoolean("auto_calibrate", enabled).apply());
        content.addView(autoCal);
        content.addView(label("In DJI Virtual Flight tap AUTO CAL on the top-left Bridge overlay. Then return to this setup page to review detected coordinates. If the algorithm cannot find BOTH rings, it keeps the existing manual calibration.", 13));
        CheckBox diagnosticOnly = new CheckBox(this);
        diagnosticOnly.setText("DIAGNOSTIC ONLY (recommended; no live stick injection)");
        diagnosticOnly.setTextColor(MINT);
        diagnosticOnly.setChecked(BridgeConfig.prefs(this).getBoolean("diagnostic_only", true));
        diagnosticOnly.setOnCheckedChangeListener((button, enabled) ->
            BridgeConfig.prefs(this).edit().putBoolean("diagnostic_only", enabled).apply());
        content.addView(diagnosticOnly);
        content.addView(label("TEST 10s L and TEST 10s 2X appear on the top-left in-game overlay after START BRIDGE. Do not touch the screen during either test. Wait for COMPLETED or CANCELLED to appear, then return here to read the saved result.", 13));
        TextView touchTitle = label("CONTROL TEST MODE", 18);
        touchTitle.setTextColor(MINT);
        content.addView(touchTitle);
        row(content,
            button("LEFT ONLY", () -> setMode(1)),
            button("RIGHT ONLY", () -> setMode(2)));
        content.addView(button("BOTH STICKS (after separate tests)", () -> setMode(3)));
        content.addView(label("BOTH STICKS is for the old experimental live mode only. In diagnostic-only mode the physical sticks are read but never injected; use the 10-second on-screen test buttons.", 13));
        CheckBox centers = new CheckBox(this);
        centers.setText("SHOW CENTER MARKERS (L/R crosshairs on simulator)");
        centers.setTextColor(TEXT);
        centers.setChecked(BridgeConfig.prefs(this).getBoolean("show_centers", true));
        centers.setOnCheckedChangeListener((button, checked) ->
            BridgeConfig.prefs(this).edit().putBoolean("show_centers", checked).apply());
        content.addView(centers);
        TextView compatibilityTitle = label("SIMULATOR DETECTION", 18);
        compatibilityTitle.setTextColor(MINT);
        content.addView(compatibilityTitle);
        row(content,
            button("NORMAL: DJI VIRTUAL FLIGHT", () ->
                choosePackage(BridgeConfig.TARGET_DEFAULT)),
            button("COMPATIBILITY: LAUNCHER3", () ->
                choosePackage("com.android.launcher3")));
        content.addView(label("Use COMPATIBILITY only when the original game is running, but the overlay says App: com.android.launcher3. In compatibility mode, STOP BRIDGE before opening Android home/settings to prevent unwanted touches.", 13));
        content.addView(label("CURRENT TARGET PACKAGE (read-only):", 14));
        packageInput = new EditText(this);
        packageInput.setText(BridgeConfig.target(this));
        packageInput.setTextColor(TEXT);
        packageInput.setSingleLine(true);
        packageInput.setEnabled(false);
        packageInput.setTextSize(14);
        content.addView(packageInput);
        // The two explicit mode buttons above change the package instead of
        // unrestricted text entry, to avoid injecting touches into arbitrary apps.
        TextView tuning = label("TOUCH STICK CALIBRATION (percent of full screen)", 18);
        tuning.setTextColor(MINT);
        content.addView(tuning);
        content.addView(label("The AUTO CAL circle detector determines L and R independently from the current simulator picture. You may also adjust values manually. X/Y are percentages of the landscape screenshot; radii are percentages of its short dimension.", 13));
        numeric(content, "Left center X", "left_x", 5, 45, 16);
        numeric(content, "Left center Y", "left_y", 40, 95, 71);
        numeric(content, "Right center X", "right_x", 55, 95, 85);
        numeric(content, "Right center Y", "right_y", 40, 95, 71);
        numeric(content, "Left stick travel radius", "left_radius", 4, 25, 19);
        numeric(content, "Right stick travel radius", "right_radius", 4, 25, 19);
        numeric(content, "SDK stick maximum", "raw_max", 1, 1500, 660);
        content.addView(button("RESET CENTER COORDINATES TO DEFAULT", () -> {
            BridgeConfig.prefs(this).edit()
                .putInt("left_x", 16).putInt("left_y", 71)
                .putInt("right_x", 85).putInt("right_y", 71)
                .putInt("left_radius", 19).putInt("right_radius", 19).apply();
            Toast.makeText(this, "Centers reset. Reopen setup to refresh sliders.",
                Toast.LENGTH_LONG).show();
        }));
        TextView cameraTitle = label("CAMERA PITCH · ZOOM_DIAL (lower-right wheel)", 18);
        cameraTitle.setTextColor(MINT);
        content.addView(cameraTitle);
        CheckBox cameraEnabled = new CheckBox(this);
        cameraEnabled.setText("ENABLE CAMERA PITCH WHEEL (simulator touch slider)");
        cameraEnabled.setTextColor(TEXT);
        cameraEnabled.setChecked(BridgeConfig.prefs(this).getBoolean("camera_enabled", false));
        cameraEnabled.setOnCheckedChangeListener((button, enabled) ->
            BridgeConfig.prefs(this).edit().putBoolean("camera_enabled", enabled).apply());
        content.addView(cameraEnabled);
        CheckBox cameraConcurrent = new CheckBox(this);
        cameraConcurrent.setText("EXPERIMENTAL: allow camera movements while sticks held");
        cameraConcurrent.setTextColor(TEXT);
        cameraConcurrent.setChecked(BridgeConfig.prefs(this).getBoolean("camera_concurrent", false));
        cameraConcurrent.setOnCheckedChangeListener((button, enabled) ->
            BridgeConfig.prefs(this).edit().putBoolean("camera_concurrent", enabled).apply());
        content.addView(cameraConcurrent);
        CheckBox cameraReverse = new CheckBox(this);
        cameraReverse.setText("REVERSE camera wheel direction");
        cameraReverse.setTextColor(TEXT);
        cameraReverse.setChecked(BridgeConfig.prefs(this).getBoolean("camera_invert", false));
        cameraReverse.setOnCheckedChangeListener((button, enabled) ->
            BridgeConfig.prefs(this).edit().putBoolean("camera_invert", enabled).apply());
        content.addView(cameraReverse);
        content.addView(label("The camera is adjusted through the original Virtual Flight touchscreen slider on the right edge, not by an aircraft/gimbal command. Without concurrent mode, wheel changes are queued until sticks are released.", 13));
        numeric(content, "Camera slider X", "camera_x", 88, 99, 95);
        numeric(content, "Camera initial Y", "camera_y", 40, 90, 70);
        numeric(content, "Camera upper limit Y", "camera_top", 25, 70, 50);
        numeric(content, "Camera lower limit Y", "camera_bottom", 60, 95, 84);
        numeric(content, "Camera wheel step", "camera_step", 1, 8, 3);
        TextView direction = label("REVERSE AXIS DIRECTION IF NECESSARY", 18);
        direction.setTextColor(MINT);
        content.addView(direction);
        for (String axis : new String[]{"LS_X", "LS_Y", "RS_X", "RS_Y"}) invert(content, axis);
        content.addView(label("For first test: no propellers or aircraft. Open Virtual Flight, then move ONE physical stick slightly. Multi-touch continuity depends on Android 11 behavior.", 13));
        content.addView(button("Back to Input Tester", this::finish));
        setContentView(scroll);
        updateState();
    }
    @Override protected void onResume() {
        super.onResume();
        updateState();
    }
    private void choosePackage(String target) {
        if (!BridgeConfig.TARGET_DEFAULT.equals(target) &&
                !"com.android.launcher3".equals(target)) return;
        BridgeConfig.prefs(this).edit().putString("target", target).apply();
        if (packageInput != null) packageInput.setText(target);
        updateState();
        Toast.makeText(this, "Bridge target: " + target, Toast.LENGTH_SHORT).show();
    }
    private void setMode(int mode) {
        BridgeConfig.prefs(this).edit().putInt("touch_mode", mode).apply();
        String label = mode == 1 ? "LEFT ONLY" : mode == 2 ? "RIGHT ONLY" : "BOTH STICKS";
        Toast.makeText(this, "Touch mode: " + label, Toast.LENGTH_SHORT).show();
        updateState();
    }
    private void startBridge() {
        if (!accessibilityEnabled()) {
            Toast.makeText(this, "Enable Virtual Flight Stick Bridge in Accessibility settings first",
                Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        BridgeConfig.prefs(this).edit().putString("last_stop_reason", "Bridge started manually").apply();
        BridgeConfig.setArmed(this, true);
        Intent i = new Intent(this, SdkProbeService.class);
        i.setAction(SdkProbeService.ACTION_BRIDGE_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Toast.makeText(this, "Bridge armed. Open the original Virtual Flight.",
                Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            BridgeConfig.setArmed(this, false);
            Toast.makeText(this, "Cannot start DJI SDK: " + t, Toast.LENGTH_LONG).show();
        }
        updateState();
    }
    private void stopBridge() {
        BridgeConfig.prefs(this).edit().putString("last_stop_reason", "Stopped by user").apply();
        BridgeConfig.setArmed(this, false);
        Intent i = new Intent(this, SdkProbeService.class);
        i.setAction(SdkProbeService.ACTION_BRIDGE_STOP);
        try { startService(i); } catch (Throwable ignored) {}
        Toast.makeText(this, "Simulator stick bridge stopped", Toast.LENGTH_SHORT).show();
        updateState();
    }
    private void openVirtualFlight() {
        String target = BridgeConfig.TARGET_DEFAULT;
        Intent launch = getPackageManager().getLaunchIntentForPackage(target);
        if (launch == null) {
            Toast.makeText(this, "Simulator package not found: " + target +
                ". Open DJI Virtual Flight manually, then tap PAIR APP on the overlay.", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(launch); }
        catch (Throwable t) {
            Toast.makeText(this, "Cannot open simulator: " + t, Toast.LENGTH_LONG).show();
        }
    }
}
