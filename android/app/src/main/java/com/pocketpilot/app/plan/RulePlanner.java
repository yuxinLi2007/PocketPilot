package com.pocketpilot.app.plan;

import com.pocketpilot.app.core.CommandType;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则规划器。当前唯一真正会产出计划的规划器。
 *
 * 设计原则：宁可返回 UNAVAILABLE，也不猜测。
 * 缺少上下文数据（例如没有地址）时明确说“做不到”，绝不编造一个默认地址，
 * 也绝不调用未配置的云端模型来补全。
 */
public final class RulePlanner implements Planner {
    public static final String ID = "RULE_V1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PlanResult plan(String utterance, ContextFacts facts) {
        String text = normalize(utterance);
        if (text.isEmpty()) {
            return PlanResult.unavailable("EMPTY_UTTERANCE", "没有收到可理解的任务描述");
        }
        ContextFacts safe = facts == null ? ContextFacts.empty() : facts;

        // “把刚收到的地址打开地图”一类：需要上下文里真的存在地址。
        if (text.contains("地址") && (text.contains("地图") || text.contains("导航") || text.contains("位置"))) {
            if (!safe.hasAddress()) {
                return PlanResult.unavailable("NO_ADDRESS_IN_CONTEXT",
                        "上下文中没有可用地址；不猜测、不使用默认地址");
            }
            List<TaskStep> steps = new ArrayList<>();
            steps.add(TaskStep.of(CommandType.OPEN_MAP, safe.latestAddress(),
                    "使用上下文中的地址（来源 " + safe.addressSource() + "）在地图中搜索"));
            return PlanResult.ready(new TaskPlan(ID, utterance, steps));
        }

        // 发送消息不在允许的动作集合内，直接拒绝，不产出计划。
        if (text.contains("发送") || text.contains("发消息")) {
            return PlanResult.unavailable("UNSUPPORTED_ACTION",
                    "发送消息不在当前允许的动作集合内");
        }

        if (text.contains("返回") || text.contains("后退") || text.contains("上一页")) {
            List<TaskStep> steps = new ArrayList<>();
            steps.add(TaskStep.of(CommandType.BACK, "", "用户要求返回上一页"));
            return PlanResult.ready(new TaskPlan(ID, utterance, steps));
        }

        if (text.contains("打开") && text.contains("微信")) {
            List<TaskStep> steps = new ArrayList<>();
            steps.add(TaskStep.of(CommandType.OPEN_WECHAT, "", "用户要求打开微信"));
            return PlanResult.ready(new TaskPlan(ID, utterance, steps));
        }

        if (text.contains("打开") && (text.contains("地图") || text.contains("导航"))) {
            List<TaskStep> steps = new ArrayList<>();
            steps.add(TaskStep.of(CommandType.OPEN_MAP, safe.latestAddress(),
                    "用户要求打开地图；地址取自上下文（来源 " + safe.addressSource() + "）"));
            if (!safe.hasAddress()) {
                // 没有地址时只能打开地图首页，这一步仍合法（OPEN_MAP 需要参数，故降级为拒绝）。
                return PlanResult.unavailable("NO_ADDRESS_IN_CONTEXT",
                        "OPEN_MAP 需要地址参数，但上下文中没有可用地址");
            }
            return PlanResult.ready(new TaskPlan(ID, utterance, steps));
        }

        return PlanResult.unavailable("NO_RULE_MATCHED", "没有匹配的规则；未调用云端模型");
    }

    /** 去掉空白和标点，只做匹配用；不改写用户原话（原话仍存进 TaskPlan）。 */
    static String normalize(String utterance) {
        if (utterance == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(utterance.length());
        for (int i = 0; i < utterance.length(); i++) {
            char c = utterance.charAt(i);
            if (isIgnorable(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 用 Unicode 类别判断标点，而不是手写字符集字面量：
     * 中英文引号、破折号、省略号、书名号都能覆盖，也避免源码里的转义歧义。
     */
    private static boolean isIgnorable(char c) {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) {
            return true;
        }
        switch (Character.getType(c)) {
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.CONNECTOR_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
                return true;
            default:
                return false;
        }
    }
}
