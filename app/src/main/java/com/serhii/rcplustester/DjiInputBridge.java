package com.serhii.rcplustester;

import dji.sdk.keyvalue.key.DJIKeyInfo;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.key.RemoteControllerKey;
import dji.v5.manager.KeyManager;

/** Read-only subscriptions to supported MSDK V5 remote-controller inputs. */
public final class DjiInputBridge {
    public interface Sink {
        void onInput(String control, String signal, String detail, boolean pressed);
    }
    private static volatile Sink sink;
    private static volatile String status = "waiting for DJI SDK";
    private static boolean started = false;
    private static final Object OWNER = new Object();

    private DjiInputBridge() { }

    public static void attach(Sink value) { sink = value; }
    public static void detach() { sink = null; }
    public static String status() { return status; }
    public static void setStatus(String value) {
        status = value;
        Sink listener = sink;
        if (listener != null) listener.onInput(null, "SDK:STATUS", value, false);
    }
    private static void emit(String control, String code, Object value) {
        Sink listener = sink;
        if (listener != null) {
            boolean pressed = !(value instanceof Boolean) || Boolean.TRUE.equals(value);
            listener.onInput(control, "SDK:" + code, code + " = " + value, pressed);
        }
    }
    private static <T> void listen(DJIKeyInfo<T> key, String name, String control) {
        KeyManager.getInstance().listen(KeyTools.createKey(key), OWNER, (previous, current) -> {
            if (current != null) emit(control, name, current);
        });
    }
    public static synchronized void start() {
        if (started) return;
        started = true;
        try {
            listen(RemoteControllerKey.KeyConnection, "CONNECTED", null);
            listen(RemoteControllerKey.KeyCustomButton1Down, "C1", "C1");
            listen(RemoteControllerKey.KeyCustomButton2Down, "C2", "C2");
            listen(RemoteControllerKey.KeyCustomButton3Down, "C3", "C3");
            listen(RemoteControllerKey.KeyStickLeftHorizontal, "LS_X", "LS_X");
            listen(RemoteControllerKey.KeyStickLeftVertical, "LS_Y", "LS_Y");
            listen(RemoteControllerKey.KeyStickRightHorizontal, "RS_X", "RS_X");
            listen(RemoteControllerKey.KeyStickRightVertical, "RS_Y", "RS_Y");
            listen(RemoteControllerKey.KeyLeftDial, "LEFT_DIAL", "LEFT_DIAL");
            listen(RemoteControllerKey.KeyRightDial, "RIGHT_DIAL", "RIGHT_DIAL");
            listen(RemoteControllerKey.KeyScrollWheel, "ZOOM_DIAL", "ZOOM_DIAL");
            listen(RemoteControllerKey.KeyShutterButtonDown, "SHUTTER", "SHUTTER");
            listen(RemoteControllerKey.KeyRecordButtonDown, "RECORD", "RECORD");
            listen(RemoteControllerKey.KeyGoHomeButtonDown, "RTH", "RTH");
            listen(RemoteControllerKey.KeyFiveDimensionPressedStatus, "5D", null);
            setStatus("SDK listeners active (actual coverage depends on controller)");
        } catch (Throwable error) {
            started = false;
            setStatus("SDK listener error: " + error.getMessage());
        }
    }
}
