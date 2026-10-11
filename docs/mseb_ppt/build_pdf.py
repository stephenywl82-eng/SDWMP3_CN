# -*- coding: utf-8 -*-
from fpdf import FPDF

FONT = r"C:/Windows/Fonts/simhei.ttf"
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
        self.add_font("CN", "", FONT)
        self.set_margins(16, 16, 16)
        self._show_footer = False

    def footer(self):
        if not self._show_footer:
            return
        self.set_font("CN", "", 9)
        self.set_text_color(*GRAY)
        self.set_xy(16, CH - 13)
        self.cell(89, 6, "MSEB 攻略")
        self.set_xy(105, CH - 13)
        self.cell(89, 6, f"{self.page_no()} / 10", align="R")

    def _cw(self, size, ch):
        return size * 0.40 if ord(ch) < 128 else size * 0.42

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
        self.set_font("CN", "", size)
        self.set_text_color(*WHITE)
        for h, w in zip(headers, widths):
            self.set_fill_color(*header_fill)
            self.rect(x, y, w, hh, "F")
            self.set_xy(x + 1.5, y + (hh - line_h) / 2)
            self.multi_cell(w - 3, line_h, h, border=0, align="L")
            x += w
        y += hh
        self.set_text_color(*DARK)
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
    p.set_font("CN", "", 200)
    p.set_text_color(*GLOW)
    p.set_xy(CW - 130, 90)
    p.cell(120, 120, "13", align="C")
    p.set_xy(28, 200)
    p.set_fill_color(*BLUE)
    p.rect(28, 200, 30, 3, "F")
    p.set_xy(64, 196)
    p.set_font("CN", "", 13)
    p.set_text_color(*CYAN)
    p.cell(0, 10, "SDW PLAYER · MSEB PSYCHOACOUSTIC CONSOLE")
    p.set_xy(28, 220)
    p.set_font("CN", "", 54)
    p.set_text_color(*WHITE)
    p.cell(0, 30, "MSEB 自定义调节")
    p.set_xy(28, 262)
    p.set_font("CN", "", 18)
    p.set_text_color(141, 162, 188)
    p.cell(0, 12, "图文攻略 · 把同一副耳机，调成你想要的声音")
    tags = ["13 段听感滑块", "进阶音效模块", "场景配方"]
    tx = 28
    p.set_font("CN", "", 12)
    for t in tags:
        tw = len(t) * 12 * 0.42 + 14
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
    p.set_font("CN", "", 12)
    p.set_text_color(*ACCENT_TXT)
    p.cell(0, 8, kicker)
    p.set_xy(16, 28)
    p.set_font("CN", "", 24)
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
    p.set_font("CN", "", 12.5)
    p.set_text_color(*tcol)
    p.multi_cell(w - 8, 5.8, title)
    p.set_xy(x + 4, y + 16)
    p.set_font("CN", "", 10.5)
    p.set_text_color(*bcol)
    p.multi_cell(w - 8, 5.2, body)


def p_open(p):
    content_header(p, "怎么打开", "怎么打开 MSEB")
    p.set_font("CN", "", 12)
    p.set_text_color(*GRAY)
    p.set_xy(16, 50)
    p.multi_cell(178, 6, "三步进入音效台，边调边看实时频谱。")
    steps = [
        ("1", "播放页 → 右上角音效菜单 → MSEB", "找到带「B」标的开关，点开即进入音效台。"),
        ("2", "顶部实时频谱", "边推滑块边「看」声音怎么变，所见即所听。"),
        ("3", "随备份导出 / 导入", "设置里导出 JSON，换手机一键恢复全部调音。"),
    ]
    y = 72
    for num, t, d in steps:
        p.set_fill_color(*BLUE)
        p.ellipse(22, y + 4, 13, 13, "F")
        p.set_font("CN", "", 13)
        p.set_text_color(*NAVY)
        p.set_xy(22, y + 4)
        p.cell(13, 13, num, align="C")
        p.set_xy(44, y)
        p.set_font("CN", "", 14)
        p.set_text_color(*DARK)
        p.multi_cell(150, 6.5, t)
        p.set_xy(44, y + 8)
        p.set_font("CN", "", 11)
        p.set_text_color(*GRAY)
        p.multi_cell(150, 5.5, d)
        y += 34


