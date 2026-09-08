package com.gongfpp.sonfolio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Paper = Color(0xFFFBFAF6)
private val Ink = Color(0xFF17201C)
private val InkSoft = Color(0xFF626B65)
private val Line = Color(0xFFE6E5DE)
private val Green = Color(0xFF1E7046)
private val PaleGreen = Color(0xFFE3F0DE)
private val PaleGreenStrong = Color(0xFFDCEFD9)
private val Amber = Color(0xFFDDA50B)
private val AmberPale = Color(0xFFFFF3CB)

internal enum class ConversationType { Release, Lunch, Game, Unknown }

internal sealed interface AppScreen {
    data object Today : AppScreen
    data object Search : AppScreen
    data object Settings : AppScreen
    data object Daily : AppScreen
    data class Conversation(val type: ConversationType) : AppScreen
}

internal fun AppScreen.toSavedRoute(): String = when (this) {
    AppScreen.Today -> "today"
    AppScreen.Search -> "search"
    AppScreen.Settings -> "settings"
    AppScreen.Daily -> "daily"
    is AppScreen.Conversation -> "conversation:${type.name}"
}

internal fun appScreenFromSavedRoute(route: String): AppScreen = when (route) {
    "today" -> AppScreen.Today
    "search" -> AppScreen.Search
    "settings" -> AppScreen.Settings
    "daily" -> AppScreen.Daily
    else -> {
        val typeName = route.substringAfter("conversation:", missingDelimiterValue = "")
        val type = ConversationType.entries.firstOrNull { it.name == typeName }
        if (type == null) AppScreen.Today else AppScreen.Conversation(type)
    }
}

private val AppScreenSaver = Saver<AppScreen, String>(
    save = { screen -> screen.toSavedRoute() },
    restore = ::appScreenFromSavedRoute,
)

private data class ConversationPreview(
    val type: ConversationType,
    val time: String,
    val title: String,
    val duration: String,
    val summary: String,
)

private val conversations = listOf(
    ConversationPreview(
        ConversationType.Release,
        "09:32",
        "与同事讨论系统投产",
        "12分钟",
        "确认十点投产窗口，先备份数据库并复核回滚方案",
    ),
    ConversationPreview(
        ConversationType.Lunch,
        "12:11",
        "午饭多人聊天",
        "28分钟",
        "聊到最近的工作节奏和周末安排",
    ),
    ConversationPreview(
        ConversationType.Game,
        "14:40",
        "记录一个游戏想法",
        "3分钟",
        "构思电梯断电时的声音提示和玩家反馈",
    ),
    ConversationPreview(
        ConversationType.Unknown,
        "18:20",
        "与未知人物对话",
        "7分钟",
        "围绕晚餐和回家时间的简短交流",
    ),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SonfolioTheme { SonfolioApp() } }
    }
}

@Composable
private fun SonfolioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Green,
            onPrimary = Color.White,
            background = Paper,
            surface = Color(0xFFFFFEFA),
            onBackground = Ink,
            onSurface = Ink,
            outline = Line,
        ),
        content = content,
    )
}

@Composable
private fun SonfolioApp() {
    var screen by rememberSaveable(stateSaver = AppScreenSaver) {
        mutableStateOf<AppScreen>(AppScreen.Today)
    }
    val isMainScreen = screen is AppScreen.Today || screen is AppScreen.Search || screen is AppScreen.Settings

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            if (isMainScreen) {
                NavigationBar(containerColor = Paper) {
                    NavigationBarItem(
                        selected = screen is AppScreen.Today,
                        onClick = { screen = AppScreen.Today },
                        icon = { Icon(Icons.Default.Home, contentDescription = "今天") },
                        label = { Text("今天") },
                    )
                    NavigationBarItem(
                        selected = screen is AppScreen.Search,
                        onClick = { screen = AppScreen.Search },
                        icon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                        label = { Text("搜索") },
                    )
                    NavigationBarItem(
                        selected = screen is AppScreen.Settings,
                        onClick = { screen = AppScreen.Settings },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                        label = { Text("设置") },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = screen) {
                AppScreen.Today -> TodayScreen(onOpen = { screen = it })
                AppScreen.Search -> SearchScreen(onOpen = { screen = it })
                AppScreen.Settings -> SettingsScreen()
                AppScreen.Daily -> DailyScreen(onBack = { screen = AppScreen.Today })
                is AppScreen.Conversation -> {
                    if (current.type == ConversationType.Game) {
                        GameSummaryScreen(onBack = { screen = AppScreen.Today })
                    } else {
                        ConversationScreen(current.type, onBack = { screen = AppScreen.Today })
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayScreen(onOpen: (AppScreen) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text("今天 · 9月8日", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("4场对话 · 已连续记录 6小时 42分", color = InkSoft, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        RecordingCard()
        SectionTitle("今天的对话")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            conversations.forEach { item -> TimelineCard(item) { onOpen(AppScreen.Conversation(item.type)) } }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { onOpen(AppScreen.Daily) },
            shape = RoundedCornerShape(15.dp),
            color = PaleGreen,
        ) {
            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.List, contentDescription = null, tint = Green, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("今日总结", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("已整理 4 场对话 · 查看一日回顾", color = InkSoft, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Green)
            }
        }
    }
}

@Composable
private fun RecordingCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = Color(0xFFF0F7EE),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB5CDB3)),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Green))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text("正在记录", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("06:42:17", color = InkSoft, fontSize = 12.sp)
            }
            Waveform(accent = Green, modifier = Modifier.width(50.dp))
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(11.dp), color = AmberPale) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("★", color = Amber, fontSize = 18.sp)
                    Text("标记刚才", color = Color(0xFF694E00), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(top = 20.dp, bottom = 9.dp), fontWeight = FontWeight.Bold, fontSize = 17.sp)
}

