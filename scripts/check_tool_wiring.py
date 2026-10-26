#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RokidLab 工具接线体检（工具接缝化后版本）。

背景（真踩过的坑，2026-09-18）：
合并 `get_glasses_battery` / `get_glasses_device_info` / `get_glasses_storage` 时，用「正则区间替换」
把 `ToolMeta(name = "get_glasses_battery")` 到 `name = "launch_glasses_app"` 之间整段删掉重写 ——
区间**跨过了相邻的 `list_glasses_apps`**，于是它的 toolList 条目被静默删除。
而 `GLASSES_REQUIRED_TOOLS` / `ToolRiskMap` / `statusText` / provider / 字符串全都还在，
**肉眼看代码完全看不出缺了一环**；编译通过、226 条单测全绿。

接缝化后（2026-09-19）：工具由各 provider 在 `tools()` 里**自声明完整元数据**
（name/group/名称与描述资源/风险档/副作用/需眼镜/状态文案/确认摘要/**确认策略**/schema），
`ToolRegistry` 的各张派生表全部由它派生 —— "只漏其中一张表"在结构上已不可能。
本脚本因此从「防漏检查」升级为**回归测试**：它守住的是
「声明是否仍然自洽」，而不是「几张表是否互相对齐」。

仍然必查的几件事（都是真出过或极易出的错）：
  1. 每个 provider 的 `toolNames` 与它 `tools()` 里声明的 name **双向相等**
  2. 每个声明都必须带 risk / schema / statusText，且 schema 内的 name 与声明 name 一致
  3. 全局工具名唯一、无重复
  4. `displayNameRes` / `descriptionRes` 指向的字符串资源真的存在
  5. ToolRegistry 里**不许再出现**手写的 `val toolList: List<ToolMeta> = listOf(`
     （防止有人"图省事"回退到旧写法，那会让接缝形同虚设）
  6. **动态 provider**（`McpToolProvider`：工具集运行期才知道）不许被静默跳过 ——
     `toolNames` 与 `tools()` 必须委托给同一接收者、取不同成员
  7. **确认策略（`ToolConfirmPolicy`，2026-09-23 新增）不许被写成 fail-open 漏洞** ——
     闸门"问不到用户"时的处置由工具的 `confirmPolicy` 声明决定（默认按风险档推导：
     `EXTERNAL_SIDE_EFFECT` ⇒ `BLOCK`，其余 ⇒ `PROCEED`）。若有人显式给一个
     **外部副作用工具**写 `PROCEED`，就等于重演 `send_sms` 那次"静默把短信发出去"的洞。
     本检查把它当**错误**报出来（确属有意请登记到 `EXTERNAL_FAIL_OPEN_ALLOWLIST` 并附理由）。
     反向的 `BLOCK` + 非外部档只作提示：那值不会被读（`RiskApprovalGuard` 只对 EXTERNAL 产生 Ask），
     属于死字段，值得清掉但不必拦。

⚠️ **工具名可以来自常量**，两种写法都必须认：
   1. 限定名 `<接收者>.<常量名>`（如 `name = ReadOnlySubagent.TOOL_NAME`）；
   2. **裸名** `<常量名>`（如 provider 自己 `object X { const val TOOL_Y = "…" }` 里写 `name = TOOL_Y`）。
   `collect_const_values` 会建两张表：`(接收者, 常量名) -> 值` 与 `常量名 -> {值集合}`。
   裸名只在**全局唯一**时才解析（同名常量在不同 object 里取值不同 ⇒ 一律判 None，宁可报错也不猜）。
   不认这两种写法的话，脚本会把名字读成空/None，于是**"接线一致"的结论是假的**（漏报甚至直接崩）。
   历史上两次翻车都源于此：`SubagentToolProvider`（限定名，2026-09-19）、
   `VisionToolProvider`（裸名，2026-09-20）。
   反过来也成立：**不要在业务代码里为了迁就脚本写重复字面量**，该修的是脚本。

用法：
    C:/Users/Administrator/.workbuddy/binaries/python/versions/3.13.12/python.exe \
        check_tool_wiring.py [RokidLab 仓库根]

