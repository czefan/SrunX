package io.github.czefan.srunx.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.czefan.srunx.BuildConfig
import io.github.czefan.srunx.R
import io.github.czefan.srunx.protocol.AuthLogEntry
import io.github.czefan.srunx.protocol.OnlineDevice
import io.github.czefan.srunx.protocol.SrunConfig
import io.github.czefan.srunx.protocol.formatTrafficBytes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SrunXScreen(
    viewModel: MainViewModel,
    onRequestNotificationPermissionIfNeeded: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val context = LocalContext.current

    var ignoringBatteryOpt by remember { mutableStateOf(isIgnoringBattery(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ignoringBatteryOpt = isIgnoringBattery(context)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    var hostDraft by rememberSaveable { mutableStateOf("") }
    var acIdDraft by rememberSaveable { mutableStateOf("") }
    var usernameDraft by rememberSaveable { mutableStateOf("") }
    var passwordDraft by remember { mutableStateOf("") }
    var domainDraft by rememberSaveable { mutableStateOf("") }
    var passwordInitialized by remember { mutableStateOf(false) }
    var draftInitialized by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.configLoaded) {
        if (state.configLoaded && !draftInitialized) {
            hostDraft = state.config.host
            acIdDraft = state.config.acId
            usernameDraft = state.config.username
            domainDraft = state.config.domainSuffix
            draftInitialized = true
        }
        if (state.configLoaded && !passwordInitialized) {
            passwordDraft = state.config.password
            passwordInitialized = true
        }
    }

    val draftReady = state.configLoaded && draftInitialized && passwordInitialized
    val hasUnsavedChanges = draftReady && (
        SrunConfig(host = hostDraft).normalizedHost() != state.config.normalizedHost() ||
            acIdDraft.trim() != state.config.acId ||
            usernameDraft.trim() != state.config.username ||
            passwordDraft != state.config.password ||
            domainDraft.trim().removePrefix("@") != state.config.domainSuffix
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showAboutDialog = true }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        SrunXCircleIcon(size = 28.dp)
                        Text(
                            text = "SrunX",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                actions = {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = {
                            Text(if (state.runtime.serviceRunning) "后台监听中" else "未监听")
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledLabelColor = if (state.runtime.serviceRunning) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(
                    state = state,
                    loginReady = draftReady,
                    hasUnsavedChanges = hasUnsavedChanges,
                    onLogin = {
                        if (draftReady) {
                            val draft = SrunConfig(
                                host = hostDraft,
                                acId = acIdDraft,
                                username = usernameDraft,
                                password = passwordDraft,
                                domainSuffix = domainDraft,
                            )
                            if (!draft.isReadyForLogin()) {
                                uiScope.launch { snackbarHostState.showSnackbar("请先填写完整的网关地址、学号与密码") }
                            } else {
                                viewModel.loginNow(draft, hasUnsavedChanges)
                            }
                        }
                    },
                    onRefresh = viewModel::refreshStatus,
                    onLogout = viewModel::logoutNow,
                )
            }

            item {
                KeepAliveCard(
                    autoMonitor = state.config.autoMonitorEnabled,
                    autoBoot = state.config.autoStartOnBoot,
                    ignoringBatteryOpt = ignoringBatteryOpt,
                    onToggleMonitor = { enabled ->
                        if (enabled) onRequestNotificationPermissionIfNeeded()
                        viewModel.setAutoMonitor(enabled)
                    },
                    onToggleBoot = viewModel::setAutoStartOnBoot,
                    onOpenBatterySettings = {
                        val pkgUri = Uri.parse("package:${context.packageName}")
                        if (ignoringBatteryOpt) {
                            val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                            runCatching { context.startActivity(listIntent) }
                                .onFailure { runCatching { context.startActivity(appDetailsIntent) } }
                        } else {
                            val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
                            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            runCatching { context.startActivity(directIntent) }
                                .onFailure { runCatching { context.startActivity(fallbackIntent) } }
                        }
                    },
                )
            }

            item {
                ConfigCard(
                    ready = draftReady,
                    host = hostDraft,
                    acId = acIdDraft,
                    username = usernameDraft,
                    password = passwordDraft,
                    domainSuffix = domainDraft,
                    hasUnsavedChanges = hasUnsavedChanges,
                    onHostChange = { hostDraft = it },
                    onAcIdChange = { acIdDraft = it },
                    onUsernameChange = { usernameDraft = it },
                    onPasswordChange = { passwordDraft = it },
                    onDomainChange = { domainDraft = it },
                    onSave = {
                        viewModel.saveConfig(hostDraft, acIdDraft, usernameDraft, passwordDraft, domainDraft)
                    },
                )
            }

            val devices = state.runtime.lastStatus
                ?.takeIf { state.runtime.wifiConnected && it.isOnline }
                ?.onlineDevices
                .orEmpty()
            if (devices.isNotEmpty()) {
                item {
                    OnlineDevicesCard(devices = devices)
                }
            }

            item {
                LogsCard(
                    logs = state.logs,
                    onClear = viewModel::clearLogs,
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(
            onOpenRepo = {
                val repoUri = Uri.parse("https://github.com/czefan/SrunX")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, repoUri)) }
            },
            onDismiss = { showAboutDialog = false },
        )
    }
}

@Composable
private fun SrunXCircleIcon(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.45f),
        )
    }
}

