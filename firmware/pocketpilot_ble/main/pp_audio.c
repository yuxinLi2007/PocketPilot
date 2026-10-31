#include "pp_audio.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/i2s_std.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define TAG "PocketPilotMic"

/* 官方 BSP esp32_s3_eye_idf5.c 中的引脚，逐项对应，不做推测 */
#define PP_I2S_BCLK GPIO_NUM_41
#define PP_I2S_WS   GPIO_NUM_42
#define PP_I2S_DIN  GPIO_NUM_2

/* ESP-IDF 在 I2S_DATA_BIT_WIDTH_24BIT 下按 3 字节/样本组织 DMA。
 * 不能继续按 int32_t 读取，否则样本边界会错位。 */
#define PP_BYTES_PER_SAMPLE 3
#define PP_CHUNK_SAMPLES 960 /* 20 ms @ 48 kHz */
#define PP_CHUNK_BYTES (PP_CHUNK_SAMPLES * PP_BYTES_PER_SAMPLE)
#define PP_STARTUP_DRAIN_MS 400

static i2s_chan_handle_t s_rx = NULL;
static bool s_ready = false;

/* 位对齐开关：见 pp_audio_align_scan() 的实测说明 */
static bool s_bit_shift = true;
static bool s_ws_pol = false;

/* 整数平方根：避免为了两个统计量引入 libm 依赖 */
static uint32_t pp_isqrt(uint64_t v)
{
    if (v == 0) {
        return 0;
    }
    uint64_t x = v;
    uint64_t y = (x + 1) / 2;
    while (y < x) {
        x = y;
        y = (x + v / x) / 2;
    }
    return (uint32_t)x;
}

/* 把 DMA 中的 3 字节小端样本还原为有符号 24 bit，再降到 16 bit。 */
static inline uint32_t pp_u24le(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16);
}

static inline uint32_t pp_u24be(const uint8_t *p)
{
    return ((uint32_t)p[0] << 16) | ((uint32_t)p[1] << 8) | (uint32_t)p[2];
}

static inline int32_t pp_s24_from_u24(uint32_t v)
{
    v &= 0x00ffffffu;
    if (v & 0x00800000u) {
        v |= 0xff000000u;
    }
    return (int32_t)v;
}

static inline int32_t pp_sample_to_s16(const uint8_t *p)
{
    return pp_s24_from_u24(pp_u24le(p)) >> 8;
}

static void pp_fill_cfg(i2s_std_config_t *cfg)
{
    *cfg = (i2s_std_config_t){
        .clk_cfg = {
            .sample_rate_hz = PP_AUDIO_I2S_RATE,
            .clk_src = I2S_CLK_SRC_DEFAULT,
            .mclk_multiple = I2S_MCLK_MULTIPLE_384,
        },
        .slot_cfg = {
            .data_bit_width = I2S_DATA_BIT_WIDTH_24BIT,
            .slot_bit_width = I2S_SLOT_BIT_WIDTH_32BIT,
            .slot_mode = I2S_SLOT_MODE_MONO,
            .slot_mask = I2S_STD_SLOT_LEFT,
            .ws_width = 32,
            .ws_pol = s_ws_pol,
            .bit_shift = s_bit_shift,
            .left_align = true,
            .big_endian = false,
            .bit_order_lsb = false,
        },
        .gpio_cfg = {
            .mclk = GPIO_NUM_NC,
            .bclk = PP_I2S_BCLK,
            .ws = PP_I2S_WS,
            .dout = GPIO_NUM_NC,
            .din = PP_I2S_DIN,
            .invert_flags = { .mclk_inv = false, .bclk_inv = false, .ws_inv = false },
        },
    };
}

static esp_err_t pp_channel_open(void)
{
    if (s_rx != NULL) {
        i2s_channel_disable(s_rx);
        i2s_del_channel(s_rx);
        s_rx = NULL;
        s_ready = false;
    }

    i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chan_cfg.auto_clear = true;
    esp_err_t err = i2s_new_channel(&chan_cfg, NULL, &s_rx);
    if (err != ESP_OK) {
        s_rx = NULL;
        return err;
    }

    i2s_std_config_t cfg;
    pp_fill_cfg(&cfg);

    err = i2s_channel_init_std_mode(s_rx, &cfg);
    if (err != ESP_OK) {
        i2s_del_channel(s_rx);
        s_rx = NULL;
        return err;
    }

    err = i2s_channel_enable(s_rx);
    if (err != ESP_OK) {
        i2s_del_channel(s_rx);
        s_rx = NULL;
        return err;
    }

    s_ready = true;
    return ESP_OK;
}

