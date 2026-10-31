/*
 * PocketPilot 板载输入/指示 —— ESP32-S3-EYE 实体按键与 LED
 * ---------------------------------------------------------
 * 引脚/阈值来源（不猜测，均取自 Espressif 官方 esp-bsp / button 组件）：
 *   esp32_s3_eye BSP:
 *     BSP_BUTTON_BOOT_IO = GPIO0      底部 BOOT，低有效
 *     BSP_BUTTONS_IO     = GPIO1      MENU/PLAY/DOWN/UP 共用 ADC1_CH0
 *     BSP_LED_1_IO       = GPIO3      高电平点亮
 *   button 组件的 ADC 电压窗口：
 *     MENU 2310~2510 mV, PLAY 1880~2080 mV,
 *     DOWN 720~920 mV,   UP 280~480 mV.
 *
 * 当前实体输入映射：
 *   BOOT 单击        -> COMMAND_OPEN_MAP
 *   BOOT 双击        -> COMMAND_OPEN_WECHAT
 *   BOOT 长按 >=1.2s -> COMMAND_BACK
 *   正面 MENU        -> COMMAND_OPEN_MAP
 *   正面 PLAY        -> 开始一次 5 s 语音监听（命令词由 pp_asr.c 识别）
 *   正面 UP / DOWN   -> COMMAND_BACK
 */

#include "pp_button.h"

#include "driver/gpio.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define TAG "PocketPilotBtn"

#define BTN_GPIO            0       /* BSP_BUTTON_BOOT_IO，低有效 */
#define BTN_ACTIVE_LEVEL    0
#define LED_GPIO            3       /* BSP_LED_1_IO，高电平点亮 */
#define LED_ON_LEVEL        1

#define ADC_BTN_UNIT        ADC_UNIT_1
#define ADC_BTN_CHANNEL     ADC_CHANNEL_0   /* GPIO1 */
#define ADC_BTN_ATTEN       ADC_ATTEN_DB_12
#define ADC_BTN_BITWIDTH    ADC_BITWIDTH_DEFAULT
#define ADC_BTN_SAMPLES     8

#define TICK_MS             10
#define DEBOUNCE_MS         25      /* 机械键抖动 5~20 ms，留余量 */
#define SHORT_MIN_MS        40      /* 短于此时长视为抖动/误触，忽略 */
#define LONG_PRESS_MS       1200
#define DOUBLE_GAP_MS       350     /* 第一次松开后等待第二次按下的窗口 */

typedef enum {
    ST_IDLE = 0,
    ST_PRESSED,
    ST_WAIT_DOUBLE,
} btn_state_t;

static volatile int s_led_mode = PP_LED_ADVERTISING;

static btn_state_t s_state = ST_IDLE;
static int s_stable_level;
static int s_raw_level;
static uint32_t s_raw_change_ms;
static uint32_t s_press_ms;
static uint32_t s_wait_ms;
static bool s_long_fired;
static int s_clicks;

static adc_oneshot_unit_handle_t s_adc = NULL;
static adc_cali_handle_t s_adc_cali = NULL;
static bool s_adc_ready = false;
static volatile int s_adc_last_mv = -1;
static volatile int s_adc_last_raw = -1;
static volatile int s_adc_last_event = PP_BTN_NONE;
static volatile uint32_t s_probe_until_ms = 0;
static uint32_t s_probe_next_ms = 0;
static uint32_t s_probe_started_ms = 0;
static pp_btn_event_t s_adc_stable_event = PP_BTN_NONE;
static pp_btn_event_t s_adc_raw_event = PP_BTN_NONE;
static uint32_t s_adc_raw_change_ms;

const char *pp_button_event_name(pp_btn_event_t ev)
{
    switch (ev) {
    case PP_BTN_SINGLE_CLICK: return "SINGLE_CLICK";
    case PP_BTN_DOUBLE_CLICK: return "DOUBLE_CLICK";
    case PP_BTN_LONG_PRESS:   return "LONG_PRESS";
    case PP_BTN_MENU:         return "MENU";
    case PP_BTN_PLAY:         return "PLAY";
    case PP_BTN_DOWN:         return "DOWN";
    case PP_BTN_UP:           return "UP";
    default:                  return "NONE";
    }
}

static void pp_emit(pp_btn_cb_t cb, pp_btn_event_t ev)
{
    ESP_LOGI(TAG, "PP_BTN event=%s clicks=%d", pp_button_event_name(ev), s_clicks);
    if (cb != NULL) {
        cb(ev);
    }
}

static void pp_on_press(uint32_t now)
{
    s_clicks = (s_state == ST_WAIT_DOUBLE) ? 2 : 1;
    s_state = ST_PRESSED;
    s_press_ms = now;
    s_long_fired = false;
}

static void pp_on_release(uint32_t now)
{
    uint32_t held = now - s_press_ms;
    if (s_long_fired) {
        /* 长按事件已在按住时发过，松开不再补发。 */
        s_state = ST_IDLE;
        s_clicks = 0;
        return;
    }
    if (held < SHORT_MIN_MS) {
        ESP_LOGD(TAG, "PP_BTN ignored held=%ums", (unsigned)held);
        s_state = ST_IDLE;
        s_clicks = 0;
        return;
    }
    s_state = ST_WAIT_DOUBLE;
    s_wait_ms = now;
}

