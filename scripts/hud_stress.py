#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HUD DSL 硬件摸底压测：adb 直推场景到眼镜，测「元素/动画/表达式数量 → 帧率」的真实天花板。

背景：眼镜端 HudOverlayController 的护栏（MAX_ELEMENTS=64 / MAX_ANIMS=6 /
MAX_EXPR_ELEMENTS=16 等）一直是设计纪律，没有实测数据。本脚本经 HudDebugReceiver
（RokidLink 调试直推入口，2026-10-24 加入）把场景 DSL 直接推上屏，逐场景采样：
  - dumpsys gfxinfo reset → 窗口采样 → Total frames / Janky% / p50-p99 帧时间
  - /proc/<pid>/stat jiffies 差 → 窗口 CPU 占用
  - logcat 确认场景真的应用（parse 耗时 + elements= 数）

用法（默认全量 ~29 场景，约 10 分钟）：
    python scripts/hud_stress.py                 # 全量
    python scripts/hud_stress.py --quick         # 精简 10 场景
    python scripts/hud_stress.py --scene E512    # 只跑某场景（可多次）
    python scripts/hud_stress.py --window 12     # 每场景采样窗口秒数（默认 12）

依赖：adb 可达眼镜；眼镜端 RokidLink 已装含 HudDebugReceiver 的构建（护栏已临时抬到
2048 元素 / 1024 动画 / 2048 expr 元素）；屏幕需亮着（ticker 只在亮屏运行，灭屏自动暂停）。
测试期间不要点手机端「重装 RokidLink」按钮（会把 assets 旧包推回眼镜，覆盖测试包）。