def p_concept(p):
    content_header(p, "核心思路", "它调的是「听感维度」")
    p.set_font("CN", "", 13)
    p.set_text_color(*DARK)
    p.set_xy(16, 54)
    p.multi_cell(
        112,
        7.5,
        "传统 EQ 是「100Hz +3dB」这种工程语言。MSEB 把每个滑块变成一种听感，"
        "两端是一对反义词（例如 冷 — 暖），你往哪边推，声音就往那个听感走——\n"
        "不懂 Hz，也能调出自己喜欢的声音。",
    )
    px, py, pw, ph = 132, 58, 62, 150
    p.set_fill_color(*PANEL)
    p.rect(px, py, pw, ph, "F")
    p.set_xy(px + 5, py + 6)
    p.set_font("CN", "", 11)
    p.set_text_color(*WHITE)
    p.cell(0, 6, "Temperature 温度")
    p.set_xy(px + 5, py + 14)
    p.set_font("CN", "", 8.5)
    p.set_text_color(141, 162, 188)
    p.cell(0, 5, "中低频 — 高频能量比")
    ty = py + 45
    p.set_fill_color(*BLUE)
    p.rect(px + 8, ty, pw - 16, 5, "F")
    p.set_fill_color(*CYAN)
    p.rect(px + 8 + (pw - 16) * 0.6, ty - 1, 7, 7, "F")
    p.set_xy(px + 6, ty + 9)
    p.set_font("CN", "", 9)
    p.set_text_color(157, 184, 232)
    p.cell((pw - 16) / 2, 5, "Cool 冷", align="L")
    p.set_xy(px + 6 + (pw - 16) / 2, ty + 9)
    p.set_text_color(157, 232, 242)
    p.cell((pw - 16) / 2, 5, "Warm 暖", align="R")
    p.set_xy(px + 5, ty + 24)
    p.set_font("CN", "", 8.5)
    p.set_text_color(157, 232, 242)
    p.multi_cell(pw - 10, 4.5, "13 段滑块都是这种玩法：每段的左右两端，就是一对反义词。")


def p_sliders(p):
    content_header(p, "13 段滑块", "13 段滑块一览")
    rows = [
        ["1", "温度 Temperature", "中低频 — 高频能量比", "Cool 冷 — Warm 暖"],
        ["2", "厚度 Thickness", "人声 / 乐器基频饱满度", "Thin 薄 — Thick 厚"],
        ["3", "人声 Vocal", "人声前后成像位置", "Distant 远 — Forward 前"],
        ["4", "超低音 Sub-bass", "下潜与冲击感", "Lean 瘦 — Deep 深"],
        ["5", "低频质感 Bass Texture", "低频弹性与紧致", "Loose 松 — Tight 紧"],
        ["6", "齿音 Sibilance", "整体齿音亮度", "Smooth 顺 — Bright 亮"],
        ["7", "齿音 LF", "精准削 ~6kHz 毛刺", "0 — 削"],
        ["8", "齿音 HF", "精准削 ~9kHz 锐利", "0 — 削"],
        ["9", "女声泛音 Female Overtones", "女声甜润与丰盈", "Soft 柔 — Sweet 甜"],
        ["10", "空气感 Air", "极高频清晰度与解析", "Dark 暗 — Airy 通透"],
        ["11", "瞬态 Transient", "瞬态响应速度与利落", "(慢/糊) — Fast 快"],
        ["12", "声场 Soundstage", "立体声宽度 (M/S 侧)", "Narrow 窄 — Wide 宽"],
        ["13", "结像 Imaging", "中央聚焦 / 人声定位", "Diffuse 散 — Focused 聚"],
    ]
    p.table(["#", "滑块", "管什么", "左 — 右"], rows, [10, 60, 56, 52], size=9.5)


