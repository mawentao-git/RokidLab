import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    kotlin("android")
    alias(libs.plugins.kotlin.compose)
}

// ── 发布前闸门（release 洁净工作区 + release 依赖单测），双端共用同一份实现 ──
apply(from = "../gradle/local-gates.gradle.kts")

/**
 * 读取一个签名属性；缺失或为空白时返回 null（**不抛异常**）。
 *
 * 为什么不在配置阶段抛：`android { signingConfigs { ... } }` 在**配置阶段无条件执行**，
 * 若在里面直接抛，`compileDebugKotlin` / `installDebug` 这类与签名毫无关系的任务
 * 也会因「本机没配 release 口令」而构建失败 —— 发布闸门被误当成日常开发闸门
 * （2026-09-18 实测：签名治理落地后 Debug 编译直接挂）。
 * 因此「缺什么」的判定与拦截下移到 [releaseSigningGate]，按**实际任务图**精确触发。
 *
 * 与 RokidLink 侧保持逐字一致（两端签名参数必须同源，改动请双改）。
 *
 * @param name     属性名（-P / gradle.properties / ORG_GRADLE_PROJECT_<name> 环境变量均可）
 * @param fallback 可选默认值；**仅限非机密项**（如 keystore 路径），口令类一律不传
 */
fun optionalSigningProperty(name: String, fallback: String? = null): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() } ?: fallback

/**
 * 解析 keystore 文件：绝对路径直接用；相对路径相对根工程解析。
 * 刻意不走 rootProject.file()：在 Linux/CI 上遇到 Windows 盘符路径（默认值
 * D:\rokidapp\release.keystore）会被 Gradle 当成 URI 解析而抛 URISyntaxException
 * （2026-09-26 GitHub Actions 实测），java.io.File 构造则跨平台安全。
 * 与 RokidLink 侧保持逐字一致（改动请双改）。
 */
fun resolveKeystore(path: String): File =
    File(path).let { if (it.isAbsolute) it else File(rootProject.projectDir, path) }

/** release keystore 默认路径（非机密项，允许回退；实际位置用 RELEASE_KEYSTORE_PATH 覆盖） */
val defaultReleaseKeystorePath = "D:\\rokidapp\\release.keystore"

/** 当前缺失的 release 签名材料清单（空列表 = 齐备）。供 [releaseSigningGate] 生成报错。 */
fun releaseSigningProblems(): List<String> = buildList {
    val path = optionalSigningProperty("RELEASE_KEYSTORE_PATH", defaultReleaseKeystorePath)!!
    if (!resolveKeystore(path).isFile) {
        add("keystore 文件不存在：$path（用 RELEASE_KEYSTORE_PATH 指定实际位置）")
    }
    if (optionalSigningProperty("RELEASE_KEYSTORE_PASSWORD") == null) {
        add("缺少 RELEASE_KEYSTORE_PASSWORD（keystore 口令）")
    }
    if (optionalSigningProperty("RELEASE_KEY_ALIAS") == null) {
        add("缺少 RELEASE_KEY_ALIAS（签名密钥别名）")
    }
    if (optionalSigningProperty("RELEASE_KEY_PASSWORD") == null) {
        add("缺少 RELEASE_KEY_PASSWORD（签名密钥口令）")
    }
}

/**
 * release 签名闸门：仅当任务图里**真的出现 release 打包任务**时才要求签名材料齐备。
 *
 * 判定基于**实际任务图**（而不是任务名字符串猜测），因此 `assemble` / `build` 这类
 * 同时产出 debug 与 release 的聚合任务也会被正确拦住 —— 它们同样会走到 packageRelease。
 * 历史教训：此处曾有 `orElse("rokid123")` 明文回退，会让缺配置的机器静默产出一个
 * 「签名看似正确」的包，事后极难发现来源不对。现在缺材料一律硬失败，且**不做任何回退**。
 *
 * ⚠️ 必须显式写成 `Action { }`（不能写 `whenReady { }`）：`TaskExecutionGraph.whenReady`
 * 有 `Action` 与 Groovy `Closure` 两个重载，Kotlin 的裸 lambda 会解析到 `Closure` 那个
 * （实测报 "Closure<(raw) Any!> was expected"）。而 Kotlin DSL 的 `Action<T> { }` 是
 * **带接收者**的变体（`inline fun <T> Action(block: T.() -> Unit)`），所以块内没有参数、
 * 用 `allTasks` 直接取任务图 —— 写成带参数形式会报 "Expected no parameters"。
 */
gradle.taskGraph.whenReady(
    org.gradle.api.Action<org.gradle.api.execution.TaskExecutionGraph> {
        val packaging = allTasks.filter { t ->
            t.project == project && t.name.contains("Release") &&
                listOf("package", "assemble", "bundle", "install", "publish").any { t.name.startsWith(it) }
        }
        if (packaging.isNotEmpty()) {
            val problems = releaseSigningProblems()
            if (problems.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        append("release 签名材料不齐备，已中止：${packaging.joinToString { it.name }}\n")
                        problems.forEach { append("  ✗ $it\n") }
                        append("为避免静默产出错误来源的包，此处不做任何回退。请任选一种方式提供：\n")
                        append("  ① 在 gradle.properties 中写（该文件已被 .gitignore 排除）\n")
                        append("  ② 在 ~/.gradle/gradle.properties 中写（推荐：跨项目复用且绝不会误提交）\n")
                        append("  ③ 设置环境变量 ORG_GRADLE_PROJECT_<属性名>\n")
                        append("  ④ 命令行传参 -P<属性名>=...\n")
                        append("模板见仓库根 gradle.properties.example。")
                    },
                )
            }
        }
    },
)

