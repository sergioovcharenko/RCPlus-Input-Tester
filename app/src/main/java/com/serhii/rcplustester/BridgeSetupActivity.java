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
        return enabled != null && enabled.contains(getPackageName() +
            "/.VirtualFlightAccessibilityService");
    }
    private void updateState() {
        if (state == null) return;
        state.setText("Accessibility: " + (accessibilityEnabled() ? "ON" : "OFF") +
            "     Bridge armed: " + (BridgeConfig.armed(this) ? "YES" : "NO") +
            "\nTarget application: " + BridgeConfig.target(this));
    }
    @Override public void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setPadding(dp(20), dp(15), dp(20), dp(15));
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(BG);
        scroll.addView(content);
        TextView headline = label("DJI VIRTUAL FLIGHT  ·  STICK BRIDGE 0.1", 23);
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
        content.addView(label("On the simulator screen, a red STOP BRIDGE button appears in the top-left corner. Use it to stop all virtual touches.", 13));
        content.addView(label("SIMULATOR PACKAGE (change only if the installed variant differs):", 14));
        packageInput = new EditText(this);
        packageInput.setText(BridgeConfig.target(this));
        packageInput.setTextColor(TEXT);
        packageInput.setSingleLine(true);
        packageInput.setTextSize(14);
        content.addView(packageInput);
        packageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                BridgeConfig.prefs(BridgeSetupActivity.this).edit()
                    .putString("target", s.toString()).apply();
                updateState();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        TextView tuning = label("TOUCH STICK CALIBRATION (percent of full screen)", 18);
        tuning.setTextColor(MINT);
        content.addView(tuning);
        content.addView(label("Defaults estimated from your photo: left ~17% / 71%, right ~83% / 71%. Change positions if touches miss the joystick circles.", 13));
        numeric(content, "Left center X", "left_x", 5, 45, 17);
        numeric(content, "Left center Y", "left_y", 40, 95, 71);
        numeric(content, "Right center X", "right_x", 55, 95, 83);
        numeric(content, "Right center Y", "right_y", 40, 95, 71);
        numeric(content, "Stick travel radius", "radius", 4, 25, 12);
        numeric(content, "SDK stick maximum", "raw_max", 100, 1500, 660);
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
    private void startBridge() {
        if (!accessibilityEnabled()) {
            Toast.makeText(this, "Enable Virtual Flight Stick Bridge in Accessibility settings first",
                Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
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
        BridgeConfig.setArmed(this, false);
        Intent i = new Intent(this, SdkProbeService.class);
        i.setAction(SdkProbeService.ACTION_BRIDGE_STOP);
        try { startService(i); } catch (Throwable ignored) {}
        Toast.makeText(this, "Simulator stick bridge stopped", Toast.LENGTH_SHORT).show();
        updateState();
    }
    private void openVirtualFlight() {
        String target = BridgeConfig.target(this);
        Intent launch = getPackageManager().getLaunchIntentForPackage(target);
        if (launch == null) {
            Toast.makeText(this, "Simulator package not found: " + target +
                ". Check installed package name.", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(launch); }
        catch (Throwable t) {
            Toast.makeText(this, "Cannot open simulator: " + t, Toast.LENGTH_LONG).show();
        }
    }
}
