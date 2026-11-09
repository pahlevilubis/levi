package com.pahlevi.levi.llm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class ChatMessage(val role: String, val content: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

data class GenParams(
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
)

class ContextFullException(message: String) : IllegalStateException(message)

/**
 * Mesin AI offline. Semua pemanggilan native berjalan di satu thread khusus,
 * jadi aman dipanggil dari mana saja.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class LlmEngine {
    private val thread = newSingleThreadContext("levi-llm")
    private val handle = AtomicLong(0L)

    @Volatile var modelPath: String? = null
        private set
    @Volatile var description: String = ""
        private set
    @Volatile var contextSize: Int = 0
        private set

    val isLoaded: Boolean get() = handle.get() != 0L

    suspend fun load(path: String, nCtx: Int, nThreads: Int) = withContext(thread) {
        ensureInit()
        unloadInternal()
        val h = LlamaNative.load(path, nCtx, nThreads)
        handle.set(h)
        modelPath = path
        description = LlamaNative.modelDescription(h)
        contextSize = LlamaNative.contextSize(h)
    }

    suspend fun unload() = withContext(thread) { unloadInternal() }

    private fun unloadInternal() {
        val h = handle.getAndSet(0L)
        if (h != 0L) LlamaNative.free(h)
        modelPath = null
        description = ""
        contextSize = 0
    }

    suspend fun countTokens(text: String): Int = withContext(thread) {
        val h = handle.get()
        if (h == 0L) text.length / 4 else LlamaNative.countTokens(h, text.toByteArray(Charsets.UTF_8))
    }

    suspend fun promptTokens(messages: List<ChatMessage>): Int = withContext(thread) {
        val h = handle.get()
        if (h == 0L) {
            messages.sumOf { it.content.length } / 4
        } else {
            LlamaNative.promptTokens(
                h,
                messages.map { it.role }.toTypedArray(),
                messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
            )
        }
    }

    /** Hentikan jawaban yang sedang ditulis. */
    fun stop() {
        val h = handle.get()
        if (h != 0L) LlamaNative.stop(h)
    }

    suspend fun resetCache() = withContext(thread) {
        val h = handle.get()
        if (h != 0L) LlamaNative.resetCache(h)
    }

    /** Hasilkan jawaban sebagai aliran potongan teks. Membatalkan pengumpulan = menghentikan AI. */
    fun generate(messages: List<ChatMessage>, params: GenParams = GenParams()): Flow<String> = callbackFlow {
        val h = handle.get()
        if (h == 0L) throw IllegalStateException("Model AI belum dimuat")
        try {
            LlamaNative.generate(
                h,
                messages.map { it.role }.toTypedArray(),
                messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                params.maxTokens,
                params.temperature,
                params.topP,
                params.topK,
                params.repeatPenalty,
                TokenCallback { bytes ->
                    trySend(String(bytes, Charsets.UTF_8)).isSuccess && isActive
                },
            )
            close()
        } catch (e: IllegalStateException) {
            val msg = e.message ?: ""
            close(if (msg.startsWith("CONTEXT_FULL")) ContextFullException(msg) else e)
        } catch (e: Throwable) {
            close(e)
        }
        awaitClose { LlamaNative.stop(h) }
    }.buffer(Channel.UNLIMITED).flowOn(thread)

    fun systemInfo(): String {
        ensureInit()
        return LlamaNative.systemInfo()
    }

    companion object {
        @Volatile private var initialized = false

        @Synchronized
        private fun ensureInit() {
            if (!initialized) {
                LlamaNative.init()
                initialized = true
            }
        }
    }
}
