// alac_decoder_jni.cpp — Apple Lossless (ALAC) direct decode via official Apple codec
// JNI bridge: nativeAlacOpen/Start/Stop/Info/Pause/Seek/IsEos/PositionMs/TotalSamples
// Container: hand-rolled MP4/M4A box parser (stsd/stts/stsc/stsz/stco/co64/mdat)
// Directly feeds float samples into UsbAudioDriver ring buffer via driver->pushPcm()
//
// Decode() outputs interleaved INTEGER PCM (16/20/24/32-bit per magic-cookie bitDepth),
// so this bridge converts int→float before pushPcm (unlike FLAC's dr_flac f32 path).

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <thread>
#include <vector>
#include <string>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <algorithm>

#include "usb_audio_driver.h"
#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"
#include "alac/EndianPortable.h"

#define TAG "AlacDecoderJNI"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,   TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,    TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,    TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,   TAG, __VA_ARGS__)

// ── External: get the global UsbAudioDriver singleton ──────────────────────
extern UsbAudioDriver* sdw_getUsbDriver();  // defined in usb_audio_jni.cpp

// ── State ───────────────────────────────────────────────────────────────────
static ALACDecoder*  gAlac        = nullptr;
static std::thread*  gDecodeThread = nullptr;
static std::atomic<bool> gRunning{false};
static std::atomic<bool> gPaused{false};
static std::atomic<bool> gSeekPending{false};
static std::atomic<int64_t> gSeekTargetMs{0};
static std::atomic<bool> gEos{false};

// Cached ALAC config (from magic cookie)
static std::atomic<int> gSampleRate{0};
static std::atomic<int> gChannels{0};
static std::atomic<int> gBitsPerSample{0};
static std::atomic<int64_t> gTotalPcmFrames{0};
static std::atomic<int64_t> gDurationMs{0};
static std::atomic<int> gFrameLength{4096};

// Current decode position (PCM frames consumed by DacDriver)
static std::atomic<int64_t> gCurrentPcmFrame{0};

static std::string gCurrentPath;

// ── MP4 box parsing state ───────────────────────────────────────────────────
struct AlacSample {
    uint64_t offset;   // absolute file offset into mdat
    uint32_t size;     // bytes
    uint32_t duration; // frames (from stts)
};
static std::vector<AlacSample> gSamples;
static FILE* gFile = nullptr;

// ── Forward declarations ────────────────────────────────────────────────────
static void decodeLoop();

// ── Helpers ─────────────────────────────────────────────────────────────────
static int64_t pcmFramesToMs(int64_t frames) {
    int sr = gSampleRate.load();
    if (sr <= 0) return 0;
    return (frames * 1000LL) / sr;
}
static int64_t msToPcmFrames(int64_t ms) {
    int sr = gSampleRate.load();
    if (sr <= 0) return 0;
    return (ms * sr) / 1000LL;
}

// Big-endian readers (MP4 is big-endian)
static inline uint16_t be16(const uint8_t* p) { return (uint16_t)((p[0]<<8)|p[1]); }
static inline uint32_t be24(const uint8_t* p) { return ((uint32_t)p[0]<<16)|((uint32_t)p[1]<<8)|p[2]; }
static inline uint32_t be32(const uint8_t* p) { return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3]; }
static inline uint64_t be64(const uint8_t* p) {
    return ((uint64_t)be32(p)<<32) | be32(p+4);
}

// ── MP4 box traversal ───────────────────────────────────────────────────────
struct Mp4Box { uint64_t offset; uint64_t size; uint32_t type; };

// List direct child boxes in [start, end)
static std::vector<Mp4Box> listBoxes(uint64_t start, uint64_t end) {
    std::vector<Mp4Box> boxes;
    uint64_t pos = start;
    while (pos + 8 <= end) {
        uint8_t hdr[16];
        if (fseek(gFile, (long)pos, SEEK_SET) != 0) break;
        if (fread(hdr, 1, 16, gFile) < 8) break;
        uint64_t size = be32(hdr);
        uint32_t type = be32(hdr + 4);
        uint64_t hdrSize = 8;
        if (size == 1) {  // 64-bit largesize
            size = be64(hdr + 8);
            hdrSize = 16;
        } else if (size == 0) {
            size = end - pos;  // extends to parent end
        }
        if (size < hdrSize) break;
        boxes.push_back({pos, size, type});
        pos += size;
    }
    return boxes;
}