int pp_button_raw_level(void)
{
    return gpio_get_level(BTN_GPIO);
}

int pp_button_adc_millivolts(void)
{
    return s_adc_last_mv;
}

int pp_button_adc_raw_value(void)
{
    return s_adc_last_raw;
}

pp_btn_event_t pp_button_adc_event(void)
{
    return (pp_btn_event_t)s_adc_last_event;
}

void pp_button_probe_start(int seconds)
{
    uint32_t now = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS);
    if (seconds <= 0) {
        s_probe_until_ms = 0;
        ESP_LOGI(TAG, "PP_PROBE stopped");
        return;
    }
    s_probe_started_ms = now;
    s_probe_next_ms = now;
    s_probe_until_ms = now + (uint32_t)seconds * 1000u;
    ESP_LOGI(TAG, "PP_PROBE start seconds=%d", seconds);
}

static void pp_adc_init(void)
{
    adc_oneshot_unit_init_cfg_t unit_cfg = {
        .unit_id = ADC_BTN_UNIT,
    };
    if (adc_oneshot_new_unit(&unit_cfg, &s_adc) != ESP_OK) {
        ESP_LOGW(TAG, "PP_ADC_INIT_FAILED unit=%d", ADC_BTN_UNIT);
        return;
    }

    adc_oneshot_chan_cfg_t chan_cfg = {
        .atten = ADC_BTN_ATTEN,
        .bitwidth = ADC_BTN_BITWIDTH,
    };
    if (adc_oneshot_config_channel(s_adc, ADC_BTN_CHANNEL, &chan_cfg) != ESP_OK) {
        ESP_LOGW(TAG, "PP_ADC_CHANNEL_FAILED channel=%d", ADC_BTN_CHANNEL);
        return;
    }

#if ADC_CALI_SCHEME_CURVE_FITTING_SUPPORTED
    adc_cali_curve_fitting_config_t curve_cfg = {
        .unit_id = ADC_BTN_UNIT,
        .chan = ADC_BTN_CHANNEL,
        .atten = ADC_BTN_ATTEN,
        .bitwidth = ADC_BTN_BITWIDTH,
    };
    if (adc_cali_create_scheme_curve_fitting(&curve_cfg, &s_adc_cali) == ESP_OK) {
        ESP_LOGI(TAG, "PP_ADC_CALI scheme=curve");
    }
#endif

#if ADC_CALI_SCHEME_LINE_FITTING_SUPPORTED
    if (s_adc_cali == NULL) {
        adc_cali_line_fitting_config_t line_cfg = {
            .unit_id = ADC_BTN_UNIT,
            .atten = ADC_BTN_ATTEN,
            .bitwidth = ADC_BTN_BITWIDTH,
        };
        if (adc_cali_create_scheme_line_fitting(&line_cfg, &s_adc_cali) == ESP_OK) {
            ESP_LOGI(TAG, "PP_ADC_CALI scheme=line");
        }
    }
#endif

    if (s_adc_cali == NULL) {
        ESP_LOGW(TAG, "PP_ADC_CALI unavailable; front buttons disabled");
        return;
    }
    s_adc_ready = true;
    ESP_LOGI(TAG, "PP_ADC_READY gpio=1 unit=%d channel=%d", ADC_BTN_UNIT, ADC_BTN_CHANNEL);
}

static int pp_adc_read_mv(void)
{
    if (!s_adc_ready) {
        return -1;
    }
    int total = 0;
    int count = 0;
    for (int i = 0; i < ADC_BTN_SAMPLES; ++i) {
        int raw = 0;
        if (adc_oneshot_read(s_adc, ADC_BTN_CHANNEL, &raw) == ESP_OK) {
            total += raw;
            ++count;
        }
    }
    if (count == 0) {
        return -1;
    }
    int avg_raw = total / count;
    s_adc_last_raw = avg_raw;
    int mv = 0;
    if (adc_cali_raw_to_voltage(s_adc_cali, avg_raw, &mv) != ESP_OK) {
        return -1;
    }
    return mv;
}

static pp_btn_event_t pp_adc_event_from_mv(int mv)
{
    /* 窗口来自官方 button 组件对 ESP32-S3-EYE 的配置。 */
    if (mv >= 2310 && mv <= 2510) return PP_BTN_MENU;
    if (mv >= 1880 && mv <= 2080) return PP_BTN_PLAY;
    if (mv >= 720  && mv <= 920)  return PP_BTN_DOWN;
    if (mv >= 280  && mv <= 480)  return PP_BTN_UP;
    return PP_BTN_NONE;
}

void pp_led_set_mode(pp_led_mode_t mode)
{
    s_led_mode = (int)mode;
}

