package com.pahlevi.levi.ui

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.ai.CatalogModel
import com.pahlevi.levi.ai.DownloadState
import com.pahlevi.levi.ai.ModelCatalog
import com.pahlevi.levi.ai.ModelDownloader
import com.pahlevi.levi.ai.ModelType
import com.pahlevi.levi.data.WebPermission
import kotlinx.coroutines.launch
import java.io.File

// ---------------------------------------------------------------------------
// Pengaturan
// ---------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@Composable
fun SettingsScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = AppGraph.prefs
    val llmPath by prefs.llmModel.collectAsState()
    val whisperPath by prefs.whisperModel.collectAsState()

    var name by remember { mutableStateOf(prefs.userName) }
    var ctx by remember { mutableIntStateOf(prefs.contextSize) }
    var threads by remember { mutableFloatStateOf(prefs.threads.toFloat()) }
    var temp by remember { mutableFloatStateOf(prefs.temperature) }
    var maxTok by remember { mutableIntStateOf(prefs.maxAnswerTokens) }
    var web by remember { mutableStateOf(prefs.webPermission) }
    var useMem by remember { mutableStateOf(prefs.useMemory) }
    var wLang by remember { mutableStateOf(prefs.whisperLanguage) }
    var confirmClearChats by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    fun reloadLlm() = scope.launch { AppGraph.llm.reload() }

    ScreenScaffold(topBar = { LeviTopBar("Pengaturan", showBack = false) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            SectionTitle("Tentang kamu")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; prefs.userName = it.trim() },
                label = { Text("Nama panggilan (dipakai Levi saat menyapa)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Model")
            LinkRow("Model AI", llmPath?.let { File(it).name } ?: "Belum dipilih — ketuk untuk mengunduh") { nav.go(Screen.Models(ModelType.LLM)) }
            LinkRow("Model suara (transkrip)", whisperPath?.let { File(it).name } ?: "Belum dipilih") { nav.go(Screen.Models(ModelType.WHISPER)) }
            Text("Bahasa transkrip rekaman", style = MaterialTheme.typography.labelLarge)
            val langOpts = listOf("id" to "Indonesia", "en" to "Inggris", "auto" to "Otomatis")
            ChoiceChips(langOpts.map { it.second }, langOpts.first { it.first == wLang }.second, { label ->
                wLang = langOpts.first { it.second == label }.first
                prefs.whisperLanguage = wLang
            })

            SectionTitle("Performa AI")
            Text("Panjang ingatan percakapan (konteks)", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("2048", "4096", "8192"), ctx.toString(), {
                ctx = it.toInt(); prefs.contextSize = ctx; reloadLlm()
            })
            Text(
                "Lebih besar = bisa membaca teks lebih panjang, tapi butuh RAM lebih banyak.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(4.dp))
            Text("Jumlah inti CPU dipakai: ${threads.toInt()}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = threads, onValueChange = { threads = it }, valueRange = 2f..8f, steps = 5,
                onValueChangeFinished = { prefs.threads = threads.toInt(); reloadLlm() },
            )
            Text(
                "4 biasanya paling cepat di Galaxy S21. Lebih banyak belum tentu lebih cepat dan lebih panas.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(4.dp))
            Text("Kreativitas jawaban: ${"%.1f".format(temp)}", style = MaterialTheme.typography.labelLarge)
            Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..1.2f, onValueChangeFinished = { prefs.temperature = temp })
            Text("Rendah = lebih konsisten & faktual. Tinggi = lebih bervariasi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(4.dp))
            Text("Panjang jawaban maksimal (token)", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("512", "768", "1024", "2048"), maxTok.toString(), { maxTok = it.toInt(); prefs.maxAnswerTokens = maxTok })

            SectionTitle("Izin & privasi")
            Text("Pakai internet untuk Cari Web & unduh paket bahasa", style = MaterialTheme.typography.labelLarge)
            val webOpts = listOf(WebPermission.ASK to "Tanya dulu", WebPermission.ALWAYS to "Selalu izinkan", WebPermission.NEVER to "Jangan pernah")
            ChoiceChips(webOpts.map { it.second }, webOpts.first { it.first == web }.second, { label ->
                web = webOpts.first { it.second == label }.first
                prefs.webPermission = web
            })
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pakai memori saat chat")
                    Text("${AppGraph.memory.items.collectAsState().value.size} hal diingat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = useMem, onCheckedChange = { useMem = it; prefs.useMemory = it })
            }
            LinkRow("Kelola memori", "Lihat, ubah, atau hapus hal yang diingat Levi") { nav.go(Screen.Memory) }
            LinkRow("Hapus semua riwayat chat", "Catatan dan memori tidak ikut terhapus") { confirmClearChats = true }
            Text(
                "Levi tidak bisa mengubah pengaturan HP, mengirim pesan, atau membuka aplikasi lain. " +
                    "Mikrofon dan internet hanya dipakai setelah kamu izinkan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            SectionTitle("Tentang")
            Text("Levi 1.0 — asisten AI offline", style = MaterialTheme.typography.bodyLarge)
            Text("Dibuat oleh Pahlevi Lubis bersama Claude.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Memakai llama.cpp & whisper.cpp (MIT), Google ML Kit untuk terjemah & baca teks, model dari Hugging Face.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showInfo = !showInfo }) { Text(if (showInfo) "Sembunyikan info teknis" else "Info teknis") }
            if (showInfo) {
                val am = context.getSystemService(ActivityManager::class.java)
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                Text(
                    "RAM: ${"%.1f".format(mi.totalMem / 1_073_741_824f)} GB (tersedia ${"%.1f".format(mi.availMem / 1_073_741_824f)} GB)\n" +
                        "CPU: ${Runtime.getRuntime().availableProcessors()} inti\n" +
                        runCatching { AppGraph.llm.engine.systemInfo() }.getOrDefault(""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.padding(16.dp))
        }
    }

    if (confirmClearChats) {
        AlertDialog(
            onDismissRequest = { confirmClearChats = false },
            title = { Text("Hapus semua riwayat chat?") },
            text = { Text("Semua obrolan akan dihapus permanen dari HP.") },
            confirmButton = { TextButton(onClick = { AppGraph.chats.clear(); confirmClearChats = false }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { confirmClearChats = false }) { Text("Batal") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Kelola model (unduh / pakai / hapus)
// ---------------------------------------------------------------------------

private fun displayName(context: Context, uri: Uri): String {
    val fromQuery = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return fromQuery ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "model"
}

private fun formatSize(mb: Int) = if (mb >= 1000) "%.1f GB".format(mb / 1000f) else "$mb MB"

private fun totalRamGb(context: Context): Float {
    val am = context.getSystemService(ActivityManager::class.java)
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return mi.totalMem / 1_073_741_824f
}

@Composable
fun ModelsScreen(screen: Screen.Models) {
    val type = screen.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dl = AppGraph.downloads
    val states by dl.states.collectAsState()
    val revision by dl.revision.collectAsState()
    val selected by (if (type == ModelType.LLM) AppGraph.prefs.llmModel else AppGraph.prefs.whisperModel).collectAsState()
    val catalog = if (type == ModelType.LLM) ModelCatalog.llm else ModelCatalog.whisper

    var confirmDownload by remember { mutableStateOf<CatalogModel?>(null) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    var customUrl by remember { mutableStateOf("") }
    var importProgress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // daftar file terpasang (diperbarui setiap ada perubahan unduhan)
    val installed = remember(revision, states) { dl.installed(type) }

    fun select(f: File) {
        if (type == ModelType.LLM) {
            AppGraph.prefs.setLlmModel(f.absolutePath)
            scope.launch { AppGraph.llm.reload() }
        } else {
            AppGraph.prefs.setWhisperModel(f.absolutePath)
            scope.launch { AppGraph.whisper.unload() }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(context, uri)
        val ext = if (type == ModelType.LLM) ".gguf" else ".bin"
        if (!name.endsWith(ext, ignoreCase = true)) {
            message = "File harus berakhiran $ext"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            importProgress = 0f
            try {
                val f = dl.importFile(type, uri, name.replace(Regex("[^A-Za-z0-9._-]"), "_")) { importProgress = it }
                if (selected == null) select(f)
                message = "Berhasil diimpor: ${f.name}"
            } catch (e: Throwable) {
                message = "Gagal impor: ${e.message}"
            } finally {
                importProgress = null
            }
        }
    }

    ScreenScaffold(
        topBar = {
            LeviTopBar(
                if (type == ModelType.LLM) "Model AI" else "Model Suara",
                subtitle = if (type == ModelType.LLM) "RAM HP: ${"%.0f".format(totalRamGb(context))} GB" else "Untuk transkrip rekaman",
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (type == ModelType.LLM) "Unduh satu model sekali saja (sebaiknya pakai WiFi). Setelah itu semua fitur AI berjalan tanpa internet."
                else "Model suara mengubah rekaman jadi teks tanpa internet. Makin besar makin akurat tapi makin lambat.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
            }

            catalog.forEach { m ->
                val key = dl.key(type, m.fileName)
                val state = states[key]
                val file = dl.file(type, m.fileName)
                val ready = installed.any { it.name == m.fileName }
                val inUse = ready && selected == file.absolutePath
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (inUse) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (m.recommended) Text("Disarankan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                        Text("${formatSize(m.sizeMb)} • ${m.description}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.padding(4.dp))
                        when {
                            state is DownloadState.Running -> {
                                val frac = if (state.total > 0) state.done.toFloat() / state.total else 0f
                                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (state.paused) "Menunggu koneksi…" else "Mengunduh ${(frac * 100).toInt()}% (${state.done / 1_048_576} MB)",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { dl.cancel(type, m.fileName) }) { Text("Batal") }
                                }
                            }
                            ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                                if (inUse) {
                                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Sedang dipakai", modifier = Modifier.weight(1f))
                                } else {
                                    Button(onClick = { select(file) }) { Text("Pakai model ini") }
                                    Spacer(Modifier.weight(1f))
                                }
                                IconButton(onClick = { confirmDelete = file }) { Icon(Icons.Filled.Delete, "Hapus") }
                            }
                            else -> {
                                if (state is DownloadState.Failed) {
                                    Text(state.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                                FilledTonalButton(onClick = { confirmDownload = m }) {
                                    Icon(Icons.Filled.Download, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Unduh (${formatSize(m.sizeMb)})")
                                }
                            }
                        }
                    }
                }
            }

            // file lain (diimpor sendiri / dari link)
            val others = installed.filter { f -> catalog.none { it.fileName == f.name } }
            if (others.isNotEmpty()) {
                Text("Model lain di HP", style = MaterialTheme.typography.titleSmall)
                others.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${f.length() / 1_048_576} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected == f.absolutePath) Text("Dipakai", color = MaterialTheme.colorScheme.primary)
                        else TextButton(onClick = { select(f) }) { Text("Pakai") }
                        IconButton(onClick = { confirmDelete = f }) { Icon(Icons.Filled.Delete, "Hapus") }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Punya file model sendiri?", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = importProgress == null) {
                Icon(Icons.Filled.Folder, null)
                Spacer(Modifier.width(6.dp))
                Text(if (type == ModelType.LLM) "Impor file .gguf dari HP" else "Impor file .bin dari HP")
            }
            importProgress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                Text("Menyalin file… ${(it * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it.trim() },
                label = { Text("Atau tempel link unduhan langsung") },
                placeholder = { Text("https://huggingface.co/…/resolve/main/model.gguf") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = customUrl.startsWith("https://"),
                onClick = {
                    val name = ModelDownloader.fileNameFromUrl(customUrl)
                    val ext = if (type == ModelType.LLM) ".gguf" else ".bin"
                    if (!name.endsWith(ext, true)) {
                        message = "Link harus mengarah ke file $ext"
                    } else {
                        confirmDownload = CatalogModel("custom", type, name, name, customUrl, 0, "dari link")
                    }
                },
            ) {
                Icon(Icons.Filled.Link, null)
                Spacer(Modifier.width(6.dp))
                Text("Unduh dari link")
            }
            if (type == ModelType.LLM) {
                Text(
                    "Tips: model GGUF ukuran 1–4 miliar parameter (Q4) paling pas untuk HP. Model lebih besar bisa membuat HP lambat atau aplikasi tertutup sendiri.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.padding(12.dp))
        }
    }

    confirmDownload?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Unduh ${m.name}?") },
            text = {
                Text(
                    (if (m.sizeMb > 0) "Ukuran ${formatSize(m.sizeMb)}. " else "") +
                        "Unduhan memakai internet (sebaiknya WiFi) dan tetap berjalan walau aplikasi ditutup. Cukup sekali.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dl.start(type, m.url, m.fileName, m.name)
                    if (m.id == "custom") customUrl = ""
                    confirmDownload = null
                }) { Text("Unduh") }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text("Batal") } },
        )
    }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Hapus model?") },
            text = { Text("${f.name} akan dihapus dari HP (${f.length() / 1_048_576} MB). Bisa diunduh lagi nanti.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (selected == f.absolutePath) {
                            if (type == ModelType.LLM) {
                                AppGraph.llm.unload()
                                AppGraph.prefs.setLlmModel(null)
                            } else {
                                AppGraph.whisper.unload()
                                AppGraph.prefs.setWhisperModel(null)
                            }
                        }
                        dl.delete(type, f.name)
                    }
                    confirmDelete = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Batal") } },
        )
    }

    // pilih otomatis model pertama yang selesai diunduh kalau belum ada yang dipakai
    androidx.compose.runtime.LaunchedEffect(installed) {
        if (selected == null) installed.firstOrNull()?.let { select(it) }
    }
}
