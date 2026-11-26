package com.nasmusicdrop.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.nasmusicdrop.app.ui.main.HistoryScreen
import com.nasmusicdrop.app.ui.main.MainScreen
import com.nasmusicdrop.app.ui.main.MainViewModel
import com.nasmusicdrop.app.ui.main.SearchScreen
import com.nasmusicdrop.app.ui.main.SettingsScreen
import com.nasmusicdrop.app.ui.theme.NASMusicDropTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.nasmusicdrop.app.data.AppStrings.init(applicationContext)
        updateTaskDescription()

        // Solicitar permiso de notificaciones en Android 13+ para el servicio en segundo plano
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Manejar Intent inicial si la app fue abierta desde "Compartir" de YouTube
        handleIncomingIntent(intent)

        setContent {
            val nasConfig by viewModel.nasConfig.collectAsState()
            val isDarkTheme = when (nasConfig.themeMode) {
                "LIGHT" -> false
                "DARK" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            NASMusicDropTheme(darkTheme = isDarkTheme, accentColor = nasConfig.accentColor) {
                val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                val coroutineScope = rememberCoroutineScope()
                val showMessage: (String) -> Unit = { msg ->
                    coroutineScope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                    bottomBar = {
                        Column {
                            GlobalAudioMiniPlayer(
                                viewModel = viewModel,
                                onNavigateToSource = { isHistory ->
                                    viewModel.selectTab(if (isHistory) 2 else 1)
                                }
                            )
                            NavigationBar {
                                NavigationBarItem(
                                    selected = selectedTabIndex == 0,
                                    onClick = { viewModel.selectTab(0) },
                                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_download)) },
                                    label = { Text(androidx.compose.ui.res.stringResource(R.string.nav_download)) }
                                )
                                NavigationBarItem(
                                    selected = selectedTabIndex == 1,
                                    onClick = { viewModel.selectTab(1) },
                                    icon = { Icon(Icons.Default.Search, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_search)) },
                                    label = { Text(androidx.compose.ui.res.stringResource(R.string.nav_search)) }
                                )
                                NavigationBarItem(
                                    selected = selectedTabIndex == 2,
                                    onClick = { viewModel.selectTab(2) },
                                    icon = { Icon(Icons.Default.History, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_history)) },
                                    label = { Text(androidx.compose.ui.res.stringResource(R.string.nav_history)) }
                                )
                                NavigationBarItem(
                                    selected = selectedTabIndex == 3,
                                    onClick = { viewModel.selectTab(3) },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_settings)) },
                                    label = { Text(androidx.compose.ui.res.stringResource(R.string.nav_settings)) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    when (selectedTabIndex) {
                        0 -> MainScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding), onShowMessage = showMessage)
                        1 -> SearchScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding), onShowMessage = showMessage)
                        2 -> HistoryScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding), onShowMessage = showMessage)
                        3 -> SettingsScreen(viewModel = viewModel, modifier = Modifier.padding(innerPadding), onShowMessage = showMessage)
                    }
                }
            }
        }
    }

    private fun updateTaskDescription() {
        try {
            val appName = getString(R.string.app_name)
            val bmp = android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            if (bmp != null) {
                try {
                    @Suppress("DEPRECATION")
                    setTaskDescription(
                        android.app.ActivityManager.TaskDescription(
                            appName,
                            bmp,
                            0xFF0F172A.toInt()
                        )
                    )
                } finally {
                    bmp.recycle()
                }
            }
        } catch (_: Throwable) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (Intent.ACTION_SEND == intent.action && "text/plain" == intent.type) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                intent.action = null // Consumir el Intent para no duplicar en rotaciones
                viewModel.selectTab(0)
                viewModel.handleInputUrl(sharedText)
            }
        }
    }
}


@Composable
fun GlobalAudioMiniPlayer(
    viewModel: MainViewModel,
    onNavigateToSource: (isHistory: Boolean) -> Unit = {}
) {
    val currentPlayingId by viewModel.previewPlayer.currentTrackId.collectAsState()
    if (currentPlayingId == null) return

    val currentTitle by viewModel.previewPlayer.currentTrackTitle.collectAsState()
    val isPlaying by viewModel.previewPlayer.isPlaying.collectAsState()
    val isLoading by viewModel.previewPlayer.isLoading.collectAsState()
    val currentPositionMs by viewModel.previewPlayer.currentPositionMs.collectAsState()
    val durationMs by viewModel.previewPlayer.durationMs.collectAsState()
    val uploadHistory by viewModel.uploadHistory.collectAsState()
    val isHistorySource = uploadHistory.any { it.id == currentPlayingId }
    val isHistoryTrack = isHistorySource && uploadHistory.size > 1

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isHistoryTrack) {
                    IconButton(
                        onClick = { viewModel.playPreviousHistoryItem() },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Anterior",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        if (isPlaying) viewModel.previewPlayer.pausePreview()
                        else viewModel.previewPlayer.resumePreview()
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (isHistoryTrack) {
                    IconButton(
                        onClick = { viewModel.playNextHistoryItem() },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Siguiente",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToSource(isHistorySource) }
                ) {
                    Text(
                        text = currentTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val curSec = currentPositionMs / 1000
                    val durSec = durationMs / 1000
                    Text(
                        text = "${com.nasmusicdrop.app.data.audio.YouTubeExtractor.formatTimestamp(curSec)} / ${com.nasmusicdrop.app.data.audio.YouTubeExtractor.formatTimestamp(durSec)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { viewModel.previewPlayer.stopPreview() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar reproductor", modifier = Modifier.size(18.dp))
                }
            }

            if (durationMs > 0) {
                Slider(
                    value = (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                    onValueChange = { factor ->
                        val targetMs = (factor * durationMs).toLong()
                        viewModel.previewPlayer.seekTo(targetMs)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                )
            }
        }
    }
}
