/*
 * PocketPilot BLE peripheral
 * --------------------------
 * Target board : ESP32-S3-EYE (ESP32-S3, 8 MB flash)
 * ESP-IDF      : v5.4.x, Bluedroid
 *
 * Role in the product loop:
 *   [serial test input] -> command frame -> BLE notify -> Android app
 *   Android app -> status frame (GATT write) -> serial log
 *
 * Command sources, in order of maturity:
 *   1. ESP-SR 中文命令词（按键触发单次监听）-> BLE - see pp_asr.c
 *   2. ESP32-S3-EYE BOOT button + front panel MENU/UP/DOWN - see pp_button.c
 *   3. USB serial/JTAG console keys 1/2/3 and v - bring-up helper
 * Voice is one-shot push-to-talk: a key starts a bounded listen window,
 * one recognised phrase becomes one BLE command frame.
 *
 * Button and LED pins are taken from the official Espressif esp-bsp board
 * support package (bsp/esp32_s3_eye), not guessed:
 *   BSP_BUTTON_5_IO = GPIO0 (active level 0), BSP_LED_1_IO = GPIO3 (active high).
 *
 * GATT layout (must match Android GattBleTransport):
 *   service 6f706f63-6b65-742d-7069-6c6f74000001
 *   RX      6f706f63-6b65-742d-7069-6c6f74000002  notify   (device -> phone)
 *   TX      6f706f63-6b65-742d-7069-6c6f74000003  write    (phone -> device)
 *
 * Frame format (UTF-8, newline terminated, max 512 bytes):
 *   PP1|<COMMAND>|<ARGUMENT>\n
 *   PP1|COMMAND_TASK_TEXT|<message_id>|<utf8_byte_length>|<text>\n
 *   PP1|RESULT|<command_id>|<STATUS>|<detail>\n
 */

#include <stdio.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_err.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_bt_defs.h"
#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_gatt_common_api.h"
#include "esp_timer.h"

#include "pp_button.h"
#include "pp_asr.h"
#include "pp_audio.h"
#include "pp_rx_assembler.h"

#define TAG "PocketPilotBLE"

#define DEVICE_NAME        "PocketPilot"
#define PROFILE_APP_ID     0
#define MAX_FRAME_BYTES    512
#define DEFAULT_MTU        23
#define TARGET_MTU         185
#define MAX_TASK_TEXT_BYTES 384
#define MAX_MESSAGE_ID_CHARS 64

/* 128-bit UUIDs in little-endian byte order (Bluetooth on-air order). */
static const uint8_t SERVICE_UUID[16] = {
    0x01, 0x00, 0x00, 0x74, 0x6f, 0x6c, 0x69, 0x70,
    0x2d, 0x74, 0x65, 0x6b, 0x63, 0x6f, 0x70, 0x6f,
};
static const uint8_t RX_UUID[16] = {
    0x02, 0x00, 0x00, 0x74, 0x6f, 0x6c, 0x69, 0x70,
    0x2d, 0x74, 0x65, 0x6b, 0x63, 0x6f, 0x70, 0x6f,
};
static const uint8_t TX_UUID[16] = {
    0x03, 0x00, 0x00, 0x74, 0x6f, 0x6c, 0x69, 0x70,
    0x2d, 0x74, 0x65, 0x6b, 0x63, 0x6f, 0x70, 0x6f,
};

static esp_gatt_if_t s_gatts_if = ESP_GATT_IF_NONE;
static uint16_t s_service_handle = 0;
static uint16_t s_rx_handle = 0;
static uint16_t s_tx_handle = 0;
static uint16_t s_cccd_handle = 0;
static uint16_t s_conn_id = 0xFFFF;
static uint16_t s_mtu = DEFAULT_MTU;
static volatile bool s_service_ready = false;
static volatile bool s_connected = false;
static volatile bool s_subscribed = false;

/* GATT write 是字节流分片，只有 '\n' 才代表一条完整帧。 */
static pp_rx_assembler_t s_rx;
static uint32_t s_rx_rejects = 0;            /* 真实拒绝计数，供 's' 状态查询 */
static uint32_t s_results_ok = 0;
static SemaphoreHandle_t s_tx_lock = NULL;   /* 串行化多任务发送，避免通知交错 */
static volatile bool s_congested = false;

