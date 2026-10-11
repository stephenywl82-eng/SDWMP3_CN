// ============================================================================
// 【V8.222】Rubber Band 拉伸引擎包装 —— 与 WsolaStretch(time_stretch.h) 同接口。
//
// 为什么存在：WSOLA 是时域拼贴，±4% 以上就有可闻"水波纹"；Rubber Band 是频域
// 相位声码器（Mixxx 同款开源引擎），±8% 内几乎无痕 —— 这才是专业软件 keylock
// 的同级别算法。挂在与 WSOLA 完全相同的插槽上：只有变速比率≠1 时才被武装，
// 默认关（crossfade_rubberband），关闭时这条路照旧是死代码，音频链零接触。
//
// 接口对齐 WsolaStretch：
//   reset(sr, ch) / setRatio(r) / clear()
//   feed(const float* inter, int frames)      交织输入
//   availableFrames() / needsInputFrames(want) / render()（RB 按需产出，空实现）
//   pull(float* inter, int frames) -> 实际取出的帧数
//
// 实时安全：所有缓冲在 reset() 里一次性预分配（feed/pull 不再做 malloc），
// 引擎用 OptionThreadingNever（音频线程自驱，不起工作线程）。
// ============================================================================
#pragma once

#include <rubberband/RubberBandStretcher.h>
#include <vector>
#include <memory>
#include <cstring>

class RubberBandStretch {
public:
    static constexpr int kMaxFrames = 8192;   // 单次 feed/pull 上限（callback 帧数的数倍余量）

    void reset(int sampleRate, int channels) {
        m_sr = sampleRate > 0 ? sampleRate : 44100;
        m_ch = (channels == 1 || channels == 2) ? channels : 2;
        int opts = RubberBand::RubberBandStretcher::OptionProcessRealTime
                 | RubberBand::RubberBandStretcher::OptionPitchHighQuality
                 | RubberBand::RubberBandStretcher::OptionThreadingNever;
        m_st.reset(new RubberBand::RubberBandStretcher(
            (size_t)m_sr, (size_t)m_ch, opts));
        m_st->setMaxProcessSize(kMaxFrames);
        m_st->setTimeRatio(m_ratio > 0.0f ? (double)m_ratio : 1.0);
        m_st->setExpectedInputDuration(-1);
        // 预分配交织/非交织转换缓冲（reset 一次，之后零分配）
        m_in.assign((size_t)m_ch * kMaxFrames, 0.0f);
        m_out.assign((size_t)m_ch * kMaxFrames, 0.0f);
        m_ratioApplied = true;
    }

    void setRatio(float r) {
        if (r <= 0.0f) r = 1.0f;
        m_ratio = r;
        if (m_st) m_st->setTimeRatio((double)r);
    }

    /** 【V8.30x】音高缩放（变调不变速）。timeRatio 保持 1.0 时只改音高、不改速度。 */
    void setPitchScale(double s) {
        if (s <= 0.0) s = 1.0;
        if (m_st) m_st->setPitchScale(s);
    }

    float ratio() const { return m_ratio; }

    /** 清状态：丢弃引擎内部缓冲（crossfade 结束/中断时调用）。 */
    void clear() {
        if (m_st) m_st->reset();
    }

    /** 交织输入。frames 超上限时截断（与 WSOLA 同语义）。 */
    void feed(const float* inter, int frames) {
        if (!m_st || !inter || frames <= 0) return;
        if (frames > kMaxFrames) frames = kMaxFrames;
        const int ch = m_ch;
        for (int c = 0; c < ch; c++) {
            float* dst = &m_in[(size_t)c * frames];
            for (int i = 0; i < frames; i++) dst[i] = inter[(size_t)i * ch + c];
        }
        float* ptrs[8];
        for (int c = 0; c < ch; c++) ptrs[c] = &m_in[(size_t)c * frames];
        m_st->process(ptrs, (size_t)frames, false);
    }

    /** 引擎已可取出的帧数（RB available() <0 表示未知，按 0 处理）。 */
    int availableFrames() {
        if (!m_st) return 0;
        int a = (int)m_st->available();
        return a > 0 ? a : 0;
    }

    /** 为凑出 wantFrames 输出还需要吃多少输入（R2 realtime 语义）。 */
    int needsInputFrames(int wantFrames) const {
        if (!m_st) return wantFrames;
        int need = (int)m_st->getSamplesRequired();
        if (need > 0) return need;
        // 【V8.417】R2 实时模式 getSamplesRequired()==0 的语义是「输入环已够、
        // 等产出」（见 R2Stretcher.cpp 注释）。旧实现把它映射成 1 帧：调用方
        // 每个回调只 process(1 帧) × 8 次 → 引擎产出跟不上回调需求，混音侧出现
        // 20~406 帧的「样本保持」缺口（真机取证：pull-A/TempoBlend 过渡 6s 内
        // 37 次，听感轻微结巴）。改为按缺口 wantFrames 喂一整块，让 RB 在同一
        // 回调内把 chunk 队列补到够输出；多喂的输入滞留引擎内部环，下回调产出。
        return wantFrames > 0 ? wantFrames : 1;
    }

    /** 【V8.226】引擎固有算法延迟（帧）。不补偿的话 B 轨开口晚 ~latency，拍点对齐白做。 */
    int latencyFrames() const {
        return m_st ? (int)m_st->getLatency() : 0;
    }

    /** R2 realtime 模式 process() 后即可 retrieve，无需额外渲染步骤。 */
    void render() {}

    /** 交织取出至多 frames 帧，返回实际帧数。 */
    int pull(float* inter, int frames) {
        if (!m_st || !inter || frames <= 0) return 0;
        if (frames > kMaxFrames) frames = kMaxFrames;
        const int ch = m_ch;
        float* ptrs[8];
        for (int c = 0; c < ch; c++) ptrs[c] = &m_out[(size_t)c * frames];
        size_t got = m_st->retrieve(ptrs, (size_t)frames);
        if (got == 0) return 0;
        for (size_t i = 0; i < got; i++)
            for (int c = 0; c < ch; c++)
                inter[(size_t)i * ch + c] = m_out[(size_t)c * got + i];
        return (int)got;
    }

private:
    std::unique_ptr<RubberBand::RubberBandStretcher> m_st;
    int m_sr = 44100;
    int m_ch = 2;
    float m_ratio = 1.0f;
    bool m_ratioApplied = false;
    std::vector<float> m_in;
    std::vector<float> m_out;
};
