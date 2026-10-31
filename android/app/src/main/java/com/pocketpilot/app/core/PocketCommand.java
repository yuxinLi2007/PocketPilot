package com.pocketpilot.app.core;

import java.util.UUID;

/** 一条已通过解码的命令。argument 对 OPEN_MAP 是地址，对 TASK_TEXT 是任务原文。 */
public final class PocketCommand {
    private final String id;
    private final CommandType type;
    private final String argument;
    private final String source;
    private final long receivedAtMillis;

    private PocketCommand(String id, CommandType type, String argument, String source, long receivedAtMillis) {
        this.id = id;
        this.type = type;
        this.argument = argument == null ? "" : argument;
        this.source = source == null ? "unknown" : source;
        this.receivedAtMillis = receivedAtMillis;
    }

    public static PocketCommand create(CommandType type, String argument, String source) {
        String id = type.wireName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return new PocketCommand(id, type, argument, source, System.currentTimeMillis());
    }

    /**
     * 使用协议帧里的消息 ID 创建命令。TASK_TEXT 必须走这里，否则设备端无法把
     * RESULT 帧关联回原始任务。
     */
    public static PocketCommand createWithId(String id, CommandType type, String argument, String source) {
        String safeId = id == null || id.isEmpty()
                ? type.wireName() + "-" + UUID.randomUUID().toString().substring(0, 8)
                : id;
        return new PocketCommand(safeId, type, argument, source, System.currentTimeMillis());
    }

    /** 协议错误还没有合法命令类型，但仍需要留下可复核的失败记录。 */
    public static PocketCommand protocolError(String source) {
        return new PocketCommand("PROTOCOL-" + UUID.randomUUID().toString().substring(0, 8),
                null, "", source, System.currentTimeMillis());
    }

    public String id() { return id; }
    public CommandType type() { return type; }
    public String argument() { return argument; }
    public String source() { return source; }
    public long receivedAtMillis() { return receivedAtMillis; }
}
