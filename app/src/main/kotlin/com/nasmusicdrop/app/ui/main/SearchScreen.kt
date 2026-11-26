package com.nasmusicdrop.app.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.nasmusicdrop.app.data.audio.YouTubeExtractor
import com.nasmusicdrop.app.data.model.SearchResultItem

@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onShowMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current

    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val recognitionState by viewModel.recognitionState.collectAsState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startAudioRecognition()
        } else {
            onShowMessage("Necesitas conceder permiso de micrófono para identificar canciones.")
        }
    }

    fun triggerAudioRecognition() {
        focusManager.clearFocus()
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            viewModel.startAudioRecognition()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var inputQuery by remember { mutableStateOf(searchQuery) }
    var selectedItemForDownload by remember { mutableStateOf<SearchResultItem?>(null) }

    val currentPlayingId by viewModel.previewPlayer.currentTrackId.collectAsState()
    val isPlaying by viewModel.previewPlayer.isPlaying.collectAsState()
    val isLoadingAudio by viewModel.previewPlayer.isLoading.collectAsState()
    val currentPositionMs by viewModel.previewPlayer.currentPositionMs.collectAsState()
    val durationMs by viewModel.previewPlayer.durationMs.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Explorar Música",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Busca, preescucha y recorta antes de descargar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Barra de búsqueda superior + Botón de micrófono para reconocer música
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputQuery,
                    onValueChange = { inputQuery = it },
                    placeholder = { Text("Buscar música (canción, artista)...") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (inputQuery.isNotBlank()) {
                            IconButton(onClick = {
                                inputQuery = ""
                                viewModel.clearSearch()
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpiar")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        if (inputQuery.isNotBlank()) {
                            viewModel.searchYouTube(inputQuery)
                        }
                    })
                )

                FilledTonalIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        triggerAudioRecognition()
                    },
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Identificar canción por micrófono",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    if (inputQuery.isNotBlank()) {
                        viewModel.searchYouTube(inputQuery)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = inputQuery.isNotBlank() && !isSearching
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Buscando música...")
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Buscar música")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Estado de carga o resultados
            if (isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Consultando catálogo musical...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Default.TravelExplore,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No se encontraron canciones" else "Explora y reproduce canciones",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotBlank())
                                "Prueba con otras palabras o el nombre exacto del artista."
                            else
                                "Busca cualquier canción para escucharla aquí mismo y confirmar que es la versión deseada antes de descargarla.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(searchResults, key = { it.videoId }) { item ->
                        Box(modifier = Modifier.animateItem()) {
                            SearchResultCard(
                                item = item,
                                isPlaying = currentPlayingId == item.videoId && isPlaying,
                                isLoading = currentPlayingId == item.videoId && isLoadingAudio,
                                onPlayPause = {
                                    viewModel.previewPlayer.playPreview(item.videoId, item.cleanTitle)
                                },
                                onDownload = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedItemForDownload = item
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Diálogo de descarga con opción de marca de tiempo (start offset)
    selectedItemForDownload?.let { track ->
        val currentCutSec = if (currentPlayingId == track.videoId && currentPositionMs > 1000) {
            currentPositionMs / 1000
        } else 0L

        DownloadWithTimestampDialog(
            track = track,
            initialTimestamp = if (currentCutSec > 0) YouTubeExtractor.formatTimestamp(currentCutSec) else "00:00",
            currentPreviewSec = currentCutSec,
            onDismiss = { selectedItemForDownload = null },
            onConfirm = { startTimeSec, endTimeSec ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.enqueueUrl(
                    rawUrl = "https://youtu.be/${track.videoId}",
                    startTimeSeconds = startTimeSec,
                    endTimeSeconds = endTimeSec,
                    initialTitle = track.cleanTitle,
                    initialArtist = track.uploader,
                    initialThumbnailUrl = track.thumbnailUrl
                )
                selectedItemForDownload = null
                onShowMessage("✓ \"${track.cleanTitle}\" añadida a la cola")
            }
        )
    }

    if (recognitionState != MainViewModel.RecognitionUiState.Idle) {
        AudioRecognitionDialog(
            state = recognitionState,
            onCancel = { viewModel.cancelAudioRecognition() },
            onDismiss = { viewModel.dismissAudioRecognition() },
            onRetry = { triggerAudioRecognition() },
            onSearchTrack = { track ->
                val q = "${track.artist} ${track.title}".trim()
                inputQuery = q
                viewModel.dismissAudioRecognition()
                viewModel.searchYouTube(q)
            },
            onDownloadTrack = { track ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.enqueueRecognizedTrack(track)
                onShowMessage("✓ Buscando \"${track.title}\" para descargar...")
            }
        )
    }
}

@Composable
fun AudioRecognitionDialog(
    state: MainViewModel.RecognitionUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSearchTrack: (com.nasmusicdrop.app.data.external.ExternalTrack) -> Unit,
    onDownloadTrack: (com.nasmusicdrop.app.data.external.ExternalTrack) -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (state is MainViewModel.RecognitionUiState.Listening) onCancel() else onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (state) {
                        is MainViewModel.RecognitionUiState.Listening -> "Escuchando música..."
                        is MainViewModel.RecognitionUiState.Identifying -> "Identificando canción..."
                        is MainViewModel.RecognitionUiState.MatchFound -> "¡Canción identificada!"
                        is MainViewModel.RecognitionUiState.NoMatch -> "Sin coincidencias"
                        is MainViewModel.RecognitionUiState.Error -> "No se pudo escuchar"
                        MainViewModel.RecognitionUiState.Idle -> ""
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            when (state) {
                is MainViewModel.RecognitionUiState.Listening -> {
                    val pulseScale by animateFloatAsState(
                        targetValue = 1f + (state.amplitude * 0.35f),
                        label = "micPulse"
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                            )
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(60.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Acerca el teléfono a la fuente de sonido...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is MainViewModel.RecognitionUiState.Identifying -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Analizando huella acústica...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is MainViewModel.RecognitionUiState.MatchFound -> {
                    val track = state.track
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = track.coverUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (track.album.isNotBlank()) {
                                Text(
                                    text = track.album,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                is MainViewModel.RecognitionUiState.NoMatch -> {
                    Text(
                        text = "No se pudo identificar ninguna canción. Prueba a acercar el móvil al altavoz o asegúrate de que no haya mucho ruido.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is MainViewModel.RecognitionUiState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                MainViewModel.RecognitionUiState.Idle -> {}
            }
        },
        confirmButton = {
            when (state) {
                is MainViewModel.RecognitionUiState.MatchFound -> {
                    Button(onClick = { onDownloadTrack(state.track) }) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Descargar")
                    }
                }
                is MainViewModel.RecognitionUiState.NoMatch,
                is MainViewModel.RecognitionUiState.Error -> {
                    Button(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reintentar")
                    }
                }
                is MainViewModel.RecognitionUiState.Listening -> {
                    TextButton(onClick = onCancel) { Text("Cancelar") }
                }
                is MainViewModel.RecognitionUiState.Identifying,
                MainViewModel.RecognitionUiState.Idle -> {}
            }
        },
        dismissButton = {
            when (state) {
                is MainViewModel.RecognitionUiState.MatchFound -> {
                    OutlinedButton(onClick = { onSearchTrack(state.track) }) {
                        Text("Buscar")
                    }
                }
                is MainViewModel.RecognitionUiState.NoMatch,
                is MainViewModel.RecognitionUiState.Error -> {
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
                else -> {}
            }
        }
    )
}

@Composable
fun SearchResultCard(
    item: SearchResultItem,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onDownload: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (isPlaying) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Miniatura con botón Play/Pausa superpuesto y duración en esquina
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = if (isPlaying) "Pausar" else "Reproducir preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (isPlaying || isLoading) 0.45f else 0.25f))
                )
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                tint = if (isPlaying) MaterialTheme.colorScheme.onPrimary else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (item.durationSeconds > 0) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                    ) {
                        Text(
                            text = YouTubeExtractor.formatTimestamp(item.durationSeconds),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.cleanTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    lineHeight = 18.sp,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.uploader,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Botón de descargar al NAS
            IconButton(
                onClick = onDownload,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Descargar",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun DownloadWithTimestampDialog(
    track: SearchResultItem,
    initialTimestamp: String,
    currentPreviewSec: Long,
    onDismiss: () -> Unit,
    onConfirm: (startTimeSec: Long, endTimeSec: Long) -> Unit
) {
    var startText by remember { mutableStateOf(initialTimestamp) }
    var endText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Descargar canción", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = track.cleanTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
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

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Recorte de inicio y fin (opcional):",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Indica los minutos/segundos (ej: 00:15) para cortar silencios, intros o créditos finales. Deja 00:00 para descargar completo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("Inicio") },
                        placeholder = { Text("00:00") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )

                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("Fin (opc)") },
                        placeholder = { Text("MM:SS") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                if (currentPreviewSec > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.clickable {
                            startText = YouTubeExtractor.formatTimestamp(currentPreviewSec)
                        }
                    ) {
                        Text(
                            text = "⏱️ Usar tiempo actual como inicio (${YouTubeExtractor.formatTimestamp(currentPreviewSec)})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val startSec = YouTubeExtractor.parseTimestampToSeconds(startText)
                    val endSec = if (endText.isNotBlank()) YouTubeExtractor.parseTimestampToSeconds(endText) else 0L
                    onConfirm(startSec, endSec)
                }
            ) {
                Text("Añadir a la cola")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