退出码 0 = 接线完整；1 = 有不一致（详见输出）。
"""
import io
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else r"D:\rokidapp\cxrl\RokidLab"
SRC = os.path.join(ROOT, "phone-app", "src", "main")
AI = os.path.join(SRC, "java", "com", "rokidlab", "phone", "ai")
TOOLS = os.path.join(AI, "tools")

# Kotlin 字符字面量（'"'、'\''、'\n' 这类）：必须整体跳过 —— 否则值里带 `"` 的
# 字面量会翻转 in_str，把后续代码当成"字符串内部"，表现为 unbalanced / 漏查。
# 只认「一字符或转义」的完整字面量，注释里的撇号（user's）不会误入此分支。
CHAR_LIT_RE = re.compile(r"'(\\.|[^\\'])'")


def read(path):
    return io.open(path, encoding="utf-8", errors="ignore").read()


def strip_comments(text):
    """字符串感知地去注释。

    ⚠️ 必须感知字符串：工具描述里大量出现 `https://…`（如 get_cover_image / fetch_webpage），
    用朴素的 `//[^\\n]*` 正则会把字符串**从 `//` 处截断**，导致字符串未闭合、
    随后的括号配平跑偏 —— 表现为「某个文件莫名 unbalanced」或静默漏掉若干工具。
    （同一个坑在生成器里也踩过一次，见 2026-09-19 日志。）

    同时跳过 Kotlin 原始字符串 `\"\"\"…\"\"\"`。
    """
    out = []
    i = 0
    n = len(text)
    in_str = False   # 普通字符串内
    in_raw = False   # 原始字符串内
    esc = False
    while i < n:
        c = text[i]
        if in_raw:
            if text.startswith('"""', i):
                in_raw = False
                out.append('"""')
                i += 3
                continue
            out.append(c)
            i += 1
            continue
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if text.startswith('"""', i):
            in_raw = True
            out.append('"""')
            i += 3
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "'":
            m = CHAR_LIT_RE.match(text, i)
            if m:
                out.append(m.group(0))
                i = m.end()
                continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j == -1 else j
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j == -1 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def balanced(text, start, op="(", cl=")"):
    """字符串感知的括号配平（Kotlin 字符串模板里允许嵌套引号，故需跳过字符串内部）。"""
    depth = 0
    i = start
    in_str = False
    esc = False
    while i < len(text):
        c = text[i]
        if esc:
            esc = False
        elif c == "\\":
            esc = True
        elif c == '"':
            in_str = not in_str
        elif c == "'":
            m = CHAR_LIT_RE.match(text, i)
            if m:
                i = m.end()
                continue
        elif not in_str:
            if c == op:
                depth += 1
            elif c == cl:
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    raise ValueError("unbalanced from %d" % start)


def collect_const_values(root):
    """扫 `object X { … const val Y = "…" }`，返回 (限定表, 裸名索引, 按文件分组表)。

    限定表 key = `(接收者简单名, 常量名)`。为什么要**按接收者分组**而不是按常量名做全局表：
    `TOOL_NAME` 在 LongTermMemoryManager / SkillRegistry / AgentPlan / ReadOnlySubagent 里
    各有一个**不同**的值，按裸名查会解析出随机的那一个 —— 那比解析不出来更危险。
    所以裸名索引保留 `常量名 -> {值集合}`，只有**值唯一**时才允许裸名解析（见 `resolve_name`）。

    第三个返回值 `by_file`：key = 文件全路径，value = `常量名 -> {值集合}`。
    它补的是全局唯一性判据的一个盲区：`ShellToolProvider.TOOL_RUN = "run_shell"` 与
    `ScriptToolProvider.TOOL_RUN = "run_script"` **同名不同值**，而 provider 的常量天然
    按 provider 分组 —— 全局看是歧义，**在各文件内却是确定的**。
    （2026-09-23 实测：加了 `install_packages` 后两个 provider 的 TOOL_RUN 撞名，
    脚本把两个文件都报成"解析不出"，结论失真。）
    解析顺序：**文件内唯一 → 全局唯一 → 放弃（报错好过猜错）**。
    """
    table = {}
    bare = {}
    by_file = {}
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if not f.endswith(".kt"):
                continue
            path = os.path.join(dirpath, f)
            src = strip_comments(read(path))
            per_file = by_file.setdefault(path, {})
            for om in re.finditer(r"\bobject\s+([A-Za-z_0-9]+)", src):
                obj = om.group(1)
                start = src.find("{", om.end())
                if start < 0:
                    continue
                try:
                    end = balanced(src, start, "{", "}")
                except ValueError:
                    continue
                for cm in re.finditer(r'const val ([A-Za-z_0-9]+)\s*=\s*"([^"]*)"', src[start:end]):
                    name, val = cm.group(1), cm.group(2)
                    table[(obj, name)] = val
                    bare.setdefault(name, set()).add(val)
                    per_file.setdefault(name, set()).add(val)
    return table, bare, by_file


