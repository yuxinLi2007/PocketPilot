/*
 * PocketPilot T0 - BLE HID probe
 *
 * Board: ESP32-S3-EYE
 * Host: Android phone connected over BLE (no USB cable between board and phone)
 *
 * Serial commands (USB cable to the computer only):
 *   a = volume up        z = volume down
 *   p = play/pause       n = next track
 *   b = previous track   m = mute
 *   e = Esc              r = right arrow
 *   ? = help
 *
 * This is a T0 probe, not the final product firmware.
 */

#include <stdio.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <inttypes.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_err.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "esp_bt.h"
#include "esp_hidd.h"
#include "esp_hid_gap.h"

static const char *TAG = "PP_T0";

#define PP_DEVICE_NAME       "PocketPilot T0"
#define PP_REPORT_ID_KEYBOARD 1
#define PP_REPORT_ID_CONSUMER 2
#define PP_KEYBOARD_REPORT_LEN 8
#define PP_CONSUMER_REPORT_LEN 2
#define PP_KEY_PRESS_MS       40

/* Consumer Page usages from the USB HID Usage Tables. */
#define PP_CC_VOLUME_UP       0x00E9
#define PP_CC_VOLUME_DOWN     0x00EA
#define PP_CC_MUTE            0x00E2
#define PP_CC_PLAY_PAUSE      0x00CD
#define PP_CC_SCAN_NEXT       0x00B5
#define PP_CC_SCAN_PREV       0x00B6

/* Keyboard Page usages. */
#define PP_KEY_ESC            0x29
#define PP_KEY_RIGHT_ARROW    0x4F

/*
 * One composite report map containing:
 *   Report ID 1: standard 8-byte boot-style keyboard input report
 *   Report ID 2: 16-bit consumer-control usage array
 *
 * Keeping both in one report map lets Android expose one HID device with
 * both keyboard and media controls.
 */
static const uint8_t pp_report_map[] = {
    /* Keyboard, Report ID 1 */
    0x05, 0x01,       /* Usage Page (Generic Desktop) */
    0x09, 0x06,       /* Usage (Keyboard) */
    0xA1, 0x01,       /* Collection (Application) */
    0x85, 0x01,       /*   Report ID (1) */
    0x05, 0x07,       /*   Usage Page (Keyboard/Keypad) */
    0x19, 0xE0,       /*   Usage Minimum (Left Control) */
    0x29, 0xE7,       /*   Usage Maximum (Right GUI) */
    0x15, 0x00,       /*   Logical Minimum (0) */
    0x25, 0x01,       /*   Logical Maximum (1) */
    0x75, 0x01,       /*   Report Size (1) */
    0x95, 0x08,       /*   Report Count (8) */
    0x81, 0x02,       /*   Input (Data, Variable, Absolute) */
    0x95, 0x01,       /*   Report Count (1) */
    0x75, 0x08,       /*   Report Size (8) */
    0x81, 0x03,       /*   Input (Constant) */
    0x95, 0x05,       /*   Report Count (5) */
    0x75, 0x01,       /*   Report Size (1) */
    0x05, 0x08,       /*   Usage Page (LEDs) */
    0x19, 0x01,       /*   Usage Minimum (Num Lock) */
    0x29, 0x05,       /*   Usage Maximum (Kana) */
    0x91, 0x02,       /*   Output (Data, Variable, Absolute) */
    0x95, 0x01,       /*   Report Count (1) */
    0x75, 0x03,       /*   Report Size (3) */
    0x91, 0x03,       /*   Output (Constant) */
    0x95, 0x06,       /*   Report Count (6) */
    0x75, 0x08,       /*   Report Size (8) */
    0x15, 0x00,       /*   Logical Minimum (0) */
    0x25, 0x65,       /*   Logical Maximum (101) */
    0x05, 0x07,       /*   Usage Page (Keyboard/Keypad) */
    0x19, 0x00,       /*   Usage Minimum (0) */
    0x29, 0x65,       /*   Usage Maximum (101) */
    0x81, 0x00,       /*   Input (Data, Array) */
    0xC0,             /* End Collection */

    /* Consumer Control, Report ID 2 */
    0x05, 0x0C,       /* Usage Page (Consumer) */
    0x09, 0x01,       /* Usage (Consumer Control) */
    0xA1, 0x01,       /* Collection (Application) */
    0x85, 0x02,       /*   Report ID (2) */
    0x15, 0x00,       /*   Logical Minimum (0) */
    0x26, 0xFF, 0x03, /*   Logical Maximum (1023) */
    0x19, 0x00,       /*   Usage Minimum (0) */
    0x2A, 0xFF, 0x03, /*   Usage Maximum (1023) */
    0x75, 0x10,       /*   Report Size (16) */
    0x95, 0x01,       /*   Report Count (1) */
    0x81, 0x00,       /*   Input (Data, Array) */
    0xC0              /* End Collection */
};

