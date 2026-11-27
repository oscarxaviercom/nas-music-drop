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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusicdrop.app.R
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
    var autoCheckUpdates by remember(currentConfig) { mutableStateOf(currentConfig.autoCheckUpdates) }
    val updateState by viewModel.updateState.collectAsState()

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
        autoClearCompleted = autoClearCompleted,
        autoCheckUpdates = autoCheckUpdates
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
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 1. APARIENCIA
        item {
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_appearance),
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
                        "LIGHT" to stringResource(R.string.settings_theme_light),
                        "DARK" to stringResource(R.string.settings_theme_dark),
                        "SYSTEM" to stringResource(R.string.settings_theme_system)
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
                    text = stringResource(R.string.settings_accent_color),
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
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val isDynamicSelected = accentColor == "DYNAMIC"
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .then(
                                    if (isDynamicSelected) {
                                        Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    accentColor = "DYNAMIC"
                                    viewModel.saveConfig(currentConfig.copy(accentColor = "DYNAMIC"))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isDynamicSelected) Icons.Default.Check else Icons.Default.Palette,
                                contentDescription = "Material You",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. DESTINO Y RED
        item {
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_dest_net),
                icon = Icons.Default.Storage
            ) {
                Text(
                    text = stringResource(R.string.settings_dest_prompt),
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
                        label = { Text(stringResource(R.string.settings_dest_mobile), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = isNasMode,
                        onClick = { destinationMode = "NAS"; autoSave() },
                        label = { Text(stringResource(R.string.settings_dest_nas), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                                text = stringResource(R.string.settings_dest_local_info),
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
                            text = stringResource(R.string.settings_nas_conn_title),
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
                                label = { Text(stringResource(R.string.settings_remote_ip_label)) },
                                placeholder = { Text(stringResource(R.string.settings_remote_ip_placeholder)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).then(autoSaveOnBlur),
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                            OutlinedTextField(
                                value = smbPortText,
                                onValueChange = { v -> smbPortText = v.filter { it.isDigit() }.take(5) },
                                label = { Text(stringResource(R.string.settings_port_label)) },
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
                            label = { Text(stringResource(R.string.settings_local_ip_label)) },
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
                            label = { Text(stringResource(R.string.settings_smb_user_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().then(autoSaveOnBlur),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.settings_password_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().then(autoSaveOnBlur),
                            shape = RoundedCornerShape(12.dp),
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = stringResource(R.string.settings_show_password)
                                    )
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = shareName,
                            onValueChange = { shareName = it },
                            label = { Text(stringResource(R.string.settings_share_name_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().then(autoSaveOnBlur),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = baseAudioPath,
                            onValueChange = { baseAudioPath = it },
                            label = { Text(stringResource(R.string.settings_base_path_label)) },
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
                                        onShowMessage(context.getString(R.string.settings_nas_saved_toast))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.settings_save_nas_btn))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_auto_ip_title),
                            subtitle = stringResource(R.string.settings_auto_ip_subtitle),
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
                                Text(stringResource(R.string.settings_testing_conn))
                            } else {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_test_conn_btn))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(4.dp))

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_wifi_only_title),
                    subtitle = stringResource(R.string.settings_wifi_only_subtitle),
                    checked = wifiOnly,
                    onCheckedChange = { wifiOnly = it; autoSave() }
                )
            }
        }

        // 3. ORGANIZACIÓN Y ARCHIVOS
        item {
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_org),
                icon = Icons.Default.FolderCopy
            ) {
                Text(
                    text = stringResource(R.string.settings_filename_format),
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
                        label = { Text(stringResource(R.string.settings_format_artist_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = fileNameFormat == "TITLE_ONLY",
                        onClick = { fileNameFormat = "TITLE_ONLY"; autoSave() },
                        label = { Text(stringResource(R.string.settings_format_title_only), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.settings_folder_org),
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
                        label = { Text(stringResource(R.string.settings_folder_none), fontSize = 12.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = folderStructure == "ARTIST",
                        onClick = { folderStructure = "ARTIST"; autoSave() },
                        label = { Text(stringResource(R.string.settings_folder_artist), fontSize = 12.sp, maxLines = 1, softWrap = false) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = folderStructure == "ARTIST_ALBUM",
                        onClick = { folderStructure = "ARTIST_ALBUM"; autoSave() },
                        label = { Text(stringResource(R.string.settings_folder_artist_album), fontSize = 12.sp, maxLines = 1, softWrap = false) },
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
                    text = stringResource(R.string.settings_folder_example, samplePath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (folderStructure == "NONE" && fileNameFormat == "TITLE_ONLY") {
                    Text(
                        text = stringResource(R.string.settings_folder_flat_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_check_dups_title),
                    subtitle = stringResource(R.string.settings_check_dups_subtitle),
                    checked = checkDuplicates,
                    onCheckedChange = { checkDuplicates = it; autoSave() }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                val isFlatFolder = folderStructure == "NONE"
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_cover_jpg_title),
                    subtitle = if (isFlatFolder)
                        stringResource(R.string.settings_cover_jpg_flat_sub)
                    else
                        stringResource(R.string.settings_cover_jpg_sub),
                    checked = if (isFlatFolder) false else saveFolderCoverJpg,
                    enabled = !isFlatFolder,
                    onCheckedChange = { saveFolderCoverJpg = it; autoSave() }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_auto_clear_title),
                    subtitle = stringResource(R.string.settings_auto_clear_sub),
                    checked = autoClearCompleted,
                    onCheckedChange = { autoClearCompleted = it; autoSave() }
                )
            }
        }

        // 4. AUDIO Y COINCIDENCIAS
        item {
            SettingsSectionCard(
                title = stringResource(R.string.settings_section_audio),
                icon = Icons.Default.GraphicEq
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_norm_title),
                    subtitle = stringResource(R.string.settings_norm_subtitle, targetLufs.toInt()),
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
                            -16.0 to stringResource(R.string.settings_lufs_soft),
                            -14.0 to stringResource(R.string.settings_lufs_standard),
                            -11.0 to stringResource(R.string.settings_lufs_loud)
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
                        -16.0 -> stringResource(R.string.settings_lufs_desc_soft)
                        -11.0 -> stringResource(R.string.settings_lufs_desc_loud)
                        else -> stringResource(R.string.settings_lufs_desc_standard)
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
                    text = stringResource(R.string.settings_mp3_quality),
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
                    text = stringResource(R.string.settings_playlist_tolerance),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_playlist_tolerance_sub),
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
                    onShowMessage(context.getString(R.string.settings_saved_msg))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_save_btn), fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            val cacheMbText = String.format(java.util.Locale.US, "%.1f MB", cacheBytes / (1024.0 * 1024.0))
            OutlinedButton(
                onClick = {
                    viewModel.clearAppCache { freed ->
                        cacheBytes = viewModel.getCacheSizeBytes()
                        val freedMb = String.format(java.util.Locale.US, "%.1f MB", freed / (1024.0 * 1024.0))
                        onShowMessage(context.getString(R.string.settings_cache_cleared, freedMb))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_clear_cache_btn, cacheMbText))
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
                Text(stringResource(R.string.settings_clear_history_btn))
            }

            if (showClearHistoryDialog) {
                AlertDialog(
                    onDismissRequest = { showClearHistoryDialog = false },
                    title = { Text(stringResource(R.string.settings_clear_history_title)) },
                    text = { Text(stringResource(R.string.settings_clear_history_msg)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.clearHistory()
                                showClearHistoryDialog = false
                                Toast.makeText(context, context.getString(R.string.settings_history_cleared_toast), Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(stringResource(R.string.settings_clear_btn), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearHistoryDialog = false }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            SettingsSwitchRow(
                title = stringResource(R.string.settings_auto_update_title),
                subtitle = stringResource(R.string.settings_auto_update_sub),
                checked = autoCheckUpdates,
                onCheckedChange = { autoCheckUpdates = it; autoSave() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            val isCheckingUpdates = updateState is MainViewModel.UpdateUiState.Checking
            OutlinedButton(
                onClick = { viewModel.checkForUpdates(manual = true) },
                enabled = !isCheckingUpdates,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isCheckingUpdates) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_checking_updates))
                } else {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_check_updates_btn))
                }
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
                Text(stringResource(R.string.settings_user_guide_btn))
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
                        text = stringResource(R.string.settings_about_version),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_about_author),
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
                Text(stringResource(R.string.guide_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                    title = stringResource(R.string.guide_s1_title),
                    body = stringResource(R.string.guide_s1_body)
                )
                GuideSection(
                    title = stringResource(R.string.guide_s2_title),
                    body = stringResource(R.string.guide_s2_body)
                )
                GuideSection(
                    title = stringResource(R.string.guide_s3_title),
                    body = stringResource(R.string.guide_s3_body)
                )
                GuideSection(
                    title = stringResource(R.string.guide_s4_title),
                    body = stringResource(R.string.guide_s4_body)
                )
                GuideSection(
                    title = stringResource(R.string.guide_s5_title),
                    body = stringResource(R.string.guide_s5_body)
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.common_got_it))
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
