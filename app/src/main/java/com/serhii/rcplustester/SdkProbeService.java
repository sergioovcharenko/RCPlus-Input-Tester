package com.serhii.rcplustester;

import android.app.Service;
import android.content.Intent;
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
    public static final String EXTRA_CONTROL = "control";
    public static final String EXTRA_SIGNAL = "signal";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_PRESSED = "pressed";
    private final IBinder binder = new Binder();

    private void broadcast(String control, String signal, String detail, boolean pressed) {
        Intent i = new Intent(ACTION_EVENT);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_CONTROL, control);
        i.putExtra(EXTRA_SIGNAL, signal);
        i.putExtra(EXTRA_DETAIL, detail);
        i.putExtra(EXTRA_PRESSED, pressed);
        sendBroadcast(i);
    }
    private void status(String value) {
        broadcast(null, "SDK:STATUS", value, false);
    }
    @Override public void onCreate() {
        super.onCreate();
        DjiInputBridge.attach((control, signal, detail, pressed) -> broadcast(control, signal, detail, pressed));
        status("DJI SDK is starting in isolated process...");
        try {
            SDKManager.getInstance().init(this, new SDKManagerCallback() {
                @Override public void onInitProcess(DJISDKInitEvent event, int progress) {
                    if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                        status("DJI SDK initialized; registering app...");
                        try { SDKManager.getInstance().registerApp(); }
                        catch (Throwable e) { status("DJI register error: " + e.toString()); }
                    }
                }
                @Override public void onRegisterSuccess() {
                    status("DJI SDK registered");
                    DjiInputBridge.start();
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
        } catch (Throwable e) {
            status("DJI SDK initialization exception: " + e.toString());
        }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        DjiInputBridge.detach();
        super.onDestroy();
    }
}