static const esp_hid_raw_report_map_t pp_report_maps[] = {
    {
        .data = pp_report_map,
        .len = sizeof(pp_report_map),
    },
};

static esp_hid_device_config_t pp_hid_config = {
    .vendor_id = 0x16C0,
    .product_id = 0x05DF,
    .version = 0x0100,
    .device_name = PP_DEVICE_NAME,
    .manufacturer_name = "PocketPilot",
    .serial_number = "PP-T0-0001",
    .report_maps = (esp_hid_raw_report_map_t *)pp_report_maps,
    .report_maps_len = 1,
};

static esp_hidd_dev_t *s_hid_dev;
static volatile bool s_connected;

static uint32_t pp_now_ms(void)
{
    return (uint32_t)(esp_timer_get_time() / 1000ULL);
}

static void pp_log_event(const char *phase, const char *action,
                         int report_id, esp_err_t status)
{
    printf("PP_EVT,ts_ms=%" PRIu32 ",phase=%s,action=%s,report_id=%d,connected=%d,status=%s,code=%d\n",
           pp_now_ms(), phase, action, report_id, s_connected ? 1 : 0,
           status == ESP_OK ? "OK" : "FAIL", (int)status);
}

static bool pp_send_consumer(uint16_t usage, const char *action)
{
    if (s_hid_dev == NULL || !s_connected) {
        pp_log_event("press", action, PP_REPORT_ID_CONSUMER, ESP_ERR_INVALID_STATE);
        return false;
    }

    uint8_t press[PP_CONSUMER_REPORT_LEN] = {
        (uint8_t)(usage & 0xFF),
        (uint8_t)((usage >> 8) & 0xFF),
    };
    uint8_t release[PP_CONSUMER_REPORT_LEN] = {0};

    esp_err_t err = esp_hidd_dev_input_set(s_hid_dev, 0, PP_REPORT_ID_CONSUMER,
                                           press, sizeof(press));
    pp_log_event("press", action, PP_REPORT_ID_CONSUMER, err);
    if (err != ESP_OK) {
        return false;
    }

    vTaskDelay(pdMS_TO_TICKS(PP_KEY_PRESS_MS));

    err = esp_hidd_dev_input_set(s_hid_dev, 0, PP_REPORT_ID_CONSUMER,
                                 release, sizeof(release));
    pp_log_event("release", action, PP_REPORT_ID_CONSUMER, err);
    return err == ESP_OK;
}

static bool pp_send_key(uint8_t keycode, const char *action)
{
    if (s_hid_dev == NULL || !s_connected) {
        pp_log_event("press", action, PP_REPORT_ID_KEYBOARD, ESP_ERR_INVALID_STATE);
        return false;
    }

    uint8_t press[PP_KEYBOARD_REPORT_LEN] = {0};
    uint8_t release[PP_KEYBOARD_REPORT_LEN] = {0};
    press[0] = 0;       /* modifiers */
    press[1] = 0;       /* reserved */
    press[2] = keycode; /* first key slot */

    esp_err_t err = esp_hidd_dev_input_set(s_hid_dev, 0, PP_REPORT_ID_KEYBOARD,
                                           press, sizeof(press));
    pp_log_event("press", action, PP_REPORT_ID_KEYBOARD, err);
    if (err != ESP_OK) {
        return false;
    }

    vTaskDelay(pdMS_TO_TICKS(PP_KEY_PRESS_MS));

    err = esp_hidd_dev_input_set(s_hid_dev, 0, PP_REPORT_ID_KEYBOARD,
                                 release, sizeof(release));
    pp_log_event("release", action, PP_REPORT_ID_KEYBOARD, err);
    return err == ESP_OK;
}

static void pp_print_help(void)
{
    printf("\n");
    printf("PocketPilot T0 serial commands\n");
    printf("  a : volume up       z : volume down\n");
    printf("  p : play/pause      n : next track\n");
    printf("  b : previous track  m : mute\n");
    printf("  e : Esc             r : right arrow\n");
    printf("  ? : this help\n");
    printf("Machine-readable results start with PP_EVT.\n\n");
}

