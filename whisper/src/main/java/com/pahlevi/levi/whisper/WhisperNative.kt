package com.pahlevi.levi.whisper

/** Dipanggil dari native: setiap potongan kalimat baru & persentase kemajuan. */
interface WhisperCallback {
    /** Kembalikan false untuk menghentikan transkripsi. */
    fun onSegment(startMs: Long, endMs: Long, text: ByteArray): Boolean
    fun onProgress(percent: Int)
}

/** Pemanggilan langsung ke library native (whisper.cpp). Gunakan lewat [WhisperEngine]. */
object WhisperNative {
    init {
        System.loadLibrary("levi_whisper")
    }

    @JvmStatic external fun load(path: String): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun systemInfo(): String
    @JvmStatic external fun transcribeWav(
        handle: Long,
        wavPath: String,
        language: String,
        threads: Int,
        startMs: Int,
        callback: WhisperCallback,
    ): Int
}
