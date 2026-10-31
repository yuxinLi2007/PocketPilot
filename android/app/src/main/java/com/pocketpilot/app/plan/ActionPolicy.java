package com.pocketpilot.app.plan;

import com.pocketpilot.app.core.CommandType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 动作白名单。这是 AI 规划层的硬边界：不在白名单里的动作一律拒绝，
 * 无论规划器（规则或未来的云端模型）声称它多么合理。
 *
 * 新增动作时的规则：
 * 1. 先在 {@link CommandType} 里定义协议名，并补齐 codec / executor / 测试；
 * 2. 再决定是否加入 ALLOWED。默认不加入，即默认被拒绝；
 * 3. 发送消息（SEND_*）、付款（PAY_*）、删除（DELETE_*）这类不可逆动作，
 *    即使加入 ALLOWED，也必须同时加入 SENSITIVE，走用户确认流程。
 */
public final class ActionPolicy {
    private static final Set<CommandType> ALLOWED;
    private static final Set<CommandType> SENSITIVE;

    static {
        Set<CommandType> allowed = EnumSet.noneOf(CommandType.class);
        allowed.add(CommandType.OPEN_MAP);
        allowed.add(CommandType.OPEN_WECHAT);
        allowed.add(CommandType.BACK);
        ALLOWED = Collections.unmodifiableSet(allowed);

        // 当前白名单里没有不可逆动作，因此敏感集合为空。
        // 这不是“忽略敏感动作”，而是敏感动作目前根本不允许被规划。
        SENSITIVE = Collections.unmodifiableSet(EnumSet.noneOf(CommandType.class));
    }

    private ActionPolicy() {
    }

    public static boolean isAllowed(CommandType type) {
        return type != null && ALLOWED.contains(type);
    }

    /** 敏感动作必须显式确认；缺确认时由 PlanValidator 拦截。 */
    public static boolean isSensitive(CommandType type) {
        return type != null && SENSITIVE.contains(type);
    }

    public static Set<CommandType> allowed() {
        return ALLOWED;
    }
}
