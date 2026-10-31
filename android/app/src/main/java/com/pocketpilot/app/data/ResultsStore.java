package com.pocketpilot.app.data;

import android.content.Context;

import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.PocketCommand;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 追加写入 JSONL。每条命令都有机器可读的证据记录，便于在模拟器中
 * 用 `adb shell run-as com.pocketpilot.app cat files/results.jsonl` 复核。
 */
public final class ResultsStore {
    private final File file;

    public ResultsStore(Context context) {
        this.file = new File(context.getFilesDir(), "results.jsonl");
    }

    public File file() {
        return file;
    }

    public synchronized void append(CommandResult result, String event) {
        PocketCommand command = result.command();
        String type = command.type() == null ? "PROTOCOL_ERROR" : command.type().wireName();
        StringBuilder json = new StringBuilder(512);
        json.append('{')
                .append("\"event\":\"").append(escape(event)).append("\",")
                .append("\"command_id\":\"").append(escape(command.id())).append("\",")
                .append("\"command\":\"").append(escape(type)).append("\",")
                .append("\"argument\":\"").append(escape(command.argument())).append("\",")
                .append("\"source\":\"").append(escape(command.source())).append("\",")
                .append("\"status\":\"").append(result.status().name()).append("\",")
                .append("\"status_label\":\"").append(escape(result.status().label())).append("\",")
                .append("\"detail\":\"").append(escape(result.detail())).append("\",")
                .append("\"evidence\":\"").append(escape(result.evidence())).append("\",")
                .append("\"elapsed_ms\":").append(result.elapsedMillis()).append(',')
                .append("\"finished_at_ms\":").append(result.finishedAtMillis())
                .append("}\n");
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 证据写入失败不能反过来伪造成命令执行成功；界面仍会显示失败状态。
        }
    }

    /**
     * 追加一行已经序列化好的 JSON。任务级结果（TaskResult.toJson）用它落盘，
     * 与命令级记录写在同一个 results.jsonl 里，便于按时间顺序复核整条链路。
     *
     * 调用方负责保证 jsonLine 是合法的单行 JSON；本方法只做换行归一化，不改写内容。
     */
    public synchronized void appendRaw(String jsonLine) {
        if (jsonLine == null || jsonLine.isEmpty()) {
            return;
        }
        String line = jsonLine.endsWith("\n") ? jsonLine : jsonLine + "\n";
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 同 append()：证据写入失败不能反过来伪造成执行成功。
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