static Mp4Box findBox(uint32_t type, uint64_t start, uint64_t end) {
    for (auto& b : listBoxes(start, end)) {
        if (b.type == type) return b;
    }
    return {0, 0, 0};
}

// Read 4CC as uint32 (big-endian stored)
static uint32_t fourcc(const char* s) {
    return ((uint32_t)(uint8_t)s[0]<<24) | ((uint32_t)(uint8_t)s[1]<<16) |
           ((uint32_t)(uint8_t)s[2]<<8)  | ((uint32_t)(uint8_t)s[3]);
}

// ── stts: time-to-sample (run-length: [sample_count][sample_delta]) ─────────
// Expands into per-sample duration vector.
static bool parseStts(uint64_t boxOffset, uint64_t boxSize, std::vector<uint32_t>& outDurations) {
    uint64_t payload = boxOffset + 8;  // skip size+type
    uint64_t payloadEnd = boxOffset + boxSize;
    if (payload + 8 > payloadEnd) return false;

    uint8_t hdr[8];
    fseek(gFile, (long)payload, SEEK_SET);
    if (fread(hdr, 1, 8, gFile) != 8) return false;
    // version+flags (4), entry_count (4)
    uint32_t entryCount = be32(hdr + 4);

    uint64_t pos = payload + 8;
    for (uint32_t i = 0; i < entryCount; i++) {
        uint8_t e[8];
        if (pos + 8 > payloadEnd) break;
        fseek(gFile, (long)pos, SEEK_SET);
        if (fread(e, 1, 8, gFile) != 8) break;
        uint32_t count = be32(e);
        uint32_t delta = be32(e + 4);
        for (uint32_t j = 0; j < count; j++) outDurations.push_back(delta);
        pos += 8;
    }
    return !outDurations.empty();
}

// ── stsz: sample sizes (constant size or per-sample) ────────────────────────
static bool parseStsz(uint64_t boxOffset, uint64_t boxSize, uint32_t sampleCount, std::vector<uint32_t>& outSizes) {
    uint64_t payload = boxOffset + 8;
    uint64_t payloadEnd = boxOffset + boxSize;
    if (payload + 12 > payloadEnd) return false;

    uint8_t hdr[12];
    fseek(gFile, (long)payload, SEEK_SET);
    if (fread(hdr, 1, 12, gFile) != 12) return false;
    uint32_t sampleSize = be32(hdr + 4);      // if != 0, all samples same size
    uint32_t entryCount = be32(hdr + 8);

    outSizes.clear();
    if (sampleSize != 0) {
        outSizes.assign(sampleCount > 0 ? sampleCount : entryCount, sampleSize);
        return true;
    }
    // per-sample sizes
    uint64_t pos = payload + 12;
    for (uint32_t i = 0; i < entryCount && i < sampleCount; i++) {
        uint8_t e[4];
        if (pos + 4 > payloadEnd) break;
        fseek(gFile, (long)pos, SEEK_SET);
        if (fread(e, 1, 4, gFile) != 4) break;
        outSizes.push_back(be32(e));
        pos += 4;
    }
    return !outSizes.empty();
}

// ── stco / co64: chunk offsets ──────────────────────────────────────────────
static bool parseChunkOffsets(uint32_t type, uint64_t boxOffset, uint64_t boxSize, std::vector<uint64_t>& outOffsets) {
    uint64_t payload = boxOffset + 8;
    uint64_t payloadEnd = boxOffset + boxSize;
    if (payload + 8 > payloadEnd) return false;

    uint8_t hdr[8];
    fseek(gFile, (long)payload, SEEK_SET);
    if (fread(hdr, 1, 8, gFile) != 8) return false;
    uint32_t entryCount = be32(hdr + 4);

    uint64_t pos = payload + 8;
    for (uint32_t i = 0; i < entryCount; i++) {
        if (type == fourcc("co64")) {
            uint8_t e[8];
            if (pos + 8 > payloadEnd) break;
            fseek(gFile, (long)pos, SEEK_SET);
            if (fread(e, 1, 8, gFile) != 8) break;
            outOffsets.push_back(be64(e));
            pos += 8;
        } else {  // stco, 32-bit
            uint8_t e[4];
            if (pos + 4 > payloadEnd) break;
            fseek(gFile, (long)pos, SEEK_SET);
            if (fread(e, 1, 4, gFile) != 4) break;
            outOffsets.push_back(be32(e));
            pos += 4;
        }
    }
    return !outOffsets.empty();
}

