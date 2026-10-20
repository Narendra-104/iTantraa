package org.coresense.itantra.frontend.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.coresense.itantra.core.protocol.Language
import org.coresense.itantra.backend.speech.TtsStatus
import org.coresense.itantra.frontend.viewmodel.MainViewModel
import org.coresense.itantra.frontend.theme.DarkSurface
import org.coresense.itantra.frontend.theme.DarkSurfaceVariant
import org.coresense.itantra.frontend.theme.PrimaryNeonGreen

/**
 * Step 11: Dedicated Offline Text-to-Speech (TTS) Studio Screen.
 * Provides user-facing offline speech synthesis controls:
 * - Multiline text input
 * - Speak, Stop, and Clear controls
 * - Dynamic offline voice availability checks across all 11 supported Indic + English languages
 * - Quick single-tap offline sample phrases
 * - Intent to configure Android System offline voice data
 * - Auto-read incoming messages toggle
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentLang by viewModel.selectedLanguage.collectAsState()
    val isTtsSpeaking by viewModel.isTtsSpeaking.collectAsState()
    val isTtsReady by viewModel.isTtsReady.collectAsState()
    val ttsStatusMsg by viewModel.ttsStatusMessage.collectAsState()
    val isAutoTts by viewModel.isAutoTtsEnabled.collectAsState()

    var customText by remember { mutableStateOf("") }
    var langDropdownExpanded by remember { mutableStateOf(false) }

    val currentVoiceStatus = remember(currentLang, isTtsReady) {
        viewModel.getTtsLanguageStatus(currentLang)
    }
    val isCurrentVoiceOfflineReady = currentVoiceStatus == TtsStatus.SYSTEM_TTS_OFFLINE ||
            currentVoiceStatus == TtsStatus.OFFLINE_READY

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Header with Back button and Settings action
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.Black
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "TEXT-TO-SPEECH (TTS) STUDIO",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Text(
                            text = "Step 11 • Offline Speech Synthesis",
                            fontSize = 11.sp,
                            color = PrimaryNeonGreen
                        )
                    }
                }

                IconButton(
                    onClick = { openAndroidTtsSettings(context) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "System TTS Settings",
                        tint = Color.LightGray
                    )
                }
            }
        }

        // 2. Status & Engine Readiness Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isTtsSpeaking) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = if (isTtsSpeaking) PrimaryNeonGreen else Color.LightGray,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "TTS Engine Status",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }

                        // Status badge
                        Surface(
                            color = when {
                                isTtsSpeaking -> PrimaryNeonGreen.copy(alpha = 0.2f)
                                isCurrentVoiceOfflineReady -> Color(0xFF00E676).copy(alpha = 0.15f)
                                else -> Color(0xFFFF5252).copy(alpha = 0.15f)
                            },
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                when {
                                    isTtsSpeaking -> PrimaryNeonGreen
                                    isCurrentVoiceOfflineReady -> Color(0xFF00E676)
                                    else -> Color(0xFFFF5252)
                                }
                            )
                        ) {
                            Text(
                                text = when {
                                    isTtsSpeaking -> "SPEAKING"
                                    isCurrentVoiceOfflineReady -> "OFFLINE READY"
                                    else -> "NO OFFLINE VOICE"
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isTtsSpeaking -> PrimaryNeonGreen
                                    isCurrentVoiceOfflineReady -> Color(0xFF00E676)
                                    else -> Color(0xFFFF5252)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Message: ${ttsStatusMsg ?: if (isTtsSpeaking) "Synthesizing voice..." else "Ready"}",
                        fontSize = 12.sp,
                        color = if (isTtsSpeaking) PrimaryNeonGreen else Color.LightGray
                    )
                }
            }
        }

        // 3. Language Selector Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "TARGET VOICE LANGUAGE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Box {
                        OutlinedButton(
                            onClick = { langDropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.Start) {
                                    Text(
                                        text = "${currentLang.displayName} (${currentLang.nativeName})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "BCP-47: ${currentLang.bcp47} • ${currentVoiceStatus.name}",
                                        fontSize = 10.sp,
                                        color = if (isCurrentVoiceOfflineReady) PrimaryNeonGreen else Color(0xFFFF9800)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Language",
                                    tint = PrimaryNeonGreen
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = langDropdownExpanded,
                            onDismissRequest = { langDropdownExpanded = false },
                            modifier = Modifier.background(DarkSurfaceVariant)
                        ) {
                            Language.entries.forEach { lang ->
                                val status = viewModel.getTtsLanguageStatus(lang)
                                val isAvail = status == TtsStatus.SYSTEM_TTS_OFFLINE || status == TtsStatus.OFFLINE_READY
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = "${lang.displayName} (${lang.nativeName})",
                                                    fontWeight = if (lang == currentLang) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (lang == currentLang) PrimaryNeonGreen else Color.White
                                                )
                                                Text(
                                                    text = if (isAvail) "Offline Ready" else "No offline voice",
                                                    fontSize = 10.sp,
                                                    color = if (isAvail) Color(0xFF00E676) else Color(0xFFFF9800)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        langDropdownExpanded = false
                                        viewModel.setAppLanguage(lang)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Multiline Text Input Field
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "INPUT TEXT FOR SYNTHESIS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        Text(
                            text = "${customText.length} chars",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 110.dp, max = 200.dp),
                        placeholder = {
                            Text(
                                text = "Enter message or alert text to speak offline in ${currentLang.displayName}...",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryNeonGreen,
                            unfocusedBorderColor = Color.DarkGray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action buttons: Speak, Stop, Clear
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // SPEAK Button
                        Button(
                            onClick = {
                                viewModel.speakCustomText(customText, currentLang)
                            },
                            modifier = Modifier
                                .weight(1.5f)
                                .height(46.dp),
                            enabled = customText.isNotBlank() && !isTtsSpeaking,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryNeonGreen,
                                contentColor = DarkSurface,
                                disabledContainerColor = Color.DarkGray,
                                disabledContentColor = Color.LightGray
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Speak",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SPEAK",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        // STOP Button
                        Button(
                            onClick = {
                                viewModel.stopPlayback()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            enabled = isTtsSpeaking,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD32F2F),
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF4A1818),
                                disabledContentColor = Color.Gray
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "STOP",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        // CLEAR Button
                        OutlinedButton(
                            onClick = {
                                customText = ""
                                viewModel.stopPlayback()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            enabled = customText.isNotBlank() || isTtsSpeaking,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.LightGray,
                                disabledContentColor = Color.DarkGray
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "CLEAR",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // 5. Offline Voice Guidance & Settings Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isCurrentVoiceOfflineReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isCurrentVoiceOfflineReady) PrimaryNeonGreen else Color(0xFFFF9800),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isCurrentVoiceOfflineReady) {
                                "Offline Voice Verified for ${currentLang.displayName}"
                            } else {
                                "Offline Voice Setup Required"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (isCurrentVoiceOfflineReady) {
                            "iTantra enforces 100% offline speech synthesis. The selected voice for ${currentLang.displayName} does not require an active internet connection."
                        } else {
                            "No verified offline voice is installed on this device for ${currentLang.displayName}. Please install offline voice data in Android Text-to-Speech Settings to enable speech synthesis for this language."
                        },
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { openAndroidTtsSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkSurfaceVariant,
                            contentColor = PrimaryNeonGreen
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = PrimaryNeonGreen
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Open Android TTS Settings",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 6. Auto-Read Incoming Messages Switch Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Read Incoming Messages",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                        Text(
                            text = "Plays voice synthesis automatically when peer or department messages arrive",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = isAutoTts,
                        onCheckedChange = { viewModel.toggleAutoTts(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DarkSurface,
                            checkedTrackColor = PrimaryNeonGreen
                        )
                    )
                }
            }
        }

        // 7. Quick Sample Phrases for all 11 Languages
        item {
            Text(
                text = "OFFLINE TEST PHRASES (11 LANGUAGES)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray
            )
        }

        items(ttsSamplePhrases) { sample ->
            val isSampleForCurrentLang = sample.language == currentLang
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        customText = sample.phrase
                        if (sample.language != currentLang) {
                            viewModel.setAppLanguage(sample.language)
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSampleForCurrentLang) DarkSurfaceVariant else DarkSurface
                ),
                shape = RoundedCornerShape(8.dp),
                border = if (isSampleForCurrentLang) {
                    androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeonGreen.copy(alpha = 0.5f))
                } else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${sample.language.displayName} (${sample.language.nativeName})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSampleForCurrentLang) PrimaryNeonGreen else Color.LightGray
                            )
                            if (isSampleForCurrentLang) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ACTIVE",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryNeonGreen
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = sample.phrase,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            customText = sample.phrase
                            if (sample.language != currentLang) {
                                viewModel.setAppLanguage(sample.language)
                            }
                            viewModel.speakCustomText(sample.phrase, sample.language)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Speak Phrase",
                            tint = PrimaryNeonGreen
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Sample test phrases across all 11 supported languages.
 */
