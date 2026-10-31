package com.pocketpilot.app.core;

import java.nio.charset.StandardCharsets;

/**
 * 阶段 7A：自然语言任务文本的协议约束。
 *
 * 这些常量同时被编码器、解码器、校验器和测试使用。文本上限按 UTF-8 字节
 * 计算，而不是 Java 字符数，否则中文任务会在 BLE 帧预算上被低估。
 */
public final class TaskTextProtocol {
    /** 任务文本最多 384 个 UTF-8 字节（中文约 128 个汉字）。 */
    public static final int MAX_TEXT_BYTES = 384;

    /** 协议消息 ID 上限 64 个 ASCII 字符，用于结果回传与重复抑制。 */
    public static final int MAX_MESSAGE_ID_CHARS = 64;

    private TaskTextProtocol() {
    }

    /** 消息 ID 只允许 ASCII，避免 BLE 两端对 ID 的字节长度产生歧义。 */
    public static boolean isValidMessageId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_MESSAGE_ID_CHARS) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == ':' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static int utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 仅含 Unicode 空白也算空文本；规划器不能从空白里猜出任务。 */
    public static boolean isBlank(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                return false;
            }
        }
        return true;
    }

    public static boolean containsLineBreak(String text) {
        return text != null && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0);
    }
}
