package com.pocketpilot.app.exec;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;

import com.pocketpilot.app.core.CommandExtras;
import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.ExecutionStatus;
import com.pocketpilot.app.core.PocketCommand;
import com.pocketpilot.app.core.TargetConfig;

/** 地图搜索：优先 geo: 意图，其次网页地图；两者都没有则如实报告不可用。 */
public final class OpenMapExecutor implements CommandExecutor {
    private final Context context;
    private final TargetConfig config;

    public OpenMapExecutor(Context context, TargetConfig config) {
        this.context = context;
        this.config = config;
    }

    @Override
    public CommandType type() {
        return CommandType.OPEN_MAP;
    }

    @Override
    public CommandResult execute(PocketCommand command) {
        long started = SystemClock.elapsedRealtime();
        String address = command.argument().trim();
        String forcedPackage = config.mapPackage();
        PackageManager pm = context.getPackageManager();

        Intent intent = buildIntent(Uri.parse("geo:0,0?q=" + Uri.encode(address)), forcedPackage);
        ResolveInfo resolver = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolver == null) {
            Uri webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(address));
            intent = buildIntent(webUri, forcedPackage);
            resolver = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        }
        if (resolver == null) {
            return ResultFactory.of(command, ExecutionStatus.NOT_INSTALLED,
                    "没有应用能处理 geo: 或网页地图搜索，地址未打开",
                    "resolveActivity failed; address=" + address + "; forced_package=" + forcedPackage,
                    started);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(CommandExtras.COMMAND_ID, command.id());
        intent.putExtra(CommandExtras.COMMAND_TYPE, command.type().wireName());
        intent.putExtra(CommandExtras.ADDRESS, address);
        String handler = resolver.activityInfo == null
                ? "(unknown)"
                : resolver.activityInfo.packageName + "/" + resolver.activityInfo.name;
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            return ResultFactory.of(command, ExecutionStatus.FAILED,
                    "启动地图失败：" + e.getClass().getSimpleName(),
                    "intent=" + intent + "; handler=" + handler, started);
        }
        return ResultFactory.of(command, ExecutionStatus.EXECUTED_UNCONFIRMED,
                "已把地址交给 " + handler + "，搜索结果未由本应用确认",
                "uri=" + intent.getData() + "; handler=" + handler
                        + "; target_package=" + (resolver.activityInfo == null ? "" : resolver.activityInfo.packageName),
                started);
    }

    private static Intent buildIntent(Uri uri, String forcedPackage) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (forcedPackage != null && !forcedPackage.trim().isEmpty()) {
            intent.setPackage(forcedPackage.trim());
        }
        return intent;
    }
}
