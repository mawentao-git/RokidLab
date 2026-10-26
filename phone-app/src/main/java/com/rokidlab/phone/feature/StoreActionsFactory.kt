package com.rokidlab.phone.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.app.MainActivity
import com.rokidlab.phone.model.BrewIndex
import com.rokidlab.phone.model.GuideStep
import com.rokidlab.phone.store.StoreActions
import com.rokidlab.phone.util.LocalizationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 商店页动作装配（Phase 5 三轮：从 MainActivity 逐字迁出）。
 *
 * 以扩展函数形式保留对宿主成员的裸引用（接收者即 MainActivity），
 * 与迁移前逻辑逐字一致；MainActivity 仅保留 `actions = buildStoreActions()`。
 * 需要宿主成员可见性为 internal（见 MainActivity 中同批翻转）。
 */
internal fun MainActivity.buildStoreActions(): StoreActions = StoreActions(
        onRefresh = { refreshStoreIndex(manual = true) },
        onHostAppSelected = ::selectRokidHostApp,
        onGoToGuideStep1 = { goToGuideStep1() },
        // 授权按钮必须直接调 requestAuthorization()，不可用 runWithPrerequisites 包装：
        // 包装会导致授权成功后死循环拉起授权页（主页已按此修复，此处为引导入口同规收敛）
        onAuthorize = { cxrL.requestAuthorization() },
        onInstall = { app, target ->
            if (target == "glasses") {
                runWithPrerequisites { installArtifact(app, target) }
            } else {
                installArtifact(app, target)
            }
        },
        onCheckGlassesInstall = ::checkGlassesInstallStateIfNeeded,
        onUninstall = { app, target ->
            if (target == "glasses") {
                runWithPrerequisites { uninstallArtifact(app, target) }
            } else {
                uninstallArtifact(app, target)
            }
        },
        onSelfUpdate = { performSelfUpdate() },
        onSwitchMirror = { showMirrorDialog = true },
        onInstallApk = { apkPickerLauncher.launch("application/vnd.android.package-archive") },
        onLaunch = { app, target -> launchApp(app, target) },
        onSelectMirrorSource = { showMirrorDialog = true },
        onMirrorSelected = { index ->
            switchMirror(index)
            showMirrorDialog = false
        },
        onDismissMirrorDialog = { showMirrorDialog = false },
        onScreenMirrorIpChange = { (application as LabApplication).screenMirrorIp = it },
        onScreenMirrorConnect = { startScreenMirror() },
        onScreenMirrorStart = { startScreenMirror() },
        onScreenMirrorStop = { stopRokidLinkOnGlasses() },
        onScreenMirrorInstallRokidLink = { reinstallRokidLinkOnGlasses() },
        onScreenMirrorOpenRokidLink = { openRokidLinkOnGlasses() },
        onScreenMirrorRetry = { },
        onScreenMirrorBack = { },
        onPhoneMirrorIpChange = { (application as LabApplication).phoneMirrorIp = it },
        onPhoneMirrorPortChange = { (application as LabApplication).setPhoneMirrorPort(it) },
        onPhoneMirrorConnect = { startPhoneMirror() },
        onPhoneMirrorStart = { 
            if (phoneMirrorState.isMirroring) stopPhoneMirror() else startPhoneMirror()
        },
        onPhoneMirrorStop = { stopRokidLinkOnGlasses() },
        onPhoneMirrorInstallRokidLink = { reinstallRokidLinkOnGlasses() },
        onPhoneMirrorOpenRokidLink = { openRokidLinkOnGlasses() },
        onPhoneMirrorRetry = { },
        onPhoneMirrorBack = { },
        onFileManagerIpChange = { (application as LabApplication).fileManagerIp = it },
        onFileManagerConnect = { startFileManager() },
        onFileManagerDisconnect = { },
        onFileManagerStop = { stopRokidLinkOnGlasses() },
        onFileManagerInstallRokidLink = { reinstallRokidLinkOnGlasses() },
        onFileManagerOpenRokidLink = { openRokidLinkOnGlasses() },
        onFileManagerRetry = { },
        onFileManagerBack = { },
        onFileManagerNavigateTo = { },
        onFileManagerRefresh = { },
        onFileManagerUploadFile = { },
        onFileManagerDownloadFile = { },
        onFileManagerDeleteFile = { },
        onFileManagerCreateFolder = { },
        onFileManagerRenameFile = { _, _ -> },
        onCancelDownload = { key -> cancelDownload(key) },
        onExitApp = { finishAndRemoveTask() },
        onSettingsReinstallRokidLink = { reinstallRokidLinkOnGlasses() },
        // 排障入口：主动拉眼镜端状态页（WiFi IP / ADB）。常规流程不弹。
        onSettingsOpenGlassesStatus = { openRokidLinkOnGlasses() },
        onExportLog = { showExportLogDialog() },
        onExportCompatDiagnostics = { exportCompatDiagnostics() },
        onToggleKeepAlive = { toggleKeepAlive() },
        onLaunchGlassAppViaSdk = { pkg, activity ->
            // 放 IO：launchApp 首段 session.cleanup() 为阻塞 IO，主线程同步调用会 ANR
            lifecycleScope.launch(Dispatchers.IO) {
                cxrL.launchApp(
                    packageName = pkg,
                    activityClass = activity,
                    onLaunchResult = { success ->
                        runOnUiThread {
                            val msg = if (success) "$pkg 启动成功" else "$pkg 启动失败"
                            log(msg)
                        }
                    }
                )
            }
        },
        onSendKeyButtonConfig = { shortPkg, shortActivity, longPkg, longActivity, onDone ->
            // 放 IO：sendKeyButtonConfig 首段 session.cleanup() 为阻塞 IO，主线程同步调用会 ANR
            lifecycleScope.launch(Dispatchers.IO) {
                cxrL.sendKeyButtonConfig(
                    shortPkg = shortPkg,
                    shortActivity = shortActivity,
                    longPkg = longPkg,
                    longActivity = longActivity,
                    onResult = { success ->
                        runOnUiThread {
                            val msg = if (success) "按键配置已发送到眼镜" else "按键配置发送失败"
                            log(msg)
                            onDone(success)
                        }
                    },
                )
            }
        },
        onSwitchLanguage = { code ->
            com.rokidlab.phone.util.LocalizationManager.setLocale(this, code)
            // Recreate activity to apply language
            recreate()
        },
        // 眼镜端 RokidLink 更新（redesign 2026-10-24）：主页横幅 / 设置页警示态共用。
        // 与设置页「重装」的差别只在于入口语义（这里是"版本落后提醒"），安装链路同款。
        onUpdateRokidLink = { reinstallRokidLinkOnGlasses() },
        // 「眼镜 WiFi 配置」浮层（设置页卡 + 乐奇工具页按需入口共用）
        onOpenGlassesWifiConfig = { showGlassesWifiConfig = true },
        onCloseGlassesWifiConfig = { showGlassesWifiConfig = false },
        onSendWifiConfig = { ssid, password, onDone ->
            // 放 IO：sendWifiConfig 首段是 session.cleanup()（拆 ADB 会话 / 断 CXR 链路，阻塞 IO），
            // 而本回调由「眼镜 WiFi 配置」页的「连接」按钮在主线程同步调用 —— 眼镜离线时主线程
            // 会卡在建链上超过 5s → 页面无响应（redesign 后该页从启动引导迁出为按需入口）
            lifecycleScope.launch(Dispatchers.IO) {
                cxrL.sendWifiConfig(ssid, password) { success, errorMsg ->
                    runOnUiThread {
                        if (success) {
                            markGlassesWifiConfigured(ssid)
                            // 与旧引导末步同规：配网成功后确保眼镜端 RokidLink 在跑（配置即时生效）
                            autoStartRokidLink()
                        }
                        onDone(success, errorMsg)
                    }
                }
            }
        },
        onInstallLink = { onDone ->
            // 与其他「装到眼镜」的入口对齐（商店 onInstall / installRokidLinkToGlasses /
            // reinstallRokidLinkOnGlasses 都走 runWithPrerequisites）：引导页这条此前是唯一
            // 绕过权限/蓝牙前置的安装入口，缺蓝牙运行时权限时不会拉起请求框而直接硬跑。
            runWithPrerequisites {
                installRokidLinkForGuide { success ->
                    if (success) {
                        markRokidLinkInstalled()
                    }
                    onDone(success)
                }
            }
        },
        onSkipGuideStep = {
            when (prerequisitesState.currentGuideStep) {
                GuideStep.PERMISSIONS -> {
                    // 与「安装」步同规：允许先过，缺的权限可随时在设置里补
                    prerequisitesState = prerequisitesState.copy(permissionsReady = true)
                }
                GuideStep.INSTALL_LINK -> {
                    markRokidLinkInstalled()
                }
                else -> {
                    // 其他步骤不支持跳过（CONFIGURE_WIFI 已于 2026-10-24 redesign 退役出启动链路）
                }
            }
        },
)
