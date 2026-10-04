package org.coresense.itantra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
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
import org.coresense.itantra.ui.MainViewModel
import org.coresense.itantra.ui.i18n.AppLocalization
import org.coresense.itantra.ui.theme.DarkSurface
import org.coresense.itantra.ui.theme.DarkSurfaceVariant
import org.coresense.itantra.ui.theme.PrimaryNeonGreen
import org.coresense.itantra.ui.theme.SecondaryTeal

@Composable
fun LanguageScreen(
    viewModel: MainViewModel,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentLang by viewModel.selectedLanguage.collectAsState()
    val strings = remember(currentLang) { AppLocalization.getStrings(currentLang) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = SecondaryTeal,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Select Language",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Language.entries.chunked(2).forEach { rowLangs ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowLangs.forEach { lang ->
                                    val isSelected = currentLang == lang
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) PrimaryNeonGreen.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                            .border(
                                                1.dp,
                                                if (isSelected) PrimaryNeonGreen else Color.Transparent,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                viewModel.setAppLanguage(lang)
                                            }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            val greeting = when (lang) {
                                                Language.HINDI -> "नमस्ते"
                                                Language.ENGLISH -> "Hello"
                                                Language.BENGALI -> "নমস্কার"
                                                Language.TAMIL -> "வணக்கம்"
                                                Language.TELUGU -> "నమస్కారం"
                                                Language.MARATHI -> "नमस्कार"
                                                Language.GUJARATI -> "નમસ્તે"
                                                Language.KANNADA -> "ನಮಸ್ಕಾರ"
                                                Language.MALAYALAM -> "നമസ്കാരം"
                                                Language.PUNJABI -> "ਸਤਿ ਸ਼੍ਰੀ"
                                                Language.ODIA -> "ନମସ୍କାର"
                                            }
                                            Text(
                                                text = greeting,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isSelected) PrimaryNeonGreen else SecondaryTeal
                                            )
                                            Text(
                                                text = "${lang.nativeName} (${lang.displayName})",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isSelected) Color.White else Color.LightGray
                                            )
                                        }
                                    }
                                }
                                repeat(2 - rowLangs.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeonGreen)
            ) {
                Text(
                    text = "Next",
                    color = DarkSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}
