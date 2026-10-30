package com.serhii.rcplustester;

import android.content.Context;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Shared app-private checkpoint file for Android UI and the :sdk process.
 * Captures Java exceptions and last checkpoint even if a native crash kills the SDK process.
 */
public final class CrashNotes {
    private static final String NAME = "rcplus_sdk_trace.txt";
    private CrashNotes() {}

    public static void record(Context context, String message) {
        if (context == null) return;
        File f = new File(context.getFilesDir(), NAME);
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = date + " pid=" + Process.myPid() + " " + message + "\n";
        try {
            if (f.exists() && f.length() > 160000) {
                File old = new File(context.getFilesDir(), NAME + ".old");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Throwable ignored) { }
    }
    public static void exception(Context context, String phase, Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        record(context, phase + " ERROR: " + sw.toString());
    }
    public static String read(Context context) {
        try {
            File file = new File(context.getFilesDir(), NAME);
            if (!file.exists()) return "No SDK trace yet. Start DJI SDK to collect checkpoints.";
            byte[] content = new byte[(int) Math.min(file.length(), 160000)];
            try (java.io.FileInputStream stream = new java.io.FileInputStream(file)) {
                int read = stream.read(content);
                if (read <= 0) return "SDK trace file is empty.";
                return new String(content, 0, read, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return "Trace read failed: " + t.getClass().getName() + ": " + t.getMessage();
        }
    }
    public static void installJavaCrashHandler(Context app) {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            exception(app, "UNCAUGHT in thread " + thread.getName(), ex);
            if (original != null) original.uncaughtException(thread, ex);
        });
    }
}
