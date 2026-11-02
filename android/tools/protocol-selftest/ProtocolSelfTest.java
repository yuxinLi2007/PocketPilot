package com.pocketpilot.app.protocoltest;

import com.pocketpilot.app.core.CommandCodec;
import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.CommandValidator;
import com.pocketpilot.app.core.ExecutionStatus;
import com.pocketpilot.app.core.GattWriteChunker;
import com.pocketpilot.app.core.PocketCommand;
import com.pocketpilot.app.core.PocketProtocol;
import com.pocketpilot.app.core.StatusFrameEncoder;
import com.pocketpilot.app.core.TaskMessageGuard;
import com.pocketpilot.app.core.TaskTextProtocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 JDK 协议自测：不依赖 Android、JUnit 或网络。
 *
 * 运行方式见 android/tools/protocol-selftest/run-selftest.ps1。
 * 覆盖：帧编解码、分片重组、校验规则、去重窗口、512 字节结果帧边界。
 */
public final class ProtocolSelfTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int passed = 0;

    public static void main(String[] args) {
        protocolConstantTests();
        decodeTests();
        fixedCommandCompatibilityTests();
        taskTextTests();
        assemblerTests();
        taskAssemblerTests();
        taskMessageGuardTests();
        validatorTests();
        taskValidatorTests();
        statusFrameTests();
        gattWriteChunkerTests();

        System.out.println();
        System.out.println("PASS " + passed + "  FAIL " + FAILURES.size());
        for (String failure : FAILURES) {
            System.out.println("  FAILED: " + failure);
        }
        if (!FAILURES.isEmpty()) {
            System.exit(1);
        }
        System.out.println("PROTOCOL_SELFTEST_OK");
    }

    // ----------------------------------------------------------- protocol ids

    private static void protocolConstantTests() {
        // 这三个数组必须与 firmware/pocketpilot_ble/main/pocketpilot_ble_main.c
        // 中的 16 字节小端数组逐字节一致；任何一侧改动都会在这里失败。
        int[] service = {0x01, 0x00, 0x00, 0x74, 0x6f, 0x6c, 0x69, 0x70,
                         0x2d, 0x74, 0x65, 0x6b, 0x63, 0x6f, 0x70, 0x6f};
        int[] rx = {0x02, 0x00, 0x00, 0x74, 0x6f, 0x6c, 0x69, 0x70,
                    0x2d, 0x74, 0x65, 0x6b, 0x63, 0x6f, 0x70, 0x6f};
        int[] tx = {0x03, 0x00, 0x00, 0x74, 0x6f, 0x6c, 0x69, 0x70,
                    0x2d, 0x74, 0x65, 0x6b, 0x63, 0x6f, 0x70, 0x6f};
        checkUuid("service", PocketProtocol.SERVICE_UUID, service);
        checkUuid("rx", PocketProtocol.RX_CHAR_UUID, rx);
        checkUuid("tx", PocketProtocol.TX_CHAR_UUID, tx);

        if (PocketProtocol.SERVICE_UUID.equalsIgnoreCase(PocketProtocol.RX_CHAR_UUID)
                || PocketProtocol.RX_CHAR_UUID.equalsIgnoreCase(PocketProtocol.TX_CHAR_UUID)) {
            fail("protocol UUIDs must be distinct");
        } else {
            pass("protocol UUIDs are distinct");
        }
    }

    private static void checkUuid(String name, String value, int[] firmwareLe) {
        if (value == null || value.length() != 36) {
            fail(name + " uuid length=" + (value == null ? -1 : value.length()) + " (expect 36)");
            return;
        }
        java.util.UUID parsed;
        try {
            parsed = java.util.UUID.fromString(value);
        } catch (RuntimeException e) {
            fail(name + " uuid does not parse: " + e.getMessage());
            return;
        }
        // java.util.UUID 输出的是大端字节序，BLE 空中传输是 128-bit 小端。
        String hex = value.replace("-", "");
        byte[] be = new byte[16];
        for (int i = 0; i < 16; i++) {
            be[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        boolean match = true;
        for (int i = 0; i < 16; i++) {
            if ((be[15 - i] & 0xFF) != firmwareLe[i]) {
                match = false;
                break;
            }
        }
        if (match && parsed.toString().equalsIgnoreCase(value)) {
            pass(name + " uuid matches firmware LE bytes");
        } else {
            fail(name + " uuid/firmware byte mismatch: " + parsed);
        }
    }

    // ---------------------------------------------------------------- decode

    private static void decodeTests() {
        decodeOk("wechat", "PP1|COMMAND_OPEN_WECHAT\n", CommandType.OPEN_WECHAT, "");
        decodeOk("map", "PP1|COMMAND_OPEN_MAP|北京市海淀区中关村大街1号\n",
                CommandType.OPEN_MAP, "北京市海淀区中关村大街1号");
        decodeOk("back", "PP1|COMMAND_BACK\n", CommandType.BACK, "");
        decodeOk("no trailing newline", "PP1|COMMAND_BACK", CommandType.BACK, "");

        decodeFails("garbage", "garbage", "MALFORMED_FRAME");
        decodeFails("empty", "   ", "EMPTY_FRAME");
        decodeFails("version", "PP2|COMMAND_BACK\n", "UNSUPPORTED_VERSION");
        decodeFails("unknown command", "PP1|COMMAND_NOPE\n", "UNKNOWN_COMMAND");
        decodeFails("oversize", longFrame(), "FRAME_TOO_LONG");

        try {
            CommandCodec.encode(CommandType.OPEN_MAP, "X");
            pass("encode map produces PP1 frame");
        } catch (RuntimeException e) {
            fail("encode map threw " + e);
        }
    }

    // ------------------------------------------------- fixed command compat

    private static void fixedCommandCompatibilityTests() {
        exactEncode("wechat", CommandType.OPEN_WECHAT, "", "PP1|COMMAND_OPEN_WECHAT\n");
        exactEncode("map", CommandType.OPEN_MAP, "北京市海淀区中关村大街1号",
                "PP1|COMMAND_OPEN_MAP|北京市海淀区中关村大街1号\n");
        exactEncode("back", CommandType.BACK, "", "PP1|COMMAND_BACK\n");

        try {
            CommandCodec.encodeText(CommandType.TASK_TEXT, "x");
            fail("fixed-command encoder accepted TASK_TEXT");
        } catch (IllegalArgumentException expected) {
            pass("fixed-command encoder rejects TASK_TEXT (must use encodeTaskText)");
        }
    }

    private static void exactEncode(String name, CommandType type, String argument, String expected) {
        String actual = CommandCodec.encodeText(type, argument);
        if (expected.equals(actual)) {
            pass("fixed command wire format unchanged: " + name);
        } else {
            fail(name + " wire changed: " + actual.replace("\n", "\\n"));
        }
    }

    // ------------------------------------------------------ task text (7A)

    private static void taskTextTests() {
        // 正常英文
        try {
            String text = "open the map";
            byte[] frame = CommandCodec.encodeTaskTextBytes("task-en-001", text);
            CommandCodec.Decoded decoded = CommandCodec.decode(frame);
            if (decoded.type == CommandType.TASK_TEXT
                    && text.equals(decoded.argument)
                    && "task-en-001".equals(decoded.messageId)) {
                pass("task text english round-trip");
            } else {
                fail("english round-trip: type=" + decoded.type + " arg=" + decoded.argument
                        + " id=" + decoded.messageId);
            }
            if (frame[frame.length - 1] == '\n' && frame.length <= CommandCodec.MAX_FRAME_BYTES) {
                pass("task text frame is newline-terminated within 512 bytes");
            } else {
                fail("task frame terminator/length: bytes=" + frame.length);
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("english round-trip threw " + e.code());
        }

        // 正常中文：按 UTF-8 字节而不是 Java 字符数计算
        try {
            String text = "把刚收到的地址打开地图";
            String wire = CommandCodec.encodeTaskText("task-zh-001", text);
            int declared = TaskTextProtocol.utf8Length(text);
            if (wire.startsWith("PP1|COMMAND_TASK_TEXT|task-zh-001|" + declared + "|")) {
                pass("task text chinese declares UTF-8 byte length " + declared);
            } else {
                fail("chinese header: " + wire);
            }
            CommandCodec.Decoded decoded = CommandCodec.decode(wire.getBytes(StandardCharsets.UTF_8));
            if (text.equals(decoded.argument) && "task-zh-001".equals(decoded.messageId)) {
                pass("task text chinese round-trip");
            } else {
                fail("chinese round-trip: " + decoded.argument);
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("chinese round-trip threw " + e.code());
        }

        // 文本在最后，允许包含 '|'
        try {
            String text = "打开地图|不要截断|第二段";
            CommandCodec.Decoded decoded = CommandCodec.decode(
                    CommandCodec.encodeTaskTextBytes("task-pipe-1", text));
            if (text.equals(decoded.argument)) {
                pass("task text keeps embedded '|' characters");
            } else {
                fail("pipe text lost: " + decoded.argument);
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("pipe text threw " + e.code());
        }

        // 384 字节边界（ASCII 与 UTF-8 中文分别覆盖）
        try {
            String text = repeat("a", TaskTextProtocol.MAX_TEXT_BYTES);
            String wire = CommandCodec.encodeTaskText("task-limit-ascii", text);
            CommandCodec.Decoded decoded = CommandCodec.decode(wire.getBytes(StandardCharsets.UTF_8));
            if (text.equals(decoded.argument)
                    && utf8(wire).length <= CommandCodec.MAX_FRAME_BYTES) {
                pass("task text 384 ASCII bytes accepted (frame " + utf8(wire).length + " bytes)");
            } else {
                fail("384 ascii: frame=" + utf8(wire).length);
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("384 ascii threw " + e.code());
        }

        try {
            String text = repeat("地", 128); // 128 * 3 = 384 UTF-8 bytes
            String wire = CommandCodec.encodeTaskText("task-limit-cjk", text);
            CommandCodec.Decoded decoded = CommandCodec.decode(wire.getBytes(StandardCharsets.UTF_8));
            if (TaskTextProtocol.utf8Length(text) == 384 && text.equals(decoded.argument)) {
                pass("task text 128 CJK chars = 384 UTF-8 bytes accepted");
            } else {
                fail("384 CJK mismatch: bytes=" + TaskTextProtocol.utf8Length(text));
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("384 CJK threw " + e.code());
        }

        expectEncodeFailure("385 ASCII bytes", "task-limit-over", repeat("a", 385),
                "TEXT_TOO_LONG");
        expectEncodeFailure("empty text", "task-empty", "", "EMPTY_TASK_TEXT");
        expectEncodeFailure("whitespace-only text", "task-blank", " \t　 ", "EMPTY_TASK_TEXT");
        expectEncodeFailure("text with line break", "task-lf", "a\nb", "MALFORMED_FRAME");
        expectEncodeFailure("message id with space", "bad id", "hello", "INVALID_MESSAGE_ID");
        expectEncodeFailure("message id with 65 chars", repeat("a", 65), "hello",
                "INVALID_MESSAGE_ID");

        // 64 字符 ID 是上限，必须仍然可编码/解码
        try {
            String id = repeat("A", TaskTextProtocol.MAX_MESSAGE_ID_CHARS);
            CommandCodec.Decoded decoded = CommandCodec.decode(
                    CommandCodec.encodeTaskTextBytes(id, "hello"));
            if (id.equals(decoded.messageId)) {
                pass("message id 64 ASCII chars accepted");
            } else {
                fail("64-char id mismatch");
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("64-char id threw " + e.code());
        }

        expectDecodeFailure("empty text field", "PP1|COMMAND_TASK_TEXT|id-1|0|", "EMPTY_TASK_TEXT");
        expectDecodeFailure("whitespace text field", "PP1|COMMAND_TASK_TEXT|id-1|1| ", "EMPTY_TASK_TEXT");
        expectDecodeFailure("non-numeric length", "PP1|COMMAND_TASK_TEXT|id-1|abc|hello",
                "INVALID_LENGTH");
        expectDecodeFailure("negative length", "PP1|COMMAND_TASK_TEXT|id-1|-1|hello",
                "INVALID_LENGTH");
        expectDecodeFailure("declared/actual mismatch", "PP1|COMMAND_TASK_TEXT|id-1|3|hello",
                "LENGTH_MISMATCH");
        expectDecodeFailure("declared length above cap", "PP1|COMMAND_TASK_TEXT|id-1|999999|hello",
                "TEXT_TOO_LONG");
        expectDecodeFailure("truncated after length", "PP1|COMMAND_TASK_TEXT|id-1|5",
                "MALFORMED_FRAME");
        expectDecodeFailure("truncated before length", "PP1|COMMAND_TASK_TEXT|id-1",
                "MALFORMED_FRAME");
        expectDecodeFailure("missing message id", "PP1|COMMAND_TASK_TEXT||5|hello",
                "INVALID_MESSAGE_ID");
        expectDecodeFailure("message id with slash", "PP1|COMMAND_TASK_TEXT|id/1|5|hello",
                "INVALID_MESSAGE_ID");
        expectDecodeFailure("wrong version", "PP2|COMMAND_TASK_TEXT|id-1|5|hello",
                "UNSUPPORTED_VERSION");
        expectDecodeFailure("text with embedded CR", "PP1|COMMAND_TASK_TEXT|id-1|6|hel\rlo",
                "MALFORMED_FRAME");
        expectDecodeFailure("task frame over 512 bytes",
                "PP1|COMMAND_TASK_TEXT|id-1|384|" + repeat("a", 500) + "\n",
                "FRAME_TOO_LONG");
        expectDecodeFailure("String decode over 512 bytes", longFrame(), "FRAME_TOO_LONG");

        // 非法 UTF-8：0xC3 0x28 不是合法的 UTF-8 序列
        byte[] prefix = "PP1|COMMAND_TASK_TEXT|id-1|2|".getBytes(StandardCharsets.UTF_8);
        byte[] illegal = new byte[prefix.length + 3];
        System.arraycopy(prefix, 0, illegal, 0, prefix.length);
        illegal[prefix.length] = (byte) 0xC3;
        illegal[prefix.length + 1] = (byte) 0x28;
        illegal[prefix.length + 2] = (byte) '\n';
        expectDecodeFailure("illegal UTF-8 bytes", illegal, "INVALID_UTF8");
    }

    private static void expectEncodeFailure(String name, String id, String text, String code) {
        try {
            CommandCodec.encodeTaskText(id, text);
            fail("encode " + name + ": accepted but expected " + code);
        } catch (CommandCodec.ProtocolException e) {
            if (code.equals(e.code())) {
                pass("encode rejects " + name + " (" + code + ")");
            } else {
                fail("encode " + name + ": code=" + e.code() + " expected=" + code);
            }
        }
    }

    private static void expectDecodeFailure(String name, String wire, String code) {
        expectDecodeFailure(name, wire.getBytes(StandardCharsets.UTF_8), code);
    }

    private static void expectDecodeFailure(String name, byte[] wire, String code) {
        try {
            CommandCodec.decode(wire);
            fail("decode " + name + ": accepted but expected " + code);
        } catch (CommandCodec.ProtocolException e) {
            if (code.equals(e.code())) {
                pass("decode rejects " + name + " (" + code + ")");
            } else {
                fail("decode " + name + ": code=" + e.code() + " expected=" + code);
            }
        }
    }

    private static void decodeOk(String name, String wire, CommandType expected, String expectedArg) {
        try {
            CommandCodec.Decoded decoded = CommandCodec.decode(
                    wire.getBytes(StandardCharsets.UTF_8));
            if (decoded.type != expected) {
                fail(name + ": type=" + decoded.type + " expected=" + expected);
            } else if (!expectedArg.equals(decoded.argument)) {
                fail(name + ": arg='" + decoded.argument + "' expected='" + expectedArg + "'");
            } else {
                pass("decode " + name);
            }
        } catch (CommandCodec.ProtocolException e) {
            fail(name + ": unexpected " + e.code());
        }
    }

    private static void decodeFails(String name, String wire, String expectedCode) {
        try {
            CommandCodec.decode(wire.getBytes(StandardCharsets.UTF_8));
            fail(name + ": decoded but expected " + expectedCode);
        } catch (CommandCodec.ProtocolException e) {
            if (expectedCode.equals(e.code())) {
                pass("reject " + name + " (" + expectedCode + ")");
            } else {
                fail(name + ": code=" + e.code() + " expected=" + expectedCode);
            }
        }
    }

    private static String longFrame() {
        StringBuilder sb = new StringBuilder("PP1|COMMAND_OPEN_MAP|");
        while (sb.length() < CommandCodec.MAX_FRAME_BYTES + 40) {
            sb.append('a');
        }
        return sb.append('\n').toString();
    }

    // ------------------------------------------------------------- assembler

    private static void assemblerTests() {
        CommandCodec.FrameAssembler assembler = new CommandCodec.FrameAssembler();
        byte[] whole = "PP1|COMMAND_OPEN_MAP|中关村大街\nPP1|COMMAND_BACK\n"
                .getBytes(StandardCharsets.UTF_8);
        List<String> frames = new ArrayList<>();
        try {
            // 逐字节喂入，必然在中文的多字节字符中间切断。
            for (byte b : whole) {
                byte[] one = new byte[]{b};
                frames.addAll(assembler.append(one));
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("assembler byte-by-byte raised " + e.code());
            return;
        }
        if (frames.size() != 2) {
            fail("assembler: frames=" + frames.size() + " expected=2");
        } else if (!frames.get(0).contains("中关村大街") || !frames.get(1).contains("COMMAND_BACK")) {
            fail("assembler: content mismatch " + frames);
        } else {
            pass("assembler reassembles split CJK frames");
        }

        CommandCodec.FrameAssembler overflowing = new CommandCodec.FrameAssembler(8);
        try {
            overflowing.append("123456789".getBytes(StandardCharsets.UTF_8));
            fail("assembler overflow not detected");
        } catch (CommandCodec.ProtocolException e) {
            if ("FRAME_TOO_LONG".equals(e.code())) {
                pass("assembler rejects overflow");
            } else {
                fail("assembler overflow code=" + e.code());
            }
        }
    }

    // ------------------------------------------------------------- validator

    private static void validatorTests() {
        PocketCommand wechat = PocketCommand.create(CommandType.OPEN_WECHAT, "", "selftest");
        PocketCommand mapNoArg = PocketCommand.create(CommandType.OPEN_MAP, "  ", "selftest");
        PocketCommand mapTooLong = PocketCommand.create(CommandType.OPEN_MAP, repeat("地", 121), "selftest");
        PocketCommand back = PocketCommand.create(CommandType.BACK, "", "selftest");

        expectAccept("wechat accepted", CommandValidator.validate(wechat, false, 0L, 1_000L));
        expectReason("map missing argument", CommandValidator.validate(mapNoArg, false, 0L, 1_000L),
                "MISSING_ARGUMENT");
        expectReason("map argument too long", CommandValidator.validate(mapTooLong, false, 0L, 1_000L),
                "ARGUMENT_TOO_LONG");
        expectReason("back without accessibility",
                CommandValidator.validate(back, false, 0L, 1_000L), "ACCESSIBILITY_DISABLED");
        expectAccept("back with accessibility",
                CommandValidator.validate(back, true, 0L, 1_000L));

        expectDuplicate("duplicate inside 300 ms",
                CommandValidator.validate(back, true, 1_000L, 1_299L));
        expectAccept("same command after 300 ms",
                CommandValidator.validate(back, true, 1_000L, 1_300L));
        // 去重表按命令类型分开维护（见 CommandPipeline.lastAcceptedAt），
        // 其它类型没有记录时传入 0，不能被误判为重复。
        expectAccept("no previous timestamp for this type",
                CommandValidator.validate(wechat, false, 0L, 1_050L));
    }

    private static void expectAccept(String name, CommandValidator.Decision decision) {
        if (decision.accepted && !decision.duplicate) {
            pass(name);
        } else {
            fail(name + ": accepted=" + decision.accepted + " reason=" + decision.reasonCode);
        }
    }

    private static void expectReason(String name, CommandValidator.Decision decision, String code) {
        if (!decision.accepted && !decision.duplicate && code.equals(decision.reasonCode)) {
            pass(name);
        } else {
            fail(name + ": accepted=" + decision.accepted + " code=" + decision.reasonCode
                    + " expected=" + code);
        }
    }

    private static void expectDuplicate(String name, CommandValidator.Decision decision) {
        if (!decision.accepted && decision.duplicate) {
            pass(name);
        } else {
            fail(name + ": accepted=" + decision.accepted + " duplicate=" + decision.duplicate);
        }
    }

    // ---------------------------------------------------------- status frame

    private static void statusFrameTests() {
        PocketCommand cmd = PocketCommand.create(CommandType.OPEN_MAP, "addr", "selftest");
        CommandResult shortResult = new CommandResult(cmd, ExecutionStatus.NOT_INSTALLED,
                "目标应用未安装", "pm=null", 5L, 1L);
        String shortFrame = StatusFrameEncoder.encode(shortResult);
        if (shortFrame.equals("PP1|RESULT|" + cmd.id() + "|NOT_INSTALLED|目标应用未安装\n")) {
            pass("status frame format");
        } else {
            fail("status frame mismatch: " + shortFrame);
        }

        String sanitized = StatusFrameEncoder.encode(
                new CommandResult(cmd, ExecutionStatus.FAILED, "a|b\nc\rd", "x", 1L, 1L));
        if (sanitized.endsWith("\n") && countPipes(sanitized) == 4 && !sanitized.contains("a|b")) {
            pass("status frame sanitizes '|' and newlines");
        } else {
            fail("sanitize failed: " + sanitized.replace("\n", "\\n"));
        }

        // 计算精确预算：512 - 1(换行) - prefix
        String prefix = "PP1|RESULT|" + cmd.id() + "|EXECUTED_UNCONFIRMED|";
        int budget = CommandCodec.MAX_FRAME_BYTES - 1 - utf8(prefix).length;
        String exact = repeat("a", budget);
        String exactFrame = StatusFrameEncoder.encode(
                new CommandResult(cmd, ExecutionStatus.EXECUTED_UNCONFIRMED, exact, "x", 1L, 1L));
        if (utf8(exactFrame).length == CommandCodec.MAX_FRAME_BYTES && !exactFrame.contains("…")) {
            pass("status frame fills exactly 512 bytes without ellipsis");
        } else {
            fail("exact boundary: bytes=" + utf8(exactFrame).length
                    + " ellipsis=" + exactFrame.contains("…"));
        }

        String overFrame = StatusFrameEncoder.encode(
                new CommandResult(cmd, ExecutionStatus.EXECUTED_UNCONFIRMED, exact + "a", "x", 1L, 1L));
        if (utf8(overFrame).length <= CommandCodec.MAX_FRAME_BYTES && overFrame.endsWith("…\n")) {
            pass("status frame truncates one-byte overflow with ellipsis");
        } else {
            fail("overflow: bytes=" + utf8(overFrame).length + " tail="
                    + overFrame.substring(Math.max(0, overFrame.length() - 4)));
        }

        // 中文/emoji 混排的极端 detail：必须 ≤512 且不能出现半个字符。
        String messy = repeat("地址🌏", 300);
        String messyFrame = StatusFrameEncoder.encode(
                new CommandResult(cmd, ExecutionStatus.EXECUTED_UNCONFIRMED, messy, "x", 1L, 1L));
        int messyBytes = utf8(messyFrame).length;
        boolean roundTrips = new String(utf8(messyFrame), StandardCharsets.UTF_8).equals(messyFrame);
        if (messyBytes <= CommandCodec.MAX_FRAME_BYTES && messyFrame.endsWith("…\n") && roundTrips) {
            pass("status frame truncates CJK/emoji at char boundary (" + messyBytes + " bytes)");
        } else {
            fail("messy truncation: bytes=" + messyBytes + " roundTrip=" + roundTrips);
        }
    }

    // ------------------------------------------------------ GATT write chunker

    private static void gattWriteChunkerTests() {
        expectInt("chunker MTU 23 -> 20-byte payload", 20,
                GattWriteChunker.payloadBytesForMtu(23));
        expectInt("chunker MTU 185 -> 182-byte payload", 182,
                GattWriteChunker.payloadBytesForMtu(185));
        expectInt("chunker MTU 517 clamps to 512", 512,
                GattWriteChunker.payloadBytesForMtu(517));
        expectInt("chunker MTU 0 falls back to 20", 20,
                GattWriteChunker.payloadBytesForMtu(0));

        PocketCommand cmd = PocketCommand.create(CommandType.OPEN_MAP, "addr", "selftest");
        String longDetail = repeat("地址🌏", 300);
        byte[] frame = utf8(StatusFrameEncoder.encode(new CommandResult(
                cmd, ExecutionStatus.CONFIRMED, longDetail, "selftest", 1L, 1L)));
        boolean frameOk = frame.length <= CommandCodec.MAX_FRAME_BYTES
                && frame[frame.length - 1] == '\n';
        if (frameOk) {
            pass("long RESULT frame is <=512 bytes and newline-terminated ("
                    + frame.length + " bytes)");
        } else {
            fail("long RESULT frame invalid: bytes=" + frame.length);
        }

        List<byte[]> chunks = GattWriteChunker.split(frame, 20);
        boolean sizesOk = chunks.size() == GattWriteChunker.chunkCount(frame.length, 20);
        for (byte[] chunk : chunks) {
            sizesOk &= chunk.length > 0 && chunk.length <= 20;
        }
        byte[] reassembled = concat(chunks);
        if (sizesOk && java.util.Arrays.equals(frame, reassembled)) {
            pass("chunker splits long RESULT into " + chunks.size()
                    + " ordered chunks and reassembles byte-identically");
        } else {
            fail("chunker round trip failed: chunks=" + chunks.size()
                    + " sizesOk=" + sizesOk + " equal="
                    + java.util.Arrays.equals(frame, reassembled));
        }

        String chinese = "PP1|RESULT|task-中文|CONFIRMED|北京市海淀区中关村大街1号\n";
        byte[] chineseBytes = utf8(chinese);
        List<byte[]> chineseChunks = GattWriteChunker.split(chineseBytes, 3);
        byte[] chineseBack = concat(chineseChunks);
        if (java.util.Arrays.equals(chineseBytes, chineseBack)
                && chinese.equals(new String(chineseBack, StandardCharsets.UTF_8))) {
            pass("chunker preserves UTF-8 when a chunk boundary splits a CJK character");
        } else {
            fail("chunker corrupted UTF-8 across chunk boundary");
        }

        if (GattWriteChunker.split(new byte[40], 20).size() == 2) {
            pass("chunker does not emit an extra empty chunk at an exact multiple");
        } else {
            fail("chunker emitted wrong chunk count for 40/20");
        }

        if (GattWriteChunker.split(new byte[0], 20).isEmpty()
                && GattWriteChunker.chunkCount(0, 20) == 0) {
            pass("chunker handles empty frame as zero chunks");
        } else {
            fail("chunker empty frame handling failed");
        }

        boolean threw = false;
        try {
            GattWriteChunker.split(new byte[]{1}, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        if (threw) {
            pass("chunker rejects non-positive payload size");
        } else {
            fail("chunker accepted payload size 0");
        }
    }

    // ------------------------------------------ task text framing / assembly

    private static void taskAssemblerTests() {
        // 按 MTU 大小切片，故意切在中文多字节字符中间。
        try {
            String text = "把刚收到的地址打开地图";
            byte[] frame = CommandCodec.encodeTaskTextBytes("task-split-1", text);
            CommandCodec.FrameAssembler assembler = new CommandCodec.FrameAssembler();
            List<String> frames = new ArrayList<>();
            int[] cuts = {7, 19, 20, 33, 47};
            int offset = 0;
            for (int cut : cuts) {
                if (cut <= offset || cut >= frame.length) {
                    continue;
                }
                byte[] chunk = new byte[cut - offset];
                System.arraycopy(frame, offset, chunk, 0, chunk.length);
                frames.addAll(assembler.append(chunk, offset));
                offset = cut;
            }
            byte[] tail = new byte[frame.length - offset];
            System.arraycopy(frame, offset, tail, 0, tail.length);
            frames.addAll(assembler.append(tail, offset));

            if (frames.size() == 1) {
                CommandCodec.Decoded decoded = CommandCodec.decode(
                        frames.get(0).getBytes(StandardCharsets.UTF_8));
                if (decoded.type == CommandType.TASK_TEXT
                        && text.equals(decoded.argument)
                        && "task-split-1".equals(decoded.messageId)) {
                    pass("assembler reassembles TASK_TEXT split across MTU and mid-CJK");
                } else {
                    fail("split task text mismatch: " + decoded.argument);
                }
            } else {
                fail("split task text: frames=" + frames.size() + " expected=1");
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("split task text threw " + e.code());
        }

        // 511 字节负载 + 换行 = 512 字节整帧，必须接受。
        try {
            CommandCodec.FrameAssembler assembler = new CommandCodec.FrameAssembler();
            String payload = "PP1|COMMAND_OPEN_MAP|" + repeat("a", 490);
            if (utf8(payload).length != CommandCodec.MAX_PAYLOAD_BYTES) {
                fail("boundary payload length=" + utf8(payload).length);
            } else {
                List<String> frames = assembler.append((payload + "\n").getBytes(StandardCharsets.UTF_8));
                if (frames.size() == 1 && utf8(frames.get(0)).length == CommandCodec.MAX_PAYLOAD_BYTES) {
                    pass("assembler accepts 511-byte payload + newline (512-byte frame)");
                } else {
                    fail("boundary frame: frames=" + frames.size());
                }
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("boundary frame threw " + e.code());
        }

        // 512 字节负载 + 换行 = 513 字节，必须拒绝。
        try {
            CommandCodec.FrameAssembler assembler = new CommandCodec.FrameAssembler();
            assembler.append(("PP1|" + repeat("a", 508) + "\n").getBytes(StandardCharsets.UTF_8));
            fail("assembler accepted 512-byte payload + newline");
        } catch (CommandCodec.ProtocolException e) {
            if ("FRAME_TOO_LONG".equals(e.code())) {
                pass("assembler rejects 512-byte payload + newline (would be 513)");
            } else {
                fail("513-byte frame code=" + e.code());
            }
        }

        // 超时后旧半帧丢弃，新到的一整帧仍必须能完成。
        try {
            CommandCodec.FrameAssembler assembler = new CommandCodec.FrameAssembler();
            assembler.append("PP1|COMMAND_TA".getBytes(StandardCharsets.UTF_8), 0L);
            List<String> frames = assembler.append(
                    "PP1|COMMAND_BACK\n".getBytes(StandardCharsets.UTF_8), 2_001L);
            if (frames.size() == 1 && "PP1|COMMAND_BACK".equals(frames.get(0))
                    && assembler.consumeTimeoutDropped()) {
                pass("assembler timeout drops stale half frame and accepts next frame");
            } else {
                fail("timeout recovery: frames=" + frames.size()
                        + " flag=" + assembler.consumeTimeoutDropped());
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("timeout recovery threw " + e.code());
        }

        // expireIfStale 主动清空后仍可恢复。
        try {
            CommandCodec.FrameAssembler assembler = new CommandCodec.FrameAssembler();
            assembler.append("PP1|COMMAND_TA".getBytes(StandardCharsets.UTF_8), 0L);
            boolean expired = assembler.expireIfStale(2_001L);
            List<String> frames = assembler.append(
                    "PP1|COMMAND_BACK\n".getBytes(StandardCharsets.UTF_8), 2_100L);
            if (expired && frames.size() == 1 && !assembler.hasPartialFrame()) {
                pass("expireIfStale clears half frame and receiver recovers");
            } else {
                fail("expire/recover: expired=" + expired + " frames=" + frames.size());
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("expire/recover threw " + e.code());
        }

        // 重复分片/重复帧：重组层会产出两份，去重必须由 TaskMessageGuard 负责。
        try {
            String frame = CommandCodec.encodeTaskText("task-dup-frame", "hello");
            CommandCodec.FrameAssembler assembler = new CommandCodec.FrameAssembler();
            List<String> frames = assembler.append(
                    (frame + frame).getBytes(StandardCharsets.UTF_8), 0L);
            if (frames.size() == 2 && frames.get(0).equals(frames.get(1))) {
                pass("assembler emits duplicate complete frames for guard-level dedup");
            } else {
                fail("duplicate frames: " + frames.size());
            }
        } catch (CommandCodec.ProtocolException e) {
            fail("duplicate frame threw " + e.code());
        }

        // 乱序字节不能拼出合法 TASK_TEXT；协议没有分片序号，不做“乱序恢复”承诺。
        try {
            String frame = CommandCodec.encodeTaskText("task-order-1", "hello");
            String reordered = frame.substring(12) + frame.substring(0, 12);
            CommandCodec.FrameAssembler assembler = new CommandCodec.FrameAssembler();
            List<String> frames = assembler.append(reordered.getBytes(StandardCharsets.UTF_8), 0L);
            boolean rejected = false;
            for (String candidate : frames) {
                try {
                    CommandCodec.decode(candidate.getBytes(StandardCharsets.UTF_8));
                } catch (CommandCodec.ProtocolException e) {
                    rejected = true;
                }
            }
            if (frames.isEmpty() || rejected) {
                pass("out-of-order bytes do not form a valid TASK_TEXT (rejected, not recovered)");
            } else {
                fail("out-of-order bytes unexpectedly decoded as valid");
            }
        } catch (CommandCodec.ProtocolException e) {
            pass("out-of-order bytes rejected at assembly (" + e.code() + ")");
        }
    }

    // ------------------------------------------------------- message guard

    private static void taskMessageGuardTests() {
        TaskMessageGuard guard = new TaskMessageGuard(1_000L, 8);
        boolean first = guard.markIfNew("task-1", 0L);
        boolean second = guard.markIfNew("task-1", 999L);
        boolean afterTtl = guard.markIfNew("task-1", 1_000L);
        if (first && !second && afterTtl) {
            pass("message guard suppresses duplicate within TTL and accepts after expiry");
        } else {
            fail("message guard: first=" + first + " second=" + second + " afterTtl=" + afterTtl);
        }

        if (!guard.markIfNew("", 0L)) {
            pass("message guard rejects empty id");
        } else {
            fail("message guard accepted empty id");
        }

        TaskMessageGuard tiny = new TaskMessageGuard(60_000L, 2);
        tiny.markIfNew("a", 0L);
        tiny.markIfNew("b", 1L);
        tiny.markIfNew("c", 2L);
        boolean evicted = tiny.markIfNew("a", 3L);
        if (evicted && tiny.size() <= 2) {
            pass("message guard bounded capacity evicts oldest entry");
        } else {
            fail("guard capacity: evicted=" + evicted + " size=" + tiny.size());
        }
    }

    // ---------------------------------------------------- task validator

    private static void taskValidatorTests() {
        PocketCommand ok = PocketCommand.createWithId("task-1", CommandType.TASK_TEXT,
                "把刚收到的地址打开地图", "selftest");
        expectAccept("TASK_TEXT accepted by validator",
                CommandValidator.validate(ok, false, 1_000L, 1_001L));

        PocketCommand blank = PocketCommand.createWithId("task-2", CommandType.TASK_TEXT,
                "   ", "selftest");
        expectReason("TASK_TEXT blank rejected",
                CommandValidator.validate(blank, false, 0L, 1_000L), "EMPTY_TASK_TEXT");

        PocketCommand tooLong = PocketCommand.createWithId("task-3", CommandType.TASK_TEXT,
                repeat("a", 385), "selftest");
        expectReason("TASK_TEXT 385 bytes rejected",
                CommandValidator.validate(tooLong, false, 0L, 1_000L), "TEXT_TOO_LONG");

        PocketCommand badId = PocketCommand.createWithId("bad id", CommandType.TASK_TEXT,
                "hello", "selftest");
        expectReason("TASK_TEXT invalid id rejected",
                CommandValidator.validate(badId, false, 0L, 1_000L), "INVALID_MESSAGE_ID");
    }

    // ---------------------------------------------------------------- utils

    private static int countPipes(String value) {
        int n = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '|') {
                n++;
            }
        }
        return n;
    }

    private static void expectInt(String name, int expected, int actual) {
        if (expected == actual) {
            pass(name);
        } else {
            fail(name + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static byte[] concat(List<byte[]> chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.length);
            offset += chunk.length;
        }
        return out;
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("PASS  " + name);
    }

    private static void fail(String message) {
        FAILURES.add(message);
        System.out.println("FAIL  " + message);
    }
}