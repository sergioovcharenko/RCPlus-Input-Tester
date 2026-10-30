package com.serhii.rcplustester;

import android.app.Application;
import android.content.Context;

/**
 * Keep the user-facing UI process independent from DJI native libraries.
 * DJI SDK initialization is limited to the :sdk service process; if it crashes
 * on unsupported hardware, the input tester UI stays on screen.
 */
public final class MyApplication extends Application {
    @Override public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        String name = android.app.Application.getProcessName();
        if (name != null && name.endsWith(":sdk")) {
            com.cySdkyc.clx.Helper.install(this);
        }
    }
}
