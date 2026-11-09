package com.pahlevi.levi.ai

enum class ModelType { LLM, WHISPER }

data class CatalogModel(
    val id: String,
    val type: ModelType,
    val name: String,
    val fileName: String,
    val url: String,
    val sizeMb: Int,
    val description: String,
    val recommended: Boolean = false,
)

/** Daftar model yang bisa diunduh langsung dari dalam aplikasi (sumber: Hugging Face). */
object ModelCatalog {
    val llm = listOf(
        CatalogModel(
            id = "qwen35-4b",
            type = ModelType.LLM,
            name = "Qwen3.5 4B",
            fileName = "Qwen3.5-4B-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
            sizeMb = 2740,
            description = "Paling pintar di daftar ini. Cocok untuk HP RAM 8–12 GB seperti Galaxy S21 Ultra.",
            recommended = true,
        ),
        CatalogModel(
            id = "qwen35-2b",
            type = ModelType.LLM,
            name = "Qwen3.5 2B",
            fileName = "Qwen3.5-2B-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
            sizeMb = 1280,
            description = "Ringan dan cepat, hemat baterai. Jawaban lebih sederhana.",
        ),
        CatalogModel(
            id = "gemma4-e2b",
            type = ModelType.LLM,
            name = "Gemma 4 E2B",
            fileName = "gemma-4-E2B-it-Q4_0.gguf",
            url = "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf",
            sizeMb = 2840,
            description = "Buatan Google. Kuat di banyak bahasa, alternatif kalau Qwen kurang cocok.",
        ),
    )

    val whisper = listOf(
        CatalogModel(
            id = "whisper-small",
            type = ModelType.WHISPER,
            name = "Whisper Small",
            fileName = "ggml-small-q5_1.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            sizeMb = 181,
            description = "Seimbang: cukup akurat untuk bahasa Indonesia, kecepatan sedang.",
            recommended = true,
        ),
        CatalogModel(
            id = "whisper-base",
            type = ModelType.WHISPER,
            name = "Whisper Base",
            fileName = "ggml-base-q5_1.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            sizeMb = 57,
            description = "Paling cepat dan kecil, tapi lebih sering salah dengar.",
        ),
        CatalogModel(
            id = "whisper-turbo",
            type = ModelType.WHISPER,
            name = "Whisper Large v3 Turbo",
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            sizeMb = 547,
            description = "Paling akurat, tapi lambat di HP. Untuk rekaman penting yang tidak buru-buru.",
        ),
    )

    fun all() = llm + whisper
}
