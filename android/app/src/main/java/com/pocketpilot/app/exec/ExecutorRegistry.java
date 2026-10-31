package com.pocketpilot.app.exec;

import com.pocketpilot.app.core.CommandType;

import java.util.EnumMap;
import java.util.Map;

public final class ExecutorRegistry {
    private final Map<CommandType, CommandExecutor> executors = new EnumMap<>(CommandType.class);

    public ExecutorRegistry register(CommandExecutor executor) {
        executors.put(executor.type(), executor);
        return this;
    }

    public CommandExecutor get(CommandType type) {
        return executors.get(type);
    }
}
