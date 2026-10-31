package com.pocketpilot.app.plan;

import com.pocketpilot.app.core.CommandType;

/**
 * 计划中的一步。只允许引用协议里已经定义的 {@link CommandType}，
 * 禁止自由文本动作、禁止任意代码、禁止“点击坐标”这类不受约束的指令。
 */
public final class TaskStep {
    private final CommandType type;
    private final String argument;
    private final String rationale;
    private final boolean requiresConfirmation;

    public TaskStep(CommandType type, String argument, String rationale, boolean requiresConfirmation) {
        this.type = type;
        this.argument = argument == null ? "" : argument;
        this.rationale = rationale == null ? "" : rationale;
        this.requiresConfirmation = requiresConfirmation;
    }

    public static TaskStep of(CommandType type, String argument, String rationale) {
        return new TaskStep(type, argument, rationale, false);
    }

    /** 敏感动作（发送消息、付款、删除等）必须走这个工厂，缺确认就不能执行。 */
    public static TaskStep sensitive(CommandType type, String argument, String rationale) {
        return new TaskStep(type, argument, rationale, true);
    }

    public CommandType type() { return type; }

    public String argument() { return argument; }

    public String rationale() { return rationale; }

    public boolean requiresConfirmation() { return requiresConfirmation; }

    public String describe() {
        if (type == null) {
            return "<null-action>";
        }
        return argument.isEmpty() ? type.wireName() : type.wireName() + "(" + argument + ")";
    }
}