static const char CMD_WECHAT[] = "PP1|COMMAND_OPEN_WECHAT\n";
static const char CMD_MAP[] = "PP1|COMMAND_OPEN_MAP|\xe5\x8c\x97\xe4\xba\xac\xe5\xb8\x82\xe6\xb5\xb7\xe6\xb7\x80\xe5\x8c\xba\xe4\xb8\xad\xe5\x85\xb3\xe6\x9d\x91\xe5\xa4\xa7\xe8\xa1\x97" "1\xe5\x8f\xb7\n";
static const char CMD_BACK[] = "PP1|COMMAND_BACK\n";
/* 串口测试文本：这是人工输入，不是语音识别结果。 */
static const char CMD_TASK_TEXT_DEMO[] = "把刚收到的地址打开地图";
static uint32_t s_task_seq = 0;

/* ------------------------------------------------------------------------- */
/* BLE helpers                                                               */
/* ------------------------------------------------------------------------- */

static void pp_start_advertising(void);

static void pp_gap_event_handler(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param);
static void pp_gatts_event_handler(esp_gatts_cb_event_t event, esp_gatt_if_t gatts_if,
                                   esp_ble_gatts_cb_param_t *param);

static esp_ble_adv_data_t s_adv_data = {
    .set_scan_rsp = false,
    .include_name = false,
    .include_txpower = true,
    .min_interval = 0,
    .max_interval = 0,
    .appearance = 0,
    .manufacturer_len = 0,
    .p_manufacturer_data = NULL,
    .service_data_len = 0,
    .p_service_data = NULL,
    .service_uuid_len = sizeof(SERVICE_UUID),
    .p_service_uuid = (uint8_t *)SERVICE_UUID,
    .flag = (ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT),
};

static esp_ble_adv_data_t s_scan_rsp_data = {
    .set_scan_rsp = true,
    .include_name = true,
    .include_txpower = false,
    .min_interval = 0,
    .max_interval = 0,
    .appearance = 0,
    .manufacturer_len = 0,
    .p_manufacturer_data = NULL,
    .service_data_len = 0,
    .p_service_data = NULL,
    .service_uuid_len = 0,
    .p_service_uuid = NULL,
    .flag = 0,
};

static esp_ble_adv_params_t s_adv_params = {
    .adv_int_min = 0x20,
    .adv_int_max = 0x40,
    .adv_type = ADV_TYPE_IND,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .channel_map = ADV_CHNL_ALL,
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};

static void pp_start_advertising(void)
{
    esp_err_t err = esp_ble_gap_start_advertising(&s_adv_params);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PP_ADV_START_FAILED err=%s", esp_err_to_name(err));
    }
}

static void pp_gap_event_handler(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_GAP_BLE_ADV_DATA_SET_COMPLETE_EVT:
        esp_ble_gap_config_adv_data(&s_scan_rsp_data);
        break;
    case ESP_GAP_BLE_SCAN_RSP_DATA_SET_COMPLETE_EVT:
        pp_start_advertising();
        break;
    case ESP_GAP_BLE_ADV_START_COMPLETE_EVT:
        if (param->adv_start_cmpl.status == ESP_BT_STATUS_SUCCESS) {
            ESP_LOGI(TAG, "PP_ADV_STARTED name=%s", DEVICE_NAME);
            pp_led_set_mode(PP_LED_ADVERTISING);
        } else {
            ESP_LOGE(TAG, "PP_ADV_START_FAILED status=%d", param->adv_start_cmpl.status);
        }
        break;
    case ESP_GAP_BLE_ADV_STOP_COMPLETE_EVT:
        ESP_LOGI(TAG, "PP_ADV_STOPPED status=%d", param->adv_stop_cmpl.status);
        break;
    default:
        break;
    }
}

/* ------------------------------------------------------------------------- */
/* Result frame validation                                                   */
/* ------------------------------------------------------------------------- */

static bool pp_is_valid_message_id(const char *id)
{
    if (id == NULL) {
        return false;
    }
    size_t len = strlen(id);
    if (len == 0 || len > MAX_MESSAGE_ID_CHARS) {
        return false;
    }
    for (size_t i = 0; i < len; i++) {
        char c = id[i];
        bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                  (c >= '0' && c <= '9') || c == '.' || c == '_' ||
                  c == ':' || c == '-';
        if (!ok) {
            return false;
        }
    }
    return true;
}

/* Android 固定命令的 ID 形如 COMMAND_OPEN_MAP-1a2b3c4d（8 位十六进制）。 */
static bool pp_is_fixed_command_id(const char *id, const char *prefix)
{
    size_t prefix_len = strlen(prefix);
    if (strncmp(id, prefix, prefix_len) != 0) {
        return false;
    }
    const char *suffix = id + prefix_len;
    if (strlen(suffix) != 8) {
        return false;
    }
    for (size_t i = 0; i < 8; i++) {
        char c = suffix[i];
        bool hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        if (!hex) {
            return false;
        }
    }
    return true;
}