@Composable
private fun AboutDialog(
    onOpenRepo: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SrunXCircleIcon(size = 36.dp)
                Column {
                    Text(
                        text = "SrunX",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} · MIT License",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "极简轻量的开源深澜（Srun3000）校园网自动认证与保活客户端，支持双进程驻留与 Wi-Fi 网卡直连。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider()
                Text(
                    text = "隐私与安全",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "• 密码经 Android Keystore (AES-256-GCM) 加密仅存本地，已关闭系统云备份。\n" +
                        "• 零定位、零存储、零悬浮窗权限，认证流量仅走 Wi-Fi 网卡直连你配置的网关。\n" +
                        "• 应用本体无任何第三方联网、遥测或广告。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text(
                    text = "开源仓库：github.com/czefan/SrunX",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenRepo) {
                Text("打开仓库")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun StatusCard(
    state: SrunUiState,
    loginReady: Boolean,
    hasUnsavedChanges: Boolean,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    val runtime = state.runtime
    val status = runtime.lastStatus
    val isOnline = runtime.wifiConnected && status?.isOnline == true

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "网络与认证状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusPill(
                    text = when {
                        runtime.isBusy -> "请求中"
                        isOnline -> "已在线"
                        !runtime.wifiConnected -> "未连 Wi-Fi"
                        else -> "未在线"
                    },
                    highlighted = isOnline,
                )
            }

            if (runtime.isBusy) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = runtime.lastMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (status != null && isOnline) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))
                InfoRow("账号 / 套餐", listOf(status.username, status.productName).filter { it.isNotBlank() }.joinToString(" · "))
                InfoRow("终端 IP / MAC", listOf(status.onlineIp, status.userMac).filter { it.isNotBlank() }.joinToString(" · "))
                InfoRow(
                    "流量统计",
                    "本次 ${formatTrafficBytes(status.sessionBytes)} · 累计 ${formatTrafficBytes(status.totalBytes)}",
                )
            }

            if (hasUnsavedChanges) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "检测到配置有修改，点击「登录」将自动保存并认证",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onLogin,
                    enabled = loginReady && !runtime.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("登录")
                }
                FilledTonalButton(
                    onClick = onRefresh,
                    enabled = !runtime.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("刷新")
                }
                OutlinedButton(
                    onClick = onLogout,
                    enabled = !runtime.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("注销")
                }
            }
        }
    }
}

@Composable
private fun KeepAliveCard(
    autoMonitor: Boolean,
    autoBoot: Boolean,
    ignoringBatteryOpt: Boolean,
    onToggleMonitor: (Boolean) -> Unit,
    onToggleBoot: (Boolean) -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "自动保活",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Wi-Fi 自动监听认证", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "无需定位权限，仅在未验证 Wi-Fi 接入时绑网卡静默登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoMonitor, onCheckedChange = onToggleMonitor)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("开机自启动", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "重启手机后自动恢复前台保活监听",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoBoot,
                    enabled = autoMonitor,
                    onCheckedChange = onToggleBoot,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (ignoringBatteryOpt) "电池优化：已设为不受限制" else "电池优化：建议设为不受限制防杀后台",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ignoringBatteryOpt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (ignoringBatteryOpt) "去关闭" else "去设置",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenBatterySettings)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ConfigCard(
    ready: Boolean,
    host: String,
    acId: String,
    username: String,
    password: String,
    domainSuffix: String,
    hasUnsavedChanges: Boolean,
    onHostChange: (String) -> Unit,
    onAcIdChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDomainChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "网关与账号配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (hasUnsavedChanges) {
                    Text(
                        text = "未保存",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            OutlinedTextField(
                value = host,
                enabled = ready,
                onValueChange = onHostChange,
                label = { Text("认证网关地址") },
                placeholder = { Text("例如 http://10.0.0.1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = acId,
                enabled = ready,
                onValueChange = onAcIdChange,
                label = { Text("AC ID（选填）") },
                placeholder = { Text("留空则自动从门户页探测") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = username,
                enabled = ready,
                onValueChange = onUsernameChange,
                label = { Text("学号 / 账号") },
                placeholder = { Text("输入校园网登录账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                enabled = ready,
                onValueChange = onPasswordChange,
                label = { Text("密码") },
                placeholder = { Text("加密保存在本地 Keystore 中") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(
                        enabled = ready,
                        onClick = { passwordVisible = !passwordVisible },
                    ) {
                        Text(if (passwordVisible) "隐藏" else "显示")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            if (password != password.trim()) {
                Text(
                    text = "提示：密码首尾包含空格，如非故意请核对",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = domainSuffix,
                enabled = ready,
                onValueChange = onDomainChange,
                label = { Text("运营商域后缀（选填）") },
                placeholder = { Text("如 cmcc / unicom，无需求留空") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                enabled = ready,
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存配置")
            }
        }
    }
}

@Composable
private fun OnlineDevicesCard(devices: List<OnlineDevice>) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "同账号在线设备 (${devices.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            devices.forEachIndexed { idx, dev ->
                if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = dev.osName.ifBlank { "未知系统" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = dev.ip,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogsCard(
    logs: List<AuthLogEntry>,
    onClear: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "最近认证日志 (${logs.size}/20)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expanded && logs.isNotEmpty()) {
                        TextButton(onClick = onClear) {
                            Text("清空")
                        }
                    }
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "暂无认证记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        logs.forEachIndexed { idx, log ->
                            if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier.size(8.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (log.success) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                ) {}
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${fmt.format(Date(log.timestampMs))} · [${log.trigger}]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = log.message,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, highlighted: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun isIgnoringBattery(context: android.content.Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java)
    return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
}