static size_t pp_read_bytes(uint8_t *buf, size_t cap, TickType_t timeout, esp_err_t *err_out)
{
    size_t got = 0;
    esp_err_t err = i2s_channel_read(s_rx, buf, cap, &got, timeout);
    if (err_out) {
        *err_out = err;
    }
    return got > cap ? cap : got;
}

/* 丢掉 I2S 启动后的硬件瞬态，避免短暂尖峰被误判成“真实声音”。 */
static void pp_discard_ms(int duration_ms)
{
    static uint8_t discard[PP_CHUNK_BYTES];
    int64_t end = esp_timer_get_time() + (int64_t)duration_ms * 1000;
    uint32_t reads = 0;
    uint32_t bytes = 0;
    while (esp_timer_get_time() < end) {
        esp_err_t err = ESP_OK;
        size_t got = pp_read_bytes(discard, sizeof(discard), pdMS_TO_TICKS(100), &err);
        if (got > 0) {
            reads++;
            bytes += (uint32_t)got;
        }
    }
    ESP_LOGI(TAG, "PP_MIC_DRAIN ms=%d reads=%u bytes=%u", duration_ms,
             (unsigned)reads, (unsigned)bytes);
}

esp_err_t pp_audio_init(void)
{
    if (s_ready) {
        return ESP_OK;
    }
    esp_err_t err = pp_channel_open();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PP_MIC_INIT_FAIL stage=open err=%s", esp_err_to_name(err));
        return err;
    }
    pp_discard_ms(PP_STARTUP_DRAIN_MS);
    ESP_LOGI(TAG,
             "PP_MIC_READY i2s_rate=%d out_rate=%d decim=%d format=24in32 "
             "bytes_per_sample=%d din=%d bclk=%d ws=%d",
             PP_AUDIO_I2S_RATE, PP_AUDIO_SAMPLE_RATE, PP_AUDIO_DECIM,
             PP_BYTES_PER_SAMPLE, (int)PP_I2S_DIN, (int)PP_I2S_BCLK, (int)PP_I2S_WS);
    return ESP_OK;
}

bool pp_audio_ready(void)
{
    return s_ready;
}

esp_err_t pp_audio_read_raw(uint8_t *buf, size_t bytes, uint32_t timeout_ms)
{
    if (!s_ready || s_rx == NULL || buf == NULL || bytes == 0) {
        return ESP_ERR_INVALID_STATE;
    }
    size_t got = 0;
    while (got < bytes) {
        esp_err_t err = ESP_OK;
        size_t n = pp_read_bytes(buf + got, bytes - got, pdMS_TO_TICKS(timeout_ms), &err);
        if (n > 0) {
            got += n;
            continue;
        }
        return (err == ESP_OK) ? ESP_ERR_TIMEOUT : err;
    }
    return ESP_OK;
}
/* 单次读取 20 ms 原始音频；返回样本数，字节数必须是 3 的整数倍。 */
static size_t pp_read_chunk(uint8_t *raw, esp_err_t *err_out)
{
    size_t got = pp_read_bytes(raw, PP_CHUNK_BYTES, pdMS_TO_TICKS(200), err_out);
    return got / PP_BYTES_PER_SAMPLE;
}