data class TtsSamplePhrase(
    val language: Language,
    val phrase: String
)

val ttsSamplePhrases = listOf(
    TtsSamplePhrase(Language.HINDI, "नमस्ते! आई-तंत्रा ऑफ़लाइन आपातकालीन संचार प्रणाली है।"),
    TtsSamplePhrase(Language.ENGLISH, "iTantra offline emergency communication system operational."),
    TtsSamplePhrase(Language.MARATHI, "नमस्कार! आय-तंतरा ही ऑफलाइन आपत्कालीन संप्रेषण यंत्रणा आहे."),
    TtsSamplePhrase(Language.BENGALI, "নমস্কার! আই-তন্ত্র একটি অফলাইন জরুরি যোগাযোগ ব্যবস্থা।"),
    TtsSamplePhrase(Language.TAMIL, "வணக்கம்! ஐ-தந்த்ரா ஆஃப்லைன் அவசரக்கால தொடர்பு அமைப்பு."),
    TtsSamplePhrase(Language.TELUGU, "నమస్కారం! ఐ-తంత్ర ఆఫ్ లైన్ అత్యవసర సమాచార వ్యవస్థ."),
    TtsSamplePhrase(Language.GUJARATI, "નમસ્તે! આઇ-તંત્ર ઑફલાઇન કટોકટી સંદેશાવ્યવહાર સિસ્ટમ છે."),
    TtsSamplePhrase(Language.KANNADA, "ನಮಸ್ಕಾರ! ಐ-ತಂತ್ರ ಆಫ್‌ಲೈನ್ ತುರ್ತು ಸಂವಹನ ವ್ಯವಸ್ಥೆ."),
    TtsSamplePhrase(Language.MALAYALAM, "നമസ്കാരം! ഐ-തന്ത്ര ഒരു ഓഫ്‌ലൈൻ അടിയന്തര ആശയവിനിമയ സംവിധാനമാണ്."),
    TtsSamplePhrase(Language.PUNJABI, "ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ! ਆਈ-ਤੰਤਰਾ ਇੱਕ ਔਫਲਾਈਨ ਸੰਕਟਕਾਲੀਨ ਸੰਚਾਰ ਪ੍ਰਣਾਲੀ ਹੈ।"),
    TtsSamplePhrase(Language.ODIA, "ନମସ୍କାର! ଆଇ-ତନ୍ତ୍ରା ଏକ ଅଫଲାଇନ ଜରୁରୀକାଳୀନ ଯୋଗାଯୋଗ ବ୍ୟବସ୍ଥା।")
)

/**
 * Launches Android TTS settings to enable downloading offline voice files.
 */
fun openAndroidTtsSettings(context: Context) {
    try {
        val ttsIntent = Intent("com.android.settings.TTS_SETTINGS").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(ttsIntent)
    } catch (_: ActivityNotFoundException) {
        try {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(settingsIntent)
        } catch (_: Exception) {}
    }
}