# 一行里可能出现三种写法：字符串字面量、`接收者.常量`、或**裸常量名**（provider 自己的 object 内）。
TOKEN_RE = re.compile(r'("[a-z_0-9]+"|[A-Za-z_0-9]+\.[A-Za-z_0-9]+|[A-Za-z_0-9]+)')


def resolve_name(token, consts, bare=None, file_bare=None):
    """把 `"get_time"` / `ReadOnlySubagent.TOOL_NAME` / 裸 `TOOL_LOOK` 归一成字符串；解析不出返回 None。"""
    if token is None:
        return None
    token = token.strip()
    lit = re.fullmatch(r'"([a-z_0-9]+)"', token)
    if lit:
        return lit.group(1)
    ref = re.fullmatch(r"([A-Za-z_0-9]+)\.([A-Za-z_0-9]+)", token)
    if ref:
        return consts.get((ref.group(1), ref.group(2)))
    if re.fullmatch(r"[A-Za-z_0-9]+", token):
        # ① 先看**本文件内**是否唯一（provider 常量按 provider 分组，见 collect_const_values）
        if file_bare is not None:
            vals = file_bare.get(token)
            if vals and len(vals) == 1:
                return next(iter(vals))
        # ② 再退到全局唯一；同名多值 ⇒ None（报错好过猜错）
        if bare is not None:
            vals = bare.get(token)
            if vals and len(vals) == 1:
                return next(iter(vals))
    return None


ok = True

# 「外部副作用工具却显式声明 fail-open」的豁免名单 —— 空表示不允许。
# 这里每加一个名字，都必须附上「为什么问不到用户也可以照做」的理由；
# 没有名单时，任何一条这样的声明都会被当错误拦下（见 4.6 节），因为它是 `send_sms` 那类
# 「静默越界执行」事故的唯一复现路径。默认派生值（EXTERNAL ⇒ BLOCK）本来就是对的。
#
# ⚠️ 入册≠免审：这里的三个工具之所以是 EXTERNAL 档，是因为它们的**动作本身**需要"问用户"
# 这一环（删东西 / 装东西）；定档理由与 fail-open 理由都写在各 provider 的声明注释里，
# 改判据时两处一起看。
EXTERNAL_FAIL_OPEN_ALLOWLIST = {
    "delete_file":
        "删的是本机文件（AIUI 工程镜像 / 下载目录副本），影响不出本机；"
        "按 BLOCK 会让本机模式下删除直接失败（save_code_file 事故的方向）",
    "delete_script":
        "删的是脚本库里一条本机脚本，可重新生成；同上，BLOCK 会让本机模式删除失败",
    "install_packages":
        "装进可丢弃的容器 rootfs（重下 28.5 MB 即恢复），不触及本机系统；"
        "与 send_sms「发出去收不回」不是一个量级",
    "restore_container":
        "恢复的是可丢弃容器 rootfs 到快照点（最坏丢掉快照后装的包，rootfs 重下 28.5 MB 即恢复）；"
        "动作本身会先经确认通道展示（summarize 说清覆盖语义），按 BLOCK 会让本机模式恢复直接失败",
}

# ── 1. 收集所有 provider 的声明 ──
consts, bare, consts_by_file = collect_const_values(SRC)
providers = {}          # provider 文件名 -> {"toolNames": set, "entries": [dict]}

# 动态 provider（工具集在运行期才知道，如 MCP）：toolNames 是 **getter** 而不是 setOf 字面量。
# ⚠️ 这类 provider 走不了"双向核对"（两次读之间对端可能已断开，set 与 list 本就不保证相等），
# 但它**也不能被静默跳过** —— 静默跳过正是本脚本最怕的失效模式（漏报比误报危险）。
# 因此改为断言「同一快照内自洽」：getter 与 tools() 必须委托给**同一个接收者**、
# 且取的是**不同的两个成员**（一个给名字、一个给完整声明）。
DYNAMIC_RE = re.compile(
    r"override val toolNames\b[^=]*?get\(\)\s*=\s*([A-Za-z_0-9]+)\.([A-Za-z_0-9]+)")
