package com.pocketpilot.app.transport;

/** 传输层只负责“收发字节”，不理解命令语义；命令解析、校验、执行全在 core/exec。 */
public interface BleTransport {
    interface Listener {
        void onTransportState(TransportState state, String detail);

        void onFrameReceived(byte[] frame);
    }

    String id();

    /** true 表示模拟通道，UI 必须显著标注，不能当成真实 BLE。 */
    boolean isSimulated();

    void start(Listener listener);

    void stop();

    void send(byte[] frame);
}
