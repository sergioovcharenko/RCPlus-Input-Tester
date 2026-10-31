package com.serhii.rcplustester;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.graphics.Canvas;
import android.graphics.Paint;
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
    // Continue strokes immediately after Android confirms the previous stroke.
    // This remains experimental Accessibility input, not a true HID controller.
    private static final int FRAME_DURATION_MS = 200;
    private static final float STICK_DEADZONE = 0.065f;
    private static final int CANCEL_LIMIT = 12;
    private static final long CANCEL_WINDOW_MS = 10000;
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
    private View centerMarkers;
    private Button pairButton, testButton, testBothButton, autoCalButton;
    private TextView debugLabel;
    private String lastSdkMessage = "SDK: waiting";
    private String gestureResult = "touch: idle";
    private int completedFrames, cancelledFrames;
    private long cancelWindowStarted;
    private int recentCancels;
    private long lastOverlayRefresh;
    private long testGestureUntil;
    private boolean diagnosticActive;
    private int diagnosticPassed, diagnosticCancelled;
    private String diagnosticResult = "10s diagnostic: not tested";
    private long diagnosticStarted;
    private long lastCalibrationAt;
    private boolean calibrationRunning;
    private String calibrationStatus = "auto-cal: not measured";
    private long lastGestureCompletedMs;
    private long largestDispatchGapMs;
    private int previousWheelSign;
    private int queuedCameraSteps;
    private float cameraYPercent = Float.NaN;
    private String cameraStatus = "camera: idle";

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
            if ("ZOOM_DIAL".equals(control)) {
                // DJI scroll wheel is a spring-centred -1/0/+1 input.
                // Count a new detent on transition; never flood touch gestures
                // while the wheel reports a sustained value.
                try {
                    int eq = detail.lastIndexOf('=');
                    if (eq >= 0) {
                        float value = Float.parseFloat(detail.substring(eq + 1).trim());
                        int sign = value > 0.4f ? 1 : value < -0.4f ? -1 : 0;
                        if (sign != 0 && sign != previousWheelSign &&
                                BridgeConfig.prefs(VirtualFlightAccessibilityService.this)
                                    .getBoolean("camera_enabled", false)) {
                            int direction = BridgeConfig.prefs(VirtualFlightAccessibilityService.this)
                                .getBoolean("camera_invert", false) ? -sign : sign;
                            queuedCameraSteps = Math.max(-12, Math.min(12,
                                queuedCameraSteps + direction));
                            cameraStatus = "camera: wheel " + sign + " queued=" + queuedCameraSteps;
                            scheduleFrame();
                        }
                        previousWheelSign = sign;
                    }
                } catch (NumberFormatException ignored) {
                    cameraStatus = "camera: invalid dial value";
                }
                return;
            }
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

    // A new dispatch during an active stroke cancels that stroke. Instead,
    // queue the continuation immediately from the completion callback.
    private void continueImmediately() {
        handler.post(() -> {
            if (active()) frame();
        });
    }

    private float raw(String key) {
        Float v = axes.get(key);
        return v == null ? 0f : v;
    }
    private float norm(String key) {
        float value = BridgeConfig.norm(raw(key), BridgeConfig.scale(this),
            BridgeConfig.invert(this, key));
        return Math.abs(value) < STICK_DEADZONE ? 0f : value;
    }

    private int mode() {
        // 1=left only, 2=right only, 3=both. Left-only is safest initial test.
        return BridgeConfig.number(this, "touch_mode", 3);
    }
    private boolean leftMoving() {
        return mode() != 2 && (norm("LS_X") != 0f || norm("LS_Y") != 0f);
    }
    private boolean rightMoving() {
        return mode() != 1 && (norm("RS_X") != 0f || norm("RS_Y") != 0f);
    }
    private boolean stickMoving() { return leftMoving() || rightMoving(); }

    private static Path path(float fromX, float fromY, float toX, float toY) {
        Path p = new Path();
        p.moveTo(fromX, fromY);
        p.lineTo(toX, toY);
        return p;
    }

    private void frame() {
        long now = SystemClock.uptimeMillis();
        if (now - lastOverlayRefresh > 350) {
            updateOverlay();
            lastOverlayRefresh = now;
        }
        if (eligibleApp() && active() && !diagnosticActive && !busy
                && leftStroke == null && rightStroke == null
                && BridgeConfig.prefs(this).getBoolean("auto_calibrate", true)
                && now - lastCalibrationAt > 16000L) {
            captureCenters();
        }
        if (!active()) {
            if (leftStroke != null || rightStroke != null || busy) stopGesture();
            return;
        }
        if (diagnosticActive || now < testGestureUntil) return;
        if (busy) return;
        // Diagnostic-only is the safe default: don't re-arm the known-unreliable
        // 200ms touch chain between test gestures.
        if (BridgeConfig.prefs(this).getBoolean("diagnostic_only", true)) {
            gestureResult = "DIAGNOSTIC ONLY; live controls disabled";
            if (leftStroke != null || rightStroke != null) stopGesture();
            return;
        }

        // No synthetic touches at all while neutral, unless an existing
        // individual finger needs a clean UP event.
        final boolean wantLeft = leftMoving();
        final boolean wantRight = rightMoving();
        final boolean cameraAllowed = BridgeConfig.prefs(this)
            .getBoolean("camera_enabled", false) &&
            (BridgeConfig.prefs(this).getBoolean("camera_concurrent", false) ||
             (!wantLeft && !wantRight && leftStroke == null && rightStroke == null));
        int nextStep = cameraAllowed ? Integer.signum(queuedCameraSteps) : 0;
        if (nextStep != 0) {
            float current = Float.isNaN(cameraYPercent) ?
                BridgeConfig.number(this, "camera_y", 70) : cameraYPercent;
            int low = BridgeConfig.number(this, "camera_top", 50);
            int high = BridgeConfig.number(this, "camera_bottom", 84);
            float proposed = Math.max(low, Math.min(high,
                current + nextStep * BridgeConfig.number(this, "camera_step", 3)));
            if (Math.abs(proposed - current) < 0.01f) {
                queuedCameraSteps -= nextStep;
                cameraStatus = "camera: at slider travel limit";
                nextStep = 0;
            }
        }
        final int cameraStepSign = nextStep;
        if (!wantLeft && !wantRight && leftStroke == null && rightStroke == null
                && cameraStepSign == 0) {
            gestureResult = "touch: idle (no fingers)";
            return;
        }
        if (windowManager == null) return;

        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        float width = dm.widthPixels, height = dm.heightPixels;
        float lr = Math.min(width, height) *
            BridgeConfig.number(this, "left_radius", 19) / 100f;
        float rr = Math.min(width, height) *
            BridgeConfig.number(this, "right_radius", 19) / 100f;
        float leftCx = width * BridgeConfig.number(this, "left_x", 16) / 100f;
        float leftCy = height * BridgeConfig.number(this, "left_y", 71) / 100f;
        float rightCx = width * BridgeConfig.number(this, "right_x", 85) / 100f;
        float rightCy = height * BridgeConfig.number(this, "right_y", 71) / 100f;

        // Only move the corresponding virtual joystick. Each finger starts,
        // continues, and ends independently.
        final float targetLX = wantLeft ? leftCx + norm("LS_X") * lr : leftX;
        final float targetLY = wantLeft ? leftCy - norm("LS_Y") * lr : leftY;
        final float targetRX = wantRight ? rightCx + norm("RS_X") * rr : rightX;
        final float targetRY = wantRight ? rightCy - norm("RS_Y") * rr : rightY;

        GestureDescription.StrokeDescription nextLeft = null, nextRight = null;
        GestureDescription.Builder builder = new GestureDescription.Builder();
        float proposedCameraPercent = cameraYPercent;
        try {
            if (wantLeft || leftStroke != null) {
                float fromX = leftStroke == null ? leftCx : leftX;
                float fromY = leftStroke == null ? leftCy : leftY;
                Path lp = path(fromX, fromY, targetLX, targetLY);
                nextLeft = leftStroke == null
                    ? new GestureDescription.StrokeDescription(lp, 0, FRAME_DURATION_MS, wantLeft)
                    : leftStroke.continueStroke(lp, 0, FRAME_DURATION_MS, wantLeft);
                builder.addStroke(nextLeft);
            }
            if (wantRight || rightStroke != null) {
                float fromX = rightStroke == null ? rightCx : rightX;
                float fromY = rightStroke == null ? rightCy : rightY;
                Path rp = path(fromX, fromY, targetRX, targetRY);
                nextRight = rightStroke == null
                    ? new GestureDescription.StrokeDescription(rp, 0, FRAME_DURATION_MS, wantRight)
                    : rightStroke.continueStroke(rp, 0, FRAME_DURATION_MS, wantRight);
                builder.addStroke(nextRight);
            }

            if (cameraStepSign != 0) {
                if (Float.isNaN(cameraYPercent)) {
                    cameraYPercent = BridgeConfig.number(this, "camera_y", 70);
                }
                int low = BridgeConfig.number(this, "camera_top", 50);
                int high = BridgeConfig.number(this, "camera_bottom", 84);
                float step = BridgeConfig.number(this, "camera_step", 3);
                proposedCameraPercent = Math.max(low, Math.min(high,
                    cameraYPercent + cameraStepSign * step));
                if (Math.abs(proposedCameraPercent - cameraYPercent) > 0.01f) {
                    float x = width * BridgeConfig.number(this, "camera_x", 95) / 100f;
                    Path cameraPath = path(x, height * cameraYPercent / 100f,
                        x, height * proposedCameraPercent / 100f);
                    builder.addStroke(new GestureDescription.StrokeDescription(
                        cameraPath, 0, FRAME_DURATION_MS, false));
                }
            }

            final float nextCameraPercent = proposedCameraPercent;
            final GestureDescription.StrokeDescription newLeft = nextLeft;
            final GestureDescription.StrokeDescription newRight = nextRight;
            GestureDescription gesture = builder.build();
            busy = true;
            gestureResult = "touch: L=" + (wantLeft ? "ON" : "OFF") +
                " R=" + (wantRight ? "ON" : "OFF");
            final int token = ++generation;
            long dispatchAt = SystemClock.uptimeMillis();
            if (lastGestureCompletedMs > 0) {
                long gap = dispatchAt - lastGestureCompletedMs;
                if (gap > largestDispatchGapMs) largestDispatchGapMs = gap;
            }
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    if (token != generation) return;
                    leftStroke = wantLeft ? newLeft : null;
                    rightStroke = wantRight ? newRight : null;
                    if (wantLeft) { leftX = targetLX; leftY = targetLY; }
                    if (wantRight) { rightX = targetRX; rightY = targetRY; }
                    if (cameraStepSign != 0) {
                        cameraYPercent = nextCameraPercent;
                        queuedCameraSteps -= cameraStepSign;
                        cameraStatus = "camera: slider Y=" + Math.round(cameraYPercent) +
                            "% remaining=" + queuedCameraSteps;
                    }
                    busy = false;
                    completedFrames++;
                    gestureResult = "touch: OK " + completedFrames +
                        " (L=" + (wantLeft ? 1 : 0) + " R=" + (wantRight ? 1 : 0) + ")";
                    lastGestureCompletedMs = SystemClock.uptimeMillis();
                    if (active() && (stickMoving() ||
                            leftStroke != null || rightStroke != null ||
                            (cameraAllowed && queuedCameraSteps != 0))) continueImmediately();
                }
                @Override public void onCancelled(GestureDescription g) {
                    if (token != generation) return;
                    busy = false;
                    leftStroke = null;
                    rightStroke = null;
                    cancelledFrames++;
                    gestureResult = "touch: CANCELLED " + cancelledFrames;
                    CrashNotes.record(VirtualFlightAccessibilityService.this,
                        "Gesture cancelled; count=" + cancelledFrames);
                    onGestureCancel();
                    if (active() && stickMoving()) handler.postDelayed(() -> scheduleFrame(), 200);
                }
            }, handler);
            if (!accepted) {
                busy = false;
                leftStroke = null;
                rightStroke = null;
                gestureResult = "touch: Android rejected dispatch";
                onGestureCancel();
                if (active() && stickMoving()) handler.postDelayed(() -> scheduleFrame(), 200);
            }
        } catch (Throwable t) {
            busy = false;
            leftStroke = null;
            rightStroke = null;
            gestureResult = "touch: exception " + t.getClass().getSimpleName();
            CrashNotes.exception(this, "Gesture dispatch", t);
            Log.e(TAG, "Gesture bridge failed", t);
            onGestureCancel();
        }
    }

    private void onGestureCancel() {
        long now = SystemClock.uptimeMillis();
        if (now - cancelWindowStarted > CANCEL_WINDOW_MS) {
            cancelWindowStarted = now;
            recentCancels = 0;
        }
        recentCancels++;
        if (recentCancels >= CANCEL_LIMIT) {
            gestureResult = "AUTO STOP: excessive gesture cancellations";
            BridgeConfig.prefs(this).edit().putString("last_stop_reason",
                "AUTO STOP: " + recentCancels + " cancelled touches in 10 seconds").apply();
            CrashNotes.record(this, gestureResult);
            android.widget.Toast.makeText(this,
                "Bridge stopped: too many cancelled touches", android.widget.Toast.LENGTH_LONG).show();
            BridgeConfig.setArmed(this, false);
            stopGesture();
            updateOverlay();
            Log.e(TAG, "Disarmed bridge due to repeated Android gesture cancellations");
        }
    }

    private void stopGesture() {
        cancelDiagnostic();
        generation++;
        busy = false;
        if (leftStroke != null || rightStroke != null) {
            try {
                GestureDescription.Builder builder = new GestureDescription.Builder();
                if (leftStroke != null) {
                    Path lp = path(leftX, leftY, leftX, leftY);
                    builder.addStroke(leftStroke.continueStroke(lp, 0, 40, false));
                }
                if (rightStroke != null) {
                    Path rp = path(rightX, rightY, rightX, rightY);
                    builder.addStroke(rightStroke.continueStroke(rp, 0, 40, false));
                }
                dispatchGesture(builder.build(), null, handler);
            } catch (Throwable t) {
                CrashNotes.record(this, "Could not finish touch: " + t);
                Log.w(TAG, "Could not finish touch sequence: " + t.getMessage());
            }
        }
        leftStroke = null;
        rightStroke = null;
    }


    /**
     * Simulator-only comparison against the successful ADB 10-second swipe.
     * One dispatch -> one continuous MotionEvent sequence. This deliberately
     * does not use chained 200 ms gestures and never reads the live sticks.
     * It is a diagnostic, NOT an implementation of real-time RC input.
     */
    private void testLongGesture(boolean both) {
        if (!eligibleApp() || windowManager == null) {
            diagnosticResult = "10s test: simulator not paired";
            return;
        }
        if (diagnosticActive) return;
        stopGesture();
        diagnosticActive = true;
        diagnosticStarted = SystemClock.uptimeMillis();
        testGestureUntil = diagnosticStarted + 12000;
        final int token = ++generation;
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        float w = dm.widthPixels, h = dm.heightPixels;
        float lr = Math.min(w, h) *
            BridgeConfig.number(this, "left_radius", 19) / 100f;
        float rr = Math.min(w, h) *
            BridgeConfig.number(this, "right_radius", 19) / 100f;
        float lx = w * BridgeConfig.number(this, "left_x", 16) / 100f;
        float ly = h * BridgeConfig.number(this, "left_y", 71) / 100f;
        float rx = w * BridgeConfig.number(this, "right_x", 85) / 100f;
        float ry = h * BridgeConfig.number(this, "right_y", 71) / 100f;
        // The tested ADB motion travelled from centre towards the top;
        // for the dual-finger diagnostic, mirror the second finger's travel.
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(
            path(lx, ly, lx, ly - lr * 0.65f), 0, 10000, false));
        if (both) {
            b.addStroke(new GestureDescription.StrokeDescription(
                path(rx, ry, rx, ry - rr * 0.65f), 0, 10000, false));
        }
        diagnosticResult = "10s " + (both ? "DUAL" : "LEFT") + ": running";
        gestureResult = diagnosticResult;
        updateOverlay();
        try {
            boolean accepted = dispatchGesture(b.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    if (token != generation) return;
                    diagnosticActive = false;
                    testGestureUntil = 0;
                    diagnosticPassed++;
                    long duration = SystemClock.uptimeMillis() - diagnosticStarted;
                    diagnosticResult = "10s " + (both ? "DUAL" : "LEFT") +
                        ": COMPLETED after " + duration + "ms";
                    gestureResult = diagnosticResult;
                    CrashNotes.record(VirtualFlightAccessibilityService.this, diagnosticResult);
                    BridgeConfig.prefs(VirtualFlightAccessibilityService.this).edit()
                        .putString("last_diagnostic_result", diagnosticResult).apply();
                    updateOverlay();
                    scheduleFrame();
                }
                @Override public void onCancelled(GestureDescription g) {
                    if (token != generation) return;
                    diagnosticActive = false;
                    testGestureUntil = 0;
                    diagnosticCancelled++;
                    long duration = SystemClock.uptimeMillis() - diagnosticStarted;
                    diagnosticResult = "10s " + (both ? "DUAL" : "LEFT") +
                        ": CANCELLED after " + duration + "ms";
                    gestureResult = diagnosticResult;
                    CrashNotes.record(VirtualFlightAccessibilityService.this, diagnosticResult);
                    BridgeConfig.prefs(VirtualFlightAccessibilityService.this).edit()
                        .putString("last_diagnostic_result", diagnosticResult).apply();
                    updateOverlay();
                    // No automatic restart: any cancellation is important evidence.
                }
            }, handler);
            if (!accepted) {
                diagnosticActive = false;
                testGestureUntil = 0;
                diagnosticResult = "10s gesture rejected by Android";
                gestureResult = diagnosticResult;
                CrashNotes.record(this, diagnosticResult);
                BridgeConfig.prefs(this).edit()
                    .putString("last_diagnostic_result", diagnosticResult).apply();
            }
        } catch (Throwable t) {
            diagnosticActive = false;
            testGestureUntil = 0;
            diagnosticResult = "10s test failed: " + t.getClass().getSimpleName();
            gestureResult = diagnosticResult;
            CrashNotes.exception(this, "10s gesture test", t);
            BridgeConfig.prefs(this).edit()
                .putString("last_diagnostic_result", diagnosticResult).apply();
        }
        updateOverlay();
    }

    /**
     * Android 11 screenshot readback. This ONLY measures Unity HUD circles:
     * a screenshot never becomes a remote flight command.
     */
    private void captureCenters() {
        if (Build.VERSION.SDK_INT < 30 || !active() || calibrationRunning ||
                diagnosticActive || busy || leftStroke != null || rightStroke != null) {
            if (!active()) calibrationStatus = "auto-cal: game/SDK not active";
            return;
        }
        calibrationRunning = true;
        lastCalibrationAt = SystemClock.uptimeMillis();
        calibrationStatus = "auto-cal: capturing game screenshot";
        removeCenterMarkers(); // green calibration guides would confuse the ring detector
        handler.postDelayed(() -> {
            if (!active()) {
                calibrationRunning = false;
                calibrationStatus = "auto-cal: simulator not active";
                updateCenterMarkers();
                return;
            }
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult screenshot) {
                        Bitmap copy = null;
                        try {
                            HardwareBuffer hb = screenshot.getHardwareBuffer();
                            try {
                                Bitmap image = Bitmap.wrapHardwareBuffer(hb, screenshot.getColorSpace());
                                if (image != null) copy = image.copy(Bitmap.Config.ARGB_8888, false);
                            } finally {
                                hb.close();
                            }
                        } catch (Throwable t) {
                            calibrationStatus = "auto-cal: screenshot read error " +
                                t.getClass().getSimpleName();
                            CrashNotes.exception(VirtualFlightAccessibilityService.this,
                                "Screenshot readback", t);
                        }
                        if (copy == null) {
                            calibrationRunning = false;
                            updateCenterMarkers();
                            return;
                        }
                        final Bitmap source = copy;
                        new Thread(() -> {
                            JoystickScreenDetector.Result result;
                            try { result = JoystickScreenDetector.detect(source,
                                BridgeConfig.number(VirtualFlightAccessibilityService.this,"left_x",16),
                                BridgeConfig.number(VirtualFlightAccessibilityService.this,"left_y",71),
                                BridgeConfig.number(VirtualFlightAccessibilityService.this,"right_x",85),
                                BridgeConfig.number(VirtualFlightAccessibilityService.this,"right_y",71),
                                BridgeConfig.number(VirtualFlightAccessibilityService.this,"left_radius",19),
                                BridgeConfig.number(VirtualFlightAccessibilityService.this,"right_radius",19)); }
                            catch (Throwable e) {
                                result = new JoystickScreenDetector.Result(null,null,
                                    "analysis failed: " + e.getClass().getSimpleName());
                            } finally { source.recycle(); }
                            final JoystickScreenDetector.Result found = result;
                            handler.post(() -> {
                                calibrationRunning = false;
                                if (found.valid()) {
                                    int lx = Math.round(found.left.x);
                                    int ly = Math.round(found.left.y);
                                    int rx = Math.round(found.right.x);
                                    int ry = Math.round(found.right.y);
                                    int lr = Math.max(4, Math.min(25,
                                        Math.round(found.left.radius * .88f)));
                                    int rr = Math.max(4, Math.min(25,
                                        Math.round(found.right.radius * .88f)));
                                    BridgeConfig.prefs(VirtualFlightAccessibilityService.this)
                                        .edit().putInt("left_x",lx).putInt("left_y",ly)
                                        .putInt("right_x",rx).putInt("right_y",ry)
                                        .putInt("left_radius",lr).putInt("right_radius",rr)
                                        .putString("last_calibration",found.detail).apply();
                                    calibrationStatus = "CAL OK L="+lx+"/"+ly+" R="+rx+"/"+ry+
                                        " r="+lr+"/"+rr;
                                    CrashNotes.record(VirtualFlightAccessibilityService.this,
                                        "Screen auto-calibration: "+found.detail);
                                } else {
                                    calibrationStatus = "CAL NO MATCH - manual values kept";
                                    CrashNotes.record(VirtualFlightAccessibilityService.this,
                                        "Screen auto-calibration rejected: "+found.detail);
                                    BridgeConfig.prefs(VirtualFlightAccessibilityService.this)
                                        .edit().putString("last_calibration",
                                            "NOT APPLIED: "+found.detail).apply();
                                }
                                updateCenterMarkers();
                                updateOverlay();
                            });
                        }, "vflight-screen-cal").start();
                    }
                    @Override public void onFailure(int errorCode) {
                        calibrationRunning = false;
                        calibrationStatus = "auto-cal screenshot failed: "+errorCode;
                        BridgeConfig.prefs(VirtualFlightAccessibilityService.this)
                            .edit().putString("last_calibration",calibrationStatus).apply();
                        updateCenterMarkers();
                        updateOverlay();
                    }
                });
            } catch (Throwable t) {
                calibrationRunning = false;
                calibrationStatus = "auto-cal permission/error: "+t.getClass().getSimpleName();
                CrashNotes.exception(this,"takeScreenshot",t);
                updateCenterMarkers();
            }
        }, 180);
    }

    private void cancelDiagnostic() {
        if (!diagnosticActive) return;
        diagnosticActive = false;
        testGestureUntil = 0;
        diagnosticResult = "10s test: manually interrupted";
        // Any new dispatch cancels the active injected gesture on Android 11.
        // Use one 1ms tap in the non-control top-left corner to release fingers.
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(
                path(2f, 2f, 2f, 2f), 0, 1, false));
            dispatchGesture(b.build(), null, handler);
        } catch (Throwable t) {
            CrashNotes.exception(this, "Cancel diagnostic", t);
        }
    }


    private final class MarkerView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        MarkerView(Context context) { super(context); setWillNotDraw(false); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float leftRadius = Math.min(w, h) *
                BridgeConfig.number(VirtualFlightAccessibilityService.this, "left_radius", 19) / 100f;
            float rightRadius = Math.min(w, h) *
                BridgeConfig.number(VirtualFlightAccessibilityService.this, "right_radius", 19) / 100f;
            drawMarker(canvas, "L", w * BridgeConfig.number(
                VirtualFlightAccessibilityService.this, "left_x", 20) / 100f,
                h * BridgeConfig.number(VirtualFlightAccessibilityService.this, "left_y", 71) / 100f,
                leftRadius);
            drawMarker(canvas, "R", w * BridgeConfig.number(
                VirtualFlightAccessibilityService.this, "right_x", 83) / 100f,
                h * BridgeConfig.number(VirtualFlightAccessibilityService.this, "right_y", 71) / 100f,
                rightRadius);
        }
        private void drawMarker(Canvas c, String label, float x, float y, float r) {
            p.setColor(Color.argb(225, 70, 255, 155));
            p.setStrokeWidth(3f);
            p.setStyle(Paint.Style.STROKE);
            // Draw the full detected joystick travel ring, not an arbitrary
            // 25% mini-ring, so the user can verify screen alignment visually.
            c.drawCircle(x, y, Math.max(10f, r), p);
            c.drawCircle(x, y, 10f, p);
            c.drawLine(x - 24, y, x + 24, y, p);
            c.drawLine(x, y - 24, x, y + 24, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(26f);
            c.drawText(label, x + 18, y - 18, p);
        }
    }
    private void updateCenterMarkers() {
        boolean show = eligibleApp() && !calibrationRunning &&
            BridgeConfig.prefs(this).getBoolean("show_centers", true);
        if (!show) {
            removeCenterMarkers();
            return;
        }
        if (centerMarkers != null) {
            centerMarkers.invalidate();
            return;
        }
        try {
            MarkerView view = new MarkerView(this);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            windowManager.addView(view, params);
            centerMarkers = view;
        } catch (Throwable t) {
            CrashNotes.record(this, "Cannot show calibration markers: " + t);
        }
    }
    private void removeCenterMarkers() {
        if (centerMarkers == null) return;
        try { windowManager.removeView(centerMarkers); } catch (Throwable ignored) {}
        centerMarkers = null;
    }

    private void updateOverlay() {
        if (!BridgeConfig.armed(this) || foregroundPackage.isEmpty() ||
                foregroundPackage.equals(getPackageName()) || windowManager == null) {
            removeOverlay();
            return;
        }
        updateCenterMarkers();
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
                boolean game = BridgeConfig.TARGET_DEFAULT.equals(foregroundPackage);
                boolean compatibility = "com.android.launcher3".equals(foregroundPackage);
                if (!game && !compatibility) {
                    cameraStatus = "Pairing blocked for " + foregroundPackage;
                    return;
                }
                // User-initiated pairing only. Some TKPL2 builds incorrectly
                // report launcher3 in fullscreen Unity game (seen on 0.4 photos).
                // In launcher3 compatibility mode STOP before leaving the game.
                BridgeConfig.prefs(this).edit().putString("target", foregroundPackage).apply();
                gestureResult = "Paired: " + foregroundPackage;
                stopGesture();
                updateOverlay();
                scheduleFrame();
            });
            pairButton = pair;
            buttons.addView(pair);

            Button test = new Button(this);
            test.setText("TEST 10s L");
            test.setTextSize(10);
            test.setAllCaps(false);
            test.setOnClickListener(v -> testLongGesture(false));
            testButton = test;
            buttons.addView(test);

            Button bothTest = new Button(this);
            bothTest.setText("TEST 10s 2X");
            bothTest.setTextSize(10);
            bothTest.setAllCaps(false);
            bothTest.setOnClickListener(v -> testLongGesture(true));
            testBothButton = bothTest;
            buttons.addView(bothTest);
            Button autoCal = new Button(this);
            autoCal.setText("AUTO CAL");
            autoCal.setTextSize(10);
            autoCal.setAllCaps(false);
            autoCal.setOnClickListener(v -> captureCenters());
            autoCalButton = autoCal;
            buttons.addView(autoCal);

            Button stop = new Button(this);
            stop.setText("STOP BRIDGE");
            stop.setTextSize(10);
            stop.setAllCaps(false);
            stop.setTextColor(Color.WHITE);
            stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(175, 45, 45)));
            stop.setOnClickListener(v -> {
                cancelDiagnostic();
                BridgeConfig.prefs(this).edit().putString("last_stop_reason", "Stopped with red STOP button").apply();
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
            debugLabel.setMaxLines(11);
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
        if (testBothButton != null) testBothButton.setVisibility(eligibleApp() ? View.VISIBLE : View.GONE);
        if (autoCalButton != null) autoCalButton.setVisibility(eligibleApp() ? View.VISIBLE : View.GONE);
        if (debugLabel != null) {
            String sdk = lastHeartbeat == 0 ? "waiting for SDK heartbeat" :
                (SystemClock.uptimeMillis() - lastHeartbeat < 2500 ?
                    "SDK heartbeat OK" : "SDK heartbeat LOST");
            String diagnostic = "App: " + foregroundPackage +
                (eligibleApp() ? " [PAIRED]" : " [NOT PAIRED]") +
                "\n" + sdk + " | " + gestureResult +
                "\nMode=" + (mode() == 1 ? "LEFT" : mode() == 2 ? "RIGHT" : "BOTH") +
                " cancel=" + cancelledFrames + " gapMax=" + largestDispatchGapMs + "ms" +
                ("com.android.launcher3".equals(BridgeConfig.target(this)) ? " [COMPATIBILITY]" : "") +
                "\n" + cameraStatus +
                "\n" + diagnosticResult + " (OK " + diagnosticPassed +
                ", cancelled " + diagnosticCancelled + ")" +
                "\n" + calibrationStatus +
                " LS=" + (int)raw("LS_X") + "," + (int)raw("LS_Y") +
                " RS=" + (int)raw("RS_X") + "," + (int)raw("RS_Y");
            if (!diagnostic.contentEquals(debugLabel.getText())) debugLabel.setText(diagnostic);
        }
    }

    private void removeOverlay() {
        if (overlay == null) {
            removeCenterMarkers();
            return;
        }
        try { windowManager.removeView(overlay); } catch (Throwable ignored) {}
        overlay = null;
        removeCenterMarkers();
        pairButton = null;
        testButton = null;
        testBothButton = null;
        autoCalButton = null;
        debugLabel = null;
    }
}
