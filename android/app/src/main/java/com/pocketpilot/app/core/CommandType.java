package com.pocketpilot.app.core;

/** 协议层支持的命令。新增命令必须同时补充 codec、validator、执行入口与测试记录。 */
public enum CommandType {
    OPEN_WECHAT("COMMAND_OPEN_WECHAT"),
    OPEN_MAP("COMMAND_OPEN_MAP"),
    BACK("COMMAND_BACK"),
    /** 阶段 7A：承载自然语言任务文本，只能进入规划器，不能直接执行手机动作。 */
    TASK_TEXT("COMMAND_TASK_TEXT");

    private final String wireName;

    CommandType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static CommandType fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (CommandType type : values()) {
            if (type.wireName.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
