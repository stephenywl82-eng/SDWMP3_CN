# -*- coding: utf-8 -*-
from fpdf import FPDF
import os

FONTR = r"C:/Windows/Fonts/arial.ttf"
FONTB = r"C:/Windows/Fonts/arialbd.ttf"
CW, CH = 210, 297  # A4 mm

NAVY = (11, 17, 32)
PANEL = (19, 32, 58)
BLUE = (59, 130, 246)
CYAN = (34, 211, 238)
DARK = (22, 30, 46)
GRAY = (108, 122, 144)
WHITE = (255, 255, 255)
ROW = (236, 240, 246)
LIGHT = (244, 246, 250)
GLOW = (28, 46, 82)
ACCENT_TXT = (37, 99, 235)
TAGBG = (30, 48, 86)
SOFT = (30, 48, 86)


class PDF(FPDF):
    def __init__(self):
        super().__init__(orientation="P", unit="mm", format="A4")
        self.set_auto_page_break(False)
        self.add_font("EN", "", FONTR)
        self.add_font("EN", "B", FONTB)
        self.set_margins(16, 16, 16)
        self._show_footer = False

    def footer(self):
        if not self._show_footer:
            return
        self.set_font("EN", "", 9)
        self.set_text_color(*GRAY)
        self.set_xy(16, CH - 13)
        self.cell(89, 6, "MSEB Guide")
        self.set_xy(105, CH - 13)
        self.cell(89, 6, f"{self.page_no()} / 10", align="R")

    def _cw(self, size, ch):
        return size * 0.22 if ord(ch) < 128 else size * 0.37

    def wrap(self, text, width, size):
        lines, cur, w = [], "", 0.0
        for ch in text:
            cw = self._cw(size, ch)
            if w + cw > width and cur:
                lines.append(cur)
                cur, w = ch, cw
            else:
                cur += ch
                w += cw
        if cur:
            lines.append(cur)
        return lines or [""]

    def table(self, headers, rows, widths, size=9.5, header_fill=BLUE):
        line_h = size * 0.50
        x0 = 16
        y = self.get_y()
        hh = line_h + 3
        x = x0
        self.set_font("EN", "B", size)
        self.set_text_color(*WHITE)
        for h, w in zip(headers, widths):
            self.set_fill_color(*header_fill)
            self.rect(x, y, w, hh, "F")
            self.set_xy(x + 1.5, y + (hh - line_h) / 2)
            self.multi_cell(w - 3, line_h, h, border=0, align="L")
            x += w
        y += hh
        self.set_text_color(*DARK)
        self.set_font("EN", "", size)
        ri = 0
        for r in rows:
            maxlines = 1
            cell_lines = []
            for txt, w in zip(r, widths):
                ls = self.wrap(txt, w - 3, size)
                cell_lines.append(ls)
                maxlines = max(maxlines, len(ls))
            rh = maxlines * line_h + 2.5
            if y + rh > CH - 16:
                self.add_page()
                self._show_footer = True
                y = 16
            x = x0
            if ri % 2 == 1:
                self.set_fill_color(*ROW)
                for w in widths:
                    self.rect(x, y, w, rh, "F")
                    x += w
                x = x0
            for ls, w in zip(cell_lines, widths):
                self.set_xy(x + 1.5, y + 1.2)
                self.multi_cell(w - 3, line_h, "\n".join(ls), border=0, align="L")
                x += w
            self.set_draw_color(*LIGHT)
            self.set_line_width(0.2)
            self.line(x0, y + rh, x0 + sum(widths), y + rh)
            y += rh
            ri += 1
        self.set_y(y + 2)


