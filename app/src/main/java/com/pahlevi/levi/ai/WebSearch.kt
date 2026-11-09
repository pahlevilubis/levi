package com.pahlevi.levi.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

data class SearchResult(val title: String, val url: String, val snippet: String)
data class WebPage(val title: String, val url: String, val text: String)

/**
 * Pencarian web sederhana tanpa akun/API key: DuckDuckGo (versi HTML),
 * dengan cadangan Wikipedia bahasa Indonesia. Hanya dipanggil setelah pengguna mengizinkan.
 */
object WebSearch {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    suspend fun search(query: String, max: Int = 5): List<SearchResult> = withContext(Dispatchers.IO) {
        val ddg = runCatching { duckDuckGo(query, max) }.getOrDefault(emptyList())
        ddg.ifEmpty { runCatching { wikipedia(query, max) }.getOrDefault(emptyList()) }
    }

    private fun duckDuckGo(query: String, max: Int): List<SearchResult> {
        val doc = Jsoup.connect("https://html.duckduckgo.com/html/")
            .userAgent(UA)
            .data("q", query)
            .data("kl", "id-id")
            .timeout(15_000)
            .post()
        return doc.select("div.result").mapNotNull { el ->
            if (el.hasClass("result--ad")) return@mapNotNull null
            val a = el.selectFirst("a.result__a") ?: return@mapNotNull null
            val url = cleanDdgUrl(a.attr("href"))
            if (!url.startsWith("http")) return@mapNotNull null
            SearchResult(a.text(), url, el.selectFirst(".result__snippet")?.text().orEmpty())
        }.distinctBy { it.url }.take(max)
    }

    private fun cleanDdgUrl(href: String): String {
        val full = if (href.startsWith("//")) "https:$href" else href
        val idx = full.indexOf("uddg=")
        if (idx < 0) return full
        val enc = full.substring(idx + 5).substringBefore('&')
        return URLDecoder.decode(enc, "UTF-8")
    }

    private fun wikipedia(query: String, max: Int): List<SearchResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = Jsoup.connect("https://id.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=$max&srsearch=$q")
            .userAgent(UA).ignoreContentType(true).timeout(15_000).execute().body()
        val arr = JSONObject(body).getJSONObject("query").getJSONArray("search")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val title = o.getString("title")
            SearchResult(
                title = title,
                url = "https://id.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                snippet = Jsoup.parse(o.optString("snippet")).text(),
            )
        }
    }

    /** Ambil isi teks utama sebuah halaman (dipotong supaya muat di konteks AI). */
    suspend fun fetchText(url: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val doc = Jsoup.connect(url).userAgent(UA).timeout(15_000).followRedirects(true).get()
        doc.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg").remove()
        val main = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        val text = main?.select("p, li, h1, h2, h3, td")?.joinToString("\n") { it.text() }
            ?.lines()?.map { it.trim() }?.filter { it.length > 30 }?.joinToString("\n").orEmpty()
        text.ifBlank { main?.text().orEmpty() }.take(maxChars)
    }

    /** Cari lalu baca beberapa halaman teratas. */
    suspend fun searchAndRead(query: String, pages: Int = 3, charsPerPage: Int = 1800, onStatus: (String) -> Unit): List<WebPage> {
        onStatus("Mencari di internet…")
        val results = search(query)
        if (results.isEmpty()) return emptyList()
        onStatus("Membaca ${minOf(pages, results.size)} halaman…")
        return coroutineScope {
            results.take(pages).map { r ->
                async {
                    val text = runCatching { fetchText(r.url, charsPerPage) }.getOrDefault("").ifBlank { r.snippet }
                    WebPage(r.title, r.url, text)
                }
            }.awaitAll().filter { it.text.isNotBlank() }
        }
    }
}
