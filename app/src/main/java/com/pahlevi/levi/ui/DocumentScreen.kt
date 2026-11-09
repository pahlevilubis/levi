package com.pahlevi.levi.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.ai.AiTasks
import com.pahlevi.levi.ai.Ocr
import com.pahlevi.levi.ai.Prompts
import com.pahlevi.levi.data.NoteKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class DocumentState {
    var text by mutableStateOf("")
    var sourceName by mutableStateOf("")
    var status by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    var question by mutableStateOf("")
    var answer by mutableStateOf<String?>(null)
    var editing by mutableStateOf(false)
    var cameraUri: Uri? = null
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun readImage(scope: CoroutineScope, context: Context, uri: Uri, name: String) {
        job = scope.launch {
            error = null
            answer = null
            status = "Membaca teks dari gambar…"
            try {
                val t = Ocr.fromImage(context, uri)
                if (t.isBlank()) error = "Tidak ada teks yang terbaca. Coba foto lebih dekat, terang, dan tidak miring."
                text = t
                sourceName = name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Gagal membaca gambar: ${e.message}"
            } finally {
                status = null
            }
        }
    }

    fun readPdf(scope: CoroutineScope, context: Context, uri: Uri) {
        job = scope.launch {
            error = null
            answer = null
            progress = 0f
            status = "Membuka PDF…"
            try {
                val t = Ocr.fromPdf(context, uri) { page, total ->
                    status = "Membaca halaman $page dari $total…"
                    progress = page.toFloat() / total
                }
                if (t.isBlank()) error = "Tidak ada teks yang terbaca dari PDF ini."
                text = t
                sourceName = "PDF"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Gagal membaca PDF: ${e.message}"
            } finally {
                status = null
            }
        }
    }

    fun ask(scope: CoroutineScope) {
        val q = question.trim()
        if (q.isEmpty() || text.isBlank() || running) return
        job = scope.launch {
            error = null
            answer = ""
            status = "Membaca dokumen…"
            try {
                val doc = AiTasks.fitDocument(AppGraph.llm, text, reserve = 600)
                answer = AppGraph.llm.complete(
                    Prompts.chat(Prompts.DOC_QA_SYSTEM, Prompts.docQaUser(doc, q)),
                    AppGraph.llm.defaultParams(maxTokens = 600, temperature = 0.2f),
                ) { status = null; answer = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Gagal menjawab"
            } finally {
                status = null
            }
        }
    }
}

@Composable
fun DocumentScreen(entry: Entry) {
    val st = remember(entry) { entry.keep("doc") { DocumentState() } }
    val context = LocalContext.current
    val nav = LocalNav.current

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = st.cameraUri
        if (ok && uri != null) st.readImage(entry.scope, context, uri, "Foto")
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) st.readImage(entry.scope, context, uri, "Gambar")
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) st.readPdf(entry.scope, context, uri)
    }

    ScreenScaffold(topBar = { LeviTopBar("Dokumen", subtitle = "Foto / PDF jadi teks, lalu ringkas atau tanya") }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    val dir = File(context.cacheDir, "foto").apply { mkdirs() }
                    val f = File(dir, "dokumen_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                    st.cameraUri = uri
                    takePhoto.launch(uri)
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CameraAlt, null); Spacer(Modifier.padding(3.dp)); Text("Foto")
                }
                FilledTonalButton(onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Image, null); Spacer(Modifier.padding(3.dp)); Text("Galeri")
                }
                FilledTonalButton(onClick = { pickPdf.launch(arrayOf("application/pdf")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PictureAsPdf, null); Spacer(Modifier.padding(3.dp)); Text("PDF")
                }
            }
            StatusLine(st.status)
            if (st.status?.startsWith("Membaca halaman") == true) {
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
            }
            ErrorText(st.error)

            if (st.text.isBlank() && st.status == null) {
                Text(
                    "Ambil foto surat, struk, papan pengumuman, atau buka file PDF. Levi akan membaca teksnya tanpa internet, " +
                        "lalu kamu bisa meringkas, menerjemahkan, atau bertanya tentang isinya.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (st.text.isNotBlank()) {
                Text("Teks dari ${st.sourceName} (${st.text.length} karakter)", style = MaterialTheme.typography.labelLarge)
                if (st.editing) {
                    OutlinedTextField(
                        value = st.text,
                        onValueChange = { st.text = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp),
                    )
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    ) {
                        SelectionContainer(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Text(st.text)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { st.editing = !st.editing }) { Text(if (st.editing) "Selesai" else "Edit") }
                    FilledTonalButton(onClick = { copyText(context, st.text) }) { Text("Salin") }
                    FilledTonalButton(onClick = {
                        AppGraph.notes.add("", st.text, NoteKind.DOCUMENT)
                        toast(context, "Disimpan ke Catatan")
                    }) { Text("Simpan") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { nav.go(Screen.Summarize(st.text)) }) { Text("Ringkas") }
                    FilledTonalButton(onClick = { nav.go(Screen.Translate(st.text)) }) { Text("Terjemahkan") }
                    FilledTonalButton(onClick = { shareText(context, st.text) }) { Text("Bagikan") }
                }

                Spacer(Modifier.padding(4.dp))
                Text("Tanya tentang dokumen ini", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = st.question,
                    onValueChange = { st.question = it },
                    placeholder = { Text("Contoh: kapan batas pembayarannya?") },
                    modifier = Modifier.fillMaxWidth(),
                )
                RunButton("Tanya", st.running && st.answer != null, st.question.isNotBlank(), { st.ask(entry.scope) }, { AppGraph.llm.stop() })
                st.answer?.takeIf { it.isNotBlank() }?.let { ResultCard("Jawaban", it) }
            }
        }
    }
}
