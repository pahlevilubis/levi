package com.pahlevi.levi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.ai.AiTasks
import com.pahlevi.levi.ai.Prompts
import com.pahlevi.levi.audio.Dictation
import com.pahlevi.levi.data.NoteKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Tombol utama yang berubah jadi tombol Hentikan selama AI bekerja. */
@Composable
fun RunButton(label: String, running: Boolean, enabled: Boolean, onRun: () -> Unit, onStop: () -> Unit) {
    if (running) {
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Stop, null)
            Spacer(Modifier.padding(4.dp))
            Text("Hentikan")
        }
    } else {
        Button(onClick = onRun, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.AutoAwesome, null)
            Spacer(Modifier.padding(4.dp))
            Text(label)
        }
    }
}

// ---------------------------------------------------------------------------
// Ringkas
// ---------------------------------------------------------------------------

class SummarizeState(initial: String, style: Prompts.SummaryStyle) {
    var input by mutableStateOf(initial)
    var style by mutableStateOf(style)
    var result by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun run(scope: CoroutineScope) {
        val text = input.trim()
        if (text.isEmpty() || running) return
        error = null
        result = ""
        job = scope.launch {
            try {
                result = AiTasks.summarize(AppGraph.llm, text, style, onStatus = { status = it }, onPartial = { result = it })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Gagal meringkas"
                if (result.isNullOrBlank()) result = null
            } finally {
                status = null
            }
        }
    }

    fun stop() {
        AppGraph.llm.stop()
        job?.cancel()
    }
}

@Composable
fun SummarizeScreen(entry: Entry, screen: Screen.Summarize) {
    val st = remember(entry) { entry.keep("sum") { SummarizeState(screen.text, screen.style) } }
    val context = LocalContext.current

    ScreenScaffold(topBar = { LeviTopBar("Ringkas", subtitle = "Teks panjang jadi intinya") }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LlmStatusHint()
            OutlinedTextField(
                value = st.input,
                onValueChange = { st.input = it },
                label = { Text("Tempel teks di sini (artikel, chat grup, notulen…)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { pasteText(context)?.let { st.input = it } }) {
                    Icon(Icons.Filled.ContentPaste, null)
                    Spacer(Modifier.padding(3.dp))
                    Text("Tempel")
                }
                Spacer(Modifier.weight(1f))
                Text("${st.input.length} karakter", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Bentuk ringkasan", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(Prompts.SummaryStyle.entries.map { it.label }, st.style.label, { label ->
                st.style = Prompts.SummaryStyle.entries.first { it.label == label }
            })
            RunButton("Ringkas", st.running, st.input.isNotBlank(), { st.run(entry.scope) }, { st.stop() })
            StatusLine(st.status)
            ErrorText(st.error)
            st.result?.takeIf { it.isNotBlank() }?.let { res ->
                ResultCard("Ringkasan", res, onSave = {
                    AppGraph.notes.add("Ringkasan: " + st.input.trim().take(40), res, NoteKind.SUMMARY)
                })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bantu tulis
// ---------------------------------------------------------------------------

class WriteState {
    var type by mutableStateOf(Prompts.docTypes.first())
    var tone by mutableStateOf(Prompts.tones.first())
    var length by mutableStateOf(Prompts.lengths[1])
    var details by mutableStateOf("")
    var result by mutableStateOf("")
    var revision by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun write(scope: CoroutineScope) {
        if (details.isBlank() || running) return
        launchGen(scope, Prompts.chat(Prompts.WRITE_SYSTEM, Prompts.writeUser(type, tone, length, details)))
    }

    fun revise(scope: CoroutineScope) {
        if (result.isBlank() || revision.isBlank() || running) return
        launchGen(scope, Prompts.chat(Prompts.REVISE_SYSTEM, Prompts.reviseUser(result, revision)))
        revision = ""
    }

    private fun launchGen(scope: CoroutineScope, msgs: List<com.pahlevi.levi.llm.ChatMessage>) {
        error = null
        val previous = result
        job = scope.launch {
            status = "Menulis…"
            try {
                val maxTokens = when (length) { "Pendek" -> 400; "Panjang" -> 1400; else -> 800 }
                result = AppGraph.llm.complete(msgs, AppGraph.llm.defaultParams(maxTokens = maxTokens)) {
                    status = null
                    result = it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Gagal menulis"
                if (result.isBlank()) result = previous
            } finally {
                status = null
            }
        }
    }

    fun stop() {
        AppGraph.llm.stop()
    }
}

@Composable
fun WriteScreen(entry: Entry) {
    val st = remember(entry) { entry.keep("write") { WriteState() } }
    val context = LocalContext.current
    val dictation = remember { Dictation(context) }
    DisposableEffect(Unit) { onDispose { dictation.stop() } }

    ScreenScaffold(topBar = { LeviTopBar("Bantu Tulis", subtitle = "Surat, email, pesan, pengumuman") }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LlmStatusHint()
            Text("Jenis tulisan", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(Prompts.docTypes, st.type, { st.type = it })
            Text("Gaya bahasa", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(Prompts.tones, st.tone, { st.tone = it })
            Text("Panjang", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(Prompts.lengths, st.length, { st.length = it })
            OutlinedTextField(
                value = st.details,
                onValueChange = { st.details = it },
                label = { Text("Isi / poin yang harus ada") },
                placeholder = { Text("Contoh: izin tidak masuk kerja besok karena anak sakit, ditujukan ke Pak Budi, pekerjaan sudah diserahkan ke Rina") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                trailingIcon = { MicButton(dictation, onText = { st.details = (st.details + " " + it).trim() }) },
            )
            RunButton("Buatkan", st.running, st.details.isNotBlank(), { st.write(entry.scope) }, { st.stop() })
            StatusLine(st.status)
            ErrorText(st.error)
            if (st.result.isNotBlank()) {
                OutlinedTextField(
                    value = st.result,
                    onValueChange = { st.result = it },
                    label = { Text("Hasil (bisa diedit)") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = st.running,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { copyText(context, st.result) }) { Text("Salin") }
                    FilledTonalButton(onClick = { shareText(context, st.result) }) { Text("Bagikan") }
                    FilledTonalButton(onClick = {
                        AppGraph.notes.add("${st.type}: " + st.details.take(30), st.result, NoteKind.WRITING)
                        toast(context, "Disimpan ke Catatan")
                    }) { Text("Simpan") }
                }
                OutlinedTextField(
                    value = st.revision,
                    onValueChange = { st.revision = it },
                    label = { Text("Minta perubahan") },
                    placeholder = { Text("Contoh: lebih singkat, lebih sopan, tambahkan nomor HP") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { st.revise(entry.scope) },
                    enabled = st.revision.isNotBlank() && !st.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Summarize, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Perbaiki tulisan")
                }
            }
        }
    }
}
