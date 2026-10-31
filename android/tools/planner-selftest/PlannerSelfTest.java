package com.pocketpilot.app.plannertest;

import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.ExecutionStatus;
import com.pocketpilot.app.core.PocketCommand;
import com.pocketpilot.app.plan.ActionPolicy;
import com.pocketpilot.app.plan.CloudPlannerStub;
import com.pocketpilot.app.plan.ContextFacts;
import com.pocketpilot.app.plan.PlanOutcome;
import com.pocketpilot.app.plan.PlanResult;
import com.pocketpilot.app.plan.PlanValidator;
import com.pocketpilot.app.plan.Planner;
import com.pocketpilot.app.plan.RulePlanner;
import com.pocketpilot.app.plan.TaskPlan;
import com.pocketpilot.app.plan.TaskResult;
import com.pocketpilot.app.plan.TaskRunner;
import com.pocketpilot.app.plan.TaskStep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 纯 JDK 规划层自测：不依赖 Android、JUnit 或网络。
 *
 * 运行方式见 android/tools/planner-selftest/run-selftest.ps1。
 * 覆盖：受约束计划生成、白名单边界、参数与权限校验、敏感动作确认、
 *      云端占位实现不编造计划、任务级 fail-fast 与状态聚合、JSONL 落盘格式。
 *
 * 这里测的是规划与校验逻辑本身，不代表真实手机通知、真实云端模型或整机链路已验证。
 */
