package com.pocketpilot.app.exec;

import android.content.Context;
import android.os.SystemClock;

import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.ExecutionStatus;
import com.pocketpilot.app.core.PocketCommand;
import com.pocketpilot.app.service.PocketAccessibilityService;

/** 返回上一页：只能通过无障碍服务完成，未授权时明确拒绝，不做静默降级。 */
public final class BackExecutor implements CommandExecutor {
    private static final long VERIFY_DELAY_MS = 500L;
    private final Context context;

    public BackExecutor(Context context) {
        this.context = context;
    }

    @Override
    public CommandType type() {
        return CommandType.BACK;
    }

    @Override
    public CommandResult execute(PocketCommand command) {
        long started = SystemClock.elapsedRealtime();
        if (!PocketAccessibilityService.isEnabled(context)) {
            return ResultFactory.of(command, ExecutionStatus.PERMISSION_REQUIRED,
                    "无障碍服务未启用，返回命令未执行",
                    "ENABLED_ACCESSIBILITY_SERVICES does not contain "
                            + context.getPackageName() + "/" + PocketAccessibilityService.class.getName(), started);
        }
        PocketAccessibilityService service = PocketAccessibilityService.getInstance();
        if (service == null) {
            return ResultFactory.of(command, ExecutionStatus.FAILED,
                    "无障碍服务已授权但未连接，请在系统设置里关闭再开启一次",
                    "serviceInstance = null", started);
        }
        String before = service.awaitForegroundSignature(600L);
        boolean performed = service.performBack();
        if (!performed) {
            return ResultFactory.of(command, ExecutionStatus.FAILED,
                    "performGlobalAction(GLOBAL_ACTION_BACK) 返回 false",
                    "before=" + before, started);
        }
        try {
            Thread.sleep(VERIFY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String after = service.foregroundSignature();
        if (after != null && before != null && !after.equals(before)) {
            return ResultFactory.of(command, ExecutionStatus.CONFIRMED,
                    "返回已生效，前台窗口发生变化",
                    "before=" + before + " -> after=" + after, started);
        }
        return ResultFactory.of(command, ExecutionStatus.EXECUTED_UNCONFIRMED,
                "已触发返回，但前台窗口未变化（可能已在根页面）",
                "before=" + before + " -> after=" + after, started);
    }
}