static void pp_drive_led(uint32_t now)
{
    bool on;
    switch ((pp_led_mode_t)s_led_mode) {
    case PP_LED_READY:
        on = true;
        break;
    case PP_LED_LISTENING:
        on = ((now / 50) % 2) == 0;
        break;
    case PP_LED_CONNECTING:
        on = ((now / 120) % 2) == 0;
        break;
    case PP_LED_ADVERTISING:
    default:
        on = ((now / 500) % 2) == 0;
        break;
    }
    gpio_set_level(LED_GPIO, on ? LED_ON_LEVEL : !LED_ON_LEVEL);
}

static void pp_button_task(void *arg)
{
    pp_btn_cb_t cb = (pp_btn_cb_t)arg;

    gpio_config_t btn_cfg = {
        .pin_bit_mask = 1ULL << BTN_GPIO,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,      /* 板上有外部上拉，这里再兜一层 */
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,        /* 轮询消抖，不用中断 */
    };
    gpio_config(&btn_cfg);

    gpio_config_t led_cfg = {
        .pin_bit_mask = 1ULL << LED_GPIO,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&led_cfg);
    gpio_set_level(LED_GPIO, !LED_ON_LEVEL);

    pp_adc_init();

    s_raw_level = gpio_get_level(BTN_GPIO);
    s_stable_level = s_raw_level;
    s_raw_change_ms = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS);
    s_adc_raw_change_ms = s_raw_change_ms;

    ESP_LOGI(TAG, "PP_BTN_READY boot_gpio=%d adc_gpio=1 led_gpio=%d "
             "boot=click_map/double_wechat/long_back front=menu_map/play_voice/updown_back",
             BTN_GPIO, LED_GPIO);

    while (true) {
        uint32_t now = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS);

        int raw = gpio_get_level(BTN_GPIO);
        if (raw != s_raw_level) {
            /* 每次电平跳变都留痕：即使消抖/手势逻辑有问题，也能分清
             * 是“电信号没进来”还是“进来了没被识别”。 */
            ESP_LOGI(TAG, "PP_BTN_RAW level=%d (0=pressed)", raw);
            s_raw_level = raw;
            s_raw_change_ms = now;
        }
        if (s_raw_level != s_stable_level && (now - s_raw_change_ms) >= DEBOUNCE_MS) {
            s_stable_level = s_raw_level;
            if (s_stable_level == BTN_ACTIVE_LEVEL) {
                pp_on_press(now);
            } else {
                pp_on_release(now);
            }
        }

        if (s_state == ST_PRESSED && !s_long_fired && (now - s_press_ms) >= LONG_PRESS_MS) {
            s_long_fired = true;
            pp_emit(cb, PP_BTN_LONG_PRESS);
        }
        if (s_state == ST_WAIT_DOUBLE && (now - s_wait_ms) >= DOUBLE_GAP_MS) {
            pp_emit(cb, s_clicks >= 2 ? PP_BTN_DOUBLE_CLICK : PP_BTN_SINGLE_CLICK);
            s_clicks = 0;
            s_state = ST_IDLE;
        }

        int mv = pp_adc_read_mv();
        s_adc_last_mv = mv;
        pp_btn_event_t adc_now = pp_adc_event_from_mv(mv);
        s_adc_last_event = adc_now;
        if (adc_now != s_adc_raw_event) {
            ESP_LOGI(TAG, "PP_ADC_RAW mv=%d button=%s", mv, pp_button_event_name(adc_now));
            s_adc_raw_event = adc_now;
            s_adc_raw_change_ms = now;
        }
        if (s_adc_raw_event != s_adc_stable_event &&
            (now - s_adc_raw_change_ms) >= DEBOUNCE_MS) {
            s_adc_stable_event = s_adc_raw_event;
            if (s_adc_stable_event != PP_BTN_NONE) {
                ESP_LOGI(TAG, "PP_BTN event=%s source=front_panel",
                         pp_button_event_name(s_adc_stable_event));
                if (cb != NULL) {
                    cb(s_adc_stable_event);
                }
            }
        }

        if (s_probe_until_ms != 0 && (int32_t)(now - s_probe_until_ms) >= 0) {
            s_probe_until_ms = 0;
            ESP_LOGI(TAG, "PP_PROBE done elapsed=%ums",
                     (unsigned)(now - s_probe_started_ms));
        } else if (s_probe_until_ms != 0 && (int32_t)(now - s_probe_next_ms) >= 0) {
            s_probe_next_ms = now + 100;   /* 10 Hz 采样打印 */
            ESP_LOGI(TAG, "PP_PROBE t=%ums gpio0=%d mv=%d raw=%d band=%s",
                     (unsigned)(now - s_probe_started_ms),
                     gpio_get_level(BTN_GPIO), mv, s_adc_last_raw,
                     pp_button_event_name(adc_now));
        }

        pp_drive_led(now);
        vTaskDelay(pdMS_TO_TICKS(TICK_MS));
    }
}

void pp_button_start(pp_btn_cb_t cb)
{
    /* 优先级低于控制台任务：按键事件发不出去时不应该抢占 BLE 栈的 CPU。 */
    xTaskCreate(pp_button_task, "pp_button", 4096, (void *)cb, 4, NULL);
}
