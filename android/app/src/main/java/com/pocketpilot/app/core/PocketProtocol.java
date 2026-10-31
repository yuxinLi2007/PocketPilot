package com.pocketpilot.app.core;

/**
 * Android 与 ESP32-S3-EYE 之间唯一的协议常量来源。
 *
 * 必须与 firmware/pocketpilot_ble/main/pocketpilot_ble_main.c 里的 16 字节
 * 小端 UUID 数组逐一对应。曾经这里把 UUID 写成 41 个字符（多了一个连字符），
 * UUID.fromString 在类初始化时抛 "UUID string too large"，真实通道直接崩溃。
 * 因此自测会校验这三个字符串都能解析且长度为 36。
 */
public final class PocketProtocol {
    /** 广播与主服务：opocket-pilot + 0001。 */
    public static final String SERVICE_UUID = "6f706f63-6b65-742d-7069-6c6f74000001";
    /** 设备 -> 手机，notify。 */
    public static final String RX_CHAR_UUID = "6f706f63-6b65-742d-7069-6c6f74000002";
    /** 手机 -> 设备，write / write-no-response。 */
    public static final String TX_CHAR_UUID = "6f706f63-6b65-742d-7069-6c6f74000003";
    public static final String DEVICE_NAME = "PocketPilot";

    private PocketProtocol() {
    }
}