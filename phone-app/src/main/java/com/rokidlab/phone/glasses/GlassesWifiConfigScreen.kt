package com.rokidlab.phone.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.design.*
import com.rokidlab.phone.permission.AppPermission
import com.rokidlab.phone.util.SecretStore
import kotlinx.coroutines.delay

/**
 * 眼镜 WiFi 配置页（2026-10-24 启动引导 redesign 从引导步抽出，供三处复用）：
 *  - 设置页「眼镜端服务」区常驻卡；
 *  - 乐奇工具页（投屏 / 文件管理）按需入口；
 *  - （保留）任何需要「给眼镜换网」的场景。
 *
 * 内容与已退役的引导步 `ConfigureWifiStep` 同源：WiFi 扫描列表 + 手动输入 + 密码持久化
 * （SecretStore 按 SSID 存，选中同网络自动回填）+ 经 CXR 自定义指令下发。
 *
 * 与原引导步的差异：出口从页内「跳过」按钮改为顶栏关闭 + 系统返回（[BackHandler]）——
 * 配置页没有「跳过」语义，只有「关掉」。
 */
@Composable
internal fun GlassesWifiConfigScreen(
    onSendWifiConfig: ((String, String, (Boolean, String?) -> Unit) -> Unit)?,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrewBg)
            .statusBarsPadding(),
    ) {
        // 顶栏：标题 + 关闭
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.glasses_wifi_config_title),
                color = BrewText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(BrewPanel)
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.close),
                    color = BrewText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        WifiConfigContent(
            onSendWifiConfig = onSendWifiConfig,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * WiFi 扫描超时兜底：超过该时长仍未收到扫描结果广播，就强制退出「正在扫描」态。
 * 手机「定位服务」总开关关闭、或扫描被系统节流时，系统不会发结果广播。
 */
private const val WIFI_SCAN_TIMEOUT_MS = 8_000L

@Suppress("DEPRECATION")
@Composable
private fun WifiConfigContent(
    onSendWifiConfig: ((String, String, (Boolean, String?) -> Unit) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val wifiManager = remember {
        ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager
    }

    // 权限状态 —— 走 [AppPermission] 总表，不再自己写 checkSelfPermission。
    // WiFi 扫描依赖定位权限，且 Android 12+ 必须与 COARSE 一起申请（只求 FINE 会被静默拒绝）。
    var hasLocationPermission by remember {
        mutableStateOf(AppPermission.isGranted(ctx, AppPermission.LOCATION))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasLocationPermission = AppPermission.isGranted(ctx, AppPermission.LOCATION) }

    fun requestLocationPermission() {
        permissionLauncher.launch(AppPermission.runtimeNames(listOf(AppPermission.LOCATION)))
    }

    // WiFi 扫描状态
    var selectedSsid by remember { mutableStateOf<String?>(null) }
    var manualSsid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(false) }

    // 已连接过的 WiFi 密码按 SSID 加密持久化（Keystore AES-256-GCM，复用 SecretStore），
    // 下次选中同一网络自动回填，不用每次重输。
    val wifiPrefs = remember {
        ctx.applicationContext.getSharedPreferences("guide_wifi_prefs", android.content.Context.MODE_PRIVATE)
    }
    fun savedWifiPassword(ssid: String): String? =
        ssid.takeIf { it.isNotBlank() }?.let { SecretStore.get(wifiPrefs, "wifi_pwd_$it") }
    fun saveWifiPassword(ssid: String, pwd: String) {
        // 开放网络无密码，不存空值（否则会误显「已自动填入保存的密码」）
        if (ssid.isNotBlank() && pwd.isNotEmpty()) SecretStore.put(wifiPrefs, "wifi_pwd_$ssid", pwd)
    }
    // 当前回填的密码是否来自存储（用于「已自动填入保存的密码」提示）
    var passwordPreFilled by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var scanTimedOut by remember { mutableStateOf(false) }
    // 自增即重新扫描；Wi-Fi 打开 / 拿到定位权限时也会自动重扫
    var scanAttempt by remember { mutableStateOf(0) }
    var wifiEnabled by remember { mutableStateOf(wifiManager?.isWifiEnabled == true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 从扫描列表选中网络：有保存的密码就回填，没有则清空（防止沿用上一个网络的密码）
    LaunchedEffect(selectedSsid) {
        val ssid = selectedSsid
        if (!ssid.isNullOrEmpty()) {
            val saved = savedWifiPassword(ssid)
            password = saved.orEmpty()
            passwordPreFilled = saved != null
        } else {
            passwordPreFilled = false
        }
    }
    // 手动输入 SSID：输入完整 SSID 恰好匹配已保存网络时回填；未匹配不动用户已输入的密码
    LaunchedEffect(manualSsid) {
        if (selectedSsid == "" && manualSsid.isNotBlank()) {
            val saved = savedWifiPassword(manualSsid)
            if (saved != null) {
                password = saved
                passwordPreFilled = true
            }
        }
    }

    // Wi-Fi 开关状态：**必须单独监听**，不能顺带在扫描广播里读。
    // Wi-Fi 关闭时系统根本不会发扫描结果广播，于是 `wifiEnabled` 永远停在 false ——
    // 用户点「打开系统设置」去开 Wi-Fi、回到本页，界面依旧显示「WiFi 已关闭」，
    // 看到的就是"卡住"。
    DisposableEffect(Unit) {
        wifiEnabled = wifiManager?.isWifiEnabled == true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                wifiEnabled = wifiManager?.isWifiEnabled == true
            }
        }
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
        onDispose { ctx.unregisterReceiver(receiver) }
    }

    // WiFi 扫描：key 带上 wifiEnabled / scanAttempt，Wi-Fi 打开或用户点「重新扫描」都会重来一次
    DisposableEffect(hasLocationPermission, wifiEnabled, scanAttempt) {
        if (!hasLocationPermission || wifiManager == null || !wifiEnabled) {
            isScanning = false
            return@DisposableEffect onDispose {}
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                isScanning = false
                scanTimedOut = false
                // scanResults 在定位权限被撤销等边界会抛 SecurityException（系统边界），
                // 不能让广播回调炸出去，否则 isScanning 永远停在 true
                val raw = runCatching { wifiManager.scanResults }.getOrDefault(emptyList())
                // 去重：同 SSID 保留信号最强的
                scanResults = raw
                    .filter { it.SSID.isNotBlank() }
                    .groupBy { it.SSID }
                    .mapNotNull { (_, results) -> results.maxByOrNull { it.level } }
                    .sortedByDescending { it.level }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), android.content.Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        }
        isScanning = true
        scanTimedOut = false
        // 不再"收到广播就立刻 startScan()"：那样形成自续扫描，很快撞上系统扫描节流
        // （节流后既没有广播也不会产生结果，isScanning 永久停在 true），改为用户手动重扫。
        runCatching { wifiManager.startScan() }

        onDispose { ctx.unregisterReceiver(receiver) }
    }

    // 扫描超时兜底：手机「定位服务」总开关关闭、或扫描被系统节流时，系统不会发扫描结果广播，
    // isScanning 会永久停在 true → 页面一直显示「正在扫描…」。到点强制退出扫描态。
    LaunchedEffect(hasLocationPermission, wifiEnabled, scanAttempt) {
        if (!hasLocationPermission || !wifiEnabled) return@LaunchedEffect
        delay(WIFI_SCAN_TIMEOUT_MS)
        if (isScanning) {
            isScanning = false
            scanTimedOut = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ctx.getString(R.string.glasses_wifi_config_desc),
            color = BrewMuted,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (configured) {
            // === 发送成功 ===
            Box(
                modifier = Modifier.fillMaxWidth().height(56.dp)
                    .background(BrewSuccess, shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ctx.getString(R.string.wifi_configured_success) + " ✓",
                    color = BrewBg, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
            }
        } else if (selectedSsid == null) {
            // === WiFi 网络列表 ===
            if (!wifiEnabled) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            ctx.getString(R.string.wifi_disabled_prompt),
                            color = BrewMuted, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.clip(BrewShapeStandard)
                                .background(BrewInfo, BrewShapeStandard)
                                .clickable {
                                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(
                                ctx.getString(R.string.wifi_open_settings),
                                color = BrewBg, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            } else if (!hasLocationPermission) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            ctx.getString(R.string.wifi_location_permission_hint),
                            color = BrewMuted, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.clip(BrewShapeStandard)
                                .background(BrewInfo, BrewShapeStandard)
                                .clickable {
                                    requestLocationPermission()
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(
                                ctx.getString(R.string.wifi_grant_permission),
                                color = BrewBg, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            } else {
                if (isScanning && scanResults.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ctx.getString(R.string.wifi_scanning),
                            color = BrewMuted, fontSize = 14.sp,
                        )
                    }
                } else if (scanResults.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ctx.getString(
                                if (scanTimedOut) R.string.wifi_scan_timeout else R.string.wifi_no_networks
                            ),
                            color = BrewMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    // 网络列表：**必须用普通 Column**。本页根容器是 `verticalScroll`（高度约束无上限），
                    // 在其中放 LazyColumn 会被量成 0 高度 → 扫描明明成功、列表却一片空白，
                    // 用户看到的正是"进了这一步没扫出网络"。页面本身已能滚动，无需懒加载。
                    Column(modifier = Modifier.fillMaxWidth()) {
                        scanResults.forEach { network ->
                            WifiNetworkItem(
                                ssid = network.SSID,
                                level = network.level,
                                capabilities = network.capabilities,
                                onClick = { selectedSsid = network.SSID },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 出口行：**必须有**。原来「正在扫描…」「未发现网络」两个分支里既没有手动输入、
                // 也没有跳过按钮，一旦扫描卡住用户就彻底出不去这一步（这正是"卡住"的表现）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isScanning) {
                        Box(
                            modifier = Modifier.clickable { scanAttempt += 1 }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                ctx.getString(R.string.wifi_rescan),
                                color = BrewInfo, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.clickable { selectedSsid = "" }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            ctx.getString(R.string.wifi_manual_input),
                            color = BrewInfo, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        } else {
            // === 已选网络，输入密码 ===
            val ssid = selectedSsid
            requireNotNull(ssid) { "selectedSsid is null in password branch" }

            if (ssid.isNotEmpty()) {
                // 来自扫描列表
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(BrewShapeStandard).background(BrewPanel, BrewShapeStandard)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ssid, color = BrewText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier.clip(CircleShape).background(BrewBorder)
                                .clickable { selectedSsid = null }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                ctx.getString(R.string.wifi_change_network),
                                color = BrewText, fontSize = 12.sp,
                            )
                        }
                    }
                }
            } else {
                // 手动输入 SSID
                OutlinedTextField(
                    value = manualSsid,
                    onValueChange = { manualSsid = it; errorMessage = null },
                    label = { Text(ctx.getString(R.string.wifi_ssid_label)) },
                    singleLine = true,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    colors = standardFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))

            // 密码输入
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                    passwordPreFilled = false
                },
                label = { Text(ctx.getString(R.string.wifi_password_label)) },
                singleLine = true,
                enabled = !sending,
                visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val targetSsid = if (ssid.isNotEmpty()) ssid else manualSsid
                    if (targetSsid.isNotBlank() && onSendWifiConfig != null) {
                        errorMessage = null
                        sending = true
                        onSendWifiConfig(targetSsid, password) { success, errMsg ->
                            sending = false
                            if (success) {
                                configured = true
                                // 连接成功才记住密码，下次自动回填
                                saveWifiPassword(targetSsid, password)
                            } else {
                                errorMessage = errMsg ?: "连接失败"
                            }
                        }
                    }
                }),
                colors = standardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))

            // 显示密码开关
            Box(
                modifier = Modifier.fillMaxWidth().clickable { showPassword = !showPassword }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    if (showPassword) ctx.getString(R.string.hide_password)
                    else ctx.getString(R.string.show_password),
                    color = BrewInfo, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }

            // 自动填入保存密码的提示
            if (passwordPreFilled) {
                Text(
                    ctx.getString(R.string.wifi_password_prefilled),
                    color = BrewMuted, fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 错误提示
            val errorText = errorMessage
            if (errorText != null) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(BrewRed.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "\u26A0",
                            color = BrewRed,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            errorText,
                            color = BrewRed,
                            fontSize = 13.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(8.dp))

            // 发送按钮
            Box(
                modifier = Modifier.fillMaxWidth().height(56.dp)
                        .clip(BrewShapeStandard)
                        .background(if (sending) BrewPanel else BrewInfo, BrewShapeStandard)
                        .then(
                            if (!sending && onSendWifiConfig != null) {
                                Modifier.clickable {
                                    val targetSsid = if (ssid.isNotEmpty()) ssid else manualSsid
                                    if (targetSsid.isBlank()) {
                                        errorMessage = "请输入网络名称"
                                        return@clickable
                                    }
                                    errorMessage = null
                                    sending = true
                                    onSendWifiConfig(targetSsid, password) { success, errMsg ->
                                        sending = false
                                        if (success) {
                                            configured = true
                                            // 连接成功才记住密码，下次自动回填
                                            saveWifiPassword(targetSsid, password)
                                        } else {
                                            errorMessage = errMsg ?: "连接失败"
                                        }
                                    }
                                }
                            } else Modifier
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sending) {
                        Box(
                            modifier = Modifier.size(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = sending,
                                enter = androidx.compose.animation.fadeIn(),
                                exit = androidx.compose.animation.fadeOut(),
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = BrewMuted,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (sending) ctx.getString(R.string.wifi_sending)
                        else ctx.getString(R.string.wifi_send_to_glasses),
                        color = if (sending) BrewMuted else BrewBg,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 手动输入 SSID
            Box(
                modifier = Modifier.fillMaxWidth().clickable { selectedSsid = ""; errorMessage = null; password = ""; manualSsid = "" }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ctx.getString(R.string.wifi_manual_input),
                    color = BrewInfo, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WifiNetworkItem(
    ssid: String,
    level: Int,
    capabilities: String,
    onClick: () -> Unit,
) {
    val isEncrypted = capabilities.contains("WPA", ignoreCase = true) ||
            capabilities.contains("WEP", ignoreCase = true)

    val signalBars = when {
        level > -50 -> 4
        level > -65 -> 3
        level > -80 -> 2
        level > -90 -> 1
        else -> 0
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(BrewShapeStandard)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 信号强度指示
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.width(24.dp)) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier.width(4.dp)
                        .height((4 + i * 4).dp)
                        .padding(end = 2.dp)
                        .background(
                            if (i < signalBars) BrewInfo else BrewPanel,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // SSID
        Text(
            ssid, color = BrewText, fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )

        // 加密锁图标（用文字替代）
        if (isEncrypted) {
            Box(
                modifier = Modifier.width(20.dp).height(16.dp)
                    .border(1.dp, BrewMuted, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "\uD83D\uDD12", fontSize = 10.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        // 展开箭头
        Text("\u276F", color = BrewMuted, fontSize = 12.sp)
    }

    // 分割线
    Box(
        modifier = Modifier.fillMaxWidth().height(0.5.dp).padding(start = 48.dp)
            .background(BrewBorder),
    )
}

@Composable
private fun standardFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = BrewText,
    unfocusedTextColor = BrewText,
    focusedLabelColor = BrewInfo,
    unfocusedLabelColor = BrewMuted,
    focusedBorderColor = BrewInfo,
    unfocusedBorderColor = BrewBorder,
    cursorColor = BrewText,
)
