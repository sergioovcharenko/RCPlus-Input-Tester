package com.serhii.rcplustester;

import android.app.Application;
import android.content.Context;
import dji.v5.common.error.IDJIError;
import dji.v5.common.register.DJISDKInitEvent;
import dji.v5.manager.SDKManager;
import dji.v5.manager.interfaces.SDKManagerCallback;

/** Initializes DJI MSDK without ever sending flight commands. */
public final class MyApplication extends Application {
    @Override public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        com.cySdkyc.clx.Helper.install(this);
    }
    @Override public void onCreate() {
        super.onCreate();
        DjiInputBridge.setStatus("SDK initialization");
        SDKManager.getInstance().init(this, new SDKManagerCallback() {
            @Override public void onInitProcess(DJISDKInitEvent event, int progress) {
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    DjiInputBridge.setStatus("Registering DJI app...");
                    SDKManager.getInstance().registerApp();
                }
            }
            @Override public void onRegisterSuccess() {
                DjiInputBridge.setStatus("DJI SDK registered");
                DjiInputBridge.start();
            }
            @Override public void onRegisterFailure(IDJIError error) {
                DjiInputBridge.setStatus("Registration failed: " + String.valueOf(error));
            }
            @Override public void onProductConnect(int productId) {
                DjiInputBridge.setStatus("Product connection: " + productId);
                DjiInputBridge.start();
            }
            @Override public void onProductDisconnect(int productId) {
                DjiInputBridge.setStatus("Product disconnected: " + productId);
            }
            @Override public void onProductChanged(int productId) {
                DjiInputBridge.setStatus("Product changed: " + productId);
            }
            @Override public void onDatabaseDownloadProgress(long current, long total) { }
        });
    }
}