static bool pp_known_command(const char *id)
{
    if (id == NULL) {
        return false;
    }
    /* Android 对“收到但无法解码的帧”会回一条 PROTOCOL-xxxxxxxx 结果，
     * 这类结果同样需要被记录，否则设备端会把它误判成未知命令。 */
    if (strncmp(id, "PROTOCOL-", 9) == 0) {
        return true;
    }
    if (strcmp(id, "COMMAND_OPEN_WECHAT") == 0 ||
        strcmp(id, "COMMAND_OPEN_MAP") == 0 ||
        strcmp(id, "COMMAND_BACK") == 0 ||
        pp_is_fixed_command_id(id, "COMMAND_OPEN_WECHAT-") ||
        pp_is_fixed_command_id(id, "COMMAND_OPEN_MAP-") ||
        pp_is_fixed_command_id(id, "COMMAND_BACK-")) {
        return true;
    }
    /* 未知的 COMMAND_* 仍然拒绝，避免把拼错的固定命令当成任务 ID。 */
    if (strncmp(id, "COMMAND_", 8) == 0) {
        return false;
    }
    return pp_is_valid_message_id(id);
}

static bool pp_known_status(const char *status)
{
    static const char *const allowed[] = {
        "RECEIVED", "REJECTED", "NOT_INSTALLED", "PERMISSION_REQUIRED",
        "EXECUTED_UNCONFIRMED", "CONFIRMED", "FAILED",
    };
    if (status == NULL) {
        return false;
    }
    for (size_t i = 0; i < sizeof(allowed) / sizeof(allowed[0]); i++) {
        if (strcmp(status, allowed[i]) == 0) {
            return true;
        }
    }
    return false;
}

static void pp_reject(const char *code, const char *detail)
{
    s_rx_rejects++;
    ESP_LOGW(TAG, "PP_RX_REJECTED code=%s detail=%s total=%u",
             code, detail == NULL ? "" : detail, (unsigned)s_rx_rejects);
}

static void pp_handle_result_line(char *line)
{
    size_t len = strlen(line);
    while (len > 0 && (line[len - 1] == '\r' || line[len - 1] == '\n')) {
        line[--len] = '\0';
    }
    if (len == 0) {
        pp_reject("EMPTY_FRAME", "empty line");
        return;
    }
    if (len > MAX_FRAME_BYTES) {
        pp_reject("FRAME_TOO_LONG", ">512 bytes");
        return;
    }

    char *save = NULL;
    char *version = strtok_r(line, "|", &save);
    char *kind = strtok_r(NULL, "|", &save);
    char *command = strtok_r(NULL, "|", &save);
    char *status = strtok_r(NULL, "|", &save);
    char *detail = strtok_r(NULL, "|", &save);

    if (version == NULL || kind == NULL) {
        pp_reject("MALFORMED_FRAME", "missing fields");
        return;
    }
    if (strcmp(version, "PP1") != 0) {
        pp_reject("UNSUPPORTED_VERSION", version);
        return;
    }
    if (strcmp(kind, "RESULT") != 0) {
        pp_reject("UNSUPPORTED_FRAME_TYPE", kind);
        return;
    }
    if (!pp_known_command(command)) {
        pp_reject("UNKNOWN_COMMAND", command == NULL ? "(null)" : command);
        return;
    }
    if (!pp_known_status(status)) {
        pp_reject("UNKNOWN_STATUS", status == NULL ? "(null)" : status);
        return;
    }

    s_results_ok++;
    ESP_LOGI(TAG, "PP_RX_RESULT command=%s status=%s detail=%s total=%u",
             command, status, detail == NULL ? "" : detail, (unsigned)s_results_ok);
}

static void pp_on_rx_line(char *line, void *ctx)
{
    (void)ctx;
    pp_handle_result_line(line);
}

static void pp_on_rx_reject(const char *code, const char *detail, void *ctx)
{
    (void)ctx;
    pp_reject(code, detail);
}

static void pp_feed_rx_bytes(const uint8_t *data, uint16_t len)
{
    pp_rx_assembler_feed(&s_rx, data, len, pp_on_rx_line, pp_on_rx_reject, NULL);
}

/* ------------------------------------------------------------------------- */
/* GATT server                                                               */
/* ------------------------------------------------------------------------- */

