package com.pocketpilot.app.core;

import android.content.Context;
import android.content.SharedPreferences;

/** 目标包名可配置：真实应用与 Mock 替代目标走同一段启动代码，不写死分支。 */
public final class TargetConfig {
    private static final String PREFS = "pocketpilot_settings";
    private static final String KEY_WECHAT_PACKAGE = "wechat_package";
    private static final String KEY_MAP_PACKAGE = "map_package";
    public static final String DEFAULT_WECHAT_PACKAGE = "com.tencent.mm";

    private final SharedPreferences prefs;

    public TargetConfig(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String wechatPackage() {
        return prefs.getString(KEY_WECHAT_PACKAGE, DEFAULT_WECHAT_PACKAGE);
    }

    public void setWechatPackage(String packageName) {
        prefs.edit().putString(KEY_WECHAT_PACKAGE, packageName).apply();
    }

    /** 空字符串表示交给系统选择地图应用；填写包名用于确定性地测试替代目标。 */
    public String mapPackage() {
        return prefs.getString(KEY_MAP_PACKAGE, "");
    }

    public void setMapPackage(String packageName) {
        prefs.edit().putString(KEY_MAP_PACKAGE, packageName == null ? "" : packageName).apply();
    }
}
