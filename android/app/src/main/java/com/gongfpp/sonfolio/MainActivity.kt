package com.gongfpp.sonfolio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.gongfpp.sonfolio.recording.RecordingFeedback
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val InkSoft: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val PaleGreen: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer
internal val PaleGreenStrong: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
internal val CardSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val ActionFill: Color @Composable get() = MaterialTheme.colorScheme.inversePrimary
internal val Amber = Color(0xFFDDA50B)
internal val AmberPale = Color(0xFFFFF3CB)

private val NavigationSaver = listSaver<AppNavigation, String>(
    save = { state -> state.stack.map { it.toSavedRoute() } },
    restore = { routes -> AppNavigation(routes.map(::appScreenFromSavedRoute).ifEmpty { listOf(AppScreen.Today) }) },
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: SonfolioViewModel = viewModel()
            SonfolioTheme { SonfolioApp(viewModel) }
        }
    }
}

@Composable
internal fun SonfolioTheme(darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) androidx.compose.material3.darkColorScheme(
            primary = Color(0xFF9ED7B2), onPrimary = Color(0xFF123922),
            primaryContainer = Color(0xFF284F36), onPrimaryContainer = Color(0xFFDAEEDC),
            secondaryContainer = Color(0xFF293D2D), onSecondaryContainer = Color(0xFFE8EEE3),
            background = Color(0xFF162019), surface = Color(0xFF1D2A21),
            onBackground = Color(0xFFE8EEE3), onSurface = Color(0xFFE8EEE3),
            onSurfaceVariant = Color(0xFFABB8A9), outline = Color(0xFF6F8273),
            outlineVariant = Color(0xFF344136), inversePrimary = Color(0xFF346C4E),
        ) else lightColorScheme(
            primary = Color(0xFF226548),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCEFD9), onPrimaryContainer = Color(0xFF17261F),
            secondaryContainer = Color(0xFFEAF1E5),
            onSecondaryContainer = Color(0xFF17261F),
            background = Color(0xFFF8F8F2),
            surface = Color(0xFFFFFEFA),
            onBackground = Color(0xFF17261F), onSurface = Color(0xFF17261F),
            onSurfaceVariant = Color(0xFF657268), outline = Color(0xFF78857A),
            outlineVariant = Color(0xFFE2E7DE), inversePrimary = Color(0xFF226548),
        ),
        content = content,
    )
}

