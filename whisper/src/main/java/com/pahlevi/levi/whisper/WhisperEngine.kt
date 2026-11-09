package com.pahlevi.levi.whisper

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class Segment(val startMs: Long, val endMs: Long, val text: String)

/** Mesin suara-ke-teks offline (whisper.cpp). Semua kerja native di satu thread khusus. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class WhisperEngine {
    private val thread = newSingleThreadContext("levi-whisper")
    private val handle = AtomicLong(0L)

    @Volatile var modelPath: String? = null
        private set

    val isLoaded: Boolean get() = handle.get() != 0L

    suspend fun load(path: String) = withContext(thread) {
        if (modelPath == path && handle.get() != 0L) return@withContext
        val old = handle.getAndSet(0L)
        if (old != 0L) WhisperNative.free(old)
        handle.set(WhisperNative.load(path))
        modelPath = path
    }

    suspend fun unload() = withContext(thread) {
        val old = handle.getAndSet(0L)
        if (old != 0L) WhisperNative.free(old)
        modelPath = null
    }

    fun stop() {
        val h = handle.get()
        if (h != 0L) WhisperNative.stop(h)
    }

    /**
     * Transkrip file WAV 16 kHz. [onSegment] dipanggil setiap ada kalimat baru,
     * kembalikan false untuk berhenti.
     */
    suspend fun transcribe(
        wavPath: String,
        language: String,
        threads: Int,
        startMs: Int = 0,
        onProgress: (Int) -> Unit,
        onSegment: (Segment) -> Boolean,
    ): Int = withContext(thread) {
        val h = handle.get()
        check(h != 0L) { "Model suara belum dimuat" }
        val segmentHandler = onSegment
        val progressHandler = onProgress
        WhisperNative.transcribeWav(h, wavPath, language, threads, startMs, object : WhisperCallback {
            override fun onSegment(startMs: Long, endMs: Long, text: ByteArray): Boolean =
                segmentHandler(Segment(startMs, endMs, String(text, Charsets.UTF_8).trim()))

            override fun onProgress(percent: Int) {
                progressHandler(percent)
            }
        })
    }
}
