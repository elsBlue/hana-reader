package com.hana.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hana.reader.tts.HanaPlayer
import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoiceCatalog
import com.hana.reader.tts.VoicePrefs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Calm Voice settings: Speed (live rate), Pace (lengthScale), Texture / Breath
 * (noiseScale / noiseScaleW). No pitch — Piper offline VITS does not expose it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsSheet(
    language: String,
    voiceId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember { HanaPlayer.get(context) }
    val prefs = remember { player.voicePreferences() }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val voice = VoiceCatalog.find(voiceId)
    val label = voice?.label ?: "Soft"

    var rate by remember(voiceId) { mutableFloatStateOf(prefs.rateForVoice(voiceId)) }
    var length by remember(voiceId) {
        mutableFloatStateOf(prefs.acousticForVoice(voiceId).lengthScale)
    }
    var noise by remember(voiceId) {
        mutableFloatStateOf(prefs.acousticForVoice(voiceId).noiseScale)
    }
    var noiseW by remember(voiceId) {
        mutableFloatStateOf(prefs.acousticForVoice(voiceId).noiseScaleW)
    }
    var applying by remember { mutableStateOf(false) }

    fun currentAcoustic() = TtsPacks.Acoustic(
        lengthScale = length,
        noiseScale = noise,
        noiseScaleW = noiseW,
    )

    ModalBottomSheet(
        onDismissRequest = { if (!applying) onDismiss() },
        sheetState = sheetState,
        containerColor = PaperElevated,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "VOICE SETTINGS",
                color = Muted,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                label,
                fontFamily = FontFamily.Serif,
                fontSize = 24.sp,
                color = Ink,
            )
            Text(
                "Raise Pace / Breath for more character; lower Texture if you hear grain. Pitch isn’t available for offline Hana voices.",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            VoiceSlider(
                title = "Speed",
                subtitle = "How quickly Hana speaks (live)",
                value = rate,
                valueRange = VoicePrefs.RATE_MIN..VoicePrefs.RATE_MAX,
                valueLabel = String.format("%.2f×", rate),
                enabled = !applying,
                onValueChange = { rate = (it * 100).roundToInt() / 100f },
                onValueChangeFinished = {
                    player.setRate(rate, persist = true)
                },
            )

            VoiceSlider(
                title = "Pace",
                subtitle = "Story pace — lower is a little faster",
                value = length,
                valueRange = VoicePrefs.LENGTH_MIN..VoicePrefs.LENGTH_MAX,
                valueLabel = String.format("%.2f", length),
                enabled = !applying,
                onValueChange = { length = (it * 100).roundToInt() / 100f },
                onValueChangeFinished = {
                    if (applying) return@VoiceSlider
                    applying = true
                    scope.launch {
                        runCatching {
                            player.applyAcousticSettings(language, voiceId, currentAcoustic())
                        }
                        applying = false
                    }
                },
            )

            VoiceSlider(
                title = "Texture",
                subtitle = "Buzz / grain — lower is cleaner",
                value = noise,
                valueRange = VoicePrefs.NOISE_MIN..VoicePrefs.NOISE_MAX,
                valueLabel = String.format("%.2f", noise),
                enabled = !applying,
                onValueChange = { noise = (it * 100).roundToInt() / 100f },
                onValueChangeFinished = {
                    if (applying) return@VoiceSlider
                    applying = true
                    scope.launch {
                        runCatching {
                            player.applyAcousticSettings(language, voiceId, currentAcoustic())
                        }
                        applying = false
                    }
                },
            )

            VoiceSlider(
                title = "Breath",
                subtitle = "Soft breathiness around words",
                value = noiseW,
                valueRange = VoicePrefs.NOISE_W_MIN..VoicePrefs.NOISE_W_MAX,
                valueLabel = String.format("%.2f", noiseW),
                enabled = !applying,
                onValueChange = { noiseW = (it * 100).roundToInt() / 100f },
                onValueChangeFinished = {
                    if (applying) return@VoiceSlider
                    applying = true
                    scope.launch {
                        runCatching {
                            player.applyAcousticSettings(language, voiceId, currentAcoustic())
                        }
                        applying = false
                    }
                },
            )

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    if (applying) return@TextButton
                    applying = true
                    scope.launch {
                        runCatching {
                            player.resetVoiceSettings(language, voiceId)
                            rate = prefs.rateForVoice(voiceId)
                            val a = prefs.acousticForVoice(voiceId)
                            length = a.lengthScale
                            noise = a.noiseScale
                            noiseW = a.noiseScaleW
                        }
                        applying = false
                    }
                },
                enabled = !applying,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Reset to defaults", color = Rose)
            }
            Button(
                onClick = onDismiss,
                enabled = !applying,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 16.dp),
            ) {
                Text(if (applying) "Applying…" else "Done")
            }
        }
    }
}

@Composable
private fun VoiceSlider(
    title: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            Text(valueLabel, color = Rose, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Rose,
                activeTrackColor = Rose,
                inactiveTrackColor = Subtle,
                disabledThumbColor = Rose.copy(alpha = 0.4f),
                disabledActiveTrackColor = Rose.copy(alpha = 0.4f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
