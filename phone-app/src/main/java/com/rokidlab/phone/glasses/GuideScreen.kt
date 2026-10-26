package com.rokidlab.phone.glasses

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.permission.AppPermission
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import com.rokidlab.phone.R
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 引导界面 - 指导用户完成前置条件
 *
 * 2026-10-24 redesign：原「配置眼镜 WiFi」步已退役出启动链路（WiFi 配置改为设置页常驻卡 +
 * 投屏/文件管理页按需入口，见 GlassesWifiConfigScreen）；INSTALL_LINK 仅在眼镜端确认
 * **未安装**时出现（PrerequisitesState.rokidLinkInstalled == false），旧版提醒走主页横幅。
 */
@Composable
internal fun GuideScreen(
    currentStep: GuideStep,
    selectedHostApp: RokidHostApp?,
    authorized: Boolean,
    hostAppInstalled: Boolean,
    rokidLinkInstalled: Boolean?,
    onSelectHostApp: (RokidHostApp) -> Unit,
    onSelectMirrorSource: () -> Unit,
    onAuthorize: () -> Unit,
    onInstallLink: ((onComplete: (Boolean) -> Unit) -> Unit)? = null,
    onPermissionsDone: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrewBg)
            // 权限步有 7 行 + 两个按钮，小屏会超出视口；滚动兜底（内容装得下时行为与原来一致）
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = ctx.getString(R.string.guide_welcome),
            color = BrewCoral,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "by DLOVER",
            color = BrewMuted,
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(48.dp))

        GuideProgressIndicator(currentStep = currentStep)

        Spacer(modifier = Modifier.height(48.dp))

        when (currentStep) {
            GuideStep.SELECT_HOST_APP -> SelectHostAppStep(
                selectedHostApp = selectedHostApp,
                onSelectHostApp = onSelectHostApp,
                ctx = ctx,
            )
            GuideStep.SELECT_MIRROR_SOURCE -> SelectMirrorSourceStep(
                onSelectMirrorSource = onSelectMirrorSource,
                ctx = ctx,
            )
            GuideStep.AUTHORIZE -> AuthorizeStep(
                authorized = authorized,
                hostAppInstalled = hostAppInstalled,
                selectedHostApp = selectedHostApp,
                onAuthorize = onAuthorize,
                ctx = ctx,
            )
            GuideStep.PERMISSIONS -> PermissionsStep(
                onDone = onPermissionsDone,
                ctx = ctx,
            )
            GuideStep.INSTALL_LINK -> InstallLinkStep(
                onInstallLink = onInstallLink,
                onSkip = onSkip,
                installed = rokidLinkInstalled,
                ctx = ctx,
            )
            GuideStep.READY -> { }
        }

        Spacer(modifier = Modifier.height(32.dp))

        StepInstructions(currentStep = currentStep, ctx = ctx)
    }
}

