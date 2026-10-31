package com.pocketpilot.app.core;

/**
 * 执行状态刻意区分“已发出”和“已确认”。
 * 只有拿到可核对证据时才能使用 CONFIRMED，禁止把 startActivity 成功当成任务完成。
 */
public enum ExecutionStatus {
    RECEIVED("已接收"),
    REJECTED("已拒绝"),
    NOT_INSTALLED("目标不可用"),
    PERMISSION_REQUIRED("缺少授权"),
    EXECUTED_UNCONFIRMED("已执行·未确认"),
    CONFIRMED("已确认"),
    FAILED("执行失败");

    private final String label;

    ExecutionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isTerminal() {
        return this != RECEIVED;
    }
}
