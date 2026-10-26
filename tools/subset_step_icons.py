#!/usr/bin/env python3
"""子集化 Material Icons Outlined 字体，供 RokidLink（眼镜端）绘制过程卡片步骤图标。

背景
----
眼镜端是传统 View 体系（无 Compose），但步骤图标要和手机端过程卡片
`ChatBubble.kt` 的 `Kind -> Icons.Outlined.X` **同一套字形**。做法：
把官方 MaterialIconsOutlined 字���按用到的 codepoint 子集化成
`RokidLink/src/main/res/font/mat_step_icons.ttf`，用 TextView +绿色 textColor 渲染。

⚠️ 增删字形的唯一正确姿势
   1. 改本脚本 ICON_CODEPOINTS；
   2. 重跑本脚本；
   3. 改 `ProcessTimelineController.STEP_ICON_CODEPOINTS` 映射表。
   只改映射表不重跑字体 ⇒ 该 codepoint 不在子集内 ⇒ 画出**豆腐块**（空框），
   单测与编译都发现不了（见脚本末尾自检）。

用法
----
  curl -L -o /tmp/src.otf \
    "https://cdn.jsdelivr.net/gh/google/material-design-icons@master/font/MaterialIconsOutlined-Regular.otf"
  python tools/subset_step_icons.py /tmp/src.otf
"""

import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

# ── 眼镜端过程卡片用到的字形（与手机端 ChatBubble.kt 的 Icons.Outlined 逐项对应）──
# codepoint 取自官方 MaterialIconsOutlined-Regular（upem 512）。
ICON_CODEPOINTS = {
    "psychology": 0xEA4A,   # THINKING  思考
    "menu_book": 0xEA19,    # KNOWLEDGE 知识库
    "build": 0xE869,        # TOOL      工具
    "compress": 0xE94D,     # COMPRESS  压缩
    "checklist": 0xE6B1,    # PLAN      计划（当前不进帧，保留防御）
    "flag": 0xE153,         # SUMMARY   收尾
    "settings": 0xE8B8,     # FRAMEWORK 框架
    "storage": 0xE1DB,      # MEMORY    上下文注入
    "report": 0xE160,       # REFLECT   自省
}

OUT = Path(__file__).resolve().parents[1] / "RokidLink/src/main/res/font/mat_step_icons.ttf"


def main(src: str) -> int:
    opts = subset.Options()
    opts.name_IDs = ["*"]
    opts.name_legacy = True
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.recalc_bounds = True
    opts.drop_tables = ["DSIG"]
    opts.layout_features = []
    opts.desubroutinize = True

    font = subset.load_font(src, opts)
    sub = subset.Subsetter(options=opts)
    sub.populate(unicodes=sorted(ICON_CODEPOINTS.values()))
    sub.subset(font)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    subset.save_font(font, str(OUT), opts)

    # ── 自检：字体里必须正好是这组码位（不多不少）──
    got = set(TTFont(str(OUT)).getBestCmap().keys())
    want = set(ICON_CODEPOINTS.values())
    missing = sorted(hex(c) for c in want - got)
    extra = sorted(hex(c) for c in got - want)
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes), glyphs={len(got)}")
    if missing:
        print(f"ERROR: 缺失字形 {missing} —— 眼镜端会画出豆腐块")
        return 1
    if extra:
        print(f"WARN: 字体多出 {extra}（不影响渲染，可忽略）")
    print("OK: 映射表与字体严格一致")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))