@Composable
private fun GuideProgressIndicator(currentStep: GuideStep) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        GuideStep.values().forEachIndexed { index, step ->
            val isCompleted = index < currentStep.ordinal
            val isCurrent = index == currentStep.ordinal
            val dotColor = when {
                isCompleted -> BrewSuccess
                isCurrent -> when (step) {
                    GuideStep.SELECT_HOST_APP -> BrewCoral
                    GuideStep.SELECT_MIRROR_SOURCE -> BrewCyan
                    GuideStep.AUTHORIZE -> BrewMagenta
                    GuideStep.PERMISSIONS -> BrewWarning
                    GuideStep.INSTALL_LINK -> BrewPurple
                    GuideStep.READY -> BrewSuccess
                }
                else -> BrewPanel
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(dotColor, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun SelectHostAppStep(
    selectedHostApp: RokidHostApp?,
    onSelectHostApp: (RokidHostApp) -> Unit,
    ctx: android.content.Context,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ctx.getString(R.string.guide_step1_title_local),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step1_desc),
            color = BrewMuted,
            fontSize = 14.sp,
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        RokidHostApp.values().forEach { hostApp ->
            val isSelected = selectedHostApp == hostApp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        color = if (isSelected) BrewCoral else BrewPanel,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) BrewCoral else BrewBorder,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelectHostApp(hostApp) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = hostApp.displayName,
                    color = if (isSelected) BrewBg else BrewText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SelectMirrorSourceStep(
    onSelectMirrorSource: () -> Unit,
    ctx: android.content.Context,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ctx.getString(R.string.guide_step2_title_local),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step2_desc),
            color = BrewMuted,
            fontSize = 14.sp,
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(BrewShapeStandard)
                .background(BrewCoral, shape = BrewShapeStandard)
                .clickable { onSelectMirrorSource() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ctx.getString(R.string.select_mirror_source),
                color = BrewBg,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AuthorizeStep(
    authorized: Boolean,
    hostAppInstalled: Boolean,
    selectedHostApp: RokidHostApp?,
    onAuthorize: () -> Unit,
    ctx: android.content.Context,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ctx.getString(R.string.guide_step3_title_local),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step3_desc),
            color = BrewMuted,
            fontSize = 14.sp,
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (!hostAppInstalled && selectedHostApp != null) {
            // Host 应用未安装 — 显示警告引导用户先安装
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(BrewWarning.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                    .border(1.dp, BrewWarning, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ctx.getString(R.string.host_app_not_installed_warning, selectedHostApp.displayName),
                    color = BrewWarning,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else if (authorized) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(BrewSuccess, shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ctx.getString(R.string.authorized) + " ✓",
                    color = BrewBg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(BrewShapeStandard)
                    .background(BrewCoral, shape = BrewShapeStandard)
                    .clickable { onAuthorize() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ctx.getString(R.string.authorize_btn),
                    color = BrewBg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step3_desc),
            color = BrewMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 权限步（第四步）：把手机侧权限一次性开齐，**装在眼镜端应用之前**。
 *
 * 为什么要有这一步：装机依赖蓝牙、装完的语音功能又依赖通话/联系人/日历/定位/通知/悬浮窗，
 * 而此前这些权限散落在启动期自动弹窗与各功能点各自的「请去设置里开启」里 ——
 * 用户装到一半才发现某功能不可用，也说不清到底缺什么。
 *
 * 唯一事实来源是 [AppPermission] 总表：**新增权限只要登记进那张表，这里自动多出一行**，
 * 不需要改本文件（规则见 project_rules.md「权限登记」）。
 * 表外的两个系统项（电池优化 / 自启动）跟在权限项后面一起过 —— 它们不是运行时权限，
 * 但同样是"装完才发现后台被系统杀掉"的隐性缺项，原本在启动后 2 秒弹窗，现统一收敛到这里。
 */
@Composable
private fun PermissionsStep(
    onDone: (() -> Unit)?,
    ctx: android.content.Context,
) {
    val items = remember { AppPermission.onboardingPermissions() }
    var refreshTick by remember { mutableStateOf(0) }

    // 从系统授权框回来有 launcher 回调，但从「悬浮窗设置页」回来没有任何回调可挂，
    // 本页也不是宿主 Activity 视角、拿不到 onResume —— 用 1s 轻量轮询兜住这两种返回路径；
    // 离开本步即随 LaunchedEffect 取消（7 次 checkSelfPermission 的代价可忽略）。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            refreshTick++
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshTick++ }

    val granted = remember(refreshTick) { items.associateWith { AppPermission.isGranted(ctx, it) } }
    val missing = granted.count { !it.value }

    // 系统设置项（不是运行时权限，所以不进 [AppPermission] 总表）—— 放在权限项之后一起过一遍。
    // 只有电池优化能查状态（AOSP `isIgnoringBatteryOptimizations`），它计入「全绿」判定；
    // 自启动 / vivo 后台限制各 ROM 都没有公开查询接口，标「需手动开启」且**不**参与判定 ——
    // 否则用户会永远卡在"还差 1 项"点不动下一步。
    val batteryOk = remember(refreshTick) { ManufacturerUtils.isIgnoringBatteryOptimizations(ctx) }
    val chineseRom = remember { ManufacturerUtils.isChineseRom() }
    val vivoLimit = chineseRom && ManufacturerUtils.hasVivoBackgroundHardLimit()
    val totalMissing = missing + if (batteryOk) 0 else 1

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = ctx.getString(R.string.guide_permissions_title),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(20.dp))

        items.forEach { permission ->
            val ok = granted[permission] == true
            PermissionRowItem(
                label = ctx.getString(permission.labelRes),
                ok = ok,
                statusText = ctx.getString(
                    when {
                        ok -> R.string.guide_permissions_granted
                        permission.runtimeRequestable -> R.string.guide_permissions_tap
                        else -> R.string.guide_permissions_go_settings
                    },
                ),
                onClick = if (ok) {
                    null
                } else {
                    {
                        if (permission.runtimeRequestable) {
                            runCatching {
                                launcher.launch(AppPermission.runtimeNames(listOf(permission)))
                            }.onFailure {
                                // 少数 ROM 的权限管理器会在 launch 阶段抛异常，重算状态即可，不中断本步
                                refreshTick++
                            }
                        } else {
                            // 悬浮窗是 AppOps 而非运行时权限：requestPermissions 对它不弹窗，
                            // 只能跳系统设置页（这也是后台拉起界面的唯一豁免，见 AppPermission）
                            ManufacturerUtils.openOverlaySettings(ctx)
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        PermissionRowItem(
            label = ctx.getString(R.string.battery_optimization_title),
            ok = batteryOk,
            statusText = ctx.getString(
                if (batteryOk) R.string.guide_permissions_granted else R.string.guide_permissions_go_settings,
            ),
            onClick = if (batteryOk) null else { { ManufacturerUtils.requestIgnoreBatteryOptimizations(ctx) } },
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (chineseRom) {
            PermissionRowItem(
                label = ctx.getString(R.string.auto_start_title),
                ok = false,
                statusText = ctx.getString(R.string.guide_permissions_manual),
                onClick = { ManufacturerUtils.openAutoStartSettings(ctx) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (vivoLimit) {
            PermissionRowItem(
                label = ctx.getString(R.string.vivo_limit_title),
                ok = false,
                statusText = ctx.getString(R.string.guide_permissions_manual),
                onClick = { ManufacturerUtils.openPowerSavingSettings(ctx) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 主按钮仅在「全绿」时可点：缺权限不影响装机、但会让对应功能不可用，
        // 半吊子状态下直接放行等于把问题推到用户第一次用某功能的时候
        val allDone = totalMissing == 0
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(BrewShapeStandard)
                .background(if (allDone) BrewSuccess else BrewPanel, BrewShapeStandard)
                .then(if (allDone && onDone != null) Modifier.clickable { onDone() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (allDone) {
                    ctx.getString(R.string.guide_permissions_next)
                } else {
                    ctx.getString(R.string.guide_permissions_pending, totalMissing)
                },
                color = if (allDone) BrewBg else BrewMuted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 跳过：与「安装 / WiFi」两步一致，允许先过（权限可随时在设置里补）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(BrewShapeStandard)
                .background(BrewPanel, BrewShapeStandard)
                .then(if (onDone != null) Modifier.clickable { onDone() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ctx.getString(R.string.guide_skip_btn),
                color = BrewText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 权限步里的单项：权限项与电池优化/自启动等系统项共用同一套样式。
 *
 * [onClick] 为 null 表示不可点（已开，或不需处理）。
 * [ok] 只对**可检测**的项有意义；自启动这类无法查询状态的项恒传 false，
 * 由 [statusText] 说明「需手动开启」，且不计入「全绿」判定。
 */
@Composable
private fun PermissionRowItem(
    label: String,
    ok: Boolean,
    statusText: String,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(BrewShapeStandard)
            .background(if (ok) BrewPanel else BrewBg, BrewShapeStandard)
            .border(1.dp, if (ok) BrewSuccess else BrewBorder, BrewShapeStandard)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (ok) "✓" else "○",
            color = if (ok) BrewSuccess else BrewMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = BrewText,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = statusText,
            color = if (ok) BrewSuccess else BrewWarning,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun InstallLinkStep(
    onInstallLink: ((onComplete: (Boolean) -> Unit) -> Unit)?,
    onSkip: (() -> Unit)?,
    installed: Boolean?,
    ctx: android.content.Context,
) {
    var isInstalling by remember { mutableStateOf(false) }
    var installFailed by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = ctx.getString(R.string.guide_install_link_title),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = ctx.getString(R.string.guide_install_link_desc),
            color = BrewMuted,
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(24.dp))

        when {
            installFailed -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .background(BrewRed, shape = RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ctx.getString(R.string.guide_install_link_fail),
                        color = BrewBg, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    ctx.getString(R.string.guide_install_link_fail_hint),
                    color = BrewRed, fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
                // 重试按钮
                Box(
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .clip(BrewShapeStandard)
                        .background(BrewPurple, BrewShapeStandard)
                        .then(
                            if (onInstallLink != null) {
                                Modifier.clickable {
                                    installFailed = false
                                    isInstalling = true
                                    onInstallLink { success ->
                                        isInstalling = false
                                        if (!success) {
                                            installFailed = true
                                        }
                                    }
                                }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ctx.getString(R.string.guide_retry_btn),
                        color = BrewBg, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // 跳过按钮
                Box(
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                        .clip(BrewShapeStandard)
                        .background(BrewPanel, BrewShapeStandard)
                        .then(
                            if (onSkip != null) {
                                Modifier.clickable { onSkip() }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ctx.getString(R.string.guide_skip_btn),
                        color = BrewText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
            else -> {
                if (installed == true) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                            .background(BrewSuccess, shape = RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ctx.getString(R.string.guide_install_link_success) + " ✓",
                            color = BrewBg, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    Text(
                        ctx.getString(R.string.guide_install_link_hint),
                        color = BrewWarning, fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Box(
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .clip(BrewShapeStandard)
                        .background(if (isInstalling) BrewPanel else BrewPurple, BrewShapeStandard)
                        .then(
                            if (!isInstalling && onInstallLink != null) {
                                Modifier.clickable {
                                    isInstalling = true
                                    installFailed = false
                                    onInstallLink { success ->
                                        isInstalling = false
                                        if (!success) {
                                            installFailed = true
                                        }
                                    }
                                }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isInstalling) ctx.getString(R.string.guide_install_link_progress)
                        else if (installed == true) ctx.getString(R.string.guide_reinstall_link_btn)
                        else ctx.getString(R.string.guide_install_link_btn),
                        color = if (isInstalling) BrewMuted else BrewBg,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // 跳过按钮
                Box(
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                        .clip(BrewShapeStandard)
                        .background(BrewPanel, BrewShapeStandard)
                        .then(
                            if (onSkip != null) {
                                Modifier.clickable { onSkip() }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ctx.getString(R.string.guide_skip_btn),
                        color = BrewText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepInstructions(currentStep: GuideStep, ctx: android.content.Context) {
    val instructions = when (currentStep) {
        GuideStep.SELECT_HOST_APP -> ctx.getString(R.string.guide_step1_desc)
        GuideStep.SELECT_MIRROR_SOURCE -> ctx.getString(R.string.guide_step2_desc)
        GuideStep.AUTHORIZE -> ctx.getString(R.string.guide_step3_desc)
        GuideStep.PERMISSIONS -> ctx.getString(R.string.guide_permissions_desc)
        GuideStep.INSTALL_LINK -> ctx.getString(R.string.guide_install_link_desc)
        GuideStep.READY -> ""
    }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = instructions,
            color = BrewMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}
