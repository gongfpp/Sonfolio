package com.gongfpp.sonfolio

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable internal fun BackgroundSettingsCard() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val power = context.getSystemService(PowerManager::class.java)
    val ignored = remember(revision) { power.isIgnoringBatteryOptimizations(context.packageName) }
    val saving = remember(revision) { power.isPowerSaveMode }
    val restricted = remember(revision) { Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted }
    val notifications = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    fun open(intent: Intent) {
        error = null
        try { context.startActivity(intent) }
        catch (_: Exception) { error = "无法打开这个系统页面，请在系统设置中搜索“声迹”，查看电池、通知和自启动选项。" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("长时间录音前，建议检查以下设置。从系统设置返回后会自动更新状态。")
        OutlinedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("电池与后台", style = MaterialTheme.typography.titleMedium)
                Text(if (ignored) "电池优化：已忽略" else "电池优化：尚未忽略")
                Text(if (restricted) "后台活动：被系统限制" else "后台活动：未检测到系统限制")
                Text(if (saving) "省电模式：已开启，可能限制后台处理" else "省电模式：未开启")
                Text("在电池优化列表中找到声迹，选择不优化；部分手机称为“不限制”。")
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("打开电池优化设置") }
            }
        }
        OutlinedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("录音通知", style = MaterialTheme.typography.titleMedium)
                Text(if (notifications) "应用通知：已允许" else "应用通知：未允许")
                Text("保留录音通知，方便查看录音状态、停止和标记。通知允许不代表每个通知类别都已开启。")
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("打开通知设置") }
            }
        }
        OutlinedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("自启动与后台保留", style = MaterialTheme.typography.titleMedium)
                Text("在应用信息或手机管家里，允许声迹自启动、后台运行。小米手机还可在最近任务中锁定声迹，避免一键清理时关闭。不同系统的入口可能不同。")
                Text("自启动和任务锁定状态无法可靠读取，请在系统中确认。")
                OutlinedButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("打开声迹应用信息") }
            }
        }
        Text("这些设置可降低中断概率，但不能保证一直运行。强行停止、重启或系统资源不足仍可能中断录音；恢复使用后请检查录音状态。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