@Composable
private fun TimelineCard(item: ConversationPreview, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Green))
        Spacer(Modifier.width(10.dp))
        Surface(
            modifier = Modifier.weight(1f).clickable(onClick = onClick),
            shape = RoundedCornerShape(13.dp),
            color = Color(0xFFFFFEFA),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.time, color = InkSoft, fontSize = 13.sp)
                    Text(item.title, modifier = Modifier.padding(start = 9.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.duration, color = InkSoft, fontSize = 12.sp)
                }
                Text(item.summary, modifier = Modifier.padding(top = 4.dp), color = InkSoft, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DetailTopBar(title: String, meta: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = "更多") }
    }
    Text(meta, modifier = Modifier.padding(start = 46.dp), color = InkSoft, fontSize = 14.sp)
}

@Composable
private fun ConversationScreen(type: ConversationType, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val info = when (type) {
        ConversationType.Release -> Triple("与同事讨论系统投产", "09:32–09:44 · 12分钟", "确认今晚十点开始投产，先完成数据库备份，再按回滚方案逐项复核。双方确认由我负责上线前检查。")
        ConversationType.Lunch -> Triple("午饭多人聊天", "12:11–12:39 · 28分钟", "午饭时聊了最近的工作节奏和周末安排，整体是轻松的日常交流，没有需要跟进的明确事项。")
        ConversationType.Unknown -> Triple("与未知人物对话", "18:20–18:27 · 7分钟", "围绕晚餐和回家时间进行了简短交流，内容以确认今晚安排为主。")
        ConversationType.Game -> Triple("游戏机制讨论", "14:40–15:18 · 38分钟", "")
    }
    val fields = when (type) {
        ConversationType.Release -> listOf("讨论主题" to "投产安排与回滚准备", "已确认" to "十点开始；先备份数据库", "后续关注" to "上线前再检查一次回滚方案")
        ConversationType.Lunch -> listOf("交流主题" to "工作节奏与周末安排", "主要内容" to "分享最近的工作状态", "后续关注" to "暂无明确后续事项")
        ConversationType.Unknown -> listOf("交流主题" to "晚餐与回家时间", "已确认" to "今晚的回家安排", "后续关注" to "暂无明确后续事项")
        ConversationType.Game -> emptyList()
    }
    var transcriptOpen by rememberSaveable { mutableStateOf(false) }
    var playing by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        DetailTopBar(info.first, info.second, onBack)
        Spacer(Modifier.height(15.dp))
        SummaryCard(info.third, fields)
        Spacer(Modifier.height(16.dp))
        TranscriptSection(transcriptOpen, { transcriptOpen = !transcriptOpen }, type)
        Spacer(Modifier.height(12.dp))
        AudioPlayer(playing = playing, onToggle = { playing = !playing })
    }
}

