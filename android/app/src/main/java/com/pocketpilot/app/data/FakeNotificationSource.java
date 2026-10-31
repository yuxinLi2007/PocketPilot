package com.pocketpilot.app.data;

import com.pocketpilot.app.plan.ContextFacts;

/**
 * 测试用的地址上下文来源。
 *
 * 明确声明它不是什么：
 * - 它不是真实的系统通知监听。真实的 NotificationListenerService 尚未实现、尚未验证。
 * - 它保存的地址只能由 adb 调试广播注入，addressSource 恒为 MOCK_FAKE_NOTIFICATION。
 * - 任何基于它的测试结论都必须带上 MOCK_ 标记，不能当成“手机真的收到了一条通知”。
 *
 * 之所以仍然需要它：多步骤任务链路（意图 -> 取上下文 -> 规划 -> 执行 -> 反馈）
 * 应当先在没有真实通知权限的情况下被验证，等链路稳定后再接真实数据源。
 */
public final class FakeNotificationSource implements AddressContextProvider {
    private volatile String injectedAddress = "";
    private volatile boolean hasInjected = false;

    @Override
    public ContextFacts snapshot(boolean accessibilityEnabled) {
        String current = injectedAddress;
        if (!hasInjected || current == null || current.trim().isEmpty()) {
            // 没有注入地址时如实返回“无地址”，让规划器给出 NO_ADDRESS_IN_CONTEXT，
            // 而不是回退到某个默认地址。
            return new ContextFacts("", ContextFacts.SOURCE_NONE, accessibilityEnabled);
        }
        return new ContextFacts(current, ContextFacts.SOURCE_FAKE_NOTIFICATION, accessibilityEnabled);
    }

    @Override
    public String sourceTag() {
        return hasInjected ? ContextFacts.SOURCE_FAKE_NOTIFICATION : ContextFacts.SOURCE_NONE;
    }

    /** 仅调试注入使用。 */
    public void setInjectedAddress(String address) {
        this.injectedAddress = address == null ? "" : address.trim();
        this.hasInjected = true;
    }

    public void clearInjectedAddress() {
        this.injectedAddress = "";
        this.hasInjected = false;
    }

    public boolean hasInjectedAddress() {
        return hasInjected && injectedAddress != null && !injectedAddress.isEmpty();
    }

    /** 只用于日志，不要拿它当执行依据。 */
    public String peekAddress() {
        return injectedAddress == null ? "" : injectedAddress;
    }
}
