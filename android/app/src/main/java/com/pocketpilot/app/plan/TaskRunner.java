package com.pocketpilot.app.plan;

import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.ExecutionStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务执行器。流程固定为：规划 -> 校验 -> 逐步执行 -> 聚合结果。
 *
 * 三条硬性行为：
 * 1. 校验不通过就一步都不执行，结果是 BLOCKED 而不是 FAILED（没有动作发生，不该报“执行失败”）。
 * 2. 任一步没成功就立刻停止，后面的步骤记为 SKIPPED，绝不“带病继续”。
 * 3. 只把 CONFIRMED / EXECUTED_UNCONFIRMED 视为成功；其它状态一律算失败并中断。
 */
public final class TaskRunner {

    /** 单步执行的最小接口。Android 侧由 CommandPipeline 实现，测试侧可以直接用假实现。 */
    public interface StepExecutor {
        CommandResult execute(TaskStep step, String source);
    }

    private TaskRunner() {
    }

    public static TaskResult run(Planner planner, String utterance, ContextFacts facts,
                                 StepExecutor executor, boolean confirmationGiven, String source) {
        long startedAt = System.currentTimeMillis();
        ContextFacts safeFacts = facts == null ? ContextFacts.empty() : facts;

        String addressSource = safeFacts.addressSource();

        if (planner == null) {
            return TaskResult.blocked(
                    PlanResult.unavailable("NO_PLANNER", "没有可用的规划器"),
                    utterance, "", addressSource, elapsed(startedAt));
        }

        PlanResult planResult = planner.plan(utterance, safeFacts);
        if (planResult.outcome() != PlanOutcome.READY
                && planResult.outcome() != PlanOutcome.NEEDS_CONFIRMATION) {
            return TaskResult.blocked(planResult, utterance, planner.id(), addressSource,
                    elapsed(startedAt));
        }

        TaskPlan plan = planResult.plan();
        PlanValidator.Decision decision = PlanValidator.validate(plan, safeFacts, confirmationGiven);
        if (!decision.accepted) {
            return TaskResult.rejected(plan, decision, addressSource, elapsed(startedAt));
        }

        List<TaskResult.StepOutcome> outcomes = new ArrayList<>();
        boolean halted = false;
        String haltReason = "";
        String haltDetail = "";

        for (int i = 0; i < plan.stepCount(); i++) {
            TaskStep step = plan.steps().get(i);
            int no = i + 1;

            if (halted) {
                outcomes.add(TaskResult.StepOutcome.skipped(no, step, "前序步骤未成功，本步骤已跳过"));
                continue;
            }

            // 校验层已经拦过一遍，这里再兜一次底：缺确认的敏感步骤绝不执行。
            if ((step.requiresConfirmation() || ActionPolicy.isSensitive(step.type())) && !confirmationGiven) {
                outcomes.add(TaskResult.StepOutcome.blocked(no, step, "缺少用户确认，已拦截"));
                halted = true;
                haltReason = "CONFIRMATION_REQUIRED";
                haltDetail = "第 " + no + " 步 " + step.describe() + " 属于敏感动作，未取得用户确认";
                continue;
            }

            CommandResult result;
            try {
                result = executor == null ? null : executor.execute(step, source);
            } catch (RuntimeException e) {
                result = null;
                haltDetail = "第 " + no + " 步执行抛出异常: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                haltReason = "STEP_EXCEPTION";
                halted = true;
                outcomes.add(TaskResult.StepOutcome.blocked(no, step, haltDetail));
                continue;
            }

            if (result == null) {
                outcomes.add(TaskResult.StepOutcome.blocked(no, step, "执行器未返回结果，无法确认该步骤"));
                halted = true;
                haltReason = "NO_STEP_RESULT";
                haltDetail = "第 " + no + " 步没有取得执行结果";
                continue;
            }

            outcomes.add(TaskResult.StepOutcome.executed(no, adapt(result, step)));

            if (!isSuccess(result.status())) {
                halted = true;
                haltReason = "STEP_FAILED";
                haltDetail = "第 " + no + " 步 " + step.describe() + " 执行未成功: "
                        + result.status().label()
                        + (result.detail().isEmpty() ? "" : " / " + result.detail());
            }
        }

        return TaskResult.fromSteps(plan, outcomes, addressSource, haltReason, haltDetail,
                elapsed(startedAt));
    }

    private static boolean isSuccess(ExecutionStatus status) {
        return status == ExecutionStatus.CONFIRMED || status == ExecutionStatus.EXECUTED_UNCONFIRMED;
    }

    private static TaskResult.CommandResultLike adapt(final CommandResult result, final TaskStep step) {
        return new TaskResult.CommandResultLike() {
            @Override
            public CommandType type() {
                if (result.command() != null && result.command().type() != null) {
                    return result.command().type();
                }
                return step.type();
            }

            @Override
            public String argument() {
                // 优先记录真正下发给执行器的参数；命令对象缺参数时回退到计划里的值。
                if (result.command() != null && !result.command().argument().isEmpty()) {
                    return result.command().argument();
                }
                return step.argument();
            }

            @Override
            public ExecutionStatus status() {
                return result.status();
            }

            @Override
            public String detail() {
                return result.detail();
            }

            @Override
            public String evidence() {
                return result.evidence();
            }
        };
    }

    private static long elapsed(long startedAt) {
        long delta = System.currentTimeMillis() - startedAt;
        return delta < 0 ? 0 : delta;
    }
}
