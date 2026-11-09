@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.pahlevi.levi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.ai.LlmStatus
import com.pahlevi.levi.ai.ModelType
import com.pahlevi.levi.data.WebPermission

// ---------------------------------------------------------------------------
// Bantuan umum
// ---------------------------------------------------------------------------

fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Levi", text))
    Toast.makeText(context, "Disalin", Toast.LENGTH_SHORT).show()
}

fun shareText(context: Context, text: String, title: String = "Bagikan") {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
}

fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

fun pasteText(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java)
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

// ---------------------------------------------------------------------------
// Bar atas
// ---------------------------------------------------------------------------

@Composable
fun LeviTopBar(
    title: String,
    subtitle: String? = null,
    showBack: Boolean = true,
    actions: @Composable () -> Unit = {},
) {
    val nav = LocalNav.current
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = { nav.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Scaffold untuk halaman dalam: inset bawah sudah diurus oleh kerangka utama. */
@Composable
fun ScreenScaffold(
    topBar: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        floatingActionButton = floatingActionButton,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Gerbang izin internet
// ---------------------------------------------------------------------------

class InternetGate {
    var pending by mutableStateOf<Pair<String, () -> Unit>?>(null)
        private set
    var blockedMessage by mutableStateOf<String?>(null)
        private set

    /** Jalankan [onAllowed] hanya kalau pengguna mengizinkan pemakaian internet untuk [purpose]. */
    fun request(purpose: String, onAllowed: () -> Unit) {
        when (AppGraph.prefs.webPermission) {
            WebPermission.ALWAYS -> onAllowed()
            WebPermission.NEVER -> blockedMessage =
                "Pemakaian internet sedang dimatikan di Pengaturan → Izin & privasi. Ubah dulu kalau mau melanjutkan."
            WebPermission.ASK -> pending = purpose to onAllowed
        }
    }

    fun allowOnce() {
        val p = pending
        pending = null
        p?.second?.invoke()
    }

    fun allowAlways() {
        AppGraph.prefs.webPermission = WebPermission.ALWAYS
        allowOnce()
    }

    fun deny() {
        pending = null
    }

    fun dismissBlocked() {
        blockedMessage = null
    }
}

val LocalInternetGate = staticCompositionLocalOf { InternetGate() }

@Composable
fun InternetGateDialogs(gate: InternetGate) {
    gate.pending?.let { (purpose, _) ->
        AlertDialog(
            onDismissRequest = { gate.deny() },
            title = { Text("Izinkan pakai internet?") },
            text = {
                Column {
                    Text(purpose)
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        "Ini memakai paket data / WiFi kamu. Selain itu, Levi tetap bekerja offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { gate.allowOnce() }) { Text("Izinkan sekali") }
                    TextButton(onClick = { gate.allowAlways() }) { Text("Selalu izinkan") }
                }
            },
            dismissButton = { TextButton(onClick = { gate.deny() }) { Text("Tolak") } },
        )
    }
    gate.blockedMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { gate.dismissBlocked() },
            title = { Text("Internet dimatikan") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { gate.dismissBlocked() }) { Text("Oke") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Potongan UI
// ---------------------------------------------------------------------------

@Composable
fun ChoiceChips(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            FilterChip(selected = opt == selected, onClick = { onSelect(opt) }, label = { Text(opt) })
        }
    }
}

@Composable
fun Dropdown(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(options.firstOrNull { it.first == selected }?.second ?: selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

@Composable
fun StatusLine(text: String?) {
    if (text == null) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorText(text: String?) {
    if (text == null) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
    }
}

/** Kartu hasil dengan tombol salin / bagikan / simpan. */
@Composable
fun ResultCard(
    title: String,
    text: String,
    onSave: (() -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.padding(3.dp))
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { copyText(context, text) }) { Icon(Icons.Filled.ContentCopy, "Salin") }
                IconButton(onClick = { shareText(context, text) }) { Icon(Icons.Filled.Share, "Bagikan") }
                if (onSave != null) {
                    IconButton(onClick = { onSave(); toast(context, "Disimpan ke Catatan") }) { Icon(Icons.Filled.Save, "Simpan") }
                }
            }
            extra()
        }
    }
}

/** Kartu peringatan kalau model belum ada, dengan tombol ke halaman unduh. */
@Composable
fun ModelMissingCard(type: ModelType) {
    val nav = LocalNav.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (type == ModelType.LLM) "Model AI belum ada" else "Model suara belum ada",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (type == ModelType.LLM) "Unduh sekali (±1–3 GB), setelah itu Levi bisa dipakai tanpa internet."
                else "Untuk mengubah rekaman jadi teks, unduh model suara sekali (±60–550 MB).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(4.dp))
            Button(onClick = { nav.go(Screen.Models(type)) }) { Text("Pilih & unduh model") }
        }
    }
}

/** Baris kecil yang menunjukkan status model AI. */
@Composable
fun LlmStatusHint() {
    val status by AppGraph.llm.status.collectAsState()
    when (val s = status) {
        LlmStatus.NoModel -> ModelMissingCard(ModelType.LLM)
        is LlmStatus.Loading -> StatusLine("Memuat ${s.name}…")
        is LlmStatus.Failed -> ErrorText("Model ${s.name} gagal dimuat: ${s.message}")
        else -> {}
    }
}
