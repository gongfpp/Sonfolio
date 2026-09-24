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

internal val Paper = Color(0xFFFBFAF6)
internal val Ink = Color(0xFF17201C)
internal val InkSoft = Color(0xFF626B65)
internal val Line = Color(0xFFE6E5DE)
internal val Green = Color(0xFF1E7046)
internal val PaleGreen = Color(0xFFE3F0DE)
internal val PaleGreenStrong = Color(0xFFDCEFD9)
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
private fun SonfolioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Green,
            onPrimary = Color.White,
            secondaryContainer = PaleGreen,
            onSecondaryContainer = Ink,
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
                    is AppScreen.Daily -> DailyScreen(viewModel = viewModel, initialDate = current.date, onBack = goBack)
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

