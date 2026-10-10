package org.coresense.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.protocol.Language
import org.coresense.itantra.stt.DownloadState
import org.coresense.itantra.stt.LanguageDownloadConfig
import org.coresense.itantra.stt.ModelMetadata
import org.coresense.itantra.stt.ModelStatus
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.DarkSurfaceVariant
import org.coresense.itantra.ui.theme.PrimaryNeonGreen
import org.coresense.itantra.ui.theme.SecondaryTeal

@Composable
fun ManageLanguagesScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val modelsMap by viewModel.modelRegistry.models.collectAsState()
    val downloadStates by viewModel.downloadManager.downloadStates.collectAsState()
    val activeLang by viewModel.selectedLanguage.collectAsState()

    var langToDelete by remember { mutableStateOf<Language?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    val totalStorageUsedMb = remember(modelsMap) {
        modelsMap.values.sumOf { it.actualSizeMb.toDouble() }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "MANAGE OFFLINE LANGUAGES",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Text(
                        text = "11 Supported Languages • Pre-bundled & On-Demand",
                        fontSize = 11.sp,
                        color = PrimaryNeonGreen
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Storage Overview Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "OFFLINE STORAGE USAGE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Text(
                                text = String.format("%.1f MB", totalStorageUsedMb),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryNeonGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• English & Hindi: Pre-bundled permanent assets inside APK\n• 9 Indian Languages: Download individually on-demand to conserve storage",
                            fontSize = 11.sp,
                            color = Color.LightGray,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Section Header
            item {
                Text(
                    text = "ALL LANGUAGES (11)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Language Items
            items(Language.entries) { lang ->
                val meta = modelsMap[lang]
                val dlState = downloadStates[lang] ?: DownloadState.Idle
                val isBundled = viewModel.modelRegistry.isBundled(lang)
                val isInstalled = viewModel.modelRegistry.isModelInstalled(lang)
                val isActive = (activeLang == lang)

                LanguageManageCard(
                    language = lang,
                    metadata = meta,
                    downloadState = dlState,
                    isBundled = isBundled,
                    isInstalled = isInstalled,
                    isActive = isActive,
                    onDownload = {
                        viewModel.downloadLanguage(lang)
                    },
                    onCancel = {
                        viewModel.cancelLanguageDownload(lang)
                    },
                    onDelete = {
                        langToDelete = lang
                    },
                    onSelectActive = {
                        val switched = viewModel.setAppLanguage(lang)
                        if (!switched) {
                            snackbarMessage = "Cannot select ${lang.displayName}: model not installed."
                        }
                    }
                )
            }
        }
    }

    // Confirmation dialog for deletion
    if (langToDelete != null) {
        val target = langToDelete!!
        AlertDialog(
            onDismissRequest = { langToDelete = null },
            title = { Text("Delete Language Model?") },
            text = {
                Text("Are you sure you want to delete the offline model for ${target.displayName} (${target.nativeName})? This will free storage space. You can re-download it anytime.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val res = viewModel.deleteLanguage(target)
                        if (res.isFailure) {
                            snackbarMessage = res.exceptionOrNull()?.message ?: "Failed to delete"
                        } else {
                            snackbarMessage = "Deleted ${target.displayName} model."
                        }
                        langToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { langToDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = DarkSurface,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    if (snackbarMessage != null) {
        LaunchedEffect(snackbarMessage) {
            kotlinx.coroutines.delay(2500)
            snackbarMessage = null
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = snackbarMessage!!,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
fun LanguageManageCard(
    language: Language,
    metadata: ModelMetadata?,
    downloadState: DownloadState,
    isBundled: Boolean,
    isInstalled: Boolean,
    isActive: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelectActive: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) DarkSurfaceVariant else DarkSurface
        ),
        shape = RoundedCornerShape(8.dp),
        border = if (isActive) androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen) else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Names and Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${language.displayName} (${language.nativeName})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(PrimaryNeonGreen.copy(alpha = 0.2f))
                                    .border(0.8.dp, PrimaryNeonGreen, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text("ACTIVE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = PrimaryNeonGreen)
                            }
                        }
                    }
                    Text(
                        text = if (isBundled) "Core Bundled • ${metadata?.engineName ?: "ONNX STT"}" else "On-Demand • ${metadata?.engineName ?: "ONNX STT"}",
                        fontSize = 10.sp,
                        color = Color.LightGray
                    )
                }

                // Status Badge
                val badgeText: String
                val badgeColor: Color
                when {
                    isBundled && isInstalled -> {
                        badgeText = "INCLUDED & READY"
                        badgeColor = PrimaryNeonGreen
                    }
                    isBundled && !isInstalled -> {
                        badgeText = "EXTRACTION ERROR"
                        badgeColor = Color(0xFFFF5252)
                    }
                    downloadState is DownloadState.Downloading -> {
                        badgeText = "DOWNLOADING"
                        badgeColor = Color(0xFFFFD700)
                    }
                    downloadState is DownloadState.Verifying -> {
                        badgeText = "VERIFYING"
                        badgeColor = Color(0xFF00E5FF)
                    }
                    downloadState is DownloadState.Error -> {
                        badgeText = "ERROR"
                        badgeColor = Color(0xFFFF5252)
                    }
                    isInstalled -> {
                        badgeText = "INSTALLED & READY"
                        badgeColor = PrimaryNeonGreen
                    }
                    LanguageDownloadConfig.isAvailable(language) -> {
                        badgeText = "NOT INSTALLED"
                        badgeColor = Color.LightGray
                    }
                    else -> {
                        badgeText = "COMING SOON"
                        badgeColor = Color(0xFFFFB300)
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .border(0.8.dp, badgeColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Body / Actions Row
            when {
                downloadState is DownloadState.Downloading -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${downloadState.bytesDownloaded / (1024 * 1024)} MB / ${downloadState.totalBytes / (1024 * 1024)} MB",
                                fontSize = 10.sp,
                                color = Color.LightGray
                            )
                            Text(
                                text = "${downloadState.progressPercent}%",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryNeonGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { downloadState.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = PrimaryNeonGreen,
                            trackColor = DarkSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.align(Alignment.End),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text("Cancel", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }

                downloadState is DownloadState.Verifying -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF00E5FF)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Verifying SHA-256 and ONNX session compatibility...",
                            fontSize = 11.sp,
                            color = Color(0xFF00E5FF)
                        )
                    }
                }

                downloadState is DownloadState.Error -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Error: ${downloadState.message}",
                            fontSize = 11.sp,
                            color = Color(0xFFFF5252)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text("Retry Download", fontSize = 11.sp, color = DarkSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                isBundled -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format("Bundled (%.1f MB) • Permanent Offline", metadata?.actualSizeMb ?: 0f),
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                        if (!isActive && isInstalled) {
                            Button(
                                onClick = onSelectActive,
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                            ) {
                                Text("Select", fontSize = 11.sp, color = DarkSurface, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                isInstalled -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format("Installed (%.1f MB)", metadata?.actualSizeMb ?: 0f),
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = onDelete,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete", fontSize = 11.sp)
                            }
                            if (!isActive) {
                                Button(
                                    onClick = onSelectActive,
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                ) {
                                    Text("Select", fontSize = 11.sp, color = DarkSurface, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                LanguageDownloadConfig.isAvailable(language) -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format("Download Size: ~%.0f MB", metadata?.estimatedSizeMb ?: 80f),
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp), tint = DarkSurface)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download", fontSize = 11.sp, color = DarkSurface, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Checkpoint packaging in progress • ~80 MB",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.DarkGray)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Coming Soon", fontSize = 10.sp, color = Color.LightGray)
                        }
                    }
                }
            }
        }
    }
}
