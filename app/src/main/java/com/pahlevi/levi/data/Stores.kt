package com.pahlevi.levi.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

// ---------------------------------------------------------------------------
// Pengaturan
// ---------------------------------------------------------------------------

enum class WebPermission { ASK, ALWAYS, NEVER }

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("levi", Context.MODE_PRIVATE)

    private val _llmModel = MutableStateFlow(sp.getString("llm_model", null))
    val llmModel: StateFlow<String?> = _llmModel.asStateFlow()
    fun setLlmModel(path: String?) {
        sp.edit().putString("llm_model", path).apply()
        _llmModel.value = path
    }

    private val _whisperModel = MutableStateFlow(sp.getString("whisper_model", null))
    val whisperModel: StateFlow<String?> = _whisperModel.asStateFlow()
    fun setWhisperModel(path: String?) {
        sp.edit().putString("whisper_model", path).apply()
        _whisperModel.value = path
    }

    var contextSize: Int
        get() = sp.getInt("ctx", 4096)
        set(v) = sp.edit().putInt("ctx", v).apply()

    var threads: Int
        get() = sp.getInt("threads", 4)
        set(v) = sp.edit().putInt("threads", v).apply()

    var temperature: Float
        get() = sp.getFloat("temp", 0.6f)
        set(v) = sp.edit().putFloat("temp", v).apply()

    var maxAnswerTokens: Int
        get() = sp.getInt("max_tokens", 768)
        set(v) = sp.edit().putInt("max_tokens", v).apply()

    var webPermission: WebPermission
        get() = runCatching { WebPermission.valueOf(sp.getString("web_perm", "ASK")!!) }.getOrDefault(WebPermission.ASK)
        set(v) = sp.edit().putString("web_perm", v.name).apply()

    var useMemory: Boolean
        get() = sp.getBoolean("use_memory", true)
        set(v) = sp.edit().putBoolean("use_memory", v).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v).apply()

    var whisperLanguage: String
        get() = sp.getString("whisper_lang", "id") ?: "id"
        set(v) = sp.edit().putString("whisper_lang", v).apply()

    var dictationLanguage: String
        get() = sp.getString("dict_lang", "id-ID") ?: "id-ID"
        set(v) = sp.edit().putString("dict_lang", v).apply()

    var lastTranslateFrom: String
        get() = sp.getString("tr_from", "auto") ?: "auto"
        set(v) = sp.edit().putString("tr_from", v).apply()

    var lastTranslateTo: String
        get() = sp.getString("tr_to", "en") ?: "en"
        set(v) = sp.edit().putString("tr_to", v).apply()

    /** id unduhan DownloadManager yang masih berjalan: namaFile -> id */
    fun pendingDownloads(): Map<String, Long> {
        val raw = sp.getString("downloads", "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return obj.keys().asSequence().associateWith { obj.getLong(it) }
    }

    fun setPendingDownload(fileName: String, id: Long?) {
        val obj = runCatching { JSONObject(sp.getString("downloads", "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (id == null) obj.remove(fileName) else obj.put(fileName, id)
        sp.edit().putString("downloads", obj.toString()).apply()
    }
}

// ---------------------------------------------------------------------------
// Penyimpanan JSON sederhana (semua data tetap di HP)
// ---------------------------------------------------------------------------

abstract class JsonListStore<T>(context: Context, fileName: String, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, fileName)
    private val mutex = Mutex()
    protected val _items = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    protected abstract fun toJson(item: T): JSONObject
    protected abstract fun fromJson(obj: JSONObject): T

    init {
        _items.value = runCatching {
            if (!file.exists()) emptyList()
            else {
                val arr = JSONArray(file.readText())
                (0 until arr.length()).mapNotNull { i -> runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    protected fun update(transform: (List<T>) -> List<T>) {
        _items.update(transform)
        persist()
    }

    private fun persist() {
        val snapshot = _items.value
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val arr = JSONArray()
                snapshot.forEach { arr.put(toJson(it)) }
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(file)) {
                    file.writeText(arr.toString())
                    tmp.delete()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Catatan
// ---------------------------------------------------------------------------

object NoteKind {
    const val NOTE = "Catatan"
    const val TRANSLATION = "Terjemahan"
    const val TRANSCRIPT = "Transkrip"
    const val SUMMARY = "Ringkasan"
    const val DOCUMENT = "Dokumen"
    const val WRITING = "Tulisan"
    const val DICTATION = "Dikte"
    const val CHAT = "Chat"
    val all = listOf(NOTE, TRANSCRIPT, SUMMARY, TRANSLATION, DOCUMENT, WRITING, DICTATION, CHAT)
}

data class Note(
    val id: String = newId(),
    val title: String,
    val body: String,
    val kind: String = NoteKind.NOTE,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
)

class NotesRepo(context: Context, scope: CoroutineScope) : JsonListStore<Note>(context, "notes.json", scope) {
    override fun toJson(item: Note) = JSONObject()
        .put("id", item.id).put("title", item.title).put("body", item.body)
        .put("kind", item.kind).put("created", item.created).put("updated", item.updated)

    override fun fromJson(obj: JSONObject) = Note(
        id = obj.getString("id"),
        title = obj.optString("title"),
        body = obj.optString("body"),
        kind = obj.optString("kind", NoteKind.NOTE),
        created = obj.optLong("created"),
        updated = obj.optLong("updated"),
    )

    fun get(id: String): Note? = items.value.firstOrNull { it.id == id }

    /** Simpan catatan baru, kembalikan id-nya */
    fun add(title: String, body: String, kind: String): String {
        val note = Note(title = title.ifBlank { autoTitle(body) }, body = body, kind = kind)
        update { listOf(note) + it }
        return note.id
    }

    fun save(note: Note) {
        val n = note.copy(updated = System.currentTimeMillis())
        update { list -> if (list.any { it.id == n.id }) list.map { if (it.id == n.id) n else it } else listOf(n) + list }
    }

    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    companion object {
        fun autoTitle(body: String): String {
            val line = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Tanpa judul"
            return if (line.length > 48) line.take(48).trimEnd() + "…" else line
        }
    }
}

// ---------------------------------------------------------------------------
// Memori: hal-hal tentang pengguna yang diingat Levi (hanya dengan izin)
// ---------------------------------------------------------------------------

data class MemoryItem(
    val id: String = newId(),
    val text: String,
    val created: Long = System.currentTimeMillis(),
)

class MemoryRepo(context: Context, scope: CoroutineScope) : JsonListStore<MemoryItem>(context, "memory.json", scope) {
    override fun toJson(item: MemoryItem) = JSONObject().put("id", item.id).put("text", item.text).put("created", item.created)
    override fun fromJson(obj: JSONObject) = MemoryItem(obj.getString("id"), obj.optString("text"), obj.optLong("created"))

    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty() || items.value.any { it.text.equals(t, ignoreCase = true) }) return
        update { it + MemoryItem(text = t) }
    }

    fun edit(id: String, text: String) = update { list -> list.map { if (it.id == id) it.copy(text = text.trim()) else it } }
    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }
    fun clear() = update { emptyList() }
}

// ---------------------------------------------------------------------------
// Riwayat chat
// ---------------------------------------------------------------------------

data class Source(val title: String, val url: String)

data class StoredMessage(
    val role: String,
    val text: String,
    val sources: List<Source> = emptyList(),
)

data class ChatSession(
    val id: String = newId(),
    val title: String = "Obrolan baru",
    val messages: List<StoredMessage> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
)

class ChatRepo(context: Context, scope: CoroutineScope) : JsonListStore<ChatSession>(context, "chats.json", scope) {
    override fun toJson(item: ChatSession): JSONObject {
        val msgs = JSONArray()
        item.messages.forEach { m ->
            val src = JSONArray()
            m.sources.forEach { src.put(JSONObject().put("t", it.title).put("u", it.url)) }
            msgs.put(JSONObject().put("r", m.role).put("x", m.text).put("s", src))
        }
        return JSONObject().put("id", item.id).put("title", item.title).put("updated", item.updated).put("messages", msgs)
    }

    override fun fromJson(obj: JSONObject): ChatSession {
        val arr = obj.optJSONArray("messages") ?: JSONArray()
        val msgs = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val srcArr = m.optJSONArray("s") ?: JSONArray()
            StoredMessage(
                role = m.optString("r"),
                text = m.optString("x"),
                sources = (0 until srcArr.length()).map { j ->
                    val s = srcArr.getJSONObject(j)
                    Source(s.optString("t"), s.optString("u"))
                },
            )
        }
        return ChatSession(obj.getString("id"), obj.optString("title"), msgs, obj.optLong("updated"))
    }

    fun get(id: String): ChatSession? = items.value.firstOrNull { it.id == id }

    fun save(session: ChatSession) {
        val s = session.copy(updated = System.currentTimeMillis())
        update { list -> listOf(s) + list.filterNot { it.id == s.id } }
    }

    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }
    fun clear() = update { emptyList() }
}
