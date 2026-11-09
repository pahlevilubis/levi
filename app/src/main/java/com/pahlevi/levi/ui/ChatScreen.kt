@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.pahlevi.levi.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.pahlevi.levi.ai.LlmManager
import com.pahlevi.levi.ai.Prompts
import com.pahlevi.levi.ai.WebSearch
import com.pahlevi.levi.audio.Dictation
import com.pahlevi.levi.data.ChatSession
import com.pahlevi.levi.data.NoteKind
import com.pahlevi.levi.data.NotesRepo
import com.pahlevi.levi.data.Source
import com.pahlevi.levi.data.StoredMessage
import com.pahlevi.levi.llm.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Daftar obrolan
// ---------------------------------------------------------------------------

@Composable
fun ChatListScreen() {
    val nav = LocalNav.current
    val sessions by AppGraph.chats.items.collectAsState()
    var confirmDelete by remember { mutableStateOf<ChatSession?>(null) }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale("id", "ID")) }

    ScreenScaffold(
        topBar = { LeviTopBar("Chat", showBack = false) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.go(Screen.Chat(null)) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Obrolan baru") },
            )
        },
    ) { pad ->
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Belum ada obrolan. Tekan \"Obrolan baru\" untuk mulai bertanya ke Levi.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sessions, key = { it.id }) { s ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = { nav.go(Screen.Chat(s.id)) },
                            onLongClick = { confirmDelete = s },
                        ),
                    ) {
                        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    s.messages.lastOrNull()?.text?.replace('\n', ' ') ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(fmt.format(Date(s.updated)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = { confirmDelete = s }) { Icon(Icons.Filled.Delete, "Hapus") }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Hapus obrolan?") },
            text = { Text("\"${s.title}\" akan dihapus dari HP.") },
            confirmButton = { TextButton(onClick = { AppGraph.chats.delete(s.id); confirmDelete = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Batal") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Satu obrolan
// ---------------------------------------------------------------------------

class ChatState(id: String?, prefill: String) {
    var session by mutableStateOf(id?.let { AppGraph.chats.get(it) } ?: ChatSession())
    var input by mutableStateOf(prefill)
    var useWeb by mutableStateOf(false)
    var streaming by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var memorySuggestion by mutableStateOf<String?>(null)
    var running by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun send(scope: CoroutineScope, gate: InternetGate) {
        val text = input.trim()
        if (text.isEmpty() || running) return
        if (useWeb) {
            gate.request("Levi akan mencari di internet: \"${text.take(80)}\"") { start(scope, text, web = true) }
        } else {
            start(scope, text, web = false)
        }
    }

    private fun start(scope: CoroutineScope, text: String, web: Boolean) {
        input = ""
        error = null
        val isFirst = session.messages.isEmpty()
        session = session.copy(
            title = if (isFirst) NotesRepo.autoTitle(text) else session.title,
            messages = session.messages + StoredMessage(ChatMessage.USER, text),
        )
        AppGraph.chats.save(session)

        running = true
        job = scope.launch {
            val sb = StringBuilder()
            var sources = emptyList<Source>()
            streaming = ""
            try {
                val prefs = AppGraph.prefs
                val memories = if (prefs.useMemory) AppGraph.memory.items.value else emptyList()
                val msgs = mutableListOf(ChatMessage(ChatMessage.SYSTEM, Prompts.chatSystem(prefs.userName, memories)))
                val history = session.messages.map { ChatMessage(it.role, it.text) }
                if (web) {
                    val pages = WebSearch.searchAndRead(text) { status = it }
                    if (pages.isEmpty()) {
                        msgs += history.dropLast(1)
                        msgs += ChatMessage(ChatMessage.USER, "$text\n\n(Catatan: pencarian internet tidak menemukan hasil. Beri tahu pengguna, lalu jawab sebisanya dan sebutkan bahwa itu tanpa sumber.)")
                    } else {
                        sources = pages.map { Source(it.title, it.url) }
                        msgs += history.dropLast(1)
                        msgs += ChatMessage(ChatMessage.USER, Prompts.webAnswerUser(text, pages))
                    }
                } else {
                    msgs += history
                }
                status = "Levi sedang berpikir…"
                AppGraph.llm.stream(msgs).collect { piece ->
                    sb.append(piece)
                    status = null
                    streaming = LlmManager.visibleText(sb.toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Terjadi kesalahan"
            } finally {
                val answer = LlmManager.visibleText(sb.toString()).trim()
                if (answer.isNotEmpty()) {
                    session = session.copy(messages = session.messages + StoredMessage(ChatMessage.ASSISTANT, answer, sources))
                    AppGraph.chats.save(session)
                }
                streaming = null
                status = null
                running = false
            }
            memoryRequest(text)?.let { memorySuggestion = it }
        }
    }

    fun stop() = AppGraph.llm.stop()

    companion object {
        private val memoryRegex = Regex("^(?:tolong\\s+)?ingat(?:lah|kan)?(?:\\s+(?:ya|bahwa|kalau))?\\s*[:,]?\\s+(.{3,})", RegexOption.IGNORE_CASE)
        fun memoryRequest(text: String): String? = memoryRegex.find(text.trim())?.groupValues?.get(1)?.trim()
    }
}

@Composable
fun ChatScreen(entry: Entry, screen: Screen.Chat) {
    val st = remember(entry) { entry.keep("chat") { ChatState(screen.id, screen.prefill) } }
    val gate = LocalInternetGate.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val dictation = remember { Dictation(context) }
    val dict by dictation.state.collectAsState()
    var menuFor by remember { mutableStateOf<StoredMessage?>(null) }
    var askNewChat by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { dictation.stop() } }

    // muat model lebih awal supaya jawaban pertama lebih cepat
    LaunchedEffect(Unit) { runCatching { AppGraph.llm.ensureLoaded() } }

    val nav = LocalNav.current
    val count = st.session.messages.size + (if (st.streaming != null) 1 else 0)
    LaunchedEffect(count, st.streaming?.length, st.status) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last > 0) listState.scrollToItem(last)
    }

    ScreenScaffold(
        topBar = {
            LeviTopBar(
                title = st.session.title,
                subtitle = if (st.useWeb) "Mode cari web aktif" else "Offline",
                actions = {
                    IconButton(onClick = {
                        if (st.session.messages.isNotEmpty()) {
                            val body = st.session.messages.joinToString("\n\n") {
                                (if (it.role == ChatMessage.USER) "Saya: " else "Levi: ") + it.text
                            }
                            AppGraph.notes.add(st.session.title, body, NoteKind.CHAT)
                            toast(context, "Obrolan disimpan ke Catatan")
                        }
                    }) { Icon(Icons.Filled.Save, "Simpan ke catatan") }
                    IconButton(onClick = { askNewChat = true }) { Icon(Icons.Filled.Add, "Obrolan baru") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { LlmStatusHint() }
                if (st.session.messages.isEmpty() && st.streaming == null) {
                    item {
                        Text(
                            "Tanya apa saja. Contoh: \"Jelaskan bedanya inflasi dan deflasi\", \"Buatkan ide menu sahur\", " +
                                "atau \"Ingat: saya kerja di bagian IT\" supaya Levi mengingatnya.\n\n" +
                                "Nyalakan ikon bola dunia kalau butuh info terbaru dari internet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(st.session.messages) { m ->
                    MessageBubble(m, onLongPress = { menuFor = m })
                }
                st.streaming?.let { text ->
                    item { MessageBubble(StoredMessage(ChatMessage.ASSISTANT, text.ifEmpty { "…" }), onLongPress = {}) }
                }
                item {
                    StatusLine(st.status)
                    ErrorText(st.error)
                }
            }

            if (dict.listening) {
                Text(
                    "Mendengarkan… ${dict.partial}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            dict.error?.let { ErrorText(it) }

            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconToggleButton(checked = st.useWeb, onCheckedChange = { st.useWeb = it }) {
                        Icon(
                            Icons.Filled.Public,
                            contentDescription = "Cari di web",
                            tint = if (st.useWeb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = st.input,
                        onValueChange = { st.input = it },
                        placeholder = { Text("Tulis pesan…") },
                        modifier = Modifier.weight(1f),
                        maxLines = 6,
                        shape = RoundedCornerShape(20.dp),
                    )
                    MicButton(dictation, onText = { st.input = (st.input + " " + it).trim() })
                    if (st.running) {
                        FilledIconButton(onClick = { st.stop() }) { Icon(Icons.Filled.Stop, "Hentikan") }
                    } else {
                        FilledIconButton(onClick = { st.send(entry.scope, gate) }, enabled = st.input.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Kirim")
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { m ->
        MessageMenu(m, onDismiss = { menuFor = null }, onRemember = { st.memorySuggestion = m.text })
    }

    st.memorySuggestion?.let { suggestion ->
        var text by remember(suggestion) { mutableStateOf(suggestion) }
        AlertDialog(
            onDismissRequest = { st.memorySuggestion = null },
            title = { Text("Simpan ke memori Levi?") },
            text = {
                Column {
                    Text("Levi akan mengingat ini di obrolan berikutnya. Kamu bisa ubah atau hapus kapan saja di menu Memori.")
                    Spacer(Modifier.padding(4.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppGraph.memory.add(text)
                    st.memorySuggestion = null
                    toast(context, "Diingat")
                }) { Text("Ingat") }
            },
            dismissButton = { TextButton(onClick = { st.memorySuggestion = null }) { Text("Jangan") } },
        )
    }

    if (askNewChat) {
        AlertDialog(
            onDismissRequest = { askNewChat = false },
            title = { Text("Mulai obrolan baru?") },
            text = { Text("Obrolan ini tetap tersimpan di daftar Chat.") },
            confirmButton = {
                TextButton(onClick = {
                    askNewChat = false
                    nav.replace(Screen.Chat(null))
                }) { Text("Mulai") }
            },
            dismissButton = { TextButton(onClick = { askNewChat = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun MessageBubble(m: StoredMessage, onLongPress: () -> Unit) {
    val isUser = m.role == ChatMessage.USER
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp).combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                if (m.sources.isNotEmpty()) {
                    Spacer(Modifier.padding(3.dp))
                    Text("Sumber:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    m.sources.forEachIndexed { i, s ->
                        AssistChip(
                            onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url))) }
                            },
                            label = { Text("[${i + 1}] ${s.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageMenu(m: StoredMessage, onDismiss: () -> Unit, onRemember: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pesan") },
        text = {
            Column {
                TextButton(onClick = { copyText(context, m.text); onDismiss() }) { Text("Salin") }
                TextButton(onClick = { shareText(context, m.text); onDismiss() }) { Text("Bagikan") }
                TextButton(onClick = {
                    AppGraph.notes.add("", m.text, NoteKind.NOTE)
                    toast(context, "Disimpan ke Catatan")
                    onDismiss()
                }) { Text("Simpan ke catatan") }
                if (m.role == ChatMessage.USER) {
                    TextButton(onClick = { onDismiss(); onRemember() }) { Text("Ingat ini (simpan ke memori)") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

/** Tombol mikrofon untuk dikte ke kolom teks (meminta izin mikrofon dulu). */
@Composable
fun MicButton(dictation: Dictation, language: String = AppGraph.prefs.dictationLanguage, onText: (String) -> Unit) {
    val state by dictation.state.collectAsState()
    val askMic = rememberMicPermission { dictation.start(language, onText) }
    IconButton(onClick = {
        if (state.listening) dictation.stop() else askMic()
    }) {
        Icon(
            if (state.listening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = "Dikte",
            tint = if (state.listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}
