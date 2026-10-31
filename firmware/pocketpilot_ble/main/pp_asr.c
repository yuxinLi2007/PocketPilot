/*
 * PocketPilot 语音命令识别实现（ESP-SR / MultiNet7 中文）
 * ---------------------------------------------------------------------
 * 只做一件事：在一段有限时间里听清 3 个固定命令词，命中就回调。
 * 不做唤醒词、不做云端、不做多步任务——那些属于后续里程碑。
 */

#include "pp_asr.h"

#include <stdio.h>
#include <string.h>

#include "esp_afe_sr_iface.h"
#include "esp_afe_sr_models.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_mn_iface.h"
#include "esp_mn_models.h"
#include "esp_mn_speech_commands.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "model_path.h"

#include "pp_audio.h"

#define TAG "PocketPilotASR"

/* 采集参数与 pp_audio 保持一致：48 kHz / 24 bit / 单声道左槽 / 3 字节一帧。 */
#define PP_ASR_BYTES        3
#define PP_ASR_DECIM        PP_AUDIO_DECIM
/* MultiNet 单次会话超时（模型内部计时），比按键监听窗口长即可。 */
#define PP_ASR_MN_TIMEOUT   6000
/* 监听窗口下限，太短会出现“还没说完就结束”的假阴性。 */
#define PP_ASR_MIN_MS       1500

typedef struct {
    int id;
    const char *native;   /* 模型原生格式：汉字 */
    const char *fallback; /* 回退格式：拼音（不带声调） */
} pp_asr_phrase_t;

static const pp_asr_phrase_t k_phrases[] = {
    { PP_ASR_CMD_WECHAT, "打开微信", "da kai wei xin" },
    { PP_ASR_CMD_MAP,    "打开地图", "da kai di tu" },
    { PP_ASR_CMD_BACK,   "返回",     "fan hui" },
};

#define PP_ASR_PHRASE_NUM (sizeof(k_phrases) / sizeof(k_phrases[0]))

typedef struct {
    int listen_ms;
    pp_asr_cb_t cb;
    pp_asr_done_cb_t done;
} pp_asr_req_t;

static void pp_asr_task(void *arg);

static srmodel_list_t *s_models = NULL;
static esp_mn_iface_t *s_mn = NULL;
static model_iface_data_t *s_mn_data = NULL;
static const esp_afe_sr_iface_t *s_afe = NULL;
static esp_afe_sr_data_t *s_afe_data = NULL;
static bool s_ready = false;
static volatile bool s_listening = false;
static char s_last_text[256] = {0};

const char *pp_asr_command_symbol(int command_id)
{
    switch (command_id) {
    case PP_ASR_CMD_WECHAT:
        return "COMMAND_OPEN_WECHAT";
    case PP_ASR_CMD_MAP:
        return "COMMAND_OPEN_MAP";
    case PP_ASR_CMD_BACK:
        return "COMMAND_BACK";
    default:
        return NULL;
    }
}

/* 24 bit 小端 -> int16（右移 8 位去掉低 8 位量化噪声）。 */
static inline int16_t pp_asr_s16(const uint8_t *p)
{
    uint32_t v = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16);
    if (v & 0x00800000u) {
        v |= 0xff000000u;
    }
    return (int16_t)(((int32_t)v) >> 8);
}

/* 用模型自己的切词检查挑格式，而不是把“汉字还是拼音”当假设写死。 */
static int pp_asr_pick_format(void)
{
    int native_ok = 0;
    int fallback_ok = 0;
    for (size_t i = 0; i < PP_ASR_PHRASE_NUM; i++) {
        int a = s_mn->check_speech_command(s_mn_data, k_phrases[i].native);
        int b = s_mn->check_speech_command(s_mn_data, k_phrases[i].fallback);
        ESP_LOGI(TAG, "PP_ASR_FMT id=%d native=\"%s\"=%d fallback=\"%s\"=%d",
                 k_phrases[i].id, k_phrases[i].native, a,
                 k_phrases[i].fallback, b);
        if (a > 0) {
            native_ok++;
        }
        if (b > 0) {
            fallback_ok++;
        }
    }

    if (native_ok == (int)PP_ASR_PHRASE_NUM) {
        ESP_LOGI(TAG, "PP_ASR_FMT_PICK native ok=%d/%d", native_ok, (int)PP_ASR_PHRASE_NUM);
        return 0;
    }
    if (fallback_ok == (int)PP_ASR_PHRASE_NUM) {
        ESP_LOGW(TAG, "PP_ASR_FMT_PICK fallback ok=%d/%d（模型不接受汉字）",
                 fallback_ok, (int)PP_ASR_PHRASE_NUM);
        return 1;
    }
    ESP_LOGE(TAG, "PP_ASR_FMT_PICK 两种格式都不完整 native=%d fallback=%d total=%d",
             native_ok, fallback_ok, (int)PP_ASR_PHRASE_NUM);
    return native_ok >= fallback_ok ? 0 : 1;
}

