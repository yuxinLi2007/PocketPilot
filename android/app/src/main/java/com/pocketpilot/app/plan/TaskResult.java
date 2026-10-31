package com.pocketpilot.app.plan;

import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.ExecutionStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次多步骤任务的最终结果。
 *
 * 状态刻意分成四档，避免“发出了命令”被当成“任务完成了”：
 * - COMPLETED_CONFIRMED   每一步都拿到了可核对的执行证据
 * - COMPLETED_UNCONFIRMED 每一步都执行了，但至少一步只有“已执行·未确认”
 * - BLOCKED               没有执行完（缺确认、缺数据、缺权限、规划器做不到）
 * - FAILED                执行过但有步骤失败
 */
public final class TaskResult {

    public enum Status {
        COMPLETED_CONFIRMED,
        COMPLETED_UNCONFIRMED,
        BLOCKED,
        FAILED
    }

    /** 单步结果。status 为 null 表示这一步根本没有执行（被跳过或被拦截）。 */
    public static final class StepOutcome {
        private final int index;
        private final CommandType type;
        private final String argument;
        private final ExecutionStatus status;
        private final String detail;
        private final String evidence;
        private final boolean skipped;
        private final boolean blockedForConfirmation;

        private StepOutcome(int index, CommandType type, String argument, ExecutionStatus status,
                            String detail, String evidence, boolean skipped,
                            boolean blockedForConfirmation) {
            this.index = index;
            this.type = type;
            this.argument = argument == null ? "" : argument;
            this.status = status;
            this.detail = detail == null ? "" : detail;
            this.evidence = evidence == null ? "" : evidence;
            this.skipped = skipped;
            this.blockedForConfirmation = blockedForConfirmation;
        }

        static StepOutcome executed(int index, CommandResultLike result) {
            return new StepOutcome(index, result.type(), result.argument(), result.status(),
                    result.detail(), result.evidence(), false, false);
        }

        static StepOutcome skipped(int index, TaskStep step, String why) {
            return new StepOutcome(index, step == null ? null : step.type(),
                    step == null ? "" : step.argument(), null, why, "", true, false);
        }

        static StepOutcome blocked(int index, TaskStep step, String why) {
            return new StepOutcome(index, step == null ? null : step.type(),
                    step == null ? "" : step.argument(), null, why, "", false, true);
        }

        public int index() { return index; }

        public CommandType type() { return type; }

        /** 该步骤实际使用的参数（OPEN_MAP 的地址）。与 ResultsStore 的字段保持一致，便于复核。 */
        public String argument() { return argument; }

        public ExecutionStatus status() { return status; }

        public String detail() { return detail; }

        public String evidence() { return evidence; }

        public boolean skipped() { return skipped; }

        public boolean blockedForConfirmation() { return blockedForConfirmation; }

        /** 落盘用的状态名。未执行的步骤没有 ExecutionStatus，用显式字符串表示。 */
        public String statusName() {
            if (blockedForConfirmation) {
                return "BLOCKED_CONFIRMATION";
            }
            if (skipped || status == null) {
                return "SKIPPED";
            }
            return status.name();
        }
    }

    /** 只取 TaskResult 需要的字段，避免 plan 层反向依赖 core.CommandResult 的构造细节。 */
    interface CommandResultLike {
        CommandType type();

        String argument();

        ExecutionStatus status();

        String detail();

        String evidence();
    }

    private final String taskId;
    private final String planId;
    private final String utterance;
    private final String plannerId;
    private final String addressSource;
    private final List<StepOutcome> steps;
    private final Status status;
    private final String reasonCode;
    private final String detail;
    private final long elapsedMillis;

