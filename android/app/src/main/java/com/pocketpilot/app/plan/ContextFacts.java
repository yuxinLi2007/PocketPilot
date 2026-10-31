package com.pocketpilot.app.plan;

/**
 * 规划器可用的上下文事实。只放“已经取到的数据”，不放猜测。
 *
 * addressSource 必须说明地址从哪里来：以 MOCK_ 开头的值代表测试数据，
 * 不能当成真实手机通知；REAL_ 开头才代表来自系统通知监听（尚未验证）。
 */
public final class ContextFacts {
    public static final String SOURCE_NONE = "NONE";
    public static final String SOURCE_FAKE_NOTIFICATION = "MOCK_FAKE_NOTIFICATION";
    public static final String SOURCE_REAL_NOTIFICATION = "REAL_NOTIFICATION_LISTENER";

    private final String latestAddress;
    private final String addressSource;
    private final boolean accessibilityEnabled;

    public ContextFacts(String latestAddress, String addressSource, boolean accessibilityEnabled) {
        this.latestAddress = latestAddress == null ? "" : latestAddress.trim();
        this.addressSource = addressSource == null ? SOURCE_NONE : addressSource;
        this.accessibilityEnabled = accessibilityEnabled;
    }

    public static ContextFacts empty() {
        return new ContextFacts("", SOURCE_NONE, false);
    }

    public String latestAddress() { return latestAddress; }

    public String addressSource() { return addressSource; }

    public boolean accessibilityEnabled() { return accessibilityEnabled; }

    public boolean hasAddress() { return !latestAddress.isEmpty(); }

    /** true 表示地址来自测试注入，任何结论都必须带上这个标记。 */
    public boolean isMockAddress() { return addressSource.startsWith("MOCK_"); }
}
