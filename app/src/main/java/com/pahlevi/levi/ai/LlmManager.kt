package com.pahlevi.levi.ai

import com.pahlevi.levi.data.Prefs
import com.pahlevi.levi.llm.ChatMessage
import com.pahlevi.levi.llm.ContextFullException
import com.pahlevi.levi.llm.GenParams
import com.pahlevi.levi.llm.LlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed interface LlmStatus {
    data object NoModel : LlmStatus
    data class Idle(val name: String) : LlmStatus
    data class Loading(val name: String) : LlmStatus
    data class Ready(val name: String, val contextSize: Int) : LlmStatus
    data class Failed(val name: String, val message: String) : LlmStatus
}

class NoModelException : IllegalStateException("Belum ada model AI. Unduh dulu di Pengaturan → Model AI.")

/** Pengelola model AI offline: memuat model yang dipilih dan menyiapkan prompt agar muat di konteks. */
class LlmManager(private val prefs: Prefs, private val scope: CoroutineScope) {
    val engine = LlmEngine()
    private val loadMutex = Mutex()

    private val _status = MutableStateFlow<LlmStatus>(LlmStatus.NoModel)
    val status: StateFlow<LlmStatus> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** true selama AI sedang menulis jawaban */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        refreshIdle()
        scope.launch {
            prefs.llmModel.collect { refreshIdle() }
        }
    }

    fun selectedFile(): File? = prefs.llmModel.value?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }

    private fun displayName(f: File) = f.name.removeSuffix(".gguf")

    private fun refreshIdle() {
        val f = selectedFile()
        val cur = _status.value
        _status.value = when {
            f == null -> LlmStatus.NoModel
            engine.isLoaded && engine.modelPath == f.path -> LlmStatus.Ready(displayName(f), engine.contextSize)
            cur is LlmStatus.Loading -> cur
            else -> LlmStatus.Idle(displayName(f))
        }
    }

    suspend fun ensureLoaded() {
        loadMutex.withLock {
            val f = selectedFile() ?: run {
                _status.value = LlmStatus.NoModel
                throw NoModelException()
            }
            if (engine.isLoaded && engine.modelPath == f.path) return
            _status.value = LlmStatus.Loading(displayName(f))
            try {
                engine.load(f.path, prefs.contextSize, prefs.threads)
                _status.value = LlmStatus.Ready(displayName(f), engine.contextSize)
            } catch (e: Throwable) {
                _status.value = LlmStatus.Failed(displayName(f), e.message ?: "Gagal memuat model")
                throw e
            }
        }
    }

    /** Muat ulang (misal setelah mengubah panjang konteks / jumlah thread). */
    suspend fun reload() {
        loadMutex.withLock { engine.unload() }
        refreshIdle()
    }

    suspend fun unload() {
        loadMutex.withLock { engine.unload() }
        refreshIdle()
    }

    fun stop() = engine.stop()

    fun defaultParams(maxTokens: Int = prefs.maxAnswerTokens, temperature: Float = prefs.temperature) =
        GenParams(maxTokens = maxTokens, temperature = temperature)

    /** Aliran potongan jawaban. Pesan lama otomatis dipangkas kalau konteks penuh. */
    fun stream(messages: List<ChatMessage>, params: GenParams = defaultParams()): Flow<String> = flow {
        ensureLoaded()
        _busy.value = true
        try {
            val fitted = fit(messages, params.maxTokens)
            try {
                emitAll(engine.generate(fitted, params))
            } catch (e: ContextFullException) {
                // cadangan: pangkas lebih keras lalu coba sekali lagi
                emitAll(engine.generate(fit(fitted, params.maxTokens, extraMargin = 512), params))
            }
        } finally {
            _busy.value = false
        }
    }

    /** Jalankan sampai selesai dan kembalikan seluruh jawaban (tanpa blok <think>). */
    suspend fun complete(
        messages: List<ChatMessage>,
        params: GenParams = defaultParams(),
        onPartial: (String) -> Unit = {},
    ): String {
        val sb = StringBuilder()
        stream(messages, params).collect { piece ->
            sb.append(piece)
            onPartial(visibleText(sb.toString()))
        }
        return visibleText(sb.toString()).trim()
    }

    /** Berapa token kira-kira yang muat untuk isi dokumen dalam satu permintaan. */
    suspend fun inputBudget(reserveForAnswer: Int): Int {
        ensureLoaded()
        return (engine.contextSize - reserveForAnswer - 400).coerceAtLeast(256)
    }

    suspend fun countTokens(text: String): Int = engine.countTokens(text)

    private suspend fun fit(messages: List<ChatMessage>, maxTokens: Int, extraMargin: Int = 0): List<ChatMessage> {
        val ctx = engine.contextSize.takeIf { it > 0 } ?: prefs.contextSize
        val budget = ctx - maxTokens.coerceAtMost(ctx / 2) - 64 - extraMargin
        var list = messages
        var tokens = engine.promptTokens(list)
        // buang pesan lama (selain system & pesan terakhir) sampai muat
        while (tokens > budget) {
            val idx = list.indexOfFirst { it.role != ChatMessage.SYSTEM }
            if (idx < 0 || idx >= list.lastIndex) break
            list = list.toMutableList().also { it.removeAt(idx) }
            tokens = engine.promptTokens(list)
        }
        if (tokens > budget && list.isNotEmpty()) {
            // pesan terakhir sendiri terlalu panjang: potong isinya
            val last = list.last()
            val lastTokens = engine.countTokens(last.content).coerceAtLeast(1)
            val keepTokens = (lastTokens - (tokens - budget)).coerceAtLeast(64)
            val keepChars = (last.content.length.toLong() * keepTokens / lastTokens).toInt().coerceAtLeast(200)
            list = list.dropLast(1) + last.copy(content = last.content.take(keepChars) + "\n…(teks dipotong karena terlalu panjang)")
        }
        return list
    }

    companion object {
        private val thinkBlock = Regex("(?s)<think>.*?</think>")

        /** Sembunyikan bagian "berpikir" model (kalau ada) dari teks yang ditampilkan. */
        fun visibleText(raw: String): String {
            var t = raw.replace(thinkBlock, "")
            val open = t.indexOf("<think>")
            if (open >= 0) t = t.substring(0, open)
            return t.trimStart()
        }
    }
}