/* 采集并统计。 */
static esp_err_t pp_capture_ms(int duration_ms, pp_audio_stats_t *out, bool verbose)
{
    static uint8_t raw[PP_CHUNK_BYTES];

    int64_t sum = 0;
    int64_t sumsq = 0;
    int32_t peak = 0;
    int32_t vmin = 32767;
    int32_t vmax = -32768;
    uint32_t count = 0;
    uint32_t clipped = 0;
    uint32_t empty_runs = 0;

    const int chunks = (duration_ms + 19) / 20;
    const int report_every = 10; /* 每 200 ms 一行 */
    int64_t win_sum_sq = 0;
    uint32_t win_count = 0;

    for (int i = 0; i < chunks; i++) {
        esp_err_t err = ESP_OK;
        size_t n = pp_read_chunk(raw, &err);
        if (n == 0) {
            empty_runs++;
            if (empty_runs >= 25) { /* 500 ms 完全没有数据 */
                ESP_LOGE(TAG, "PP_MIC_READ_FAIL err=%s empty_reads=%u",
                         esp_err_to_name(err), (unsigned)empty_runs);
                return err == ESP_OK ? ESP_ERR_TIMEOUT : err;
            }
            continue;
        }
        for (size_t k = 0; k + PP_AUDIO_DECIM <= n; k += PP_AUDIO_DECIM) {
            /* 3:1 抽取（简单平均，兼作抗混叠的低通） */
            int32_t acc = 0;
            for (int d = 0; d < PP_AUDIO_DECIM; d++) {
                acc += pp_sample_to_s16(&raw[(k + (size_t)d) * PP_BYTES_PER_SAMPLE]);
            }
            int32_t s = acc / PP_AUDIO_DECIM;
            int32_t a = s < 0 ? -s : s;
            sum += s;
            sumsq += (int64_t)s * s;
            win_sum_sq += (int64_t)s * s;
            if (a > peak) {
                peak = a;
            }
            if (s < vmin) {
                vmin = s;
            }
            if (s > vmax) {
                vmax = s;
            }
            if (a >= 32000) {
                clipped++;
            }
            count++;
            win_count++;
        }
        if (count == 0) {
            continue;
        }
        if (verbose && (i + 1) % report_every == 0) {
            uint32_t wrms = win_count ? pp_isqrt((uint64_t)(win_sum_sq / (int64_t)win_count)) : 0;
            ESP_LOGI(TAG, "PP_MIC t=%ums rms=%u peak=%d", (unsigned)((i + 1) * 20),
                     (unsigned)wrms, (int)peak);
            win_sum_sq = 0;
            win_count = 0;
        }
    }

    pp_audio_stats_t st = {0};
    if (count == 0) {
        ESP_LOGE(TAG, "PP_MIC_NO_DATA dur=%dms empty_reads=%u", duration_ms, (unsigned)empty_runs);
        if (out) {
            *out = st;
        }
        return ESP_ERR_TIMEOUT;
    }

    /* 交流有效值：sqrt(E[x^2] - mean^2)，单次遍历即可，不需要缓存整段音频 */
    int64_t mean = sum / (int64_t)count;
    int64_t var = sumsq / (int64_t)count - mean * mean;
    if (var < 0) {
        var = 0;
    }

    st.samples = count;
    st.rms = (int32_t)pp_isqrt((uint64_t)var);
    st.peak = peak;
    st.p2p = vmax - vmin;
    st.min = vmin;
    st.max = vmax;
    st.dc_offset = (int32_t)mean;
    st.clipped = clipped;
    if (out) {
        *out = st;
    }

    if (verbose) {
        ESP_LOGI(TAG, "PP_MIC_STATS dur=%dms n=%u ac_rms=%d peak=%d p2p=%d min=%d max=%d dc=%d clipped=%u",
                 duration_ms, (unsigned)count, (int)st.rms, (int)st.peak, (int)st.p2p,
                 (int)st.min, (int)st.max, (int)st.dc_offset, (unsigned)st.clipped);
    }
    return ESP_OK;
}

esp_err_t pp_audio_capture(int duration_ms, pp_audio_stats_t *out)
{
    if (!s_ready) {
        esp_err_t err = pp_audio_init();
        if (err != ESP_OK) {
            return err;
        }
    }
    if (duration_ms <= 0) {
        return ESP_ERR_INVALID_ARG;
    }
    return pp_capture_ms(duration_ms, out, true);
}

