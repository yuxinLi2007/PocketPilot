/*
 * Host-side test for the exact frame assembler compiled into the ESP32 firmware.
 *
 * The assembler is byte-oriented on purpose: an Android GATT write can split a
 * UTF-8 character, a frame, or several frames. The tests below feed the same
 * byte sequences that GattWriteChunker produces and verify newline reassembly.
 */
#include <stdio.h>
#include <string.h>

#include "pp_rx_assembler.h"

#define MAX_CAPTURED_LINES 8
#define MAX_CAPTURED_REJECTS 8

static char captured[MAX_CAPTURED_LINES][PP_RX_LINE_BYTES + 1];
static size_t captured_len[MAX_CAPTURED_LINES];
static int captured_count;

static char rejects[MAX_CAPTURED_REJECTS][96];
static int reject_count;

static int failures;
static int checks;

static void reset_capture(void)
{
    memset(captured, 0, sizeof(captured));
    memset(captured_len, 0, sizeof(captured_len));
    memset(rejects, 0, sizeof(rejects));
    captured_count = 0;
    reject_count = 0;
}

static void on_line(char *line, void *ctx)
{
    (void)ctx;
    if (captured_count >= MAX_CAPTURED_LINES) {
        return;
    }
    size_t len = strlen(line);
    if (len > PP_RX_LINE_BYTES) {
        len = PP_RX_LINE_BYTES;
    }
    memcpy(captured[captured_count], line, len);
    captured[captured_count][len] = '\0';
    captured_len[captured_count] = len;
    captured_count++;
}

static void on_reject(const char *code, const char *detail, void *ctx)
{
    (void)ctx;
    (void)detail;
    if (reject_count >= MAX_CAPTURED_REJECTS) {
        return;
    }
    snprintf(rejects[reject_count], sizeof(rejects[reject_count]), "%s", code);
    reject_count++;
}

static void check(int condition, const char *name, const char *detail)
{
    checks++;
    if (condition) {
        printf("PASS %s\n", name);
    } else {
        failures++;
        printf("FAIL %s%s%s\n", name, detail != NULL ? ": " : "", detail != NULL ? detail : "");
    }
}

static void feed(pp_rx_assembler_t *a, const char *bytes, size_t len)
{
    pp_rx_assembler_feed(a, (const uint8_t *)bytes, (uint16_t)len, on_line, on_reject, NULL);
}

static void feed_str(pp_rx_assembler_t *a, const char *text)
{
    feed(a, text, strlen(text));
}

static void test_two_writes(void)
{
    pp_rx_assembler_t a;
    pp_rx_assembler_init(&a);
    reset_capture();
    const char *frame = "PP1|RESULT|COMMAND_OPEN_MAP|CONFIRMED|ok\n";
    feed(&a, frame, 11);
    feed(&a, frame + 11, strlen(frame) - 11);
    check(captured_count == 1 && reject_count == 0 &&
          strcmp(captured[0], "PP1|RESULT|COMMAND_OPEN_MAP|CONFIRMED|ok") == 0,
          "two GATT writes reassemble one RESULT frame", NULL);
}

static void test_utf8_split(void)
{
    pp_rx_assembler_t a;
    const char *frame = "PP1|RESULT|COMMAND_OPEN_MAP|CONFIRMED|北京市海淀区中关村大街1号\n";
    const char *expected = "PP1|RESULT|COMMAND_OPEN_MAP|CONFIRMED|北京市海淀区中关村大街1号";
    const char *bei = strstr(frame, "北");
    size_t split = (size_t)(bei - frame) + 1; /* inside the 3-byte UTF-8 sequence */

    pp_rx_assembler_init(&a);
    reset_capture();
    feed(&a, frame, split);
    feed(&a, frame + split, strlen(frame) - split);
    check(captured_count == 1 && reject_count == 0 && strcmp(captured[0], expected) == 0,
          "UTF-8 Chinese character split across writes", NULL);

    pp_rx_assembler_init(&a);
    reset_capture();
    for (size_t i = 0; i < strlen(frame); i++) {
        feed(&a, frame + i, 1);
    }
    check(captured_count == 1 && strcmp(captured[0], expected) == 0,
          "byte-by-byte writes reassemble Chinese frame", NULL);
}