def cover(p):
    p.add_page()
    p._show_footer = False
    p.set_fill_color(*NAVY)
    p.rect(0, 0, CW, CH, "F")
    p.set_fill_color(*GLOW)
    p.ellipse(CW - 70, -60, 240, 240, "F")
    p.ellipse(-80, CH - 150, 240, 240, "F")
    p.set_font("EN", "B", 200)
    p.set_text_color(*GLOW)
    p.set_xy(CW - 130, 90)
    p.cell(120, 120, "13", align="C")
    p.set_xy(28, 200)
    p.set_fill_color(*BLUE)
    p.rect(28, 200, 30, 3, "F")
    p.set_xy(64, 196)
    p.set_font("EN", "", 12)
    p.set_text_color(*CYAN)
    p.cell(0, 10, "SDW PLAYER · MSEB PSYCHOACOUSTIC CONSOLE")
    p.set_xy(28, 220)
    p.set_font("EN", "B", 46)
    p.set_text_color(*WHITE)
    p.cell(0, 30, "MSEB Custom Tuning")
    p.set_xy(28, 262)
    p.set_font("EN", "", 17)
    p.set_text_color(141, 162, 188)
    p.cell(0, 12, "Illustrated guide · shape the same headphones into the sound you like")
    tags = ["13 perceptual sliders", "Pro audio modules", "Scene recipes"]
    tx = 28
    p.set_font("EN", "", 11)
    for t in tags:
        tw = len(t) * 11 * 0.22 + 14
        p.set_fill_color(*TAGBG)
        p.rect(tx, 286, tw, 12, "F")
        p.set_draw_color(*BLUE)
        p.set_line_width(0.3)
        p.rect(tx, 286, tw, 12)
        p.set_xy(tx, 286)
        p.set_text_color(157, 184, 232)
        p.cell(tw, 12, t, align="C")
        tx += tw + 10


def content_header(p, kicker, title):
    p.add_page()
    p._show_footer = True
    p.set_fill_color(*BLUE)
    p.rect(16, 18, 8, 4, "F")
    p.set_xy(28, 16)
    p.set_font("EN", "", 12)
    p.set_text_color(*ACCENT_TXT)
    p.cell(0, 8, kicker)
    p.set_xy(16, 28)
    p.set_font("EN", "B", 24)
    p.set_text_color(*DARK)
    p.cell(0, 12, title)
    p.set_draw_color(*CYAN)
    p.set_line_width(0.6)
    p.line(16, 46, 194, 46)
    p.set_y(54)


def card(p, x, y, w, h, title, body, fill=PANEL, tcol=WHITE, bcol=(169, 184, 204)):
    p.set_fill_color(*fill)
    p.rect(x, y, w, h, "F")
    p.set_xy(x + 4, y + 4)
    p.set_font("EN", "B", 12)
    p.set_text_color(*tcol)
    p.multi_cell(w - 8, 5.6, title)
    p.set_xy(x + 4, y + 15)
    p.set_font("EN", "", 10)
    p.set_text_color(*bcol)
    p.multi_cell(w - 8, 4.9, body)


def p_open(p):
    content_header(p, "HOW TO OPEN", "How to open MSEB")
    p.set_font("EN", "", 12)
    p.set_text_color(*GRAY)
    p.set_xy(16, 50)
    p.multi_cell(178, 6, "Three steps to the sound console — watch the live spectrum as you tune.")
    steps = [
        ("1", "Play screen -> top-right effects menu -> MSEB", "Find the toggle marked 'B' and turn it on to enter the console."),
        ("2", "Realtime spectrum on top", "Push a slider and 'see' how the sound changes — what you see is what you hear."),
        ("3", "Export / import with backup", "Export a JSON in Settings; restore all tuning on a new phone in one tap."),
    ]
    y = 72
    for num, t, d in steps:
        p.set_fill_color(*BLUE)
        p.ellipse(22, y + 4, 13, 13, "F")
        p.set_font("EN", "B", 13)
        p.set_text_color(*NAVY)
        p.set_xy(22, y + 4)
        p.cell(13, 13, num, align="C")
        p.set_xy(44, y)
        p.set_font("EN", "B", 13)
        p.set_text_color(*DARK)
        p.multi_cell(150, 6.2, t)
        p.set_xy(44, y + 8)
        p.set_font("EN", "", 10.5)
        p.set_text_color(*GRAY)
        p.multi_cell(150, 5.2, d)
        y += 34