@Composable
private fun SonfolioApp(viewModel: SonfolioViewModel) {
    var navigation by rememberSaveable(stateSaver = NavigationSaver) {
        mutableStateOf(AppNavigation())
    }
    val screen = navigation.current
    val screenStates = rememberSaveableStateHolder()
    val openScreen: (AppScreen) -> Unit = { navigation = navigation.open(it) }
    val goBack: () -> Unit = {
        if (!screen.isMainScreen) screenStates.removeState(screen.toSavedRoute())
        navigation = navigation.back()
    }
    BackHandler(enabled = navigation.canGoBack, onBack = goBack)
    val aliases by viewModel.conversationAliases.collectAsStateWithLifecycle()
    val recordingStatus by viewModel.recordingStatus.collectAsStateWithLifecycle()
    val recordingGaps by viewModel.recordingGaps.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val application = context.applicationContext as SonfolioApplication
    val markerState by com.gongfpp.sonfolio.recording.RecordingController.markerState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.recordingFeedback.collectLatest { feedback ->
            val message = when (feedback) {
                is RecordingFeedback.Marked -> "已标记前 ${feedback.windowMinutes} 分钟涉及的整段对话"
                is RecordingFeedback.Failed -> feedback.message
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val microphoneGranted = results[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED)
        if (microphoneGranted) {
            viewModel.startRecording()
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                results[Manifest.permission.POST_NOTIFICATIONS] == false
            ) {
                Toast.makeText(context, "通知未开启，通知栏标记按钮不可见", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "需要麦克风权限才能开始记录", Toast.LENGTH_LONG).show()
        }
    }
    val requestRecordingStart = {
        val permissions = buildList {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isEmpty()) {
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
    val isMainScreen = screen.isMainScreen

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            if (isMainScreen) {
                Column {
                if (recordingStatus.isRecording && screen !is AppScreen.Today) {
                    androidx.compose.material3.Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = ActionFill, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.TextButton(onClick = { navigation = navigation.selectTab(AppScreen.Today) }, modifier = Modifier.weight(1f)) {
                                Text("正在记录 · 返回声迹", color = Color.White, fontSize = 13.sp)
                            }
                            val minutes = application.preferences.markerWindows.first()
                            androidx.compose.material3.FilledTonalButton(
                                enabled = markerState !is com.gongfpp.sonfolio.recording.MarkerSaveState.Saving,
                                onClick = { viewModel.markCurrentMoment(minutes) },
                                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = AmberPale, contentColor = Color(0xFF694E00)),
                            ) { Text(if (markerState is com.gongfpp.sonfolio.recording.MarkerSaveState.Saving) "保存中…" else "★ 标记（${minutes}分）", fontSize = 12.sp) }
                        }
                    }
                }
                NavigationBar(containerColor = Paper) {
                    NavigationBarItem(
                        selected = screen is AppScreen.Today,
                        onClick = { navigation = navigation.selectTab(AppScreen.Today) },
                        icon = { Icon(Icons.Default.Home, contentDescription = "声迹") },
                        label = { Text("声迹") },
                    )
                    NavigationBarItem(
                        selected = screen is AppScreen.Search,
                        onClick = { navigation = navigation.selectTab(AppScreen.Search) },
                        icon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                        label = { Text("搜索") },
                    )
                    NavigationBarItem(
                        selected = screen is AppScreen.Settings,
                        onClick = { navigation = navigation.selectTab(AppScreen.Settings) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                        label = { Text("设置") },
                    )
                }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            screenStates.SaveableStateProvider(screen.toSavedRoute()) {
                when (val current = screen) {
                    AppScreen.Today -> TodayScreen(
                        viewModel = viewModel,
                        recordingStatus = recordingStatus,
                        gaps = recordingGaps,
                        onStartRecording = requestRecordingStart,
                        onStopRecording = viewModel::stopRecording,
                        onMark = viewModel::markCurrentMoment,
                        onRecoverRecording = viewModel::recoverRecording,
                        onEndInterruptedRecording = viewModel::endInterruptedRecording,
                        onOpen = openScreen,
                    )
                    AppScreen.Search -> SearchScreen(viewModel = viewModel, onOpen = openScreen)
                    AppScreen.Settings -> SettingsScreen(
                        preferences = application.preferences,
                        recordingStatus = recordingStatus,
                        onOpenRawRecordings = { openScreen(AppScreen.RawRecordings()) },
                        onRebuildConversations = viewModel::rebuildConversations,
                    )
                    is AppScreen.Daily -> DailyScreen(viewModel = viewModel, initialDate = current.date, onBack = goBack, onOpenConversation = { openScreen(AppScreen.Conversation(id = it)) })
                    is AppScreen.RawRecordings -> RawRecordingsScreen(
                        viewModel = viewModel,
                        date = current.date,
                        onRetry = viewModel::retryProcessing,
                        onBack = goBack,
                        onDeleteSelected = { viewModel.deleteChunks(it, protectMarked = true) },
                        onExportSelected = { ids, uri -> viewModel.exportChunksZip(uri, ids) },
                    )
                    is AppScreen.Conversation -> {
                        val canonicalId = aliases.firstOrNull { it.oldId == current.id }?.canonicalId ?: current.id
                        val conversation by remember(canonicalId) { viewModel.observeConversation(canonicalId) }.collectAsStateWithLifecycle(initialValue = null)
                        RealConversationScreen(
                            conversation = conversation,
                            conversationId = canonicalId,
                            initialTranscriptId = current.transcriptId,
                            searchQuery = current.query,
                            viewModel = viewModel,
                            onBack = goBack,
                        )
                    }
                }
            }
        }
    }
}
