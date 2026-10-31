package com.serhii.rcplustester;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Experimental, simulator-only Accessibility touch adapter for DJI Virtual Flight.
 * Runs ONLY while the chosen simulator is the foreground application AND the
 * user has explicitly armed the bridge. Does not access a real aircraft.
 *
 * Android dispatchGesture is asynchronous and may cancel existing touches.
 * Chained multitouch strokes are used to maintain both virtual sticks.
 */
public final class VirtualFlightAccessibilityService extends AccessibilityService {
    private static final String TAG = "VFlightBridge";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Float> axes = new HashMap<>();
    private String foregroundPackage = "";
    private boolean receiverRegistered, busy, scheduled;
    private long lastHeartbeat;
    private int generation;
    private GestureDescription.StrokeDescription leftStroke, rightStroke;
    private float leftX, leftY, rightX, rightY;
    private WindowManager windowManager;
    private LinearLayout overlay;
    private Button pairButton, testButton;
    private TextView debugLabel;
    private String lastSdkMessage = "SDK: waiting";
    private String gestureResult = "touch: idle";
    private int completedFrames, cancelledFrames;
    private boolean testRequested;

    private final BroadcastReceiver inputs = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!SdkProbeService.ACTION_EVENT.equals(intent.getAction())) return;
            String control = intent.getStringExtra(SdkProbeService.EXTRA_CONTROL);
            String signal = intent.getStringExtra(SdkProbeService.EXTRA_SIGNAL);
            String detail = intent.getStringExtra(SdkProbeService.EXTRA_DETAIL);
            if ("SDK:STATUS".equals(signal) && detail != null) {
                lastSdkMessage = detail;
                scheduleFrame();
                return;
            }
            if ("SDK:BRIDGE_HEARTBEAT".equals(signal)) {
                lastHeartbeat = SystemClock.uptimeMillis();
                scheduleFrame();
                return;
            }
            if (control == null || detail == null) return;
            if (!("LS_X".equals(control) || "LS_Y".equals(control) ||
                  "RS_X".equals(control) || "RS_Y".equals(control))) return;
            int eq = detail.lastIndexOf('=');
            if (eq < 0) return;
            try {
                float value = Float.parseFloat(detail.substring(eq + 1).trim());
                axes.put(control, value);
                scheduleFrame();
            } catch (NumberFormatException ignored) {
                Log.w(TAG, "Unrecognized SDK axis sample " + detail);
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            registerReceiver(inputs, new IntentFilter(SdkProbeService.ACTION_EVENT));
            receiverRegistered = true;
        } catch (Throwable t) {
            Log.e(TAG, "Receiver registration failed", t);
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Log.i(TAG, "Accessibility service connected; armed=" + BridgeConfig.armed(this));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String next = pkg.toString();
        // The accessibility overlay can generate its own window events.
        // Do not lose simulator foreground tracking when that happens.
        if (next.equals(getPackageName()) || next.equals("com.android.systemui")
                || next.equals("com.android.settings")) return;
        if (!next.equals(foregroundPackage)) {
            foregroundPackage = next;
            if (!eligibleApp()) stopGesture();
            updateOverlay();
            scheduleFrame();
        }
    }

    @Override public void onInterrupt() {
        stopGesture();
    }

    @Override public void onDestroy() {
        BridgeConfig.setArmed(this, false);
        stopGesture();
        removeOverlay();
        if (receiverRegistered) {
            try { unregisterReceiver(inputs); } catch (Throwable ignored) {}
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private boolean eligibleApp() {
        return BridgeConfig.armed(this) &&
            !BridgeConfig.target(this).isEmpty() &&
            BridgeConfig.target(this).equals(foregroundPackage);
    }

    private boolean active() {
        return eligibleApp() && lastHeartbeat != 0 &&
            SystemClock.uptimeMillis() - lastHeartbeat < 2500;
    }

    private void scheduleFrame() {
        if (scheduled) return;
        scheduled = true;
        handler.postDelayed(() -> {
            scheduled = false;
            frame();
        }, 25);
    }

    private float raw(String key) {
        Float v = axes.get(key);
        return v == null ? 0f : v;
    }
    private float norm(String key) {
        return BridgeConfig.norm(raw(key), BridgeConfig.scale(this),
            BridgeConfig.invert(this, key));
    }

    private static Path path(float fromX, float fromY, float toX, float toY) {
        Path p = new Path();
        p.moveTo(fromX, fromY);
        p.lineTo(toX, toY);
        return p;
    }

    private void frame() {
        updateOverlay();
        if (!active()) {
            stopGesture();
            return;
        }
        if (busy) return;
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        float width = dm.widthPixels, height = dm.heightPixels;
        float radius = Math.min(width, height) *
            BridgeConfig.number(this, "radius", 12) / 100f;
        float leftCx = width * BridgeConfig.number(this, "left_x", 17) / 100f;
        float leftCy = height * BridgeConfig.number(this, "left_y", 71) / 100f;
        float rightCx = width * BridgeConfig.number(this, "right_x", 83) / 100f;
        float rightCy = height * BridgeConfig.number(this, "right_y", 71) / 100f;

        // Android screen Y grows downward. Physical stick up = virtual upward.
        float targetLX = leftCx + norm("LS_X") * radius;
        float targetLY = leftCy - norm("LS_Y") * radius;
        float targetRX = rightCx + norm("RS_X") * radius;
        float targetRY = rightCy - norm("RS_Y") * radius;

        float fromLX = leftStroke == null ? leftCx : leftX;
        float fromLY = leftStroke == null ? leftCy : leftY;
        float fromRX = rightStroke == null ? rightCx : rightX;
        float fromRY = rightStroke == null ? rightCy : rightY;

        try {
            Path lpath = path(fromLX, fromLY, targetLX, targetLY);
            Path rpath = path(fromRX, fromRY, targetRX, targetRY);
            GestureDescription.StrokeDescription nextLeft = leftStroke == null
                ? new GestureDescription.StrokeDescription(lpath, 0, 135, true)
                : leftStroke.continueStroke(lpath, 0, 135, true);
            GestureDescription.StrokeDescription nextRight = rightStroke == null
                ? new GestureDescription.StrokeDescription(rpath, 0, 135, true)
                : rightStroke.continueStroke(rpath, 0, 135, true);
            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(nextLeft).addStroke(nextRight).build();
            busy = true;
            gestureResult = "touch: dispatched";
            final int token = ++generation;
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    if (token != generation) return;
                    leftStroke = nextLeft;
                    rightStroke = nextRight;
                    leftX = targetLX;
                    leftY = targetLY;
                    rightX = targetRX;
                    rightY = targetRY;
                    busy = false;
                    completedFrames++;
                    gestureResult = "touch: OK (" + completedFrames + " frames)";
                    scheduleFrame();
                }
                @Override public void onCancelled(GestureDescription g) {
                    if (token != generation) return;
                    busy = false;
                    leftStroke = null;
                    rightStroke = null;
                    cancelledFrames++;
                    gestureResult = "touch: CANCELLED (" + cancelledFrames + ")";
                    Log.w(TAG, "Gesture cancelled by Android or user input");
                    handler.postDelayed(() -> scheduleFrame(), 150);
                }
            }, handler);
            if (!accepted) {
                busy = false;
                leftStroke = null;
                rightStroke = null;
                gestureResult = "touch: dispatchGesture returned false";
                Log.w(TAG, "Android rejected bridge gesture");
                handler.postDelayed(() -> scheduleFrame(), 250);
            }
        } catch (Throwable t) {
            busy = false;
            leftStroke = null;
            rightStroke = null;
            gestureResult = "touch: exception " + t.getClass().getSimpleName();
            Log.e(TAG, "Gesture bridge failed", t);
        }
    }

    private void stopGesture() {
        generation++;
        busy = false;
        if (leftStroke != null && rightStroke != null) {
            try {
                Path lp = path(leftX, leftY, leftX, leftY);
                Path rp = path(rightX, rightY, rightX, rightY);
                GestureDescription finish = new GestureDescription.Builder()
                    .addStroke(leftStroke.continueStroke(lp, 0, 40, false))
                    .addStroke(rightStroke.continueStroke(rp, 0, 40, false))
                    .build();
                dispatchGesture(finish, null, handler);
            } catch (Throwable t) {
                Log.w(TAG, "Could not finish touch sequence: " + t.getMessage());
            }
        }
        leftStroke = null;
        rightStroke = null;
    }


    private void testGesture() {
        if (!eligibleApp() || windowManager == null) return;
        stopGesture();
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        float cx = dm.widthPixels * BridgeConfig.number(this, "left_x", 17) / 100f;
        float cy = dm.heightPixels * BridgeConfig.number(this, "left_y", 71) / 100f;
        float radius = Math.min(dm.widthPixels, dm.heightPixels) *
            BridgeConfig.number(this, "radius", 12) / 100f;
        gestureResult = "test touch: sending upward drag";
        try {
            GestureDescription g = new GestureDescription.Builder().addStroke(
                new GestureDescription.StrokeDescription(
                    path(cx, cy, cx, cy - radius * 0.65f), 0, 500, false)
            ).build();
            boolean accepted = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription result) {
                    gestureResult = "test touch: OK";
                    scheduleFrame();
                }
                @Override public void onCancelled(GestureDescription result) {
                    gestureResult = "test touch: cancelled";
                    scheduleFrame();
                }
            }, handler);
            if (!accepted) gestureResult = "test touch: rejected by Android";
        } catch (Throwable t) {
            gestureResult = "test touch: " + t.getClass().getSimpleName();
        }
        scheduleFrame();
    }

    private void updateOverlay() {
        if (!BridgeConfig.armed(this) || foregroundPackage.isEmpty() ||
                foregroundPackage.equals(getPackageName()) || windowManager == null) {
            removeOverlay();
            return;
        }
        if (overlay == null) {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.VERTICAL);
            bar.setPadding(6, 5, 6, 5);
            bar.setBackgroundColor(Color.argb(230, 20, 29, 42));
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);

            Button pair = new Button(this);
            pair.setText("PAIR APP");
            pair.setTextSize(10);
            pair.setAllCaps(false);
            pair.setOnClickListener(v -> {
                if (foregroundPackage.isEmpty() ||
                    foregroundPackage.equals(getPackageName())) return;
                // Explicit user click binds the currently visible simulator package.
                BridgeConfig.prefs(this).edit().putString("target", foregroundPackage).apply();
                gestureResult = "Paired: " + foregroundPackage;
                stopGesture();
                updateOverlay();
                scheduleFrame();
            });
            pairButton = pair;
            buttons.addView(pair);

            Button test = new Button(this);
            test.setText("TEST TOUCH");
            test.setTextSize(10);
            test.setAllCaps(false);
            test.setOnClickListener(v -> testGesture());
            testButton = test;
            buttons.addView(test);

            Button stop = new Button(this);
            stop.setText("STOP BRIDGE");
            stop.setTextSize(10);
            stop.setAllCaps(false);
            stop.setTextColor(Color.WHITE);
            stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(175, 45, 45)));
            stop.setOnClickListener(v -> {
                BridgeConfig.setArmed(this, false);
                stopGesture();
                removeOverlay();
                Log.i(TAG, "Bridge disarmed by user");
            });
            buttons.addView(stop);
            bar.addView(buttons);

            debugLabel = new TextView(this);
            debugLabel.setTextColor(Color.WHITE);
            debugLabel.setTextSize(11);
            debugLabel.setMaxLines(4);
            bar.addView(debugLabel);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.LEFT;
            p.x = 8; p.y = 8;
            try {
                windowManager.addView(bar, p);
                overlay = bar;
            } catch (Throwable t) {
                Log.e(TAG, "Cannot display simulator pairing overlay", t);
                return;
            }
        }
        if (pairButton != null) pairButton.setVisibility(eligibleApp() ? View.GONE : View.VISIBLE);
        if (testButton != null) testButton.setVisibility(eligibleApp() ? View.VISIBLE : View.GONE);
        if (debugLabel != null) {
            String sdk = lastHeartbeat == 0 ? "waiting for SDK heartbeat" :
                (SystemClock.uptimeMillis() - lastHeartbeat < 2500 ?
                    "SDK heartbeat OK" : "SDK heartbeat LOST");
            String diagnostic = "App: " + foregroundPackage +
                (eligibleApp() ? " [PAIRED]" : " [NOT PAIRED]") +
                "\\n" + sdk + " | " + gestureResult +
                "\\nLS=" + (int)raw("LS_X") + "," + (int)raw("LS_Y") +
                " RS=" + (int)raw("RS_X") + "," + (int)raw("RS_Y");
            if (!diagnostic.contentEquals(debugLabel.getText())) debugLabel.setText(diagnostic);
        }
    }

    private void removeOverlay() {
        if (overlay == null) return;
        try { windowManager.removeView(overlay); } catch (Throwable ignored) {}
        overlay = null;
        pairButton = null;
        testButton = null;
        debugLabel = null;
    }
}
