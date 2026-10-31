package com.pocketpilot.app.plan;

/**
 * 规划器返回值。plan 只在 READY / NEEDS_CONFIRMATION 时非空；
 * UNAVAILABLE / REJECTED 一律不带计划，避免“半成品计划”被误执行。
 */
public final class PlanResult {
    private final PlanOutcome outcome;
    private final TaskPlan plan;
    private final String reasonCode;
    private final String reasonDetail;

    private PlanResult(PlanOutcome outcome, TaskPlan plan, String reasonCode, String reasonDetail) {
        this.outcome = outcome;
        this.plan = plan;
        this.reasonCode = reasonCode == null ? "" : reasonCode;
        this.reasonDetail = reasonDetail == null ? "" : reasonDetail;
    }

    public static PlanResult ready(TaskPlan plan) {
        return new PlanResult(PlanOutcome.READY, plan, "", "");
    }

    public static PlanResult needsConfirmation(TaskPlan plan, TaskStep step) {
        String detail = step == null ? "计划含敏感动作，需要用户确认"
                : "步骤 " + step.describe() + " 属于敏感动作，需要用户确认后才能执行";
        return new PlanResult(PlanOutcome.NEEDS_CONFIRMATION, plan, "CONFIRMATION_REQUIRED", detail);
    }

    public static PlanResult unavailable(String reasonCode, String reasonDetail) {
        return new PlanResult(PlanOutcome.UNAVAILABLE, null, reasonCode, reasonDetail);
    }

    public static PlanResult rejected(String reasonCode, String reasonDetail) {
        return new PlanResult(PlanOutcome.REJECTED, null, reasonCode, reasonDetail);
    }

    public PlanOutcome outcome() { return outcome; }

    /** 可能为 null，调用前必须先看 outcome。 */
    public TaskPlan plan() { return plan; }

    public String reasonCode() { return reasonCode; }

    public String reasonDetail() { return reasonDetail; }

    public boolean hasPlan() { return plan != null && !plan.isEmpty(); }

    public String describe() {
        if (plan == null) {
            return outcome + "(" + reasonCode + ")";
        }
        return outcome + " " + plan.id() + " [" + plan.describeSteps() + "]";
    }
}