android {
    namespace = "com.rokidlab.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rokidlab.phone"
        minSdk = 29
        targetSdk = 34
        versionCode = 36
        versionName = "5.1"
        manifestPlaceholders["cleartextTrafficPermitted"] = "false"

        // 本地 OCR（onnxruntime + opencv）体积较大，只保留主流真机 ABI
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // 签名参数一律显式提供，**缺失时留空**、由上面的 releaseSigningGate 硬失败拦截
            // （此处不得抛异常：本块在配置阶段无条件执行，抛了会误伤 debug 构建，理由见其上注释）。
            val keystorePath = optionalSigningProperty("RELEASE_KEYSTORE_PATH", defaultReleaseKeystorePath)!!
            val ksFile = resolveKeystore(keystorePath)
            if (ksFile.isFile) storeFile = ksFile
            storePassword = optionalSigningProperty("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = optionalSigningProperty("RELEASE_KEY_ALIAS")
            keyPassword = optionalSigningProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["cleartextTrafficPermitted"] = "true"
        }

        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 混淆压缩 dex，减小 APK 体积（so 的压缩策略见下方 packaging 块，别照抄旧结论）
            isMinifyEnabled = true
            // P0-5：release 关闭全局明文。实际策略以 res/xml/network_security_config.xml 为准
            // （声明该文件后本属性在 API 24+ 被忽略），此处保持同值以避免误读。
            manifestPlaceholders["cleartextTrafficPermitted"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ── JVM 单测：android.jar 桩方法返回默认值，而不是抛 "not mocked" ──
    // 关键链路回归单测（ADB sync 协议 / HID 报表归一化）要直接驱动生产代码，而生产代码里有
    // android.util.Log 调用；不开此项则一碰就抛 RuntimeException("Stub!")。
    // 副作用：Log.getStackTraceString 会返回 null —— LogCollector 的堆栈拼接已做 null 兜底。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // so 库**压缩**存储（⇒ 清单里 extractNativeLibs=true，安装时解压到 nativeLibraryDir）。
    //
    // 为什么必须是 true —— 两件事同时成立才敢改：
    //  1. **自持 proot 的硬需求**：W^X 只允许在 `nativeLibraryDir`（SELinux 类型 `apk_data_file`）
    //     里 `execve`；而 `useLegacyPackaging = false` 时 .so 是**未压缩直载**、系统**根本不落盘** ⇒
    //     nativeLibraryDir 是个空目录，execve 必然 ENOENT。
    //     （2026-09-22 真机实测：装完 APK 后 `libproot.so` 不在位，自检报「二进制不在位」。）
    //  2. **官方 16KB 页面文档明确支持这条路**：「如果无法升级到 AGP 8.5.1+，可以改用**压缩共享库**……
    //     从而避免因共享库未对齐而导致的应用安装问题」。
    //     ⚠️ 原注释写反了：会让 16KB 设备**装不上**的是「未压缩且未做 16KB zip 对齐」，
    //     **不是**压缩 —— 压缩的库安装时被解压，压根不参与 zip 对齐检查。
    // 代价：安装后占用磁盘变大（库被解压落盘，本包 11 个库约 +45 MB）；APK 本身反而变小。
    // 依据：https://developer.android.com/guide/practices/page-sizes
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // ⚠️ 这里曾有一条 pickFirsts += "lib/x86/libonnxruntime.so"，已删除。
            // 起因是 sherpa-onnx static-link aar 仅在 arm 系把 onnxruntime 静态编进 jni.so，
            // x86 仍带独立的同名 so 会与 onnxruntime-android:1.22.0 冲突。
            // 但上方 abiFilters 只保留 arm64-v8a ⇒ x86 产物根本不进包，该规则永不生效，
            // 留着只会让人误以为「还在支持 x86 / 还需处理 so 冲突」。
            // 若将来真要加 x86_64（模拟器调试），届时再按实际冲突的so 加回 pickFirst，
            // 代价是 opencv + onnxruntime 各带一份 x86 版本，APK 体积会显著变大。
        }

        // ── 排掉从未使用的 BPE 编码表（2026-10-05，约-2.8 MB）──
        // jtokkit 1.1.0 把 5 张 tiktoken 表全打进包里，但本项目**只用 cl100k_base**
        // （`ai/compaction/TokenMeter.kt` 里硬编码 EncodingType.CL100K_BASE）。
        // 保留的三张纯白带，合计 5.04 MB raw / 约 2.8 MB stored：
        //   o200k_base 3.61 MB、p50k_base 0.84 MB、r50k_base 0.84 MB
        //
        // ⚠️ **前置条件（缺一不可）**：TokenMeter 必须用 `Encodings.newLazyEncodingRegistry()`。
        //   反编译确认 DefaultEncodingRegistry 构造时遍历全部 5 个 EncodingType 急加载建表，
        //   表被排掉后构造期就会抛资源缺失，且 count() 的 runCatching 会静默降级成
        //   「1 字 1 token」让预算口径悄悄失真。改这一项前先看 TokenMeter 的类注释。
        // ⚠️ 若将来要支持 o200k 系模型（如 gpt-4o），必须同步撤销这里的 p50k/r50k 排除，
        //    并改 TokenMeter 用对应的 EncodingType。
        resources {
            excludes += setOf(
                "com/knuddels/jtokkit/o200k_base.tiktoken",
                "com/knuddels/jtokkit/p50k_base.tiktoken",
                "com/knuddels/jtokkit/r50k_base.tiktoken",
            )
        }
    }

    // ── APK 输出命名规则 ──
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "RokidLab-v${variant.versionName}-${variant.buildType.name}.apk"
        }
    }
}

