package com.pocketpilot.app;

import android.app.Application;
import android.util.Log;

import com.pocketpilot.app.core.TargetConfig;
import com.pocketpilot.app.data.FakeNotificationSource;
import com.pocketpilot.app.runtime.CommandPipeline;
import com.pocketpilot.app.runtime.TaskController;
import com.pocketpilot.app.transport.BleTransport;
import com.pocketpilot.app.transport.FakeBleTransport;
import com.pocketpilot.app.transport.GattBleTransport;

/**
 * 进程级装配点：默认使用 FakeBleTransport（模拟器可用），
 * 可在 UI 中切换到真实 GATT 传输，供连接 ESP32-S3-EYE 时使用。
 * 两种通道共用同一套命令管线，切换不会改变命令语义。
 */
public final class PocketPilotApp extends Application {
    private static final String TAG = "PocketPilot/App";
    private static volatile PocketPilotApp instance;

    private TargetConfig config;
    private CommandPipeline pipeline;
    private TaskController taskController;
    private boolean simulated = true;

    /**
     * 地址上下文来源，跨传输层切换保留。
     * 当前实现是测试注入源（MOCK_FAKE_NOTIFICATION），真实通知监听尚未实现。
     */
    private final FakeNotificationSource addressSource = new FakeNotificationSource();

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        config = new TargetConfig(this);
        pipeline = new CommandPipeline(this, new FakeBleTransport(), config);
        // 先装配任务入口，再启动传输：否则第一帧 TASK_TEXT 可能在 dispatcher 就绪前到达。
        taskController = new TaskController(this, pipeline, addressSource);
        pipeline.setTaskDispatcher((messageId, text, source) ->
                taskController.run(text, false, source));
        pipeline.start();
        Log.i(TAG, "PocketPilot started with SIMULATED transport; no BLE hardware involved"
                + "; planner=" + taskController.plannerId()
                + "; addressSource=" + addressSource.sourceTag());
    }

    public synchronized void useSimulatedTransport() {
        swap(new FakeBleTransport(), true);
    }

    /**
     * @return false 表示真实通道初始化失败（例如设备不支持 BLE 或依赖缺失），
     *         此时自动回退到模拟通道，App 不能因为切换通道而崩溃。
     */
    public synchronized boolean useGattTransport() {
        try {
            swap(new GattBleTransport(this), false);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "failed to open GATT transport, falling back to SIMULATED", e);
            try {
                swap(new FakeBleTransport(), true);
            } catch (RuntimeException fatal) {
                Log.e(TAG, "fallback to SIMULATED also failed", fatal);
            }
            return false;
        }
    }

    private void swap(BleTransport transport, boolean isSimulated) {
        // 先构造新管线，构造失败时旧管线仍在运行，通道不会“两头空”。
        CommandPipeline next = new CommandPipeline(this, transport, config);
        CommandPipeline previous = pipeline;
        if (previous != null) {
            previous.setUiListener(null);
            previous.stop();
        }
        pipeline = next;
        simulated = isSimulated;
        // 任务控制器跟着管线走，避免任务把步骤下发到已经停掉的旧管线。
        final TaskController nextTaskController = new TaskController(this, next, addressSource);
        taskController = nextTaskController;
        next.setTaskDispatcher((messageId, text, source) ->
                nextTaskController.run(text, false, source));
        next.start();
        Log.i(TAG, "transport switched to " + transport.id());
    }

    public synchronized boolean isSimulatedTransport() {
        return simulated;
    }

    public static CommandPipeline pipeline() {
        PocketPilotApp app = instance;
        if (app == null) {
            throw new IllegalStateException("PocketPilotApp not initialized");
        }
        return app.pipeline;
    }

    public static TaskController taskController() {
        PocketPilotApp app = instance;
        if (app == null) {
            throw new IllegalStateException("PocketPilotApp not initialized");
        }
        return app.taskController;
    }

    public static FakeNotificationSource addressSource() {
        PocketPilotApp app = instance;
        if (app == null) {
            throw new IllegalStateException("PocketPilotApp not initialized");
        }
        return app.addressSource;
    }

    public static PocketPilotApp get() {
        return instance;
    }
}