static esp_err_t pp_asr_add_commands(int use_fallback)
{
    esp_mn_commands_clear();
    for (size_t i = 0; i < PP_ASR_PHRASE_NUM; i++) {
        const char *phrase = use_fallback ? k_phrases[i].fallback : k_phrases[i].native;
        esp_err_t err = esp_mn_commands_add(k_phrases[i].id, phrase);
        ESP_LOGI(TAG, "PP_ASR_CMD_ADD id=%d phrase=\"%s\" err=%s",
                 k_phrases[i].id, phrase, esp_err_to_name(err));
    }

    /* update() 返回 NULL 才算全部被模型接受；否则逐条打印被拒绝的命令词。 */
    esp_mn_error_t *errs = esp_mn_commands_update();
    if (errs != NULL && errs->num > 0) {
        for (int i = 0; i < errs->num; i++) {
            ESP_LOGE(TAG, "PP_ASR_CMD_REJECT \"%s\"",
                     errs->phrases[i] && errs->phrases[i]->string ? errs->phrases[i]->string : "?");
        }
        return ESP_ERR_INVALID_ARG;
    }

    esp_mn_active_commands_print();
    return ESP_OK;
}

esp_err_t pp_asr_init(void)
{
    if (s_ready) {
        return ESP_OK;
    }

    s_models = esp_srmodel_init("model");
    if (s_models == NULL || s_models->num <= 0) {
        ESP_LOGE(TAG, "PP_ASR_MODEL_FAIL 没有在 model 分区找到模型（检查 partitions.csv 与烧录内容）");
        return ESP_ERR_NOT_FOUND;
    }
    for (int i = 0; i < s_models->num; i++) {
        ESP_LOGI(TAG, "PP_ASR_MODEL[%d]=%s", i, s_models->model_name[i]);
    }

    char *mn_name = esp_srmodel_filter(s_models, ESP_MN_PREFIX, ESP_MN_CHINESE);
    if (mn_name == NULL) {
        ESP_LOGE(TAG, "PP_ASR_MODEL_FAIL 没有中文 MultiNet 模型");
        return ESP_ERR_NOT_FOUND;
    }

    s_mn = esp_mn_handle_from_name(mn_name);
    if (s_mn == NULL) {
        ESP_LOGE(TAG, "PP_ASR_MODEL_FAIL esp_mn_handle_from_name(%s)", mn_name);
        return ESP_ERR_NOT_FOUND;
    }

    s_mn_data = s_mn->create(mn_name, PP_ASR_MN_TIMEOUT);
    if (s_mn_data == NULL) {
        ESP_LOGE(TAG, "PP_ASR_MODEL_FAIL multinet create(%s)", mn_name);
        return ESP_ERR_NO_MEM;
    }

    if (esp_mn_commands_alloc(s_mn, s_mn_data) != ESP_OK) {
        ESP_LOGE(TAG, "PP_ASR_CMD_ALLOC_FAIL");
        return ESP_ERR_NO_MEM;
    }

    int use_fallback = pp_asr_pick_format();
    esp_err_t cmd_err = pp_asr_add_commands(use_fallback);
    if (cmd_err != ESP_OK) {
        ESP_LOGW(TAG, "PP_ASR_CMD_RETRY 换用另一种书写格式重试");
        use_fallback = !use_fallback;
        cmd_err = pp_asr_add_commands(use_fallback);
    }
    if (cmd_err != ESP_OK) {
        ESP_LOGE(TAG, "PP_ASR_CMD_FAIL 命令词两种格式都无法加入模型");
        return cmd_err;
    }
    ESP_LOGI(TAG, "PP_ASR_CMD_READY format=%s", use_fallback ? "pinyin" : "native");

    /* 单麦（"M"）：本板采集链路已验证的是左槽单声道，不做双麦波束成形。
     * 没有回采通道，因此 AEC/SE 一律关闭，避免它们等一个不存在的参考信号。 */
    afe_config_t *afe_cfg = afe_config_init("M", s_models, AFE_TYPE_SR, AFE_MODE_LOW_COST);
    if (afe_cfg == NULL) {
        ESP_LOGE(TAG, "PP_ASR_AFE_FAIL afe_config_init");
        return ESP_ERR_INVALID_STATE;
    }
    afe_cfg->aec_init = false;
    afe_cfg->se_init = false;
    afe_cfg->vad_init = true;

    s_afe = esp_afe_handle_from_config(afe_cfg);
    if (s_afe == NULL) {
        ESP_LOGE(TAG, "PP_ASR_AFE_FAIL handle");
        afe_config_free(afe_cfg);
        return ESP_ERR_INVALID_STATE;
    }

    s_afe_data = s_afe->create_from_config(afe_cfg);
    afe_config_free(afe_cfg);
    if (s_afe_data == NULL) {
        ESP_LOGE(TAG, "PP_ASR_AFE_FAIL create_from_config");
        return ESP_ERR_NO_MEM;
    }

    s_ready = true;
    s_last_text[0] = '\0';
    ESP_LOGI(TAG, "PP_ASR_READY model=%s feed_chunk=%d feed_ch=%d fetch_chunk=%d",
             mn_name, s_afe->get_feed_chunksize(s_afe_data),
             s_afe->get_feed_channel_num(s_afe_data),
             s_afe->get_fetch_chunksize(s_afe_data));
    return ESP_OK;
}