    private TaskResult(String taskId, String planId, String utterance, String plannerId,
                       String addressSource, List<StepOutcome> steps, Status status,
                       String reasonCode, String detail, long elapsedMillis) {
        this.taskId = taskId == null ? "" : taskId;
        this.planId = planId == null ? "" : planId;
        this.utterance = utterance == null ? "" : utterance;
        this.plannerId = plannerId == null ? "" : plannerId;
        this.addressSource = addressSource == null ? "" : addressSource;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps == null ? new ArrayList<>() : steps));
        this.status = status;
        this.reasonCode = reasonCode == null ? "" : reasonCode;
        this.detail = detail == null ? "" : detail;
        this.elapsedMillis = elapsedMillis;
    }

    /** 规划器没能给出可执行计划（UNAVAILABLE / REJECTED）。一步都没执行，因此是 BLOCKED。 */
    static TaskResult blocked(PlanResult planResult, String utterance, String plannerId,
                              String addressSource, long elapsedMillis) {
        String code = planResult.reasonCode().isEmpty() ? planResult.outcome().name() : planResult.reasonCode();
        return new TaskResult("", "", utterance, plannerId, addressSource,
                new ArrayList<>(), Status.BLOCKED, code, planResult.reasonDetail(), elapsedMillis);
    }

    /** 计划产出了，但校验层拒绝。同样是 BLOCKED，不是 FAILED——因为没有执行任何动作。 */
    static TaskResult rejected(TaskPlan plan, PlanValidator.Decision decision,
                               String addressSource, long elapsedMillis) {
        String code = decision.reasonCode.isEmpty() ? decision.outcome.name() : decision.reasonCode;
        return new TaskResult(plan == null ? "" : plan.id(), plan == null ? "" : plan.id(),
                plan == null ? "" : plan.utterance(), plan == null ? "" : plan.plannerId(),
                addressSource, new ArrayList<>(), Status.BLOCKED, code, decision.reasonDetail,
                elapsedMillis);
    }

    static TaskResult fromSteps(TaskPlan plan, List<StepOutcome> outcomes, String addressSource,
                                String fallbackReason, String fallbackDetail, long elapsedMillis) {
        Status aggregated = aggregate(outcomes, fallbackReason);
        String code = fallbackReason;
        String detail = fallbackDetail;
        if (aggregated == Status.COMPLETED_CONFIRMED || aggregated == Status.COMPLETED_UNCONFIRMED) {
            code = "";
            detail = "全部 " + outcomes.size() + " 步已执行";
        } else if (code.isEmpty()) {
            code = aggregated == Status.BLOCKED ? "TASK_BLOCKED" : "TASK_FAILED";
        }
        return new TaskResult(plan.id(), plan.id(), plan.utterance(), plan.plannerId(),
                addressSource, outcomes, aggregated, code, detail, elapsedMillis);
    }

    private static Status aggregate(List<StepOutcome> outcomes, String reasonCode) {
        if (outcomes.isEmpty()) {
            return "CONFIRMATION_REQUIRED".equals(reasonCode) ? Status.BLOCKED : Status.BLOCKED;
        }
        boolean anyBlocked = false;
        boolean anySkipped = false;
        boolean anyFailed = false;
        boolean anyUnconfirmed = false;
        boolean anyConfirmed = false;

        for (StepOutcome o : outcomes) {
            if (o.blockedForConfirmation()) {
                anyBlocked = true;
            } else if (o.skipped() || o.status() == null) {
                anySkipped = true;
            } else if (o.status() == ExecutionStatus.CONFIRMED) {
                anyConfirmed = true;
            } else if (o.status() == ExecutionStatus.EXECUTED_UNCONFIRMED) {
                anyUnconfirmed = true;
            } else {
                anyFailed = true;
            }
        }

        if (anyBlocked) {
            return Status.BLOCKED;
        }
        if (!anySkipped && !anyFailed && anyConfirmed && !anyUnconfirmed) {
            return Status.COMPLETED_CONFIRMED;
        }
        if (!anySkipped && !anyFailed && (anyConfirmed || anyUnconfirmed) && anyUnconfirmed) {
            return Status.COMPLETED_UNCONFIRMED;
        }
        if (anySkipped && !anyFailed && !anyConfirmed && !anyUnconfirmed) {
            return Status.BLOCKED;
        }
        return Status.FAILED;
    }

    public String taskId() { return taskId; }

    public String planId() { return planId; }

    public String utterance() { return utterance; }

    public String plannerId() { return plannerId; }

    /**
     * 地址上下文来源。MOCK_ 前缀代表测试注入，读日志的人必须能一眼看出
     * 这条任务用的不是真实手机通知。
     */
    public String addressSource() { return addressSource; }

    public boolean usedMockAddress() { return addressSource.startsWith("MOCK_"); }

    public List<StepOutcome> steps() { return steps; }

    public Status status() { return status; }

    public String reasonCode() { return reasonCode; }

    public String detail() { return detail; }

    public long elapsedMillis() { return elapsedMillis; }

    /** results.jsonl 里的事件名，统一 TASK_ 前缀。 */
    public String eventName() {
        switch (status) {
            case COMPLETED_CONFIRMED:
            case COMPLETED_UNCONFIRMED:
                return "TASK_EXECUTED";
            case BLOCKED:
                return "TASK_BLOCKED";
            case FAILED:
            default:
                return "TASK_FAILED";
        }
    }

    /** 单行 JSON，可直接追加进 results.jsonl。 */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendField(sb, "event", eventName());
        sb.append(',');
        appendField(sb, "taskId", taskId);
        sb.append(',');
        appendField(sb, "planId", planId);
        sb.append(',');
        appendField(sb, "utterance", utterance);
        sb.append(',');
        appendField(sb, "plannerId", plannerId);
        sb.append(',');
        appendField(sb, "addressSource", addressSource);
        sb.append(',');
        appendField(sb, "status", status.name());
        sb.append(',');
        appendField(sb, "reasonCode", reasonCode);
        sb.append(',');
        appendField(sb, "detail", detail);
        sb.append(',');
        sb.append("\"elapsedMillis\":").append(elapsedMillis);
        sb.append(',');
        sb.append("\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            StepOutcome o = steps.get(i);
            sb.append('{');
            sb.append("\"index\":").append(o.index());
            sb.append(',');
            appendField(sb, "type", o.type() == null ? "" : o.type().wireName());
            sb.append(',');
            appendField(sb, "argument", o.argument());
            sb.append(',');
            appendField(sb, "status", o.statusName());
            sb.append(',');
            appendField(sb, "detail", o.detail());
            sb.append(',');
            appendField(sb, "evidence", o.evidence());
            sb.append('}');
        }
        sb.append(']');
        sb.append('}');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(escape(value)).append('"');
    }

    /** JSON 字符串转义。公开是为了让自测能直接验证换行/引号不会破坏单行 JSONL。 */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    /** 便于人读的一行摘要，用于 UI 提示和 logcat。 */
    public String describe() {
        return eventName() + " " + status + " planner=" + plannerId
                + " addressSource=" + addressSource
                + (reasonCode.isEmpty() ? "" : " reason=" + reasonCode)
                + (steps.isEmpty() ? "" : " steps=" + steps.size());
    }
}