esp_err_t pp_audio_two_phase(int phase_ms, pp_audio_stats_t *quiet, pp_audio_stats_t *loud)
{
    if (phase_ms <= 0) {
        return ESP_ERR_INVALID_ARG;
    }
    if (!s_ready) {
        esp_err_t err = pp_audio_init();
        if (err != ESP_OK) {
            return err;
        }
    }

    pp_audio_stats_t q = {0};
    pp_audio_stats_t l = {0};

    ESP_LOGI(TAG, "PP_MIC_PHASE_BEGIN name=quiet ms=%d", phase_ms);
    esp_err_t err = pp_capture_ms(phase_ms, &q, false);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PP_MIC_PHASE_FAIL name=quiet");
        return err;
    }
    ESP_LOGI(TAG, "PP_MIC_PHASE name=quiet n=%u ac_rms=%d peak=%d p2p=%d dc=%d clipped=%u",
             (unsigned)q.samples, (int)q.rms, (int)q.peak, (int)q.p2p, (int)q.dc_offset,
             (unsigned)q.clipped);

    ESP_LOGI(TAG, "PP_MIC_PHASE_BEGIN name=loud ms=%d", phase_ms);
    /* 给用户反应时间；这段不计入统计。 */
    pp_discard_ms(300);
    err = pp_capture_ms(phase_ms, &l, false);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "PP_MIC_PHASE_FAIL name=loud");
        return err;
    }
    ESP_LOGI(TAG, "PP_MIC_PHASE name=loud n=%u ac_rms=%d peak=%d p2p=%d dc=%d clipped=%u",
             (unsigned)l.samples, (int)l.rms, (int)l.peak, (int)l.p2p, (int)l.dc_offset,
             (unsigned)l.clipped);

    uint32_t ratio = q.rms > 0 ? (uint32_t)((int64_t)l.rms * 100 / q.rms) : 0;
    uint32_t clip_permille = l.samples ? (uint32_t)((uint64_t)l.clipped * 1000 / l.samples) : 0;
    int32_t abs_dc = q.dc_offset < 0 ? -q.dc_offset : q.dc_offset;

    const char *verdict;
    const char *reason;
    if (q.rms > 1500) {
        verdict = "FAIL";
        reason = "quiet_floor_too_high";
    } else if (ratio >= 300 && clip_permille < 50 && abs_dc <= 3000) {
        verdict = "PASS";
        reason = "speech_lifts_level_above_quiet_floor";
    } else if (ratio >= 150) {
        verdict = "UNDECIDED";
        reason = "level_changes_but_outside_tolerance";
    } else {
        verdict = "FAIL";
        reason = "level_does_not_respond_to_sound";
    }

    ESP_LOGI(TAG, "PP_MIC_VERDICT result=%s reason=%s quiet_ac_rms=%d loud_ac_rms=%d ratio_pct=%u "
                  "loud_clip_pm=%u quiet_dc=%d",
             verdict, reason, (int)q.rms, (int)l.rms, (unsigned)ratio,
             (unsigned)clip_permille, (int)q.dc_offset);

    if (quiet) {
        *quiet = q;
    }
    if (loud) {
        *loud = l;
    }
    return ESP_OK;
}

esp_err_t pp_audio_stream_pcm16(int seconds)
{
    if (seconds <= 0) {
        return ESP_ERR_INVALID_ARG;
    }
    if (!s_ready) {
        esp_err_t err = pp_audio_init();
        if (err != ESP_OK) {
            return err;
        }
    }

    static uint8_t raw[PP_CHUNK_BYTES];
    static int16_t pcm[PP_CHUNK_SAMPLES / PP_AUDIO_DECIM];

    const uint32_t total = (uint32_t)seconds * PP_AUDIO_SAMPLE_RATE;
    const int chunks = seconds * 50; /* 每块 20 ms 原始音频 */

    printf("\nPP_WAV_BEGIN rate=%d channels=1 bits=16 samples=%u\n",
           PP_AUDIO_SAMPLE_RATE, (unsigned)total);
    fflush(stdout);

    uint32_t written = 0;
    for (int i = 0; i < chunks && written < total; i++) {
        esp_err_t err = ESP_OK;
        size_t n = pp_read_chunk(raw, &err);
        if (n < PP_AUDIO_DECIM) {
            continue;
        }
        size_t out_n = 0;
        for (size_t k = 0; k + PP_AUDIO_DECIM <= n && written + out_n < total; k += PP_AUDIO_DECIM) {
            int32_t acc = 0;
            for (int d = 0; d < PP_AUDIO_DECIM; d++) {
                acc += pp_sample_to_s16(&raw[(k + (size_t)d) * PP_BYTES_PER_SAMPLE]);
            }
            pcm[out_n++] = (int16_t)(acc / PP_AUDIO_DECIM);
        }
        if (out_n > 0) {
            fwrite(pcm, sizeof(int16_t), out_n, stdout);
            fflush(stdout);
            written += (uint32_t)out_n;
        }
    }

    printf("\nPP_WAV_END samples=%u\n", (unsigned)written);
    fflush(stdout);
    ESP_LOGI(TAG, "PP_WAV_DONE samples=%u expect=%u", (unsigned)written, (unsigned)total);
    return written == total ? ESP_OK : ESP_ERR_INVALID_SIZE;
}

