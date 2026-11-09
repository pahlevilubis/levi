package com.pahlevi.levi.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.ai.ModelType
import com.pahlevi.levi.ai.Prompts
import com.pahlevi.levi.audio.Dictation
import com.pahlevi.levi.audio.RecorderService
import com.pahlevi.levi.audio.RecorderState
import com.pahlevi.levi.audio.TranscribeState
import com.pahlevi.levi.audio.WavRecorder
import com.pahlevi.levi.audio.formatDuration
import com.pahlevi.levi.data.NoteKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class DictationState {
    var text by mutableStateOf("")
    var language by mutableStateOf(AppGraph.prefs.dictationLanguage)
    var status by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var job: Job? = null
}

@Composable
fun RecordScreen(entry: Entry, screen: Screen.Record) {
    var tab by remember(entry) { mutableIntStateOf(screen.tab) }
    ScreenScaffold(topBar = { LeviTopBar("Rekam & Dikte") }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Dikte") }, icon = { Icon(Icons.Filled.Mic, null) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Rekaman") }, icon = { Icon(Icons.Filled.GraphicEq, null) })
            }
            if (tab == 0) DictationTab(entry) else RecordingsTab(entry)
        }
    }
}

// ---------------------------------------------------------------------------
// Dikte: ngomong langsung jadi teks
// ---------------------------------------------------------------------------