static void pp_gatts_event_handler(esp_gatts_cb_event_t event, esp_gatt_if_t gatts_if,
                                   esp_ble_gatts_cb_param_t *param)
{
    switch (event) {
    case ESP_GATTS_REG_EVT:
        if (param->reg.status != ESP_GATT_OK) {
            ESP_LOGE(TAG, "PP_GATTS_REG_FAILED status=%d", param->reg.status);
            break;
        }
        s_gatts_if = gatts_if;
        esp_ble_gap_set_device_name(DEVICE_NAME);
        esp_ble_gap_config_adv_data(&s_adv_data);
        {
            esp_gatt_srvc_id_t service_id = {
                .is_primary = true,
                .id = {
                    .inst_id = 0,
                    .uuid = {
                        .len = ESP_UUID_LEN_128,
                    },
                },
            };
            memcpy(service_id.id.uuid.uuid.uuid128, SERVICE_UUID, sizeof(SERVICE_UUID));
            esp_err_t err = esp_ble_gatts_create_service(gatts_if, &service_id, 10);
            if (err != ESP_OK) {
                ESP_LOGE(TAG, "PP_CREATE_SERVICE_FAILED err=%s", esp_err_to_name(err));
            }
        }
        break;

    case ESP_GATTS_CREATE_EVT:
        s_service_handle = param->create.service_handle;
        ESP_LOGI(TAG, "PP_SERVICE_CREATED handle=%u", s_service_handle);
        {
            esp_bt_uuid_t rx = {.len = ESP_UUID_LEN_128};
            memcpy(rx.uuid.uuid128, RX_UUID, sizeof(RX_UUID));
            esp_ble_gatts_add_char(s_service_handle, &rx, ESP_GATT_PERM_READ,
                                   ESP_GATT_CHAR_PROP_BIT_NOTIFY, NULL, NULL);
        }
        break;

    case ESP_GATTS_ADD_CHAR_EVT: {
        esp_bt_uuid_t *uuid = &param->add_char.char_uuid;
        if (uuid->len == ESP_UUID_LEN_128 &&
            memcmp(uuid->uuid.uuid128, RX_UUID, sizeof(RX_UUID)) == 0) {
            s_rx_handle = param->add_char.attr_handle;
            ESP_LOGI(TAG, "PP_RX_CHAR_READY handle=%u", s_rx_handle);
            esp_bt_uuid_t cccd = {.len = ESP_UUID_LEN_16};
            cccd.uuid.uuid16 = ESP_GATT_UUID_CHAR_CLIENT_CONFIG;
            esp_ble_gatts_add_char_descr(s_service_handle, &cccd,
                                         ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE,
                                         NULL, NULL);
        } else if (uuid->len == ESP_UUID_LEN_128 &&
                   memcmp(uuid->uuid.uuid128, TX_UUID, sizeof(TX_UUID)) == 0) {
            s_tx_handle = param->add_char.attr_handle;
            ESP_LOGI(TAG, "PP_TX_CHAR_READY handle=%u", s_tx_handle);
            esp_ble_gatts_start_service(s_service_handle);
        }
        break;
    }

    case ESP_GATTS_ADD_CHAR_DESCR_EVT:
        s_cccd_handle = param->add_char_descr.attr_handle;
        ESP_LOGI(TAG, "PP_CCCD_READY handle=%u", s_cccd_handle);
        {
            esp_bt_uuid_t tx = {.len = ESP_UUID_LEN_128};
            memcpy(tx.uuid.uuid128, TX_UUID, sizeof(TX_UUID));
            esp_ble_gatts_add_char(s_service_handle, &tx,
                                   ESP_GATT_PERM_WRITE,
                                   ESP_GATT_CHAR_PROP_BIT_WRITE | ESP_GATT_CHAR_PROP_BIT_WRITE_NR,
                                   NULL, NULL);
        }
        break;

    case ESP_GATTS_START_EVT:
        s_service_ready = true;
        ESP_LOGI(TAG, "PP_SERVICE_READY handle=%u", param->start.service_handle);
        break;

    case ESP_GATTS_CONNECT_EVT:
        if (s_connected) {
            ESP_LOGW(TAG, "PP_SECOND_CONNECTION_IGNORED_STATE conn_id=%u",
                     param->connect.conn_id);
        }
        s_conn_id = param->connect.conn_id;
        s_connected = true;
        s_subscribed = false;
        s_congested = false;
        s_mtu = DEFAULT_MTU;
        pp_rx_assembler_reset(&s_rx);
        pp_led_set_mode(PP_LED_CONNECTING);
        ESP_LOGI(TAG, "PP_CONNECTED conn_id=%u", s_conn_id);
        esp_ble_gap_stop_advertising();
        break;

    case ESP_GATTS_DISCONNECT_EVT:
        ESP_LOGI(TAG, "PP_DISCONNECTED conn_id=%u reason=0x%02x",
                 param->disconnect.conn_id, param->disconnect.reason);
        pp_led_set_mode(PP_LED_ADVERTISING);
        s_connected = false;
        s_subscribed = false;
        s_conn_id = 0xFFFF;
        s_mtu = DEFAULT_MTU;
        pp_rx_assembler_reset(&s_rx);
        s_congested = false;
        pp_start_advertising();
        break;

    case ESP_GATTS_MTU_EVT:
        s_mtu = param->mtu.mtu;
        ESP_LOGI(TAG, "PP_MTU mtu=%u", s_mtu);
        break;

    case ESP_GATTS_WRITE_EVT: {
        if (param->write.handle == s_tx_handle) {
            pp_feed_rx_bytes(param->write.value, param->write.len);
        } else if (param->write.handle == s_cccd_handle && param->write.len >= 1) {
            s_subscribed = (param->write.value[0] & 0x01) != 0;
            ESP_LOGI(TAG, "PP_CCCD_WRITE subscribed=%d value=0x%02x",
                     s_subscribed ? 1 : 0, param->write.value[0]);
            if (s_subscribed) {
                pp_led_set_mode(PP_LED_READY);
                ESP_LOGI(TAG, "PP_SUBSCRIBED ready_for_commands");
            } else {
                pp_led_set_mode(PP_LED_CONNECTING);
            }
        } else {
            ESP_LOGW(TAG, "PP_WRITE_UNKNOWN handle=%u len=%u",
                     param->write.handle, param->write.len);
        }
        /* Android 13+ 用 WRITE_TYPE_DEFAULT（带响应）。不回 ATT 响应会让
         * onCharacteristicWrite 一直等到超时，看起来像“手机没收到状态”。 */
        if (param->write.need_rsp) {
            esp_err_t rsp = esp_ble_gatts_send_response(gatts_if, param->write.conn_id,
                                                       param->write.trans_id, ESP_GATT_OK, NULL);
            if (rsp != ESP_OK) {
                ESP_LOGW(TAG, "PP_WRITE_RSP_FAILED handle=%u err=%s",
                         param->write.handle, esp_err_to_name(rsp));
            }
        }
        break;
    }

    case ESP_GATTS_CONGEST_EVT:
        s_congested = param->congest.congested;
        ESP_LOGW(TAG, "PP_CONGEST congested=%d", param->congest.congested);
        break;

    default:
        break;
    }
}

