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

    /**
     * 返回安全白名单（决议 2026-11-11 #3）：只对已知应用发送"返回"。
     * "返回"在不同应用里语义不可预测——在微信聊天页可能退回列表、在 H5 页可能
     * 直接退出整个应用。宁可拒绝并让语音播报说明，也不在未知界面盲发返回。
     *
     * 前缀匹配项：启动器（返回=回桌面，永远安全）+ 自家应用。
     */
    private static final java.util.Set<String> SAFE_BACK_PACKAGES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.tencent.mm",            // 微信
            "com.autonavi.minimap",      // 高德地图
            "com.baidu.BaiduMap",        // 百度地图
            "com.tencent.map",           // 腾讯地图
            "com.pocketpilot.mocktarget" // 自家测试靶应用（模拟器回归用）
    ));
    private static final java.util.Set<String> SAFE_BACK_PREFIXES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.launcher",      // 系统启动器
            "com.google.android.apps.nexuslauncher",
            "com.huawei.android.launcher",
            "com.miui.home",             // 小米启动器
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.pocketpilot.app"        // 自家应用
    ));

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
        String foregroundPkg = extractPackage(before);
        if (!isSafeBackTarget(foregroundPkg)) {
            return ResultFactory.of(command, ExecutionStatus.REJECTED,
                    "当前应用不在返回安全名单，已拒绝返回以避免误退出",
                    "foreground=" + before, started);
        }
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

    /** 从 "pkg/class" 签名中提取包名；签名缺失时返回 null。 */
    private static String extractPackage(String signature) {
        if (signature == null || signature.isEmpty() || "unknown".equals(signature)) {
            return null;
        }
        int slash = signature.indexOf('/');
        return slash > 0 ? signature.substring(0, slash) : signature;
    }

    /** 白名单判定：精确匹配 + 启动器前缀匹配。未知包一律不安全。 */
    private static boolean isSafeBackTarget(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (SAFE_BACK_PACKAGES.contains(pkg)) {
            return true;
        }
        for (String prefix : SAFE_BACK_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