// ── stsc: sample-to-chunk (run-length: [first_chunk][samples_per_chunk][desc_idx]) ──
// Expands into a per-chunk "samples per chunk" array, sized to chunkCount.
static bool parseStsc(uint64_t boxOffset, uint64_t boxSize, uint32_t chunkCount, std::vector<uint32_t>& outSamplesPerChunk) {
    uint64_t payload = boxOffset + 8;
    uint64_t payloadEnd = boxOffset + boxSize;
    if (payload + 8 > payloadEnd) return false;

    uint8_t hdr[8];
    fseek(gFile, (long)payload, SEEK_SET);
    if (fread(hdr, 1, 8, gFile) != 8) return false;
    uint32_t entryCount = be32(hdr + 4);

    struct StscEntry { uint32_t firstChunk; uint32_t samplesPerChunk; uint32_t descIdx; };
    std::vector<StscEntry> entries;
    uint64_t pos = payload + 8;
    for (uint32_t i = 0; i < entryCount; i++) {
        uint8_t e[12];
        if (pos + 12 > payloadEnd) break;
        fseek(gFile, (long)pos, SEEK_SET);
        if (fread(e, 1, 12, gFile) != 12) break;
        entries.push_back({be32(e), be32(e+4), be32(e+8)});
        pos += 12;
    }
    if (entries.empty()) return false;

    outSamplesPerChunk.assign(chunkCount, 0);
    for (size_t i = 0; i < entries.size(); i++) {
        uint32_t fromChunk = entries[i].firstChunk;
        uint32_t toChunk = (i + 1 < entries.size()) ? (entries[i+1].firstChunk - 1) : chunkCount;
        if (fromChunk < 1) fromChunk = 1;
        for (uint32_t c = fromChunk; c <= toChunk && c <= chunkCount; c++) {
            outSamplesPerChunk[c - 1] = entries[i].samplesPerChunk;
        }
    }
    return true;
}