结果写 .workbuddy/tmp/hud_stress_result.md（人读）与 hud_stress_result.json（机器读）。
"""

import argparse
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import time

ADB = os.environ.get("ADB", r"D:\android-sdk\platform-tools\adb.exe")
PKG = "com.rokidlab.rokidlink"
RECEIVER = PKG + "/.HudDebugReceiver"
ACTION = "com.rokidlab.rokidlink.DEBUG_HUD"
DEVICE_JSON = "/data/local/tmp/hud_stress_scene.json"
TMP_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".workbuddy", "tmp")

SW, SH = 480, 640  # 屏尺寸（中心原点坐标空间用）

SERIAL = None  # main() 里选定眼镜后，adb() 自动带 -s


# ──────────────────── adb 基础设施 ────────────────────

def adb(*args, timeout=90):
    """带 -s SERIAL 的 adb 调用（SERIAL 未定时不带，供 pick_glasses 枚举用）。"""
    cmd = [ADB]
    if SERIAL:
        cmd += ["-s", SERIAL]
    cmd += list(args)
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def adb_raw(*args, timeout=60):
    """裸 adb（不带 -s），仅 pick_glasses 枚举设备用。"""
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)


def pick_glasses():
    out = adb_raw("devices", "-l").stdout
    serials = []
    for line in out.splitlines():
        line = line.strip()
        if not line or line.startswith("List of") or "*" in line:
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    if not serials:
        sys.exit("❌ 没有在线设备（adb devices 为空）")
    # 优先 model:RG_glasses，否则只有一个设备就用它
    for s in serials:
        info = adb_raw("-s", s, "shell", "getprop ro.product.model").stdout.strip()
        if "glasses" in info.lower() or "rg" in info.lower():
            return s
    if len(serials) == 1:
        return serials[0]
    sys.exit("❌ 多设备在线且无法识别眼镜，请用 --serial 指定。在线: %s" % serials)


# ──────────────────── 场景生成（中心原点坐标：x∈[-240,240], y∈[-320,320]） ────────────────────

def grid_positions(n):
    """把 n 个元素均匀铺满 480×640（中心原点），返回 [(x,y), ...]"""
    cols = max(1, math.ceil(math.sqrt(n * SW / SH)))
    rows = max(1, math.ceil(n / cols))
    pos = []
    for i in range(n):
        r, c = divmod(i, cols)
        x = -SW / 2 + (c + 0.5) * (SW / cols)
        y = -SH / 2 + (r + 0.5) * (SH / rows)
        pos.append((round(x, 1), round(y, 1)))
    return pos


def dot(x, y, r=2.4, stroke=2):
    return {"type": "dot", "x": x, "y": y, "r": r, "stroke": stroke}


def anim_dot(x, y):
    return {"type": "dot", "x": x, "y": y, "r": 3,
            "anim": [{"type": "spin", "period": 4}, {"type": "pulse", "period": 6}]}


def expr_dot(x, y):
    """3 条 expr（x/y/opacity），每帧求值 —— expr 轴标准件"""
    return {"type": "dot", "r": 2.4,
            "x": "expr:(t*40+%d)%%sw" % (x + 240),
            "y": "expr:(t*25+%d)%%sh" % (y + 320),
            "opacity": "expr:0.5+0.5*sin(t*2)"}


def text_el(x, y):
    return {"type": "text", "content": "X", "x": x, "y": y, "size": 14}


BG_FULL = {"scanline": 0.08, "grid": 48, "gridOpacity": 0.10}


def scene_elements(n_static):
    """元素数轴：n_static 个静态 dot + 1 个动画 dot（保 ticker 常转）"""
    els = [dot(x, y) for x, y in grid_positions(n_static)]
    els.append(anim_dot(0, 0))
    return {"v": 1, "scene": els}


def scene_anims(n_anim_els, n_static=32):
    """动画轴：32 个静态 dot + n_anim_els 个双动画 dot（动画实例 = 2×n）"""
    els = [dot(x, y) for x, y in grid_positions(n_static)]
    for x, y in grid_positions(n_anim_els):
        els.append(anim_dot(x, y))
    return {"v": 1, "scene": els}


def scene_expr(n_expr):
    """expr 轴：n_expr 个 3-expr dot + 1 个动画 dot"""
    els = [expr_dot(x, y) for x, y in grid_positions(n_expr)]
    els.append(anim_dot(0, 0))
    return {"v": 1, "scene": els}


def scene_text(n_text):
    """text 轴：n_text 个文本 + 1 个动画 dot"""
    els = [text_el(x, y) for x, y in grid_positions(n_text)]
    els.append(anim_dot(0, 0))
    return {"v": 1, "scene": els}


def scene_kitchen():
    """混合极端：512 静态 + 512 动画实例 + 512 expr 元素(1536 exprs) + 128 text + 全背景"""
    els = [dot(x, y) for x, y in grid_positions(512)]
    for x, y in grid_positions(256):
        els.append(anim_dot(x, y))
    for x, y in grid_positions(512):
        els.append(expr_dot(x, y))
    for x, y in grid_positions(128):
        els.append(text_el(x, y))
    return {"v": 1, "bg": BG_FULL, "scene": els}


def build_scene(name):
    """场景名 → (scene dict, 描述dict)。FLOOR 为地板对照。"""
    if name == "FLOOR":
        return {"v": 1, "scene": [anim_dot(0, 0)]}, dict(els=1, anims=2, exprs=0, texts=0)
    m = re.fullmatch(r"E(\d+)", name)
    if m:
        n = int(m.group(1))
        return scene_elements(n), dict(els=n + 1, anims=2, exprs=0, texts=0)
    m = re.fullmatch(r"A(\d+)", name)
    if m:
        n = int(m.group(1))  # n = 动画实例数（双动画元素 ×2）
        return scene_anims(n // 2), dict(els=32 + n // 2, anims=n, exprs=0, texts=0)
    m = re.fullmatch(r"X(\d+)", name)
    if m:
        n = int(m.group(1))  # n = expr 元素数（每个 3 条 expr）
        return scene_expr(n), dict(els=n + 1, anims=2, exprs=n * 3, texts=0)
    m = re.fullmatch(r"T(\d+)", name)
    if m:
        n = int(m.group(1))
        return scene_text(n), dict(els=n + 1, anims=2, exprs=0, texts=n)
    if name == "KITCHEN":
        return scene_kitchen(), dict(els=512 + 256 + 512 + 128, anims=2 + 512, exprs=512 * 3, texts=128)
    raise ValueError("unknown scene: " + name)


ALL_SCENES = (
    ["FLOOR"]
    + ["E%d" % n for n in (16, 64, 128, 256, 512, 1024, 2048)]
    + ["A%d" % n for n in (4, 8, 16, 32, 64, 128, 256, 512, 1024)]
    + ["X%d" % n for n in (3, 12, 48, 192, 768, 1536, 2048)]
    + ["T%d" % n for n in (32, 128, 512, 2048)]
    + ["KITCHEN"]
)
QUICK_SCENES = ["FLOOR", "E64", "E512", "E2048", "A64", "A512", "X192", "X1536", "T512", "KITCHEN"]


# ──────────────────── 采样 ────────────────────

def pidof(tries=6):
    """pidof 带重试：本机 adb daemon 每次调用都重启，首次命令可能竞态返回空。"""
    for i in range(tries):
        out = adb("shell", "pidof " + PKG).stdout.strip()
        if out:
            return out.split()[0]
        time.sleep(1)
    return None


def cpu_jiffies(pid):
    out = adb("shell", "cat /proc/%s/stat" % pid).stdout.strip()
    parts = out.split()
    return int(parts[13]) + int(parts[14])


def gfxinfo_reset():
    adb("shell", "dumpsys gfxinfo %s reset" % PKG)


def gfxinfo_stats():
    out = adb("shell", "dumpsys gfxinfo %s" % PKG).stdout
    stats = {}
    m = re.search(r"Total frames rendered:\s*(\d+)", out)
    stats["frames"] = int(m.group(1)) if m else None
    m = re.search(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", out)
    stats["janky_pct"] = float(m.group(2)) if m else None
    for p in (50, 90, 95, 99):
        m = re.search(r"%dth percentile:\s*(\d+)ms" % p, out)
        stats["p%d" % p] = int(m.group(1)) if m else None
    return stats


def logcat_hud_lines():
    out = adb("shell", "logcat -d -t 4000 | grep -E 'debug hud|hud overlay shown' | tail -4").stdout
    return [l.strip() for l in out.splitlines() if l.strip()]


def screen_awake():
    out = adb("shell", "dumpsys power | grep mWakefulness=").stdout
    return "Awake" in out


def push_scene(scene):
    payload = json.dumps(scene, separators=(",", ":"))
    local = os.path.join(TMP_DIR, "hud_stress_scene.json")
    with open(local, "w", encoding="utf-8") as f:
        f.write(payload)
    adb("push", local, DEVICE_JSON)
    # 设备端 $(cat) 展开，规避 Windows CreateProcess 32KB 命令行上限
    adb("shell",
        'am broadcast -n %s -a %s --es act apply --es dsl "$(cat %s)"'
        % (RECEIVER, ACTION, DEVICE_JSON))
    return len(payload)


def measure_scene(name, window_s):
    scene, desc = build_scene(name)
    push_scene(scene)
    time.sleep(2.5)  # 沉降：解析 + 首帧 + ticker 稳定
    pid = pidof()
    if pid is None:
        return dict(name=name, error="app process not running")
    gfxinfo_reset()
    c0 = cpu_jiffies(pid)
    t0 = time.time()
    time.sleep(window_s)
    dt = time.time() - t0
    c1 = cpu_jiffies(pid)
    st = gfxinfo_stats()
    logs = logcat_hud_lines()
    parse_ms = None
    applied = None
    for l in reversed(logs):
        if "debug hud parsed" in l:
            m = re.search(r"parsed in (\d+)ms.*elements=(\d+)", l)
            if m:
                parse_ms, applied = int(m.group(1)), int(m.group(2))
            break
        if "debug hud parse failed" in l:
            applied = "REJECTED: " + l.split("parse failed:")[-1].strip()[:120]
            break
    frames = st["frames"] or 0
    return dict(
        name=name, els=desc["els"], anims=desc["anims"], exprs=desc["exprs"], texts=desc["texts"],
        fps=round(frames / dt, 1), frames=frames,
        janky_pct=st["janky_pct"],
        p50=st["p50"], p90=st["p90"], p95=st["p95"], p99=st["p99"],
        cpu_pct=round((c1 - c0) * 10 / (dt * 1000) * 100, 1),
        parse_ms=parse_ms, applied=applied, window=round(dt, 1),
    )


# ──────────────────── 报告 ────────────────────

def verdict(r):
    """单场景判定：OK / 边缘 / 卡顿（判据：janky>10% 或 p95>20ms 或 fps<50）"""
    if r.get("error") or r.get("applied") in (None, ) or isinstance(r.get("applied"), str):
        return "❌"
    if (r["janky_pct"] or 0) > 10 or (r["p95"] or 0) > 20 or (r["fps"] or 0) < 50:
        return "🔴"
    if (r["janky_pct"] or 0) > 2 or (r["p95"] or 0) > 17:
        return "🟡"
    return "🟢"


def write_report(results, path_md, path_json):
    with open(path_json, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    lines = [
        "# HUD DSL 硬件摸底压测报告",
        "",
        "- 设备：%s / %s" % (SERIAL, MODEL),
        "- 时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"),
        "- 窗口：每场景 %ds；判据 🟢<2%% janky & p95≤17ms｜🟡 边缘｜🔴 janky>10%% 或 p95>20ms 或 fps<50" % WINDOW,
        "",
        "| 场景 | 元素 | 动画实例 | expr | text | fps | janky% | p50 | p90 | p95 | p99 | CPU% | parse | 应用 | 判定 |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        if r.get("error"):
            lines.append("| %s | – | – | – | – | – | – | – | – | – | – | – | – | %s | ❌ |" % (r["name"], r["error"]))
            continue
        ap = r["applied"]
        ap_s = str(ap) if ap is not None else "?"
        lines.append(
            "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %sms | %s | %s |"
            % (r["name"], r["els"], r["anims"], r["exprs"], r["texts"], r["fps"], r["janky_pct"],
               r["p50"], r["p90"], r["p95"], r["p99"], r["cpu_pct"], r["parse_ms"], ap_s, verdict(r)))
    lines += ["", "## 逐轴结论（自动生成：该轴首个 🔴 场景 = 掉帧起点）", ""]
    for axis, label in (("E", "元素数轴"), ("A", "动画实例轴"), ("X", "expr 元素轴"), ("T", "text 轴")):
        rows = [r for r in results if r["name"].startswith(axis) and r["name"][1:].isdigit()]
        if not rows:
            continue
        bad = [r for r in rows if verdict(r) == "🔴"]
        if bad:
            lines.append("- **%s**：掉帧起点 = %s（%d 个场景内首个越界）" % (label, bad[0]["name"], len(rows)))
        else:
            lines.append("- **%s**：测到 %s 全部 🟢/🟡，未触顶（上限 = 临时护栏 %s）"
                         % (label, rows[-1]["name"], {"E": 2048, "A": 1024, "X": 2048, "T": 2048}[axis]))
    kitchen = [r for r in results if r["name"] == "KITCHEN"]
    if kitchen:
        lines.append("- **混合极端（KITCHEN）**：%s" % ("🟢 流畅" if verdict(kitchen[0]) in ("🟢", "🟡") else "🔴 掉帧"))
    floor = [r for r in results if r["name"] == "FLOOR"]
    if floor and floor[0].get("fps"):
        lines.append("- **地板帧率（FLOOR）**：%.1f fps ⇒ 屏幕刷新率参考" % floor[0]["fps"])
    with open(path_md, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


# ──────────────────── 主流程 ────────────────────

def main():
    global SERIAL, MODEL, WINDOW
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None)
    ap.add_argument("--window", type=int, default=12, help="每场景采样窗口秒数")
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--scene", action="append", default=None, help="只跑指定场景（可多次）")
    args = ap.parse_args()

    os.makedirs(TMP_DIR, exist_ok=True)
    SERIAL = args.serial or pick_glasses()
    WINDOW = args.window
    MODEL = adb("-s", SERIAL, "shell", "getprop ro.product.model").stdout.strip()
    print("眼镜: %s (%s)" % (SERIAL, MODEL))

    pid = pidof()
    if pid is None:
        sys.exit("❌ %s 进程不在跑 —— 先在眼镜上打开 RokidLink（或手机端拉起链路）再测" % PKG)
    print("pid=%s" % pid)

    if not screen_awake():
        print("⚠️ 屏幕灭屏中 → WAKEUP 点亮（帧率测量需要亮屏；ticker 灭屏自动暂停是本版新行为）")
        adb("shell", "input keyevent KEYCODE_WAKEUP")
        time.sleep(1)
        if not screen_awake():
            sys.exit("❌ 点不亮，请手动唤醒眼镜")

    scenes = args.scene or (QUICK_SCENES if args.quick else list(ALL_SCENES))
    print("场景 %d 个，窗口 %ds/个，预计 %.1f 分钟" % (len(scenes), WINDOW, len(scenes) * (WINDOW + 4.5) / 60))

    results = []
    for i, name in enumerate(scenes, 1):
        print("[%d/%d] %s ..." % (i, len(scenes), name), flush=True)
        try:
            r = measure_scene(name, WINDOW)
        except Exception as e:  # 单场景失败不中断整轮
            r = dict(name=name, error=str(e)[:120])
        results.append(r)
        print("    fps=%s janky=%s%% p95=%sms cpu=%s%% parse=%sms applied=%s"
              % (r.get("fps"), r.get("janky_pct"), r.get("p95"), r.get("cpu_pct"),
                 r.get("parse_ms"), r.get("applied", r.get("error"))), flush=True)

    # 收尾：收起场景 + 清理设备临时文件
    adb("shell", "am broadcast -n %s -a %s --es act hide" % (RECEIVER, ACTION))
    adb("shell", "rm -f %s" % DEVICE_JSON)

    md = os.path.join(TMP_DIR, "hud_stress_result.md")
    js = os.path.join(TMP_DIR, "hud_stress_result.json")
    write_report(results, md, js)
    print("\n完成 → %s\n      %s" % (md, js))
    print("\n" + open(md, encoding="utf-8").read())


if __name__ == "__main__":
    main()
