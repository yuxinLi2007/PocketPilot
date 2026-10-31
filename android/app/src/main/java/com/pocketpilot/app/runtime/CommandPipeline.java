package com.pocketpilot.app.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.pocketpilot.app.core.CommandCodec;
import com.pocketpilot.app.core.CommandExtras;
import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.CommandValidator;
import com.pocketpilot.app.core.ExecutionStatus;
import com.pocketpilot.app.core.PocketCommand;
import com.pocketpilot.app.core.StatusFrameEncoder;
import com.pocketpilot.app.core.TargetConfig;
import com.pocketpilot.app.core.TaskMessageGuard;
import com.pocketpilot.app.core.TaskTextProtocol;
import com.pocketpilot.app.plan.TaskResult;
import com.pocketpilot.app.data.ResultsStore;
import com.pocketpilot.app.exec.BackExecutor;
import com.pocketpilot.app.exec.CommandExecutor;
import com.pocketpilot.app.exec.ExecutorRegistry;
import com.pocketpilot.app.exec.OpenMapExecutor;
import com.pocketpilot.app.exec.OpenWeChatExecutor;
import com.pocketpilot.app.service.PocketAccessibilityService;
import com.pocketpilot.app.transport.BleTransport;
import com.pocketpilot.app.transport.FakeBleTransport;
import com.pocketpilot.app.transport.TransportState;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单一命令管线：字节 -> 解码 -> 校验 -> 执行 -> 记录 -> 反馈。
 * 传输层完全不知道“微信/地图”的语义，执行层也完全不知道字节来自哪里。
 */
public final class CommandPipeline implements BleTransport.Listener {
    private static final String TAG = "PocketPilot/Pipeline";

    /** 任务步骤在主调用线程上等待工作线程的上限。超时按失败处理，绝不无限期挂着。 */
    private static final long TASK_STEP_TIMEOUT_MS = 15_000L;

    /** Mock 回执可能晚于 startActivity 返回，任务步骤给它一个有上限的等待窗口。 */
    private static final long MOCK_ACK_WAIT_MS = 2_000L;

    public interface Listener {
        void onResult(CommandResult result);
        void onTransportState(TransportState state, String detail);
        void onNotice(String notice);
    }

    /**
     * 自然语言任务入口。由 PocketPilotApp 接到现有 TaskController；
     * 管线只负责协议、去重和结果映射，不直接执行任何手机动作。
     */
    public interface TaskDispatcher {
        TaskResult dispatch(String messageId, String text, String source);
    }

