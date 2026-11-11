package com.pocketpilot.app.plan;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 财产安全文本守卫（硬防守，不可配置、不可覆盖）。
 *
 * 设计立场：PocketPilot 在任何阶段都【不提供】任何支付、转账、金钱操作能力。
 * 本守卫是独立的最后一道文本防线：无论指令从哪一层来（设备端命令词、TASK_TEXT
 * 自然语言、未来的云端规划器），只要文本命中财产类语义，一律拒绝执行。
 *
 * 防守层次（自外向内，本类是第 4 层）：
 *   0. 设备端：ESP-SR 命令词注册表里【从不注册】任何支付类词条；
 *   1. 协议层：CommandType 枚举中不存在任何 PAY/TRANSFER 类命令；
 *   2. 白名单层：ActionPolicy.ALLOWED 只含 OPEN_WECHAT/OPEN_MAP/BACK；
 *   3. 敏感层：ActionPolicy.SENSITIVE 为空，不可逆动作连"确认后执行"的资格都没有；
 *   4. 文本层：本类对 utterance 和每个计划步骤的参数做关键词与金额模式拦截；
 *   5. 执行层：ExecutorRegistry 中不存在任何支付执行器，白名单之外无代码路径。
 *
 * 误拦截取舍：宁可错杀（如"买药"被拦），不可放行。老人场景的正确姿势是
 * 被拦后由 App 播报"涉及金钱的操作请让家人帮忙"，而不是让设备代为执行。
 */
public final class FinancialGuard {

    /** 拒绝原因码，写进 results.jsonl 便于复核。 */
    public static final String REASON_CODE = "FINANCIAL_CONTENT_DENY";

    private FinancialGuard() {
    }

    /** 命中结果：denied=true 时 reasonCode 固定为 REASON_CODE，detail 说明命中词。 */
    public static final class Decision {
        public final boolean denied;
        public final String reasonCode;
        public final String detail;

        private Decision(boolean denied, String detail) {
            this.denied = denied;
            this.reasonCode = denied ? REASON_CODE : "";
            this.detail = detail == null ? "" : detail;
        }

        static Decision allow() {
            return new Decision(false, "");
        }

        static Decision deny(String hit) {
            return new Decision(true, "文本命中财产类关键词/模式：" + hit
                    + "。PocketPilot 不执行任何金钱操作，请在家人陪同下手动完成。");
        }
    }

    // 支付/转账/金钱操作类关键词。全角归一化后做包含匹配。
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            // 支付动作
            "支付", "付款", "付钱", "缴费", "交费", "转账", "转钱", "汇款", "汇钱", "打钱",
            "代付", "结账", "结算", "买单", "收银",
            // 红包/收款
            "红包", "收款码", "付款码", "收付款", "扫一扫", "扫码付", "扫码支",
            // 账户与凭据
            "密码", "验证码", "短信码", "动态码", "银行", "卡号", "银行卡", "余额", "存款",
            "免密", "花呗", "借呗", "白条", "信用卡", "贷款", "借款", "借贷", "网贷",
            // 金融产品
            "理财", "基金", "股票", "期货", "黄金", "外汇", "投资", "利息", "收益",
            "充值", "提现", "退款", "还款", "分期", "额度", "开户", "销户",
            // 高危意图
            "借钱给", "转给", "汇给", "付给", "打款", "打给钱",
            // 英文兜底
            "pay", "payment", "transfer", "password", "passwd", "otp", "verifycode",
            "verification", "bank", "wallet", "checkout", "purchase", "refund",
            "topup", "recharge", "withdraw", "deposit", "invest", "gamble", "casino",
            "lottery"));

    /**
     * 金额模式：数字后跟货币单位（元/块/万/千/佰/圆），如"转500块""汇2万"。
     * 覆盖关键词表漏掉的口语变体。
     */
    private static final String AMOUNT_PATTERN =
            "[0-9０-９一二两三四五六七八九十百]+(?:\\.[0-9０-９]+)?(?:元|圆|块钱|块|万元|万|千元|千|百万|千万|亿)";

    /**
     * 检查一段文本是否命中财产类语义。null / 空串直接放行。
     * 不抛异常：防守代码自身必须零故障。
     */
    public static Decision check(String text) {
        if (text == null || text.isEmpty()) {
            return Decision.allow();
        }
        try {
            String normalized = normalize(text);

            for (String keyword : KEYWORDS) {
                if (normalized.contains(keyword)) {
                    return Decision.deny(keyword);
                }
            }
            if (normalized.matches(".*" + AMOUNT_PATTERN + ".*")) {
                return Decision.deny("金额模式(数字+货币单位)");
            }
            return Decision.allow();
        } catch (RuntimeException e) {
            // 防守代码自身异常时按"拒绝"处理，绝不能因异常放行。
            return Decision.deny("守卫内部异常(" + e.getClass().getSimpleName() + ")，按拒绝处理");
        }
    }

    /**
     * 归一化：小写、全角转半角、去空白与常见标点。
     * 目的：挡住"转 账""ｐａｙ""转，账"这类简单的绕过手法。
     */
    static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                // 全角 ASCII 区转半角
                c = (char) (c - 0xFEE0);
            } else if (c == 0x3000) {
                // 全角空格
                c = ' ';
            }
            if (Character.isWhitespace(c) || isPunctuation(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static boolean isPunctuation(char c) {
        // 中英文常见标点 + 装饰符号
        switch (c) {
            case ',': case '.': case '!': case '?': case ';': case ':': case '\'':
            case '"': case '`': case '~': case '-': case '_': case '*': case '/':
            case '\\': case '(': case ')': case '[': case ']': case '{': case '}':
            case '<': case '>': case '@': case '#': case '$': case '%': case '^':
            case '&': case '+': case '=': case '|':
                return true;
            default:
                return (c >= 0x3000 && c <= 0x303F)   // CJK 标点 ，。！？
                        || (c >= 0x2010 && c <= 0x2027); // 通用标点 —— … '
        }
    }
}