DELEGATE_RE = re.compile(
    r"override fun tools\(\)[^=]*?=\s*([A-Za-z_0-9]+)\.([A-Za-z_0-9]+)")
dynamic = {}            # provider 文件名 -> (接收者, 名字成员, 声明成员)

for f in sorted(os.listdir(TOOLS)):
    if not f.endswith("ToolProvider.kt"):
        continue
    src = strip_comments(read(os.path.join(TOOLS, f)))
    file_bare = consts_by_file.get(os.path.join(TOOLS, f))
    # `ToolProvider.kt` 是**接口定义**本身（抽象 val / 抽象 fun，没有 override），不是实现 ⇒ 跳过。
    # 不显式跳过的话，它会被下面的兜底分支当成"认不出的写法"报错。
    if re.search(r"\binterface\s+ToolProvider\b", src):
        continue
    dm = DYNAMIC_RE.search(src)
    if dm:
        deleg = DELEGATE_RE.search(src)
        if not deleg:
            ok = False
            print("!! %s：toolNames 是动态 getter，但 tools() 不是简单委托 —— "
                  "脚本无法核对，请人工确认两者读的是同一数据源" % f)
            continue
        if dm.group(1) != deleg.group(1):
            ok = False
            print("!! %s：toolNames 读 %s，tools() 读 %s —— 两个数据源不同，"
                  "会出现「设置页有、模型调不到」" % (f, dm.group(1), deleg.group(1)))
            continue
        if dm.group(2) == deleg.group(2):
            ok = False
            print("!! %s：toolNames 与 tools() 取的是同一个成员 %s.%s —— "
                  "类型上不可能同时是 Set<String> 与 List<ToolEntry>"
                  % (f, dm.group(1), dm.group(2)))
            continue
        dynamic[f] = (dm.group(1), dm.group(2), deleg.group(2))
        providers[f] = dict(toolNames=set(), entries=[])
        continue
    m = re.search(r"override val toolNames[^=]*=\s*setOf\(([^)]*)\)", src, re.S)
    if m is None:
        ok = False
        print("!! %s：认不出 toolNames 的写法（既不是 setOf 字面量，也不是 getter 委托）—— "
              "该 provider 会被漏查" % f)
        continue
    names = set()
    for tok in TOKEN_RE.findall(m.group(1)):
        v = resolve_name(tok, consts, bare, file_bare)
        if v:
            names.add(v)
        else:
            ok = False
            print("!! %s：toolNames 里的 %s 解析不出工具名（脚本认不出这种写法，结论会失真）" % (f, tok))

    entries = []
    tm = re.search(r"override fun tools\(\)[^=]*=\s*listOf\(", src, re.S)
    if tm:
        body = src[tm.end() - 1:]
        pos = 0
        while True:
            em = re.search(r"ToolEntry\(", body[pos:])
            if not em:
                break
            st = pos + em.start() + len("ToolEntry")
            try:
                close = balanced(body, st)
            except ValueError:
                # ★ 2026-10-18 复审修：解析不了就显式失败，不以堆栈崩溃收场
                # （崩溃会让 CI 只有 traceback、没有违规清单，排查成本更高）
                ok = False
                print("!! %s：tools() 里第 %d 个 ToolEntry 括号配不平（脚本解析不了该写法），该文件其余条目漏查" % (f, len(entries) + 1))
                break
            blk = body[st:close + 1]
            pos = close + 1
            nm = re.search(r"name = " + TOKEN_RE.pattern, blk)
            gm = re.search(r"group = ToolRegistry\.([A-Z_]+)", blk)
            dn = re.search(r"displayNameRes = (R\.string\.[A-Za-z_0-9]+)", blk)
            de = re.search(r"descriptionRes = (R\.string\.[A-Za-z_0-9]+)", blk)
            rk = re.search(r"risk = ToolRisk\.([A-Z_]+)", blk)
            sm = re.search(r'schema = toolSchema\(\s*name = ' + TOKEN_RE.pattern, blk)
            # confirmPolicy：可省（省则按 risk 推导默认值）；写了就必须是枚举里的值。
            # 用 `[A-Za-z_0-9.]*` 而不是 `([A-Z_]+)`，是为了把写成变量/错名的情况也**捕获到**
            # —— 否则「写了但认不出」会被静默当成"没写"，那正是本脚本最怕的漏报。
            cp_raw = re.search(r"confirmPolicy\s*=\s*([A-Za-z_0-9.]+)", blk)
            cp = None
            cp_bad = None
            if cp_raw:
                cpm = re.fullmatch(r"ToolConfirmPolicy\.([A-Z_]+)", cp_raw.group(1))
                if cpm and cpm.group(1) in ("PROCEED", "BLOCK"):
                    cp = cpm.group(1)
                else:
                    cp_bad = cp_raw.group(1)
            entries.append(dict(
                name=resolve_name(nm.group(1) if nm else None, consts, bare, file_bare),
                group=gm.group(1) if gm else None,
                dn=dn.group(1).split(".")[-1] if dn else None,
                de=de.group(1).split(".")[-1] if de else None,
                risk=rk.group(1) if rk else None,
                schema_name=resolve_name(sm.group(1) if sm else None, consts, bare, file_bare),
                side_effect="sideEffect = true" in blk,
                needs_glasses="requiresGlasses = true" in blk,
                status_text=("statusText = " in blk),
                confirm_policy=cp,
                confirm_policy_bad=cp_bad,
            ))
    providers[f] = dict(toolNames=names, entries=entries)