    private final Context context;
    private final BleTransport transport;
    private final TargetConfig config;
    private final ResultsStore resultsStore;
    private final ExecutorRegistry executors = new ExecutorRegistry();
    private volatile Thread workerThread;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "pocketpilot-command-pipeline");
        thread.setDaemon(true);
        workerThread = thread;
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<CommandType, Long> lastAcceptedAt = new EnumMap<>(CommandType.class);
    private final Map<String, CommandResult> awaitingMockAck = new ConcurrentHashMap<>();
    private final Map<String, String> earlyMockAcks = new ConcurrentHashMap<>();
    private final Map<String, CommandResult> confirmedResults = new ConcurrentHashMap<>();
    private final TaskMessageGuard taskMessageGuard = new TaskMessageGuard();

    private volatile Listener uiListener;
    private volatile TaskDispatcher taskDispatcher;
    private volatile CommandResult latestResult;
    private volatile TransportState transportState = TransportState.IDLE;
    private volatile String transportDetail = "尚未启动";

    public CommandPipeline(Context context, BleTransport transport, TargetConfig config) {
        this.context = context.getApplicationContext();
        this.transport = transport;
        this.config = config;
        this.resultsStore = new ResultsStore(this.context);
        executors.register(new OpenWeChatExecutor(this.context, config))
                .register(new OpenMapExecutor(this.context, config))
                .register(new BackExecutor(this.context));
    }

    public void start() {
        transport.start(this);
    }

    /** 停止当前传输。切换模拟/真实通道时调用，不会清理已记录的结果。 */
    public void stop() {
        transport.stop();
    }

    /** 由装配点在 TaskController 构造完成后注入；未注入时 TASK_TEXT 一律拒绝。 */
    public void setTaskDispatcher(TaskDispatcher dispatcher) {
        this.taskDispatcher = dispatcher;
    }

    public void setUiListener(Listener listener) {
        this.uiListener = listener;
        CommandResult current = latestResult;
        if (current != null && listener != null) {
            main.post(() -> listener.onResult(current));
        }
        if (listener != null) {
            main.post(() -> listener.onTransportState(transportState, transportDetail));
        }
    }

    public boolean isSimulated() {
        return transport.isSimulated();
    }

    public String transportId() {
        return transport.id();
    }

    public TransportState transportState() {
        return transportState;
    }

    public String transportDetail() {
        return transportDetail;
    }

    public CommandResult latestResult() {
        return latestResult;
    }

    public ResultsStore resultsStore() {
        return resultsStore;
    }

    public TargetConfig targetConfig() {
        return config;
    }

    public boolean injectFakeCommand(CommandType type, String argument) {
        if (!(transport instanceof FakeBleTransport)) {
            return false;
        }
        FakeBleTransport fake = (FakeBleTransport) transport;
        if (type == CommandType.OPEN_MAP) {
            fake.injectCommand(type, argument);
        } else {
            fake.injectCommand(type, "");
        }
        return true;
    }

    public boolean injectFakeRaw(String raw) {
        if (!(transport instanceof FakeBleTransport)) {
            return false;
        }
        ((FakeBleTransport) transport).injectRaw(raw);
        return true;
    }
    /** 仅模拟通道可用：按真实 TASK_TEXT 帧格式注入，仍标记为 SIMULATED。 */
    public boolean injectFakeTaskText(String messageId, String text) {
        if (!(transport instanceof FakeBleTransport)) {
            return false;
        }
        try {
            ((FakeBleTransport) transport).injectFrame(
                    CommandCodec.encodeTaskTextBytes(messageId, text));
            return true;
        } catch (CommandCodec.ProtocolException e) {
            Log.w(TAG, "SIMULATED task text rejected: " + e.code() + " " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onTransportState(TransportState state, String detail) {
        transportState = state;
        transportDetail = detail;
        Listener listener = uiListener;
        if (listener != null) {
            main.post(() -> listener.onTransportState(state, detail));
        }
    }

    @Override
    public void onFrameReceived(byte[] frame) {
        if (frame == null) {
            return;
        }
        byte[] copy = frame.clone();
        // 去重必须用“到达时间”，不能用工作线程的处理时间：单线程队列会串行执行，
        // 若用处理时间去重，连按两下时第二条会被误判为间隔超过窗口。
        long arrivedAtMillis = System.currentTimeMillis();
        worker.execute(() -> processFrame(copy, arrivedAtMillis));
    }

    private void processFrame(byte[] frame, long arrivedAtMillis) {
        PocketCommand command;
        try {
            CommandCodec.Decoded decoded = CommandCodec.decode(frame);
            // TASK_TEXT 必须保留设备端给出的 message_id，RESULT 才能关联回原任务；
            // 固定命令仍走原来的随机 ID 语义，避免改变旧行为。
            command = decoded.type == CommandType.TASK_TEXT
                    ? PocketCommand.createWithId(decoded.messageId, decoded.type,
                            decoded.argument, transport.id())
                    : PocketCommand.create(decoded.type, decoded.argument, transport.id());
        } catch (CommandCodec.ProtocolException e) {
            command = PocketCommand.protocolError(transport.id());
            CommandResult rejected = new CommandResult(command, ExecutionStatus.REJECTED,
                    e.code() + "：" + e.getMessage(), "frame_rejected=" + e.code(), 0L,
                    System.currentTimeMillis());
            publish(rejected, "PROTOCOL_REJECTED");
            return;
        }
        processCommand(command, arrivedAtMillis);
    }

    /**
     * 解码之后的统一处理路径。BLE 帧和多步骤任务的单步都走这里，
     * 因此任务不会获得任何绕过校验、绕过记录的特权。
     *
     * @return 终态结果；调用方不得把 RECEIVED 当成执行成功。
     */
    private CommandResult processCommand(PocketCommand command, long arrivedAtMillis) {
        if (command.type() == CommandType.TASK_TEXT) {
            return processTaskCommand(command, arrivedAtMillis);
        }
        long last = lastAcceptedAt.getOrDefault(command.type(), 0L);
        CommandValidator.Decision decision = CommandValidator.validate(
                command, PocketAccessibilityService.isEnabled(context), last, arrivedAtMillis);
        if (!decision.accepted) {
            ExecutionStatus status = "ACCESSIBILITY_DISABLED".equals(decision.reasonCode)
                    ? ExecutionStatus.PERMISSION_REQUIRED : ExecutionStatus.REJECTED;
            CommandResult rejected = new CommandResult(command, status,
                    decision.reasonDetail, "reason=" + decision.reasonCode, 0L,
                    System.currentTimeMillis());
            publish(rejected, decision.duplicate ? "DUPLICATE_SUPPRESSED" : "VALIDATION_REJECTED");
            return rejected;
        }
        lastAcceptedAt.put(command.type(), arrivedAtMillis);

        CommandResult received = new CommandResult(command, ExecutionStatus.RECEIVED,
                "命令已接收，开始执行", "validated; source=" + command.source(), 0L,
                System.currentTimeMillis());
        publish(received, "RECEIVED");

        CommandExecutor executor = executors.get(command.type());
        if (executor == null) {
            CommandResult missing = new CommandResult(command, ExecutionStatus.FAILED,
                    "没有为 " + command.type().wireName() + " 注册执行器",
                    "executor = null", 0L, System.currentTimeMillis());
            publish(missing, "EXECUTION_FAILED");
            return missing;
        }

        long started = SystemClock.elapsedRealtime();
        CommandResult result;
        try {
            result = executor.execute(command);
        } catch (RuntimeException e) {
            result = new CommandResult(command, ExecutionStatus.FAILED,
                    "执行器异常：" + e.getClass().getSimpleName(),
                    Log.getStackTraceString(e), SystemClock.elapsedRealtime() - started,
                    System.currentTimeMillis());
        }
        publish(result, "EXECUTED");

        if (result.status() == ExecutionStatus.EXECUTED_UNCONFIRMED
                && result.evidence().contains("target_package=" + CommandExtras.MOCK_PACKAGE)) {
            awaitingMockAck.put(command.id(), result);
            String earlyDetail = earlyMockAcks.remove(command.id());
            if (earlyDetail != null) {
                confirmMockReached(command.id(), CommandExtras.MOCK_PACKAGE, earlyDetail);
            }
        }
        return result;
    }

    /**
     * TASK_TEXT 的唯一入口：解码 -> 防御性校验 -> 按消息 ID 去重 -> 交给现有规划器。
     *
     * 这里刻意不查执行器表，也不调用任何手机动作；任务能否执行完全由
     * TaskController/TaskRunner/PlanValidator/ActionPolicy 决定。规划器拒绝
     * 的任务仍然保持拒绝状态。
     */
    private CommandResult processTaskCommand(PocketCommand command, long arrivedAtMillis) {
        CommandValidator.Decision decision = CommandValidator.validate(
                command, PocketAccessibilityService.isEnabled(context), 0L, arrivedAtMillis);
        if (!decision.accepted) {
            ExecutionStatus status = "ACCESSIBILITY_DISABLED".equals(decision.reasonCode)
                    ? ExecutionStatus.PERMISSION_REQUIRED : ExecutionStatus.REJECTED;
            CommandResult rejected = new CommandResult(command, status,
                    decision.reasonDetail, "reason=" + decision.reasonCode, 0L,
                    System.currentTimeMillis());
            publish(rejected, "TASK_TEXT_REJECTED");
            return rejected;
        }

        if (!taskMessageGuard.markIfNew(command.id(), arrivedAtMillis)) {
            CommandResult duplicate = new CommandResult(command, ExecutionStatus.REJECTED,
                    "重复的任务消息 ID，已抑制（不会再次规划或执行）",
                    "reason=DUPLICATE_MESSAGE_ID; message_id=" + command.id(), 0L,
                    System.currentTimeMillis());
            publish(duplicate, "DUPLICATE_MESSAGE_ID");
            return duplicate;
        }

        CommandResult received = new CommandResult(command, ExecutionStatus.RECEIVED,
                "任务文本已接收，交给规划器处理",
                "task_text_received; message_id=" + command.id()
                        + "; text_bytes=" + TaskTextProtocol.utf8Length(command.argument())
                        + "; source=" + command.source(),
                0L, System.currentTimeMillis());
        publish(received, "TASK_TEXT_RECEIVED");

        TaskDispatcher dispatcher = taskDispatcher;
        if (dispatcher == null) {
            CommandResult unavailable = new CommandResult(command, ExecutionStatus.FAILED,
                    "任务调度器尚未装配，文本未进入规划器",
                    "reason=TASK_DISPATCHER_UNAVAILABLE", 0L, System.currentTimeMillis());
            publish(unavailable, "TASK_TEXT_FAILED");
            return unavailable;
        }

        TaskResult task;
        long started = SystemClock.elapsedRealtime();
        try {
            task = dispatcher.dispatch(command.id(), command.argument(), command.source());
        } catch (RuntimeException e) {
            CommandResult failed = new CommandResult(command, ExecutionStatus.FAILED,
                    "任务调度异常：" + e.getClass().getSimpleName(),
                    "reason=TASK_DISPATCH_EXCEPTION; " + Log.getStackTraceString(e),
                    SystemClock.elapsedRealtime() - started, System.currentTimeMillis());
            publish(failed, "TASK_TEXT_FAILED");
            return failed;
        }

        if (task == null) {
            CommandResult failed = new CommandResult(command, ExecutionStatus.FAILED,
                    "规划器没有返回任务结果",
                    "reason=TASK_RESULT_NULL", SystemClock.elapsedRealtime() - started,
                    System.currentTimeMillis());
            publish(failed, "TASK_TEXT_FAILED");
            return failed;
        }

        ExecutionStatus status = mapTaskStatus(task);
        String detail = task.detail().isEmpty() ? task.status().name() : task.detail();
        String evidence = "task_status=" + task.status()
                + "; reason=" + task.reasonCode()
                + "; planner=" + task.plannerId()
                + "; addressSource=" + task.addressSource()
                + "; message_id=" + command.id()
                + "; text_bytes=" + TaskTextProtocol.utf8Length(command.argument());
        CommandResult mapped = new CommandResult(command, status, detail, evidence,
                SystemClock.elapsedRealtime() - started, System.currentTimeMillis());
        publish(mapped, "TASK_TEXT_COMPLETED");
        return mapped;
    }

    /** 任务级四态到设备可识别的命令结果状态。BLOCKED 不伪装成 FAILED。 */
    private static ExecutionStatus mapTaskStatus(TaskResult task) {
        switch (task.status()) {
            case COMPLETED_CONFIRMED:
                return ExecutionStatus.CONFIRMED;
            case COMPLETED_UNCONFIRMED:
                return ExecutionStatus.EXECUTED_UNCONFIRMED;
            case BLOCKED:
                return "PERMISSION_REQUIRED".equals(task.reasonCode())
                        ? ExecutionStatus.PERMISSION_REQUIRED : ExecutionStatus.REJECTED;
            case FAILED:
            default:
                return ExecutionStatus.FAILED;
        }
    }
    /**
     * 供多步骤任务调用：在管线工作线程上执行一步，并把终态结果同步返回给任务。
     *
     * 超时、异常都不会抛出，而是转成 FAILED 结果——任务层必须能如实记录失败，
     * 不能因为一步炸掉整个任务后没有任何证据。
     */
    public CommandResult runTaskStep(CommandType type, String argument, String source) {
        String safeSource = source == null || source.isEmpty() ? "TASK" : source;
        if (type == null) {
            PocketCommand bad = PocketCommand.protocolError(safeSource);
            CommandResult rejected = new CommandResult(bad, ExecutionStatus.REJECTED,
                    "任务步骤缺少命令类型", "reason=UNKNOWN_ACTION", 0L, System.currentTimeMillis());
            publish(rejected, "VALIDATION_REJECTED");
            return rejected;
        }

        PocketCommand command = PocketCommand.create(type, argument == null ? "" : argument, safeSource);
        long arrivedAtMillis = System.currentTimeMillis();

        // 已经在工作线程上时直接执行，否则自己等自己会死锁。
        if (Thread.currentThread() == workerThread) {
            return awaitMockAck(processCommand(command, arrivedAtMillis));
        }

        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        worker.execute(() -> {
            try {
                future.complete(processCommand(command, arrivedAtMillis));
            } catch (RuntimeException e) {
                future.complete(new CommandResult(command, ExecutionStatus.FAILED,
                        "管线处理异常：" + e.getClass().getSimpleName(),
                        Log.getStackTraceString(e), 0L, System.currentTimeMillis()));
            }
        });

        try {
            CommandResult result = future.get(TASK_STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return result == null ? timeoutResult(command, "管线未返回结果") : awaitMockAck(result);
        } catch (TimeoutException e) {
            future.cancel(true);
            return timeoutResult(command, "等待超过 " + TASK_STEP_TIMEOUT_MS + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return timeoutResult(command, "等待被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new CommandResult(command, ExecutionStatus.FAILED,
                    "管线执行异常：" + cause.getClass().getSimpleName(),
                    String.valueOf(cause.getMessage()), 0L, System.currentTimeMillis());
        }
    }

    private CommandResult timeoutResult(PocketCommand command, String why) {
        CommandResult failed = new CommandResult(command, ExecutionStatus.FAILED,
                "任务步骤未取得执行结果：" + why,
                "reason=TASK_STEP_TIMEOUT", 0L, System.currentTimeMillis());
        publish(failed, "EXECUTION_FAILED");
        return failed;
    }

    /**
     * EXECUTED_UNCONFIRMED 时给 Mock 回执一个短窗口。等到了就升级为 CONFIRMED，
     * 等不到就原样返回未确认——不会把“没等到”说成“已确认”。
     */
    private CommandResult awaitMockAck(CommandResult result) {
        if (result == null || result.status() != ExecutionStatus.EXECUTED_UNCONFIRMED) {
            return result;
        }
        String id = result.command().id();
        long deadline = System.currentTimeMillis() + MOCK_ACK_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            CommandResult confirmed = confirmedResults.get(id);
            if (confirmed != null) {
                return confirmed;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return result;
    }

    /** 把提示同时写进 logcat 和 UI，任务层不需要自己持有界面引用。 */
    public void publishNotice(String notice) {
        if (notice == null || notice.isEmpty()) {
            return;
        }
        Log.i(TAG, notice);
        Listener listener = uiListener;
        if (listener != null) {
            main.post(() -> listener.onNotice(notice));
        }
    }

    public void confirmMockReached(String commandId, String targetPackage, String detail) {
        if (commandId == null || !CommandExtras.MOCK_PACKAGE.equals(targetPackage)) {
            return;
        }
        String safeDetail = detail == null ? "Mock 替代目标已到达" : detail;
        CommandResult pending = awaitingMockAck.remove(commandId);
        if (pending == null) {
            // onCreate 的回执可能早于 startActivity() 返回，短暂保存以消除竞态。
            earlyMockAcks.put(commandId, safeDetail);
            return;
        }
        CommandResult confirmed = pending.confirm(
                "Mock 替代目标已回执到达；这不是真实微信/地图的加载证明",
                "mock_ack=" + safeDetail + "; target_package=" + targetPackage);
        confirmedResults.put(commandId, confirmed);
        publish(confirmed, "MOCK_ACK_CONFIRMED");
    }

    private void publish(CommandResult result, String event) {
        resultsStore.append(result, event);
        latestResult = result;
        if (!transport.isSimulated()) {
            sendResultToDevice(result);
        }
        Listener listener = uiListener;
        if (listener != null) {
            main.post(() -> listener.onResult(result));
        }
        Log.i(TAG, event + " " + result.command().id() + " " + result.status()
                + " " + result.detail());
    }

    /** 状态帧编码集中在 {@link StatusFrameEncoder}，保证含换行 ≤ 512 字节。 */
    private void sendResultToDevice(CommandResult result) {
        transport.send(StatusFrameEncoder.encode(result).getBytes(StandardCharsets.UTF_8));
    }
}