// ── stsd: extract ALAC magic cookie (24 bytes ALACSpecificConfig) ───────────
static bool parseStsdAlacCookie(uint64_t boxOffset, uint64_t boxSize, uint8_t* outCookie, uint32_t* outCookieSize) {
    uint64_t payload = boxOffset + 8;
    uint64_t payloadEnd = boxOffset + boxSize;
    if (payload + 8 > payloadEnd) return false;

    uint8_t hdr[8];
    fseek(gFile, (long)payload, SEEK_SET);
    if (fread(hdr, 1, 8, gFile) != 8) return false;
    uint32_t entryCount = be32(hdr + 4);  // version+flags(4) + entry_count(4)
    (void)entryCount;

    // First sample entry
    uint64_t entryOffset = payload + 8;
    if (entryOffset + 8 > payloadEnd) return false;
    uint8_t ehdr[8];
    fseek(gFile, (long)entryOffset, SEEK_SET);
    if (fread(ehdr, 1, 8, gFile) != 8) return false;
    uint64_t entrySize = be32(ehdr);
    uint32_t entryType = be32(ehdr + 4);
    if (entryType != fourcc("alac")) return false;

    uint64_t entryStart = entryOffset + 8;          // skip size+type
    uint64_t entryEnd   = entryOffset + entrySize;
    if (entryEnd > payloadEnd) entryEnd = payloadEnd;

    // AudioSampleEntry header: 28 bytes (version 0)
    // Then remaining sub-atoms ('alac' box directly, or 'wave'->'alac')
    const uint64_t audioHeaderSize = 28;
    uint64_t subStart = entryStart + audioHeaderSize;

    // Search for 'alac' box in sub-atoms (and inside 'wave' if present)
    for (auto& sub : listBoxes(subStart, entryEnd)) {
        if (sub.type == fourcc("alac")) {
            // payload = version+flags(4) + ALACSpecificConfig(24)  (standard FullBox)
            uint64_t p = sub.offset + 8;
            uint64_t pEnd = sub.offset + sub.size;
            uint32_t cookieSize = 24;
            if (pEnd - p >= 4 + 24) {
                fseek(gFile, (long)(p + 4), SEEK_SET);   // skip version+flags
            } else if (pEnd - p >= 24) {
                fseek(gFile, (long)p, SEEK_SET);
            } else {
                return false;
            }
            if (fread(outCookie, 1, cookieSize, gFile) != cookieSize) return false;
            *outCookieSize = cookieSize;
            return true;
        } else if (sub.type == fourcc("wave")) {
            for (auto& w : listBoxes(sub.offset + 8, sub.offset + sub.size)) {
                if (w.type == fourcc("alac")) {
                    uint64_t p = w.offset + 8;
                    uint64_t pEnd = w.offset + w.size;
                    uint32_t cookieSize = 24;
                    if (pEnd - p >= 4 + 24) {
                        fseek(gFile, (long)(p + 4), SEEK_SET);
                    } else if (pEnd - p >= 24) {
                        fseek(gFile, (long)p, SEEK_SET);
                    } else {
                        return false;
                    }
                    if (fread(outCookie, 1, cookieSize, gFile) != cookieSize) return false;
                    *outCookieSize = cookieSize;
                    return true;
                }
            }
        }
    }
    return false;
}