def p_concept(p):
    content_header(p, "KEY IDEA", "It tunes perceptual dimensions")
    p.set_font("EN", "", 13)
    p.set_text_color(*DARK)
    p.set_xy(16, 54)
    p.multi_cell(
        112,
        7.2,
        "A traditional EQ speaks engineering ('+3dB at 100Hz'). MSEB turns each "
        "slider into a perceived character, with antonyms at both ends (e.g. Cool — Warm). "
        "Push one way and the sound moves that direction — no Hz math needed.",
    )
    px, py, pw, ph = 132, 58, 62, 150
    p.set_fill_color(*PANEL)
    p.rect(px, py, pw, ph, "F")
    p.set_xy(px + 5, py + 6)
    p.set_font("EN", "B", 11)
    p.set_text_color(*WHITE)
    p.cell(0, 6, "Temperature")
    p.set_xy(px + 5, py + 14)
    p.set_font("EN", "", 8.5)
    p.set_text_color(141, 162, 188)
    p.cell(0, 5, "Mid-low — high energy ratio")
    ty = py + 45
    p.set_fill_color(*BLUE)
    p.rect(px + 8, ty, pw - 16, 5, "F")
    p.set_fill_color(*CYAN)
    p.rect(px + 8 + (pw - 16) * 0.6, ty - 1, 7, 7, "F")
    p.set_xy(px + 6, ty + 9)
    p.set_font("EN", "", 9)
    p.set_text_color(157, 184, 232)
    p.cell((pw - 16) / 2, 5, "Cool", align="L")
    p.set_xy(px + 6 + (pw - 16) / 2, ty + 9)
    p.set_text_color(157, 232, 242)
    p.cell((pw - 16) / 2, 5, "Warm", align="R")
    p.set_xy(px + 5, ty + 24)
    p.set_font("EN", "", 8.5)
    p.set_text_color(157, 232, 242)
    p.multi_cell(pw - 10, 4.5, "All 13 sliders work this way: the two ends are a pair of antonyms.")


def p_sliders(p):
    content_header(p, "13 SLIDERS", "The 13 sliders at a glance")
    rows = [
        ["1", "Temperature", "Mid-low — high energy ratio", "Cool — Warm"],
        ["2", "Thickness", "Vocal/instrument fundamental body", "Thin — Thick"],
        ["3", "Vocal", "Vocal imaging front/back", "Distant — Forward"],
        ["4", "Sub-bass", "Sub-bass depth and impact", "Lean — Deep"],
        ["5", "Bass Texture", "Bass elasticity and tightness", "Loose — Tight"],
        ["6", "Sibilance", "Overall sibilance brightness", "Smooth — Bright"],
        ["7", "Sibilance LF", "Snipe ~6kHz harshness", "0 — cut"],
        ["8", "Sibilance HF", "Snipe ~9kHz piercing", "0 — cut"],
        ["9", "Female Overtones", "Female vocal sweetness/lushness", "Soft — Sweet"],
        ["10", "Air", "Ultra-high clarity & resolution", "Dark — Airy"],
        ["11", "Transient", "Transient speed & sharpness", "(slow) — Fast"],
        ["12", "Soundstage", "Stereo width (M/S side)", "Narrow — Wide"],
        ["13", "Imaging", "Center focus / vocal place (M/S mid)", "Diffuse — Focused"],
    ]
    p.table(["#", "Slider", "Controls", "Ends"], rows, [10, 58, 56, 54], size=9.5)