static void test_26_chunks(void)
{
    pp_rx_assembler_t a;
    char payload[PP_RX_MAX_FRAME_BYTES + 2];
    memset(payload, 'A', 511);
    payload[511] = '\n';
    payload[512] = '\0';

    pp_rx_assembler_init(&a);
    reset_capture();
    size_t offset = 0;
    int chunks = 0;
    while (offset < 512) {
        size_t n = 512 - offset;
        if (n > 20) {
            n = 20;
        }
        feed(&a, payload + offset, n);
        offset += n;
        chunks++;
    }
    check(chunks == 26 && captured_count == 1 && captured_len[0] == 511 && reject_count == 0,
          "26 writes x 20 bytes reassemble 511-byte payload", NULL);
}

static void test_exact_buffer_limit(void)
{
    pp_rx_assembler_t a;
    char payload[PP_RX_MAX_FRAME_BYTES + 2];
    memset(payload, 'B', PP_RX_MAX_FRAME_BYTES);
    payload[PP_RX_MAX_FRAME_BYTES] = '\n';
    payload[PP_RX_MAX_FRAME_BYTES + 1] = '\0';

    pp_rx_assembler_init(&a);
    reset_capture();
    feed(&a, payload, PP_RX_MAX_FRAME_BYTES + 1);
    check(captured_count == 1 && captured_len[0] == PP_RX_MAX_FRAME_BYTES && reject_count == 0,
          "512-byte payload is accepted by receiver buffer", NULL);
}

static void test_overflow_and_resync(void)
{
    pp_rx_assembler_t a;
    char payload[PP_RX_MAX_FRAME_BYTES + 3];
    memset(payload, 'C', PP_RX_MAX_FRAME_BYTES + 1);
    payload[PP_RX_MAX_FRAME_BYTES + 1] = '\n';
    payload[PP_RX_MAX_FRAME_BYTES + 2] = '\0';

    pp_rx_assembler_init(&a);
    reset_capture();
    feed(&a, payload, PP_RX_MAX_FRAME_BYTES + 2);
    check(captured_count == 0 && reject_count == 1 &&
          strcmp(rejects[0], "FRAME_TOO_LONG") == 0,
          "513-byte payload is rejected", NULL);

    feed_str(&a, "PP1|RESULT|COMMAND_BACK|CONFIRMED|back\n");
    check(captured_count == 1 && strcmp(captured[0], "PP1|RESULT|COMMAND_BACK|CONFIRMED|back") == 0,
          "receiver resynchronises after over-long frame", NULL);
}

static void test_partial_until_newline(void)
{
    pp_rx_assembler_t a;
    pp_rx_assembler_init(&a);
    reset_capture();
    feed_str(&a, "PP1|RESULT|COMMAND_BACK|CONFIRMED|back");
    check(captured_count == 0, "frame without newline is held as partial", NULL);
    feed_str(&a, "\n");
    check(captured_count == 1 && strcmp(captured[0], "PP1|RESULT|COMMAND_BACK|CONFIRMED|back") == 0,
          "newline in a later write completes the frame", NULL);
}

static void test_multiple_frames_one_write(void)
{
    pp_rx_assembler_t a;
    pp_rx_assembler_init(&a);
    reset_capture();
    feed_str(&a, "PP1|RESULT|COMMAND_BACK|CONFIRMED|a\nPP1|RESULT|COMMAND_BACK|CONFIRMED|b\n");
    check(captured_count == 2 && strcmp(captured[0], "PP1|RESULT|COMMAND_BACK|CONFIRMED|a") == 0 &&
          strcmp(captured[1], "PP1|RESULT|COMMAND_BACK|CONFIRMED|b") == 0,
          "one write containing two frames is split at newline", NULL);
}

static void test_reset_clears_partial(void)
{
    pp_rx_assembler_t a;
    pp_rx_assembler_init(&a);
    reset_capture();
    feed_str(&a, "PP1|RESULT|COMMAND_BACK|CONFIRMED|stale");
    pp_rx_assembler_reset(&a);
    feed_str(&a, "PP1|RESULT|COMMAND_BACK|CONFIRMED|fresh\n");
    check(captured_count == 1 && strcmp(captured[0], "PP1|RESULT|COMMAND_BACK|CONFIRMED|fresh") == 0,
          "reset drops partial frame across reconnect", NULL);
}

static void test_empty_line(void)
{
    pp_rx_assembler_t a;
    pp_rx_assembler_init(&a);
    reset_capture();
    feed_str(&a, "\n");
    check(captured_count == 1 && captured_len[0] == 0,
          "empty line is delivered for higher-layer rejection", NULL);
}

int main(void)
{
    test_two_writes();
    test_utf8_split();
    test_26_chunks();
    test_exact_buffer_limit();
    test_overflow_and_resync();
    test_partial_until_newline();
    test_multiple_frames_one_write();
    test_reset_clears_partial();
    test_empty_line();

    printf("SUMMARY checks=%d failures=%d\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