// ── Full sample table build: locate soun trak → stbl → build gSamples ───────
static bool buildSampleTable() {
    if (!gFile) return false;

    // File size
    fseek(gFile, 0, SEEK_END);
    long fileSize = ftell(gFile);
    fseek(gFile, 0, SEEK_SET);
    if (fileSize <= 0) return false;

    // Find moov
    Mp4Box moov = findBox(fourcc("moov"), 0, (uint64_t)fileSize);
    if (moov.type == 0) { LOGE("buildSampleTable: moov not found"); return false; }
    uint64_t moovPayloadStart = moov.offset + 8;
    uint64_t moovEnd = moov.offset + moov.size;

    // Iterate traks, find the one with hdlr == 'soun'
    Mp4Box audioTrak{0,0,0};
    for (auto& trak : listBoxes(moovPayloadStart, moovEnd)) {
        if (trak.type != fourcc("trak")) continue;
        // trak → mdia → hdlr
        Mp4Box mdia = findBox(fourcc("mdia"), trak.offset + 8, trak.offset + trak.size);
        if (mdia.type == 0) continue;
        Mp4Box hdlr = findBox(fourcc("hdlr"), mdia.offset + 8, mdia.offset + mdia.size);
        if (hdlr.type == 0) continue;
        // hdlr full box: version+flags(4) + pre_defined(4) + handler_type(4)
        uint8_t hb[12];
        fseek(gFile, (long)(hdlr.offset + 8), SEEK_SET);
        if (fread(hb, 1, 12, gFile) != 12) continue;
        uint32_t handlerType = be32(hb + 8);
        if (handlerType == fourcc("soun")) {
            audioTrak = trak;
            break;
        }
    }
    if (audioTrak.type == 0) { LOGE("buildSampleTable: no soun trak"); return false; }

    // trak → mdia → minf → stbl
    Mp4Box mdia = findBox(fourcc("mdia"), audioTrak.offset + 8, audioTrak.offset + audioTrak.size);
    if (mdia.type == 0) return false;
    Mp4Box minf = findBox(fourcc("minf"), mdia.offset + 8, mdia.offset + mdia.size);
    if (minf.type == 0) return false;
    Mp4Box stbl = findBox(fourcc("stbl"), minf.offset + 8, minf.offset + minf.size);
    if (stbl.type == 0) { LOGE("buildSampleTable: stbl not found"); return false; }
    uint64_t stblPayloadStart = stbl.offset + 8;
    uint64_t stblEnd = stbl.offset + stbl.size;

    // Locate stsd/stts/stsc/stsz/stco/co64
    Mp4Box stsd{0,0,0}, stts{0,0,0}, stsc{0,0,0}, stsz{0,0,0}, stco{0,0,0}, co64{0,0,0};
    for (auto& b : listBoxes(stblPayloadStart, stblEnd)) {
        if (b.type == fourcc("stsd")) stsd = b;
        else if (b.type == fourcc("stts")) stts = b;
        else if (b.type == fourcc("stsc")) stsc = b;
        else if (b.type == fourcc("stsz")) stsz = b;
        else if (b.type == fourcc("stco")) stco = b;
        else if (b.type == fourcc("co64")) co64 = b;
    }
    if (stsd.type == 0 || stts.type == 0 || stsz.type == 0) {
        LOGE("buildSampleTable: missing stsd/stts/stsz");
        return false;
    }

    // Extract ALAC cookie
    uint8_t cookie[24];
    uint32_t cookieSize = 0;
    if (!parseStsdAlacCookie(stsd.offset, stsd.size, cookie, &cookieSize)) {
        LOGE("buildSampleTable: ALAC cookie extraction failed");
        return false;
    }

    // Init decoder
    gAlac = new ALACDecoder();
    int32_t initStatus = gAlac->Init(cookie, cookieSize);
    if (initStatus != 0) {
        LOGE("buildSampleTable: ALACDecoder::Init failed status=%d", initStatus);
        delete gAlac; gAlac = nullptr;
        return false;
    }

    // Read config for stream params
    gSampleRate    = gAlac->mConfig.sampleRate;
    gChannels      = gAlac->mConfig.numChannels;
    gBitsPerSample = gAlac->mConfig.bitDepth;
    gFrameLength   = gAlac->mConfig.frameLength > 0 ? gAlac->mConfig.frameLength : 4096;

    LOGI("buildSampleTable: cookie ok sr=%d ch=%d bits=%d frameLength=%d",
         gSampleRate.load(), gChannels.load(), gBitsPerSample.load(), gFrameLength.load());

    // Parse stts (durations)
    std::vector<uint32_t> durations;
    if (!parseStts(stts.offset, stts.size, durations)) { LOGE("buildSampleTable: stts parse failed"); return false; }
    uint32_t sampleCount = (uint32_t)durations.size();

    // Parse stsz (sizes)
    std::vector<uint32_t> sizes;
    if (!parseStsz(stsz.offset, stsz.size, sampleCount, sizes)) { LOGE("buildSampleTable: stsz parse failed"); return false; }
    if (sizes.size() < sampleCount) sampleCount = (uint32_t)sizes.size();

    // Parse chunk offsets
    std::vector<uint64_t> chunkOffsets;
    Mp4Box coBox = (co64.type != 0) ? co64 : stco;
    if (coBox.type == 0) { LOGE("buildSampleTable: no stco/co64"); return false; }
    if (!parseChunkOffsets(coBox.type, coBox.offset, coBox.size, chunkOffsets)) {
        LOGE("buildSampleTable: chunk offset parse failed");
        return false;
    }
    uint32_t chunkCount = (uint32_t)chunkOffsets.size();

    // Parse stsc (samples per chunk)
    std::vector<uint32_t> samplesPerChunk;
    if (stsc.type != 0) {
        if (!parseStsc(stsc.offset, stsc.size, chunkCount, samplesPerChunk)) {
            LOGE("buildSampleTable: stsc parse failed");
            return false;
        }
    } else {
        // No stsc: assume 1 sample per chunk (common for audio)
        samplesPerChunk.assign(chunkCount, 1);
    }

    // Build per-sample offset/size/duration
    gSamples.clear();
    uint32_t sampleIdx = 0;
    for (uint32_t c = 0; c < chunkCount && sampleIdx < sampleCount; c++) {
        uint32_t spc = samplesPerChunk[c];
        uint64_t chunkDataOffset = chunkOffsets[c];
        for (uint32_t s = 0; s < spc && sampleIdx < sampleCount; s++) {
            AlacSample smp;
            smp.offset   = chunkDataOffset;
            smp.size     = sizes[sampleIdx];
            smp.duration = durations[sampleIdx];
            gSamples.push_back(smp);
            chunkDataOffset += sizes[sampleIdx];
            sampleIdx++;
        }
    }

    if (gSamples.empty()) { LOGE("buildSampleTable: zero samples"); return false; }

    // Total PCM frames = sum of durations
    int64_t total = 0;
    for (auto& s : gSamples) total += s.duration;
    gTotalPcmFrames = total;
    gDurationMs     = pcmFramesToMs(total);

    LOGI("buildSampleTable: %zu samples, total=%lld frames, dur=%lldms",
         gSamples.size(), (long long)total, (long long)gDurationMs.load());
    return true;
}