/* ------------------------------------------------------------------------- */
/* Command sending                                                           */
/* ------------------------------------------------------------------------- */

static size_t pp_chunk_size(void)
{
    return s_mtu > 3 ? (size_t)(s_mtu - 3) : 20;
}

static bool pp_send_frame(const char *name, const char *frame)
{
    if (!s_service_ready || !s_connected || !s_subscribed) {
        ESP_LOGW(TAG, "PP_SEND_BLOCKED name=%s ready=%d connected=%d subscribed=%d",
                 name, s_service_ready ? 1 : 0, s_connected ? 1 : 0, s_subscribed ? 1 : 0);
        return false;
    }

    /* 串口任务和未来其它生产者共用一条通知通道，帧与帧之间必须互斥，
     * 否则两个发送方会交错分片，手机端重组出乱码。 */
    if (s_tx_lock == NULL || xSemaphoreTake(s_tx_lock, pdMS_TO_TICKS(3000)) != pdTRUE) {
        ESP_LOGW(TAG, "PP_SEND_BUSY name=%s", name);
        return false;
    }

    const uint8_t *data = (const uint8_t *)frame;
    size_t total = strlen(frame);
    size_t chunk = pp_chunk_size();
    size_t offset = 0;
    bool ok = true;
    ESP_LOGI(TAG, "PP_SEND name=%s bytes=%u mtu=%u", name, (unsigned)total, s_mtu);

    while (offset < total) {
        /* 断连/取消订阅可能发生在发送途中（控制台任务与 BT 任务并发）。 */
        if (!s_connected || !s_subscribed) {
            ESP_LOGW(TAG, "PP_SEND_ABORTED name=%s offset=%u reason=link_lost",
                     name, (unsigned)offset);
            ok = false;
            break;
        }
        /* 等拥塞解除，避免 send_indicate 连续失败。 */
        for (int waited = 0; s_congested && waited < 1000; waited += 10) {
            vTaskDelay(pdMS_TO_TICKS(10));
        }

        size_t n = total - offset;
        if (n > chunk) {
            n = chunk;
        }
        esp_err_t err = ESP_FAIL;
        for (int retry = 0; retry < 5; retry++) {
            err = esp_ble_gatts_send_indicate(s_gatts_if, s_conn_id, s_rx_handle,
                                              (uint32_t)n, (uint8_t *)(data + offset), false);
            if (err == ESP_OK) {
                break;
            }
            vTaskDelay(pdMS_TO_TICKS(20));
        }
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "PP_SEND_FAILED name=%s err=%s offset=%u",
                     name, esp_err_to_name(err), (unsigned)offset);
            ok = false;
            break;
        }
        offset += n;
        vTaskDelay(pdMS_TO_TICKS(15));
    }

    xSemaphoreGive(s_tx_lock);
    if (ok) {
        ESP_LOGI(TAG, "PP_SEND_DONE name=%s", name);
    }
    return ok;
}

