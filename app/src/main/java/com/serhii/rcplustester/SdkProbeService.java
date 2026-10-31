package com.serhii.rcplustester;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import dji.v5.common.error.IDJIError;
import dji.v5.common.register.DJISDKInitEvent;
import dji.v5.manager.SDKManager;
import dji.v5.manager.interfaces.SDKManagerCallback;

/**
 * Runs in separate :sdk process. It cannot issue flight-control commands.
 * The UI only receives passive SDK events via app-scoped broadcasts.
 */
public final class SdkProbeService extends Service {
    public static final String ACTION_EVENT = "com.serhii.rcplustester.DJI_SDK_EVENT";
    public static final String ACTION_READ_FSN = "com.serhii.rcplustester.READ_FSN";
    public static final String ACTION_BRIDGE_START = "com.serhii.rcplustester.BRIDGE_START";
    public static final String ACTION_BRIDGE_STOP = "com.serhii.rcplustester.BRIDGE_STOP";
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private boolean registered;
    private boolean bridgeForeground;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!bridgeForeground) return;
            if (registered) broadcast(null, "SDK:BRIDGE_HEARTBEAT",
                "MSDK registered; simulator input bridge alive", false);
            heartbeatHandler.postDelayed(this, 700);
        }
    };
    public static final String EXTRA_CONTROL = "control";
    public static final String EXTRA_SIGNAL = "signal";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_PRESSED = "pressed";
    private final IBinder binder = new Binder();
    private final BroadcastReceiver probeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_READ_FSN.equals(intent.getAction())) return;
            CrashNotes.record(SdkProbeService.this, "UI requested explicit F/S/N SDK getValue");
            DjiInputBridge.probeFlightMode();
        }
    };

    private void broadcast(String control, String signal, String detail, boolean pressed) {
        Intent i = new Intent(ACTION_EVENT);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_CONTROL, control);
        i.putExtra(EXTRA_SIGNAL, signal);
        i.putExtra(EXTRA_DETAIL, detail);
        i.putExtra(EXTRA_PRESSED, pressed);
        sendBroadcast(i);
        if ("SDK:STATUS".equals(signal)) CrashNotes.record(this, "SDK STATUS " + detail);
    }
    private void status(String value) {
        broadcast(null, "SDK:STATUS", value, false);
    }
    @Override public void onCreate() {
        super.onCreate();
        CrashNotes.record(this, "SDK_SERVICE onCreate");
        registerReceiver(probeReceiver, new IntentFilter(ACTION_READ_FSN));
        DjiInputBridge.attach((control, signal, detail, pressed) -> broadcast(control, signal, detail, pressed));
        status("DJI SDK is starting in isolated process...");
        try {
            CrashNotes.record(this, "BEFORE SDKManager.getInstance().init");
            SDKManager.getInstance().init(this, new SDKManagerCallback() {
                @Override public void onInitProcess(DJISDKInitEvent event, int progress) {
                    CrashNotes.record(SdkProbeService.this, "onInitProcess " + event + " " + progress);
                    if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                        status("DJI SDK initialized; registering app...");
                        try { SDKManager.getInstance().registerApp(); }
                        catch (Throwable e) { status("DJI register error: " + e.toString()); }
                    }
                }
                @Override public void onRegisterSuccess() {
                    status("DJI SDK registered");
                    DjiInputBridge.start();
                    registered = true;
                }
                @Override public void onRegisterFailure(IDJIError error) {
                    status("DJI registration failed: " + String.valueOf(error));
                }
                @Override public void onProductConnect(int productId) {
                    status("DJI controller/product connected: " + productId);
                    DjiInputBridge.start();
                }
                @Override public void onProductDisconnect(int productId) {
                    status("DJI controller/product disconnected: " + productId);
                }
                @Override public void onProductChanged(int productId) {
                    status("DJI product changed: " + productId);
                }
                @Override public void onDatabaseDownloadProgress(long current, long total) {}
            });
            CrashNotes.record(this, "AFTER SDKManager.init returned");
        } catch (Throwable e) {
            CrashNotes.exception(this, "DJI SDK initialization", e);
            status("DJI SDK initialization exception: " + e.toString());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_BRIDGE_STOP.equals(action)) {
            BridgeConfig.setArmed(this, false);
            bridgeForeground = false;
            heartbeatHandler.removeCallbacks(heartbeat);
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_BRIDGE_START.equals(action)) {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    NotificationChannel channel = new NotificationChannel(
                        "vf_bridge_channel", "Virtual Flight stick bridge",
                        NotificationManager.IMPORTANCE_LOW);
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    if (manager != null) manager.createNotificationChannel(channel);
                }
                Intent stop = new Intent(this, SdkProbeService.class);
                stop.setAction(ACTION_BRIDGE_STOP);
                PendingIntent stopPending = PendingIntent.getService(this, 95, stop,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "vf_bridge_channel")
                    : new Notification.Builder(this);
                Notification note = builder
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Virtual Flight Stick Bridge")
                    .setContentText("Simulator only. STOP cancels bridge.")
                    .setOngoing(true)
                    .addAction(android.R.drawable.ic_delete, "STOP", stopPending)
                    .build();
                startForeground(8471, note);
                bridgeForeground = true;
                heartbeatHandler.removeCallbacks(heartbeat);
                heartbeatHandler.post(heartbeat);
                status("Simulator bridge SDK active (foreground)");
            } catch (Throwable error) {
                status("Could not start simulator bridge: " + error);
                BridgeConfig.setArmed(this, false);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        bridgeForeground = false;
        heartbeatHandler.removeCallbacks(heartbeat);
        BridgeConfig.setArmed(this, false);
        try { unregisterReceiver(probeReceiver); } catch (Exception ignored) {}
        DjiInputBridge.detach();
        super.onDestroy();
    }
}
