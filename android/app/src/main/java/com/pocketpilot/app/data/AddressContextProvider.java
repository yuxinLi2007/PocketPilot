package com.pocketpilot.app.data;

import com.pocketpilot.app.plan.ContextFacts;

/**
 * 地址上下文来源。
 *
 * 实现方只提供“地址是什么、从哪来”，不提供权限状态：
 * 无障碍权限属于运行时事实，由调用方（TaskController）现场查询后传入，
 * 避免同一个标志有两个来源而产生分歧。
 */
public interface AddressContextProvider {
    ContextFacts snapshot(boolean accessibilityEnabled);

    /** 供日志和 UI 标注数据来源，必须是 MOCK_ 或 REAL_ 前缀之一。 */
    String sourceTag();
}