bool pp_asr_ready(void)
{
    return s_ready;
}

bool pp_asr_listening(void)
{
    return s_listening;
}

const char *pp_asr_last_text(void)
{
    return s_last_text;
}

esp_err_t pp_asr_listen_start(int listen_ms, pp_asr_cb_t cb, pp_asr_done_cb_t done)
{
    if (!s_ready) {
        ESP_LOGW(TAG, "PP_ASR_LISTEN_BLOCKED not_ready");
        return ESP_ERR_INVALID_STATE;
    }
    if (s_listening) {
        ESP_LOGW(TAG, "PP_ASR_LISTEN_BLOCKED already_listening");
        return ESP_ERR_INVALID_STATE;
    }
    if (listen_ms < PP_ASR_MIN_MS) {
        listen_ms = PP_ASR_MIN_MS;
    }

    pp_asr_req_t *req = heap_caps_malloc(sizeof(*req), MALLOC_CAP_DEFAULT);
    if (req == NULL) {
        ESP_LOGE(TAG, "PP_ASR_LISTEN_FAIL 请求结构分配失败");
        return ESP_ERR_NO_MEM;
    }
    req->listen_ms = listen_ms;
    req->cb = cb;
    req->done = done;

    s_listening = true;
    if (xTaskCreate(pp_asr_task, "pp_asr", 8192, req, 5, NULL) != pdPASS) {
        s_listening = false;
        heap_caps_free(req);
        ESP_LOGE(TAG, "PP_ASR_LISTEN_FAIL 任务创建失败");
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

static void pp_asr_task(void *arg)
{
    pp_asr_req_t req = *(pp_asr_req_t *)arg;
    heap_caps_free(arg);

    bool matched = false;
    int16_t *feed = NULL;
    uint8_t *raw = NULL;

    const int chunk = s_afe->get_feed_chunksize(s_afe_data);
    const int nch = s_afe->get_feed_channel_num(s_afe_data);
    const size_t frames48 = (size_t)chunk * PP_ASR_DECIM;
    const size_t raw_bytes = frames48 * PP_ASR_BYTES;

    feed = heap_caps_malloc((size_t)chunk * (size_t)nch * sizeof(int16_t), MALLOC_CAP_SPIRAM);
    raw = heap_caps_malloc(raw_bytes, MALLOC_CAP_SPIRAM);
    if (feed == NULL || raw == NULL) {
        ESP_LOGE(TAG, "PP_ASR_LISTEN_FAIL 缓冲区分配失败 feed=%p raw=%p", feed, raw);
        goto out;
    }

    /* I2S 由 pp_audio 独占：这里只借用它的读接口，不再自己开通道。 */
    esp_err_t err = pp_audio_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PP_ASR_LISTEN_FAIL mic_init err=%s", esp_err_to_name(err));
        goto out;
    }

    ESP_LOGI(TAG, "PP_ASR_LISTEN_BEGIN ms=%d chunk=%d ch=%d raw_bytes=%u",
             req.listen_ms, chunk, nch, (unsigned)raw_bytes);

    /* 清掉上一轮会话残留的中间状态，否则上一句话可能污染这一句。 */
    s_mn->clean(s_mn_data);
    s_afe->reset_buffer(s_afe_data);

    const int64_t start_us = esp_timer_get_time();
    const int64_t deadline = start_us + (int64_t)req.listen_ms * 1000;
    int64_t next_report_us = start_us + 1000000;

    while (esp_timer_get_time() < deadline) {
        err = pp_audio_read_raw(raw, raw_bytes, 200);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "PP_ASR_READ_FAIL err=%s", esp_err_to_name(err));
            continue;
        }

        for (int i = 0; i < chunk; i++) {
            int32_t acc = 0;
            for (int d = 0; d < PP_ASR_DECIM; d++) {
                const uint8_t *p = raw + ((size_t)i * PP_ASR_DECIM + d) * PP_ASR_BYTES;
                acc += pp_asr_s16(p);
            }
            int16_t v = (int16_t)(acc / PP_ASR_DECIM);
            if (nch == 1) {
                feed[i] = v;
            } else {
                for (int c = 0; c < nch; c++) {
                    feed[i * nch + c] = v;
                }
            }
        }

        s_afe->feed(s_afe_data, feed);
        afe_fetch_result_t *res = s_afe->fetch(s_afe_data);
        if (res == NULL || res->ret_value == ESP_FAIL) {
            continue;
        }

        esp_mn_state_t st = s_mn->detect(s_mn_data, res->data);
        if (st == ESP_MN_STATE_DETECTED) {
            esp_mn_results_t *out = s_mn->get_results(s_mn_data);
            if (out != NULL && out->num > 0) {
                int id = out->command_id[0];
                float score = out->prob[0];
                int elapsed_ms = (int)((esp_timer_get_time() - start_us) / 1000);
                snprintf(s_last_text, sizeof(s_last_text), "%.*s", (int)sizeof(s_last_text) - 1, out->string);
                ESP_LOGI(TAG, "PP_ASR_RESULT id=%d text=\"%s\" raw=\"%s\" score=%.2f t_ms=%d",
                         id, out->string, out->raw_string, score, elapsed_ms);
                matched = true;
                if (req.cb != NULL) {
                    req.cb(id, out->string, score);
                }
            }
            break;
        }
        if (st == ESP_MN_STATE_TIMEOUT) {
            ESP_LOGW(TAG, "PP_ASR_TIMEOUT 模型侧超时");
            break;
        }

        if (esp_timer_get_time() >= next_report_us) {
            next_report_us += 1000000;
            ESP_LOGI(TAG, "PP_ASR_WAIT t_ms=%d vol=%.1f dBFS vad=%d",
                     (int)((esp_timer_get_time() - start_us) / 1000),
                     res->data_volume, (int)res->vad_state);
        }
    }

    if (!matched) {
        ESP_LOGW(TAG, "PP_ASR_NO_MATCH 本次未识别到命令词");
    }

out:
    if (feed != NULL) {
        heap_caps_free(feed);
    }
    if (raw != NULL) {
        heap_caps_free(raw);
    }
    ESP_LOGI(TAG, "PP_ASR_LISTEN_END matched=%d", matched ? 1 : 0);
    s_listening = false;
    if (req.done != NULL) {
        req.done(matched);
    }
    vTaskDelete(NULL);
}