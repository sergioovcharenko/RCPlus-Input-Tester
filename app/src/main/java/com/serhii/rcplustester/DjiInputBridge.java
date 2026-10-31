package com.serhii.rcplustester;

import dji.sdk.keyvalue.key.DJIKeyInfo;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.key.RemoteControllerKey;
import dji.sdk.keyvalue.key.FlightControllerKey;
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
            // In MSDK V5, flight-mode switch state is published under FlightControllerKey,
            // not RemoteControllerKey. Read only: no setValue and no flight commands.
            // getOnce=true requests an initial hardware read in addition to change events.
            try {
                KeyManager.getInstance().listen(
                    KeyTools.createKey(FlightControllerKey.KeyRemoteControllerFlightMode),
                    OWNER, true, (previous, current) -> {
                        if (current != null) {
                            emit("F_S_N", "MODE_SWITCH", current);
                        } else {
                            Sink listener = sink;
                            if (listener != null) listener.onInput("F_S_N", "SDK:MODE_SWITCH_UNAVAILABLE",
                                "No mode switch value from SDK (aircraft may be required)", false);
                        }
                    });
                Object cached = KeyManager.getInstance().getValue(
                    KeyTools.createKey(FlightControllerKey.KeyRemoteControllerFlightMode));
                if (cached != null) emit("F_S_N", "MODE_SWITCH_CACHE", cached);
                Sink listener = sink;
                if (listener != null) listener.onInput("F_S_N", "SDK:MODE_SWITCH_PROBE",
                    "F/S/N read-only listener attached; move switch through three positions", false);
            } catch (Throwable switchError) {
                Sink listener = sink;
                if (listener != null) listener.onInput("F_S_N", "SDK:MODE_SWITCH_UNAVAILABLE",
                    "Switch key not available: " + switchError.toString(), false);
            }
            setStatus("SDK listeners active (actual coverage depends on controller)");
        } catch (Throwable error) {
            started = false;
            setStatus("SDK listener error: " + error.getMessage());
        }
    }
}
