package com.pocketpilot.app.transport;

import android.util.Log;

import com.pocketpilot.app.core.CommandCodec;
import com.pocketpilot.app.core.CommandType;

import java.nio.charset.StandardCharsets;

/**
 * 模拟传输：把“收到一帧”这个事件在进程内伪造出来，用于模拟器验证。
 * 它不连接任何蓝牙硬件，日志一律带 SIMULATED 前缀，防止与真实链路混淆。
 */
public final class FakeBleTransport implements BleTransport {
    private static final String TAG = "PocketPilot/FakeBle";
    public static final String ID = "FAKE-BLE（模拟）";

    private Listener listener;
    private volatile boolean running;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        this.running = true;
        Log.i(TAG, "SIMULATED transport started; no bluetooth hardware involved");
        if (listener != null) {
            listener.onTransportState(TransportState.CONNECTED, "模拟通道已就绪（非真实蓝牙）");
        }
    }

    @Override
    public void stop() {
        running = false;
        Log.i(TAG, "SIMULATED transport stopped");
        if (listener != null) {
            listener.onTransportState(TransportState.IDLE, "模拟通道已停止");
        }
    }

    /** 真实设备侧的回执在模拟模式下没有落点，这里只记录，不假装发送成功。 */
    @Override
    public void send(byte[] frame) {
        String text = frame == null ? "" : new String(frame, StandardCharsets.UTF_8).trim();
        Log.i(TAG, "SIMULATED tx (dropped, no device): " + text);
    }

    public void injectCommand(CommandType type, String argument) {
        injectFrame(CommandCodec.encode(type, argument));
    }

    public void injectRaw(String raw) {
        if (raw == null) {
            return;
        }
        injectFrame(raw.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void injectFrame(byte[] frame) {
        if (!running || listener == null) {
            Log.w(TAG, "SIMULATED inject ignored: transport not started");
            return;
        }
        String text = frame == null ? "" : new String(frame, StandardCharsets.UTF_8).trim();
        Log.i(TAG, "SIMULATED rx: " + text);
        listener.onFrameReceived(frame);
    }
}
