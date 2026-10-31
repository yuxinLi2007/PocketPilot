package com.pocketpilot.app.plan;

/** 规划结果的四种走向。刻意区分“可执行”“等确认”“没能力做”“做了但被拒”。 */
public enum PlanOutcome {
    /** 计划合法且无需额外确认，可以执行。 */
    READY,
    /** 计划里含敏感动作，必须先拿到用户确认。 */
    NEEDS_CONFIRMATION,
    /** 规划器没有能力产出计划（缺数据、缺 API、没匹配规则）。不是失败，是“做不到”。 */
    UNAVAILABLE,
    /** 产出了计划，但校验层拒绝执行。 */
    REJECTED
}
