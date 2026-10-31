package com.pocketpilot.app.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * 只申请“观察窗口切换 + 执行全局返回”所需的最小能力。
 * 不读取窗口内容，也不执行任意点击。
 */
public final class PocketAccessibilityService extends AccessibilityService {
    private static volatile PocketAccessibilityService instance;
    private final Object foregroundLock = new Object();
    private volatile String foregroundPackage;
    private volatile String foregroundClass;

    public static PocketAccessibilityService getInstance() {
        return instance;
    }

    public String foregroundPackage() {
        return foregroundPackage;
    }

    public String foregroundSignature() {
        String packageName = foregroundPackage;
        if (packageName == null) {
            return null;
        }
        return packageName + "/" + (foregroundClass == null ? "unknown" : foregroundClass);
    }

    /** 等待窗口状态事件到达；无障碍服务刚启用时，首个事件可能稍有延迟。 */
    public String awaitForegroundSignature(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + Math.max(0L, timeoutMs);
        synchronized (foregroundLock) {
            while (foregroundPackage == null) {
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    foregroundLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return foregroundSignature();
    }

    public boolean performBack() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /**
     * 判断本服务当前是否可用。
     *
     * <p>不能只比较 Settings 里的原始字符串：同一个服务既可能被写成
     * {@code pkg/com.pkg.service.X}，也可能被写成 {@code pkg/.service.X}，
     * 直接字符串比较会把已启用的服务误判为未授权。这里先查系统实际启用的
     * 服务列表，再用 {@link ComponentName#unflattenFromString(String)} 兜底。
     */
    public static boolean isEnabled(Context context) {
        ComponentName expected = new ComponentName(context, PocketAccessibilityService.class);

        AccessibilityManager manager = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager != null) {
            List<AccessibilityServiceInfo> services =
                    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (services != null) {
                for (AccessibilityServiceInfo info : services) {
                    if (info == null || info.getResolveInfo() == null
                            || info.getResolveInfo().serviceInfo == null) {
                        continue;
                    }
                    android.content.pm.ServiceInfo si = info.getResolveInfo().serviceInfo;
                    if (expected.equals(new ComponentName(si.packageName, si.name))) {
                        return true;
                    }
                }
            }
        }

        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName parsed = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(parsed)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }
        CharSequence className = event.getClassName();
        synchronized (foregroundLock) {
            foregroundPackage = event.getPackageName().toString();
            foregroundClass = className == null ? null : className.toString();
            foregroundLock.notifyAll();
        }
    }

    @Override
    public void onInterrupt() {
        // 当前只执行一次性全局动作，无长任务需要中断。
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