@Composable
private fun DictationTab(entry: Entry) {
    val st = remember(entry) { entry.keep("dict") { DictationState() } }
    val context = LocalContext.current
    val nav = LocalNav.current
    val dictation = remember { Dictation(context) }
    val dict by dictation.state.collectAsState()
    DisposableEffect(Unit) { onDispose { dictation.stop() } }

    val start = rememberMicPermission {
        dictation.start(st.language) { piece ->
            st.text = if (st.text.isBlank()) piece else st.text.trimEnd() + " " + piece
        }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!dictation.available) {
            ErrorText("Pengenal suara tidak ditemukan di HP ini. Pakai tab Rekaman lalu Transkrip.")
        }
        ChoiceChips(listOf("Indonesia", "Inggris"), if (st.language.startsWith("en")) "Inggris" else "Indonesia", {
            st.language = if (it == "Inggris") "en-US" else "id-ID"
            AppGraph.prefs.dictationLanguage = st.language
        })
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val levelSize = 96.dp + (32 * dict.level).dp
            Box(
                Modifier.size(levelSize).background(
                    if (dict.listening) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                FilledIconButton(
                    onClick = { if (dict.listening) dictation.stop() else start() },
                    modifier = Modifier.size(84.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (dict.listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(if (dict.listening) Icons.Filled.Stop else Icons.Filled.Mic, "Dikte", modifier = Modifier.size(40.dp))
                }
            }
        }
        Text(
            when {
                dict.listening && dict.partial.isNotBlank() -> dict.partial
                dict.listening -> "Silakan bicara…"
                else -> "Tekan mikrofon lalu bicara. Tekan lagi untuk berhenti."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        dict.error?.let { ErrorText(it) }
        OutlinedTextField(
            value = st.text,
            onValueChange = { st.text = it },
            label = { Text("Hasil dikte (bisa diedit)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        )
        StatusLine(st.status)
        ErrorText(st.error)
        if (st.text.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { copyText(context, st.text) }) { Text("Salin") }
                FilledTonalButton(onClick = {
                    AppGraph.notes.add("", st.text, NoteKind.DICTATION)
                    toast(context, "Disimpan ke Catatan")
                }) { Text("Simpan") }
                FilledTonalButton(onClick = { st.text = "" }) { Text("Kosongkan") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    enabled = st.job?.isActive != true,
                    onClick = {
                        val raw = st.text
                        st.job = entry.scope.launch {
                            st.error = null
                            st.status = "Merapikan tanda baca…"
                            try {
                                st.text = AppGraph.llm.complete(
                                    Prompts.chat(Prompts.TIDY_SYSTEM, raw),
                                    AppGraph.llm.defaultParams(maxTokens = 1200, temperature = 0.2f),
                                ) { st.status = null }
                                    .ifBlank { raw }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                st.error = e.message
                                st.text = raw
                            } finally {
                                st.status = null
                            }
                        }
                    },
                ) { Text("Rapikan (AI)") }
                FilledTonalButton(onClick = { nav.go(Screen.Summarize(st.text)) }) { Text("Ringkas") }
                FilledTonalButton(onClick = { nav.go(Screen.Translate(st.text)) }) { Text("Terjemah") }
            }
        }
        Text(
            "Dikte memakai pengenal suara bawaan HP. Supaya bisa tanpa internet, unduh bahasa Indonesia offline di " +
                "Pengaturan HP → Google → Ucapan / Pengenalan ucapan offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Rekaman panjang + transkrip dengan Whisper
// ---------------------------------------------------------------------------

private fun wavDurationMs(f: File): Long = ((f.length() - 44).coerceAtLeast(0) * 1000) / (WavRecorder.SAMPLE_RATE * 2)

@Composable
private fun RecordingsTab(entry: Entry) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val rec by RecorderState.state.collectAsState()
    val tr by TranscribeState.state.collectAsState()
    val files = remember { mutableStateListOf<File>() }
    var playing by remember { mutableStateOf<File?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    var needWhisper by remember { mutableStateOf(false) }

    fun refresh() {
        files.clear()
        files.addAll(
            RecorderState.recordingsDir(context).listFiles()
                ?.filter { it.extension == "wav" && it != rec.file }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        )
    }
    LaunchedEffect(rec.recording, rec.lastSaved) { refresh() }

    fun stopPlayer() {
        player[0]?.runCatching { stop(); release() }
        player[0] = null
        playing = null
    }
    DisposableEffect(Unit) { onDispose { stopPlayer() } }

    val startRec = rememberRecordPermissions { RecorderState.start(context) }
    var pendingTranscribe by remember { mutableStateOf<File?>(null) }
    val askNotif = rememberNotificationPermission {
        pendingTranscribe?.let { TranscribeState.start(context, it) }
        pendingTranscribe = null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- panel perekam ----
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatDuration(rec.elapsedMs),
                    style = MaterialTheme.typography.displaySmall,
                    color = if (rec.recording && !rec.paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                // indikator volume
                Box(
                    Modifier.fillMaxWidth().height(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                ) {
                    Box(
                        Modifier.fillMaxWidth(rec.level.coerceIn(0f, 1f)).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (rec.recording) {
                        FilledTonalButton(onClick = {
                            RecorderState.send(context, if (rec.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE)
                        }) {
                            Icon(if (rec.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (rec.paused) "Lanjut" else "Jeda")
                        }
                        Button(onClick = { RecorderState.send(context, RecorderService.ACTION_STOP) }) {
                            Icon(Icons.Filled.Stop, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Selesai")
                        }
                    } else {
                        FilledIconButton(
                            onClick = { stopPlayer(); startRec() },
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Icon(Icons.Filled.FiberManualRecord, "Mulai rekam", modifier = Modifier.size(36.dp)) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (rec.recording) "Rekaman tetap jalan walau layar mati. Hentikan dari sini atau dari notifikasi."
                    else "Cocok untuk rapat atau kuliah. Setelah selesai, tekan Transkrip untuk mengubahnya jadi teks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rec.error?.let { ErrorText(it) }
            }
        }

        // ---- status transkripsi ----
        if (tr.running || tr.doneNoteId != null || tr.error != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    if (tr.running) {
                        Text("Mentranskrip ${File(tr.file ?: "").nameWithoutExtension}… ${tr.progress}%", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(progress = { tr.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Text(
                            tr.text.takeLast(400).ifBlank { "Memuat model suara…" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Boleh tinggalkan halaman ini atau matikan layar; proses jalan di latar belakang.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        TextButton(onClick = { TranscribeState.stop(context) }) { Text("Hentikan (simpan yang sudah jadi)") }
                    } else {
                        tr.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (tr.doneNoteId != null) Text("Transkrip selesai dan disimpan di Catatan.", style = MaterialTheme.typography.titleSmall)
                        Row {
                            tr.doneNoteId?.let { id ->
                                TextButton(onClick = { TranscribeState.clearResult(); nav.go(Screen.NoteDetail(id)) }) { Text("Buka transkrip") }
                                TextButton(onClick = {
                                    val body = AppGraph.notes.get(id)?.body.orEmpty()
                                    TranscribeState.clearResult()
                                    nav.go(Screen.Summarize(body, Prompts.SummaryStyle.MINUTES))
                                }) { Text("Jadikan notulen") }
                            }
                            TextButton(onClick = { TranscribeState.clearResult() }) { Text("Tutup") }
                        }
                    }
                }
            }
        }

        // ---- daftar rekaman ----
        Text("Rekaman tersimpan", style = MaterialTheme.typography.titleSmall)
        if (files.isEmpty()) {
            Text("Belum ada rekaman.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        files.forEach { f ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)) {
                    Text(f.nameWithoutExtension.replace('_', ' '), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatDuration(wavDurationMs(f))} • ${"%.1f".format(f.length() / 1_048_576f)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            if (playing == f) stopPlayer() else {
                                stopPlayer()
                                runCatching {
                                    player[0] = MediaPlayer().apply {
                                        setDataSource(f.absolutePath)
                                        setOnCompletionListener { stopPlayer() }
                                        prepare()
                                        start()
                                    }
                                    playing = f
                                }.onFailure { toast(context, "Tidak bisa memutar rekaman") }
                            }
                        }) { Icon(if (playing == f) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Putar") }
                        OutlinedButton(
                            enabled = !tr.running && !rec.recording,
                            onClick = {
                                if (AppGraph.whisper.selectedFile() == null) needWhisper = true
                                else {
                                    pendingTranscribe = f
                                    askNotif()
                                }
                            },
                        ) { Text("Transkrip") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                            val send = Intent(Intent.ACTION_SEND).setType("audio/wav")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "Bagikan rekaman"))
                        }) { Icon(Icons.Filled.Share, "Bagikan") }
                        IconButton(onClick = { confirmDelete = f }) { Icon(Icons.Filled.Delete, "Hapus") }
                    }
                }
            }
        }
        Text(
            "Bahasa transkrip: ${when (AppGraph.prefs.whisperLanguage) { "en" -> "Inggris"; "auto" -> "Otomatis"; else -> "Indonesia" }} (ubah di Pengaturan).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Hapus rekaman?") },
            text = { Text("${f.nameWithoutExtension} akan dihapus permanen dari HP.") },
            confirmButton = {
                TextButton(onClick = {
                    if (playing == f) stopPlayer()
                    f.delete()
                    confirmDelete = null
                    refresh()
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Batal") } },
        )
    }
    if (needWhisper) {
        AlertDialog(
            onDismissRequest = { needWhisper = false },
            title = { Text("Perlu model suara") },
            text = { Text("Untuk mengubah rekaman jadi teks tanpa internet, unduh model suara (Whisper) sekali dulu.") },
            confirmButton = { TextButton(onClick = { needWhisper = false; nav.go(Screen.Models(ModelType.WHISPER)) }) { Text("Pilih model") } },
            dismissButton = { TextButton(onClick = { needWhisper = false }) { Text("Nanti") } },
        )
    }
}
