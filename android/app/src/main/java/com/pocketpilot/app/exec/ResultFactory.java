package com.pocketpilot.app.exec;

import android.os.SystemClock;

import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.ExecutionStatus;
import com.pocketpilot.app.core.PocketCommand;

/** 统一构造结果，保证耗时与时间戳口径一致。 */
final class ResultFactory {
    private ResultFactory() {
    }

    static CommandResult of(PocketCommand command, ExecutionStatus status, String detail, String evidence, long startedElapsed) {
        long elapsed = SystemClock.elapsedRealtime() - startedElapsed;
        return new CommandResult(command, status, detail, evidence, elapsed, System.currentTimeMillis());
    }
}
