package com.pahlevi.levi.llm

/** Dipanggil dari native setiap ada potongan teks baru. Kembalikan false untuk berhenti. */
fun interface TokenCallback {
    fun onToken(piece: ByteArray): Boolean
}

/** Pemanggilan langsung ke library native (llama.cpp). Gunakan lewat [LlmEngine]. */
object LlamaNative {
    init {
        System.loadLibrary("levi_llm")
    }

    @JvmStatic external fun init()
    @JvmStatic external fun systemInfo(): String
    @JvmStatic external fun load(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun contextSize(handle: Long): Int
    @JvmStatic external fun modelDescription(handle: Long): String
    @JvmStatic external fun countTokens(handle: Long, text: ByteArray): Int
    @JvmStatic external fun promptTokens(handle: Long, roles: Array<String>, contents: Array<ByteArray>): Int
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun resetCache(handle: Long)
    @JvmStatic external fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<ByteArray>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callback: TokenCallback,
    ): Int
}
