package com.pocketpilot.app.runtime;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.data.AddressContextProvider;
import com.pocketpilot.app.plan.ContextFacts;
import com.pocketpilot.app.plan.Planner;
import com.pocketpilot.app.plan.RulePlanner;
import com.pocketpilot.app.plan.TaskResult;
import com.pocketpilot.app.plan.TaskRunner;
import com.pocketpilot.app.plan.TaskStep;
import com.pocketpilot.app.service.PocketAccessibilityService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多步骤任务的组装点：上下文 -> 规划 -> 校验 -> 执行 -> 落盘 -> 反馈。
 *
 * 规划层（plan 包）是纯 Java，不认识 Android；本类负责把它接到真实管线。
 * 每一步仍然走 {@link CommandPipeline#runTaskStep}，也就是和 BLE 命令完全相同的
 * 校验、执行与记录路径，任务不会获得任何绕过白名单的特权。
 */
public final class TaskController {
    private static final String TAG = "PocketPilot/Task";

    private final Context context;
    private final CommandPipeline pipeline;
    private final AddressContextProvider addressSource;
    private final Planner planner;
    private final ExecutorService taskWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "pocketpilot-task");
        thread.setDaemon(true);
        return thread;
    });

    public TaskController(Context context, CommandPipeline pipeline,
                          AddressContextProvider addressSource) {
        this(context, pipeline, addressSource, new RulePlanner());
    }

    /** 规划器可替换（例如将来接入云端模型），但校验层不可绕过。 */
    public TaskController(Context context, CommandPipeline pipeline,
                          AddressContextProvider addressSource, Planner planner) {
        this.context = context.getApplicationContext();
        this.pipeline = pipeline;
        this.addressSource = addressSource;
        this.planner = planner == null ? new RulePlanner() : planner;
    }

    public String plannerId() {
        return planner.id();
    }

    /**
     * 同步执行一次任务。会阻塞最长若干秒，因此禁止在主线程调用。
     *
     * @param source 任务来源标记，会写进每一步的命令记录。调试注入请使用 DEBUG_TASK。
     */
    public TaskResult run(String utterance, boolean confirmationGiven, String source) {
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            throw new IllegalStateException(
                    "TaskController.run must not be called on the main thread; use submit()");
        }
        ContextFacts facts = addressSource == null
                ? new ContextFacts("", ContextFacts.SOURCE_NONE,
                        PocketAccessibilityService.isEnabled(context))
                : addressSource.snapshot(PocketAccessibilityService.isEnabled(context));

        String safeSource = source == null || source.isEmpty() ? "TASK" : source;
        TaskResult result = TaskRunner.run(planner, utterance, facts,
                (TaskStep step, String src) -> pipeline.runTaskStep(step.type(), step.argument(), src),
                confirmationGiven, safeSource);

        pipeline.resultsStore().appendRaw(result.toJson());
        String notice = describeForUser(result, facts);
        pipeline.publishNotice(notice);
        Log.i(TAG, result.describe() + " utterance=" + utterance
                + " addressSource=" + facts.addressSource()
                + " mockAddress=" + facts.isMockAddress()
                + " notice=" + notice);
        return result;
    }

    /** 异步提交，供广播接收器等不能阻塞的调用方使用。 */
    public void submit(String utterance, boolean confirmationGiven, String source) {
        taskWorker.execute(() -> {
            try {
                run(utterance, confirmationGiven, source);
            } catch (RuntimeException e) {
                // 任务失败必须留下记录，不能让异常静默消失后被误认为成功。
                Log.e(TAG, "task failed utterance=" + utterance, e);
                pipeline.publishNotice("任务异常终止：" + e.getClass().getSimpleName());
            }
        });
    }

    private static String describeForUser(TaskResult result, ContextFacts facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务").append(labelOf(result.status()));
        if (!result.reasonCode().isEmpty()) {
            sb.append("（").append(result.reasonCode()).append("）");
        }
        if (result.status() == TaskResult.Status.COMPLETED_CONFIRMED
                || result.status() == TaskResult.Status.COMPLETED_UNCONFIRMED) {
            sb.append("：").append(result.detail());
        } else if (!result.detail().isEmpty()) {
            sb.append("：").append(result.detail());
        }
        if (facts.isMockAddress()) {
            sb.append("｜地址来自测试注入，非真实通知");
        }
        return sb.toString();
    }

    private static String labelOf(TaskResult.Status status) {
        switch (status) {
            case COMPLETED_CONFIRMED:
                return "已完成并确认";
            case COMPLETED_UNCONFIRMED:
                return "已执行但未确认";
            case BLOCKED:
                return "未执行";
            case FAILED:
            default:
                return "执行失败";
        }
    }
}
