/*
 * PocketPilot 板载输入/指示 —— ESP32-S3-EYE 实体按键与 LED
 * ---------------------------------------------------------
 * 引脚来源（不猜测，均取自 Espressif 官方 esp-bsp 的 esp32_s3_eye BSP）：
 *   BSP_BUTTON_BOOT_IO = GPIO0   底部 BOOT，低有效
 *   BSP_BUTTONS_IO     = GPIO1   MENU/PLAY/DOWN/UP 共用 ADC1_CH0
 *   BSP_LED_1_IO       = GPIO3   单色 LED，高电平点亮
 *
 * 按键映射（语音已接入：PLAY 触发一次命令词监听，其余键仍是固定命令）：
 *   BOOT 单击        -> COMMAND_OPEN_MAP
 *   BOOT 双击        -> COMMAND_OPEN_WECHAT
 *   BOOT 长按 >=1.2s -> COMMAND_BACK
 *   正面 MENU        -> COMMAND_OPEN_MAP
 *   正面 PLAY        -> 开始一次 5 s 语音监听（ESP-SR 命令词 -> BLE）
 *   正面 UP / DOWN   -> COMMAND_BACK
 */
#pragma once

#include <stdbool.h>

typedef enum {
    PP_BTN_NONE = 0,
    PP_BTN_SINGLE_CLICK,
    PP_BTN_DOUBLE_CLICK,
    PP_BTN_LONG_PRESS,
    PP_BTN_MENU,
    PP_BTN_PLAY,
    PP_BTN_DOWN,
    PP_BTN_UP,
} pp_btn_event_t;

typedef enum {
    PP_LED_ADVERTISING = 0, /* 未连接：1 Hz 慢闪 */
    PP_LED_CONNECTING,      /* 已连接但未订阅通知：8 Hz 快闪 */
    PP_LED_READY,           /* 已订阅，可发命令：常亮 */
    PP_LED_LISTENING,       /* 正在监听语音：10 Hz 极快闪 */
} pp_led_mode_t;

typedef void (*pp_btn_cb_t)(pp_btn_event_t event);

/* 启动按键扫描 + LED 指示任务（10 ms 周期，回调在该任务上下文执行）。 */
void pp_button_start(pp_btn_cb_t cb);

/* 任意任务可调用；只写一个 volatile 变量，由按键任务驱动 GPIO。 */
void pp_led_set_mode(pp_led_mode_t mode);

/* 诊断用：读取 BOOT 键当前的原始电平（0 = 按住，1 = 松开）。 */
int pp_button_raw_level(void);

/* 诊断用：按键任务最近一次采样的正面按键 ADC 电压/原始码值与识别结果。 */
int pp_button_adc_millivolts(void);
int pp_button_adc_raw_value(void);
pp_btn_event_t pp_button_adc_event(void);
const char *pp_button_event_name(pp_btn_event_t ev);

/* 诊断用：在串口上连续打印 GPIO0 与正面 ADC 的原始采样，
 * 用于确认“按下的电信号到底有没有进来”。seconds <= 0 表示停止。 */
void pp_button_probe_start(int seconds);
