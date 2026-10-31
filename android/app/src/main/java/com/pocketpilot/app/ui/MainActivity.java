package com.pocketpilot.app.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.pocketpilot.app.PocketPilotApp;
import com.pocketpilot.app.core.CommandExtras;
import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.ExecutionStatus;
import com.pocketpilot.app.core.TargetConfig;
import com.pocketpilot.app.runtime.CommandPipeline;
import com.pocketpilot.app.service.PocketAccessibilityService;
import com.pocketpilot.app.transport.TransportState;

import java.util.Locale;

/** 模拟器验收面板。所有“发送命令”按钮都只产生 SIMULATED 帧。 */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(244, 247, 251);
    private static final int INK = Color.rgb(24, 33, 47);
    private static final int MUTED = Color.rgb(92, 105, 125);
    private static final int BLUE = Color.rgb(31, 111, 235);
    private static final int GREEN = Color.rgb(22, 128, 80);
    private static final int RED = Color.rgb(190, 48, 48);
    private static final int ORANGE = Color.rgb(170, 103, 0);

    private CommandPipeline pipeline;
    private TargetConfig config;
    private EditText addressInput;
    private TextView transportView;
    private TextView accessibilityView;
    private TextView targetView;
    private TextView noticeView;
    private TextView resultTitle;
    private TextView resultDetail;
    private TextView resultEvidence;
    private TextView resultMeta;

    private final CommandPipeline.Listener pipelineListener = new CommandPipeline.Listener() {
        @Override
        public void onResult(CommandResult result) {
            renderResult(result);
        }

        @Override
        public void onTransportState(TransportState state, String detail) {
            renderTransport(state, detail);
        }

        @Override
        public void onNotice(String notice) {
            noticeView.setText(notice);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pipeline = PocketPilotApp.pipeline();
        config = pipeline.targetConfig();
        setContentView(buildContent());
        renderTransport(pipeline.transportState(), pipeline.transportDetail());
        renderResult(pipeline.latestResult());
        refreshTargets();
    }

    @Override
    protected void onStart() {
        super.onStart();
        pipeline.setUiListener(pipelineListener);
    }

    @Override
    protected void onStop() {
        pipeline.setUiListener(null);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessibility();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = text("POCKETPILOT · ANDROID 模拟器", 12, BLUE, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.12f);
        root.addView(eyebrow);

        TextView title = text("把一句话变成手机动作", 28, INK, Typeface.BOLD);
        title.setPadding(0, dp(6), 0, dp(4));
        root.addView(title);

        TextView subtitle = text("当前阶段优先在模拟器中验证 Android 端命令链路；实际使用哪条传输通道，以下方状态卡为准。", 14, MUTED, Typeface.NORMAL);
        subtitle.setLineSpacing(dp(3), 1f);
        root.addView(subtitle);

        TextView warning = text(pipeline.isSimulated()
                        ? "模拟 BLE · 非真实蓝牙\n所有注入都来自进程内的 FakeBleTransport，不能作为硬件通信或真机验收证据。"
                        : "真实 GATT 通道\n需要 ESP32-S3-EYE 已烧录 PocketPilot 固件并完成配对授权；真机结果需另行记录。",
                14, Color.rgb(112, 74, 0), Typeface.BOLD);
        warning.setPadding(dp(16), dp(14), dp(16), dp(14));
        warning.setBackground(shape(Color.rgb(255, 243, 205), 14, Color.rgb(242, 201, 76)));
        LinearLayout.LayoutParams warningParams = fullWidth();
        warningParams.topMargin = dp(18);
        root.addView(warning, warningParams);

        LinearLayout statusCard = card();
        root.addView(statusCard, withTop(16));
        transportView = text("", 15, INK, Typeface.BOLD);
        statusCard.addView(transportView);
        noticeView = text("等待模拟命令", 13, MUTED, Typeface.NORMAL);
        noticeView.setPadding(0, dp(6), 0, 0);
        statusCard.addView(noticeView);

        LinearLayout commandCard = card();
        root.addView(commandCard, withTop(16));
        commandCard.addView(sectionTitle("01  注入模拟命令"));
        commandCard.addView(caption("等同于 ESP32 在真实链路上发来一帧；当前并未读取麦克风，也未经过 BLE。"));

        Button wechat = actionButton("模拟收到 · 打开微信");
        wechat.setOnClickListener(v -> inject(CommandType.OPEN_WECHAT, ""));
        commandCard.addView(wechat, withTop(14));

        addressInput = new EditText(this);
        addressInput.setText("北京市海淀区中关村大街1号");
        addressInput.setHint("地图搜索地址");
        addressInput.setSingleLine(true);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT);
        addressInput.setTextSize(15);
        addressInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        addressInput.setBackground(shape(Color.rgb(246, 248, 251), 10, Color.rgb(216, 223, 234)));
        commandCard.addView(addressInput, withTop(12));

        Button map = actionButton("模拟收到 · 打开地图");
        map.setOnClickListener(v -> inject(CommandType.OPEN_MAP, addressInput.getText().toString()));
        commandCard.addView(map, withTop(8));

        Button back = actionButton("模拟收到 · 返回上一页");
        back.setOnClickListener(v -> inject(CommandType.BACK, ""));
        commandCard.addView(back, withTop(8));

        LinearLayout targetCard = card();
        root.addView(targetCard, withTop(16));
        targetCard.addView(sectionTitle("02  目标应用配置"));
        targetCard.addView(caption("模拟器没有微信和真实地图。可切换到同签名的 Mock 替代目标，但结果会明确标注为替代验证。"));
        targetView = text("", 14, INK, Typeface.NORMAL);
        targetView.setPadding(0, dp(12), 0, dp(8));
        targetView.setTextIsSelectable(true);
        targetCard.addView(targetView);

        Button useMock = secondaryButton("微信测试目标 → Mock 替代应用");
        useMock.setOnClickListener(v -> {
            config.setWechatPackage(CommandExtras.MOCK_PACKAGE);
            refreshTargets();
            toast("已切换微信测试目标；这不会安装或伪造真实微信");
        });
        targetCard.addView(useMock);

        Button restoreWechat = secondaryButton("恢复真实微信包名 com.tencent.mm");
        restoreWechat.setOnClickListener(v -> {
            config.setWechatPackage(TargetConfig.DEFAULT_WECHAT_PACKAGE);
            refreshTargets();
            toast("已恢复真实微信包名");
        });
        targetCard.addView(restoreWechat, withTop(8));

        Button mapMock = secondaryButton("地图测试目标 → Mock 替代应用");
        mapMock.setOnClickListener(v -> {
            config.setMapPackage(CommandExtras.MOCK_PACKAGE);
            refreshTargets();
            toast("地图命令将显式交给 Mock 应用，用于验证地址传递");
        });
        targetCard.addView(mapMock, withTop(8));

        Button mapSystem = secondaryButton("地图测试目标 → 交给系统选择");
        mapSystem.setOnClickListener(v -> {
            config.setMapPackage("");
            refreshTargets();
            toast("已恢复系统地图选择");
        });
        targetCard.addView(mapSystem, withTop(8));

        LinearLayout permissionCard = card();
        root.addView(permissionCard, withTop(16));
        permissionCard.addView(sectionTitle("03  无障碍权限"));
        permissionCard.addView(caption("返回命令只通过系统无障碍服务执行；未授权时会明确显示“缺少授权”。"));
        accessibilityView = text("", 14, INK, Typeface.BOLD);
        accessibilityView.setPadding(0, dp(12), 0, dp(8));
        permissionCard.addView(accessibilityView);
        Button settingsButton = secondaryButton("打开系统无障碍设置");
        settingsButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permissionCard.addView(settingsButton);

        LinearLayout realCard = card();
        root.addView(realCard, withTop(16));
        realCard.addView(sectionTitle("04  传输通道"));
        realCard.addView(caption("FakeBleTransport 用于模拟器回归；GattBleTransport 连接已烧录 PocketPilot 固件的 ESP32-S3-EYE。切换后请以真实硬件日志为准。"));
        Button gatt = secondaryButton(pipeline.isSimulated()
                ? "切换到真实 GATT（需要 ESP32 与蓝牙权限）"
                : "切回模拟 BLE 通道");
        gatt.setOnClickListener(v -> {
            if (!pipeline.isSimulated()) {
                PocketPilotApp.get().useSimulatedTransport();
                recreate();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, REQUEST_BLE_PERMISSIONS);
                return;
            }
            switchToGatt();
        });
        realCard.addView(gatt, withTop(12));

        Button openMock = secondaryButton("直接打开 Mock 替代目标（含第二页）");
        openMock.setOnClickListener(v -> openMockTarget());
        root.addView(openMock, withTop(10));

        LinearLayout resultCard = card();
        root.addView(resultCard, withTop(16));
        resultCard.addView(sectionTitle("最近一次执行结果"));
        resultTitle = text("尚未收到命令", 18, MUTED, Typeface.BOLD);
        resultTitle.setPadding(0, dp(12), 0, dp(4));
        resultCard.addView(resultTitle);
        resultDetail = text("", 14, INK, Typeface.NORMAL);
        resultDetail.setTextIsSelectable(true);
        resultCard.addView(resultDetail);
        resultMeta = text("", 12, MUTED, Typeface.NORMAL);
        resultMeta.setPadding(0, dp(8), 0, 0);
        resultMeta.setTextIsSelectable(true);
        resultCard.addView(resultMeta);
        resultEvidence = text("", 11, MUTED, Typeface.NORMAL);
        resultEvidence.setTypeface(Typeface.MONOSPACE);
        resultEvidence.setPadding(0, dp(10), 0, 0);
        resultEvidence.setTextIsSelectable(true);
        resultCard.addView(resultEvidence);

        return scroll;
    }

    private static final int REQUEST_BLE_PERMISSIONS = 4101;

    private void switchToGatt() {
        boolean ok = PocketPilotApp.get().useGattTransport();
        if (ok) {
            recreate();
            return;
        }
        // 模拟器通常没有可用蓝牙适配器：必须明确失败并留在模拟通道。
        toast("真实 GATT 不可用，已保留模拟通道");
        noticeView.setText("真实 GATT 初始化失败（模拟器/设备无可用蓝牙）；已自动回退到模拟通道。");
        renderTransport(pipeline.transportState(), pipeline.transportDetail());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLE_PERMISSIONS) {
            return;
        }
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            granted = granted && result == PackageManager.PERMISSION_GRANTED;
        }
        if (granted) {
            switchToGatt();
        } else {
            toast("蓝牙权限被拒绝，保持模拟通道");
        }
    }

    private void inject(CommandType type, String argument) {
        boolean ok = pipeline.injectFakeCommand(type, argument);
        if (!ok) {
            toast("当前不是模拟通道，命令未注入");
            return;
        }
        noticeView.setText("SIMULATED RX → " + type.wireName());
        Toast.makeText(this, "已注入模拟命令（非 BLE）", Toast.LENGTH_SHORT).show();
    }

    private void openMockTarget() {
        Intent intent = new Intent();
        intent.setClassName(CommandExtras.MOCK_PACKAGE, CommandExtras.MOCK_PACKAGE + ".MainActivity");
        intent.putExtra(CommandExtras.COMMAND_ID, "MANUAL-OPEN");
        intent.putExtra(CommandExtras.COMMAND_TYPE, "MANUAL");
        intent.putExtra(CommandExtras.ADDRESS, "手动打开，用于返回键测试");
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            toast("请先安装 mocktarget-debug.apk");
        }
    }

    private void refreshTargets() {
        targetView.setText("微信目标包：\n" + config.wechatPackage()
                + "\n\n地图测试目标：\n"
                + (config.mapPackage().isEmpty() ? "系统选择（未强制）" : config.mapPackage()));
    }

    private void refreshAccessibility() {
        boolean enabled = PocketAccessibilityService.isEnabled(this);
        boolean connected = PocketAccessibilityService.getInstance() != null;
        accessibilityView.setText(enabled
                ? (connected ? "已授权，服务已连接" : "已授权，但服务尚未连接")
                : "未授权 · COMMAND_BACK 会返回“缺少授权”");
        accessibilityView.setTextColor(enabled && connected ? GREEN : ORANGE);
    }

    private void renderTransport(TransportState state, String detail) {
        String mode = pipeline.isSimulated() ? "SIMULATED · 非真实蓝牙" : "REAL GATT · 连接 ESP32-S3-EYE";
        transportView.setText("通道：" + mode + "\n状态：" + state.label() + " · " + detail);
    }

    private void renderResult(CommandResult result) {
        if (result == null) {
            resultTitle.setText("尚未收到命令");
            resultTitle.setTextColor(MUTED);
            return;
        }
        ExecutionStatus status = result.status();
        resultTitle.setText(status.label() + "  ·  " + (result.command().type() == null
                ? "PROTOCOL_ERROR" : result.command().type().wireName()));
        resultTitle.setTextColor(statusColor(status));
        resultDetail.setText(result.detail());
        resultMeta.setText(String.format(Locale.CHINA, "%s  ·  %d ms  ·  id=%s",
                result.command().source(), result.elapsedMillis(), result.command().id()));
        resultEvidence.setText(result.evidence());
    }

    private static int statusColor(ExecutionStatus status) {
        switch (status) {
            case CONFIRMED: return GREEN;
            case FAILED:
            case REJECTED: return RED;
            case PERMISSION_REQUIRED:
            case NOT_INSTALLED: return ORANGE;
            case EXECUTED_UNCONFIRMED: return BLUE;
            default: return MUTED;
        }
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        view.setBackground(shape(Color.WHITE, 16, Color.rgb(225, 231, 240)));
        return view;
    }

    private TextView sectionTitle(String value) {
        return text(value, 18, INK, Typeface.BOLD);
    }

    private TextView caption(String value) {
        TextView view = text(value, 13, MUTED, Typeface.NORMAL);
        view.setLineSpacing(dp(2), 1f);
        view.setPadding(0, dp(6), 0, 0);
        return view;
    }

    private TextView text(String value, int sizeSp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private Button actionButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
        button.setMinHeight(dp(48));
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = actionButton(value);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(232, 239, 250)));
        button.setTextColor(BLUE);
        return button;
    }

    private GradientDrawable shape(int fill, int radiusDp, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams withTop(int dp) {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(dp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}