// ── 眼镜端 RokidLink APK 自动构建集成 ──
// 构建 phone-app 时, 先构建 RokidLink 模块, 将输出的 APK 拷贝到 assets
// 这样 installRokidLinkToGlasses() 读取的总是最新版 RokidLink
// 兼容两种构建布局：仓库根（RokidLab/）为 ":RokidLink"；外层壳工程（D:\rokidapp）为 ":cxrl:RokidLab:RokidLink"
val rokidLinkProject by lazy {
    rootProject.findProject(":RokidLink")
        ?: rootProject.findProject(":cxrl:RokidLab:RokidLink")
        ?: error("找不到 RokidLink 模块（既不在 :RokidLink 也不在 :cxrl:RokidLab:RokidLink）")
}

// ── ink 引擎资产：与眼镜端共用同一份 ──
// 手机端宿主（AiuiWebHost）与眼镜端（AiuiLinkActivity）跑的是同一套 assets/ink/
// （index.html / host.js / index.js / env.js / lab-page-bridge.js / pkg/*，其中
// ink_web_bg.wasm 有 20MB）。这里把 RokidLink 的 assets 目录挂成本模块的 srcDir，
// APK 里仍是 assets/ink/... 的路径（两端宿主代码零改动），仓库里则只存一份。
//
// ⚠️ 两条约束：
//   1. ink 资产只改 RokidLink 侧那一份；本模块 assets/ 下**不要**再放 ink/，否则同
//      一路径有两份来源（合并结果取决于顺序，会静默用错版本）。
//   2. RokidLink/src/main/assets 里新增的任何文件都会一并打进手机端 APK（目前该目录
//      只有 ink/）。
android.sourceSets.getByName("main").assets.srcDir(rokidLinkProject.projectDir.resolve("src/main/assets"))

val buildRokidLinkDebug by tasks.registering {
    description = "构建 RokidLink (debug) 并拷贝到 phone-app assets"
    group = "build"
    dependsOn("${rokidLinkProject.path}:assembleDebug")
    doLast {
        val sourceApk = rokidLinkProject.buildDir.resolve("outputs/apk/debug/RokidLink-debug.apk")
        val targetFile = file("src/main/assets/RokidLink.apk")
        sourceApk.copyTo(targetFile, overwrite = true)
        logger.lifecycle("RokidLink APK (debug) 已同步 -> $targetFile")
    }
}

val buildRokidLinkRelease by tasks.registering {
    description = "构建 RokidLink (release) 并拷贝到 phone-app assets"
    group = "build"
    dependsOn("${rokidLinkProject.path}:assembleRelease")
    doLast {
        val releaseDir = rokidLinkProject.buildDir.resolve("outputs/apk/release")
        // 优先取已签名的 APK，失败则取未签名的
        val sourceApk = releaseDir.resolve("RokidLink-release.apk").takeIf { it.exists() }
            ?: releaseDir.resolve("RokidLink-release-unsigned.apk").takeIf { it.exists() }
            ?: error("在 $releaseDir 中未找到 RokidLink-release APK")
        val targetFile = file("src/main/assets/RokidLink.apk")
        sourceApk.copyTo(targetFile, overwrite = true)
        logger.lifecycle("RokidLink APK (release) 已同步 ${sourceApk.name} -> $targetFile")
    }
}

