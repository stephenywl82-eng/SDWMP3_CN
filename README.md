# 🎵 Moto Music Pro v7.0 — Audiophile-Grade Pure AAudio Music Player

A modern Android music player built with Jetpack Compose and Material 3. **Pure AAudio Direct pipeline** — no ExoPlayer fallback, no Android mixer, no SRC resampling.

## ✨ Key Features

- 🔊 **USB DAC Exclusive Mode** — Direct USB Audio Class access via hand-rolled USB Host API (USBDEVFS_CLAIM + ISO URB), completely bypassing Android's audio framework for true Bit-Perfect output
- 🎵 **libFLAC + dr_wav Native Decoding** — Built-in native decoders supporting lossless FLAC/WAV at any bit depth / sample rate, plus NDK MediaCodec for MP3/AAC/Opus/OGG/M4A
- ⚡ **Pure AAudio Direct Pipeline** — Single path: native decode → AAudio Shared → hardware. No ExoPlayer, no AudioFlinger mixer, no SRC
- 🎛️ **MSEB 10‑Band Psychoacoustic EQ** — 10‑dimensional sound shaping (temperature, thickness, sibilance, sub‑bass, bass texture, vocal forwardness, female overtones, sibilance LF/HF, air, impulse response) mapped to 5 native Biquad DSP bands with A/B instant comparison
- 🔬 **Double‑Precision Biquad DSP** — 53‑bit mantissa coefficients and state registers eliminate quantization noise across cascaded IIR stages; hardware FTZ (FPCR FZ) for zero‑cost denormal protection
- 📊 **8‑Segment Real‑time FFT Spectrum** — Cascaded LP‑diff analysis (CDJ/DJM style) with 8 frequency bands displayed as animated bar graph in MSEB screen
- 🔗 **Gapless Playback** — Seamless track transitions for uninterrupted listening
- 💿 **CUE Sheet Support** — Auto‑loads matching .cue files for gapless playback of single‑file albums
- 🎚️ **29‑Preset 5‑Band Native DSP EQ** — Frequency, gain, and Q per band, all processed in C++ with zero Android framework involvement
- 📋 **Custom Playlists** — Create, edit, and manage playlists with search filtering
- 🎨 **Material You Dynamic Colors** — Full app color scheme follows Android system accent (Material 3 dynamic color)
- 📝 **Auto Lyrics** — LRCLIB automatic lyric search with local LRC file fallback and manual editing
- 🌊 **Aurora Background** — Flowing aurora visual effects matching album colors
- 📱 **Curved Edge Glow** — Breathing edge light effect for curved displays
- 🔊 **Audio Quality Analyzer** — Batch‑scan local files for spectral analysis: cutoff frequency, dynamic range, clipping, fake‑lossless detection with scoring
- 🪩 **VU Meters** — Analog needle + Mixer‑style VU visualization
- 🎯 **Adaptive DAC Profiles** — Auto‑detect USB DAC capabilities (clock ranges, alt settings, wire format) via descriptor parsing

## 🛠️ Tech Stack

| Layer | Technology |
|------|------|
| **UI** | Jetpack Compose + Material 3 |
| **Architecture** | MVI + Hilt DI + StateFlow |
| **Audio** | Native C++ (AAudio/oboe + libFLAC + dr_wav + NDK MediaCodec) + USB Audio Class |
| **DSP** | 5‑Band RBJ Cookbook Biquad (double‑precision) + Look‑Ahead Limiter + softClip + TPDF Dither |
| **Images** | Coil + Palette API |
| **Build** | Gradle KTS + R8 + ProGuard + NDK r25c |

## 📦 Download

Latest APK: [MotoMusicPro_v7.0.apk](https://github.com/stephenywl82-eng/SDWMP2/raw/master/MotoMusicPro_v7.0.apk)

> ⚠️ **Requirements**: Android 7.0 (API 24) or above. A USB DAC is recommended for the best audio quality.

## 🚀 Build from Source

```bash
git clone https://github.com/stephenywl82-eng/SDWMP2.git
cd SDWMP2

# Build native .so first (manual NDK r25c, not Gradle CMake)
powershell -ExecutionPolicy Bypass -File build_so.ps1

# Build Debug APK
./gradlew assembleDebug

# Build Release APK (keystore required)
./gradlew assembleRelease
```

### Requirements
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 35
- NDK r25c (for building oboe_bridge.so: AAudio + libFLAC + USB Audio + DSP)

## 📁 Project Structure

```
app/src/main/java/com/sdw/music/player/
├── core/
│   ├── audio/          # MusicService, USB DAC, Oboe, EQ, MSEB, DacProfile, Visualizer
│   ├── lyrics/         # Lyric parsing, LRCLIB integration
│   └── model/          # Song, PlayerState data models
├── di/                 # Hilt dependency injection modules
├── ui/
│   ├── components/     # Reusable Compose components (VU meters, DefaultCoverImage, etc.)
│   ├── navigation/      # Top-level navigation
│   ├── screens/        # All screens (player, MSEB, EQ, lyrics, audio-quality, etc.)
│   ├── theme/          # Material 3 dynamic color theme
│   └── viewmodel/      # PlayerViewModel, SongListViewModel
├── utils/              # CUE parser, utilities
└── MainActivity.kt

app/src/main/cpp/       # Native engine: AAudio + libFLAC + USB Audio + DSP (C++)
analyzer/               # Audio Quality Analyzer library module (FFT spectrum analysis)
```

## 🎯 Audio Architecture

```
                    ┌─────────────────────┐
 FLAC/WAV ────────▶│  libFLAC / dr_wav    │──── float PCM ────┐
                    │  (C++ native decode) │                   │
                    └─────────────────────┘                   │
                                                              ▼
 MP3/AAC/Opus ───▶│  NDK MediaCodec      │──── float PCM ──▶ │ 5-Band Biquad DSP │
                  │  (system decode)     │                   │ (double-precision)  │
                  └─────────────────────┘                   │ + Limiter + Dither  │
                                                             └────────┬───────────┘
                                                                      │
                              ┌───────────────────────────────────────┘
                              ▼
                    ┌──────────────────┐      ┌──────────────┐
                    │  AAudio Shared   │─────▶│  Hardware    │
                    │  (bypass SRC)    │      │  (Speaker /  │
                    └──────────────────┘      │   Headphones │
                                              └──────────────┘

 USB DAC path (TTGK 33C0 + compatible DACs):
                    ┌──────────────────┐      ┌──────────────┐
 float PCM ───────▶│  USB Host API    │─────▶│  USB DAC     │
                    │  (USBDEVFS_CLAIM)│      │  (Bit-Perfect)│
                    └──────────────────┘      └──────────────┘
```

- **AAudio Direct**: File → native decode → 5‑Band DSP → AAudio Shared → hardware (no SRC, no mixer)
- **USB DAC Exclusive**: File → native decode → USB Host API (ISO URB) → DAC (true Bit‑Perfect)
- Adaptive DAC selection via `DacProfile.kt` with descriptor parsing for clock ranges, alt settings, and wire format

## 📄 License

MIT License — see [LICENSE](LICENSE).

---

*Designed for audiophiles who demand bit‑perfect playback.*