static void pp_serial_task(void *arg)
{
    (void)arg;
    /* Single-key input: do not wait for Enter. */
    setvbuf(stdin, NULL, _IONBF, 0);
    pp_print_help();

    while (true) {
        int c = fgetc(stdin);
        if (c == EOF) {
            /*
             * The default console VFS polls the UART FIFO: when no byte is
             * pending, read() returns 0 and stdio latches EOF. Without
             * clearerr() every later fgetc() would return EOF immediately and
             * serial commands would silently stop working forever.
             */
            clearerr(stdin);
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }
        switch (c) {
        case 'a':
            pp_send_consumer(PP_CC_VOLUME_UP, "VOLUME_UP");
            break;
        case 'z':
            pp_send_consumer(PP_CC_VOLUME_DOWN, "VOLUME_DOWN");
            break;
        case 'p':
            pp_send_consumer(PP_CC_PLAY_PAUSE, "PLAY_PAUSE");
            break;
        case 'n':
            pp_send_consumer(PP_CC_SCAN_NEXT, "NEXT_TRACK");
            break;
        case 'b':
            pp_send_consumer(PP_CC_SCAN_PREV, "PREV_TRACK");
            break;
        case 'm':
            pp_send_consumer(PP_CC_MUTE, "MUTE");
            break;
        case 'e':
            pp_send_key(PP_KEY_ESC, "ESC");
            break;
        case 'r':
            pp_send_key(PP_KEY_RIGHT_ARROW, "RIGHT_ARROW");
            break;
        case '?':
            pp_print_help();
            break;
        default:
            break;
        }
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

/*
 * The upstream esp_hid_gap.c calls this hook when BLE authentication or the
 * connection completes. In the Espressif example it starts a demo task that
 * spams volume up/down every 2 seconds forever. We deliberately keep it a
 * no-op: PocketPilot only sends a key when the operator asks for one.
 */
void ble_hid_task_start_up(void)
{
    printf("PP_STATE,ts_ms=%" PRIu32 ",state=HID_READY\n", pp_now_ms());
}
static void pp_hid_event_callback(void *handler_args, esp_event_base_t base,
                                  int32_t id, void *event_data)
{
    (void)handler_args;
    (void)base;
    esp_hidd_event_t event = (esp_hidd_event_t)id;
    esp_hidd_event_data_t *param = (esp_hidd_event_data_t *)event_data;

    switch (event) {
    case ESP_HIDD_START_EVENT:
        printf("PP_STATE,ts_ms=%" PRIu32 ",state=HID_START\n", pp_now_ms());
        esp_hid_ble_gap_adv_start();
        break;
    case ESP_HIDD_CONNECT_EVENT:
        s_connected = true;
        printf("PP_STATE,ts_ms=%" PRIu32 ",state=HID_CONNECTED\n", pp_now_ms());
        break;
    case ESP_HIDD_CONTROL_EVENT:
        printf("PP_STATE,ts_ms=%" PRIu32 ",state=HID_CONTROL,value=%u\n",
               pp_now_ms(), (unsigned)param->control.control);
        break;
    case ESP_HIDD_PROTOCOL_MODE_EVENT:
        printf("PP_STATE,ts_ms=%" PRIu32 ",state=HID_PROTOCOL_MODE,value=%u\n",
               pp_now_ms(), (unsigned)param->protocol_mode.protocol_mode);
        break;
    case ESP_HIDD_DISCONNECT_EVENT:
        s_connected = false;
        printf("PP_STATE,ts_ms=%" PRIu32 ",state=HID_DISCONNECTED,reason=%d\n",
               pp_now_ms(), param->disconnect.reason);
        esp_hid_ble_gap_adv_start();
        break;
    case ESP_HIDD_STOP_EVENT:
        s_connected = false;
        printf("PP_STATE,ts_ms=%" PRIu32 ",state=HID_STOP\n", pp_now_ms());
        break;
    default:
        break;
    }
}

void app_main(void)
{
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    ESP_LOGI(TAG, "starting %s", PP_DEVICE_NAME);
    ESP_ERROR_CHECK(esp_hid_gap_init(HID_DEV_MODE));
    ESP_ERROR_CHECK(esp_hid_ble_gap_adv_init(ESP_HID_APPEARANCE_KEYBOARD,
                                             pp_hid_config.device_name));
    ESP_ERROR_CHECK(esp_ble_gatts_register_callback(esp_hidd_gatts_event_handler));
    ESP_ERROR_CHECK(esp_hidd_dev_init(&pp_hid_config, ESP_HID_TRANSPORT_BLE,
                                      pp_hid_event_callback, &s_hid_dev));

    xTaskCreate(pp_serial_task, "pp_serial", 4096, NULL, 5, NULL);
    ESP_LOGI(TAG, "ready; pair with '%s' over Bluetooth LE", PP_DEVICE_NAME);
}