// ── 双端协议同源守护（AiChannel.kt + LinkProtocol.kt）──
// 两个协议文件在 phone-app 与 RokidLink 各持一份同源副本，修改必须双端同步。
// 本任务在 preBuild 时校验：除 package 行与空行外逐字节一致，否则构建失败。
// 同时校验 HUD 求值器：phone HudDsl.kt ↔ RokidLink HudOverlayController.kt 的
// EXPR-SYNC 标记区间必须逐字节一致（区间内只准用 kotlin stdlib / java.lang.Math）。
// 另扫描两模块 src/main，禁止在协议文件之外出现裸协议字面量
// （CXR 频道名 "Ai"/"Sys"/"Wifi"/"Jsai"/"Ai_RenderPayload" 与 __LAB_* 控制帧标记），
// 强制所有协议引用收敛到 LinkProtocol / AiChannel。
val checkProtocolSynced by tasks.registering {
    group = "verification"
    description = "校验 phone-app 与 RokidLink 的 AiChannel.kt / LinkProtocol.kt / HUD 求值器（EXPR-SYNC）同源，并禁止裸协议字面量"
    doLast {
        fun normalized(f: File): String = f.readText()
            .lineSequence()
            .filterNot { it.startsWith("package ") || it.isBlank() }
            .joinToString("\n")

        val protocolFiles = listOf(
            "AiChannel.kt" to "src/main/java/com/rokidlab/phone/glasses/AiChannel.kt",
            "LinkProtocol.kt" to "src/main/java/com/rokidlab/phone/glasses/LinkProtocol.kt",
        )
        for ((name, phoneRel) in protocolFiles) {
            val phone = file(phoneRel)
            val glasses = rokidLinkProject.projectDir.resolve(
                phoneRel.replace("com/rokidlab/phone/glasses", "com/rokidlab/rokidlink"),
            )
            if (normalized(phone) != normalized(glasses)) {
                error(
                    "$name 双端不同源！\n  phone-app: $phone\n  RokidLink: $glasses\n" +
                        "修改协议必须同步修改两端文件（仅 package 行允许不同）。",
                )
            }
            logger.lifecycle("checkProtocolSynced: $name 双端同源校验通过")
        }

        // HUD 求值器同源守护：HudDsl.kt ↔ HudOverlayController.kt 的 EXPR-SYNC 标记区间
        // 逐字节一致（含 BEGIN/END 之间全部行，缩进差异即失败）。标记缺失/错序同样报错。
        val exprSyncBegin = "// ══EXPR-SYNC-BEGIN══"
        val exprSyncEnd = "// ══EXPR-SYNC-END══"
        val hudExprPhone = file("src/main/java/com/rokidlab/phone/glasses/HudDsl.kt")
        val hudExprGlass = rokidLinkProject.projectDir.resolve(
            "src/main/java/com/rokidlab/rokidlink/HudOverlayController.kt",
        )
        fun syncRegion(f: File): List<String> {
            val lines = f.readLines()
            val b = lines.indexOfFirst { it.trim() == exprSyncBegin }
            val e = lines.indexOfLast { it.trim() == exprSyncEnd }
            if (b < 0 || e <= b) error("EXPR-SYNC 标记缺失或错序（BEGIN 必须在 END 之前）：$f")
            return lines.subList(b + 1, e)
        }
        val regionPhone = syncRegion(hudExprPhone)
        val regionGlass = syncRegion(hudExprGlass)
        if (regionPhone != regionGlass) {
            val diffAt = (0 until minOf(regionPhone.size, regionGlass.size))
                .firstOrNull { regionPhone[it] != regionGlass[it] }
            error(
                "HUD 求值器双端不同源（EXPR-SYNC 区间）！\n" +
                    "  phone-app: $hudExprPhone（区间 ${regionPhone.size} 行）\n" +
                    "  RokidLink: $hudExprGlass（区间 ${regionGlass.size} 行）\n" +
                    (diffAt?.let {
                        "  首个差异（区间第 ${it + 1} 行）：\n    phone:   ${regionPhone[it]}\n    glasses: ${regionGlass[it]}\n"
                    } ?: "  行数一致但内容有差异。\n") +
                    "修改求值器必须同步修改两端文件（EXPR-SYNC 区间逐字节复制）。",
            )
        }
        logger.lifecycle("checkProtocolSynced: HUD 求值器 EXPR-SYNC 区间同源校验通过（${regionPhone.size} 行）")

        // 裸协议字面量扫描：禁止在协议文件之外直接写 CXR 频道名 / __LAB_* 标记
        val forbiddenChannels = setOf("Ai", "Sys", "Wifi", "Jsai", "Ai_RenderPayload")
        val chanArg = Regex("""(?:sendCustomCmd|sendMessage|rawSendCmd|caps\.write|pushControl|push)\s*\(\s*(?:[^,)]*,\s*)?["']([^"']+)["']""")
        val labMarker = Regex("""__LAB_""")
        val roots = listOf(
            file("src/main/java"),
            rokidLinkProject.projectDir.resolve("src/main/java"),
        )
        val violations = mutableListOf<String>()
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { f ->
                    if (f.name == "LinkProtocol.kt" || f.name == "AiChannel.kt") return@forEach
                    f.useLines { lines ->
                        lines.forEachIndexed { idx, raw ->
                            val trimmed = raw.trimStart()
                            if (trimmed.startsWith("*") || trimmed.startsWith("//") ||
                                trimmed.startsWith("/*") || trimmed.startsWith("*/")
                            ) return@forEachIndexed
                            val code = raw.substringBefore("//")
                            if (labMarker.containsMatchIn(code)) {
                                violations.add("${f.path}:${idx + 1}: 裸 __LAB_ 字面量")
                            }
                            val m = chanArg.find(code)
                            if (m != null && m.groupValues[1] in forbiddenChannels) {
                                violations.add("${f.path}:${idx + 1}: 裸频道字面量 \"${m.groupValues[1]}\"")
                            }
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            error(
                "发现裸协议字面量，请改用 LinkProtocol / AiChannel 常量引用：\n" +
                    violations.joinToString("\n"),
            )
        }
        logger.lifecycle("checkProtocolSynced: 无裸协议字面量")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkProtocolSynced)
}

// ── 多语言 key 集合守护（values/strings.xml ↔ values-en/strings.xml）──
// 规则（RULES「多语言同步」）：新增/修改/删除中文串必须同步英文包。历史上曾出现 5 条
// 中文串缺英文翻译（guide_ready_title / guide_reinstall_link_btn / guide_skip_btn /
// unknown_author / wifi_config_success），只在切英文真机时才暴露。故在 preBuild 强制
// 两个模块的 key 集合完全相等（只比 key，不比顺序与文案）。
val checkI18nKeysSynced by tasks.registering {
    group = "verification"
    description = "校验 phone-app 与 RokidLink 的 values / values-en strings.xml key 集合一致"
    doLast {
        val nameAttr = Regex("""<string\s+name="([^"]+)"""")
        fun keysOf(f: File): Set<String> = nameAttr.findAll(f.readText())
            .map { it.groupValues[1] }
            .toSet()

        val targets = listOf(
            "phone-app" to file("src/main/res"),
            "RokidLink" to rokidLinkProject.projectDir.resolve("src/main/res"),
        )
        for ((module, resDir) in targets) {
            val zh = resDir.resolve("values/strings.xml")
            val en = resDir.resolve("values-en/strings.xml")
            if (!zh.exists() || !en.exists()) {
                error("checkI18nKeysSynced: $module 缺少 strings.xml（$zh / $en）")
            }
            val zhKeys = keysOf(zh)
            val enKeys = keysOf(en)
            val missingEn = (zhKeys - enKeys).sorted()
            val extraEn = (enKeys - zhKeys).sorted()
            if (missingEn.isNotEmpty() || extraEn.isNotEmpty()) {
                error(
                    buildString {
                        append("$module 多语言 key 不一致（values=${zhKeys.size} / values-en=${enKeys.size}）：\n")
                        if (missingEn.isNotEmpty()) {
                            append("  values 有 / values-en 缺（需补英文翻译）: ${missingEn.joinToString(", ")}\n")
                        }
                        if (extraEn.isNotEmpty()) {
                            append("  values-en 有 / values 缺（需删除或补中文）: ${extraEn.joinToString(", ")}\n")
                        }
                        append("  请同步 res/values-en/strings.xml（RULES「多语言同步」）。")
                    },
                )
            }
            logger.lifecycle("checkI18nKeysSynced: $module ${zhKeys.size} 条 key 双语一致")
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkI18nKeysSynced)
}

// ── 关键链路「空 catch」守护（RULES §12.14）──
// 异常吞噬是 P1-8 的根因：链路出问题时 App 内日志面板（LogCollector）里什么都看不到，
// 只能靠用户口述现象反推。规则分两层：
//   1. 关键链路（ASR 补读 / RFCOMM 隧道 / ADB sync / AIUI 工具网关）的 catch 必须落 LogCollector；
//   2. 确实无信息量的空 catch（关闭句柄、消费收尾包、读线程正常退出）必须带 `// catch-ok: <原因>` 标注 ——
//      把「默默吞掉」变成「显式声明的决策」，CR 时一眼可查。
// 全局禁止空 catch 由 RULES §12.14 作 CR 约束，并由下方 checkNoBareCatch 以「棘轮预算」机器兜底。
val keyPathFiles = listOf(
    "src/main/java/com/rokidlab/phone/glasses/AsrBridgeCoordinator.kt",
    "src/main/java/com/rokidlab/phone/glasses/AsrPushClient.kt",
    "src/main/java/com/rokidlab/phone/connection/ConnectionRouteManager.kt",
    "src/main/java/com/rokidlab/phone/platform/AdbTransport.kt",
    "src/main/java/com/rokidlab/phone/adb/AdbFileManagerClient.kt",
    "src/main/java/com/rokidlab/phone/ai/ToolGateway.kt",
)

val checkKeyPathEmptyCatch by tasks.registering {
    group = "verification"
    description = "关键链路禁止裸空 catch：空 catch 必须带 // catch-ok: <原因> 豁免说明"
    doLast {
        val emptyCatch = Regex("""catch\s*\([^)]*\)\s*\{\s*\}""")
        val violations = mutableListOf<String>()
        var waived = 0
        for (rel in keyPathFiles) {
            val f = file(rel)
            if (!f.exists()) {
                error("checkKeyPathEmptyCatch: 关键链路文件缺失（路径已变？请同步本任务清单）: $rel")
            }
            f.readLines().forEachIndexed { idx, line ->
                if (emptyCatch.containsMatchIn(line)) {
                    if (line.contains("catch-ok:")) {
                        waived++
                    } else {
                        violations.add("  $rel:${idx + 1}  ${line.trim()}")
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            error(
                "关键链路存在未标注的空 catch（异常被静默吞掉）：\n" +
                    violations.joinToString("\n") +
                    "\n处理方式二选一：\n" +
                    "  a) 在 catch 内落 LogCollector（推荐，见 RULES §12.14）；\n" +
                    "  b) 若确无信息量（关闭句柄 / 消费收尾包 / 读线程正常退出），补注释 `// catch-ok: <原因>`。",
            )
        }
        logger.lifecycle("checkKeyPathEmptyCatch: ${keyPathFiles.size} 个关键链路文件，$waived 处空 catch 均已标注豁免理由")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkKeyPathEmptyCatch)
}

// ── 全仓「空 catch」预算守护（RULES §12.14，阶段二 #10）──
// checkKeyPathEmptyCatch 对 6 个关键链路文件零容忍；本任务把扫描扩到双端 src/main 全量，
// 采用「棘轮预算」：未标注的空 catch 数只允许下降、不允许上升。
// 为什么不一次清零存量：存量多为「关句柄 / 消费收尾包 / 读线程正常退出」，逐一补
// `// catch-ok:` 属纯注释 churn，收益低于回归风险；先把闸门立起来，新增一处即构建失败，
// 存量在后续改动该文件时顺手收敛（预算可随之下调）。
// 2026-09-18 收敛：基线 47 → 42（实测全仓 68 处，已标注 26 / 未标注 42），
// 按 RULES §12.14「只降不升」同步下调基线。
// 2026-10-06 收紧：42 → 41（实测未标注 41，任务自己提示「可下调为 41」）。
// 顺带记一条实测结论：门禁是**逐行**正则匹配，跨行写法 `catch (e: X) {\n}` 扫不到，
// 但全仓实扫**跨行空 catch 为 0 处**（代码风格统一）⇒ 该盲区当前零实际影响，
// 故不为它改门禁实现（改正则本身有误报风险，收益为零）。若将来有人写跨行风格再补 DOT_MATCHES_ALL。
val bareCatchBudget = 41

val checkNoBareCatch by tasks.registering {
    group = "verification"
    description = "全仓空 catch 预算守护：未标注的空 catch 不得超过 $bareCatchBudget 处（只降不升）"
    doLast {
        val emptyCatch = Regex("""catch\s*\([^)]*\)\s*\{\s*\}""")
        val targets = listOf(
            "phone-app" to file("src/main/java"),
            "RokidLink" to rokidLinkProject.projectDir.resolve("src/main/java"),
        )
        var total = 0
        var unannotated = 0
        val violations = mutableListOf<String>()
        for ((module, root) in targets) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { f ->
                    f.useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            if (emptyCatch.containsMatchIn(line)) {
                                total++
                                if (!line.contains("catch-ok:")) {
                                    unannotated++
                                    violations.add("  $module/${f.relativeTo(root).path}:${idx + 1}  ${line.trim()}")
                                }
                            }
                        }
                    }
                }
        }
        if (unannotated > bareCatchBudget) {
            error(
                "未标注的空 catch 增至 $unannotated 处（预算 $bareCatchBudget），新增静默吞异常不允许：\n" +
                    violations.joinToString("\n") +
                    "\n处理方式二选一：\n" +
                    "  a) 在 catch 内落 LogCollector（推荐，见 RULES §12.14）；\n" +
                    "  b) 若确无信息量（关闭句柄 / 消费收尾包 / 读线程正常退出），补注释 `// catch-ok: <原因>`。\n" +
                    "若确需调整存量基线，请同步修改 phone-app/build.gradle.kts 的 bareCatchBudget。",
            )
        }
        logger.lifecycle(
            "checkNoBareCatch: 全仓空 catch $total 处（已标注 ${total - unannotated} / 未标注 $unannotated，预算 $bareCatchBudget）",
        )
        if (unannotated < bareCatchBudget) {
            logger.lifecycle("checkNoBareCatch: 未标注数已低于预算，可把 bareCatchBudget 下调为 $unannotated")
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkNoBareCatch)
}

// ── 工具接线下体体检（M13，2026-10-18 随仓库分发）──
// scripts/check_tool_wiring.py 是「加了工具却调不到」的唯一机械防线：toolNames ↔
// tools() 声明 ↔ execute 分支三表一致 + 派生表聚合自洽 + 字符串资源存在性 +
// 确认策略 fail-open 白名单。它此前只存在于开发机技能目录（~/.workbuddy/skills/），
// 新人克隆仓库后该防线不存在、只能靠人肉记得跑 —— 现随仓库分发并挂 preBuild
// （debug/release 都跑；纯静态扫描、秒级、无外部依赖；CI 的 assembleDebug 自动覆盖）。
// 2026-10-18 实测（新版脚本）：23 provider / 113 工具 / 18 眼镜依赖，全绿。
val checkToolWiring by tasks.registering {
    group = "verification"
    description = "工具接线下体体检：scripts/check_tool_wiring.py 三表一致 + 派生表自洽（加工具后必跑）"
    doLast {
        // ★ 2026-10-18 复审修：不能只用 rootProject.file —— 本模块经工作区级
        //   settings.gradle.kts（:cxrl:RokidLab:phone-app）引入时 rootProject 是工作区根
        //   （d:\rokidapp），不是 RokidLab 仓库根，脚本会解析到不存在的路径，构建必炸。
        //   脚本随 RokidLab 仓库分发（scripts/ 与 phone-app/ 平级），沿 projectDir 上溯定位。
        val script = generateSequence(projectDir) { it.parentFile }
            .map { it.resolve("scripts/check_tool_wiring.py") }
            .firstOrNull { it.exists() }
            ?: rootProject.file("scripts/check_tool_wiring.py")
        if (!script.exists()) {
            error("checkToolWiring: 缺少 ${script.path} —— 脚本应随仓库分发，勿删（见 RULES §工具接缝化）")
        }
        // 解释器候选（2026-10-18 复审修）：local.properties 的 python.exe（机器私有路径，
        // 本机 PATH 上没有 Python）→ PATH 上的 python / python3 / py -3。
        // ⚠️ 一个都找不到时**必须构建失败**——闸门静默跳过 = 没有闸门（M13 的教训
        // 就是防线只活在个人机器上）；同时说清怎么补，别让人对着报错猜。
        val localProps = Properties().apply {
            // 工作区级（rootProject）与 RokidLab 仓库级两处 local.properties 都认：
            // 机器私有配置放哪一层都行
            sequenceOf(rootProject.file("local.properties"), projectDir.parentFile.resolve("local.properties"))
                .filter { it.exists() }
                .forEach { f -> f.inputStream().use { load(it) } }
        }
        val candidates = buildList {
            localProps.getProperty("python.exe")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add(it to listOf(it)) // 绝对路径整体作为一个命令，不做空格切分
            }
            listOf("python", "python3", "py -3").forEach { add(it to it.split(" ")) }
        }
        // 脚本根参数必须是 RokidLab 仓库根（scripts/ 的上一级）——传 rootProject 会把
        // 工作区根传过去，脚本在工作区根找不到 phone-app/src/main，闸门误报或空转。
        val repoRoot = script.parentFile.parentFile.absolutePath
        for ((label, parts) in candidates) {
            // project.exec：子进程 stdout/stderr 实时继承到构建输出——违规清单直接可见，
            // 不用先捕获再回显（capture 反而要处理 ExecOutput/ExecResult 两套 API）。
            val exit = runCatching {
                project.exec {
                    commandLine(parts + listOf(script.absolutePath, repoRoot))
                    isIgnoreExitValue = true
                }.exitValue
            }.getOrNull() ?: continue // 解释器不存在（ExecException）→ 试下一个候选
            if (exit == 0) {
                logger.lifecycle("checkToolWiring: 接线完整 ✅（$label）")
                return@doLast
            }
            // 解释器存在但体检不过 ⇒ 直接失败，不换解释器重试（同样的代码同样的结果）。
            // 违规清单已实时打在各方输出里，这里只说清修法与命令。
            error(
                "checkToolWiring: 工具接线体检未通过（$label exit=$exit，违规清单见上方输出）。\n" +
                    "修法：对齐三表（provider toolNames / tools() 声明 / execute 分支），" +
                    "改完重跑 python scripts/check_tool_wiring.py .（RULES §工具接缝化）",
            )
        }
        error(
            "checkToolWiring: 找不到可用的 Python 解释器（试过：${candidates.joinToString("、") { it.first }}）。\n" +
                "修法：local.properties 加一行 python.exe=<Python 绝对路径>，或把 Python 3 装进 PATH。",
        )
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkToolWiring)
}

// ── 第六道门禁：禁止明文密钥入库（RULES §10，2026-10-18 审计 S1 补齐）──
// 背景：Gitee / 爱发电 / 酷我三个令牌曾以 `const val …TOKEN = "明文"` 提交进仓库，
// 既进了 git 远端历史（RULES §10 明令禁止，规则被违反两次），又随 release APK 派发
// 可反编译提取。三者现已改为 buildConfigField 构建期注入（见上方 buildConfig 块）。
// 本任务防第三次。
//
// 为什么不用「扫已知令牌字面量」：那只能防同三个值复用，**新增一个令牌照样漏过去**。
// 判据改成**形态**——「看起来像凭据的标识符被赋了一个看起来像凭据的字面量」，
// 与具体值无关，新令牌自动纳入。
val secretNamePattern = Regex(
    """\b[A-Za-z_][A-Za-z0-9_]*(TOKEN|SECRET|PASSWORD|PASSWD|APIKEY|API_KEY|ACCESS_KEY)\b""",
    RegexOption.IGNORE_CASE,
)
// ⚠️ 必须**锚在赋值语句上**（`val NAME = "字面量"`），不能扫「行里出现凭据名 + 行里有长串」。
// 2026-10-18 首版就栽在这：`val fileUrl = "…/apps.v1.json?access_token=$GITEE_TOKEN"`
// 是 URL 模板（凭据来自变量），却被判成明文密钥 —— 误报的门禁会被直接关掉，等于没有门禁。
// 所以：名字必须是**被赋值的那个标识符**（不是出现在值里），且值必须是纯字面量
// （含 `$` 说明是模板插值，含 `(` 说明是构造调用，都不是"写死在源码里的凭据"）。
val secretAssignmentPattern = Regex(
    """\b(?:const\s+|private\s+|internal\s+|public\s+)*val\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]+)?=\s*"([^"$(){}]{16,})"""",
)
val secretPlaceholders = setOf(
    "YOUR_GITEE_TOKEN_HERE", "YOUR_TOKEN_HERE", "TODO", "CHANGEME", "PLACEHOLDER",
)
// 明文豁免名单：属主（用户）确认**官方允许明码**、随 APK 派发无属主风险的令牌
// （2026-10-18 拍板，S1 构建期注入回退为源码明文常量）。豁免按**标识符**登记，
// 门禁对其余凭据照常生效——新增真凭据不在名单里照样被拦。
val allowedPlaintextTokens = setOf("KUWO_API_TOKEN", "GITEE_TOKEN", "IFDIAN_TOKEN")
val checkNoHardcodedSecrets by tasks.registering {
    group = "verification"
    description = "禁止明文密钥入库：形如 TOKEN/SECRET/PASSWORD/API_KEY 的常量不得被赋长字面量"
    doLast {
        // 只剥**字符串字面量之外**的行注释（2026-10-18 复审修）：值里含 URL
        // （"https://…"）时 substringBefore("//") 会把赋值行截断成无闭合引号 → 漏检。
        // 块注释行（/* … */ 起始行与 * … 续行）整体跳过：注释里的示例赋值会误报阻断构建。
        fun stripLineComment(line: String): String {
            var inStr = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' -> inStr = !inStr
                    c == '\\' && inStr -> i++
                    !inStr && c == '/' && i + 1 < line.length && line[i + 1] == '/' ->
                        return line.substring(0, i)
                }
                i++
            }
            return line
        }
        val roots = listOf(
            "phone-app" to file("src/main/java"),
            "phone-app-assets" to file("src/main/assets"),
            "RokidLink" to rokidLinkProject.projectDir.resolve("src/main/java"),
        )
        val violations = mutableListOf<String>()
        var scanned = 0
        for ((module, root) in roots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { f ->
                    scanned++
                    f.useLines { lines ->
                        var inBlockComment = false
                        lines.forEachIndexed { idx, line ->
                            val trimmed = line.trim()
                            if (inBlockComment) {
                                if (trimmed.contains("*/")) inBlockComment = false
                                return@forEachIndexed
                            }
                            if (trimmed.startsWith("/*")) {
                                if (!trimmed.contains("*/")) inBlockComment = true
                                return@forEachIndexed
                            }
                            val code = stripLineComment(line)
                            val m = secretAssignmentPattern.find(code) ?: return@forEachIndexed
                            val name = m.groupValues[1]
                            val value = m.groupValues[2]
                            if (!secretNamePattern.containsMatchIn(name)) return@forEachIndexed
                            if (value in secretPlaceholders) return@forEachIndexed
                            if (name in allowedPlaintextTokens) return@forEachIndexed
                            violations.add(
                                "  $module/${f.relativeTo(root).path}:${idx + 1}  ${line.trim()}",
                            )
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            error(
                buildString {
                    append("源码里出现疑似明文密钥（$scanned 个文件已扫）：\n")
                    append(violations.joinToString("\n"))
                    append("\n处理方式：\n")
                    append("  a) 构建期注入：在 phone-app/build.gradle.kts 加 buildConfigField，")
                    append("值从不入库的 gradle.properties（或环境变量 ORG_GRADLE_PROJECT_XXX_TOKEN）取，")
                    append("模板见 gradle.properties.example；\n")
                    append("  b) 确属占位符：把值加进本任务的 secretPlaceholders 白名单并注明理由；\n")
                    append("  c) 确非凭据（如 flag 名带 TOKEN 的普通字符串）：改个不触发的名字；\n")
                    append("  d) 属主确认官方允许明码的令牌：加进 allowedPlaintextTokens 并注明属主拍板日期。")
                    append("另：已泄露的旧令牌必须去属主后台作废轮换，改注入方式不改变泄露事实。")
                },
            )
        }
        logger.lifecycle("checkNoHardcodedSecrets: $scanned 个源文件，0 处明文密钥")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkNoHardcodedSecrets)
}

// 在合并 assets 前先同步 RokidLink APK
tasks.matching { it.name.startsWith("mergeDebug") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(buildRokidLinkDebug)
}
tasks.matching { it.name.startsWith("mergeRelease") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(buildRokidLinkRelease)
}

// ── 依赖 ──

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // OkHttp：连接池复用 + HTTP/2，替代 HttpURLConnection 每请求重握手
    implementation(libs.okhttp)
    
    // CXR-L SDK 1.1.2 (Maven)
    // 1.1.1 起 client-l 改为瘦 aar：CXRServiceBridge/Caps/CXRSocketProtocol/RLog 拆到独立
    // artifact com.rokid.cxr:cxr-service-bridge，由 client-l 传递依赖引入（so 仍在 bridge 内）。
    implementation("com.rokid.cxr:client-l:1.1.2")
    // ⚠️ 显式锁定 bridge 版本：1.1.2 的 POM 依赖的是「固定时间戳 SNAPSHOT」
    //    cxr-service-bridge:1.0-20260715.121510-107，而该 artifact 的 release 1.0 与它内容并不相同
    //    （1076548 vs 1126241 字节）。此处显式声明同一版本，保证构建可复现；
    //    若将来 Nexus 清理掉该 SNAPSHOT，需改用 release 1.0 并真机回归。
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260715.121510-107")
    
    // client-l SDK 内部依赖 Gson，需要显式引入
    implementation("com.google.code.gson:gson:2.10.1")

    // jtokkit：OpenAI tiktoken 的纯 Java 实现（cl100k_base），上下文预算按真实 token 计量
    // （2026-10-02 起替代「字符数 ≈ token」的经验换算，见 ai/compaction/TokenMeter）
    implementation("com.knuddels:jtokkit:1.1.0")

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material:material")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 本地 OCR（PP-OCRv4，ONNX Runtime 推理，无需联网/无 GMS 依赖）。
    // 使用剥除 assets 模型的「薄 AAR」（官方 AAR 内置 3 个 onnx 约 15.4MB，会撑大 APK）：
    // 模型首次使用时由 LocalOcr 从 Gitee Release 按需下载（见 LocalOcr.MODELS）。
    // 薄 AAR 仅去掉 assets/*.onnx，classes.jar 与官方 1.0.0 完全一致。
    implementation(files("libs/rapidocr4j-android-1.0.0-thin.aar"))
    // 覆盖旧版 native 依赖为 16KB 对齐版本（兼容 Android 16 页面大小）
    // opencv 4.9.0 / onnxruntime 1.18.0 的 so 为 4KB 对齐，16KB 设备上 dlopen 会崩溃
    implementation("org.opencv:opencv:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // ── JVM 单元测试（纯 Kotlin 协议/状态机/裁剪逻辑，无需真机）──
    testImplementation("junit:junit:4.13.2")
    // 真实 org.json 实现：android.jar 中为抛 "not mocked" 的桩，SSE 解析单测需要真实解析
    testImplementation("org.json:json:20231013")
}
