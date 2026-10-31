package com.pocketpilot.app.plan;

import com.pocketpilot.app.core.CommandValidator;
import com.pocketpilot.app.core.PocketCommand;

/**
 * 计划校验层。规划器（尤其是未来的云端模型）的输出必须经过这里才能执行。
 * 校验不产生任何副作用，只做“该不该执行”的判断。
 */
public final class PlanValidator {
    /** 步数上限。多步骤任务越长越容易累积误差，先卡死在 4 步。 */
    public static final int MAX_STEPS = 4;

    private PlanValidator() {
    }

    public static final class Decision {
        public final boolean accepted;
        public final PlanOutcome outcome;
        public final String reasonCode;
        public final String reasonDetail;

        private Decision(boolean accepted, PlanOutcome outcome, String reasonCode, String reasonDetail) {
            this.accepted = accepted;
            this.outcome = outcome;
            this.reasonCode = reasonCode;
            this.reasonDetail = reasonDetail;
        }

        static Decision accept() {
            return new Decision(true, PlanOutcome.READY, "", "");
        }

        static Decision needsConfirmation(String detail) {
            return new Decision(false, PlanOutcome.NEEDS_CONFIRMATION, "CONFIRMATION_REQUIRED", detail);
        }

        static Decision reject(String code, String detail) {
            return new Decision(false, PlanOutcome.REJECTED, code, detail);
        }
    }

    public static Decision validate(TaskPlan plan, ContextFacts facts, boolean confirmationGiven) {
        if (plan == null || plan.isEmpty()) {
            return Decision.reject("EMPTY_PLAN", "计划为空，没有可执行步骤");
        }
        if (plan.stepCount() > MAX_STEPS) {
            return Decision.reject("PLAN_TOO_LONG",
                    "计划步数 " + plan.stepCount() + " 超过上限 " + MAX_STEPS);
        }

        for (int i = 0; i < plan.stepCount(); i++) {
            TaskStep step = plan.steps().get(i);
            String where = "第 " + (i + 1) + " 步";

            if (step.type() == null) {
                return Decision.reject("UNKNOWN_ACTION", where + " 动作类型为空");
            }
            if (!ActionPolicy.isAllowed(step.type())) {
                return Decision.reject("ACTION_NOT_ALLOWED",
                        where + " 动作 " + step.type().wireName() + " 不在白名单内");
            }

            // 复用命令层校验，保证“计划里的步骤”和“BLE 收到的命令”遵守同一套规则。
            // lastSameCommandAtMillis 传 0：计划步骤不做去重抑制。
            PocketCommand probe = PocketCommand.create(step.type(), step.argument(), "PLAN");
            CommandValidator.Decision cd =
                    CommandValidator.validate(probe, facts == null ? false : facts.accessibilityEnabled(), 0L);
            if (!cd.accepted) {
                String code = cd.reasonCode;
                // 计划里“缺无障碍权限”应表述为权限问题，而不是命令层的内部码。
                if ("ACCESSIBILITY_DISABLED".equals(code)) {
                    code = "PERMISSION_REQUIRED";
                }
                return Decision.reject(code, where + " " + cd.reasonDetail);
            }

            boolean sensitive = step.requiresConfirmation() || ActionPolicy.isSensitive(step.type());
            if (sensitive && !confirmationGiven) {
                return Decision.needsConfirmation(where + " " + step.describe() + " 需要用户确认");
            }
        }
        return Decision.accept();
    }
}
