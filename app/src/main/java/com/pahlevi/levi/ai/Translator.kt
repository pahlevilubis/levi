package com.pahlevi.levi.ai

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

data class Lang(val code: String, val name: String)

/** Terjemahan offline memakai Google ML Kit. Paket tiap bahasa (±30 MB) diunduh sekali. */
object Translator {
    val languages = listOf(
        Lang(TranslateLanguage.INDONESIAN, "Indonesia"),
        Lang(TranslateLanguage.ENGLISH, "Inggris"),
        Lang(TranslateLanguage.MALAY, "Melayu"),
        Lang(TranslateLanguage.ARABIC, "Arab"),
        Lang(TranslateLanguage.JAPANESE, "Jepang"),
        Lang(TranslateLanguage.KOREAN, "Korea"),
        Lang(TranslateLanguage.CHINESE, "Mandarin"),
        Lang(TranslateLanguage.THAI, "Thai"),
        Lang(TranslateLanguage.VIETNAMESE, "Vietnam"),
        Lang(TranslateLanguage.TAGALOG, "Tagalog"),
        Lang(TranslateLanguage.HINDI, "Hindi"),
        Lang(TranslateLanguage.DUTCH, "Belanda"),
        Lang(TranslateLanguage.GERMAN, "Jerman"),
        Lang(TranslateLanguage.FRENCH, "Prancis"),
        Lang(TranslateLanguage.SPANISH, "Spanyol"),
        Lang(TranslateLanguage.PORTUGUESE, "Portugis"),
        Lang(TranslateLanguage.ITALIAN, "Italia"),
        Lang(TranslateLanguage.RUSSIAN, "Rusia"),
        Lang(TranslateLanguage.TURKISH, "Turki"),
    )

    fun name(code: String): String = languages.firstOrNull { it.code == code }?.name ?: code

    /** Tebak bahasa teks; null kalau tidak yakin atau tidak didukung. */
    suspend fun detect(text: String): String? {
        val client = LanguageIdentification.getClient()
        return try {
            val tag = client.identifyLanguage(text.take(500)).await()
            if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
        } finally {
            client.close()
        }
    }

    suspend fun downloadedLanguages(): Set<String> =
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
            .map { it.language }
            .toSet()

    /** Bahasa yang paketnya belum ada di HP untuk pasangan ini */
    suspend fun missingFor(from: String, to: String): List<String> {
        val have = downloadedLanguages()
        return listOf(from, to, TranslateLanguage.ENGLISH).distinct().filter { it !in have }
    }

    suspend fun download(code: String) {
        val model = TranslateRemoteModel.Builder(code).build()
        RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build()).await()
    }

    suspend fun deleteLanguage(code: String) {
        val model = TranslateRemoteModel.Builder(code).build()
        RemoteModelManager.getInstance().deleteDownloadedModel(model).await()
    }

    /** Terjemahkan per baris supaya susunan paragraf tetap rapi. */
    suspend fun translate(text: String, from: String, to: String, onProgress: (Float) -> Unit = {}): String {
        if (from == to) return text
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
        )
        return try {
            val lines = text.split("\n")
            val out = ArrayList<String>(lines.size)
            lines.forEachIndexed { i, line ->
                out += if (line.isBlank()) line else translator.translate(line).await()
                onProgress((i + 1).toFloat() / lines.size)
            }
            out.joinToString("\n")
        } finally {
            translator.close()
        }
    }
}
