package com.pocketpilot.app.core;

import java.nio.charset.StandardCharsets;

/**
 * 结果帧编码：PP1|RESULT|<command_id>|<STATUS>|<detail>\n
 *
 * 设备端接收缓冲固定为 512 字节，因此这里保证“整帧含换行”不超过
 * {@link CommandCodec#MAX_FRAME_BYTES}。detail 按 UTF-8 字节截断，绝不切坏
 * 多字节字符（中文、emoji）。detail 中的 '|' 和换行会破坏字段解析，先替换。
 */
public final class StatusFrameEncoder {
    private static final String ELLIPSIS = "…";
    private static final int ELLIPSIS_BYTES = ELLIPSIS.getBytes(StandardCharsets.UTF_8).length;
    private static final int NEWLINE_BYTES = 1;

    private StatusFrameEncoder() {
    }

    /** @return 可直接写入 BLE TX 特征的完整帧（含结尾换行，≤ 512 字节）。 */
    public static String encode(CommandResult result) {
        String prefix = "PP1|RESULT|" + result.command().id() + "|" + result.status().name() + "|";
        String detail = sanitize(result.detail());

        int maxContentBytes = CommandCodec.MAX_FRAME_BYTES - NEWLINE_BYTES
                - prefix.getBytes(StandardCharsets.UTF_8).length;
        if (maxContentBytes <= 0) {
            // 前缀本身已超限（协议上不应发生）：整帧硬截断，宁缺字段也不发超长帧。
            return truncateUtf8(prefix, CommandCodec.MAX_FRAME_BYTES - NEWLINE_BYTES) + "\n";
        }

        byte[] detailBytes = detail.getBytes(StandardCharsets.UTF_8);
        if (detailBytes.length > maxContentBytes) {
            // 省略号本身占 3 字节，必须从预算里扣掉，否则截断后反而超限。
            int contentBudget = Math.max(0, maxContentBytes - ELLIPSIS_BYTES);
            detail = truncateUtf8(detail, contentBudget) + ELLIPSIS;
        }
        return prefix + detail + "\n";
    }

    static String sanitize(String detail) {
        if (detail == null) {
            return "";
        }
        return detail.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    /** 按 UTF-8 字节上限截断，回退到完整字符边界。 */
    static String truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int cut = Math.max(0, Math.min(maxBytes, bytes.length));
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8);
    }
}