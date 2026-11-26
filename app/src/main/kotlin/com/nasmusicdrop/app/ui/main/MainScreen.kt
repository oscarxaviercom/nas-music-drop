package com.nasmusicdrop.app.ui.main

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nasmusicdrop.app.R
import com.nasmusicdrop.app.data.external.ExternalCollection
import com.nasmusicdrop.app.data.model.PlaylistData
import com.nasmusicdrop.app.data.model.PlaylistItem
import com.nasmusicdrop.app.data.model.QueueItem
import com.nasmusicdrop.app.data.model.QueueStatus
import com.nasmusicdrop.app.data.model.SongCandidate
import com.nasmusicdrop.app.ui.theme.successColor
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onShowMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val nasConfig by viewModel.nasConfig.collectAsState()
    val queueList by viewModel.queueList.collectAsState()
    val isQueuePaused by viewModel.isQueuePaused.collectAsState()
    val isAnalyzingPlaylist by viewModel.isAnalyzingPlaylist.collectAsState()
    val playlistDialogData by viewModel.playlistDialogData.collectAsState()
    val isResolvingExternal by viewModel.isResolvingExternal.collectAsState()
    val externalBatchProgress by viewModel.externalBatchProgress.collectAsState()
    val externalCollectionDialogData by viewModel.externalCollectionDialogData.collectAsState()
    val externalErrorMessage by viewModel.externalErrorMessage.collectAsState()
    val playlistSummaryBanner by viewModel.playlistSummaryBanner.collectAsState()

    var inputUrlText by remember { mutableStateOf("") }

    val activeCount = queueList.count { it.status != QueueStatus.COMPLETED && it.status != QueueStatus.FAILED }
    val completedCount = queueList.count { it.status == QueueStatus.COMPLETED }
    val failedRetryableCount = queueList.count { it.status == QueueStatus.FAILED && !it.videoId.startsWith("unresolved_") }
    val activeRunningItem = queueList.firstOrNull {
        it.status == QueueStatus.ANALYZING ||
            it.status == QueueStatus.DOWNLOADING ||
            it.status == QueueStatus.NORMALIZING ||
            it.status == QueueStatus.UPLOADING
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Banner sticky cuando hay una descarga activa y elementos arriba (UX/U1)
        AnimatedVisibility(visible = activeRunningItem != null && (completedCount > 0 || listState.firstVisibleItemIndex > 1)) {
            activeRunningItem?.let { running ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val idx = queueList.indexOfFirst { it.id == running.id }
                            if (idx >= 0) {
                                coroutineScope.launch { listState.animateScrollToItem(idx + 3) }
                            }
                        }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${running.title} · ${running.progressPercent}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.cancelQueueItem(running.id)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(stringResource(R.string.common_cancel), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { running.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
        // 1. Header con estado del almacenamiento
        item {
            NasStatusHeader(
                destinationMode = nasConfig.destinationMode,
                tailscaleIp = nasConfig.tailscaleIp,
                onTestClick = { viewModel.testNasConnection() }
            )
        }

        // 2. Zona de Entrada de URL
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.main_add_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.main_add_card_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputUrlText,
                            onValueChange = { inputUrlText = it },
                            placeholder = { Text(stringResource(R.string.main_url_placeholder)) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() ?: "" else ""
                                if (text.isNotBlank()) {
                                    inputUrlText = text
                                } else {
                                    onShowMessage(context.getString(R.string.main_clipboard_empty))
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.main_paste_cd))
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (inputUrlText.isNotBlank()) {
                                viewModel.handleInputUrl(inputUrlText)
                                inputUrlText = ""
                            }
                        },
                        enabled = inputUrlText.isNotBlank() && !isAnalyzingPlaylist && !isResolvingExternal,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isAnalyzingPlaylist || isResolvingExternal) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.main_analyzing_link))
                        } else {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.main_add_btn))
                        }
                    }

                    if (externalBatchProgress != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = externalBatchProgress ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else if (isAnalyzingPlaylist) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.main_fetching_playlist),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (!playlistSummaryBanner.isNullOrBlank()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PlaylistAddCheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = playlistSummaryBanner ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.dismissPlaylistSummary() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.main_close_summary_cd),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // 3. Encabezado de la Cola
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.main_queue_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (queueList.isNotEmpty()) {
                    val pausedSuffix = if (isQueuePaused) stringResource(R.string.main_queue_paused_suffix) else ""
                    Text(
                        text = stringResource(R.string.main_queue_counts, activeCount, completedCount, pausedSuffix),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isQueuePaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val totalBatch = activeCount + completedCount
                    if (activeCount > 0 && totalBatch > 1) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (completedCount.toFloat() / totalBatch.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
                if (activeCount > 0 || isQueuePaused || failedRetryableCount > 0 || completedCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (activeCount > 0 || isQueuePaused) {
                            TextButton(
                                onClick = { viewModel.togglePauseQueue() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (isQueuePaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isQueuePaused) stringResource(R.string.main_resume_queue) else stringResource(R.string.main_pause_queue))
                            }
                        }
                        if (failedRetryableCount > 0) {
                            TextButton(
                                onClick = { viewModel.retryAllFailed() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.main_retry_count, failedRetryableCount))
                            }
                        }
                        if (completedCount > 0) {
                            TextButton(
                                onClick = { viewModel.clearCompleted() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.main_clear_completed))
                            }
                        }
                    }
                }
            }
        }

        // 4. Si la cola está vacía
        if (queueList.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.main_queue_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.main_queue_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            // 5. Lista de elementos en cola (con animación UX7 y swipe-to-dismiss UX1)
            items(queueList, key = { it.id }) { item ->
                val isItemRunning = item.status == QueueStatus.ANALYZING ||
                        item.status == QueueStatus.DOWNLOADING ||
                        item.status == QueueStatus.NORMALIZING ||
                        item.status == QueueStatus.UPLOADING

                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart && !isItemRunning) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.removeQueueItem(item.id)
                            onShowMessage(context.getString(R.string.main_removed_from_queue))
                            true
                        } else {
                            false
                        }
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = !isItemRunning,
                    modifier = Modifier.animateItem(),
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 20.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                ) {
                    QueueItemCard(
                        item = item,
                        onRetry = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.retryQueueItem(item.id)
                        },
                        onCancel = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.cancelQueueItem(item.id)
                        },
                        onSkipDuplicate = { viewModel.skipDuplicate(item.id) },
                        onOverwriteDuplicate = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.overwriteDuplicate(item.id)
                        },
                        onRemove = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.removeQueueItem(item.id)
                            onShowMessage(context.getString(R.string.main_removed_from_queue))
                        },
                        onUpdateLyrics = { newLyrics, newSynced -> viewModel.updateQueueItemLyrics(item.id, newLyrics, newSynced) },
                        onSearchLyrics = { query -> viewModel.searchAlternativeLyrics(query) },
                        onSelectCandidate = { candidate -> viewModel.selectCandidate(item.id, candidate) },
                        onUpdateMetadata = { title, artist, album, startSec, endSec -> viewModel.updateItemMetadata(item.id, title, artist, album, startSec, endSec) },
                        onPickYouTubeVideo = { videoId -> viewModel.resolveFailedWithYouTubeVideo(item.id, videoId) }
                    )
                }
            }
        }
    }
    }

    // Diálogo interactivo para seleccionar canciones de listas de reproducción de YouTube
    playlistDialogData?.let { data ->
        PlaylistSelectorDialog(
            playlistData = data,
            onDismiss = { viewModel.dismissPlaylistDialog() },
            onConfirm = { selectedIds ->
                viewModel.enqueuePlaylistItems(selectedIds)
            }
        )
    }

    // Diálogo interactivo para seleccionar canciones de colecciones de plataformas externas (Spotify)
    externalCollectionDialogData?.let { collection ->
        ExternalCollectionSelectorDialog(
            collection = collection,
            onDismiss = { viewModel.dismissExternalCollectionDialog() },
            onConfirm = { selectedIds ->
                viewModel.enqueueExternalCollection(selectedIds)
            }
        )
    }

    // Diálogo informativo claro cuando una plataforma bloquea el enlace (ej. playlist de Amazon Music o Spotify privada)
    externalErrorMessage?.let { (errTitle, errBody) ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissExternalError() },
            icon = {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(errTitle, fontWeight = FontWeight.Bold) },
            text = { Text(errBody, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = { viewModel.dismissExternalError() }) {
                    Text(stringResource(R.string.common_got_it))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.retryLastExternalUrl() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.common_retry))
                }
            }
        )
    }
}

