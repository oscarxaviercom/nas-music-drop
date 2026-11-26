package com.nasmusicdrop.app.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.nasmusicdrop.app.data.model.UploadHistoryItem
import com.nasmusicdrop.app.ui.theme.successColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onShowMessage: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val history by viewModel.uploadHistory.collectAsState()
    var selectedItemForDetails by remember { mutableStateOf<UploadHistoryItem?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var historySearchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    LaunchedEffect(historySearchQuery) {
        kotlinx.coroutines.delay(150)
        debouncedQuery = historySearchQuery.trim()
    }

    val filteredHistory = remember(history, debouncedQuery) {
        if (debouncedQuery.isBlank()) history
        else history.filter {
            it.title.contains(debouncedQuery, ignoreCase = true) ||
                it.artist.contains(debouncedQuery, ignoreCase = true) ||
                it.album.contains(debouncedQuery, ignoreCase = true)
        }
    }

    val currentPlayingId by viewModel.previewPlayer.currentTrackId.collectAsState()
    val isPlaying by viewModel.previewPlayer.isPlaying.collectAsState()
    val isLoadingAudio by viewModel.previewPlayer.isLoading.collectAsState()
    val currentPositionMs by viewModel.previewPlayer.currentPositionMs.collectAsState()
    val durationMs by viewModel.previewPlayer.durationMs.collectAsState()

    if (history.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.history_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.history_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.history_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.history_subtitle_count, history.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { showClearConfirmDialog = true },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.history_clear_cd),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = historySearchQuery,
                    onValueChange = { historySearchQuery = it },
                    placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (historySearchQuery.isNotBlank()) {
                            IconButton(onClick = { historySearchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (filteredHistory.isEmpty() && historySearchQuery.isNotBlank()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.history_search_empty, historySearchQuery),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(filteredHistory, key = { it.id }) { item ->
                val isCurrent = currentPlayingId == item.id
                val progress = if (isCurrent && durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                HistoryItemCard(
                    item = item,
                    isThisPlaying = isCurrent && isPlaying,
                    isThisLoading = isCurrent && isLoadingAudio,
                    playbackProgress = progress,
                    durationMs = if (isCurrent) durationMs else 0L,
                    isThisActive = isCurrent,
                    onPlayPause = { viewModel.playHistoryItem(item) },
                    onSeekTo = { targetMs ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.previewPlayer.seekTo(targetMs)
                    },
                    onClick = { selectedItemForDetails = item }
                )
            }
        }
    }

    // Diálogo de confirmación para vaciar todo el historial
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            icon = {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text(stringResource(R.string.history_clear_dialog_title))
            },
            text = {
                Text(stringResource(R.string.history_clear_dialog_msg))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearHistory()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.history_clear_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirmDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Diálogo detallado con la información y normalización de la canción
    selectedItemForDetails?.let { item ->
        val isCurrent = currentPlayingId == item.id
        SongDetailsDialog(
            item = item,
            isThisPlaying = isCurrent && isPlaying,
            isThisLoading = isCurrent && isLoadingAudio,
            onPlayPause = { viewModel.playHistoryItem(item) },
            onRequeue = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.requeueFromHistory(item) { ok ->
                    onShowMessage(
                        if (ok) context.getString(R.string.history_requeued, item.title)
                        else context.getString(R.string.history_requeue_not_found, item.title)
                    )
                }
                selectedItemForDetails = null
            },
            onDismiss = { selectedItemForDetails = null },
            onDelete = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.deleteHistoryItem(item.id)
                selectedItemForDetails = null
                onShowMessage(context.getString(R.string.history_deleted_msg))
            }
        )
    }
}

@Composable
fun HistoryItemCard(
    item: UploadHistoryItem,
    isThisPlaying: Boolean,
    isThisLoading: Boolean,
    playbackProgress: Float,
    durationMs: Long,
    isThisActive: Boolean,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val dateString = dateFormat.format(Date(item.timestamp))
    val sizeMb = String.format(Locale.US, "%.1f MB", item.fileSizeBytes / (1024.0 * 1024.0))

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
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
                Text(
                    text = "$dateString · $sizeMb · ${item.bitrateKbps}k",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isThisActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            ) {
                if (isThisLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isThisPlaying) stringResource(R.string.common_pause) else stringResource(R.string.common_play),
                        tint = if (isThisActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SongDetailsDialog(
    item: UploadHistoryItem,
    isThisPlaying: Boolean,
    isThisLoading: Boolean,
    onPlayPause: () -> Unit,
    onRequeue: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val datePattern = stringResource(R.string.history_date_pattern)
    val dateFormat = SimpleDateFormat(datePattern, Locale.getDefault())
    val dateString = dateFormat.format(Date(item.timestamp))
    val sizeMb = String.format(Locale.US, "%.2f MB", item.fileSizeBytes / (1024.0 * 1024.0))

    var showFullLyricsDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.history_details_delete))
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = successColor(),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.history_details_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Carátula grande e información principal
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = item.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(90.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.album.isNotBlank() && item.album != item.title) {
                            Text(
                                text = stringResource(R.string.history_details_album, item.album),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (item.year.isNotBlank() || item.genre.isNotBlank()) {
                            Text(
                                text = listOf(item.year, item.genre).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Botón para reproducir la canción guardada (local o desde el NAS)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onPlayPause,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isThisLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.common_loading), fontSize = 13.sp, maxLines = 1, softWrap = false)
                        } else {
                            Icon(
                                imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isThisPlaying) stringResource(R.string.common_pause) else stringResource(R.string.common_play),
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onRequeue,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.history_requeue_btn), fontSize = 13.sp, maxLines = 1, softWrap = false)
                    }
                }

                // Botón para ver la letra si está disponible
                if (!item.lyrics.isNullOrBlank()) {
                    Button(
                        onClick = { showFullLyricsDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Lyrics, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.history_view_lyrics_btn))
                    }
                }

                HorizontalDivider()

                // Sección 1: Verificación de Normalización y Audio
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.history_audio_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        DetailRow(
                            label = stringResource(R.string.history_label_format),
                            value = "MP3 ${item.bitrateKbps} kbps CBR (LAME)"
                        )
                        DetailRow(
                            label = stringResource(R.string.history_label_loudness),
                            value = "EBU R128 (${item.targetLufs} LUFS)"
                        )
                        DetailRow(
                            label = stringResource(R.string.history_label_orig_lufs),
                            value = "${String.format(Locale.US, "%.1f", item.originalLufs)} LUFS"
                        )
                        DetailRow(
                            label = stringResource(R.string.history_label_gain),
                            value = stringResource(
                                R.string.history_gain_value,
                                if (item.appliedGainDb >= 0) "+" else "",
                                String.format(Locale.US, "%.1f", item.appliedGainDb)
                            )
                        )
                        DetailRow(
                            label = stringResource(R.string.history_label_cover),
                            value = "${item.coverResolution} APIC"
                        )
                        DetailRow(
                            label = stringResource(R.string.history_label_lyrics),
                            value = if (item.hasLyrics) stringResource(R.string.history_lyrics_embedded) else stringResource(R.string.history_lyrics_unavailable)
                        )
                    }
                }

                // Sección 2: Destino y Almacenamiento en el NAS
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.history_location_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val detailClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                        val detailContext = androidx.compose.ui.platform.LocalContext.current
                        Text(
                            text = stringResource(R.string.history_remote_path_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.remotePath,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 16.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    detailClipboard.setText(androidx.compose.ui.text.AnnotatedString(item.remotePath))
                                    android.widget.Toast.makeText(detailContext, detailContext.getString(R.string.history_path_copied), android.widget.Toast.LENGTH_SHORT).show()
                                }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        DetailRow(
                            label = stringResource(R.string.history_label_size),
                            value = sizeMb
                        )
                        DetailRow(
                            label = stringResource(R.string.history_label_date),
                            value = dateString
                        )
                    }
                }
            }
        }
    )

    if (showFullLyricsDialog && !item.lyrics.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showFullLyricsDialog = false },
            confirmButton = {
                Button(onClick = { showFullLyricsDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lyrics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.history_lyrics_dialog_title), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = item.title,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = item.artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = item.lyrics,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp
                    )
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.widthIn(max = 175.dp)
        )
    }
}