// ── int → float conversion (ALAC Decode outputs interleaved int PCM) ────────
static inline float int16ToFloat(const uint8_t* p) {
    int16_t v = (int16_t)((p[0] << 8) | p[1]);  // big-endian? No: Decode outputs native little-endian on ARM
    // Decode writes via raw byte copy in matrix_dec.c using HBYTE/MBYTE/LBYTE (little-endian on ARM)
    return (float)v / 32768.0f;
}

// Correct little-endian int readers (ARM is little-endian; matrix_dec.c LBYTE=0)
static inline float le16ToFloat(const uint8_t* p) {
    int16_t v;
    memcpy(&v, p, 2);
    return (float)v / 32768.0f;
}
static inline float le24ToFloat(const uint8_t* p) {
    int32_t v = (int32_t)(p[0] | (p[1] << 8) | (p[2] << 16));
    if (v & 0x800000) v |= (int32_t)0xFF000000;  // sign-extend
    return (float)v / 8388608.0f;   // 2^23
}
static inline float le32ToFloat(const uint8_t* p) {
    int32_t v;
    memcpy(&v, p, 4);
    return (float)v / 2147483648.0f; // 2^31
}

// Convert interleaved int PCM → interleaved float
static void convertToFloat(const uint8_t* in, float* out, int frames, int channels, int bits) {
    if (bits == 16) {
        for (int i = 0; i < frames * channels; i++) out[i] = le16ToFloat(in + i * 2);
    } else if (bits == 24 || bits == 20) {
        for (int i = 0; i < frames * channels; i++) out[i] = le24ToFloat(in + i * 3);
    } else {  // 32 (and fallback)
        for (int i = 0; i < frames * channels; i++) out[i] = le32ToFloat(in + i * 4);
    }
}