@Composable
fun QueueItemCard(
    item: QueueItem,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onSkipDuplicate: () -> Unit,
    onOverwriteDuplicate: () -> Unit,
    onRemove: () -> Unit,
    onUpdateLyrics: (String?, String?) -> Unit,
    onSearchLyrics: suspend (String) -> List<com.nasmusicdrop.app.data.metadata.LrclibService.LyricsCandidate>,
    onSelectCandidate: (com.nasmusicdrop.app.data.model.SongCandidate) -> Unit,
    onUpdateMetadata: (String, String, String, Long, Long) -> Unit,
    onPickYouTubeVideo: (String) -> Unit
) {
    var showLyricsDialog by remember { mutableStateOf(false) }
    var showCandidateDialog by remember { mutableStateOf(false) }
    var showEditMetadataDialog by remember { mutableStateOf(false) }
    var showYouTubePickerDialog by remember { mutableStateOf(false) }

    val isRunning = item.status == QueueStatus.ANALYZING ||
            item.status == QueueStatus.DOWNLOADING ||
            item.status == QueueStatus.NORMALIZING ||
            item.status == QueueStatus.UPLOADING

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // ZONA 1: Cabecera estable (Carátula + Título/Artista/Badges + Cerrar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                AsyncImage(
                    model = item.thumbnailUrl.ifBlank { "https://img.youtube.com/vi/${item.videoId}/hqdefault.jpg" },
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        QueueStatusBadge(status = item.status, percent = item.progressPercent)
                        if (item.startTimeSeconds > 0 || item.endTimeSeconds > 0) {
                            val trimLabel = when {
                                item.startTimeSeconds > 0 && item.endTimeSeconds > 0 ->
                                    "✂️ ${com.nasmusicdrop.app.data.audio.YouTubeExtractor.formatTimestamp(item.startTimeSeconds)} - ${com.nasmusicdrop.app.data.audio.YouTubeExtractor.formatTimestamp(item.endTimeSeconds)}"
                                item.startTimeSeconds > 0 ->
                                    stringResource(R.string.queue_trim_start, com.nasmusicdrop.app.data.audio.YouTubeExtractor.formatTimestamp(item.startTimeSeconds))
                                else ->
                                    stringResource(R.string.queue_trim_end, com.nasmusicdrop.app.data.audio.YouTubeExtractor.formatTimestamp(item.endTimeSeconds))
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = trimLabel,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                if (!isRunning) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.main_remove_from_queue_cd),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ZONA 3: Chips de metadatos a ancho completo en 1 fila (siempre accesibles)
            Spacer(modifier = Modifier.height(10.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                    // Chip Letra
                    val hasLyrics = !item.lyrics.isNullOrBlank()
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (hasLyrics)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showLyricsDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Lyrics,
                                contentDescription = null,
                                tint = if (hasLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (hasLyrics) stringResource(R.string.queue_lyrics_yes) else stringResource(R.string.queue_lyrics_add),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (hasLyrics) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Chip Editar
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showEditMetadataDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(stringResource(R.string.queue_edit), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // Chip Versiones
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showCandidateDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Album,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            val countText = if (item.allCandidates.isNotEmpty()) " (${item.allCandidates.size})" else ""
                            Text(
                                stringResource(R.string.queue_versions, countText),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

            // ZONA 2 & 4: Progreso y acción principal (Cancelar abajo a la derecha)
            if (isRunning) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { item.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = item.progressStatusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 32.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.common_cancel), fontSize = 11.sp)
                    }
                }
            }

            // Estado duplicado: advertencia y elección del usuario
            if (item.status == QueueStatus.DUPLICATE_FOUND) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = com.nasmusicdrop.app.ui.theme.AmberWarning,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.queue_dup_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        if (item.duplicatePath != null) {
                            Text(
                                text = item.duplicatePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onSkipDuplicate,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.queue_dup_skip), fontSize = 12.sp)
                            }
                            Button(
                                onClick = onOverwriteDuplicate,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.queue_dup_overwrite), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Estado omitido por duplicado
            if (item.status == QueueStatus.SKIPPED) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.queue_skipped),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onOverwriteDuplicate) {
                        Text(stringResource(R.string.queue_download_anyway), fontSize = 11.sp)
                    }
                }
            }

            // Estado completado: ruta en NAS
            if (item.status == QueueStatus.COMPLETED && item.remotePath != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.queue_saved_at, item.remotePath),
                    style = MaterialTheme.typography.labelSmall,
                    color = successColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Estado de cancelado: opción de reanudar
            if (item.status == QueueStatus.CANCELLED) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.queue_cancelled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.queue_resume), fontSize = 12.sp)
                    }
                }
            }

            // Estado de error: mensaje y reintento
            if (item.status == QueueStatus.FAILED) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.queue_transfer_warning),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.errorMessage ?: stringResource(R.string.queue_default_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            if (item.videoId.startsWith("unresolved_")) {
                                Button(
                                    onClick = { showYouTubePickerDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(stringResource(R.string.queue_pick_video), fontSize = 12.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { showYouTubePickerDialog = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(stringResource(R.string.nav_search), fontSize = 12.sp)
                                }
                                Button(
                                    onClick = onRetry,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(stringResource(R.string.common_retry), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showYouTubePickerDialog) {
        YouTubeManualPickerDialog(
            initialQuery = "${item.artist} ${item.title}".trim(),
            onDismiss = { showYouTubePickerDialog = false },
            onSelectVideo = { vid ->
                showYouTubePickerDialog = false
                onPickYouTubeVideo(vid)
            }
        )
    }

    // Diálogo interactivo para ver, confirmar, buscar o editar la letra
    if (showLyricsDialog) {
        var selectedTab by remember { mutableIntStateOf(0) }
        var editedLyrics by remember(item.lyrics) { mutableStateOf(item.lyrics ?: "") }
        var currentSyncedLyrics by remember(item.syncedLyrics) { mutableStateOf(item.syncedLyrics) }
        var isEditingText by remember { mutableStateOf(false) }

        var lyricsQuery by remember { mutableStateOf("${item.artist} ${item.title}".trim()) }
        var candidateResults by remember { mutableStateOf<List<com.nasmusicdrop.app.data.metadata.LrclibService.LyricsCandidate>>(emptyList()) }
        var isSearchingLyrics by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = { showLyricsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lyrics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.lyrics_dialog_title),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                ) {
                    // Pestañas
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            label = { Text(stringResource(R.string.lyrics_tab_current)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                if (candidateResults.isEmpty() && !isSearchingLyrics) {
                                    coroutineScope.launch {
                                        isSearchingLyrics = true
                                        candidateResults = onSearchLyrics(lyricsQuery)
                                        isSearchingLyrics = false
                                    }
                                }
                            },
                            label = { Text(stringResource(R.string.lyrics_tab_search)) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (selectedTab == 0) {
                        // Modo 0: Ver / Editar letra actual
                        if (editedLyrics.isNotBlank()) {
                            if (isEditingText) {
                                OutlinedTextField(
                                    value = editedLyrics,
                                    onValueChange = { editedLyrics = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = editedLyrics,
                                        style = MaterialTheme.typography.bodySmall,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = stringResource(R.string.lyrics_none_assigned),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            selectedTab = 1
                                            coroutineScope.launch {
                                                isSearchingLyrics = true
                                                candidateResults = onSearchLyrics(lyricsQuery)
                                                isSearchingLyrics = false
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.lyrics_search_lrclib))
                                    }
                                }
                            }
                        }
                    } else {
                        // Modo 1: Buscador de letras alternativas en LRCLIB
                        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = lyricsQuery,
                                    onValueChange = { lyricsQuery = it },
                                    label = { Text(stringResource(R.string.lyrics_search_label)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            isSearchingLyrics = true
                                            candidateResults = onSearchLyrics(lyricsQuery)
                                            isSearchingLyrics = false
                                        }
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = stringResource(R.string.nav_search),
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (isSearchingLyrics) {
                                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                            } else if (candidateResults.isEmpty()) {
                                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.lyrics_none_found), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(candidateResults) { candidate ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = candidate.trackName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        text = "${candidate.artistName}${if (candidate.albumName.isNotBlank()) " · ${candidate.albumName}" else ""}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1
                                                    )
                                                    if (candidate.isSynced) {
                                                        Text(
                                                            text = stringResource(R.string.lyrics_synced_badge),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = successColor()
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Button(
                                                    onClick = {
                                                        editedLyrics = candidate.lyrics
                                                        currentSyncedLyrics = candidate.syncedLyrics
                                                        selectedTab = 0
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text(stringResource(R.string.lyrics_use_btn), fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateLyrics(editedLyrics.ifBlank { null }, currentSyncedLyrics)
                        showLyricsDialog = false
                    }
                ) {
                    Text(stringResource(R.string.lyrics_confirm_btn))
                }
            },
            dismissButton = {
                Row {
                    if (selectedTab == 0 && editedLyrics.isNotBlank()) {
                        TextButton(onClick = { isEditingText = !isEditingText }) {
                            Text(if (isEditingText) stringResource(R.string.lyrics_view_btn) else stringResource(R.string.lyrics_edit_text_btn))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    TextButton(
                        onClick = {
                            onUpdateLyrics(null, null)
                            showLyricsDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.lyrics_delete_btn))
                    }
                }
            }
        )
    }

    // Diálogo para editar metadatos manualmente
    if (showEditMetadataDialog) {
        EditMetadataDialog(
            initialTitle = item.title,
            initialArtist = item.artist,
            initialAlbum = item.album,
            initialStartTimeSeconds = item.startTimeSeconds,
            initialEndTimeSeconds = item.endTimeSeconds,
            onDismiss = { showEditMetadataDialog = false },
            onConfirm = { newTitle, newArtist, newAlbum, newStartSec, newEndSec ->
                onUpdateMetadata(newTitle, newArtist, newAlbum, newStartSec, newEndSec)
                showEditMetadataDialog = false
            }
        )
    }

    // Diálogo para elegir versión / portada alternativa
    if (showCandidateDialog) {
        CandidateSelectorDialog(
            initialQuery = "${item.artist} ${item.title}".trim(),
            initialCandidates = item.allCandidates,
            currentCandidate = item.candidate,
            onDismiss = { showCandidateDialog = false },
            onSelect = { candidate ->
                onSelectCandidate(candidate)
                showCandidateDialog = false
            }
        )
    }
}

@Composable
fun QueueStatusBadge(status: QueueStatus, percent: Int) {
    val (label, bg, fg) = when (status) {
        QueueStatus.QUEUED -> Triple(stringResource(R.string.status_queued), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        QueueStatus.WAITING_WIFI -> Triple(stringResource(R.string.status_waiting_wifi), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        QueueStatus.ANALYZING -> Triple(stringResource(R.string.status_analyzing), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        QueueStatus.DOWNLOADING -> Triple(stringResource(R.string.status_downloading, percent), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        QueueStatus.NORMALIZING -> Triple(stringResource(R.string.status_normalizing), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        QueueStatus.UPLOADING -> Triple(stringResource(R.string.status_uploading, percent), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        QueueStatus.COMPLETED -> Triple(stringResource(R.string.status_completed), successColor().copy(alpha = 0.18f), successColor())
        QueueStatus.FAILED -> Triple(stringResource(R.string.status_failed), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        QueueStatus.CANCELLED -> Triple(stringResource(R.string.status_cancelled), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        QueueStatus.DUPLICATE_FOUND -> Triple(stringResource(R.string.status_duplicate), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        QueueStatus.SKIPPED -> Triple(stringResource(R.string.status_skipped), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun PlaylistSelectorDialog(
    playlistData: PlaylistData,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val initialSelected = remember(playlistData) {
        playlistData.items.filter { !it.isAlreadyInLibrary }.map { it.videoId }
    }
    val selectedIds = remember(playlistData) { mutableStateListOf<String>().apply { addAll(initialSelected) } }
    val allSelected = selectedIds.size == playlistData.items.size
    val alreadyInLibraryCount = playlistData.items.count { it.isAlreadyInLibrary }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = playlistData.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (alreadyInLibraryCount > 0)
                        stringResource(R.string.playlist_songs_new, playlistData.items.size, playlistData.items.size - alreadyInLibraryCount)
                    else
                        stringResource(R.string.playlist_songs_found, playlistData.items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (allSelected) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.clear()
                                    selectedIds.addAll(playlistData.items.map { it.videoId })
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = { checked ->
                                selectedIds.clear()
                                if (checked) {
                                    selectedIds.addAll(playlistData.items.map { it.videoId })
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (allSelected) stringResource(R.string.playlist_select_none) else stringResource(R.string.playlist_select_all),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (alreadyInLibraryCount > 0) {
                        TextButton(
                            onClick = {
                                selectedIds.clear()
                                selectedIds.addAll(playlistData.items.filter { !it.isAlreadyInLibrary }.map { it.videoId })
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(stringResource(R.string.playlist_missing_only), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(playlistData.items, key = { it.videoId }) { track ->
                        val isChecked = selectedIds.contains(track.videoId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isChecked) selectedIds.remove(track.videoId)
                                    else selectedIds.add(track.videoId)
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) selectedIds.add(track.videoId)
                                    else selectedIds.remove(track.videoId)
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            AsyncImage(
                                model = track.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.uploader,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (track.isAlreadyInLibrary) {
                                    Text(
                                        text = stringResource(R.string.playlist_in_library),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = successColor(),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedIds.toList()) },
                enabled = selectedIds.isNotEmpty()
            ) {
                Text(stringResource(R.string.playlist_add_count, selectedIds.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
fun CandidateSelectorDialog(
    initialQuery: String,
    initialCandidates: List<SongCandidate>,
    currentCandidate: SongCandidate?,
    onDismiss: () -> Unit,
    onSelect: (SongCandidate) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf(initialQuery) }
    var candidateList by remember(initialCandidates) { mutableStateOf(initialCandidates) }
    var isSearchingItunes by remember { mutableStateOf(false) }

    // Si la lista de candidatos estaba vacía y tenemos término, buscar automáticamente
    LaunchedEffect(initialQuery) {
        if (candidateList.isEmpty() && initialQuery.isNotBlank()) {
            isSearchingItunes = true
            val results = com.nasmusicdrop.app.data.metadata.ItunesService.directSearch(initialQuery)
            candidateList = results
            isSearchingItunes = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.versions_dialog_title), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
            ) {
                Text(
                    text = stringResource(R.string.versions_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Barra de búsqueda en vivo dentro del diálogo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.versions_search_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                coroutineScope.launch {
                                    isSearchingItunes = true
                                    val results = com.nasmusicdrop.app.data.metadata.ItunesService.directSearch(searchQuery)
                                    candidateList = results
                                    isSearchingItunes = false
                                }
                            }
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        if (isSearchingItunes) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.nav_search), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearchingItunes) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (candidateList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.versions_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(candidateList) { candidate ->
                            val isSelected = currentCandidate?.album == candidate.album && currentCandidate?.title == candidate.title
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(candidate) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = candidate.artworkUrl600,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = candidate.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = candidate.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${candidate.album}${if (candidate.year.isNotBlank()) " (${candidate.year})" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.versions_selected_cd), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

@Composable
fun ExternalCollectionSelectorDialog(
    collection: ExternalCollection,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    // Las pistas que no están en la biblioteca se marcan por defecto
    val initialSelected = remember(collection) {
        collection.tracks.filter { !it.isAlreadyInLibrary }.map { it.id }
    }
    val selectedIds = remember(collection) {
        mutableStateListOf<String>().apply { addAll(initialSelected) }
    }
    val allSelected = selectedIds.size == collection.tracks.size
    val alreadyInLibraryCount = collection.tracks.count { it.isAlreadyInLibrary }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (collection.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = collection.coverUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    val platformColor = when (collection.platform) {
                        "Spotify" -> Color(0xFF1DB954)
                        "Apple Music" -> Color(0xFFFA243C)
                        "Deezer" -> Color(0xFFA238FF)
                        "Amazon Music" -> Color(0xFF00A8E1)
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Surface(
                        color = platformColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        val typeLabel = if (collection.type.equals("album", ignoreCase = true)) {
                            stringResource(R.string.collection_type_album)
                        } else {
                            stringResource(R.string.collection_type_playlist)
                        }
                        Text(
                            text = "${collection.platform} · $typeLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = platformColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = collection.title,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = collection.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.playlist_songs_new, collection.tracks.size, collection.tracks.size - alreadyInLibraryCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (alreadyInLibraryCount > 0) {
                            TextButton(
                                onClick = {
                                    selectedIds.clear()
                                    selectedIds.addAll(collection.tracks.filter { !it.isAlreadyInLibrary }.map { it.id })
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(stringResource(R.string.playlist_missing_only), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        TextButton(
                            onClick = {
                                if (allSelected) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.clear()
                                    selectedIds.addAll(collection.tracks.map { it.id })
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = if (allSelected) stringResource(R.string.playlist_select_none) else stringResource(R.string.playlist_select_all),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(collection.tracks, key = { it.id }) { track ->
                        val isChecked = selectedIds.contains(track.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isChecked) selectedIds.remove(track.id)
                                    else selectedIds.add(track.id)
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) selectedIds.add(track.id)
                                    else selectedIds.remove(track.id)
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (track.durationSeconds > 0) {
                                        val min = track.durationSeconds / 60
                                        val sec = track.durationSeconds % 60
                                        Text(
                                            text = " · ${String.format("%d:%02d", min, sec)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (track.isAlreadyInLibrary) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = successColor().copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.playlist_in_library_short),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = successColor(),
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedIds.toList()) },
                enabled = selectedIds.isNotEmpty()
            ) {
                Text(stringResource(R.string.collection_download_count, selectedIds.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
fun EditMetadataDialog(
    initialTitle: String,
    initialArtist: String,
    initialAlbum: String,
    initialStartTimeSeconds: Long = 0L,
    initialEndTimeSeconds: Long = 0L,
    onDismiss: () -> Unit,
    onConfirm: (title: String, artist: String, album: String, startTimeSec: Long, endTimeSec: Long) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var album by remember { mutableStateOf(initialAlbum) }
    var startTimeText by remember {
        mutableStateOf(if (initialStartTimeSeconds > 0) com.nasmusicdrop.app.data.audio.YouTubeExtractor.formatTimestamp(initialStartTimeSeconds) else "00:00")
    }
    var endTimeText by remember {
        mutableStateOf(if (initialEndTimeSeconds > 0) com.nasmusicdrop.app.data.audio.YouTubeExtractor.formatTimestamp(initialEndTimeSeconds) else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.metadata_dialog_title), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.metadata_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.metadata_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(stringResource(R.string.metadata_artist_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text(stringResource(R.string.metadata_album_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startTimeText,
                        onValueChange = { startTimeText = it },
                        label = { Text(stringResource(R.string.metadata_start_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = endTimeText,
                        onValueChange = { endTimeText = it },
                        label = { Text(stringResource(R.string.metadata_end_label)) },
                        placeholder = { Text("MM:SS") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && artist.isNotBlank()) {
                        val parsedStart = com.nasmusicdrop.app.data.audio.YouTubeExtractor.parseTimestampToSeconds(startTimeText)
                        val parsedEnd = if (endTimeText.isNotBlank()) com.nasmusicdrop.app.data.audio.YouTubeExtractor.parseTimestampToSeconds(endTimeText) else 0L
                        onConfirm(title.trim(), artist.trim(), album.trim(), parsedStart, parsedEnd)
                    }
                },
                enabled = title.isNotBlank() && artist.isNotBlank()
            ) {
                Text(stringResource(R.string.metadata_apply_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}


@Composable
fun YouTubeManualPickerDialog(
    initialQuery: String,
    onDismiss: () -> Unit,
    onSelectVideo: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<com.nasmusicdrop.app.data.model.SearchResultItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        results = com.nasmusicdrop.app.data.audio.YouTubeExtractor.searchYouTube(query)
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.picker_dialog_title), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.picker_query_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            if (query.isNotBlank()) {
                                coroutineScope.launch {
                                    isLoading = true
                                    results = com.nasmusicdrop.app.data.audio.YouTubeExtractor.searchYouTube(query)
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.nav_search), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (results.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.picker_no_videos),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(results, key = { it.videoId }) { item ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectVideo(item.videoId) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = item.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = item.uploader,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (item.durationSeconds > 0) {
                                                Text(
                                                    text = " · ${com.nasmusicdrop.app.data.audio.YouTubeExtractor.formatTimestamp(item.durationSeconds)}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}
