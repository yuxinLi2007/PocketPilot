package com.pocketpilot.app.transport;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import com.pocketpilot.app.core.CommandCodec;
import com.pocketpilot.app.core.GattWriteChunker;
import com.pocketpilot.app.core.PocketProtocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 真实 BLE（GATT）传输实现，对应 firmware/pocketpilot_ble 的 PocketPilot GATT 服务。
 *
 * 职责边界：本类只做“扫描 -> 连接 -> 订阅 -> 收发字节”。
 * 命令语义、校验和执行由 runtime/core/exec 负责。
 *
 * 就绪判定：CCCD 描述符写入成功（onDescriptorWrite）后才算订阅完成；
 * MTU 协商只是优化项，失败不影响通道可用。
 */
public final class GattBleTransport implements BleTransport {
    private static final String TAG = "PocketPilot/Gatt";
    public static final String ID = "GATT（真实）";

    public static final UUID SERVICE_UUID = UUID.fromString(PocketProtocol.SERVICE_UUID);
    /** 设备 -> 手机（notify）。 */
    public static final UUID RX_CHAR_UUID = UUID.fromString(PocketProtocol.RX_CHAR_UUID);
    /** 手机 -> 设备（write）。 */
    public static final UUID TX_CHAR_UUID = UUID.fromString(PocketProtocol.TX_CHAR_UUID);

    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long SCAN_TIMEOUT_MS = 10_000L;
    private static final long READY_TIMEOUT_MS = 8_000L;
    private static final int MAX_PENDING_WRITES = 16;
    /** 意外断连后的有上限自动重连次数。 */
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_DELAY_MS = 1_500L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CommandCodec.FrameAssembler assembler = new CommandCodec.FrameAssembler();
    private final Deque<byte[]> writeQueue = new ArrayDeque<>();
    /** 半帧超时看门狗：BLE notify 分片不保证在同一调度周期内全部到达。 */
    private final Runnable frameTimeout = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (assembler.expireIfStale(System.currentTimeMillis())) {
                Log.w(TAG, "frame assembly timeout: partial frame dropped");
                if (listener != null) {
                    listener.onTransportState(TransportState.ERROR,
                            "收到半帧超过 " + CommandCodec.FRAME_TIMEOUT_MS + " ms，已丢弃（FRAME_TIMEOUT）");
                }
            } else if (assembler.hasPartialFrame()) {
                // 定时器可能略早于拼接器的判定点，稍后复查而不是无限忙等。
                main.postDelayed(this, 250L);
            }
        }
    };

    private Listener listener;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;
    private volatile boolean running;
    private volatile boolean ready;
    private boolean writeInFlight;
    /** 当前正在分片写入的完整帧；null 表示没有帧在途。 */
    private byte[] activeWriteFrame;
    private int activeWriteOffset;
    private int activeWriteChunkLength;
    private int writePayloadBytes = GattWriteChunker.DEFAULT_PAYLOAD_BYTES;
    private boolean mtuRequested;
    private int reconnectAttempts;

    public GattBleTransport(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isSimulated() {
        return false;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        this.running = true;
        this.ready = false;
        this.writePayloadBytes = GattWriteChunker.DEFAULT_PAYLOAD_BYTES;
        if (!hasBlePermissions()) {
            fail("缺少蓝牙权限：Android 12+ 需要 BLUETOOTH_SCAN / BLUETOOTH_CONNECT");
            return;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            fail("本机不支持蓝牙（模拟器通常没有真实蓝牙适配器）");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                fail("蓝牙未开启");
                return;
            }
        } catch (SecurityException e) {
            fail("读取蓝牙状态被拒绝：" + e.getMessage());
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            fail("无法获取 BLE 扫描器");
            return;
        }
        reconnectAttempts = 0;
        beginScan("按 PocketPilot 服务 UUID 扫描设备");
    }

    /** 单次扫描；是否重试由 {@link #scanTimeout} 与 {@link #scheduleReconnect} 决定。 */
    private void beginScan(String detail) {
        if (!running || scanner == null) {
            return;
        }
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        emit(TransportState.SCANNING, detail);
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (SecurityException e) {
            fail("扫描被系统拒绝：" + e.getMessage());
            return;
        }
        main.removeCallbacks(scanTimeout);
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
    }

    /**
     * 有上限的自动重连，只用于“已连上后意外断开”。
     * 首次 start() 扫描失败仍然直接报错，避免无设备时无限重试。
     * stop() 会取消待执行的重连。
     */
    private void scheduleReconnect(String why) {
        if (!running) {
            return;
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            fail(why + "；自动重连 " + MAX_RECONNECT_ATTEMPTS + " 次仍未成功，请手动重新切换通道");
            return;
        }
        reconnectAttempts++;
        emit(TransportState.SCANNING, why + "；" + (RECONNECT_DELAY_MS / 1000) + " 秒后自动重连（第 "
                + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + " 次）");
        main.removeCallbacks(reconnect);
        main.postDelayed(reconnect, RECONNECT_DELAY_MS);
    }

    private final Runnable reconnect = () ->
            beginScan("自动重连扫描（第 " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + " 次）");

    private final Runnable scanTimeout = () -> {
        if (running && gatt == null) {
            stopScan();
            if (reconnectAttempts > 0) {
                scheduleReconnect("重连扫描在 10 秒内未发现设备");
            } else {
                fail("10 秒内未发现 PocketPilot GATT 设备");
            }
        }
    };

    private final Runnable readyTimeout = () -> {
        if (running && !ready && gatt != null) {
            fail("GATT 已连接但 8 秒内未完成命令特征订阅");
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!running || gatt != null) {
                return;
            }
            stopScan();
            main.removeCallbacks(scanTimeout);
            BluetoothDevice device = result.getDevice();
            String name = safeName(device);
            emit(TransportState.CONNECTING, "发现设备 " + name + " " + safeAddress(device));
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    gatt = device.connectGatt(context, false, gattCallback);
                }
            } catch (SecurityException e) {
                fail("连接被系统拒绝：" + e.getMessage());
                return;
            }
            main.postDelayed(readyTimeout, READY_TIMEOUT_MS);
        }

        @Override
        public void onScanFailed(int errorCode) {
            fail("BLE 扫描失败，errorCode=" + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emit(TransportState.CONNECTING, "已连接，开始发现服务");
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    fail("服务发现被拒绝：" + e.getMessage());
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                writeInFlight = false;
                activeWriteFrame = null;
                activeWriteOffset = 0;
                activeWriteChunkLength = 0;
                writePayloadBytes = GattWriteChunker.DEFAULT_PAYLOAD_BYTES;
                synchronized (writeQueue) {
                    writeQueue.clear();
                }
                assembler.reset();
                main.removeCallbacks(readyTimeout);
                main.removeCallbacks(scanTimeout);
                if (g == gatt) {
                    gatt = null;
                    rxCharacteristic = null;
                    txCharacteristic = null;
                    mtuRequested = false;
                    try {
                        g.close();
                    } catch (RuntimeException ignored) {
                    }
                }
                emit(TransportState.DISCONNECTED, "连接已断开（status=" + status + "）");
                if (running) {
                    // 已是“连上后掉线”的场景，按上限自动扫描重连；stop() 会取消。
                    scheduleReconnect("连接已断开（status=" + status + "）");
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败，status=" + status);
                return;
            }
            BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) {
                fail("对端未提供 PocketPilot GATT 服务（UUID " + SERVICE_UUID + "）");
                return;
            }
            rxCharacteristic = service.getCharacteristic(RX_CHAR_UUID);
            txCharacteristic = service.getCharacteristic(TX_CHAR_UUID);
            if (rxCharacteristic == null) {
                fail("缺少设备->手机命令特征 RX");
                return;
            }
            if (txCharacteristic == null) {
                fail("缺少手机->设备状态特征 TX");
                return;
            }
            try {
                if (!g.setCharacteristicNotification(rxCharacteristic, true)) {
                    fail("启用本地通知失败");
                    return;
                }
                BluetoothGattDescriptor descriptor = rxCharacteristic.getDescriptor(CCCD_UUID);
                if (descriptor == null) {
                    fail("RX 特征缺少 CCCD 描述符，无法订阅通知");
                    return;
                }
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!g.writeDescriptor(descriptor)) {
                    fail("写入通知订阅描述符失败");
                }
            } catch (SecurityException e) {
                fail("订阅通知被拒绝：" + e.getMessage());
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!CCCD_UUID.equals(descriptor.getUuid())) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("通知订阅写入失败，status=" + status);
                return;
            }
            ready = true;
            reconnectAttempts = 0;
            main.removeCallbacks(readyTimeout);
            emit(TransportState.CONNECTED, "命令通知已订阅，GATT 就绪");
            if (!mtuRequested) {
                mtuRequested = true;
                try {
                    g.requestMtu(185);
                } catch (SecurityException ignored) {
                }
            }
            drainWrites();
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writePayloadBytes = GattWriteChunker.payloadBytesForMtu(mtu);
                emit(TransportState.CONNECTED,
                        "命令通知已订阅，MTU=" + mtu + "，单片写入=" + writePayloadBytes + " 字节");
            } else {
                Log.w(TAG, "MTU 协商失败 status=" + status + "，继续使用默认 MTU");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            handleChunk(characteristic.getValue());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            if (!TX_CHAR_UUID.equals(characteristic.getUuid())) {
                return;
            }
            synchronized (writeQueue) {
                writeInFlight = false;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    writeQueue.clear();
                    activeWriteFrame = null;
                    activeWriteOffset = 0;
                    activeWriteChunkLength = 0;
                    emit(TransportState.ERROR, "状态回传写入失败，status=" + status + "（队列已清空）");
                    return;
                }
                if (activeWriteFrame != null) {
                    activeWriteOffset += activeWriteChunkLength;
                    activeWriteChunkLength = 0;
                    if (activeWriteOffset >= activeWriteFrame.length) {
                        activeWriteFrame = null;
                        activeWriteOffset = 0;
                    }
                }
            }
            drainWrites();
        }
    };

    private void handleChunk(byte[] value) {
        try {
            List<String> frames = assembler.append(value);
            if (assembler.consumeTimeoutDropped() && listener != null) {
                // 旧半帧已丢弃，但本批新数据已按新帧继续处理，通道不停止。
                listener.onTransportState(TransportState.ERROR,
                        "上一半帧超过 " + CommandCodec.FRAME_TIMEOUT_MS
                                + " ms，已丢弃并继续接收（FRAME_TIMEOUT）");
            }
            for (String frame : frames) {
                if (!running) {
                    return;
                }
                if (listener != null) {
                    listener.onFrameReceived(frame.getBytes(StandardCharsets.UTF_8));
                }
            }
            scheduleFrameTimeout();
        } catch (CommandCodec.ProtocolException e) {
            main.removeCallbacks(frameTimeout);
            Log.w(TAG, "frame assembly error: " + e.code() + " " + e.getMessage());
            if (listener != null) {
                listener.onTransportState(TransportState.ERROR,
                        "收到的数据帧不合法：" + e.code());
            }
        }
    }

    /** 只在确实存在半帧时挂定时器；完整帧或停止传输时取消。 */
    private void scheduleFrameTimeout() {
        main.removeCallbacks(frameTimeout);
        if (running && assembler.hasPartialFrame()) {
            main.postDelayed(frameTimeout, CommandCodec.FRAME_TIMEOUT_MS + 100L);
        }
    }

    @Override
    public void stop() {
        running = false;
        ready = false;
        main.removeCallbacks(scanTimeout);
        main.removeCallbacks(readyTimeout);
        main.removeCallbacks(reconnect);
        main.removeCallbacks(frameTimeout);
        reconnectAttempts = 0;
        stopScan();
        synchronized (writeQueue) {
            writeQueue.clear();
            writeInFlight = false;
            activeWriteFrame = null;
            activeWriteOffset = 0;
            activeWriteChunkLength = 0;
        }
        writePayloadBytes = GattWriteChunker.DEFAULT_PAYLOAD_BYTES;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {
            }
            gatt = null;
        }
        rxCharacteristic = null;
        txCharacteristic = null;
        mtuRequested = false;
        assembler.reset();
        emit(TransportState.IDLE, "已停止");
    }

    @Override
    public void send(byte[] frame) {
        if (frame == null || frame.length == 0) {
            return;
        }
        if (!running || gatt == null || !ready) {
            emit(TransportState.ERROR, "状态回传被丢弃：GATT 尚未就绪");
            return;
        }
        synchronized (writeQueue) {
            if (writeQueue.size() >= MAX_PENDING_WRITES) {
                emit(TransportState.ERROR, "状态回传队列已满，丢弃最旧一帧");
                writeQueue.pollFirst();
            }
            writeQueue.addLast(frame.clone());
        }
        drainWrites();
    }

    private void drainWrites() {
        byte[] nextChunk;
        synchronized (writeQueue) {
            if (!running || gatt == null || !ready || writeInFlight) {
                return;
            }
            if (activeWriteFrame == null) {
                if (writeQueue.isEmpty()) {
                    return;
                }
                activeWriteFrame = writeQueue.pollFirst();
                activeWriteOffset = 0;
            }
            int remaining = activeWriteFrame.length - activeWriteOffset;
            if (remaining <= 0) {
                activeWriteFrame = null;
                activeWriteOffset = 0;
                return;
            }
            int length = Math.min(writePayloadBytes, remaining);
            nextChunk = new byte[length];
            System.arraycopy(activeWriteFrame, activeWriteOffset, nextChunk, 0, length);
            activeWriteChunkLength = length;
            writeInFlight = true;
        }
        boolean queued;
        try {
            queued = writeCharacteristic(gatt, txCharacteristic, nextChunk);
        } catch (SecurityException e) {
            queued = false;
            Log.w(TAG, "write rejected: " + e.getMessage());
        }
        if (!queued) {
            synchronized (writeQueue) {
                writeInFlight = false;
                activeWriteFrame = null;
                activeWriteOffset = 0;
                activeWriteChunkLength = 0;
                writeQueue.clear();
            }
            emit(TransportState.ERROR, "状态回传写入未被 BLE 栈接受（队列已清空）");
        }
    }

    @SuppressWarnings("deprecation")
    private boolean writeCharacteristic(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    == BluetoothGatt.GATT_SUCCESS;
        }
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        c.setValue(value);
        return g.writeCharacteristic(c);
    }

    private void stopScan() {
        if (scanner == null) {
            return;
        }
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
        }
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String safeAddress(BluetoothDevice device) {
        try {
            return device.getAddress();
        } catch (SecurityException e) {
            return "(地址不可见)";
        }
    }

    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "(未命名设备)" : name;
        } catch (SecurityException e) {
            return "(名称不可见)";
        }
    }

    private void fail(String reason) {
        running = false;
        ready = false;
        main.removeCallbacks(scanTimeout);
        main.removeCallbacks(readyTimeout);
        Log.w(TAG, "transport failed: " + reason);
        emit(TransportState.ERROR, reason);
    }

    private void emit(TransportState state, String detail) {
        Log.i(TAG, "state=" + state + " " + detail);
        if (listener != null) {
            listener.onTransportState(state, detail);
        }
    }
}