// ── JNI implementation ──────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeAlacOpen(
    JNIEnv* env, jobject /*this*/, jstring jpath) {

    // Stop previous
    if (gRunning) {
        gRunning = false;
        if (gDecodeThread && gDecodeThread->joinable()) {
            gDecodeThread->join();
            delete gDecodeThread;
            gDecodeThread = nullptr;
        }
    }
    if (gAlac) { delete gAlac; gAlac = nullptr; }
    if (gFile) { fclose(gFile); gFile = nullptr; }
    gSamples.clear();

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) { LOGE("nativeAlacOpen: GetStringUTFChars null"); return JNI_FALSE; }
    gCurrentPath = path;
    env->ReleaseStringUTFChars(jpath, path);

    LOGI("nativeAlacOpen: opening \"%s\"", gCurrentPath.c_str());
    gFile = fopen(gCurrentPath.c_str(), "rb");
    if (!gFile) { LOGE("nativeAlacOpen: fopen failed"); gEos = true; return JNI_FALSE; }

    if (!buildSampleTable()) {
        LOGE("nativeAlacOpen: buildSampleTable failed");
        if (gAlac) { delete gAlac; gAlac = nullptr; }
        fclose(gFile); gFile = nullptr;
        gSamples.clear();
        gEos = true;
        return JNI_FALSE;
    }

    gEos = false;
    gPaused = false;
    gSeekPending = false;
    gCurrentPcmFrame = 0;

    LOGI("nativeAlacOpen: OK sr=%d ch=%d bits=%d dur=%lldms",
         gSampleRate.load(), gChannels.load(), gBitsPerSample.load(), (long long)gDurationMs.load());
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeAlacInfo(
    JNIEnv* env, jobject /*this*/) {
    jintArray result = env->NewIntArray(4);
    if (!result) return nullptr;
    jint info[4] = {
        gSampleRate.load(),
        gChannels.load(),
        gBitsPerSample.load(),
        (jint)gDurationMs.load()
    };
    env->SetIntArrayRegion(result, 0, 4, info);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeAlacStart(
    JNIEnv* /*env*/, jobject /*this*/) {
    if (!gAlac || !gFile) { LOGE("nativeAlacStart: no open stream"); return JNI_FALSE; }
    if (gRunning) { LOGW("nativeAlacStart: already running"); return JNI_TRUE; }

    gRunning = true;
    gEos = false;
    gPaused = false;

    if (gDecodeThread && gDecodeThread->joinable()) {
        gDecodeThread->join();
        delete gDecodeThread;
    }
    gDecodeThread = new std::thread(decodeLoop);
    LOGI("nativeAlacStart: decode thread spawned");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeAlacPause(
    JNIEnv* /*env*/, jobject /*this*/, jboolean paused) {
    gPaused = paused;
    LOGI("nativeAlacPause: %s", paused ? "PAUSED" : "RESUMED");
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeAlacSeek(
    JNIEnv* /*env*/, jobject /*this*/, jlong ms) {
    gSeekTargetMs = ms;
    gSeekPending = true;
    LOGD("nativeAlacSeek: targetMs=%lld", (long long)ms);
}

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeAlacStop(
    JNIEnv* /*env*/, jobject /*this*/) {
    gRunning = false;
    if (gDecodeThread && gDecodeThread->joinable()) {
        gDecodeThread->join();
        delete gDecodeThread;
        gDecodeThread = nullptr;
    }
    if (gAlac) { delete gAlac; gAlac = nullptr; }
    if (gFile) { fclose(gFile); gFile = nullptr; }
    gSamples.clear();
    gEos = true;
    LOGI("nativeAlacStop: stopped");
}

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeAlacIsEos(
    JNIEnv* /*env*/, jobject /*this*/) {
    return gEos ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeAlacPositionMs(
    JNIEnv* /*env*/, jobject /*this*/) {
    auto* drv = sdw_getUsbDriver();
    if (!drv || !gAlac) return pcmFramesToMs(gCurrentPcmFrame);
    int ringFill = drv->getRingFillFrames();
    int64_t consumedFrames = gCurrentPcmFrame - ringFill;
    if (consumedFrames < 0) consumedFrames = 0;
    return pcmFramesToMs(consumedFrames);
}

JNIEXPORT jlong JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeAlacTotalSamples(
    JNIEnv* /*env*/, jobject /*this*/) {
    return gTotalPcmFrames.load();
}

} // extern "C"

// ── Decode loop: iterate samples → ALAC Decode → int→float → pushPcm ────────
static void decodeLoop() {
    LOGI("alacDecodeLoop: started");

    auto* drv = sdw_getUsbDriver();
    if (!drv) { LOGE("alacDecodeLoop: no UsbAudioDriver"); gEos = true; return; }

    const int channels = gChannels.load();
    const int sr       = gSampleRate.load();
    const int bits     = gBitsPerSample.load();
    const int frameLen = gFrameLength.load();
    if (channels < 1 || channels > 8 || sr < 1 || frameLen < 1) {
        LOGE("alacDecodeLoop: invalid params ch=%d sr=%d frameLen=%d", channels, sr, frameLen);
        gEos = true;
        return;
    }

    const int targetFillFrames = (sr * 200) / 1000;  // ~200ms backpressure

    // Buffers
    const int maxOutputBytes = frameLen * channels * 4;   // 32-bit worst case
    std::vector<uint8_t> intPcm(maxOutputBytes);
    // 零初始化：BitBufferRead 末尾可能越界读 cur[0..2]，garbage padding 会误读 partialFrame/numSamples
    std::vector<uint8_t> packet(maxOutputBytes + 16, 0);  // compressed packet (maxFrameBytes + escape header)
    std::vector<float> floatBuf((size_t)frameLen * channels);

    BitBuffer bitBuf;

    int64_t sampleIdx = 0;
    int64_t framePos = 0;  // PCM frames fed so far (used for seek)

    while (gRunning && gAlac && gFile) {
        // ── Seek handling ────────────────────────────────
        if (gSeekPending.exchange(false)) {
            int64_t targetFrame = msToPcmFrames(gSeekTargetMs.load());
            if (targetFrame < 0) targetFrame = 0;
            if (targetFrame >= gTotalPcmFrames.load()) targetFrame = gTotalPcmFrames.load() - 1;

            // Find sample containing targetFrame
            int64_t acc = 0;
            int64_t newIdx = 0;
            for (int64_t i = 0; i < (int64_t)gSamples.size(); i++) {
                if (acc + gSamples[i].duration > targetFrame) { newIdx = i; break; }
                acc += gSamples[i].duration;
                newIdx = i;
            }
            sampleIdx = newIdx;
            framePos  = acc;
            gCurrentPcmFrame = acc;
            drv->resetRingBuffer();
            gSeekTargetMs = 0;
            gEos = false;
            LOGI("alacDecodeLoop: seek to frame %lld (sample %lld)", (long long)targetFrame, (long long)sampleIdx);
        }

        // ── Pause ────────────────────────────────────────
        if (gPaused) { std::this_thread::sleep_for(std::chrono::milliseconds(50)); continue; }

        // ── Backpressure ─────────────────────────────────
        int ringFill = drv->getRingFillFrames();
        if (ringFill >= targetFillFrames) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        // ── End of samples ───────────────────────────────
        if (sampleIdx >= (int64_t)gSamples.size()) {
            LOGI("alacDecodeLoop: EOS at frame %lld", (long long)framePos);
            gEos = true;
            int drainRetries = 0;
            while (drv->getRingFillFrames() > 0 && gRunning && drainRetries < 200) {
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                drainRetries++;
            }
            break;
        }

        // ── Read one compressed packet ───────────────────
        const AlacSample& smp = gSamples[sampleIdx];
        if (smp.size == 0 || smp.size > packet.size()) {
            LOGE("alacDecodeLoop: bad packet size %u at sample %lld", smp.size, (long long)sampleIdx);
            sampleIdx++;
            continue;
        }
        fseek(gFile, (long)smp.offset, SEEK_SET);
        size_t got = fread(packet.data(), 1, smp.size, gFile);
        if (got != smp.size) {
            LOGE("alacDecodeLoop: short read %zu/%u at sample %lld", got, smp.size, (long long)sampleIdx);
            sampleIdx++;
            continue;
        }

        // ── Decode ───────────────────────────────────────
        BitBufferInit(&bitBuf, packet.data(), smp.size);
        uint32_t outFrames = 0;
        LOGI("alacDecodeLoop: dec sample=%lld size=%u bits=%d", (long long)sampleIdx, smp.size, bits);
        int32_t status = gAlac->Decode(&bitBuf, intPcm.data(), (uint32_t)frameLen, (uint32_t)channels, &outFrames);
        LOGI("alacDecodeLoop: dec done sample=%lld status=%d outFrames=%u", (long long)sampleIdx, status, outFrames);
        if (status != 0) {
            LOGE("alacDecodeLoop: Decode failed status=%d at sample %lld", status, (long long)sampleIdx);
            sampleIdx++;
            continue;
        }
        if (outFrames == 0) { sampleIdx++; continue; }

        // ── int → float ──────────────────────────────────
        convertToFloat(intPcm.data(), floatBuf.data(), (int)outFrames, channels, bits);

        // ── Push to ring ─────────────────────────────────
        int pushed = drv->pushPcm(floatBuf.data(), (int)outFrames);
        if (pushed <= 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        gCurrentPcmFrame += (int64_t)outFrames;
        framePos += (int64_t)outFrames;
        sampleIdx++;
    }

    gRunning = false;
    LOGI("alacDecodeLoop: exited (frames fed=%lld)", (long long)gCurrentPcmFrame.load());
}
