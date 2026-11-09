package com.pahlevi.levi.ai

import com.pahlevi.levi.data.Prefs
import com.pahlevi.levi.whisper.WhisperEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class NoWhisperModelException : IllegalStateException("Belum ada model suara. Unduh dulu di Pengaturan → Model Suara.")

/** Pengelola model suara-ke-teks (Whisper). */
class WhisperManager(private val prefs: Prefs) {
    val engine = WhisperEngine()
    private val mutex = Mutex()

    fun selectedFile(): File? = prefs.whisperModel.value?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }

    suspend fun ensureLoaded() = mutex.withLock {
        val f = selectedFile() ?: throw NoWhisperModelException()
        engine.load(f.path)
    }

    suspend fun unload() = mutex.withLock { engine.unload() }
}
