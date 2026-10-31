package com.pocketpilot.app.verify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.pocketpilot.app.PocketPilotApp;
import com.pocketpilot.app.core.CommandExtras;

/**
 * 只接受同签名 Mock 目标发来的“已到达”回执。
 * 真实微信、真实地图不会发送这个广播，因此它们最多只能处于“已执行·未确认”。
 */
public final class TargetAckReceiver extends BroadcastReceiver {
    private static final String TAG = "PocketPilot/TargetAck";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !CommandExtras.ACTION_TARGET_ACK.equals(intent.getAction())) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            String sender = getSentFromPackage();
            if (sender != null && !CommandExtras.MOCK_PACKAGE.equals(sender)) {
                Log.w(TAG, "ignored ack from unexpected package: " + sender);
                return;
            }
        }
        String targetPackage = intent.getStringExtra(CommandExtras.EXTRA_TARGET_PACKAGE);
        if (!CommandExtras.MOCK_PACKAGE.equals(targetPackage)) {
            Log.w(TAG, "ignored ack for non-mock target: " + targetPackage);
            return;
        }
        String commandId = intent.getStringExtra(CommandExtras.COMMAND_ID);
        String detail = intent.getStringExtra(CommandExtras.EXTRA_DETAIL);
        PocketPilotApp.pipeline().confirmMockReached(commandId, targetPackage, detail);
        Log.i(TAG, "mock ack commandId=" + commandId + " detail=" + detail);
    }
}