/* ------------------------------------------------------------------------- */
/* Stage 7A: natural-language task text                                       */
/* ------------------------------------------------------------------------- */

/*
 * 构造并发送 TASK_TEXT 帧。文本只作为“任务描述”发给手机，设备端不做任何
 * 规划或动作执行。长度按 UTF-8 字节计算，上限 384，保证整帧仍 <= 512 字节，
 * 消息 ID 由设备生成并满足 Android TaskTextProtocol 的字符约束。
 */
static bool pp_send_task_text(const char *text)
{
    if (text == NULL) {
        pp_reject("EMPTY_TASK_TEXT", "task text is null");
        return false;
    }
    size_t text_len = strlen(text);
    if (text_len == 0) {
        pp_reject("EMPTY_TASK_TEXT", "task text is empty");
        return false;
    }
    if (text_len > MAX_TASK_TEXT_BYTES) {
        pp_reject("TEXT_TOO_LONG", "task text exceeds 384 UTF-8 bytes");
        return false;
    }
    for (size_t i = 0; i < text_len; i++) {
        if (text[i] == '\n' || text[i] == '\r') {
            pp_reject("MALFORMED_FRAME", "task text contains line break");
            return false;
        }
    }

    char message_id[MAX_MESSAGE_ID_CHARS + 1];
    uint32_t seq = __atomic_fetch_add(&s_task_seq, 1, __ATOMIC_RELAXED);
    int id_len = snprintf(message_id, sizeof(message_id), "TASK-%08lx-%04lx",
                          (unsigned long)(esp_timer_get_time() / 1000LL),
                          (unsigned long)(seq & 0xFFFFu));
    if (id_len <= 0 || (size_t)id_len >= sizeof(message_id)) {
        pp_reject("INVALID_MESSAGE_ID", "cannot generate message id");
        return false;
    }

    char frame[MAX_FRAME_BYTES + 1];
    int n = snprintf(frame, sizeof(frame), "PP1|COMMAND_TASK_TEXT|%s|%u|%s\n",
                     message_id, (unsigned)text_len, text);
    if (n < 0 || (size_t)n >= sizeof(frame)) {
        pp_reject("FRAME_TOO_LONG", "task frame exceeds 512 bytes");
        return false;
    }
    ESP_LOGI(TAG, "PP_TASK_TEXT id=%s text_bytes=%u frame_bytes=%d",
             message_id, (unsigned)text_len, n);
    return pp_send_frame("COMMAND_TASK_TEXT", frame);
}
/* ------------------------------------------------------------------------- */
/* Voice: ESP-SR command word -> BLE command frame                           */
/* ------------------------------------------------------------------------- */

#define PP_ASR_LISTEN_MS 5000

/* 监听期间用 LED 快闪提示“现在可以说话”，结束/失败后按连接状态复位。 */
static void pp_refresh_led(void)
{
    if (pp_asr_listening()) {
        pp_led_set_mode(PP_LED_LISTENING);
    } else if (s_connected && s_subscribed) {
        pp_led_set_mode(PP_LED_READY);
    } else if (s_connected) {
        pp_led_set_mode(PP_LED_CONNECTING);
    } else {
        pp_led_set_mode(PP_LED_ADVERTISING);
    }
}

static void pp_on_asr_result(int command_id, const char *text, float score)
{
    const char *name = pp_asr_command_symbol(command_id);
    const char *frame = NULL;
    switch (command_id) {
    case PP_ASR_CMD_WECHAT: frame = CMD_WECHAT; break;
    case PP_ASR_CMD_MAP:    frame = CMD_MAP;    break;
    case PP_ASR_CMD_BACK:   frame = CMD_BACK;   break;
    default: break;
    }
    if (name == NULL || frame == NULL) {
        ESP_LOGW(TAG, "PP_ASR_DISPATCH_UNKNOWN id=%d text=\"%s\"",
                 command_id, text != NULL ? text : "");
        return;
    }
    ESP_LOGI(TAG, "PP_ASR_DISPATCH id=%d name=%s text=\"%s\" score=%.2f",
             command_id, name, text != NULL ? text : "", score);
    /* 发送失败必须留痕：识别成功不等于命令已经送到手机。 */
    if (!pp_send_frame(name, frame)) {
        ESP_LOGW(TAG, "PP_ASR_SEND_FAILED name=%s connected=%d subscribed=%d",
                 name, s_connected ? 1 : 0, s_subscribed ? 1 : 0);
    }
}

