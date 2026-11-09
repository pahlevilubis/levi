@file:OptIn(ExperimentalLayoutApi::class)

package com.pahlevi.levi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.ai.Prompts
import com.pahlevi.levi.data.MemoryItem
import com.pahlevi.levi.data.Note
import com.pahlevi.levi.data.NoteKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Daftar catatan
// ---------------------------------------------------------------------------

class NotesListState {
    var query by mutableStateOf("")
    var kind by mutableStateOf<String?>(null)
}

@Composable
fun NotesScreen(entry: Entry) {
    val st = remember(entry) { entry.keep("notes") { NotesListState() } }
    val nav = LocalNav.current
    val notes by AppGraph.notes.items.collectAsState()
    val fmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID")) }
    val kinds = NoteKind.all.filter { k -> notes.any { it.kind == k } }
    val filtered = notes.filter { n ->
        (st.kind == null || n.kind == st.kind) &&
            (st.query.isBlank() || n.title.contains(st.query, true) || n.body.contains(st.query, true))
    }

    ScreenScaffold(
        topBar = { LeviTopBar("Catatan", showBack = false) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.go(Screen.NoteDetail(null)) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Catatan baru") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = st.query,
                    onValueChange = { st.query = it },
                    placeholder = { Text("Cari catatan…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (kinds.size > 1) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = st.kind == null, onClick = { st.kind = null }, label = { Text("Semua") })
                        kinds.forEach { k ->
                            FilterChip(selected = st.kind == k, onClick = { st.kind = if (st.kind == k) null else k }, label = { Text(k) })
                        }
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (notes.isEmpty()) "Belum ada catatan. Hasil terjemahan, transkrip, ringkasan, dan tulisan yang kamu simpan akan muncul di sini."
                        else "Tidak ada catatan yang cocok.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(filtered, key = { it.id }) { n ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().clickable { nav.go(Screen.NoteDetail(n.id)) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(n.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            n.body.replace('\n', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(fmt.format(Date(n.updated)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Detail catatan
// ---------------------------------------------------------------------------

class NoteEditState(id: String?) {
    private val original: Note? = id?.let { AppGraph.notes.get(it) }
    val noteId: String? = original?.id
    var title by mutableStateOf(original?.title ?: "")
    var body by mutableStateOf(original?.body ?: "")
    val kind: String = original?.kind ?: NoteKind.NOTE
    var editing by mutableStateOf(original == null)
    var savedId: String? = original?.id

    fun save() {
        if (title.isBlank() && body.isBlank()) return
        val existing = savedId?.let { AppGraph.notes.get(it) }
        if (existing != null) {
            if (existing.title != title || existing.body != body) AppGraph.notes.save(existing.copy(title = title.ifBlank { existing.title }, body = body))
        } else {
            savedId = AppGraph.notes.add(title, body, kind)
        }
    }
}

@Composable
fun NoteDetailScreen(entry: Entry, screen: Screen.NoteDetail) {
    val st = remember(entry) { entry.keep("note") { NoteEditState(screen.id) } }
    val nav = LocalNav.current
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    // simpan otomatis saat keluar dari halaman
    DisposableEffect(Unit) { onDispose { st.save() } }

    ScreenScaffold(
        topBar = {
            LeviTopBar(
                title = if (st.noteId == null && st.savedId == null) "Catatan baru" else st.kind,
                actions = {
                    IconButton(onClick = { st.editing = !st.editing; if (!st.editing) st.save() }) { Icon(Icons.Filled.Edit, "Edit") }
                    IconButton(onClick = { shareText(context, (st.title + "\n\n" + st.body).trim()) }) { Icon(Icons.Filled.Share, "Bagikan") }
                    if (st.savedId != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Hapus") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (st.editing) {
                OutlinedTextField(value = st.title, onValueChange = { st.title = it }, label = { Text("Judul") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = st.body, onValueChange = { st.body = it }, label = { Text("Isi") }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp))
            } else {
                Text(st.title, style = MaterialTheme.typography.headlineSmall)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(st.body, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (st.body.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { copyText(context, st.body) }) { Text("Salin") }
                    FilledTonalButton(onClick = {
                        val style = if (st.kind == NoteKind.TRANSCRIPT) Prompts.SummaryStyle.MINUTES else Prompts.SummaryStyle.POINTS
                        nav.go(Screen.Summarize(st.body, style))
                    }) { Text(if (st.kind == NoteKind.TRANSCRIPT) "Jadikan notulen" else "Ringkas") }
                    FilledTonalButton(onClick = { nav.go(Screen.Translate(st.body)) }) { Text("Terjemah") }
                }
                FilledTonalButton(onClick = {
                    nav.go(Screen.Chat(null, prefill = "Tentang catatan berikut:\n\"\"\"\n${st.body.take(6000)}\n\"\"\"\n\nPertanyaan saya: "))
                }) { Text("Tanya AI tentang catatan ini") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus catatan?") },
            text = { Text("Catatan ini akan dihapus permanen.") },
            confirmButton = {
                TextButton(onClick = {
                    st.savedId?.let { AppGraph.notes.delete(it) }
                    st.savedId = null
                    st.title = ""
                    st.body = ""
                    confirmDelete = false
                    nav.back()
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Memori
// ---------------------------------------------------------------------------

@Composable
fun MemoryScreen() {
    val items by AppGraph.memory.items.collectAsState()
    var useMemory by remember { mutableStateOf(AppGraph.prefs.useMemory) }
    var newText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    ScreenScaffold(topBar = { LeviTopBar("Memori", subtitle = "Hal yang Levi ingat tentang kamu") }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Levi hanya mengingat hal yang kamu setujui (misal ketik \"Ingat: saya kerja di bagian IT\" di Chat). " +
                        "Semua tersimpan di HP ini saja, dan bisa kamu ubah atau hapus kapan pun.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pakai memori saat chat", modifier = Modifier.weight(1f))
                    Switch(checked = useMemory, onCheckedChange = { useMemory = it; AppGraph.prefs.useMemory = it })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newText,
                        onValueChange = { newText = it },
                        placeholder = { Text("Tambah hal untuk diingat…") },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { AppGraph.memory.add(newText); newText = "" }, enabled = newText.isNotBlank()) {
                        Icon(Icons.Filled.Add, "Tambah")
                    }
                }
            }
            if (items.isEmpty()) {
                item { Text("Belum ada yang diingat.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(items, key = { it.id }) { m ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.text, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                        IconButton(onClick = { editing = m }) { Icon(Icons.Filled.Edit, "Ubah") }
                        IconButton(onClick = { AppGraph.memory.delete(m.id) }) { Icon(Icons.Filled.Delete, "Hapus") }
                    }
                }
            }
            if (items.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        TextButton(onClick = { confirmClear = true }) { Text("Hapus semua memori") }
                    }
                }
            }
        }
    }

    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Ubah memori") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { AppGraph.memory.edit(m.id, text); editing = null }) { Text("Simpan") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Batal") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Hapus semua memori?") },
            text = { Text("Levi akan lupa semua hal yang pernah kamu minta untuk diingat.") },
            confirmButton = { TextButton(onClick = { AppGraph.memory.clear(); confirmClear = false }) { Text("Hapus semua") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Batal") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Teks yang dibagikan dari aplikasi lain
// ---------------------------------------------------------------------------

@Composable
fun SharedTextScreen(screen: Screen.SharedText) {
    val nav = LocalNav.current
    val context = LocalContext.current
    ScreenScaffold(topBar = { LeviTopBar("Teks dari aplikasi lain") }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Text(screen.text, maxLines = 12, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(14.dp))
            }
            Text("Mau diapakan?", style = MaterialTheme.typography.titleSmall)
            FilledTonalButton(onClick = { nav.replace(Screen.Summarize(screen.text)) }, modifier = Modifier.fillMaxWidth()) { Text("Ringkas") }
            FilledTonalButton(onClick = { nav.replace(Screen.Translate(screen.text)) }, modifier = Modifier.fillMaxWidth()) { Text("Terjemahkan") }
            FilledTonalButton(onClick = {
                nav.replace(Screen.Chat(null, prefill = "Tentang teks berikut:\n\"\"\"\n${screen.text.take(6000)}\n\"\"\"\n\n"))
            }, modifier = Modifier.fillMaxWidth()) { Text("Tanya AI") }
            FilledTonalButton(onClick = {
                AppGraph.notes.add("", screen.text, NoteKind.NOTE)
                toast(context, "Disimpan ke Catatan")
                nav.back()
            }, modifier = Modifier.fillMaxWidth()) { Text("Simpan ke catatan") }
            Spacer(Modifier.padding(4.dp))
        }
    }
}
