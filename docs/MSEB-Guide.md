# MSEB Custom Tuning · Illustrated Guide

> One line: MSEB is the built-in **psychoacoustic sound console** in the SDW player. With **13 perceptual sliders + a few pro modules**, it lets you shape the same pair of headphones / speakers into the sound you like — **no EQ knowledge required**.

---

## 1. How to open it

1. On any song's **play screen** → top-right audio-effects menu → find **MSEB** (the toggle is a "B" badge).
2. Once open, there's a **Realtime Spectrum** at the top so you can *see* the change as you tune.
3. All your MSEB tuning is included in **Settings → Backup / Export** as a JSON file — import it on another phone to restore instantly (analysis caches and playback position are never included).

---

## 2. Key idea first: it tunes "perceptual dimensions", not frequency bands

A traditional EQ speaks engineering ("add 3 dB at 100 Hz"). MSEB is different — **each slider represents a perceived character**, and its two ends are antonyms (e.g. "Cool ↔ Warm"). Push it one way and the sound moves that direction. No Hz math needed.

> Not sure what a slider does? **Tap / long-press the small text under it** — the app shows an official one-line description (this guide is built from those descriptions).

---

## 3. The 13 sliders, one by one

Each row below maps to one slider on screen. Left / right = the two ends of that slider.

| # | Slider | What it controls | Left (cool) ↔ Right (warm) | Quick tip |
|---|--------|------------------|----------------------------|-----------|
| 1 | Temperature | Mid-low ↔ high frequency energy ratio | Cool ↔ Warm | Harsh / thin → **left**; muffled → **right** |
| 2 | Thickness | Body of vocal / instrument fundamental band | Thin ↔ Thick | Thin, hollow vocals → **right** |
| 3 | Vocal | Vocal imaging, front/back position | Distant ↔ Forward | Want clearer lyrics → **right** (bring forward) |
| 4 | Sub-bass | Sub-bass depth and impact | Lean ↔ Deep | Want that "punch" → **right** |
| 5 | Bass Texture | Bass elasticity and tightness | Loose ↔ Tight | Bloated, boomy bass → **right** (tighten) |
| 6 | Sibilance | Overall sibilance brightness | Smooth ↔ Bright | Hissy "s" vocals → **left** |
| 7 | Sibilance LF | **Precisely** tame ~6 kHz harshness | 0 ↔ cut | For a specific frequency only — surgical |
| 8 | Sibilance HF | **Precisely** tame ~9 kHz piercing | 0 ↔ cut | Same, for the higher spike |
| 9 | Female Overtones | Female vocal sweetness and lushness | Soft ↔ Sweet | Dry, thin female voice → **right** |
| 10 | Air | Ultra-high frequency clarity and resolution | Dark ↔ Airy | Want "openness" / detail → **right** |
| 11 | Transient | Transient response speed and sharpness | slow / soft ↔ Fast | Drums feel soft, sluggish → **right** |
| 12 | Soundstage | Stereo width (M/S side channel) | Narrow ↔ Wide | Sound stuck in your head → **right** (widen) |
| 13 | Imaging | Center focus / vocal placement (M/S mid) | Diffuse ↔ Focused | Vocals float, unfocused → **right** (focus) |

> Sliders 7 & 8 ("Sibilance LF / HF") are **precision de-essers**: for most tracks, slider 6 ("Sibilance") tames it globally; only when one track spikes at a specific frequency do you use 7/8 to notch it without touching the rest.

---

## 4. Advanced modules (the groups below the sliders)

### Dynamic Compressor
Master-bus downward compressor (Threshold / Ratio / Attack / Release / Makeup Gain).
**Use:** flattens uneven dynamics — great on the subway or in the car so it doesn't swing between too-loud and too-quiet.

### Loudness Compensation
At low volume, **auto-boosts bass and treble** (per ISO 226 equal-loudness curve).
**Use:** late-night low-volume listening so it doesn't sound "thin / dry". Set strength with Intensity.

### Crossfeed
**Headphones only** — reduces "in-head" fatigue (sound feels crammed inside your skull). Set amount with Mix; a little moves the sound to in-front-of-you.

### Space Reverb
Procedural room tail — leans orchestral / live. Adjust Wet Mix, Room Size, Damping.

### Dynamic EQ
Per-band level compression instead of one global curve. Paired with Bluetooth (esp. SBC) it restores the high-frequency transient bite that gets cut. Adjust Depth / Threshold.

