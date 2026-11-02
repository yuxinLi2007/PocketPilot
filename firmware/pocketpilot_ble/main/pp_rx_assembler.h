#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * Byte-stream frame assembler for the PocketPilot GATT write characteristic.
 *
 * A logical frame is newline-terminated. GATT writes are transport fragments:
 * one write may contain part of a frame, one whole frame, or several frames.
 * This module therefore never interprets a write as a message boundary.
 */

#define PP_RX_MAX_FRAME_BYTES 512
#define PP_RX_LINE_BYTES      (PP_RX_MAX_FRAME_BYTES + 1)

typedef struct {
    char line[PP_RX_LINE_BYTES];
    size_t len;
    bool discard;
} pp_rx_assembler_t;

typedef void (*pp_rx_line_cb)(char *line, void *ctx);
typedef void (*pp_rx_reject_cb)(const char *code, const char *detail, void *ctx);

void pp_rx_assembler_init(pp_rx_assembler_t *assembler);
void pp_rx_assembler_reset(pp_rx_assembler_t *assembler);
void pp_rx_assembler_feed(pp_rx_assembler_t *assembler, const uint8_t *data, uint16_t len,
                          pp_rx_line_cb on_line, pp_rx_reject_cb on_reject, void *ctx);