esp_err_t pp_audio_level_watch(int seconds)
{
    if (seconds <= 0) {
        return ESP_ERR_INVALID_ARG;
    }
    if (!s_ready) {
        esp_err_t err = pp_audio_init();
        if (err != ESP_OK) {
            return err;
        }
    }

    const int window_ms = 500;
    const int windows = seconds * 1000 / window_ms;
    ESP_LOGI(TAG, "PP_LEVEL_WATCH_BEGIN seconds=%d window_ms=%d", seconds, window_ms);
    for (int i = 0; i < windows; i++) {
        pp_audio_stats_t st = {0};
        esp_err_t err = pp_capture_ms(window_ms, &st, false);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "PP_LEVEL_WATCH_FAIL t=%dms err=%s", (i + 1) * window_ms,
                     esp_err_to_name(err));
            return err;
        }
        ESP_LOGI(TAG, "PP_LEVEL t=%dms rms=%d peak=%d", (i + 1) * window_ms, (int)st.rms,
                 (int)st.peak);
    }
    ESP_LOGI(TAG, "PP_LEVEL_WATCH_END windows=%d", windows);
    return ESP_OK;
}

esp_err_t pp_audio_dump_raw(int count)
{
    if (!s_ready) {
        esp_err_t err = pp_audio_init();
        if (err != ESP_OK) {
            return err;
        }
    }
    if (count <= 0) {
        count = 32;
    }
    if (count > 64) {
        count = 64;
    }
    static uint8_t buf[64 * PP_BYTES_PER_SAMPLE];
    int printed = 0;
    int empty = 0;
    while (printed < count && empty < 25) {
        esp_err_t err = ESP_OK;
        size_t got = pp_read_bytes(buf, (size_t)count * PP_BYTES_PER_SAMPLE,
                                   pdMS_TO_TICKS(200), &err);
        if (got == 0) {
            empty++;
            continue;
        }
        size_t n = got / PP_BYTES_PER_SAMPLE;
        for (size_t i = 0; i < n && printed < count; i++, printed++) {
            const uint8_t *p = &buf[i * PP_BYTES_PER_SAMPLE];
            uint32_t le = pp_u24le(p);
            uint32_t be = pp_u24be(p);
            ESP_LOGI(TAG,
                     "PP_MIC_RAW i=%d b=%02x,%02x,%02x le24=0x%06x be24=0x%06x pcm16=%d",
                     printed, p[0], p[1], p[2], (unsigned)le, (unsigned)be,
                     (int)pp_sample_to_s16(p));
        }
    }
    ESP_LOGI(TAG, "PP_MIC_RAW_DONE printed=%d empty_reads=%d", printed, empty);
    return printed > 0 ? ESP_OK : ESP_ERR_TIMEOUT;
}

typedef struct {
    int kind; /* 0 = probe seconds, 1 = two-phase, 2 = wav */
    int arg;
} pp_audio_job_t;

