package com.pocketpilot.app.core;

/** 命令校验与执行分离：校验只判断“该不该执行”，不产生副作用。 */
public final class CommandValidator {
    public static final int MAX_ARGUMENT_LENGTH = 120;
    public static final long DUPLICATE_WINDOW_MS = 300L;

    private CommandValidator() {
    }

    public static final class Decision {
        public final boolean accepted;
        public final boolean duplicate;
        public final String reasonCode;
        public final String reasonDetail;

        private Decision(boolean accepted, boolean duplicate, String reasonCode, String reasonDetail) {
            this.accepted = accepted;
            this.duplicate = duplicate;
            this.reasonCode = reasonCode;
            this.reasonDetail = reasonDetail;
        }

        static Decision accept() {
            return new Decision(true, false, "", "");
        }

        static Decision reject(String code, String detail) {
            return new Decision(false, false, code, detail);
        }

        static Decision duplicate() {
            return new Decision(false, true, "DUPLICATE_SUPPRESSED",
                    DUPLICATE_WINDOW_MS + " ms 内收到重复命令，已抑制");
        }
    }

    public static Decision validate(PocketCommand command, boolean accessibilityEnabled,
                                    long lastSameCommandAtMillis) {
        return validate(command, accessibilityEnabled, lastSameCommandAtMillis,
                command == null ? System.currentTimeMillis() : command.receivedAtMillis());
    }

    /**
     * @param nowMillis 命令“到达传输层”的时间，而不是被工作线程处理的时间。
     *                  用处理时间去重会把排队延迟算进去，导致连按两下时第二条漏抑制。
     */
    public static Decision validate(PocketCommand command, boolean accessibilityEnabled,
                                    long lastSameCommandAtMillis, long nowMillis) {
        if (command == null || command.type() == null) {
            return Decision.reject("UNKNOWN_COMMAND", "命令类型为空");
        }
        if (command.type() == CommandType.TASK_TEXT) {
            // 任务文本按协议消息 ID 去重（TaskMessageGuard），这里不做 300 ms 类型去重，
            // 否则两条不同的任务在短时间内先后到达会被误杀。
            return validateTaskText(command);
        }
        if (lastSameCommandAtMillis > 0 && nowMillis - lastSameCommandAtMillis < DUPLICATE_WINDOW_MS) {
            return Decision.duplicate();
        }
        switch (command.type()) {
            case OPEN_MAP: {
                String argument = command.argument().trim();
                if (argument.isEmpty()) {
                    return Decision.reject("MISSING_ARGUMENT", "OPEN_MAP 必须携带地址参数");
                }
                if (argument.length() > MAX_ARGUMENT_LENGTH) {
                    return Decision.reject("ARGUMENT_TOO_LONG",
                            "地址长度 " + argument.length() + " 超过上限 " + MAX_ARGUMENT_LENGTH);
                }
                break;
            }
            case BACK: {
                if (!accessibilityEnabled) {
                    return Decision.reject("ACCESSIBILITY_DISABLED",
                            "无障碍服务未启用，无法执行返回；请在系统设置中开启");
                }
                break;
            }
            default:
                break;
        }
        return Decision.accept();
    }

    /**
     * TASK_TEXT 的防御性校验。编解码层已经校验过一次；这里再次检查，
     * 保证绕过传输层直接构造命令时也无法把空文本/超长文本送进规划器。
     */
    private static Decision validateTaskText(PocketCommand command) {
        if (!TaskTextProtocol.isValidMessageId(command.id())) {
            return Decision.reject("INVALID_MESSAGE_ID",
                    "message_id 必须是 1-" + TaskTextProtocol.MAX_MESSAGE_ID_CHARS + " 个 ASCII 字符");
        }
        String text = command.argument();
        if (TaskTextProtocol.isBlank(text)) {
            return Decision.reject("EMPTY_TASK_TEXT", "任务文本不能为空");
        }
        if (TaskTextProtocol.containsLineBreak(text)) {
            return Decision.reject("MALFORMED_FRAME", "任务文本不能包含换行");
        }
        int bytes = TaskTextProtocol.utf8Length(text);
        if (bytes > TaskTextProtocol.MAX_TEXT_BYTES) {
            return Decision.reject("TEXT_TOO_LONG",
                    "任务文本 " + bytes + " 字节，超过上限 " + TaskTextProtocol.MAX_TEXT_BYTES);
        }
        return Decision.accept();
    }
}
