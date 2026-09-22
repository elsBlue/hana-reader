package com.hana.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.hana.reader.tts.CatalogVoice
import com.hana.reader.tts.HanaPlayer
import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoiceCatalog
import com.hana.reader.tts.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoicesScreen(nav: NavHostController) {
    val context = LocalContext.current
    val player = remember { HanaPlayer.get(context) }
    val prefs = remember { player.voicePreferences() }
    val models = remember { player.modelManager() }
    val neural = remember { player.neuralEngine() }
    val scope = rememberCoroutineScope()

    var langTab by remember { mutableStateOf("en") }
    var selectedId by remember { mutableStateOf(prefs.selectedVoiceId(langTab)) }
    var enReady by remember { mutableStateOf(models.isReady("en")) }
    var idReady by remember { mutableStateOf(models.isReady("id")) }
    var enBytes by remember { mutableStateOf(models.installedBytes("en")) }
    var idBytes by remember { mutableStateOf(models.installedBytes("id")) }
    var downloadingLang by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf(false) }

    fun refresh() {
        enReady = models.isReady("en")
        idReady = models.isReady("id")
        enBytes = models.installedBytes("en")
        idBytes = models.installedBytes("id")
        selectedId = prefs.selectedVoiceId(langTab)
    }

    LaunchedEffect(langTab) {
        selectedId = prefs.selectedVoiceId(langTab)
    }

    val pack = TtsPacks.forLanguage(langTab)!!
    val packReady = if (langTab == "en") enReady else idReady
    val packBytes = if (langTab == "en") enBytes else idBytes
    val voices = VoiceCatalog.forLanguage(langTab)

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink)
            }
            Column(Modifier.weight(1f)) {
                Text("VOICES", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                Text("Choose how Hana reads", fontFamily = FontFamily.Serif, fontSize = 22.sp, color = Ink)
            }
        }

        Text(
            "Download a pack once, then pick a voice. Tap a row to make it active.",
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            listOf("en" to "English · Kokoro", "id" to "Indonesia · Piper").forEach { (key, label) ->
                val on = langTab == key
                Text(
                    label,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(CircleShape)
                        .background(if (on) Ink else Subtle)
                        .clickable {
                            langTab = key
                            selectedId = prefs.selectedVoiceId(key)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (on) Paper else Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Surface(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth(),
            color = PaperElevated,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 1.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(pack.displayName, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Ink)
                Text(
                    if (langTab == "en") "Kokoro fp32 · ~300 MB · offline after download"
                    else "Piper news · ~63 MB · offline after download",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    if (packReady) "Installed · ${formatMb(packBytes)}" else "Not downloaded",
                    color = if (packReady) Rose else Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (downloadingLang == langTab) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth(),
                        color = Rose,
                        trackColor = Subtle
                    )
                    Text(status ?: "Downloading…", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!packReady) {
                        Button(
                            onClick = {
                                if (downloadingLang != null) return@Button
                                downloadingLang = langTab
                                progress = 0f
                                status = "Starting…"
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            models.ensure(langTab) { p ->
                                                progress = p
                                                status = "Downloading… ${(p * 100).toInt()}%"
                                            }
                                        }
                                    }.onSuccess {
                                        status = "Ready"
                                        refresh()
                                    }.onFailure {
                                        status = it.message ?: "Download failed"
                                    }
                                    downloadingLang = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Rose),
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download pack")
                        }
                    } else {
                        TextButton(onClick = {
                            models.deletePack(langTab)
                            neural.release()
                            status = "Pack removed"
                            refresh()
                        }) {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = Muted)
                            Spacer(Modifier.width(4.dp))
                            Text("Remove", color = Muted)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            item {
                Text(
                    "VOICES IN THIS PACK",
                    color = Muted,
                    fontSize = 11.sp,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }
            items(voices, key = { it.id }) { voice ->
                VoiceRow(
                    voice = voice,
                    selected = voice.id == selectedId,
                    packReady = packReady,
                    previewBusy = previewing,
                    onSelect = {
                        prefs.setSelectedVoiceId(langTab, voice.id)
                        selectedId = voice.id
                        // Force Hana profile so neural path is preferred next listen
                        player.setProfile(VoiceProfile.Hana)
                        status = "Selected ${voice.label}"
                    },
                    onPreview = {
                        if (!packReady || previewing) return@VoiceRow
                        previewing = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.Default) {
                                    val files = models.files(langTab)
                                        ?: models.ensure(langTab) {}
                                    if (!neural.isLoaded(langTab)) neural.prepare(langTab, files)
                                    val sample = if (langTab == "id") {
                                        "Halo. Ini suara Hana untuk membaca buku secara offline."
                                    } else {
                                        "Hello. This is Hana, reading softly so long books feel easy."
                                    }
                                    val pcm = neural.synthesize(sample, langTab, voice.sid, TtsPacks.DEFAULT_RATE)
                                    neural.play(pcm)
                                }
                            }.onFailure {
                                status = it.message ?: "Preview failed"
                            }
                            previewing = false
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: CatalogVoice,
    selected: Boolean,
    packReady: Boolean,
    previewBusy: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (selected) Modifier.border(1.5.dp, Rose, RoundedCornerShape(18.dp))
                else Modifier
            )
            .clickable(onClick = onSelect),
        color = if (selected) Rose.copy(alpha = 0.08f) else PaperElevated,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = if (selected) 0.dp else 1.dp
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(voice.label, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Ink)
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Check, null, tint = Rose, modifier = Modifier.size(16.dp))
                    }
                }
                Text(voice.name, color = Muted, fontSize = 11.sp)
                Text(voice.traits, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            IconButton(
                onClick = onPreview,
                enabled = packReady && !previewBusy
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Preview ${voice.label}",
                    tint = if (packReady) Ink else Muted
                )
            }
        }
    }
}

private fun formatMb(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format("%.0f MB", mb)
}