def p_key(p):
    content_header(p, "重点滑块", "三个最常用的滑块怎么调")
    cards = [
        ("温度 Temperature", "中低频 — 高频能量比", "嫌刺耳、发飘 → 往左（冷）；嫌闷、糊 → 往右（暖）。"),
        ("厚度 Thickness", "基频段饱满度", "人声单薄、发虚 → 往右（厚），声音更扎实。"),
        ("人声 Vocal", "前后位置", "想听清歌词 → 往右（前），人声推到面前。"),
    ]
    y = 56
    for t, sub, b in cards:
        card(p, 16, y, 110, 46, f"{t}\n{sub}", b)
        y += 52
    px, py, pw, ph = 134, 56, 60, 204
    p.set_fill_color(*PANEL)
    p.rect(px, py, pw, ph, "F")
    p.set_xy(px + 5, py + 6)
    p.set_font("CN", "", 13)
    p.set_text_color(*CYAN)
    p.cell(0, 7, "速记卡")
    tips = [
        ("齿音重", "Sibilance 往左 + LF/HF 精准削"),
        ("低频糊", "Bass Texture 往右（紧）"),
        ("想通透", "Air 往右（Airy）"),
    ]
    yy = py + 20
    for k, v in tips:
        p.set_xy(px + 5, yy)
        p.set_font("CN", "", 10.5)
        p.set_text_color(*CYAN)
        p.cell(18, 5.5, k)
        p.set_xy(px + 5, yy + 5.5)
        p.set_font("CN", "", 9.5)
        p.set_text_color(199, 210, 224)
        p.multi_cell(pw - 10, 4.6, v)
        yy += 22
    p.set_xy(px + 5, yy + 2)
    p.set_font("CN", "", 8.5)
    p.set_text_color(141, 162, 188)
    p.multi_cell(pw - 10, 4.4, "小提示：长按滑块下方的小字，App 会显示官方一句话说明。")


def p_modules(p):
    content_header(p, "进阶模块", "进阶模块")
    p.set_font("CN", "", 11)
    p.set_text_color(*GRAY)
    p.set_xy(16, 50)
    p.multi_cell(178, 5.5, "滑块之外的一组专业音效，按需开启。")
    mods = [
        ("动态压缩器 Dynamic Compressor", "压平忽大忽小，通勤 / 车载更稳"),
        ("等响补偿 Loudness", "小音量自动补低频高频（ISO 226）"),
        ("Crossfeed 串扰", "耳机减「头中效应」疲劳（仅耳机）"),
        ("空间混响 Space Reverb", "程序化房间尾音，偏现场 / 交响"),
        ("动态 EQ Dynamic EQ", "分频段压缩，修蓝牙高频瞬态"),
        ("多段限幅 Limiter", "低 / 中 / 高独立限幅防破音"),
        ("AutoEQ 校正 Correction", "你的调音 — 完整校正 之间混合"),
        ("DTS 环绕 Surround", "环绕 / 高度，建议 LDAC / LHDC"),
        ("设备自动校准 Auto-Calibration", "自动套用当前耳机 / DAC 曲线"),
    ]
    y = 64
    for i, (t, d) in enumerate(mods):
        x = 16 if i % 2 == 0 else 104
        p.set_fill_color(*LIGHT)
        p.rect(x, y, 90, 16, "F")
        p.set_xy(x + 4, y + 2)
        p.set_font("CN", "", 10.5)
        p.set_text_color(*DARK)
        p.cell(0, 5.5, t)
        p.set_xy(x + 4, y + 8)
        p.set_font("CN", "", 9)
        p.set_text_color(*GRAY)
        p.cell(0, 5, d)
        if i % 2 == 1:
            y += 18


