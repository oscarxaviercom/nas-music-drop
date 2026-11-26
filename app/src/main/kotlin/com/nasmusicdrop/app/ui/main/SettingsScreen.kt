package com.nasmusicdrop.app.ui.main

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusicdrop.app.data.model.NasConfig
import com.nasmusicdrop.app.ui.theme.AppAccentOptions

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onShowMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val currentConfig by viewModel.nasConfig.collectAsState()
    val isTesting by viewModel.isTestingConnection.collectAsState()
    val testResult by viewModel.connectionTestResult.collectAsState()

    var destinationMode by remember(currentConfig) { mutableStateOf(currentConfig.destinationMode) }
    var tailscaleIp by remember(currentConfig) { mutableStateOf(currentConfig.tailscaleIp) }
    var localIp by remember(currentConfig) { mutableStateOf(currentConfig.localIp) }
    var smbPortText by remember(currentConfig) { mutableStateOf(currentConfig.smbPort.toString()) }
    var username by remember(currentConfig) { mutableStateOf(currentConfig.username) }
    var password by remember(currentConfig) { mutableStateOf(currentConfig.password) }
    var shareName by remember(currentConfig) { mutableStateOf(currentConfig.shareName) }
    var baseAudioPath by remember(currentConfig) { mutableStateOf(currentConfig.baseAudioPath) }
    var normalizeVolume by remember(currentConfig) { mutableStateOf(currentConfig.normalizeVolume) }
    var targetLufs by remember(currentConfig) { mutableDoubleStateOf(currentConfig.targetLufs) }
    var autoSelectBestIp by remember(currentConfig) { mutableStateOf(currentConfig.autoSelectBestIp) }
    var checkDuplicates by remember(currentConfig) { mutableStateOf(currentConfig.checkDuplicates) }
    var saveFolderCoverJpg by remember(currentConfig) { mutableStateOf(currentConfig.saveFolderCoverJpg) }
    var folderStructure by remember(currentConfig) { mutableStateOf(currentConfig.folderStructure) }
    var bitrateKbps by remember(currentConfig) { mutableIntStateOf(currentConfig.bitrateKbps) }
    var themeMode by remember(currentConfig) { mutableStateOf(currentConfig.themeMode) }
    var accentColor by remember(currentConfig) { mutableStateOf(currentConfig.accentColor) }
    var wifiOnly by remember(currentConfig) { mutableStateOf(currentConfig.wifiOnly) }
    var durationToleranceSeconds by remember(currentConfig) { mutableIntStateOf(currentConfig.durationToleranceSeconds) }
    var fileNameFormat by remember(currentConfig) { mutableStateOf(currentConfig.fileNameFormat) }
    var autoClearCompleted by remember(currentConfig) { mutableStateOf(currentConfig.autoClearCompleted) }

    var isPasswordVisible by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showUserGuideDialog by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(viewModel.getCacheSizeBytes()) }

    val isNasMode = destinationMode == "NAS"

    fun buildConfig() = NasConfig(
        destinationMode = destinationMode,
        tailscaleIp = tailscaleIp.trim(),
        localIp = localIp.trim(),
        smbPort = smbPortText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 445,
        username = username.trim(),
        password = password,
        shareName = shareName.trim(),
        baseAudioPath = baseAudioPath.trim(),
        normalizeVolume = normalizeVolume,
        targetLufs = targetLufs,
        bitrateKbps = bitrateKbps,
        autoSelectBestIp = autoSelectBestIp,
        checkDuplicates = checkDuplicates,
        saveFolderCoverJpg = saveFolderCoverJpg,
        organizeByAlbum = folderStructure == "ARTIST_ALBUM",
        folderStructure = folderStructure,
        themeMode = themeMode,
        accentColor = accentColor,
        wifiOnly = wifiOnly,
        durationToleranceSeconds = durationToleranceSeconds,
        fileNameFormat = fileNameFormat,
        autoClearCompleted = autoClearCompleted
    )

    fun autoSave() {
        viewModel.saveConfig(buildConfig())
    }

    val isNasDirty = tailscaleIp.trim() != currentConfig.tailscaleIp ||
            localIp.trim() != currentConfig.localIp ||
            (smbPortText.trim().toIntOrNull()?.coerceIn(1, 65535) ?: 445) != currentConfig.smbPort ||
            username.trim() != currentConfig.username ||
            password != currentConfig.password ||
            shareName.trim() != currentConfig.shareName ||
            baseAudioPath.trim() != currentConfig.baseAudioPath

    val autoSaveOnBlur = Modifier.onFocusChanged { focusState ->
        if (!focusState.isFocused && isNasDirty) {
            autoSave()
        }
    }

    testResult?.let { result ->
        ConnectionTestDialog(
            result = result,
            onDismiss = { viewModel.dismissConnectionTestDialog() }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Cabecera
        item {
            Column {
                Text(
                    text = "Ajustes",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Personaliza el aspecto, almacenamiento, red y calidad de audio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 1. APARIENCIA
        item {
            SettingsSectionCard(
                title = "Apariencia",
                icon = when (themeMode) {
                    "LIGHT" -> Icons.Default.LightMode
                    "DARK" -> Icons.Default.DarkMode
                    else -> Icons.Default.BrightnessAuto
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val options = listOf(
                        "LIGHT" to "☀️ Día",
                        "DARK" to "🌙 Noche",
                        "SYSTEM" to "📱 Auto"
                    )
                    options.forEach { (modeValue, label) ->
                        FilterChip(
                            selected = themeMode == modeValue,
                            onClick = {
                                themeMode = modeValue
                                viewModel.saveConfig(currentConfig.copy(themeMode = modeValue))
                            },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Color principal:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppAccentOptions.forEach { option ->
                        val isSelected = accentColor == option.id
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(option.swatchColor)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    accentColor = option.id
                                    viewModel.saveConfig(currentConfig.copy(accentColor = option.id))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = option.label,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. DESTINO Y RED
        item {
            SettingsSectionCard(
                title = "Destino y Red",
                icon = Icons.Default.Storage
            ) {
                Text(
                    text = "Elige dónde se guardarán las canciones descargadas:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isNasMode,
                        onClick = { destinationMode = "LOCAL"; autoSave() },
                        label = { Text("📱 En móvil", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isNasMode,
                        onClick = { destinationMode = "NAS"; autoSave() },
                        label = { Text("🖥️ Servidor NAS", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                }

                AnimatedVisibility(visible = !isNasMode) {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Las canciones se guardarán directamente en la carpeta Música de tu teléfono, listas para escuchar sin conexión.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = isNasMode) {
                    Column {
                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Conexión SMB al NAS",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = tailscaleIp,
                                onValueChange = { tailscaleIp = it },
                                label = { Text("IP Remota / Tailscale") },
                                placeholder = { Text("100.x.x.x o dominio") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).then(autoSaveOnBlur),
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                            OutlinedTextField(
                                value = smbPortText,
                                onValueChange = { v -> smbPortText = v.filter { it.isDigit() }.take(5) },
                                label = { Text("Puerto") },
                                placeholder = { Text("445") },
                                singleLine = true,
                                modifier = Modifier.width(96.dp).then(autoSaveOnBlur),
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = localIp,
                            onValueChange = { localIp = it },
                            label = { Text("IP Local (Wi‑Fi de casa)") },
                            placeholder = { Text("192.168.1.x") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().then(autoSaveOnBlur),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Usuario SMB") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().then(autoSaveOnBlur),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Contraseña") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().then(autoSaveOnBlur),
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Mostrar contraseña"
                                    )
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = shareName,
                            onValueChange = { shareName = it },
                            label = { Text("Recurso compartido") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().then(autoSaveOnBlur),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = baseAudioPath,
                            onValueChange = { baseAudioPath = it },
                            label = { Text("Carpeta base") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().then(autoSaveOnBlur),
                            shape = RoundedCornerShape(12.dp)
                        )

                        AnimatedVisibility(visible = isNasDirty) {
                            Column {
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        autoSave()
                                        onShowMessage("✓ Configuración NAS guardada")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Guardar configuración NAS")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        SettingsSwitchRow(
                            title = "Conmutación automática de IP",
                            subtitle = "Usa Wi‑Fi local si está en casa o Tailscale si estás fuera.",
                            checked = autoSelectBestIp,
                            onCheckedChange = { autoSelectBestIp = it; autoSave() }
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = {
                                val tempConfig = buildConfig()
                                viewModel.saveConfig(tempConfig)
                                viewModel.testNasConnection()
                            },
                            enabled = !isTesting,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Probando conexión...")
                            } else {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Probar conexión NAS")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(4.dp))

                SettingsSwitchRow(
                    title = "Solo descargar con Wi‑Fi",
                    subtitle = "Evita gastar datos móviles al bajar canciones o playlists grandes.",
                    checked = wifiOnly,
                    onCheckedChange = { wifiOnly = it; autoSave() }
                )
            }
        }

        // 3. ORGANIZACIÓN Y ARCHIVOS
        item {
            SettingsSectionCard(
                title = "Organización y Archivos",
                icon = Icons.Default.FolderCopy
            ) {
                Text(
                    text = "Formato de nombre de archivo:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = fileNameFormat == "ARTIST_TITLE",
                        onClick = { fileNameFormat = "ARTIST_TITLE"; autoSave() },
                        label = { Text("Artista - Título", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = fileNameFormat == "TITLE_ONLY",
                        onClick = { fileNameFormat = "TITLE_ONLY"; autoSave() },
                        label = { Text("Solo título", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Organización de carpetas:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = folderStructure == "NONE",
                        onClick = { folderStructure = "NONE"; autoSave() },
                        label = { Text("Sin carpetas", fontSize = 12.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = folderStructure == "ARTIST",
                        onClick = { folderStructure = "ARTIST"; autoSave() },
                        label = { Text("Artista", fontSize = 12.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = folderStructure == "ARTIST_ALBUM",
                        onClick = { folderStructure = "ARTIST_ALBUM"; autoSave() },
                        label = { Text("Artista/Álbum", fontSize = 12.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.weight(1f)
                    )
                }

                val sampleRoot = if (isNasMode) baseAudioPath.trim().ifBlank { "Audio" } else "Music"
                val sampleFile = if (fileNameFormat == "TITLE_ONLY") "The Nights.mp3" else "Avicii - The Nights.mp3"
                val samplePath = when (folderStructure) {
                    "NONE" -> "$sampleRoot/$sampleFile"
                    "ARTIST_ALBUM" -> "$sampleRoot/Avicii/Stories/$sampleFile"
                    else -> "$sampleRoot/Avicii/$sampleFile"
                }
                Text(
                    text = "Ejemplo: $samplePath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (folderStructure == "NONE" && fileNameFormat == "TITLE_ONLY") {
                    Text(
                        text = "⚠️ Con «Sin carpetas» y «Solo título», canciones con el mismo nombre podrían sobrescribirse.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingsSwitchRow(
                    title = "Comprobar duplicados antes de bajar",
                    subtitle = "Avisa u omite canciones que ya existen en tu biblioteca.",
                    checked = checkDuplicates,
                    onCheckedChange = { checkDuplicates = it; autoSave() }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                val isFlatFolder = folderStructure == "NONE"
                SettingsSwitchRow(
                    title = "Guardar carátula externa (cover.jpg)",
                    subtitle = if (isFlatFolder)
                        "Omitido automáticamente en modo «Sin carpetas» (cada MP3 ya lleva su carátula)."
                    else
                        "Crea cover.jpg en la carpeta para reproductores compatibles.",
                    checked = if (isFlatFolder) false else saveFolderCoverJpg,
                    enabled = !isFlatFolder,
                    onCheckedChange = { saveFolderCoverJpg = it; autoSave() }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingsSwitchRow(
                    title = "Auto-limpiar completadas",
                    subtitle = "Quita de la cola las canciones al terminar (se guardan en Historial).",
                    checked = autoClearCompleted,
                    onCheckedChange = { autoClearCompleted = it; autoSave() }
                )
            }
        }

        // 4. AUDIO Y COINCIDENCIAS
        item {
            SettingsSectionCard(
                title = "Audio y Coincidencias",
                icon = Icons.Default.GraphicEq
            ) {
                SettingsSwitchRow(
                    title = "Normalizar volumen (EBU R128)",
                    subtitle = "Equilibra todas las pistas a ${targetLufs.toInt()} LUFS sin saturación.",
                    checked = normalizeVolume,
                    onCheckedChange = { normalizeVolume = it; autoSave() }
                )

                if (normalizeVolume) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            -16.0 to "-16 (Suave)",
                            -14.0 to "-14 (Estándar)",
                            -11.0 to "-11 (Potente)"
                        ).forEach { (lufsVal, label) ->
                            FilterChip(
                                selected = targetLufs == lufsVal,
                                onClick = { targetLufs = lufsVal; autoSave() },
                                label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val lufsDescription = when (targetLufs) {
                        -16.0 -> "-16 LUFS (Suave): conserva más rango dinámico y matices. Ideal para acústica, clásica, jazz o escucha en equipo Hi-Fi / auriculares."
                        -11.0 -> "-11 LUFS (Potente): volumen más alto y con más presencia (con limitador anti-clipping). Recomendado para coche, altavoces portátiles o entornos ruidosos."
                        else -> "-14 LUFS (Estándar): referencia de plataformas como Spotify y YouTube. Mejor equilibrio entre volumen uniforme y calidad dinámica."
                    }
                    Text(
                        text = lufsDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Calidad de codificación MP3:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = bitrateKbps == 192,
                        onClick = { bitrateKbps = 192; autoSave() },
                        label = { Text("192 kbps", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = bitrateKbps == 320,
                        onClick = { bitrateKbps = 320; autoSave() },
                        label = { Text("320 kbps", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Margen de duración en playlists (Spotify / Apple / Deezer):",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Descarta vídeos de YouTube que se desvíen más de este tiempo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(10, 15, 25, 30).forEach { sec ->
                        FilterChip(
                            selected = durationToleranceSeconds == sec,
                            onClick = { durationToleranceSeconds = sec; autoSave() },
                            label = { Text("±${sec}s", maxLines = 1) }
                        )
                    }
                }
            }
        }

        // 5. ACCIONES Y ACERCA DE
        item {
            Button(
                onClick = {
                    autoSave()
                    onShowMessage("Ajustes guardados correctamente")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Guardar ajustes", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            val cacheMbText = String.format(java.util.Locale.US, "%.1f MB", cacheBytes / (1024.0 * 1024.0))
            OutlinedButton(
                onClick = {
                    viewModel.clearAppCache { freed ->
                        cacheBytes = viewModel.getCacheSizeBytes()
                        val freedMb = String.format(java.util.Locale.US, "%.1f MB", freed / (1024.0 * 1024.0))
                        onShowMessage("Caché vaciada ($freedMb liberados)")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Limpiar caché temporal ($cacheMbText)")
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { showClearHistoryDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Vaciar historial de descargas")
            }

            if (showClearHistoryDialog) {
                AlertDialog(
                    onDismissRequest = { showClearHistoryDialog = false },
                    title = { Text("Vaciar historial") },
                    text = { Text("¿Deseas vaciar el historial de descargas completadas y fallidas? Los archivos ya guardados en tu música o NAS no se borrarán.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.clearHistory()
                                showClearHistoryDialog = false
                                Toast.makeText(context, "Historial vaciado", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Vaciar", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearHistoryDialog = false }) {
                            Text("Cancelar")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            FilledTonalButton(
                onClick = { showUserGuideDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Guía de uso y funciones")
            }

            if (showUserGuideDialog) {
                UserGuideDialog(onDismiss = { showUserGuideDialog = false })
            }

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🎵 Music Drop v2.2.0",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Desarrollado por oscarxaviercom",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun UserGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Guía de uso de Music Drop", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GuideSection(
                    title = "1. Descargar desde enlaces y playlists",
                    body = "• Pega cualquier enlace en «Descargar» o usa «Compartir → Music Drop» desde YouTube, Spotify, Apple Music, Deezer, Amazon Music o SoundCloud.\n" +
                        "• En playlists y álbumes, la app comprueba antes tu biblioteca y marca automáticamente solo las canciones que te faltan (botón «Solo faltantes»).\n" +
                        "• Si una URL de YouTube lleva marca de tiempo (?t=...), se aplica como inicio de recorte.\n" +
                        "• En la cola puedes usar «Pausar cola / Reanudar cola», ver el progreso global, editar Título/Artista/Álbum, recortar Inicio/Fin, cambiar la edición en «Versiones» o editar/buscar letras en «Letra»."
                )
                GuideSection(
                    title = "2. Buscar y reconocer por micrófono",
                    body = "• En «Buscar» puedes buscar en YouTube, preescuchar tocando ▶ sobre la miniatura y recortar con un toque desde el segundo actual de reproducción.\n" +
                        "• Pulsa el botón del micrófono (🎙️) para escuchar ~5,5 segundos de música ambiente e identificar automáticamente la canción (100% gratis, sin cuentas) para descargarla o ver sus versiones."
                )
                GuideSection(
                    title = "3. Historial y mini-reproductor",
                    body = "• En «Historial» puedes buscar y reproducir tus canciones ya guardadas (tanto locales como desde el NAS).\n" +
                        "• Toca cualquier canción para ver sus «Detalles»: carátula, letra incrustada, verificación de normalización LUFS, tamaño y ruta completa (toca la ruta para copiarla).\n" +
                        "• El mini-reproductor inferior permite avanzar/retroceder (⏮ / ⏭) entre el historial con salto automático y tocar el título para volver a la lista."
                )
                GuideSection(
                    title = "4. Calidad de audio y organización",
                    body = "• Todo se normaliza sin saturación (limitador true-peak) al nivel LUFS elegido:\n" +
                        "  - -16 (Suave): mayor dinámica natural (clásica, acústica, Hi-Fi).\n" +
                        "  - -14 (Estándar): estándar de Spotify/YouTube; equilibrio ideal.\n" +
                        "  - -11 (Potente): más volumen y pegada (coche o altavoces Bluetooth).\n" +
                        "• Codificación MP3 192k o 320k, con etiquetas ID3v2.3, carátula 600×600 y letra incrustada.\n" +
                        "• Si recortas el final de una canción, se aplica un fade-out suave automático de 250 ms.\n" +
                        "• En «Ajustes» puedes elegir guardar en tu NAS (conmutación automática Local/Remota) o en la carpeta Music/ del móvil, sin carpetas, por Artista o Artista/Álbum."
                )
                GuideSection(
                    title = "5. Conexión al NAS (Local, VPN o puerto SMB)",
                    body = "• IP Local (puerto 445): úsala para subir rápido cuando estés en el Wi‑Fi de casa.\n" +
                        "• IP Remota / Tailscale + Puerto (445 por defecto): sirve para Tailscale, otra VPN (WireGuard/ZeroTier) o una IP/dominio externo (DDNS) con puerto redirigido en tu router (también admite formato host:puerto).\n" +
                        "• Por seguridad, si conectas desde fuera de casa se recomienda usar VPN (Tailscale/WireGuard) en lugar de exponer SMB directamente a internet."
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Entendido")
            }
        }
    )
}

@Composable
private fun GuideSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp
        )
    }
}
