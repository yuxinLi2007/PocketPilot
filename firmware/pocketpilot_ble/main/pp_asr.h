/*
 * PocketPilot 语音命令识别（ESP-SR / MultiNet7 中文）
 * ---------------------------------------------------------------------
 * 链路：板载 MEMS 麦克风 I2S(48 kHz / 24 bit / 单声道左槽)
 *      -> 3:1 抽取 -> 16 kHz int16 -> AFE(M, 无回采/AEC)
 *      -> MultiNet7 中文命令词 -> 命令 ID -> 回调交给 BLE 层
 *
 * 命令词以模型原生格式（汉字）书写，启动时用
 * esp_mn_iface_t::check_speech_command() 实测校验；若模型拒绝汉字，
 * 再回退到拼音写法，并把实际采用的一种打进串口日志。两种格式都不
 * 命中时 pp_asr_init() 返回错误，不会静默降级成“假识别”。
 *
 * 设计约束：
 *   - 单次监听（push-to-talk），不做常驻唤醒词；
 *   - 本模块不直接发 BLE：识别结果通过回调交给调用方，协议层解耦；
 *   - I2S 通道由 pp_audio 独占（pp_audio_init / pp_audio_read_raw），
 *     这里不再自己 i2s_new_channel，避免两个模块同时持有 I2S_NUM_0；
 *   - 命令 ID 只在固件内部使用，线上协议仍然是文本命令名
 *     （COMMAND_OPEN_*），两者不要混为一谈。
 */
#pragma once

#include <stdbool.h>

#include "esp_err.h"

/* 固件内部命令 ID。编号与串口测试菜单 1/2/3 保持一致，便于对照日志。 */
#define PP_ASR_CMD_WECHAT 1
#define PP_ASR_CMD_MAP    2
#define PP_ASR_CMD_BACK   3

/* 识别回调：command_id、模型输出的命令字符串、模型原始分数。 */
/* score 是 MultiNet 的原始分数，未归一化，只能用于横向比较，不能当百分比展示。 */
typedef void (*pp_asr_cb_t)(int command_id, const char *text, float score);

/* 结束回调：监听窗口结束（true = 命中命令，false = 未命中/出错）。 */
typedef void (*pp_asr_done_cb_t)(bool matched);

/* 加载 model 分区并创建 AFE + MultiNet7。可重复调用。 */
esp_err_t pp_asr_init(void);

bool pp_asr_ready(void);

/* 后台任务：listen_ms 毫秒内监听一次命令词，命中后回调。
 * 同一时刻只允许一个监听任务，重复调用返回 ESP_ERR_INVALID_STATE。 */
esp_err_t pp_asr_listen_start(int listen_ms, pp_asr_cb_t cb, pp_asr_done_cb_t done);

bool pp_asr_listening(void);

/* 最近一次识别到的命令字符串，无结果时返回空串。 */
const char *pp_asr_last_text(void);

/* 命令 ID 到线上协议名的映射，未知 ID 返回 NULL。 */
const char *pp_asr_command_symbol(int command_id);