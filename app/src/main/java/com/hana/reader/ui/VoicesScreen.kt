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
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.hana.reader.tts.TtsDownloadState
import com.hana.reader.tts.TtsPack
import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoiceCatalog
import com.hana.reader.tts.VoiceProfile
import com.hana.reader.tts.VoiceSwitchLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoicesScreen(nav: NavHostController) {
    val context = LocalContext.current
    val player = remember { HanaPlayer.get(context) }
    val prefs = remember { player.voicePreferences() }
    val models = remember { player.modelManager() }
    val scope = rememberCoroutineScope()

    var langTab by remember { mutableStateOf("en") }
    var selectedId by remember { mutableStateOf(prefs.selectedVoiceId(langTab)) }
    var readyKeys by remember { mutableStateOf(emptySet<String>()) }
    var bytesByKey by remember { mutableStateOf(emptyMap<String, Long>()) }
    var incompleteKeys by remember { mutableStateOf(emptySet<String>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var showVoiceSettings by remember { mutableStateOf(false) }

    val download by models.downloadState.collectAsState()

    fun refresh() {
        val keys = TtsPacks.all().map { it.storageKey }
        readyKeys = keys.filter { models.isReady(it) }.toSet()
        bytesByKey = keys.associateWith { models.installedBytes(it) }
        incompleteKeys = keys.filter { models.isIncomplete(it) }.toSet()
        selectedId = prefs.selectedVoiceId(langTab)
    }

    LaunchedEffect(Unit) { refresh() }

    LaunchedEffect(langTab) {
        selectedId = prefs.selectedVoiceId(langTab)
    }

    LaunchedEffect(download) {
        when (val d = download) {
            is TtsDownloadState.Ready -> {
                refresh()
                status = "Ready"
                val finishedKey = d.language
                val selected = prefs.selectedVoiceId(langTab)
                if (VoiceSwitchLogic.shouldWarmPrepareAfterDownload(finishedKey, selected)) {
                    val lang = TtsPacks.packForVoice(selected)?.language
                        ?: TtsPacks.forLanguage(finishedKey)?.language
                        ?: finishedKey
                    runCatching { player.warmPrepare(lang) }
                }
            }
            is TtsDownloadState.Failed -> {
                status = d.message
                refresh()
            }
            is TtsDownloadState.Downloading -> {
                status = d.stage
            }
            else -> Unit
        }
    }

    val packs = TtsPacks.packsForLanguage(langTab)
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
            IconButton(onClick = { showVoiceSettings = true }) {
                Icon(Icons.Default.Tune, contentDescription = "Voice settings", tint = Ink)
            }
        }

        Text(
            "Smooth is the offline English voice — slower, with pauses. Tweak Pace and Texture for more soul.",
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        TextButton(
            onClick = { showVoiceSettings = true },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, tint = Rose, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Voice settings — speed, pace, texture", color = Rose)
        }

        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                "English",
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Ink)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                color = Paper,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(packs, key = { it.storageKey }) { pack ->
                val key = pack.storageKey
                val downloadingThis = download is TtsDownloadState.Downloading &&
                    (download as TtsDownloadState.Downloading).language == key
                val downloadProgress = (download as? TtsDownloadState.Downloading)
                    ?.takeIf { it.language == key }?.progress ?: 0f
                val downloadStage = (download as? TtsDownloadState.Downloading)
                    ?.takeIf { it.language == key }?.stage
                val failedMsg = (download as? TtsDownloadState.Failed)
                    ?.takeIf { it.language == key }?.message
                PackCard(
                    pack = pack,
                    ready = key in readyKeys,
                    bytes = bytesByKey[key] ?: 0L,
                    incomplete = key in incompleteKeys,
                    downloading = downloadingThis,
                    progress = downloadProgress,
                    stage = downloadStage,
                    failedMsg = failedMsg,
                    onDownload = {
                        if (downloadingThis) return@PackCard
                        status = "Connecting…"
                        models.startDownload(key)
                    },
                    onRemove = {
                        models.deletePack(key)
                        player.releaseNeuralPack(pack.packId)
                        status = "${pack.displayName} removed"
                        refresh()
                    }
                )
            }
            item {
                Text(
                    "VOICES",
                    color = Muted,
                    fontSize = 11.sp,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(voices, key = { it.id }) { voice ->
                val pack = TtsPacks.packById(voice.packId)
                val packKey = pack?.storageKey
                val packReady = packKey != null && packKey in readyKeys
                VoiceRow(
                    voice = voice,
                    selected = voice.id == selectedId,
                    packReady = packReady,
                    packLabel = pack?.displayName,
                    previewBusy = previewing,
                    onSelect = {
                        selectedId = voice.id
                        status = "Selected ${voice.label}"
                        when (VoiceSwitchLogic.onSelectAction(packReady)) {
                            VoiceSwitchLogic.SelectAction.DownloadOnly -> {
                                prefs.setSelectedVoiceId(langTab, voice.id)
                                if (packKey != null) models.startDownload(packKey)
                            }
                            VoiceSwitchLogic.SelectAction.SwitchVoice -> {
                                scope.launch {
                                    runCatching {
                                        player.switchVoice(langTab, voice.id)
                                    }.onFailure {
                                        status = it.message ?: "Switch failed"
                                    }
                                    selectedId = prefs.selectedVoiceId(langTab)
                                }
                            }
                        }
                    },
                    onPreview = {
                        if (!packReady || previewing || pack == null || packKey == null) return@VoiceRow
                        previewing = true
                        scope.launch {
                            runCatching {
                                player.previewVoice(voice.id)
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

    if (showVoiceSettings) {
        VoiceSettingsSheet(
            language = langTab,
            voiceId = selectedId,
            onDismiss = { showVoiceSettings = false },
        )
    }
}

@Composable
private fun PackCard(
    pack: TtsPack,
    ready: Boolean,
    bytes: Long,
    incomplete: Boolean,
    downloading: Boolean,
    progress: Float,
    stage: String?,
    failedMsg: String?,
    onDownload: () -> Unit,
    onRemove: () -> Unit
) {
    val blurb = when (pack.packId) {
        TtsPacks.EN_SMOOTH.packId -> "Piper Lessac · ~67 MB · slower, with pauses"
        else -> "Piper · English voice pack"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PaperElevated,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(pack.displayName, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Ink)
            Text(
                blurb,
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                when {
                    ready -> "Installed · ${formatMb(bytes)}"
                    incomplete && !downloading -> "Incomplete — tap to retry"
                    else -> "Not downloaded"
                },
                color = when {
                    ready -> Rose
                    incomplete -> Rose
                    else -> Muted
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (downloading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth(),
                    color = Rose,
                    trackColor = Subtle
                )
                Text(
                    stage ?: "Downloading…",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else if (failedMsg != null) {
                Text(
                    "Failed: $failedMsg",
                    color = Rose,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ready) {
                    Button(
                        onClick = onDownload,
                        enabled = !downloading,
                        colors = ButtonDefaults.buttonColors(containerColor = Rose),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                downloading -> "Downloading…"
                                failedMsg != null || incomplete -> "Retry"
                                else -> "Download pack"
                            }
                        )
                    }
                } else {
                    TextButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = Muted)
                        Spacer(Modifier.width(4.dp))
                        Text("Remove", color = Muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: CatalogVoice,
    selected: Boolean,
    packReady: Boolean,
    packLabel: String?,
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
                        Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Rose)
                    }
                }
                Text(
                    buildString {
                        append(voice.name)
                        if (!packLabel.isNullOrBlank()) {
                            append(" · ")
                            append(packLabel)
                        }
                    },
                    color = Muted,
                    fontSize = 11.sp
                )
                Text(voice.traits, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                if (!packReady) {
                    Text(
                        "Download ${packLabel ?: "pack"} to use this voice",
                        color = Rose,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
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
