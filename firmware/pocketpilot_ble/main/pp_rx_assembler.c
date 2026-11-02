#include "pp_rx_assembler.h"

void pp_rx_assembler_init(pp_rx_assembler_t *assembler)
{
    if (assembler == NULL) {
        return;
    }
    assembler->line[0] = '\0';
    assembler->len = 0;
    assembler->discard = false;
}

void pp_rx_assembler_reset(pp_rx_assembler_t *assembler)
{
    pp_rx_assembler_init(assembler);
}

void pp_rx_assembler_feed(pp_rx_assembler_t *assembler, const uint8_t *data, uint16_t len,
                          pp_rx_line_cb on_line, pp_rx_reject_cb on_reject, void *ctx)
{
    if (assembler == NULL || data == NULL) {
        return;
    }

    for (uint16_t i = 0; i < len; i++) {
        char c = (char)data[i];
        if (c == '\n') {
            if (assembler->discard) {
                /* Tail of an over-long frame: drop it without parsing as a new frame. */
                assembler->discard = false;
            } else {
                assembler->line[assembler->len] = '\0';
                if (on_line != NULL) {
                    on_line(assembler->line, ctx);
                }
            }
            assembler->len = 0;
            continue;
        }
        if (assembler->discard) {
            continue;
        }
        if (assembler->len >= PP_RX_MAX_FRAME_BYTES) {
            assembler->len = 0;
            assembler->discard = true;
            if (on_reject != NULL) {
                on_reject("FRAME_TOO_LONG", "rx buffer overflow, dropping until newline", ctx);
            }
            continue;
        }
        assembler->line[assembler->len++] = c;
    }
}