def p_preset(p):
    content_header(p, "预设与保存", "预设，和你的命名")
    card(p, 16, 56, 88, 80, "Original Bit-Perfect (A)", "原曲比特完美。关掉所有音效，听原始母带——想要完全原汁原味就选它。")
    card(p, 16, 144, 88, 70, "Reset to flat", "一键把所有滑块归零。调乱了？点它立刻回血。")
    px, py, pw, ph = 112, 56, 82, 158
    p.set_fill_color(*PANEL)
    p.rect(px, py, pw, ph, "F")
    p.set_xy(px + 5, py + 6)
    p.set_font("CN", "", 13)
    p.set_text_color(*CYAN)
    p.cell(0, 7, "Save Preset 存预设")
    p.set_xy(px + 5, py + 16)
    p.set_font("CN", "", 10)
    p.set_text_color(169, 184, 204)
    p.multi_cell(pw - 10, 5, "把当前这一套存成预设，最多 12 个自定义预设。")
    p.set_xy(px + 5, py + 44)
    p.set_fill_color(*SOFT)
    p.rect(px + 5, py + 44, pw - 10, 42, "F")
    p.set_xy(px + 9, py + 48)
    p.set_font("CN", "", 9.5)
    p.set_text_color(199, 210, 224)
    p.multi_cell(pw - 18, 4.6, "存的时候可以命名（如「通勤」「睡前」）。之后播放页会显示这个名字，而不是一长串功能列表。")
    p.set_xy(px + 5, py + 92)
    p.set_font("CN", "", 9.5)
    p.set_text_color(141, 162, 188)
    p.multi_cell(pw - 10, 4.6, "例：一套「通勤」+ 一套「人声」+ 一套「现场」，按场景秒切。")


def p_scenes(p):
    content_header(p, "场景配方", "按场景抄作业")
    rows = [
        ["通勤 / 地铁（蓝牙 SBC）", "动态 EQ 开一点（补高频瞬态）+ 多段限幅保底 + 等响补偿中"],
        ["有线 HiFi / DAC 独占", "只留限幅保护，校正全开（传输近透明，不用动）"],
        ["睡前小音量", "等响补偿开、强度中高；温度略暖"],
        ["齿音重的老歌", "Sibilance 往左 + Sibilance LF / HF 精准削"],
        ["人声 / 播客", "Vocal 往右（前）+ Thickness 略厚 + Imaging 聚焦"],
        ["现场 / 交响", "Space Reverb 开、Soundstage 略宽"],
        ["游戏 / 电影", "DTS Surround 开、Height 调高"],
    ]
    p.table(["场景", "怎么调"], rows, [55, 123], size=9.5)


def p_tips(p):
    content_header(p, "排错 & 小贴士", "排错 & 小贴士")
    faqs = [
        ("某些输出不支持 MSEB", "会显示「当前输出不可用」——正常，换回支持的源即可。"),
        ("蓝牙 SBC 砍掉 16k 以上", "动态 EQ 补瞬态 + 限幅守底；切 LDAC / LHDC 才能吃满 DTS 高频。"),
        ("调乱了", "点 Reset to flat 一键归零，从头再来。"),
        ("想完全原汁原味", "切到 Original Bit-Perfect (A)，听原始母带。"),
    ]
    y = 58
    for q, a in faqs:
        p.set_fill_color(*LIGHT)
        p.rect(16, y, 178, 34, "F")
        p.set_fill_color(*CYAN)
        p.ellipse(24, y + 11, 12, 12, "F")
        p.set_xy(24, y + 11)
        p.set_font("CN", "", 11)
        p.set_text_color(*NAVY)
        p.cell(12, 12, "?", align="C")
        p.set_xy(44, y + 5)
        p.set_font("CN", "", 12.5)
        p.set_text_color(*DARK)
        p.cell(0, 6, q)
        p.set_xy(44, y + 13)
        p.set_font("CN", "", 10.5)
        p.set_text_color(*GRAY)
        p.multi_cell(146, 5, a)
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
    p.set_font("CN", "", 13)
    p.set_text_color(*CYAN)
    p.cell(0, 8, "MSEB · 13 段听感滑块", align="C")
    p.set_xy(0, 268)
    p.set_font("CN", "", 58)
    p.set_text_color(*WHITE)
    p.cell(0, 30, "调出你的声音", align="C")
    p.set_xy(0, 312)
    p.set_font("CN", "", 16)
    p.set_text_color(141, 162, 188)
    p.cell(0, 10, "从今天开始，随手把耳机调成喜欢的样子。", align="C")


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

out = r"E:/SDWMP3_CN/docs/mseb_ppt/MSEB攻略.pdf"
p.output(out)
print("WROTE", out, "pages=", p.page_no())