static void pp_audio_task(void *arg)
{
    pp_audio_job_t job = *(pp_audio_job_t *)arg;
    free(arg);

    if (job.kind == 0) {
        ESP_LOGI(TAG, "PP_MIC_PROBE start seconds=%d", job.arg);
        pp_audio_stats_t st;
        esp_err_t err = pp_audio_capture(job.arg * 1000, &st);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "PP_MIC_PROBE fail err=%s", esp_err_to_name(err));
        }
    } else if (job.kind == 1) {
        esp_err_t err = pp_audio_two_phase(job.arg, NULL, NULL);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "PP_MIC_TWOPHASE fail err=%s", esp_err_to_name(err));
        }
    } else if (job.kind == 2) {
        esp_err_t err = pp_audio_stream_pcm16(job.arg);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "PP_WAV fail err=%s", esp_err_to_name(err));
        }
    } else {
        esp_err_t err = pp_audio_level_watch(job.arg);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "PP_LEVEL_WATCH fail err=%s", esp_err_to_name(err));
        }
    }
    vTaskDelete(NULL);
}

static void pp_audio_start(int kind, int arg, const char *name, uint32_t stack)
{
    pp_audio_job_t *job = malloc(sizeof(*job));
    if (!job) {
        ESP_LOGE(TAG, "PP_AUDIO_JOB alloc failed");
        return;
    }
    job->kind = kind;
    job->arg = arg;
    if (xTaskCreate(pp_audio_task, name, stack, job, 4, NULL) != pdPASS) {
        ESP_LOGE(TAG, "PP_AUDIO_JOB task create failed");
        free(job);
    }
}

void pp_audio_probe_start(int seconds)
{
    if (seconds <= 0) {
        ESP_LOGI(TAG, "PP_MIC_PROBE stopped");
        return;
    }
    pp_audio_start(0, seconds, "pp_mic", 6144);
}

void pp_audio_two_phase_start(int phase_ms)
{
    pp_audio_start(1, phase_ms, "pp_mic2", 6144);
}

void pp_audio_stream_start(int seconds)
{
    pp_audio_start(2, seconds, "pp_wav", 8192);
}

void pp_audio_level_watch_start(int seconds)
{
    pp_audio_start(3, seconds, "pp_audio_level", 4096);
}
/* 诊断用：I2S 运行期间直接高速采样 DIN 电平，数它翻转了多少次。 */
void pp_audio_pin_activity(void)
{
    if (!s_ready) {
        if (pp_audio_init() != ESP_OK) {
            return;
        }
    }
    const int rounds = 3;
    for (int r = 0; r < rounds; r++) {
        int last = gpio_get_level(PP_I2S_DIN);
        uint32_t edges = 0;
        uint32_t max_run = 0;
        uint32_t run = 0;
        const int64_t t_end = esp_timer_get_time() + 20000; /* 20 ms */
        uint32_t loops = 0;
        while (esp_timer_get_time() < t_end) {
            int v = gpio_get_level(PP_I2S_DIN);
            if (v != last) {
                edges++;
                if (run > max_run) {
                    max_run = run;
                }
                run = 0;
                last = v;
            } else {
                run++;
            }
            loops++;
        }
        ESP_LOGI(TAG, "PP_MIC_PIN round=%d loops=%u edges=%u max_run=%u level=%d",
                 r, (unsigned)loops, (unsigned)edges, (unsigned)max_run, last);
    }
}