def p_key(p):
    content_header(p, "KEY SLIDERS", "Three sliders you'll use most")
    cards = [
        ("Temperature", "Mid-low — high energy ratio", "Harsh/thin -> left (Cool); muffled -> right (Warm)."),
        ("Thickness", "Fundamental band body", "Thin/hollow vocals -> right (Thick) for a fuller sound."),
        ("Vocal", "Front/back position", "Want clearer lyrics -> right (Forward), push voice up front."),
    ]
    y = 56
    for t, sub, b in cards:
        card(p, 16, y, 110, 46, f"{t}\n{sub}", b)
        y += 52
    px, py, pw, ph = 134, 56, 60, 204
    p.set_fill_color(*PANEL)
    p.rect(px, py, pw, ph, "F")
    p.set_xy(px + 5, py + 6)
    p.set_font("EN", "B", 13)
    p.set_text_color(*CYAN)
    p.cell(0, 7, "Cheat card")
    tips = [
        ("Harsh 's'", "Sibilance left + LF/HF notch"),
        ("Muddy bass", "Bass Texture right (Tight)"),
        ("Want air", "Air right (Airy)"),
    ]
    yy = py + 20
    for k, v in tips:
        p.set_xy(px + 5, yy)
        p.set_font("EN", "B", 10)
        p.set_text_color(*CYAN)
        p.cell(22, 5.5, k)
        p.set_xy(px + 5, yy + 5.5)
        p.set_font("EN", "", 9)
        p.set_text_color(199, 210, 224)
        p.multi_cell(pw - 10, 4.4, v)
        yy += 22
    p.set_xy(px + 5, yy + 2)
    p.set_font("EN", "", 8.2)
    p.set_text_color(141, 162, 188)
    p.multi_cell(pw - 10, 4.2, "Tip: long-press the small text under a slider to see its official one-line description.")


def p_modules(p):
    content_header(p, "ADVANCED MODULES", "Advanced modules")
    p.set_font("EN", "", 11)
    p.set_text_color(*GRAY)
    p.set_xy(16, 50)
    p.multi_cell(178, 5.5, "A set of pro effects beyond the sliders — turn on what you need.")
    mods = [
        ("Dynamic Compressor", "Flattens dynamics — steady on subway / in car"),
        ("Loudness Compensation", "Auto bass/treble boost at low volume (ISO 226)"),
        ("Crossfeed", "Reduces 'in-head' fatigue (headphones only)"),
        ("Space Reverb", "Procedural room tail — orchestral / live hall"),
        ("Dynamic EQ", "Per-band compression; restores Bluetooth HF"),
        ("Multi-band Limiter", "Limits low/mid/high independently; no clipping"),
        ("AutoEQ Correction", "Blend your tuning with full AutoEQ curve"),
        ("DTS Surround", "Surround / Height — prefer LDAC / LHDC"),
        ("Device Auto-Calibration", "Applies matching AutoEQ for current device"),
    ]
    y = 64
    for i, (t, d) in enumerate(mods):
        x = 16 if i % 2 == 0 else 104
        p.set_fill_color(*LIGHT)
        p.rect(x, y, 88, 18, "F")
        p.set_xy(x + 4, y + 2.5)
        p.set_font("EN", "B", 10)
        p.set_text_color(*DARK)
        p.cell(0, 5.2, t)
        p.set_xy(x + 4, y + 9)
        p.set_font("EN", "", 8.5)
        p.set_text_color(*GRAY)
        p.multi_cell(80, 4.2, d)
        if i % 2 == 1:
            y += 20


def p_preset(p):
    content_header(p, "PRESETS & SAVING", "Presets, and your own names")
    card(p, 16, 56, 88, 80, "Original Bit-Perfect (A)", "Disables all effects and plays the raw master — for pure, unaltered sound.")
    card(p, 16, 144, 88, 70, "Reset to flat", "Zeroes every slider at once. Messed up? One tap to recover.")
    px, py, pw, ph = 112, 56, 82, 158
    p.set_fill_color(*PANEL)
    p.rect(px, py, pw, ph, "F")
    p.set_xy(px + 5, py + 6)
    p.set_font("EN", "B", 13)
    p.set_text_color(*CYAN)
    p.cell(0, 7, "Save Preset")
    p.set_xy(px + 5, py + 16)
    p.set_font("EN", "", 9.5)
    p.set_text_color(169, 184, 204)
    p.multi_cell(pw - 10, 4.6, "Save the current setup as a preset — up to 12 custom presets.")
    p.set_xy(px + 5, py + 44)
    p.set_fill_color(*SOFT)
    p.rect(px + 5, py + 44, pw - 10, 42, "F")
    p.set_xy(px + 9, py + 48)
    p.set_font("EN", "", 9)
    p.set_text_color(199, 210, 224)
    p.multi_cell(pw - 18, 4.3, "You can name a preset (e.g. 'Commute'). The play screen then shows that name instead of a long feature list.")
    p.set_xy(px + 5, py + 92)
    p.set_font("EN", "", 9)
    p.set_text_color(141, 162, 188)
    p.multi_cell(pw - 10, 4.3, "Example: one 'Commute' + one 'Vocals' + one 'Live' — switch by scene instantly.")


