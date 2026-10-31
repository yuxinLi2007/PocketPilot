package com.pocketpilot.app.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 帧格式（UTF-8 文本，换行结尾，整帧最多 512 字节）：
 *
 *   固定命令（PP1 起就存在，保持不变）：
 *     PP1|COMMAND_OPEN_WECHAT\n
 *     PP1|COMMAND_OPEN_MAP|<地址>\n
 *     PP1|COMMAND_BACK\n
 *
 *   阶段 7A 任务文本：
 *     PP1|COMMAND_TASK_TEXT|<message_id>|<utf8_byte_length>|<text>\n
 *
 * 文本字段放在最后，因此可以包含 '|'；length 是 text 的 UTF-8 字节数，
 * 由发送端计算、接收端逐字节复核。任务文本只进入规划器，不能直接执行。
 */
public final class CommandCodec {
    public static final String VERSION = "PP1";
    public static final int MAX_FRAME_BYTES = 512;
    /**
     * 换行不计入负载：一个 BLE 帧最多 511 个负载字节 + 1 个 '\n'，
     * 整帧含换行才能稳定落在 512 字节预算内。
     */
    public static final int MAX_PAYLOAD_BYTES = MAX_FRAME_BYTES - 1;
    /** 半帧超过 2 秒仍未收到换行，就丢弃并按超时错误处理。 */
    public static final long FRAME_TIMEOUT_MS = 2_000L;

    private CommandCodec() {
    }

    public static final class Decoded {
        public final CommandType type;
        public final String argument;
        /** 仅 COMMAND_TASK_TEXT 有值；固定命令为 null，保持旧行为。 */
        public final String messageId;

        Decoded(CommandType type, String argument) {
            this(type, argument, null);
        }

        Decoded(CommandType type, String argument, String messageId) {
            this.type = type;
            this.argument = argument;
            this.messageId = messageId;
        }
    }

    public static final class ProtocolException extends Exception {
        private final String code;