all_entries = [e for p in providers.values() for e in p["entries"]]
all_names = [e["name"] for e in all_entries]
print("provider 数 = %d，声明工具数 = %d（另有动态 provider %d 个）"
      % (len(providers), len(all_names), len(dynamic)))
for f, (recv, name_member, entry_member) in sorted(dynamic.items()):
    print("  动态 %s：%s.%s ↔ %s.%s（同一快照内自洽，工具名不下发时数量为 0 属正常）"
          % (f, recv, name_member, recv, entry_member))

# ── 2. 逐 provider：toolNames 与 tools() 双向核对（动态 provider 见上，已单独断言） ──
for f, p in sorted(providers.items()):
    if f in dynamic:
        continue
    declared = [e["name"] for e in p["entries"]]
    if set(declared) != p["toolNames"]:
        ok = False
        print("!! %s：toolNames 与 tools() 不一致" % f)
        print("     只在 toolNames：%s" % sorted(p["toolNames"] - set(declared)))
        print("     只在 tools()  ：%s" % sorted(set(declared) - p["toolNames"]))

# ── 3. 全局唯一 ──
dups = sorted({n for n in all_names if all_names.count(n) > 1})
if dups:
    ok = False
    print("!! 工具名重复：", dups)

# ── 4. 每条声明的必填项 ──
for e in all_entries:
    n = e["name"]
    if not n:
        ok = False
        print("!! 有无名声明：", e)
        continue
    problems = []
    if not e["group"]:
        problems.append("缺 group")
    if not e["risk"]:
        problems.append("缺 risk")
    if not e["dn"] or not e["de"]:
        problems.append("缺 名称/描述资源")
    if e["schema_name"] != n:
        problems.append("schema 名(%s) != 声明名(%s)" % (e["schema_name"], n))
    if not e["status_text"]:
        problems.append("缺 statusText")
    if problems:
        ok = False
        print("!! %-28s %s" % (n, "；".join(problems)))

