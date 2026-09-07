// bpm_detector.h — 全曲多算法 BPM 检测（v8.14 增强版）
// 三算法：①能量包络自相关 ②beat-interval 直方图 ③onset频谱通量
// 流程：能量包络 → 三算法独立算BPM → 候选聚类 → 最高票胜出
#pragma once
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include <algorithm>

namespace bpmdet {

// ================================================================
// 工具：归一化向量
// ================================================================
static inline void normalize(const std::vector<float>& v, float& peak, float& invPeak) {
    peak = 0.f;
    for (float x : v) if (x > peak) peak = x;
    invPeak = (peak > 1e-6f) ? (1.f / peak) : 0.f;
}

// ================================================================
// 算法①：能量包络自相关（原有，适合鼓点音乐）
// ================================================================
static int detectByAutocorr(const std::vector<float>& env, float peak, float invPeak, int hop) {
    const int minLag = 4;   // ~234 BPM
    const int maxLag = 24;  // ~39 BPM
    const size_t N = env.size();
    if (N < minLag * 2) return -1;
    float bestScore = 0.f;
    int bestLag = 0;
    for (int lag = minLag; lag <= maxLag; lag++) {
        float acc = 0.f, normA = 0.f, normB = 0.f;
        for (size_t i = 0; i + lag < N; i++) {
            float a = env[i] * invPeak, b = env[i + lag] * invPeak;
            acc += a * b; normA += a * a; normB += b * b;
        }
        if (normA < 1e-9f || normB < 1e-9f) continue;
        float score = acc / std::sqrt(normA * normB);
        if (score > bestScore) { bestScore = score; bestLag = lag; }
    }
    if (bestLag == 0) return -1;
    // 谐波消除（v8.14 修正方向：检查整数倍 lag = 更慢基频，防倍频误判）
    // 若 bestLag 对应 180BPM（lag=5），检查 90BPM（lag=10）得分接近 → 取 90（基频）
    for (int mult = 2; mult <= 4; mult++) {
        int mLag = bestLag * mult;
        if (mLag > maxLag) break;
        float acc = 0.f, normA = 0.f, normB = 0.f;
        for (size_t i = 0; i + mLag < N; i++) {
            float a = env[i] * invPeak, b = env[i + mLag] * invPeak;
            acc += a * b; normA += a * a; normB += b * b;
        }
        if (normA > 1e-9f && normB > 1e-9f) {
            float s = acc / std::sqrt(normA * normB);
            if (s > bestScore * 0.93f) { bestLag = mLag; bestScore = s; }
        }
    }
    // 置信度
    float meanScore = 0.f; int cnt = 0;
    for (int lag = minLag; lag <= maxLag; lag++) {
        if (std::abs(lag - bestLag) > 2) {
            float acc = 0.f, normA = 0.f, normB = 0.f;
            for (size_t i = 0; i + lag < N; i++) {
                float a = env[i] * invPeak, b = env[i + lag] * invPeak;
                acc += a * b; normA += a * a; normB += b * b;
            }
            if (normA > 1e-9f && normB > 1e-9f) {
                meanScore += acc / std::sqrt(normA * normB); cnt++;
            }
        }
    }
    float conf = (cnt > 0) ? (bestScore - meanScore / cnt) : bestScore;
    if (conf < 0.08f) return -1;  // 极低阈值，只排除纯平坦
    int bpm = (int)std::lround(60.f * 8000.f / (bestLag * (float)hop));
    if (bpm < 40) bpm *= 2;
    return (bpm >= 40 && bpm <= 220) ? bpm : -1;
}

// ================================================================
// 算法②：Beat-interval 直方图（适合强节拍电子/舞曲）
// 思路：onset 峰值间隔 → 众数周期 → BPM
// ================================================================
static int detectByIntervalHist(const std::vector<float>& onset, int hop, float peak, float invPeak) {
    // 找所有 onset 峰值位置
    const float kThresh = peak * 0.45f;  // 45% 峰值以上算打击点（只看真"拍"强峰，排除扫弦/弱kick干扰）
    std::vector<int> beats;
    for (size_t i = 1; i + 1 < onset.size(); i++) {
        if (onset[i] > kThresh && onset[i] > onset[i - 1] && onset[i] > onset[i + 1]) {
            beats.push_back((int)i);
        }
    }
    if (beats.size() < 4) return -1;
    // 计算相邻打击间隔（跳过过长间隔 >3s 视为段落间隙）
    std::vector<int> intervals;
    for (size_t i = 1; i < beats.size(); i++) {
        int gap = beats[i] - beats[i - 1];
        if (gap >= 4 && gap <= 48)  // 4*64ms=256ms (~230BPM) 到 48*64ms=3s (20BPM)
            intervals.push_back(gap);
    }
    if (intervals.size() < 3) return -1;
    // 间隔直方图（lag 4..48）
    const int kNumBins = 45;  // 4..48
    int hist[kNumBins] = {0};
    for (int iv : intervals) {
        int bin = iv - 4;
        if (bin >= 0 && bin < kNumBins) hist[bin]++;
    }
    // 找最高票间隔（含邻域平滑）
    int bestBin = 0, bestCnt = 0;
    for (int b = 0; b < kNumBins; b++) {
        int cnt = hist[b];
        for (int d = -1; d <= 1; d++) {  // 邻域投票
            int nb = b + d;
            if (nb >= 0 && nb < kNumBins) cnt += hist[nb] / 2;
        }
        if (cnt > bestCnt) { bestCnt = cnt; bestBin = b; }
    }
    int lag = bestBin + 4;
    // 半速检查：2*lag 处（更慢）计数接近 → 取基频（防倍频）
    for (int mult = 2; mult <= 4; mult++) {
        int mLag = lag * mult;
        if (mLag > 48) break;
        int mCnt = hist[mLag - 4];
        for (int d = -1; d <= 1; d++) {
            int nb = mLag - 4 + d;
            if (nb >= 0 && nb < kNumBins) mCnt += hist[nb] / 2;
        }
        if (mCnt > bestCnt) { lag = mLag; bestCnt = mCnt; }
    }
    // 【V8.25】快方向检查修复：旧实现读强峰直方图（45% 阈值只含 kick，hihat/snare 被滤掉）
    // → lag/2 处计数恒为 0 → 检查从不触发 → half-time kick 流行歌（真 90-100）测成 40 几。
    // 修复：用低阈值（10% 峰值）独立提取"所有拍"（含 hihat/snare 弱拍），
    // 在低阈值 beats 上统计 lag/2 间隔的连续命中数；≥ 最佳间隔计数 40% → 取快。
    if (lag >= 8) {
        int hLag = lag / 2;
        if (hLag >= 4) {
            int hCnt = 0;
            int prev = -1000;
            for (size_t i = 1; i + 1 < onset.size(); i++) {
                if (onset[i] > peak * 0.10f && onset[i] > onset[i - 1] && onset[i] > onset[i + 1]) {
                    if (prev >= 0) {
                        int gap = (int)i - prev;
                        if (gap >= hLag - 1 && gap <= hLag + 1) hCnt++;
                    }
                    prev = (int)i;
                }
            }
            if (hCnt >= bestCnt * 4 / 10) { lag = hLag; bestCnt = hCnt; }
        }
    }
    int bpm = (int)std::lround(60.f * 8000.f / (lag * (float)hop));
    if (bpm < 40) bpm *= 2;
    return (bpm >= 40 && bpm <= 220) ? bpm : -1;
}

// ================================================================
// 算法③：频谱通量 onset detection（对无人声/高频打击敏感）
// ================================================================
static int detectBySpectralFlux(const float* samples, int n, int sampleRate) {
    // 短时傅里叶变换（简化版：8 频段能量差异）
    const int winSize = 1024;
    const int hop = 512;
    const int numBands = 8;
    if (n < winSize * 4) return -1;
    // 计算每帧各频段能量
    std::vector<std::vector<float>> bandEn(numBands);
    for (int pos = 0; pos + winSize <= n; pos += hop) {
        float energy[8] = {0};
        for (int k = 0; k < winSize; k++) {
            float s = samples[pos + k];
            // 粗略分段能量
            int band = (k * numBands) / winSize;
            if (band >= numBands) band = numBands - 1;
            energy[band] += s * s;
        }
        for (int b = 0; b < numBands; b++) {
            bandEn[b].push_back(std::sqrt(energy[b] / winSize));
        }
    }
    if (bandEn[0].empty()) return -1;
    // 计算通量（相邻帧差异，正值累加）
    size_t frames = bandEn[0].size();
    std::vector<float> flux(frames, 0.f);
    for (size_t f = 1; f < frames; f++) {
        float sum = 0.f;
        for (int b = 0; b < numBands; b++) {
            float d = bandEn[b][f] - bandEn[b][f - 1];
            if (d > 0.f) sum += d;
        }
        flux[f] = sum;
    }
    // 通量 onset → 自相关（复用算法①的逻辑，但用 flux 做输入）
    float peak, invPeak;
    normalize(flux, peak, invPeak);
    return detectByAutocorr(flux, peak, invPeak, hop);
}

// ================================================================
// 能量包络计算（①和②共用）
// ================================================================
static void computeEnvelope(const float* samples, int n, int sampleRate,
                            std::vector<float>& env, std::vector<float>& onset,
                            int& outHop) {
    const int hop = 512;
    const int frame = 1024;
    outHop = hop;
    for (int pos = 0; pos + frame <= n; pos += hop) {
        float e = 0.f;
        for (int i = 0; i < frame; i++) {
            float s = samples[pos + i];
            e += s * s;
        }
        env.push_back(e / frame);
    }
    if (env.empty()) return;
    onset.resize(env.size());
    for (size_t i = 1; i < env.size(); i++) {
        float d = env[i] - env[i - 1];
        onset[i] = (d > 0.f) ? d : 0.f;
    }
}

// ================================================================
// 主入口：对一个 30s 窗口运行三算法，返回最佳 BPM（候选分不够也返回）
// ================================================================
static int detectWindowAlgorithms(const float* samples, int n, int sampleRate) {
    if (n < sampleRate * 4) return -1;
    std::vector<float> env, onset;
    int hop;
    computeEnvelope(samples, n, sampleRate, env, onset, hop);
    if (env.size() < 20) return -1;
    float peak, invPeak;
    normalize(env, peak, invPeak);
    if (peak < 1e-7f) return -1;
    // 三算法独立跑
    int b1 = detectByAutocorr(env, peak, invPeak, hop);
    int b2 = detectByIntervalHist(onset, hop, peak, invPeak);
    int b3 = detectBySpectralFlux(samples, n, sampleRate);
    // 【v8.15】以算法②（45% 强峰直方图）为节拍基准：b1/b3 若与 b2 成 2 倍关系 → 对齐到 b2
    // 原理：自相关对严格周期序列的整数倍 lag 得分几乎相同（128 与 64 无法靠自相关区分），
    //       但强峰（kick/重音）间隔直方图能直接给出"拍"周期，最可信。
    if (b2 > 0) {
        auto align = [&](int v) -> int {
            if (v <= 0) return v;
            if (std::abs(v - b2) <= 3) return b2;
            if (std::abs(v - 2 * b2) <= 3) return b2;   // v=2×b2 → b2（v 是倍频，b2 快）
            if (std::abs(2 * v - b2) <= 3) return b2;   // b2=2×v → b2（b2 是倍频，取 b2 快）
            return v;
        };
        b1 = align(b1);
        b3 = align(b3);
    }
    // 收集 >0 的候选
    int candidates[3] = {b1, b2, b3};
    int votes[3] = {0, 0, 0};
    int nCand = 0;
    for (int i = 0; i < 3; i++) {
        if (candidates[i] > 0) {
            bool merged = false;
            for (int j = 0; j < nCand; j++) {
                // 【v8.15】只 ±3 内算同类；倍频（128 vs 64）不算同类，各自计票
                if (std::abs(candidates[i] - candidates[j]) <= 3) {
                    votes[j]++; merged = true; break;
                }
            }
            if (!merged) { candidates[nCand] = candidates[i]; votes[nCand] = 1; nCand++; }
        }
    }
    if (nCand == 0) return -1;
    // 最高票；平局偏好快（数值大）——EDM 等强节拍音乐快解释更合理
    int bestIdx = 0;
    for (int i = 1; i < nCand; i++) {
        if (votes[i] > votes[bestIdx] ||
            (votes[i] == votes[bestIdx] && candidates[i] > candidates[bestIdx])) bestIdx = i;
    }
    return candidates[bestIdx];
}

// ================================================================
// 全曲滑动窗口主入口（n = 8kHz 单声道 float 总帧数）
// 返回：40..220 范围 BPM，失败 -1
// ================================================================
static int detectBpm(const float* samples8k, int n8k) {
    if (!samples8k || n8k < 8000 * 4) return -1;
    // 滑动窗口：30s 窗口，每 15s 滑动一次
    const int winSec = 30;
    const int stepSec = 15;
    const int winFrames = 8000 * winSec;
    const int stepFrames = 8000 * stepSec;
    int songCandidates[64];
    int nCand = 0;
    int bestBpm = -1;
    int bestVotes = 0;
    for (int start = 0; start + winFrames <= n8k; start += stepFrames) {
        int bpm = detectWindowAlgorithms(samples8k + start, winFrames, 8000);
        if (bpm > 0) {
            // 与已有候选合并投票
            bool merged = false;
            for (int i = 0; i < nCand; i++) {
                if (std::abs(bpm - songCandidates[i]) <= 3 ||
                    std::abs(bpm - songCandidates[i] * 2) <= 3 ||
                    std::abs(bpm * 2 - songCandidates[i]) <= 3) {
                    // 同类票数+1（直接叠代）
                    bestVotes++; merged = true; break;
                }
            }
            if (!merged) songCandidates[nCand++] = bpm;
        }
    }
    if (nCand == 0) return -1;
    // 全局候选聚类
    int cluster[64] = {0};
    int clusterSize[64] = {0};
    int nClust = 0;
    for (int i = 0; i < nCand; i++) {
        bool merged = false;
        for (int c = 0; c < nClust; c++) {
            // 【v8.15】只 ±3 内算同类；倍频不算（128 与 64 各自计票）
            if (std::abs(songCandidates[i] - cluster[c]) <= 3) {
                clusterSize[c]++; merged = true; break;
            }
        }
        if (!merged) { cluster[nClust] = songCandidates[i]; clusterSize[nClust] = 1; nClust++; }
    }
    if (nClust == 0) return -1;
    // 最大聚类；若次大簇与最大簇成 2 倍关系且票数接近 → 偏好快（EDM 主/副歌 pattern 变化时多数窗口测对）
    int bestClust = 0;
    for (int c = 1; c < nClust; c++) if (clusterSize[c] > clusterSize[bestClust]) bestClust = c;
    for (int c = 0; c < nClust; c++) {
        if (c == bestClust) continue;
        int fast = std::max(cluster[bestClust], cluster[c]);
        int slow = std::min(cluster[bestClust], cluster[c]);
        int fastSize = (cluster[bestClust] == fast) ? clusterSize[bestClust] : clusterSize[c];
        int slowSize = (cluster[bestClust] == slow) ? clusterSize[bestClust] : clusterSize[c];
        if (fast >= slow * 2 - 3 && fast <= slow * 2 + 3) {  // 2 倍关系
            // 【V8.25】快簇票数 >= 慢簇 40% → 选快（流行歌 half-time kick 主歌慢窗口多，
            // 副歌密集窗口测快簇票数常只有慢簇 40-70%；40% 阈值让真 90-100 歌恢复）
            if (fastSize >= slowSize * 4 / 10) {
                bestClust = (cluster[bestClust] == fast) ? bestClust : c;
            }
            break;
        }
    }
    int result = cluster[bestClust];
    // 后处理：容许 ±1 调整
    if (result < 40) result *= 2;
    return (result >= 40 && result <= 220) ? result : -1;
}

} // namespace bpmdet
