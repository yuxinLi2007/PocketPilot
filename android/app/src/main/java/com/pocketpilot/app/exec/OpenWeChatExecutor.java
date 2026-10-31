package com.pocketpilot.app.exec;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import com.pocketpilot.app.core.CommandExtras;
import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.ExecutionStatus;
import com.pocketpilot.app.core.PocketCommand;
import com.pocketpilot.app.core.TargetConfig;

/**
 * 打开微信（或配置的替代包）。包名可配置，是为了在模拟器没有微信时
 * 用 Mock 应用验证同一条启动链路，而不是伪造“微信已打开”。
 */
public final class OpenWeChatExecutor implements CommandExecutor {
    private final Context context;
    private final TargetConfig config;

    public OpenWeChatExecutor(Context context, TargetConfig config) {
        this.context = context;
        this.config = config;
    }

    @Override
    public CommandType type() {
        return CommandType.OPEN_WECHAT;
    }

    @Override
    public CommandResult execute(PocketCommand command) {
        long started = SystemClock.elapsedRealtime();
        String pkg = config.wechatPackage();
        PackageManager pm = context.getPackageManager();
        Intent launchIntent = null;
        try {
            launchIntent = pm.getLaunchIntentForPackage(pkg);
        } catch (RuntimeException ignored) {
            // 包名非法或查询受限，按“不可用”处理
        }
        if (launchIntent == null) {
            return ResultFactory.of(command, ExecutionStatus.NOT_INSTALLED,
                    "未安装或不可启动：" + pkg,
                    "PackageManager.getLaunchIntentForPackage(" + pkg + ") = null; target_package=" + pkg,
                    started);
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launchIntent.putExtra(CommandExtras.COMMAND_ID, command.id());
        launchIntent.putExtra(CommandExtras.COMMAND_TYPE, command.type().wireName());
        try {
            context.startActivity(launchIntent);
        } catch (RuntimeException e) {
            return ResultFactory.of(command, ExecutionStatus.FAILED,
                    "启动抛出 " + e.getClass().getSimpleName(),
                    "intent=" + launchIntent + "; target_package=" + pkg, started);
        }
        return ResultFactory.of(command, ExecutionStatus.EXECUTED_UNCONFIRMED,
                "已发送启动请求，目标应用是否完成加载未由本应用确认",
                "component=" + launchIntent.getComponent() + "; target_package=" + pkg, started);
    }
}