# ── 4.6 确认策略自洽（ToolConfirmPolicy，2026-09-23） ──
# 闸门在"问不到用户"时的处置 = 工具声明的 confirmPolicy（缺省按 risk 推导）。
# 两个真正要守的不变式：
#   ① 写了就必须写得出来（否则"没写"和"写错"无法区分 ⇒ 漏报）
#   ② **外部副作用工具不许显式 fail-open** —— 这正是 `send_sms` 那次静默发信的洞
#      （改造前 fail-open 条件写死 `domain == MCP`，send_sms 与 call_phone 走同一条放行路径）。
for e in all_entries:
    n = e["name"]
    if not n:
        continue
    if e["confirm_policy_bad"] is not None:
        ok = False
        print("!! %-28s confirmPolicy 写法认不出（%s）—— 枚举只有 ToolConfirmPolicy.PROCEED / BLOCK；"
              "认不出会让本检查把「写错」当成「没写」" % (n, e["confirm_policy_bad"]))
        continue
    cp = e["confirm_policy"]
    risk = e["risk"]
    if cp is None:
        # 没写 ⇒ 按 risk 派生（defaultFor）。派生结果本身安全，无需干预。
        continue
    if risk == "EXTERNAL_SIDE_EFFECT" and cp == "PROCEED":
        if n in EXTERNAL_FAIL_OPEN_ALLOWLIST:
            print("⚠️  %-28s 外部副作用工具被显式豁免为 fail-open（问不到用户就照做）；"
                  "理由：%s" % (n, EXTERNAL_FAIL_OPEN_ALLOWLIST[n]))
        else:
            ok = False
            print("!! %-28s 外部副作用工具却声明 confirmPolicy = PROCEED —— "
                  "这是「问不到用户就静默执行越界动作」的洞（send_sms 同类）。"
                  "默认派生已经是 BLOCK；若确属有意，请登记进 EXTERNAL_FAIL_OPEN_ALLOWLIST 并写明理由" % n)
    elif cp == "BLOCK" and risk != "EXTERNAL_SIDE_EFFECT":
        print("⚠️  %-28s 非外部档工具声明了 confirmPolicy = BLOCK —— 该值不会被读"
              "（RiskApprovalGuard 只对 EXTERNAL 产生 Ask），建议删掉以免误导" % n)

# ── 5. 字符串资源存在性 ──
zh = read(os.path.join(SRC, "res", "values", "strings.xml"))
en = read(os.path.join(SRC, "res", "values-en", "strings.xml"))
zh_keys = set(re.findall(r'<string name="([A-Za-z_0-9]+)"', zh))
en_keys = set(re.findall(r'<string name="([A-Za-z_0-9]+)"', en))
for e in all_entries:
    for k in (e["dn"], e["de"]):
        if not k:
            continue
        if k not in zh_keys:
            ok = False
            print("!! 缺中文字符串资源：%s（工具 %s）" % (k, e["name"]))
        elif k not in en_keys:
            ok = False
            print("!! 缺英文字符串资源：%s（工具 %s）" % (k, e["name"]))

# ── 6. 防回退：ToolRegistry 不许再有手写 toolList ──
reg = strip_comments(read(os.path.join(AI, "ToolRegistry.kt")))
if re.search(r"val toolList: List<ToolMeta> = listOf\(", reg):
    ok = False
    print("!! ToolRegistry 又出现手写 toolList 字面量 —— 接缝被绕过，六张表会重新失去一致性保证")

# ── 7. 派生表健康度摘要 ──
named = [e for e in all_entries if e["name"]]
se = sorted(e["name"] for e in named if e["side_effect"])
gl = sorted(e["name"] for e in named if e["needs_glasses"])
ro = sorted(e["name"] for e in named if e["risk"] == "READ_ONLY")
# 确认策略的**生效值**（显式声明优先，否则按风险档派生）—— 与 ApprovalGate 读的是同一套判据。
def _effective_confirm(e):
    if e["confirm_policy"]:
        return e["confirm_policy"]
    return "BLOCK" if e["risk"] == "EXTERNAL_SIDE_EFFECT" else "PROCEED"
cb = sorted(e["name"] for e in named if _effective_confirm(e) == "BLOCK")
print("\n派生表（均由 tools() 聚合，不再手工维护）")
print("  SIDE_EFFECT_TOOLS   (%d): %s" % (len(se), se))
print("  GLASSES_REQUIRED    (%d): %s" % (len(gl), gl))
print("  READ_ONLY（无人值守准入）(%d): %s" % (len(ro), ro))
print("  CONFIRM_BLOCK（问不到用户即拒）(%d): %s" % (len(cb), cb))
if dynamic:
    print("  ⚠️ 上表**不含**动态 provider 的工具（运行期才存在，数量随连接状态变化）：%s"
          % sorted(dynamic))

print("\n结论：" + ("接线完整 ✅" if ok else "存在不一致 ❌（见上）"))
sys.exit(0 if ok else 1)
