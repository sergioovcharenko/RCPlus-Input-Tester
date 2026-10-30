package com.serhii.rcplustester;

import android.app.Application;
import android.content.Context;

/**
 * SDK native startup runs only in :sdk process.
 * Collect checkpoint/Java crash details so a crash cannot erase the explanation.
 */
public final class MyApplication extends Application {
    @Override public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        String process = android.app.Application.getProcessName();
        if (process != null && process.endsWith(":sdk")) {
            CrashNotes.record(this, "SDK_PROCESS_ATTACH start; Android " +
                android.os.Build.VERSION.RELEASE + " API=" + android.os.Build.VERSION.SDK_INT +
                " ABI=" + java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS));
            CrashNotes.installJavaCrashHandler(this);
            try {
                CrashNotes.record(this, "BEFORE DJI Helper.install");
                com.cySdkyc.clx.Helper.install(this);
                CrashNotes.record(this, "AFTER DJI Helper.install OK");
            } catch (Throwable ex) {
                CrashNotes.exception(this, "DJI Helper.install", ex);
                throw ex;
            }
        } else {
            CrashNotes.record(this, "UI_PROCESS_ATTACH OK");
        }
    }
}
