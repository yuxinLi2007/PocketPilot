package com.pocketpilot.app.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务消息 ID 去重。与固定命令的 300 ms 类型去重不同，任务文本必须按
 * 协议消息 ID 去重：两条不同任务可能在很短时间内先后到达，不能被误杀。
 *
 * 纯 Java、有界容量、带 TTL，便于协议自测直接注入时间戳。
 */
public final class TaskMessageGuard {
    public static final long DEFAULT_TTL_MS = 60_000L;
    public static final int DEFAULT_CAPACITY = 64;

    private final long ttlMillis;
    private final Map<String, Long> seen = new LinkedHashMap<String, Long>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > capacity;
        }
    };
    private final int capacity;

    public TaskMessageGuard() {
        this(DEFAULT_TTL_MS, DEFAULT_CAPACITY);
    }

    public TaskMessageGuard(long ttlMillis, int capacity) {
        this.ttlMillis = ttlMillis <= 0 ? DEFAULT_TTL_MS : ttlMillis;
        this.capacity = capacity <= 0 ? DEFAULT_CAPACITY : capacity;
    }

    /** @return true 表示这是首次见到该 ID；false 表示 TTL 内重复。 */
    public synchronized boolean markIfNew(String messageId, long nowMillis) {
        if (messageId == null || messageId.isEmpty()) {
            return false;
        }
        prune(nowMillis);
        Long previous = seen.get(messageId);
        if (previous != null && nowMillis - previous < ttlMillis) {
            // 保留首次时间，重复包不会无限延长去重窗口。
            return false;
        }
        seen.put(messageId, nowMillis);
        return true;
    }

    private void prune(long nowMillis) {
        seen.entrySet().removeIf(entry -> nowMillis - entry.getValue() >= ttlMillis);
    }

    public synchronized int size() {
        return seen.size();
    }
}
