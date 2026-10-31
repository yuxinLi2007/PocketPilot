package com.pocketpilot.app.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.pocketpilot.app.PocketPilotApp;
import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.TargetConfig;
import com.pocketpilot.app.runtime.CommandPipeline;

/**
 * 仅存在于 debug APK。它是测试入口，不参与 release 构建，
 * 也不能被描述成真实 BLE 接收器。
 */
public final class DebugInjectReceiver extends BroadcastReceiver {
    private static final String TAG = "PocketPilot/DebugInject";
    public static final String ACTION_INJECT = "com.pocketpilot.app.DEBUG_INJECT";
    public static final String ACTION_CONFIG = "com.pocketpilot.app.DEBUG_CONFIG";
    /** 注入一次多步骤任务。utterance 必填，address 为测试注入地址，confirm 表示已确认敏感动作。 */
    public static final String ACTION_TASK = "com.pocketpilot.app.DEBUG_TASK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        CommandPipeline pipeline = PocketPilotApp.pipeline();
        if (ACTION_TASK.equals(intent.getAction())) {
            handleTask(intent);
            return;
        }
        if (ACTION_CONFIG.equals(intent.getAction())) {
            TargetConfig config = pipeline.targetConfig();
            if (intent.hasExtra("wechat_package")) {
                config.setWechatPackage(intent.getStringExtra("wechat_package"));
            }
            if (intent.hasExtra("map_package")) {
                config.setMapPackage(intent.getStringExtra("map_package"));
            }
            Log.i(TAG, "config updated: wechat=" + config.wechatPackage()
                    + " map=" + config.mapPackage());
            return;
        }
        if (!ACTION_INJECT.equals(intent.getAction())) {
            return;
        }
        String raw = intent.getStringExtra("raw");
        if (raw != null) {
            boolean accepted = pipeline.injectFakeRaw(raw);
            Log.i(TAG, "SIMULATED raw injected accepted=" + accepted + " frame=" + raw.trim());
            return;
        }
        String taskText = intent.getStringExtra("task_text");
        if (taskText != null) {
            String messageId = intent.getStringExtra("message_id");
            if (messageId == null || messageId.trim().isEmpty()) {
                messageId = "debug-" + System.currentTimeMillis();
            }
            if (intent.hasExtra("clear_address")) {
                PocketPilotApp.addressSource().clearInjectedAddress();
                Log.i(TAG, "SIMULATED task context cleared: injected address removed");
            } else if (intent.hasExtra("address")) {
                String address = intent.getStringExtra("address");
                PocketPilotApp.addressSource().setInjectedAddress(address);
                Log.i(TAG, "SIMULATED task context set: MOCK address length="
                        + (address == null ? 0 : address.trim().length()));
            }
            int taskCount = Math.max(1, Math.min(intent.getIntExtra("count", 1), 10));
            boolean taskAccepted = false;
            for (int i = 0; i < taskCount; i++) {
                taskAccepted = pipeline.injectFakeTaskText(messageId, taskText);
            }
            Log.i(TAG, "SIMULATED task_text injected accepted=" + taskAccepted
                    + " message_id=" + messageId + " count=" + taskCount
                    + " text_bytes=" + taskText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            return;
        }
        String wire = intent.getStringExtra("command");
        CommandType type = CommandType.fromWire(wire);
        if (type == null) {
            Log.w(TAG, "SIMULATED command rejected: unknown wire command=" + wire);
            return;
        }
        if (type == CommandType.TASK_TEXT) {
            Log.w(TAG, "SIMULATED command rejected: COMMAND_TASK_TEXT requires 'task_text' extra");
            return;
        }
        String argument = intent.getStringExtra("argument");
        // Debug-only: count > 1 injects identical frames back-to-back so the
        // duplicate-suppression path can be exercised from adb.
        int count = Math.max(1, Math.min(intent.getIntExtra("count", 1), 10));
        boolean accepted = false;
        for (int i = 0; i < count; i++) {
            accepted = pipeline.injectFakeCommand(type, argument == null ? "" : argument);
        }
        Log.i(TAG, "SIMULATED command injected accepted=" + accepted + " command=" + wire + " count=" + count);
    }

    /**
     * 任务注入。这是调试入口，不是真实语音、真实 BLE 或真实通知事件；
     * 日志一律带 SIMULATED 前缀，落盘的 addressSource 也恒为 MOCK_FAKE_NOTIFICATION。
     */
    private void handleTask(Intent intent) {
        String utterance = intent.getStringExtra("utterance");
        if (utterance == null || utterance.trim().isEmpty()) {
            Log.w(TAG, "SIMULATED task rejected: missing or blank 'utterance' extra");
            return;
        }
        if (intent.hasExtra("clear_address")) {
            PocketPilotApp.addressSource().clearInjectedAddress();
            Log.i(TAG, "SIMULATED task context cleared: injected address removed");
        } else if (intent.hasExtra("address")) {
            String address = intent.getStringExtra("address");
            PocketPilotApp.addressSource().setInjectedAddress(address);
            Log.i(TAG, "SIMULATED task context set: MOCK address length="
                    + (address == null ? 0 : address.trim().length()));
        }
        boolean confirm = intent.getBooleanExtra("confirm", false);
        PocketPilotApp.taskController().submit(utterance, confirm, "DEBUG_TASK");
        Log.i(TAG, "SIMULATED task submitted utterance=" + utterance + " confirm=" + confirm
                + " addressSource=" + PocketPilotApp.addressSource().sourceTag());
    }
}

