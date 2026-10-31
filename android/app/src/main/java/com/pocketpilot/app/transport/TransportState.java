package com.pocketpilot.app.transport;

public enum TransportState {
    IDLE("未启动"),
    SCANNING("扫描中"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
    DISCONNECTED("已断开"),
    ERROR("错误");

    private final String label;

    TransportState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
