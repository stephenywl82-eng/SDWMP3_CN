#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_hrtf_data.py — 生成 HrtfProbe 生产版用的 HRIR 数据集头文件 hrtf_data.h。

输出两种模式（写入文件头 HRTF_MODE 注释）：
  * MODELED : 物理模型生成的双耳 HRIR 网格（ITD + 头影 ILD + 耳廓谱陷波），
              作为稳定可用的回退；不依赖任何外部数据。
  * REAL    : 若 /tmp/HRIR.mat 为结构完整的 MATLAB 5.0 文件且含 HRIR 矩阵与
              az/el 标注，则优先使用真实测量数据（需 scipy 不可用环境下的纯 Python 解析）。

用法: python3 gen_hrtf_data.py [out_path]
默认输出到本脚本同目录的 hrtf_data.h。
"""
import math
import os
import struct
import sys
import zlib

FS = 48000          # 数据集采样率
N = 200             # 每条 HRIR 长度（样本）
OUT_DEFAULT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hrtf_data.h")

# 探针所需的 5 个方向都落在网格点上，保证插值在这些方向上是精确的
AZ_GRID = [-180, -135, -90, -45, 0, 45, 90, 135]
EL_GRID = [0, 45, 90]


def place(ir, pos, amp):
    """在浮点位置 pos 处放置幅度 amp（线性插值到相邻两个样本）。"""
    if amp == 0.0:
        return
    i0 = int(math.floor(pos))
    frac = pos - i0
    if 0 <= i0 < len(ir):
        ir[i0] += (1.0 - frac) * amp
    if 0 <= i0 + 1 < len(ir):
        ir[i0 + 1] += frac * amp


def make_ear_ir(az_deg, el_deg, ear):
    """生成单耳 HRIR（时域）：直达声 + 短指数尾 + 耳廓早期反射（谱陷波/峰）。"""
    az = math.radians(az_deg)
    el = math.radians(el_deg)
    ir = [0.0] * N
    ITDmax = 0.00063 * FS           # ~30 样本
    base = 0.5 * ITDmax
    lat = math.sin(az)
    delay = (base + 0.5 * ITDmax * lat) if ear == 'L' else (base - 0.5 * ITDmax * lat)

    # 直达声 + 短尾（让 HRIR 更像真实宽带 onset）
    place(ir, delay, 1.0)
    i0 = int(math.floor(delay))
    for i in range(i0 + 1, min(N, i0 + 40)):
        ir[i] += 0.30 * math.exp(-(i - delay) / 8.0)

    # 耳廓谱线索：陷波频率随仰角升高、随前后变化
    frontness = max(0.0, math.cos(az))
    backness = max(0.0, -math.cos(az))
    upness = max(0.0, math.sin(el))
    notch_f = 4000.0 + 4000.0 * math.sin(el) + 1500.0 * math.cos(az)
    notch_f = min(11000.0, max(3000.0, notch_f))
    notch_amp = -(0.25 + 0.35 * backness + 0.10 * upness)  # 负 => 陷波
    place(ir, delay + 0.5 * FS / notch_f, notch_amp)

    # 正前：高频峰（明亮），强化前后差异
    if frontness > 0:
        place(ir, delay + 0.5 * FS / 9000.0, 0.15 * frontness)
    # 通用耳廓"环"反射，丰富频谱
    place(ir, delay + 0.0002 * FS, 0.08)
    place(ir, delay + 0.00035 * FS, 0.05)
    return ir


def shadow_fc(az_deg, ear):
    """头影低通截止：对侧耳（远离声源）截止更低 -> 天然 ILD。"""
    az = math.radians(az_deg)
    backness = max(0.0, -math.cos(az))
    contra = max(0.0, math.sin(az)) if ear == 'L' else max(0.0, -math.sin(az))
    fc = 8000.0 - 4500.0 * contra - 2000.0 * backness
    return min(8000.0, max(2500.0, fc))


def apply_lowpass(ir, fc):
    a1 = math.exp(-2.0 * math.pi * fc / FS)
    b0 = 1.0 - a1
    out = [0.0] * len(ir)
    prev = 0.0
    for i, x in enumerate(ir):
        prev = b0 * x + a1 * prev
        out[i] = prev
    return out


def modeled_points():
    pts = []
    for az in AZ_GRID:
        for el in EL_GRID:
            iL = make_ear_ir(az, el, 'L')
            iR = make_ear_ir(az, el, 'R')
            iL = apply_lowpass(iL, shadow_fc(az, 'L'))
            iR = apply_lowpass(iR, shadow_fc(az, 'R'))
            # ILD：对侧耳整体略衰减
            contraL = max(0.0, math.sin(math.radians(az)))
            contraR = max(0.0, -math.sin(math.radians(az)))
            gL = 1.0 - 0.12 * contraL
            gR = 1.0 - 0.12 * contraR
            iL = [v * gL for v in iL]
            iR = [v * gR for v in iR]
            # 归一化到峰值 0.85
            peak = max(max((abs(v) for v in iL), default=0.0),
                       max((abs(v) for v in iR), default=0.0), 1e-6)
            s = 0.85 / peak
            iL = [v * s for v in iL]
            iR = [v * s for v in iR]
            pts.append((az, el, iL, iR))
    return pts


# ----------------------- 真实数据（MAT5 纯 Python 解析，尽量优雅降级）-----------------------

def _read_mat5(path):
    with open(path, 'rb') as f:
        data = f.read()
    if not data.startswith(b'MATLAB'):
        return None
    # 字节序：偏移 124 的 uint16 == 0x0100 -> 小端
    ver = struct.unpack_from('<H', data, 124)[0]
    le = (ver == 0x0100)
    endian = '<' if le else '>'
    pos = 128
    vars_ = {}

    def parse_elements(buf, off, end, en):
        local = {}
        p = off
        while p + 8 <= end:
            tag = struct.unpack(en + 'I', buf[p:p + 4])[0]
            p += 4
            cls = (tag >> 16) & 0xFFFF
            nbytes = tag & 0xFFFF
            if nbytes == 0:
                # 压缩元素：tag 的 32 位 = 总字节数（含 tag），数据为 zlib
                total = tag
                comp = buf[p:p + total - 4]
                p += (total - 4)
                try:
                    sub = zlib.decompress(comp)
                except Exception:
                    continue
                parse_elements(sub, 0, len(sub), '<' if le else '>')
                # 注意：递归结果需回传，这里用闭包外字典
                local.update(_LAST)
                continue
            # 跳过对齐填充
            while (p - off) % 8 != 0 and p < end:
                p += 1
            body = buf[p:p + nbytes]
            p += nbytes
            if cls == 14:  # miMATRIX
                name, arr = _parse_matrix(body, en)
                if name is not None:
                    local[name] = arr
        return local

    # 用模块级收集器在递归中传递变量
    global _LAST
    _LAST = vars_
    parse_elements(data, pos, len(data), endian)
    return vars_


def _parse_matrix(body, en):
    p = 0
    n = len(body)
    # array flags
    tag = struct.unpack(en + 'I', body[p:p + 4])[0]; p += 4
    cls = (tag >> 16) & 0xFFFF
    nbytes = tag & 0xFFFF
    p += nbytes
    array_class = struct.unpack(en + 'I', body[p:p + 4])[0] & 0xFF; p += 4
    p += 4  # nzmax
    # dims
    tag = struct.unpack(en + 'I', body[p:p + 4])[0]; p += 4
    cls = (tag >> 16) & 0xFFFF
    nbytes = tag & 0xFFFF
    ndims = nbytes // 4
    dims = struct.unpack(en + ('%dI' % ndims), body[p:p + nbytes]); p += nbytes
    while p % 8 != 0 and p < n:
        p += 1
    # name
    tag = struct.unpack(en + 'I', body[p:p + 4])[0]; p += 4
    cls = (tag >> 16) & 0xFFFF
    nbytes = tag & 0xFFFF
    name = body[p:p + nbytes].split(b'\x00')[0].decode('ascii', 'ignore'); p += nbytes
    while p % 8 != 0 and p < n:
        p += 1
    # real part
    tag = struct.unpack(en + 'I', body[p:p + 4])[0]; p += 4
    cls = (tag >> 16) & 0xFFFF
    nbytes = tag & 0xFFFF
    if cls == 9:  # miDOUBLE
        cnt = nbytes // 8
        vals = struct.unpack(en + ('%dd' % cnt), body[p:p + nbytes])
        # 重塑为嵌套列表
        arr = vals
        for d in reversed(dims):
            arr = [list(arr[i * d:(i + 1) * d]) for i in range(len(arr) // d)] if d > 0 else []
        p += nbytes
        return name, arr
    return name, None


def real_points(path):
    """尝试从 MAT5 提取真实 HRIR + az/el；失败则返回 None。"""
    try:
        v = _read_mat5(path)
        if not v:
            return None
        # 找 HRIR 三维数组（含一维==2 的耳）
        hrir = None
        for k, val in v.items():
            if isinstance(val, list) and _ndim(val) == 3:
                shape = _shape(val)
                if 2 in shape and any(80 <= s <= 512 for s in shape):
                    hrir = (k, val, shape)
                    break
        if hrir is None:
            return None
        # 找 az / el 标注
        az = el = None
        for k in v:
            if k.lower() in ('az', 'azi', 'azimuth', 'azim', 'theta'):
                az = _flatten(v[k])
            if k.lower() in ('el', 'elev', 'elevation', 'phi'):
                el = _flatten(v[k])
        if az is None or el is None:
            return None  # 没有方向标注就不猜测，回退建模
        name, arr, shape = hrir
        # 确定维度角色：找 ears(==2) 与 irlen(80..512)，剩下为 directions
        ears_dim = shape.index(2)
        irlen_dim = next(i for i, s in enumerate(shape) if 80 <= s <= 512)
        dirs_dim = next(i for i in range(3) if i not in (ears_dim, irlen_dim))
        d0, d1, d2 = [0, 1, 2]
        ndirs = shape[dirs_dim]
        if len(az) < ndirs or len(el) < ndirs:
            return None
        pts = []
        for di in range(ndirs):
            def fetch(ear_idx):
                # 按 (dirs, ears, irlen) 任意排列取值
                idx = [0, 0, 0]
                idx[dirs_dim] = di
                idx[ears_dim] = ear_idx
                a = arr
                for dim in (dirs_dim, ears_dim, irlen_dim):
                    a = a[idx[dim]]
                return list(a)
            iL = [float(x) for x in fetch(0)]
            iR = [float(x) for x in fetch(1)]
            # 重采样到 FS 与长度 N
            iL = _resample(iL, FS, N)
            iR = _resample(iR, FS, N)
            pts.append((float(az[di]), float(el[di]), iL, iR))
        return pts
    except Exception:
        return None


def _ndim(x):
    d = 0
    while isinstance(x, list) and x:
        d += 1
        x = x[0]
    return d


def _shape(x):
    s = []
    while isinstance(x, list) and x:
        s.append(len(x))
        x = x[0]
    return s


def _flatten(x):
    out = []
    if isinstance(x, list):
        for e in x:
            out.extend(_flatten(e))
    else:
        out.append(x)
    return out


def _resample(src, fs, n):
    """把任意采样率的 IR 重采样到 fs、长度 n（线性插值）。"""
    L = len(src)
    if L < 2:
        return [0.0] * n
    out = [0.0] * n
    for j in range(n):
        pos = j * (L - 1) / (n - 1)
        i0 = int(pos)
        frac = pos - i0
        i1 = min(i0 + 1, L - 1)
        out[j] = src[i0] * (1 - frac) + src[i1] * frac
    return out


def emit(points, mode, path):
    azs = [p[0] for p in points]
    els = [p[1] for p in points]
    iL = [p[2] for p in points]
    iR = [p[3] for p in points]
    np_ = len(points)
    lines = []
    lines.append("// Auto-generated by gen_hrtf_data.py — DO NOT EDIT BY HAND.")
    lines.append("// HRTF_MODE: %s" % mode)
    lines.append("#pragma once")
    lines.append("#define HRTF_RATE %d" % FS)
    lines.append("#define HRTF_IR_LEN %d" % N)
    lines.append("#define HRTF_NUM_PTS %d" % np_)
    lines.append("static const float HRTF_AZ[HRTF_NUM_PTS] = {")
    lines.append("  " + ", ".join("%.4f" % a for a in azs) + " };")
    lines.append("static const float HRTF_EL[HRTF_NUM_PTS] = {")
    lines.append("  " + ", ".join("%.4f" % e for e in els) + " };")
    lines.append("static const float HRTF_IR_L[HRTF_NUM_PTS][HRTF_IR_LEN] = {")
    for row in iL:
        lines.append("  { " + ", ".join("%.6g" % v for v in row) + " },")
    lines.append("};")
    lines.append("static const float HRTF_IR_R[HRTF_NUM_PTS][HRTF_IR_LEN] = {")
    for row in iR:
        lines.append("  { " + ", ".join("%.6g" % v for v in row) + " },")
    lines.append("};")
    with open(path, 'w') as f:
        f.write("\n".join(lines) + "\n")
    print("emit %s mode=%s points=%d -> %s" % (mode, np_, np_, path))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else OUT_DEFAULT
    points = modeled_points()
    mode = "MODELED"
    real_path = "/tmp/HRIR.mat"
    if os.path.exists(real_path) and os.path.getsize(real_path) > 100000:
        rp = real_points(real_path)
        if rp and len(rp) >= 5:
            points = rp
            mode = "REAL"
            print("using REAL measured dataset from %s (%d pts)" % (real_path, len(rp)))
    emit(points, mode, out)


if __name__ == "__main__":
    main()
