package com.pahlevi.levi.ai

import com.pahlevi.levi.ai.Prompts.SummaryStyle

/** Tugas AI yang lebih dari satu langkah (misal meringkas teks yang sangat panjang). */
object AiTasks {

    /**
     * Ringkas teks. Kalau teks lebih panjang dari kapasitas model, teks dipecah per bagian,
     * tiap bagian diringkas, lalu hasilnya digabung.
     */
    suspend fun summarize(
        llm: LlmManager,
        text: String,
        style: SummaryStyle,
        onStatus: (String) -> Unit = {},
        onPartial: (String) -> Unit = {},
    ): String {
        val answerTokens = 800
        onStatus("Menyiapkan model…")
        val budget = llm.inputBudget(answerTokens)
        val tokens = llm.countTokens(text)
        val params = llm.defaultParams(maxTokens = answerTokens, temperature = 0.3f)

        if (tokens <= budget) {
            onStatus("Meringkas…")
            return llm.complete(Prompts.chat(Prompts.SUMMARY_SYSTEM, Prompts.summaryUser(text, style)), params, onPartial)
        }

        val chunks = splitText(text, tokens, budget)
        val partials = mutableListOf<String>()
        chunks.forEachIndexed { i, chunk ->
            onStatus("Meringkas bagian ${i + 1} dari ${chunks.size}…")
            val info = "bagian ${i + 1} dari ${chunks.size}"
            partials += llm.complete(
                Prompts.chat(Prompts.SUMMARY_SYSTEM, Prompts.summaryUser(chunk, SummaryStyle.POINTS, info)),
                llm.defaultParams(maxTokens = 500, temperature = 0.3f),
            ) { onPartial("(${info})\n$it") }
        }

        val joined = partials.joinToString("\n\n")
        return if (llm.countTokens(joined) <= budget) {
            onStatus("Menggabungkan ringkasan…")
            llm.complete(Prompts.chat(Prompts.SUMMARY_SYSTEM, Prompts.combineUser(partials, style)), params, onPartial)
        } else {
            // masih terlalu panjang: ringkas lagi ringkasannya
            summarize(llm, joined, style, onStatus, onPartial)
        }
    }

    /** Pecah teks menjadi potongan yang masing-masing muat dalam [budgetTokens], mengikuti batas paragraf. */
    fun splitText(text: String, totalTokens: Int, budgetTokens: Int): List<String> {
        val charsPerToken = (text.length.toFloat() / totalTokens.coerceAtLeast(1)).coerceIn(1.5f, 6f)
        val maxChars = (budgetTokens * charsPerToken * 0.85f).toInt().coerceAtLeast(500)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (para in text.split("\n")) {
            var p = para
            while (p.length > maxChars) {
                if (current.isNotEmpty()) {
                    chunks += current.toString()
                    current.clear()
                }
                chunks += p.take(maxChars)
                p = p.drop(maxChars)
            }
            if (current.length + p.length + 1 > maxChars && current.isNotEmpty()) {
                chunks += current.toString()
                current.clear()
            }
            current.append(p).append('\n')
        }
        if (current.isNotBlank()) chunks += current.toString()
        return chunks.filter { it.isNotBlank() }
    }

    /** Potong dokumen supaya muat sebagai konteks tanya-jawab. */
    suspend fun fitDocument(llm: LlmManager, doc: String, reserve: Int): String {
        val budget = llm.inputBudget(reserve)
        val tokens = llm.countTokens(doc)
        if (tokens <= budget) return doc
        val keep = (doc.length.toLong() * budget / tokens).toInt()
        return doc.take(keep) + "\n…(dokumen terlalu panjang, hanya bagian awal yang dibaca)"
    }
}