static void pp_on_asr_done(bool matched)
{
    ESP_LOGI(TAG, "PP_ASR_WINDOW_END matched=%d", matched ? 1 : 0);
    pp_refresh_led();
}

static void pp_voice_listen(const char *source)
{
    esp_err_t err = pp_asr_listen_start(PP_ASR_LISTEN_MS, pp_on_asr_result, pp_on_asr_done);
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "PP_ASR_LISTEN_START source=%s ms=%d", source, PP_ASR_LISTEN_MS);
        pp_refresh_led();
    } else {
        ESP_LOGW(TAG, "PP_ASR_LISTEN_START_FAIL source=%s err=%s",
                 source, esp_err_to_name(err));
    }
}

/* ------------------------------------------------------------------------- */
/* Serial test input (NOT a physical button)                                 */
/* ------------------------------------------------------------------------- */

static void pp_print_menu(void)
{
    printf("\nPocketPilot BLE peripheral - serial test input\n");
    printf("  1 = COMMAND_OPEN_WECHAT\n");
    printf("  2 = COMMAND_OPEN_MAP (北京市海淀区中关村大街1号)\n");
    printf("  3 = COMMAND_BACK\n");
    printf("  4 = COMMAND_TASK_TEXT (serial test: 把刚收到的地址打开地图, NOT voice)\n");
    printf("  h = help, s = status, b = read BOOT pin (GPIO0), a = read ADC buttons\n");
    printf("  p = probe raw inputs for 30 s (press buttons while it runs)\n");
    printf("  m = record 5 s from the onboard microphone (watch PP_MIC lines)\n");
    printf("  v = listen 5 s for ONE Chinese command word via ESP-SR (speak right after pressing)\n");
    printf("  t = mic two-phase test: 3 s quiet, then 3 s speak/clap\n");
    printf("  w = stream 5 s of 16 kHz PCM over serial (PC builds a WAV)\n");
    printf("  l = live level watch for 90 s (clap/speak any time, watch PP_LEVEL)\n");
    printf("  r = dump 32 raw I2S words (check bit alignment)\n");
    printf("  g = mic DIN pin activity: count edges on GPIO2 while I2S runs\n");
    printf("  i = stereo raw dump: show which slot (L/R) carries the mic data\n");
    printf("  k = align scan: 4 combinations of bit shift / WS polarity\n");
    printf("NOTE: BOOT (GPIO0): click=MAP, double-click=WECHAT, long press=BACK.\n");
    printf("      Front panel: MENU=MAP, PLAY=voice listen (5 s), UP/DOWN=BACK.\n\n");
    fflush(stdout);
}

static void pp_console_task(void *arg)
{
    (void)arg;
    setvbuf(stdin, NULL, _IONBF, 0);
    pp_print_menu();

    while (true) {
        int c = fgetc(stdin);
        if (c == EOF) {
            clearerr(stdin);
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }
        switch (c) {
            /* I2S 正被语音识别占用时，诊断键先让路，避免抢通道。 */
            if (pp_asr_listening() &&
                (c == 'm' || c == 't' || c == 'w' || c == 'l' || c == 'r' ||
                 c == 'g' || c == 'i' || c == 'k')) {
                printf("ASR listening now; audio diagnostics are busy\r\n");
                fflush(stdout);
                continue;
            }
        case '1':
            pp_send_frame("COMMAND_OPEN_WECHAT", CMD_WECHAT);
            break;
        case '2':
            pp_send_frame("COMMAND_OPEN_MAP", CMD_MAP);
            break;
        case '3':
            pp_send_frame("COMMAND_BACK", CMD_BACK);
            break;
        case '4':
            /* 人工串口输入，用于验证协议；不是语音识别结果。 */
            pp_send_task_text(CMD_TASK_TEXT_DEMO);
            break;
        case 'h':
        case '?':
            pp_print_menu();
            break;
        case 'b':
            ESP_LOGI(TAG, "PP_GPIO0 level=%d (0=pressed, 1=released)",
                     pp_button_raw_level());
            break;
        case 'a':
            ESP_LOGI(TAG, "PP_ADC mv=%d button=%s (front panel, GPIO1)",
                     pp_button_adc_millivolts(),
                     pp_button_event_name(pp_button_adc_event()));
            break;
        case 'p':
            pp_button_probe_start(30);
            break;
        case 'v':
            pp_voice_listen("console");
            break;
        case 'm':
            pp_audio_probe_start(5);
            break;
        case 't':
            pp_audio_two_phase_start(3000);
            break;
        case 'w':
            pp_audio_stream_start(5);
            break;
        case 'l':
            pp_audio_level_watch_start(90);
            break;
        case 'r':
            pp_audio_dump_raw(32);
            break;
        case 'g':
            pp_audio_pin_activity();
            break;
        case 'i':
            pp_audio_stereo_dump(16);
            break;
        case 'k':
            pp_audio_align_scan();
            break;
        case 's':
            ESP_LOGI(TAG, "PP_STATUS service=%d connected=%d subscribed=%d mtu=%u "
                     "rx_rejects=%u results_ok=%u",
                     s_service_ready ? 1 : 0, s_connected ? 1 : 0,
                     s_subscribed ? 1 : 0, s_mtu,
                     (unsigned)s_rx_rejects, (unsigned)s_results_ok);
            break;
        default:
            if (c != '\r' && c != '\n') {
                printf("unknown key '%c' (h for help)\n", c);
                fflush(stdout);
            }
            break;
        }
    }
}