@Composable
private fun SummaryCard(summary: String, fields: List<Pair<String, String>>) {
    Surface(shape = RoundedCornerShape(15.dp), color = PaleGreen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(19.dp))
                Text("本段小结", modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text(summary, modifier = Modifier.padding(top = 12.dp), color = Color(0xFF344039), fontSize = 13.sp, lineHeight = 22.sp)
            Column(Modifier.padding(top = 12.dp)) {
                fields.forEach { (label, value) ->
                    Row(Modifier.padding(top = 7.dp)) {
                        Text(label, modifier = Modifier.width(67.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(value, color = Color(0xFF4D5A51), fontSize = 12.sp)
                    }
                }
            }
            Surface(modifier = Modifier.padding(top = 10.dp), shape = CircleShape, color = Color.White.copy(alpha = .55f)) {
                Text("⌁  本地生成", modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = Green, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TranscriptSection(expanded: Boolean, onToggle: () -> Unit, type: ConversationType) {
    Column {
        Surface(Modifier.fillMaxWidth().height(1.dp), color = Line) {}
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(19.dp))
            Text("原始转写", modifier = Modifier.padding(start = 9.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandMore, contentDescription = null, tint = Ink)
        }
        if (expanded) {
            val rows = when (type) {
                ConversationType.Release -> listOf("09:32" to "我们今晚十点可以开始投产。", "09:35" to "先把数据库备份好。", "★ 09:38" to "然后按回滚方案逐项复核。", "09:41" to "没问题，我来负责上线前的检查。")
                ConversationType.Lunch -> listOf("12:11" to "最近工作节奏还好吗？", "12:18" to "这周比较忙，周末想安排一点轻松的活动。", "★ 12:31" to "那周末再看看天气，找时间一起吃饭。")
                ConversationType.Unknown -> listOf("18:20" to "晚饭已经准备好了吗？", "18:23" to "还没有，回去路上再决定吃什么。", "★ 18:26" to "好，那到家再联系。")
                ConversationType.Game -> emptyList()
            }
            rows.forEach { (time, text) ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(time, modifier = Modifier.width(55.dp), color = if (time.startsWith("★")) Amber else InkSoft, fontSize = 12.sp)
                    Text(text, color = Color(0xFF3E4A42), fontSize = 12.5.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun AudioPlayer(playing: Boolean, onToggle: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = PaleGreen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle, modifier = Modifier.size(36.dp).clip(CircleShape).background(Green)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "暂停" else "播放", tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Waveform(accent = Color(0xFF53966C), modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (playing) "00:07" else "00:00", color = InkSoft, fontSize = 10.sp)
                    Text("12:00", color = InkSoft, fontSize = 10.sp)
                }
            }
            Text("1.0x", color = InkSoft, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Waveform(accent: Color, modifier: Modifier = Modifier) {
    Row(modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(26) { index ->
            val barHeight = (5 + ((index * 7) % 17)).dp
            Box(Modifier.width(2.dp).height(barHeight).clip(CircleShape).background(accent))
        }
    }
}

@Composable
private fun GameSummaryScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var transcriptOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        DetailTopBar("游戏机制讨论", "14:40–15:18 · 38分钟", onBack)
        Row(Modifier.padding(top = 15.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("详细总结", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f))
            Surface(shape = CircleShape, color = AmberPale) { Text("信息量较大", color = Color(0xFF7C5D11), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            StructuredCard("讨论主题", "电梯断电时如何让玩家先感知危险", Icons.Default.List)
            StructuredCard("关键观点", "• 先用继电器断开的声音建立预警\n• 黑暗中保留短暂的方向提示", Icons.Default.Lightbulb)
            StructuredCard("共识与决定", "声音提示先于画面提示，作为第一版实验方案", Icons.Default.CheckCircle, PaleGreenStrong)
            StructuredCard("未决问题", "不同电梯材质是否需要不同音色", Icons.Default.HelpOutline, Color(0xFFEDF3E6))
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable { transcriptOpen = true },
            shape = RoundedCornerShape(13.dp),
            color = PaleGreen,
        ) {
            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (transcriptOpen) "已展开原始转写" else "展开全部转写", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Green)
            }
        }
        if (transcriptOpen) {
            Spacer(Modifier.height(10.dp))
            TranscriptSection(expanded = true, onToggle = { transcriptOpen = !transcriptOpen }, type = ConversationType.Game)
        }
        Text("已从 38 分钟语音中提炼", modifier = Modifier.fillMaxWidth().padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = InkSoft, fontSize = 11.sp)
    }
}

@Composable
private fun StructuredCard(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color = PaleGreen) {
    Surface(shape = RoundedCornerShape(14.dp), color = color, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
                Text(title, modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(body, modifier = Modifier.padding(top = 9.dp), color = Color(0xFF3D4B41), fontSize = 12.5.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun DailyScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        DetailTopBar("今日回顾", "9月8日", onBack)
        Surface(Modifier.fillMaxWidth().padding(top = 15.dp), RoundedCornerShape(15.dp), color = PaleGreen) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = Green, modifier = Modifier.size(19.dp))
                    Text("今天发生了什么", modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text("今天上午主要在处理系统投产相关工作。九点半和同事确认了晚上十点的投产安排，先做数据库备份，再复核回滚方案。中午聊了一些日常话题。下午记录了一个关于游戏电梯断电机制的想法，重点是用声音让玩家先感知危险。晚上和家里简单聊了晚餐与回家时间。", modifier = Modifier.padding(top = 13.dp), color = Color(0xFF39483E), fontSize = 13.sp, lineHeight = 23.sp)
            }
        }
        AuxiliaryCard("值得记住", "电梯断电机制的第一版方向已确定", Icons.Default.Star, Amber)
        AuxiliaryCard("可能需要处理", "上线前再检查一次回滚方案", Icons.Default.Warning, Color(0xFFC59016))
        Text("基于 4 场对话整理 · 原始录音仍按你的保留策略保存", modifier = Modifier.padding(top = 20.dp), color = InkSoft, fontSize = 11.sp)
        TextButton(onClick = {}) { Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("重新生成") }
    }
}

@Composable
private fun AuxiliaryCard(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp), RoundedCornerShape(14.dp), color = Color(0xFFEAF3E7)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                Text(title, modifier = Modifier.padding(start = 9.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Text(body, modifier = Modifier.padding(start = 28.dp, top = 8.dp), color = Color(0xFF4A574E), fontSize = 12.5.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(onOpen: (AppScreen) -> Unit) {
    var query by rememberSaveable { mutableStateOf("电梯 断电") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text("搜索记忆", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 17.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "清空搜索") } },
            shape = RoundedCornerShape(11.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "今天", "本周", "仅标记").forEach { value ->
                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value, fontSize = 12.sp) })
            }
        }
        Text("找到 2 条相关内容", color = InkSoft, fontSize = 13.sp)
        SearchResult("14:40", "记录一个游戏想法", "如果电梯突然断电，可以让玩家先听见继电器断开的声音……", "今天") { onOpen(AppScreen.Conversation(ConversationType.Game)) }
        SearchResult("上周三", "游戏机制讨论", "当电梯断电时，角色会被困在中间楼层，需要手动恢复电力……", "38分钟") { onOpen(AppScreen.Conversation(ConversationType.Game)) }
    }
}

@Composable
private fun SearchResult(time: String, title: String, excerpt: String, trailing: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onClick), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, color = InkSoft, fontSize = 13.sp)
                Text(title, modifier = Modifier.padding(start = 8.dp).weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(trailing, color = InkSoft, fontSize = 11.sp)
            }
            Text(excerpt, modifier = Modifier.padding(top = 9.dp), color = Color(0xFF3E4A42), fontSize = 12.5.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun SettingsScreen() {
    var chargeOnly by rememberSaveable { mutableStateOf(false) }
    var neverUpload by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text("录音与存储", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Surface(Modifier.fillMaxWidth().padding(top = 17.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("录音服务", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("正常", color = Green, fontSize = 13.sp)
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = InkSoft, modifier = Modifier.padding(start = 5.dp))
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(13.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StorageValue("已使用", "18.6", "GB", Modifier.weight(1f))
                    StorageValue("预计还可记录", "47", "天", Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(10.dp).clip(CircleShape).background(Color(0xFFE3E3DF))) {
                    Box(Modifier.fillMaxWidth(.62f).fillMaxSize().clip(CircleShape).background(Green))
                }
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column {
                RetentionRow("完整录音", "30天")
                RetentionRow("有效人声音频", "永久保留")
                RetentionRow("标记片段", "永久保留")
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 13.dp), RoundedCornerShape(14.dp), color = Color(0xFFFFFEFA), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column {
                ToggleRow("仅在充电时执行语音识别", chargeOnly) { chargeOnly = it }
                ToggleRow("原始音频永不上传", neverUpload) { neverUpload = it }
            }
        }
        Row(Modifier.padding(top = 17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = InkSoft, modifier = Modifier.size(19.dp))
            Text("所有核心处理默认在本机完成", modifier = Modifier.padding(start = 8.dp), color = InkSoft, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StorageValue(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = InkSoft, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, modifier = Modifier.padding(top = 5.dp), fontSize = 23.sp)
            Text(unit, modifier = Modifier.padding(start = 3.dp, bottom = 3.dp), fontSize = 11.sp)
        }
    }
}

@Composable
private fun RetentionRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().clickable {}.padding(horizontal = 13.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(value, color = InkSoft, fontSize = 13.sp)
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = InkSoft, modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
