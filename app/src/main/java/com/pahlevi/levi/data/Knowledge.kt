package com.pahlevi.levi.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Penilaian situs: kamu yang memutuskan situs mana yang bisa dipercaya
// ---------------------------------------------------------------------------

data class SiteRating(val domain: String, val trusted: Boolean, val decided: Long = System.currentTimeMillis())

class SiteRatingRepo(context: Context, scope: CoroutineScope) : JsonListStore<SiteRating>(context, "sites.json", scope) {
    override fun toJson(item: SiteRating) = JSONObject().put("d", item.domain).put("t", item.trusted).put("at", item.decided)
    override fun fromJson(obj: JSONObject) = SiteRating(obj.getString("d"), obj.optBoolean("t"), obj.optLong("at"))

    fun rating(domain: String): Boolean? = items.value.firstOrNull { it.domain == domain }?.trusted

    fun set(domain: String, trusted: Boolean) {
        val d = normalize(domain)
        if (d.isBlank()) return
        update { list -> list.filterNot { it.domain == d } + SiteRating(d, trusted) }
    }

    fun remove(domain: String) = update { list -> list.filterNot { it.domain == domain } }

    companion object {
        /** "https://www.kompas.com/x" -> "kompas.com" */
        fun normalize(urlOrDomain: String): String {
            val host = if (urlOrDomain.contains("://")) Uri.parse(urlOrDomain).host ?: "" else urlOrDomain
            return host.lowercase().trim().removePrefix("www.").removePrefix("m.")
        }
    }
}

// ---------------------------------------------------------------------------
// Pengetahuan: hal yang Levi pelajari dari web, hanya setelah kamu setujui
// ---------------------------------------------------------------------------

data class KnowledgeItem(
    val id: String = newId(),
    val text: String,
    val sources: List<Source> = emptyList(),
    val created: Long = System.currentTimeMillis(),
)

class KnowledgeRepo(context: Context, scope: CoroutineScope) : JsonListStore<KnowledgeItem>(context, "knowledge.json", scope) {
    override fun toJson(item: KnowledgeItem): JSONObject {
        val src = JSONArray()
        item.sources.forEach { src.put(JSONObject().put("t", it.title).put("u", it.url)) }
        return JSONObject().put("id", item.id).put("text", item.text).put("src", src).put("at", item.created)
    }

    override fun fromJson(obj: JSONObject): KnowledgeItem {
        val arr = obj.optJSONArray("src") ?: JSONArray()
        val src = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Source(it.optString("t"), it.optString("u")) } }
        return KnowledgeItem(obj.getString("id"), obj.optString("text"), src, obj.optLong("at"))
    }

    fun add(text: String, sources: List<Source>) {
        if (text.isBlank()) return
        update { listOf(KnowledgeItem(text = text.trim(), sources = sources)) + it }
    }

    fun edit(id: String, text: String) = update { list -> list.map { if (it.id == id) it.copy(text = text.trim()) else it } }
    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    /** Cari pengetahuan yang paling nyambung dengan pertanyaan (pencocokan kata sederhana, offline). */
    fun relevant(query: String, max: Int = 3): List<KnowledgeItem> {
        val q = words(query)
        if (q.isEmpty()) return emptyList()
        return items.value
            .map { item ->
                val w = words(item.text)
                val shared = q.count { it in w }
                item to if (w.isEmpty()) 0.0 else shared / sqrt(w.size.toDouble())
            }
            .filter { it.second > 0.0 && q.count { w -> w in words(it.first.text) } >= minOf(2, q.size) }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
    }

    companion object {
        private val stop = setOf(
            "yang", "dan", "di", "ke", "dari", "ini", "itu", "apa", "apakah", "bagaimana", "kenapa", "mengapa",
            "untuk", "dengan", "pada", "adalah", "saya", "aku", "kamu", "dia", "kami", "kita", "mereka", "tidak",
            "ada", "bisa", "akan", "sudah", "belum", "juga", "atau", "karena", "jadi", "kalau", "jika", "tentang",
            "the", "and", "for", "what", "how", "is", "are", "of", "to", "in", "a", "an", "tolong", "dong", "ya",
        )

        fun words(text: String): Set<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 2 && it !in stop }
                .toSet()
    }
}