def p_scenes(p):
    content_header(p, "SCENE RECIPES", "Copy-paste by scenario")
    rows = [
        ["Commute / subway (BT SBC)", "Slight Dynamic EQ (restore HF) + Multi-band Limiter + mid Loudness"],
        ["Wired HiFi / DAC exclusive", "Keep only the limiter; full correction on (transport is transparent)"],
        ["Late-night low volume", "Loudness on, mid-high intensity; Temperature slightly warm"],
        ["Old track with harsh 's'", "Sibilance left + Sibilance LF / HF notch"],
        ["Vocals / podcasts", "Vocal right (forward) + Thickness slightly thick + Imaging focused"],
        ["Live / orchestral", "Space Reverb on, Soundstage slightly wide"],
        ["Games / movies", "DTS Surround on, Height up"],
    ]
    p.table(["Scenario", "How to tune"], rows, [55, 123], size=9.5)


def p_tips(p):
    content_header(p, "TROUBLESHOOTING", "Troubleshooting & tips")
    faqs = [
        ("Some outputs don't support MSEB", "Shows 'Not available on this output' — normal; switch to a supported source."),
        ("Bluetooth SBC cuts above 16k", "Dynamic EQ restores transients + limiter guards the floor; use LDAC/LHDC for full DTS."),
        ("Messy tuning", "Tap Reset to flat to zero everything and start over."),
        ("Want pure original", "Switch to Original Bit-Perfect (A) to hear the raw master."),
    ]
    y = 58
    for q, a in faqs:
        p.set_fill_color(*LIGHT)
        p.rect(16, y, 178, 34, "F")
        p.set_fill_color(*CYAN)
        p.ellipse(24, y + 11, 12, 12, "F")
        p.set_xy(24, y + 11)
        p.set_font("EN", "B", 11)
        p.set_text_color(*NAVY)
        p.cell(12, 12, "?", align="C")
        p.set_xy(44, y + 5)
        p.set_font("EN", "B", 12)
        p.set_text_color(*DARK)
        p.cell(0, 6, q)
        p.set_xy(44, y + 13)
        p.set_font("EN", "", 10)
        p.set_text_color(*GRAY)
        p.multi_cell(146, 4.8, a)
        y += 40


def p_end(p):
    p.add_page()
    p._show_footer = False
    p.set_fill_color(*NAVY)
    p.rect(0, 0, CW, CH, "F")
    p.set_fill_color(*GLOW)
    p.ellipse(-90, -120, 300, 300, "F")
    p.ellipse(CW - 120, CH - 160, 300, 300, "F")
    p.set_xy(0, 250)
    p.set_font("EN", "", 13)
    p.set_text_color(*CYAN)
    p.cell(0, 8, "MSEB · 13 PERCEPTUAL SLIDERS", align="C")
    p.set_xy(0, 268)
    p.set_font("EN", "B", 58)
    p.set_text_color(*WHITE)
    p.cell(0, 30, "Tune Your Sound", align="C")
    p.set_xy(0, 312)
    p.set_font("EN", "", 15)
    p.set_text_color(141, 162, 188)
    p.cell(0, 10, "From today, shape your headphones into the sound you love.", align="C")


p = PDF()
cover(p)
p_open(p)
p_concept(p)
p_sliders(p)
p_key(p)
p_modules(p)
p_preset(p)
p_scenes(p)
p_tips(p)
p_end(p)

out = r"E:/SDWMP3_CN/docs/mseb_ppt/MSEB-Guide.pdf"
p.output(out)
print("WROTE", out, "pages=", p.page_no())