/* ------------------------------------------------------------------------- */
/* Physical button -> command                                               */
/* ------------------------------------------------------------------------- */

static void pp_on_button_event(pp_btn_event_t ev)
{
    const char *name = NULL;
    const char *frame = NULL;
    switch (ev) {
    case PP_BTN_SINGLE_CLICK:
        name = "COMMAND_OPEN_MAP";
        frame = CMD_MAP;
        break;
    case PP_BTN_DOUBLE_CLICK:
        name = "COMMAND_OPEN_WECHAT";
        frame = CMD_WECHAT;
        break;
    case PP_BTN_LONG_PRESS:
    case PP_BTN_DOWN:
    case PP_BTN_UP:
        name = "COMMAND_BACK";
        frame = CMD_BACK;
        break;
    case PP_BTN_MENU:
        name = "COMMAND_OPEN_MAP";
        frame = CMD_MAP;
        break;
    case PP_BTN_PLAY:
        /* PLAY 现在触发一次语音监听（push-to-talk），不再直接发固定命令。 */
        pp_voice_listen("PLAY");
        return;
    default:
        return;
    }
    if (!pp_send_frame(name, frame)) {
        /* 没连接/未订阅时明确记录，绝不把“按下去了”当成“已发送”。 */
        ESP_LOGW(TAG, "PP_BTN_SEND_FAILED event=%s name=%s connected=%d subscribed=%d",
                 pp_button_event_name(ev),
                 name, s_connected ? 1 : 0, s_subscribed ? 1 : 0);
    }
}

/* ------------------------------------------------------------------------- */
/* app_main                                                                  */
/* ------------------------------------------------------------------------- */

void app_main(void)
{
    pp_rx_assembler_init(&s_rx);
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT));

    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_bt_controller_init(&bt_cfg));
    ESP_ERROR_CHECK(esp_bt_controller_enable(ESP_BT_MODE_BLE));
    ESP_ERROR_CHECK(esp_bluedroid_init());
    ESP_ERROR_CHECK(esp_bluedroid_enable());

    ESP_ERROR_CHECK(esp_ble_gatts_register_callback(pp_gatts_event_handler));
    ESP_ERROR_CHECK(esp_ble_gap_register_callback(pp_gap_event_handler));
    ESP_ERROR_CHECK(esp_ble_gatts_app_register(PROFILE_APP_ID));
    ESP_ERROR_CHECK(esp_ble_gatt_set_local_mtu(TARGET_MTU));

    s_tx_lock = xSemaphoreCreateMutex();
    if (s_tx_lock == NULL) {
        ESP_LOGE(TAG, "PP_TX_LOCK_ALLOC_FAILED");
        return;
    }

    ESP_LOGI(TAG, "PP_BOOT name=%s mtu_target=%d", DEVICE_NAME, TARGET_MTU);

    /* 语音识别失败不得阻塞 BLE 主链路：把失败原因打清楚，其余功能照常。 */
    ESP_LOGI(TAG, "PP_ASR_INIT_BEGIN");
    esp_err_t asr_err = pp_asr_init();
    if (asr_err != ESP_OK) {
        ESP_LOGE(TAG, "PP_ASR_INIT_FAIL err=%s (voice unavailable, BLE text commands unaffected)",
                 esp_err_to_name(asr_err));
    }

    pp_button_start(pp_on_button_event);
    xTaskCreate(pp_console_task, "pp_console", 4096, NULL, 5, NULL);
}



