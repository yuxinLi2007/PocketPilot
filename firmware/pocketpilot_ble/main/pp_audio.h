/*
 * PocketPilot 板载麦克风采集 —— ESP32-S3-EYE 数字 MEMS (MSM261S4030H0)
 * ---------------------------------------------------------------------
 * 引脚来源（不猜测，取自 Espressif 官方 esp-bsp 的 esp32_s3_eye）：
 *   BSP_I2S_SCLK = GPIO41   BCLK
 *   BSP_I2S_LCLK = GPIO42   WS / LRCK
 *   BSP_I2S_DIN  = GPIO2    麦克风数据（只读，本板无麦克风回放通道）
 *   BSP_I2S_GPIO_CFG(): mclk = GPIO_NUM_NC, dout = GPIO_NUM_NC
 *
 * 该板麦克风是数字 I2S 器件，不经过 ES8311 编解码器，
 * 因此采集链路不需要 esp_codec_dev / es8311 任何额外依赖。
 *
 * 采样格式（对齐官方 BSP esp32_s3_eye_idf5.c）：
 *   48 kHz / 24 bit 数据 / 32 bit 槽 / 左槽 / 1 bit 移位 / 左对齐。
 *   ESP-IDF 对 24 bit 数据按 3 字节/样本组织 DMA；本模块按小端三字节
 *   还原有符号 24 bit，再右移 8 位得到 16 bit PCM。
 *   禁止把同一缓冲区按 int32_t 继续读取，否则样本边界会错位。
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

#define PP_AUDIO_I2S_RATE 48000
#define PP_AUDIO_SAMPLE_RATE 16000
#define PP_AUDIO_DECIM 3

typedef struct {
    uint32_t samples;   /* 参与统计的样本数 */
    int32_t rms;        /* 交流有效值（已去直流），16 bit 量纲 */
    int32_t peak;       /* 绝对值峰值，16 bit 量纲 */
    int32_t p2p;        /* 峰峰值 max - min */
    int32_t min;        /* 最小样本值 */
    int32_t max;        /* 最大样本值 */
    int32_t dc_offset;  /* 均值，反映直流偏置 */
    uint32_t clipped;   /* 触顶样本数（|x| >= 32000） */
} pp_audio_stats_t;

/* 初始化 I2S 接收通道。可重复调用。 */
esp_err_t pp_audio_init(void);

bool pp_audio_ready(void);

/* 读取 bytes 字节原始 I2S 数据（24 bit / 32 bit 槽 / 单声道 / 小端三字节）。
 * 供语音识别复用同一条 I2S 通道，避免两个模块同时 i2s_new_channel 抢通道。
 * 返回 ESP_ERR_INVALID_STATE 表示通道未初始化，ESP_ERR_TIMEOUT 表示未读满。 */
esp_err_t pp_audio_read_raw(uint8_t *buf, size_t bytes, uint32_t timeout_ms);
/* 阻塞采集 duration_ms 毫秒，每 200 ms 打印一行 PP_MIC 实时电平。 */
esp_err_t pp_audio_capture(int duration_ms, pp_audio_stats_t *out);

/* 诊断用：后台任务采集若干秒（0 表示停止），打印 PP_MIC 行并给出结论。 */
void pp_audio_probe_start(int seconds);

/* 诊断用：直接打印 count 个 I2S 原始 32 bit 字（不抽取、不缩放）。 */
esp_err_t pp_audio_dump_raw(int count);

/* 两段式实测：前 phase_ms 安静、后 phase_ms 出声。
 * 打印 PP_MIC_PHASE / PP_MIC_VERDICT，用来判断拿到的是真实 PCM 还是错位噪声。 */
esp_err_t pp_audio_two_phase(int phase_ms, pp_audio_stats_t *quiet, pp_audio_stats_t *loud);

/* 把 16 kHz 单声道 16 bit PCM 以二进制形式直接写串口，供 PC 侧拼成 WAV。
 * 先打印 PP_WAV_BEGIN，再流式输出音频字节，最后打印 PP_WAV_END。 */
esp_err_t pp_audio_stream_pcm16(int seconds);
/* 后台任务版本：安静段 + 出声段各 phase_ms 毫秒，打印 PP_MIC_VERDICT。 */
void pp_audio_two_phase_start(int phase_ms);

/* 后台任务版本：把 16 kHz PCM 以二进制写串口，PC 侧拼 WAV。 */
void pp_audio_stream_start(int seconds);

/* 后台任务版本：每 500 ms 打印一行 PP_LEVEL 实时电平，持续 seconds 秒。
 * 用于“用户随时出声”的现场验证，不需要预知发声时刻。 */
void pp_audio_level_watch_start(int seconds);

/* 诊断用：直接打印 count 个 I2S 原始 32 bit 字（不抽取、不缩放）。 */
esp_err_t pp_audio_dump_raw(int count);
/* 诊断用：I2S 运行时高速采样 DIN，统计 20 ms 内的电平翻转次数。 */
void pp_audio_pin_activity(void);
/* 诊断用：立体声逐对打印左右槽原始字，用于判断数据落在哪个声道。 */
void pp_audio_stereo_dump(int pairs);
/* 诊断用：四种位对齐/WS 极性组合实测对比，选出信号真正落在 PCM 上的那一种。 */
void pp_audio_align_scan(void);