/* 诊断用：临时切成立体声，逐对打印左右槽的 24 bit 原始样本。 */
void pp_audio_stereo_dump(int pairs)
{
    if (pairs <= 0) {
        pairs = 16;
    }
    if (s_rx != NULL) {
        i2s_channel_disable(s_rx);
        i2s_del_channel(s_rx);
        s_rx = NULL;
        s_ready = false;
    }

    i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chan_cfg.auto_clear = true;
    if (i2s_new_channel(&chan_cfg, NULL, &s_rx) != ESP_OK) {
        ESP_LOGE(TAG, "PP_MIC_STEREO new_channel failed");
        s_rx = NULL;
        return;
    }
    i2s_std_config_t cfg;
    pp_fill_cfg(&cfg);
    cfg.slot_cfg.slot_mode = I2S_SLOT_MODE_STEREO;
    cfg.slot_cfg.slot_mask = I2S_STD_SLOT_BOTH;
    if (i2s_channel_init_std_mode(s_rx, &cfg) != ESP_OK || i2s_channel_enable(s_rx) != ESP_OK) {
        ESP_LOGE(TAG, "PP_MIC_STEREO open failed");
        i2s_del_channel(s_rx);
        s_rx = NULL;
        return;
    }
    s_ready = true;
    pp_discard_ms(100);

    static uint8_t buf[PP_CHUNK_BYTES];
    int printed = 0;
    int empty = 0;
    while (printed < pairs && empty < 25) {
        esp_err_t err = ESP_OK;
        size_t got = pp_read_bytes(buf, sizeof(buf), pdMS_TO_TICKS(200), &err);
        if (got == 0) {
            empty++;
            continue;
        }
        size_t n = got / PP_BYTES_PER_SAMPLE;
        for (size_t i = 0; i + 1 < n && printed < pairs; i += 2, printed++) {
            const uint8_t *l = &buf[i * PP_BYTES_PER_SAMPLE];
            const uint8_t *r = &buf[(i + 1) * PP_BYTES_PER_SAMPLE];
            ESP_LOGI(TAG, "PP_MIC_PAIR n=%d L=0x%06x R=0x%06x pcmL=%d pcmR=%d",
                     printed, (unsigned)pp_u24le(l), (unsigned)pp_u24le(r),
                     (int)pp_sample_to_s16(l), (int)pp_sample_to_s16(r));
        }
    }
    ESP_LOGI(TAG, "PP_MIC_STEREO_DONE printed=%d", printed);

    i2s_channel_disable(s_rx);
    i2s_del_channel(s_rx);
    s_rx = NULL;
    s_ready = false;
    (void)pp_audio_init();
}

/* 诊断用：四种“位对齐 + WS 极性”组合各采 512 个 24 bit 样本。 */
void pp_audio_align_scan(void)
{
    static const bool shifts[] = { false, true, false, true };
    static const bool pols[] = { false, false, true, true };
    static const char *names[] = { "shift0_pol0", "shift1_pol0", "shift0_pol1", "shift1_pol1" };

    for (int v = 0; v < 4; v++) {
        s_bit_shift = shifts[v];
        s_ws_pol = pols[v];
        if (pp_channel_open() != ESP_OK) {
            ESP_LOGE(TAG, "PP_ALIGN v=%d name=%s open_fail", v, names[v]);
            continue;
        }
        static uint8_t buf[PP_CHUNK_BYTES];
        int64_t sum = 0, sumsq = 0;
        uint32_t n = 0, neg = 0, empty = 0;
        while (n < 512 && empty < 10) {
            esp_err_t err = ESP_OK;
            size_t got = pp_read_bytes(buf, sizeof(buf), pdMS_TO_TICKS(100), &err);
            if (got == 0) {
                empty++;
                continue;
            }
            size_t m = got / PP_BYTES_PER_SAMPLE;
            for (size_t i = 0; i < m && n < 512; i++, n++) {
                int32_t sample24 = pp_s24_from_u24(pp_u24le(&buf[i * PP_BYTES_PER_SAMPLE]));
                int32_t s = sample24 >> 8;
                sum += s;
                sumsq += (int64_t)s * s;
                if (sample24 < 0) {
                    neg++;
                }
            }
        }
        if (n == 0) {
            ESP_LOGW(TAG, "PP_ALIGN v=%d name=%s no_data", v, names[v]);
        } else {
            int32_t dc = (int32_t)(sum / (int64_t)n);
            int64_t var = sumsq / (int64_t)n - (int64_t)dc * dc;
            if (var < 0) {
                var = 0;
            }
            ESP_LOGI(TAG, "PP_ALIGN v=%d name=%-12s n=%u neg_pct=%u ac_rms=%d dc=%d",
                     v, names[v], (unsigned)n, (unsigned)(neg * 100 / n),
                     (int)pp_isqrt((uint64_t)var), (int)dc);
        }
        i2s_channel_disable(s_rx);
        i2s_del_channel(s_rx);
        s_rx = NULL;
        s_ready = false;
    }
    s_bit_shift = true;
    s_ws_pol = false;
    ESP_LOGI(TAG, "PP_ALIGN_DONE");
}