        public ProtocolException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 旧固定命令编码。TASK_TEXT 必须使用 {@link #encodeTaskText} 以携带消息 ID 和长度。 */
    public static String encodeText(CommandType type, String argument) {
        if (type == CommandType.TASK_TEXT) {
            throw new IllegalArgumentException("TASK_TEXT requires encodeTaskText(messageId, text)");
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append(VERSION).append('|').append(type.wireName());
        if (argument != null && !argument.isEmpty()) {
            sb.append('|').append(argument);
        }
        sb.append('\n');
        return sb.toString();
    }

    public static byte[] encode(CommandType type, String argument) {
        return encodeText(type, argument).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeTaskTextBytes(String messageId, String text) throws ProtocolException {
        return encodeTaskText(messageId, text).getBytes(StandardCharsets.UTF_8);
    }

    /** 发送端与接收端使用同一套长度/UTF-8/消息 ID 约束。 */
    public static String encodeTaskText(String messageId, String text) throws ProtocolException {
        if (!TaskTextProtocol.isValidMessageId(messageId)) {
            throw new ProtocolException("INVALID_MESSAGE_ID",
                    "message_id 必须是 1-" + TaskTextProtocol.MAX_MESSAGE_ID_CHARS + " 个 ASCII 字符");
        }
        if (TaskTextProtocol.isBlank(text)) {
            throw new ProtocolException("EMPTY_TASK_TEXT", "任务文本不能为空");
        }
        if (TaskTextProtocol.containsLineBreak(text)) {
            throw new ProtocolException("MALFORMED_FRAME", "任务文本不能包含换行");
        }
        int bytes = TaskTextProtocol.utf8Length(text);
        if (bytes > TaskTextProtocol.MAX_TEXT_BYTES) {
            throw new ProtocolException("TEXT_TOO_LONG",
                    "任务文本 " + bytes + " 字节，超过上限 " + TaskTextProtocol.MAX_TEXT_BYTES);
        }
        String frame = VERSION + "|" + CommandType.TASK_TEXT.wireName() + "|"
                + messageId + "|" + bytes + "|" + text + "\n";
        int frameBytes = TaskTextProtocol.utf8Length(frame);
        if (frameBytes > MAX_FRAME_BYTES) {
            throw new ProtocolException("FRAME_TOO_LONG",
                    "任务帧 " + frameBytes + " 字节，超过上限 " + MAX_FRAME_BYTES);
        }
        return frame;
    }

    public static Decoded decode(byte[] frame) throws ProtocolException {
        if (frame == null || frame.length == 0) {
            throw new ProtocolException("EMPTY_FRAME", "空帧");
        }
        if (frame.length > MAX_FRAME_BYTES) {
            throw new ProtocolException("FRAME_TOO_LONG", "帧超过 " + MAX_FRAME_BYTES + " 字节");
        }
        return decode(decodeUtf8Strict(frame, frame.length));
    }

    public static Decoded decode(String text) throws ProtocolException {
        if (text == null) {
            throw new ProtocolException("EMPTY_FRAME", "空帧");
        }
        // 供直接调用 String 版本的测试/内部代码使用；字节版本已在前面按原始字节数检查。
        if (TaskTextProtocol.utf8Length(text) > MAX_FRAME_BYTES) {
            throw new ProtocolException("FRAME_TOO_LONG", "帧超过 " + MAX_FRAME_BYTES + " 字节");
        }
        String line = stripLineEnd(text);
        if (line == null || line.trim().isEmpty()) {
            throw new ProtocolException("EMPTY_FRAME", "空帧");
        }
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new ProtocolException("MALFORMED_FRAME", "帧内出现换行");
        }

        int first = line.indexOf('|');
        if (first < 0) {
            throw new ProtocolException("MALFORMED_FRAME", "帧缺少分隔符：" + preview(line));
        }
        int second = line.indexOf('|', first + 1);
        String version = line.substring(0, first);
        String typeName = second < 0 ? line.substring(first + 1) : line.substring(first + 1, second);
        String rest = second < 0 ? null : line.substring(second + 1);

        if (!VERSION.equals(version)) {
            throw new ProtocolException("UNSUPPORTED_VERSION", "不支持的协议版本：" + version);
        }
        CommandType type = CommandType.fromWire(typeName);
        if (type == null) {
            throw new ProtocolException("UNKNOWN_COMMAND", "未知命令：" + typeName);
        }
        if (type == CommandType.TASK_TEXT) {
            return decodeTaskText(rest);
        }

        // 旧固定命令继续沿用原来的“参数整体 trim”语义，不能改变。
        String argument = rest == null ? "" : rest.trim();
        return new Decoded(type, argument);
    }

    private static Decoded decodeTaskText(String rest) throws ProtocolException {
        if (rest == null) {
            throw new ProtocolException("MALFORMED_FRAME", "任务帧缺少 message_id/length/text");
        }
        int first = rest.indexOf('|');
        int second = first < 0 ? -1 : rest.indexOf('|', first + 1);
        if (first < 0 || second < 0) {
            throw new ProtocolException("MALFORMED_FRAME", "任务帧字段不完整（需要 id|length|text）");
        }
        String messageId = rest.substring(0, first);
        String declaredText = rest.substring(first + 1, second);
        String text = rest.substring(second + 1);

        if (!TaskTextProtocol.isValidMessageId(messageId)) {
            throw new ProtocolException("INVALID_MESSAGE_ID",
                    "message_id 必须是 1-" + TaskTextProtocol.MAX_MESSAGE_ID_CHARS + " 个 ASCII 字符");
        }
        if (!isDecimal(declaredText)) {
            throw new ProtocolException("INVALID_LENGTH", "length 不是十进制字节数：" + preview(declaredText));
        }
        int declared;
        try {
            declared = Integer.parseInt(declaredText);
        } catch (NumberFormatException e) {
            throw new ProtocolException("INVALID_LENGTH", "length 超出整数范围：" + preview(declaredText));
        }
        if (declared > TaskTextProtocol.MAX_TEXT_BYTES) {
            throw new ProtocolException("TEXT_TOO_LONG",
                    "声明长度 " + declared + " 超过上限 " + TaskTextProtocol.MAX_TEXT_BYTES);
        }
        if (TaskTextProtocol.isBlank(text)) {
            throw new ProtocolException("EMPTY_TASK_TEXT", "任务文本不能为空");
        }
        if (TaskTextProtocol.containsLineBreak(text)) {
            throw new ProtocolException("MALFORMED_FRAME", "任务文本不能包含换行");
        }
        int actual = TaskTextProtocol.utf8Length(text);
        if (actual > TaskTextProtocol.MAX_TEXT_BYTES) {
            throw new ProtocolException("TEXT_TOO_LONG",
                    "任务文本 " + actual + " 字节，超过上限 " + TaskTextProtocol.MAX_TEXT_BYTES);
        }
        if (declared != actual) {
            throw new ProtocolException("LENGTH_MISMATCH",
                    "声明长度 " + declared + "，实际 UTF-8 长度 " + actual);
        }
        return new Decoded(CommandType.TASK_TEXT, text, messageId);
    }

    private static boolean isDecimal(String value) {
        if (value == null || value.isEmpty() || value.length() > 6) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String stripLineEnd(String text) {
        if (text == null) {
            return null;
        }
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String decodeUtf8Strict(byte[] bytes, int length) throws ProtocolException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes, 0, length));
            return chars.toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("INVALID_UTF8", "帧不是合法的 UTF-8 文本");
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return "(null)";
        }
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    /**
     * BLE 通知按 MTU 分片。这里把多次通知拼回以换行结尾的完整帧。
     *
     * maxBytes 指“换行之前的负载上限”，默认 511；加 1 个换行后整帧正好
     * 不超过 {@link #MAX_FRAME_BYTES}。半帧超过 timeout 会被丢弃；超时后
     * 到达的新分片仍会按新帧继续处理，并可通过 {@link #consumeTimeoutDropped()}
     * 取得“刚刚丢弃过半帧”的信号。必须逐连接实例化。
     */
    public static final class FrameAssembler {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int maxBytes;
        private final long timeoutMillis;
        private long lastChunkAtMillis = -1L;
        private boolean timeoutDropped;

        public FrameAssembler() {
            this(MAX_PAYLOAD_BYTES, FRAME_TIMEOUT_MS);
        }

        public FrameAssembler(int maxBytes) {
            this(maxBytes, FRAME_TIMEOUT_MS);
        }

        public FrameAssembler(int maxBytes, long timeoutMillis) {
            this.maxBytes = maxBytes;
            this.timeoutMillis = timeoutMillis <= 0 ? FRAME_TIMEOUT_MS : timeoutMillis;
        }

        public List<String> append(byte[] chunk) throws ProtocolException {
            return append(chunk, System.currentTimeMillis());
        }

        public List<String> append(byte[] chunk, long nowMillis) throws ProtocolException {
            List<String> frames = new ArrayList<>();
            if (chunk == null || chunk.length == 0) {
                return frames;
            }
            if (buffer.size() > 0 && lastChunkAtMillis >= 0
                    && nowMillis - lastChunkAtMillis >= timeoutMillis) {
                // 丢弃旧半帧，但不要连带丢掉这一批新数据：它很可能是新帧的开头。
                clearBuffer();
                timeoutDropped = true;
            }
            lastChunkAtMillis = nowMillis;

            for (byte b : chunk) {
                if (b == '\n') {
                    byte[] complete = buffer.toByteArray();
                    clearBuffer();
                    String frame = decodeUtf8Strict(complete, complete.length);
                    if (!frame.trim().isEmpty()) {
                        frames.add(frame);
                    }
                } else {
                    buffer.write(b);
                    if (buffer.size() > maxBytes) {
                        reset();
                        throw new ProtocolException("FRAME_TOO_LONG", "重组缓冲区溢出，已丢弃半帧");
                    }
                }
            }
            return frames;
        }

        public boolean hasPartialFrame() {
            return buffer.size() > 0;
        }

        /** 供 GattBleTransport 的定时器调用；返回 true 表示确实丢弃了超时半帧。 */
        public boolean expireIfStale(long nowMillis) {
            if (buffer.size() > 0 && lastChunkAtMillis >= 0
                    && nowMillis - lastChunkAtMillis >= timeoutMillis) {
                clearBuffer();
                timeoutDropped = true;
                return true;
            }
            return false;
        }

        /** 读取并清除“丢弃过超时半帧”的一次性标志。 */
        public boolean consumeTimeoutDropped() {
            boolean value = timeoutDropped;
            timeoutDropped = false;
            return value;
        }

        public void reset() {
            clearBuffer();
            timeoutDropped = false;
        }

        private void clearBuffer() {
            buffer.reset();
            lastChunkAtMillis = -1L;
        }
    }
}