public final class PlannerSelfTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int passed = 0;

    private static final String TEST_ADDRESS = "北京市海淀区中关村大街1号";

    public static void main(String[] args) {
        actionPolicyTests();
        rulePlannerTests();
        cloudStubTests();
        planValidatorTests();
        taskRunnerTests();
        jsonTests();

        System.out.println();
        System.out.println("PASS " + passed + "  FAIL " + FAILURES.size());
        for (String failure : FAILURES) {
            System.out.println("  FAILED: " + failure);
        }
        if (!FAILURES.isEmpty()) {
            System.exit(1);
        }
        System.out.println("PLANNER_SELFTEST_OK");
    }

    // ------------------------------------------------------- action policy

    private static void actionPolicyTests() {
        boolean ok = ActionPolicy.isAllowed(CommandType.OPEN_MAP)
                && ActionPolicy.isAllowed(CommandType.OPEN_WECHAT)
                && ActionPolicy.isAllowed(CommandType.BACK);
        expect(ok, "whitelist covers the three shipped commands");

        expect(!ActionPolicy.isAllowed(null), "null action is not allowed");
        expect(ActionPolicy.allowed().size() == 3,
                "whitelist has exactly 3 entries (got " + ActionPolicy.allowed().size() + ")");

        boolean anySensitive = false;
        for (CommandType type : CommandType.values()) {
            if (ActionPolicy.isSensitive(type)) {
                anySensitive = true;
            }
        }
        // 当前白名单里没有不可逆动作。若将来加入 SEND_*/PAY_*/DELETE_*，这条会失败，
        // 提醒实现者必须同时登记敏感集合与确认流程。
        expect(!anySensitive, "no whitelisted action is currently sensitive/irreversible");
    }

    // ------------------------------------------------------- rule planner

    private static void rulePlannerTests() {
        RulePlanner planner = new RulePlanner();
        ContextFacts withAddress = new ContextFacts(TEST_ADDRESS,
                ContextFacts.SOURCE_FAKE_NOTIFICATION, true);

        PlanResult r1 = planner.plan("把刚收到的地址打开地图", withAddress);
        expect(r1.outcome() == PlanOutcome.READY, "address->map utterance yields READY");
        expect(r1.hasPlan() && r1.plan().stepCount() == 1, "address->map plan has exactly 1 step");
        if (r1.hasPlan() && r1.plan().stepCount() == 1) {
            TaskStep step = r1.plan().steps().get(0);
            expect(step.type() == CommandType.OPEN_MAP, "step type is OPEN_MAP");
            expect(TEST_ADDRESS.equals(step.argument()),
                    "step argument equals the context address verbatim (got '" + step.argument() + "')");
            expect(!step.requiresConfirmation(), "OPEN_MAP step does not require confirmation");
        }
        expect("RULE_V1".equals(planner.id()) && "RULE_V1".equals(r1.plan().plannerId()),
                "planner id is recorded on the plan");

        // 没有地址时必须明确做不到，且不得编造任何地址。
        PlanResult r2 = planner.plan("把刚收到的地址打开地图", ContextFacts.empty());
        expect(r2.outcome() == PlanOutcome.UNAVAILABLE, "missing address yields UNAVAILABLE");
        expect("NO_ADDRESS_IN_CONTEXT".equals(r2.reasonCode()),
                "missing address reason is NO_ADDRESS_IN_CONTEXT (got " + r2.reasonCode() + ")");
        expect(r2.plan() == null || r2.plan().isEmpty(), "missing address produces no plan at all");

        PlanResult r3 = planner.plan("给张三发消息", withAddress);
        expect(r3.outcome() == PlanOutcome.UNAVAILABLE, "send-message utterance is not planned");
        expect("UNSUPPORTED_ACTION".equals(r3.reasonCode()),
                "send-message reason is UNSUPPORTED_ACTION (got " + r3.reasonCode() + ")");

        PlanResult r4 = planner.plan("返回上一页", withAddress);
        expect(r4.outcome() == PlanOutcome.READY && r4.plan().stepCount() == 1
                        && r4.plan().steps().get(0).type() == CommandType.BACK,
                "back utterance yields a single BACK step");

        PlanResult r5 = planner.plan("打开微信", withAddress);
        expect(r5.outcome() == PlanOutcome.READY && r5.plan().stepCount() == 1
                        && r5.plan().steps().get(0).type() == CommandType.OPEN_WECHAT,
                "open-wechat utterance yields a single OPEN_WECHAT step");

        PlanResult r6 = planner.plan("帮我订一张明天去上海的机票", withAddress);
        expect(r6.outcome() == PlanOutcome.UNAVAILABLE, "unrelated utterance is UNAVAILABLE");
        expect("NO_RULE_MATCHED".equals(r6.reasonCode()),
                "unrelated utterance reason is NO_RULE_MATCHED (got " + r6.reasonCode() + ")");
        expect(r6.plan() == null || r6.plan().isEmpty(), "unrelated utterance produces no plan");

        PlanResult r7 = planner.plan("   ", withAddress);
        expect(r7.outcome() == PlanOutcome.UNAVAILABLE
                        && "EMPTY_UTTERANCE".equals(r7.reasonCode()),
                "blank utterance yields EMPTY_UTTERANCE");

        // 标点与空格不应影响匹配。
        PlanResult r8 = planner.plan("把 刚收到的地址，打开地图！", withAddress);
        expect(r8.outcome() == PlanOutcome.READY, "punctuation/whitespace does not break matching");

        // 白名单兜底：规则规划器绝不产出白名单外的步骤。
        boolean allInsideWhitelist = true;
        for (PlanResult pr : Arrays.asList(r1, r4, r5, r8)) {
            if (pr.hasPlan()) {
                for (TaskStep step : pr.plan().steps()) {
                    if (!ActionPolicy.isAllowed(step.type())) {
                        allInsideWhitelist = false;
                    }
                }
            }
        }
        expect(allInsideWhitelist, "RulePlanner never emits an action outside the whitelist");
    }

    // -------------------------------------------------------- cloud stub

    private static void cloudStubTests() {
        CloudPlannerStub unconfigured = new CloudPlannerStub(false);
        PlanResult r = unconfigured.plan("把刚收到的地址打开地图",
                new ContextFacts(TEST_ADDRESS, ContextFacts.SOURCE_FAKE_NOTIFICATION, true));
        expect(r.outcome() == PlanOutcome.UNAVAILABLE, "unconfigured cloud stub is UNAVAILABLE");
        expect("NO_API_KEY".equals(r.reasonCode()),
                "unconfigured cloud stub reason is NO_API_KEY (got " + r.reasonCode() + ")");
        expect(r.plan() == null || r.plan().isEmpty(),
                "unconfigured cloud stub returns no fabricated plan");

        CloudPlannerStub configured = new CloudPlannerStub(true);
        PlanResult r2 = configured.plan("把刚收到的地址打开地图", ContextFacts.empty());
        expect(r2.outcome() == PlanOutcome.UNAVAILABLE
                        && "NOT_IMPLEMENTED".equals(r2.reasonCode()),
                "configured-but-unimplemented cloud stub is UNAVAILABLE(NOT_IMPLEMENTED)");
        expect(r2.plan() == null || r2.plan().isEmpty(),
                "configured cloud stub still returns no plan");
        expect("CLOUD_STUB_V1".equals(configured.id()), "cloud stub id is CLOUD_STUB_V1");
    }

    // ------------------------------------------------------ plan validator

    private static void planValidatorTests() {
        ContextFacts a11yOn = new ContextFacts(TEST_ADDRESS, ContextFacts.SOURCE_REAL_NOTIFICATION, true);
        ContextFacts a11yOff = new ContextFacts(TEST_ADDRESS, ContextFacts.SOURCE_REAL_NOTIFICATION, false);

        PlanValidator.Decision empty = PlanValidator.validate(
                new TaskPlan("TEST", "x", new ArrayList<>()), a11yOn, false);
        expect(!empty.accepted && "EMPTY_PLAN".equals(empty.reasonCode)
                        && empty.outcome == PlanOutcome.REJECTED,
                "empty plan is rejected with EMPTY_PLAN");

        PlanValidator.Decision nullPlan = PlanValidator.validate(null, a11yOn, false);
        expect(!nullPlan.accepted && "EMPTY_PLAN".equals(nullPlan.reasonCode),
                "null plan is rejected with EMPTY_PLAN");

        // 敏感动作缺确认 -> NEEDS_CONFIRMATION，不是 REJECTED。
        TaskPlan sensitive = planOf(TaskStep.sensitive(CommandType.OPEN_MAP, TEST_ADDRESS, "test"));
        PlanValidator.Decision needs = PlanValidator.validate(sensitive, a11yOn, false);
        expect(!needs.accepted && needs.outcome == PlanOutcome.NEEDS_CONFIRMATION
                        && "CONFIRMATION_REQUIRED".equals(needs.reasonCode),
                "sensitive step without confirmation -> NEEDS_CONFIRMATION/CONFIRMATION_REQUIRED");

        PlanValidator.Decision confirmed = PlanValidator.validate(sensitive, a11yOn, true);
        expect(confirmed.accepted && confirmed.outcome == PlanOutcome.READY,
                "sensitive step with confirmation is accepted");

        // 动作类型为空。
        TaskPlan unknown = planOf(new TaskStep(null, "", "no type", false));
        PlanValidator.Decision unknownDecision = PlanValidator.validate(unknown, a11yOn, false);
        expect(!unknownDecision.accepted && unknownDecision.outcome == PlanOutcome.REJECTED
                        && "UNKNOWN_ACTION".equals(unknownDecision.reasonCode),
                "null action type -> REJECTED(UNKNOWN_ACTION)");

        // 参数超长。
        StringBuilder longAddress = new StringBuilder();
        for (int i = 0; i < 121; i++) {
            longAddress.append('a');
        }
        TaskPlan tooLong = planOf(TaskStep.of(CommandType.OPEN_MAP, longAddress.toString(), "test"));
        PlanValidator.Decision tooLongDecision = PlanValidator.validate(tooLong, a11yOn, false);
        expect(!tooLongDecision.accepted && "ARGUMENT_TOO_LONG".equals(tooLongDecision.reasonCode),
                "121-char OPEN_MAP argument -> REJECTED(ARGUMENT_TOO_LONG)");

        // OPEN_MAP 缺参数。
        TaskPlan noArg = planOf(TaskStep.of(CommandType.OPEN_MAP, "", "test"));
        PlanValidator.Decision noArgDecision = PlanValidator.validate(noArg, a11yOn, false);
        expect(!noArgDecision.accepted && "MISSING_ARGUMENT".equals(noArgDecision.reasonCode),
                "OPEN_MAP without argument -> REJECTED(MISSING_ARGUMENT)");

        // BACK 缺无障碍权限：命令层的 ACCESSIBILITY_DISABLED 必须映射成 PERMISSION_REQUIRED。
        TaskPlan back = planOf(TaskStep.of(CommandType.BACK, "", "test"));
        PlanValidator.Decision permOff = PlanValidator.validate(back, a11yOff, false);
        expect(!permOff.accepted && permOff.outcome == PlanOutcome.REJECTED
                        && "PERMISSION_REQUIRED".equals(permOff.reasonCode),
                "BACK with accessibility disabled -> REJECTED(PERMISSION_REQUIRED), got "
                        + permOff.reasonCode);
        PlanValidator.Decision permOn = PlanValidator.validate(back, a11yOn, false);
        expect(permOn.accepted, "BACK with accessibility enabled is accepted");

        // facts 为 null 时按“无权限”处理，而不是抛异常或放行。
        PlanValidator.Decision nullFacts = PlanValidator.validate(back, null, false);
        expect(!nullFacts.accepted && "PERMISSION_REQUIRED".equals(nullFacts.reasonCode),
                "null ContextFacts is treated as accessibility disabled");

        // 步数上限。
        List<TaskStep> many = new ArrayList<>();
        for (int i = 0; i <= PlanValidator.MAX_STEPS; i++) {
            many.add(TaskStep.of(CommandType.OPEN_WECHAT, "", "test"));
        }
        PlanValidator.Decision tooMany = PlanValidator.validate(new TaskPlan("TEST", "x", many), a11yOn, false);
        expect(!tooMany.accepted && "PLAN_TOO_LONG".equals(tooMany.reasonCode),
                "MAX_STEPS+1 steps -> REJECTED(PLAN_TOO_LONG)");

        List<TaskStep> maxOk = new ArrayList<>();
        for (int i = 0; i < PlanValidator.MAX_STEPS; i++) {
            maxOk.add(TaskStep.of(CommandType.OPEN_WECHAT, "", "test"));
        }
        expect(PlanValidator.validate(new TaskPlan("TEST", "x", maxOk), a11yOn, false).accepted,
                "exactly MAX_STEPS steps is accepted");
    }

    // --------------------------------------------------------- task runner

    private static void taskRunnerTests() {
        RulePlanner planner = new RulePlanner();
        ContextFacts facts = new ContextFacts(TEST_ADDRESS,
                ContextFacts.SOURCE_FAKE_NOTIFICATION, true);

        // 全链路成功：所有步骤 CONFIRMED。
        RecordingExecutor allConfirmed = new RecordingExecutor(ExecutionStatus.CONFIRMED);
        TaskResult ok = TaskRunner.run(planner, "把刚收到的地址打开地图", facts,
                allConfirmed, false, "SELFTEST");
        expect(ok.status() == TaskResult.Status.COMPLETED_CONFIRMED,
                "all-confirmed run -> COMPLETED_CONFIRMED (got " + ok.status() + ")");
        expect("TASK_EXECUTED".equals(ok.eventName()), "all-confirmed run event is TASK_EXECUTED");
        expect(ok.steps().size() == 1 && allConfirmed.calls == 1,
                "one plan step executed exactly once");
        expect(ok.steps().get(0).status() == ExecutionStatus.CONFIRMED
                        && !ok.steps().get(0).skipped(),
                "step outcome carries CONFIRMED and is not marked skipped");

        // 混合确认：至少一步只有“已执行·未确认”，整体不能报 COMPLETED_CONFIRMED。
        TaskResult mixed = TaskRunner.run(planner, "返回上一页", facts,
                new RecordingExecutor(ExecutionStatus.EXECUTED_UNCONFIRMED), false, "SELFTEST");
        expect(mixed.status() == TaskResult.Status.COMPLETED_UNCONFIRMED,
                "unconfirmed execution -> COMPLETED_UNCONFIRMED (got " + mixed.status() + ")");
        expect("TASK_EXECUTED".equals(mixed.eventName()),
                "unconfirmed execution still reports TASK_EXECUTED (it did run)");

        TaskResult half = TaskRunner.run(stubPlanner(planOf(
                        TaskStep.of(CommandType.OPEN_WECHAT, "", "s1"),
                        TaskStep.of(CommandType.OPEN_MAP, TEST_ADDRESS, "s2"))),
                "two steps", facts, new MixedExecutor(), false, "SELFTEST");
        expect(half.status() == TaskResult.Status.COMPLETED_UNCONFIRMED,
                "CONFIRMED + EXECUTED_UNCONFIRMED -> COMPLETED_UNCONFIRMED (got " + half.status() + ")");

        // fail-fast：第一步失败，第二步必须根本不执行，并记为 SKIPPED。
        FailingFirstExecutor failFast = new FailingFirstExecutor();
        TaskResult failed = TaskRunner.run(stubPlanner(planOf(
                        TaskStep.of(CommandType.OPEN_MAP, TEST_ADDRESS, "s1"),
                        TaskStep.of(CommandType.OPEN_WECHAT, "", "s2"))),
                "fail fast", facts, failFast, false, "SELFTEST");
        expect(failed.status() == TaskResult.Status.FAILED,
                "failing first step -> FAILED (got " + failed.status() + ")");
        expect("TASK_FAILED".equals(failed.eventName()), "failing run event is TASK_FAILED");
        expect(failFast.calls == 1, "executor was called only once (fail-fast, got " + failFast.calls + ")");
        expect(failed.steps().size() == 2, "both steps are reported");
        expect(failed.steps().get(1).skipped() && failed.steps().get(1).status() == null
                        && "SKIPPED".equals(failed.steps().get(1).statusName()),
                "second step is marked SKIPPED without a status");
        expect("STEP_FAILED".equals(failed.reasonCode()),
                "failed run reason is STEP_FAILED (got " + failed.reasonCode() + ")");

        // 规划器做不到 -> BLOCKED，一步都不执行。
        RecordingExecutor neverCalled = new RecordingExecutor(ExecutionStatus.CONFIRMED);
        TaskResult blocked = TaskRunner.run(planner, "把刚收到的地址打开地图", ContextFacts.empty(),
                neverCalled, false, "SELFTEST");
        expect(blocked.status() == TaskResult.Status.BLOCKED,
                "no address in context -> BLOCKED (got " + blocked.status() + ")");
        expect("TASK_BLOCKED".equals(blocked.eventName()), "blocked run event is TASK_BLOCKED");
        expect("NO_ADDRESS_IN_CONTEXT".equals(blocked.reasonCode()),
                "blocked run keeps the planner reason code (got " + blocked.reasonCode() + ")");
        expect(neverCalled.calls == 0 && blocked.steps().isEmpty(),
                "blocked run executed nothing and reports zero steps");

        TaskResult cloudBlocked = TaskRunner.run(new CloudPlannerStub(false), "把刚收到的地址打开地图",
                facts, neverCalled, false, "SELFTEST");
        expect(cloudBlocked.status() == TaskResult.Status.BLOCKED
                        && "NO_API_KEY".equals(cloudBlocked.reasonCode()),
                "cloud stub without API key -> BLOCKED(NO_API_KEY)");
        expect(neverCalled.calls == 0, "cloud stub path executed nothing");

        // 校验层拒绝 -> BLOCKED（没有动作发生，不是 FAILED）。
        TaskResult rejected = TaskRunner.run(stubPlanner(planOf(new TaskStep(null, "", "bad", false))),
                "bad plan", facts, neverCalled, false, "SELFTEST");
        expect(rejected.status() == TaskResult.Status.BLOCKED
                        && "UNKNOWN_ACTION".equals(rejected.reasonCode()),
                "invalid plan -> BLOCKED(UNKNOWN_ACTION), got " + rejected.reasonCode());
        expect(neverCalled.calls == 0, "rejected plan executed nothing");

        // 敏感动作缺确认 -> BLOCKED，且执行器一次都不该被调用。
        TaskResult needConfirm = TaskRunner.run(stubPlanner(planOf(
                        TaskStep.sensitive(CommandType.OPEN_MAP, TEST_ADDRESS, "sensitive"))),
                "sensitive", facts, neverCalled, false, "SELFTEST");
        expect(needConfirm.status() == TaskResult.Status.BLOCKED
                        && "CONFIRMATION_REQUIRED".equals(needConfirm.reasonCode()),
                "sensitive step without confirmation -> BLOCKED(CONFIRMATION_REQUIRED), got "
                        + needConfirm.reasonCode());
        expect(neverCalled.calls == 0, "sensitive step without confirmation executed nothing");

        // 给出确认后，同一计划应当真正执行。
        RecordingExecutor confirmExec = new RecordingExecutor(ExecutionStatus.CONFIRMED);
        TaskResult withConfirm = TaskRunner.run(stubPlanner(planOf(
                        TaskStep.sensitive(CommandType.OPEN_MAP, TEST_ADDRESS, "sensitive"))),
                "sensitive", facts, confirmExec, true, "SELFTEST");
        expect(withConfirm.status() == TaskResult.Status.COMPLETED_CONFIRMED
                        && confirmExec.calls == 1,
                "sensitive step with confirmation is executed once");

        // 执行器返回 null：不能当成成功。
        TaskResult nullResult = TaskRunner.run(planner, "返回上一页", facts,
                new NullExecutor(), false, "SELFTEST");
        expect(nullResult.status() == TaskResult.Status.BLOCKED
                        && "NO_STEP_RESULT".equals(nullResult.reasonCode()),
                "executor returning null -> BLOCKED(NO_STEP_RESULT), got " + nullResult.reasonCode());

        // 执行器抛异常：必须被捕获并如实记录，不能让任务崩掉。
        TaskResult threw = TaskRunner.run(planner, "返回上一页", facts,
                new ThrowingExecutor(), false, "SELFTEST");
        expect(threw.status() == TaskResult.Status.BLOCKED
                        && "STEP_EXCEPTION".equals(threw.reasonCode()),
                "executor throwing -> BLOCKED(STEP_EXCEPTION), got " + threw.reasonCode());

        // 无障碍关闭时，BACK 任务在执行前就被拦下。
        RecordingExecutor a11yExec = new RecordingExecutor(ExecutionStatus.CONFIRMED);
        TaskResult noPerm = TaskRunner.run(planner, "返回上一页",
                new ContextFacts("", ContextFacts.SOURCE_NONE, false), a11yExec, false, "SELFTEST");
        expect(noPerm.status() == TaskResult.Status.BLOCKED
                        && "PERMISSION_REQUIRED".equals(noPerm.reasonCode()),
                "BACK without accessibility -> BLOCKED(PERMISSION_REQUIRED), got " + noPerm.reasonCode());
        expect(a11yExec.calls == 0, "BACK without accessibility executed nothing");

        expect(TaskRunner.run(null, "任意", facts, a11yExec, false, "SELFTEST").status()
                        == TaskResult.Status.BLOCKED,
                "null planner -> BLOCKED instead of crashing");
    }

    // --------------------------------------------------------------- json

    private static void jsonTests() {
        ContextFacts facts = new ContextFacts(TEST_ADDRESS,
                ContextFacts.SOURCE_FAKE_NOTIFICATION, true);
        TaskResult ok = TaskRunner.run(new RulePlanner(), "把刚收到的地址打开地图", facts,
                new RecordingExecutor(ExecutionStatus.CONFIRMED), false, "SELFTEST");
        String json = ok.toJson();

        expect(json != null && !json.isEmpty(), "toJson produces output");
        expect(!json.contains("\n") && !json.contains("\r"), "toJson is a single line");
        expect(json.startsWith("{") && json.endsWith("}"), "toJson is a JSON object");
        expect(json.contains("\"event\":\"TASK_EXECUTED\""), "json carries the event name");
        expect(json.contains("\"plannerId\":\"RULE_V1\""), "json carries the planner id");
        expect(json.contains("\"steps\":["), "json carries a steps array");
        expect(json.contains(TEST_ADDRESS), "json carries the executed address");
        expect(json.contains("\"status\":\"CONFIRMED\""), "json carries the step status");
        expect(json.contains("\"argument\":\"" + TEST_ADDRESS + "\""),
                "json records the address actually used by the step");
        expect(ok.steps().get(0).argument().equals(TEST_ADDRESS),
                "step outcome exposes the executed argument");

        TaskResult blocked = TaskRunner.run(new RulePlanner(), "把刚收到的地址打开地图",
                ContextFacts.empty(), new RecordingExecutor(ExecutionStatus.CONFIRMED), false, "SELFTEST");
        String blockedJson = blocked.toJson();
        expect(blockedJson.contains("\"event\":\"TASK_BLOCKED\""), "blocked json event is TASK_BLOCKED");
        // 地址来源必须写进审计记录：MOCK 注入的结果不能被误读成真实通知。
        expect(json.contains("\"addressSource\":\"MOCK_FAKE_NOTIFICATION\""),
                "json records the MOCK address source");
        expect(ok.usedMockAddress(), "task result flags itself as using a mock address");
        expect(blockedJson.contains("\"addressSource\":\"NONE\""),
                "json records NONE when no address was available");
        expect(!blocked.usedMockAddress(), "task without address is not flagged as mock");

        TaskResult realSrc = TaskRunner.run(new RulePlanner(), "把刚收到的地址打开地图",
                new ContextFacts(TEST_ADDRESS, ContextFacts.SOURCE_REAL_NOTIFICATION, true),
                new RecordingExecutor(ExecutionStatus.CONFIRMED), false, "SELFTEST");
        expect(realSrc.toJson().contains("\"addressSource\":\"REAL_NOTIFICATION_LISTENER\""),
                "json records the REAL notification source when provided");
        expect(!realSrc.usedMockAddress(), "REAL_ source is not flagged as mock");

        expect(blockedJson.contains("\"steps\":[]"), "blocked json has an empty steps array");
        expect(!blockedJson.contains("CONFIRMED"),
                "blocked json never claims a CONFIRMED step");

        // 转义：引号、反斜杠、换行都不能破坏单行 JSON。
        String escaped = TaskResult.escape("he said \"hi\"\\path\nline2\ttab");
        expect(!escaped.contains("\n") && !escaped.contains("\t"),
                "escape removes raw newlines and tabs");
        expect(escaped.contains("\\\"") && escaped.contains("\\\\"),
                "escape doubles quotes and backslashes");
        expect(TaskResult.escape(null).isEmpty(), "escape(null) is empty string");

        // Mock 标记必须能被读出来，避免测试数据被当成真实通知。
        expect(new ContextFacts(TEST_ADDRESS, ContextFacts.SOURCE_FAKE_NOTIFICATION, true).isMockAddress(),
                "MOCK_ address source is flagged as mock");
        expect(!new ContextFacts(TEST_ADDRESS, ContextFacts.SOURCE_REAL_NOTIFICATION, true).isMockAddress(),
                "REAL_ address source is not flagged as mock");
        expect(!ContextFacts.empty().hasAddress()
                        && ContextFacts.SOURCE_NONE.equals(ContextFacts.empty().addressSource()),
                "empty facts have no address and SOURCE_NONE");
        expect(new ContextFacts("  " + TEST_ADDRESS + "  ", null, false).latestAddress().equals(TEST_ADDRESS)
                        && ContextFacts.SOURCE_NONE.equals(
                        new ContextFacts(TEST_ADDRESS, null, false).addressSource()),
                "facts trim the address and default a null source to NONE");
    }

    // -------------------------------------------------------------- utils

    private static TaskPlan planOf(TaskStep... steps) {
        return new TaskPlan("TEST_STUB", "selftest", Arrays.asList(steps));
    }

    /** 返回固定状态的执行器，同时记录被调用次数，用于证明 fail-fast 真的生效。 */
    private static final class RecordingExecutor implements TaskRunner.StepExecutor {
        private final ExecutionStatus status;
        int calls = 0;

        RecordingExecutor(ExecutionStatus status) {
            this.status = status;
        }

        @Override
        public CommandResult execute(TaskStep step, String source) {
            calls++;
            return result(step.type(), step.argument(), status,
                    "selftest step " + calls, "evidence:calls=" + calls);
        }
    }

    /** 第一步 CONFIRMED、第二步 EXECUTED_UNCONFIRMED。 */
    private static final class MixedExecutor implements TaskRunner.StepExecutor {
        private int calls = 0;

        @Override
        public CommandResult execute(TaskStep step, String source) {
            calls++;
            ExecutionStatus status = calls == 1
                    ? ExecutionStatus.CONFIRMED : ExecutionStatus.EXECUTED_UNCONFIRMED;
            return result(step.type(), step.argument(), status,
                    "mixed step " + calls, "evidence:calls=" + calls);
        }
    }

    /** 第一步失败，后续步骤若被调用即视为 fail-fast 失效。 */
    private static final class FailingFirstExecutor implements TaskRunner.StepExecutor {
        int calls = 0;

        @Override
        public CommandResult execute(TaskStep step, String source) {
            calls++;
            if (calls == 1) {
                return result(step.type(), step.argument(), ExecutionStatus.NOT_INSTALLED,
                        "目标应用未安装", "evidence:resolveActivity=null");
            }
            return result(step.type(), step.argument(), ExecutionStatus.CONFIRMED,
                    "should never run", "");
        }
    }

    private static final class NullExecutor implements TaskRunner.StepExecutor {
        @Override
        public CommandResult execute(TaskStep step, String source) {
            return null;
        }
    }

    private static final class ThrowingExecutor implements TaskRunner.StepExecutor {
        @Override
        public CommandResult execute(TaskStep step, String source) {
            throw new IllegalStateException("selftest injected failure");
        }
    }

    private static Planner stubPlanner(final TaskPlan plan) {
        return new Planner() {
            @Override
            public String id() {
                return "TEST_STUB";
            }

            @Override
            public PlanResult plan(String utterance, ContextFacts facts) {
                return PlanResult.ready(plan);
            }
        };
    }

    private static CommandResult result(CommandType type, String argument, ExecutionStatus status,
                                        String detail, String evidence) {
        PocketCommand command = PocketCommand.create(
                type == null ? CommandType.BACK : type, argument, "SELFTEST");
        return new CommandResult(command, status, detail, evidence, 0L, System.currentTimeMillis());
    }

    private static void expect(boolean condition, String name) {
        if (condition) {
            pass(name);
        } else {
            fail(name);
        }
    }

    private static void pass(String name) {
        passed++;
        System.out.println("PASS  " + name);
    }

    private static void fail(String message) {
        FAILURES.add(message);
        System.out.println("FAIL  " + message);
    }
}
