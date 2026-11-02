package com.pocketpilot.app.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BLE GATT 写入分片：把一个完整协议帧切成 ATT 允许的写入负载。
 *
 * <p>ATT 写入的最大负载是 MTU - 3（3 字节 ATT 操作码/句柄开销）。Android 在
 * 默认 MTU 23 时一次最多写 20 字节；协商到 185 时最多写 182 字节。协议帧本身
 * 仍以换行作为消息边界，因此分片只影响传输，不改变帧格式。</p>
 *
 * <p>这是纯 Java 类，不依赖 Android，便于在主机侧自测。真实 BLE 写入必须等
 * 上一片 {@code onCharacteristicWrite} 成功后再发下一片，不能一次性把整帧塞进
 * {@code writeCharacteristic()}。</p>
 */
public final class GattWriteChunker {
    public static final int ATT_HEADER_BYTES = 3;
    public static final int DEFAULT_MTU = 23;
    public static final int DEFAULT_PAYLOAD_BYTES = DEFAULT_MTU - ATT_HEADER_BYTES;
    public static final int MIN_PAYLOAD_BYTES = DEFAULT_PAYLOAD_BYTES;
    public static final int MAX_PAYLOAD_BYTES = CommandCodec.MAX_FRAME_BYTES;

    private GattWriteChunker() {
    }

    /** 根据协商 MTU 计算单次写入负载；未知/过小的 MTU 回退到 20 字节。 */
    public static int payloadBytesForMtu(int mtu) {
        if (mtu <= DEFAULT_MTU) {
            return DEFAULT_PAYLOAD_BYTES;
        }
        int payload = mtu - ATT_HEADER_BYTES;
        return Math.max(MIN_PAYLOAD_BYTES, Math.min(payload, MAX_PAYLOAD_BYTES));
    }

    /**
     * 按顺序切分完整帧。
     *
     * @param frame        完整帧（通常以 '\n' 结尾）
     * @param payloadBytes 每片最大字节数，必须 &gt; 0
     * @return 不可变的切片列表；空帧返回空列表
     */
    public static List<byte[]> split(byte[] frame, int payloadBytes) {
        if (frame == null || frame.length == 0) {
            return Collections.emptyList();
        }
        if (payloadBytes <= 0) {
            throw new IllegalArgumentException("payloadBytes must be > 0");
        }
        List<byte[]> chunks = new ArrayList<>((frame.length + payloadBytes - 1) / payloadBytes);
        int offset = 0;
        while (offset < frame.length) {
            int length = Math.min(payloadBytes, frame.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(frame, offset, chunk, 0, length);
            chunks.add(chunk);
            offset += length;
        }
        return Collections.unmodifiableList(chunks);
    }

    /** 给定帧长度和单片负载，计算需要多少次写入。 */
    public static int chunkCount(int frameBytes, int payloadBytes) {
        if (frameBytes <= 0) {
            return 0;
        }
        if (payloadBytes <= 0) {
            throw new IllegalArgumentException("payloadBytes must be > 0");
        }
        return (frameBytes + payloadBytes - 1) / payloadBytes;
    }
}