### Multi-band Limiter
Limits low / mid / high **independently** — guards against clipping, protects ears and gear.

### AutoEQ Correction Strength
Blends between **your own tuning** and the **full AutoEQ correction**. Max it for the measured headphone curve; lower it to keep your personal taste.

### DTS Surround (legacy M/S)
- Surround level / Height (turns "wider" into "in front of / behind you").
- Three modes: Theater / Immersive / Wide.
- Over Bluetooth the high end is reduced — use **LDAC / LHDC** for the full effect. Syncs with the play-screen menu and the control-bar pill.
- **Superseded by HRIR Spatial** (below): DTS is a parametric M/S approximation; the newer HRIR mode is real-measured binaural and sounds closer to a true soundfield. The two are mutually exclusive — only one spatial mode is active at a time.

### HRIR Spatial (recommended)
Real-measured **binaural surround** that replaces DTS. A virtual 5.1 ring (FL/FR/C/SL/SR) is routed through its own Resonance-style partitioned-FFT **HRIR convolver** and summed to L/R, using the IRCAM LISTEN IRC_1002_C measured dataset (187 directions × 256 taps @ 48 kHz) with direction interpolation.
- **Drag the 3D sound-field panner** on the MSEB screen: horizontal = azimuth, vertical = elevation (drives `hrtf_field_yaw` / `hrtf_field_pitch`).
- Optional **Room Reverb** and near/far **Distance** for source placement.
- Mutually exclusive with DTS Surround and the 5-point HRTF Probe.
- Over Bluetooth the high end is reduced — use **LDAC / LHDC** for the full effect.

### Device Auto-Calibration
Automatically applies the matching AutoEQ profile for the **currently connected headphone / DAC** — no manual curve hunting.

---

## 5. Presets & saving

- **Original Bit-Perfect (A)**: bit-perfect original. Disables all effects to hear the raw master — pick this for pure, unaltered sound.
- **Reset to flat**: one tap zeroes every slider — your panic button when things get messy.
- **Save Preset**: save the current setup as a preset — **up to 12 custom presets**; you can **name** each one, and the play screen will then show that name instead of a long feature list.
  - Example: save "Commute", "Night", "Female Vocals" for different situations.

---

## 6. Copy-paste per scenario

| Scenario | How to tune |
|----------|-------------|
| Commute / subway (Bluetooth SBC) | Slight Dynamic EQ (restore HF transient) + Multi-band Limiter for safety + mid Loudness Compensation. *SBC cuts above 16k — Dynamic EQ restores, limiter guards the floor* |
| Wired HiFi / DAC exclusive | Keep only the limiter; full correction on (transport is near-transparent, leave it alone) |
| Late-night low volume | Loudness Compensation on, mid-high intensity; Temperature slightly warm |
| Old track with harsh sibilance | Slider 6 "Sibilance" left + Sliders 7/8 notch the exact frequency |
| Vocals / podcasts | Vocal right (forward) + Thickness slightly thick + Imaging focused |
| Live / orchestral | Space Reverb on, Soundstage slightly wide |
| Games / movies, want surround | **HRIR Spatial** on + drag the field (recommended); or DTS Surround on, Height up |

---

## 7. Tips & troubleshooting

- **Some outputs don't support MSEB**: you'll see "Not available on this output" — normal; switch back to a supported source.
- **Bluetooth SBC cuts above 16k**: use Dynamic EQ to restore transients and the limiter to guard the floor; switch to LDAC / LHDC for the full HRIR / DTS high-end.
- **Messy tuning**: tap **Reset to flat** to zero everything.
- **Want pure original**: switch to **Original Bit-Perfect (A)**.
- **New phone**: the Settings backup export includes MSEB — import to restore.

---

## 8. Terms for the curious

- **M/S (Mid/Side)**: mid-side channel processing. Soundstage adjusts the *side* channel width; Imaging adjusts the *mid* channel vocal placement.
- **ISO 226**: the international equal-loudness standard — at low volume the ear is less sensitive to highs and lows, so Loudness Compensation auto-restores them.
- **AutoEQ**: an automatic correction curve based on your headphone's measured response; blend it with your taste via "Correction Strength".

---

*All slider names and descriptions in this guide match the real in-app MSEB strings, so you can follow along directly on screen.*
