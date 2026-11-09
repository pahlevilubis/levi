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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.ai.Translator
import com.pahlevi.levi.audio.Dictation
import com.pahlevi.levi.data.NoteKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TranslateState(initial: String) {
    var input by mutableStateOf(initial)
    var from by mutableStateOf(AppGraph.prefs.lastTranslateFrom)
    var to by mutableStateOf(AppGraph.prefs.lastTranslateTo)
    var result by mutableStateOf<String?>(null)
    var detected by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun swap() {
        if (from == "auto") from = detected ?: "id"
        val f = from
        from = to
        to = f
        result?.let { input = it; result = null }
        savePrefs()
    }

    private fun savePrefs() {
        AppGraph.prefs.lastTranslateFrom = from
        AppGraph.prefs.lastTranslateTo = to
    }

    fun translate(scope: CoroutineScope, gate: InternetGate) {
        val text = input.trim()
        if (text.isEmpty() || running) return
        savePrefs()
        error = null
        job = scope.launch {
            try {
                val src = if (from == "auto") {
                    status = "Mengenali bahasa…"
                    Translator.detect(text) ?: run {
                        error = "Bahasa teks tidak dikenali. Pilih bahasa asal secara manual."
                        return@launch
                    }
                } else from
                detected = src
                if (src == to) {
                    error = "Bahasa asal dan tujuan sama (${Translator.name(src)})."
                    return@launch
                }
                status = "Memeriksa paket bahasa…"
                val missing = Translator.missingFor(src, to)
                if (missing.isNotEmpty()) {
                    status = null
                    val names = missing.joinToString(", ") { Translator.name(it) }
                    gate.request("Unduh paket bahasa $names (±30 MB per bahasa) untuk terjemahan offline. Cukup sekali.") {
                        job = scope.launch { downloadThenTranslate(missing, text, src) }
                    }
                    return@launch
                }
                runTranslation(text, src)
            } catch (e: Throwable) {
                error = e.message ?: "Gagal menerjemahkan"
            } finally {
                status = null
            }
        }
    }

    private suspend fun downloadThenTranslate(missing: List<String>, text: String, src: String) {
        try {
            missing.forEach {
                status = "Mengunduh paket bahasa ${Translator.name(it)}…"
                Translator.download(it)
            }
            runTranslation(text, src)
        } catch (e: Throwable) {
            error = "Gagal: ${e.message ?: "periksa koneksi internet"}"
        } finally {
            status = null
        }
    }

    private suspend fun runTranslation(text: String, src: String) {
        status = "Menerjemahkan…"
        progress = 0f
        result = Translator.translate(text, src, to) { progress = it }
        status = null
    }
}

@Composable
fun TranslateScreen(entry: Entry, screen: Screen.Translate) {
    val st = remember(entry) { entry.keep("tr") { TranslateState(screen.text) } }
    val gate = LocalInternetGate.current
    val context = LocalContext.current
    val dictation = remember { Dictation(context) }
    DisposableEffect(Unit) { onDispose { dictation.stop() } }

    val fromOptions = listOf("auto" to "Deteksi otomatis") + Translator.languages.map { it.code to it.name }
    val toOptions = Translator.languages.map { it.code to it.name }

    ScreenScaffold(topBar = { LeviTopBar("Terjemah", subtitle = "Offline dengan Google ML Kit") }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dropdown("Dari", fromOptions, st.from, { st.from = it }, Modifier.weight(1f))
                IconButton(onClick = { st.swap() }) { Icon(Icons.Filled.SwapHoriz, "Tukar") }
                Dropdown("Ke", toOptions, st.to, { st.to = it }, Modifier.weight(1f))
            }
            if (st.from == "auto" && st.detected != null) {
                Text(
                    "Terdeteksi: ${Translator.name(st.detected!!)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = st.input,
                onValueChange = { st.input = it },
                label = { Text("Teks yang mau diterjemahkan") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                trailingIcon = {
                    Column {
                        if (st.input.isNotEmpty()) {
                            IconButton(onClick = { st.input = ""; st.result = null }) { Icon(Icons.Filled.Close, "Hapus") }
                        }
                        IconButton(onClick = { pasteText(context)?.let { st.input = it } }) { Icon(Icons.Filled.ContentPaste, "Tempel") }
                        val lang = when (val code = st.from) {
                            "auto" -> AppGraph.prefs.dictationLanguage
                            "en" -> "en-US"
                            "id" -> "id-ID"
                            else -> code
                        }
                        MicButton(dictation, language = lang, onText = { st.input = (st.input + " " + it).trim() })
                    }
                },
            )
            Button(onClick = { st.translate(entry.scope, gate) }, enabled = st.input.isNotBlank() && !st.running, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Translate, null)
                Spacer(Modifier.padding(4.dp))
                Text("Terjemahkan")
            }
            StatusLine(st.status)
            if (st.status == "Menerjemahkan…" && st.progress in 0.01f..0.99f) {
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            }
            ErrorText(st.error)
            st.result?.let { res ->
                ResultCard(
                    title = "Terjemahan (${Translator.name(st.to)})",
                    text = res,
                    onSave = {
                        AppGraph.notes.add(
                            "Terjemahan: " + st.input.take(40),
                            "${st.input.trim()}\n\n— ${Translator.name(st.to)} —\n$res",
                            NoteKind.TRANSLATION,
                        )
                    },
                )
            }
            Text(
                "Paket bahasa diunduh sekali (perlu internet), setelah itu terjemahan berjalan tanpa internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
