package com.pahlevi.levi.ai

import com.pahlevi.levi.data.KnowledgeItem
import com.pahlevi.levi.data.MemoryItem
import com.pahlevi.levi.data.SiteRatingRepo
import com.pahlevi.levi.llm.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Semua instruksi (prompt) untuk AI dikumpulkan di sini supaya mudah diubah. */
object Prompts {
    private fun today(): String =
        SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id", "ID")).format(Date())

    fun chatSystem(userName: String, memories: List<MemoryItem>, knowledge: List<KnowledgeItem> = emptyList()): String = buildString {
        append("Kamu adalah Levi, asisten AI yang berjalan offline di HP")
        if (userName.isNotBlank()) append(" milik $userName")
        append(". Hari ini ${today()}. ")
        append("Jawab dalam bahasa Indonesia yang jelas, ramah, dan ringkas, kecuali pengguna memakai atau meminta bahasa lain. ")
        append("Kalau kamu tidak yakin atau tidak tahu, katakan terus terang — jangan mengarang fakta, angka, nama, atau tautan. ")
        append("Kamu tidak bisa membuka internet sendiri dan tidak bisa mengubah apa pun di HP. ")
        append("Kamu tidak bisa mengirim pesan sendiri; kalau pengguna mau mengirim pesan, sarankan memakai menu Kirim Pesan (selalu dengan izinnya).")
        if (memories.isNotEmpty()) {
            append("\n\nHal yang pengguna minta kamu ingat tentang dirinya:\n")
            memories.forEach { append("- ").append(it.text).append('\n') }
            append("Gunakan informasi ini hanya kalau relevan dengan pertanyaannya.")
        }
        if (knowledge.isNotEmpty()) {
            append("\n\nPengetahuan yang sudah kamu pelajari dari situs yang dipercaya pengguna (pakai kalau relevan, sebutkan sumbernya):\n")
            knowledge.forEach { k ->
                append("- ").append(k.text.take(800))
                if (k.sources.isNotEmpty()) append(" (sumber: ").append(k.sources.joinToString(", ") { SiteRatingRepo.normalize(it.url) }).append(")")
                append('\n')
            }
        }
    }

    fun webAnswerUser(question: String, sources: List<WebPage>): String = buildString {
        append("Pertanyaan: ").append(question).append("\n\n")
        append("Berikut hasil pencarian internet. Jawab pertanyaan HANYA berdasarkan sumber ini. ")
        append("Sebutkan nomor sumber seperti [1] atau [2] di akhir kalimat yang memakainya. ")
        append("Kalau sumber tidak menjawab pertanyaan, katakan begitu.\n\n")
        sources.forEachIndexed { i, s ->
            append("[${i + 1}] ").append(s.title).append(" (").append(s.url).append(")\n")
            append(s.text).append("\n\n")
        }
    }

    // ---- Ringkas ----

    enum class SummaryStyle(val label: String, val instruction: String) {
        POINTS("Poin-poin", "Tulis ringkasan berupa poin-poin penting (pakai tanda -), maksimal 10 poin."),
        PARAGRAPH("Paragraf", "Tulis ringkasan dalam 1–3 paragraf pendek."),
        SHORT("Sangat singkat", "Tulis ringkasan dalam 1–2 kalimat saja."),
        MINUTES("Notulen rapat", "Susun menjadi notulen rapat dengan bagian: Topik, Poin pembahasan, Keputusan, Tugas/tindak lanjut (siapa & kapan, kalau disebut)."),
    }

    const val SUMMARY_SYSTEM = "Kamu adalah asisten yang meringkas teks dengan setia pada isi aslinya. " +
        "Jangan menambahkan informasi yang tidak ada di teks. Tulis dalam bahasa Indonesia."

    fun summaryUser(text: String, style: SummaryStyle, partInfo: String? = null): String = buildString {
        append(style.instruction)
        if (partInfo != null) append(" Ini adalah ").append(partInfo).append(" dari teks yang lebih panjang.")
        append("\n\nTeks:\n\"\"\"\n").append(text.trim()).append("\n\"\"\"")
    }

    fun combineUser(partials: List<String>, style: SummaryStyle): String = buildString {
        append("Berikut ringkasan dari beberapa bagian sebuah teks panjang. Gabungkan menjadi satu ringkasan utuh tanpa pengulangan. ")
        append(style.instruction).append("\n\n")
        partials.forEachIndexed { i, p -> append("Bagian ${i + 1}:\n").append(p.trim()).append("\n\n") }
    }

    // ---- Tanya dokumen ----

    const val DOC_QA_SYSTEM = "Kamu menjawab pertanyaan tentang sebuah dokumen. Jawab hanya berdasarkan isi dokumen. " +
        "Kalau jawabannya tidak ada di dokumen, katakan tidak ditemukan. Jawab dalam bahasa Indonesia, singkat dan jelas."

    fun docQaUser(doc: String, question: String) =
        "Dokumen:\n\"\"\"\n${doc.trim()}\n\"\"\"\n\nPertanyaan: $question"

    // ---- Bantu tulis ----

    val docTypes = listOf(
        "Surat resmi", "Surat izin / permohonan", "Email kantor", "Pesan WhatsApp",
        "Pengumuman", "Laporan singkat", "Undangan", "Caption media sosial", "Bebas",
    )
    val tones = listOf("Formal", "Sopan & santai", "Akrab")
    val lengths = listOf("Pendek", "Sedang", "Panjang")

    const val WRITE_SYSTEM = "Kamu membantu menulis dokumen dalam bahasa Indonesia yang baik, benar, dan rapi. " +
        "Tulis HANYA isi dokumennya, tanpa penjelasan tambahan sebelum atau sesudahnya. " +
        "Kalau ada data yang belum diketahui (nama, tanggal, nomor), pakai tempat isian seperti [Nama] atau [Tanggal]."

    fun writeUser(type: String, tone: String, length: String, details: String): String = buildString {
        append("Buatkan ").append(type.lowercase()).append(" dengan gaya bahasa ").append(tone.lowercase())
        append(", panjangnya ").append(length.lowercase()).append(".\n\nIsi / poin yang harus ada:\n").append(details.trim())
    }

    const val REVISE_SYSTEM = "Kamu memperbaiki tulisan sesuai permintaan. Tulis HANYA hasil perbaikannya, tanpa komentar."

    fun reviseUser(text: String, instruction: String) =
        "Tulisan:\n\"\"\"\n${text.trim()}\n\"\"\"\n\nPerbaiki sesuai permintaan ini: $instruction"

    // ---- Merapikan hasil dikte / transkrip ----

    const val TIDY_SYSTEM = "Kamu merapikan teks hasil pengenalan suara: perbaiki tanda baca, huruf besar, dan salah ketik yang jelas, " +
        "pisahkan menjadi paragraf. Jangan mengubah makna atau menambah isi. Tulis hanya teks hasilnya."

    // ---- Pesan ----

    const val MESSAGE_SYSTEM = "Kamu menulis pesan singkat atas nama pengguna, dalam bahasa Indonesia yang sopan dan wajar. " +
        "Tulis HANYA isi pesannya (tanpa judul, tanpa penjelasan, tanpa tanda kutip). Jangan mengarang fakta yang tidak disebut."

    fun messageUser(brief: String, channel: String, toName: String, replyContext: String = "") = buildString {
        if (replyContext.isNotBlank()) {
            append("Pesan yang akan dibalas:\n\"\"\"\n").append(replyContext.take(3000).trim()).append("\n\"\"\"\n\n")
        }
        append("Buat isi pesan ").append(channel)
        if (toName.isNotBlank()) append(" untuk ").append(toName)
        append(" berdasarkan permintaan ini:\n").append(brief.trim())
    }

    // ---- Email ----

    const val EMAIL_DIGEST_SYSTEM = "Kamu membantu pengguna yang malas membaca email. Untuk setiap email, tulis satu poin: " +
        "pengirim, inti isinya dalam satu kalimat, dan apakah perlu tindakan (apa dan kapan). " +
        "Dahulukan yang penting/mendesak. Email promosi/iklan cukup dikumpulkan di akhir sebagai satu baris 'Promo: ...'. " +
        "Jangan mengarang isi yang tidak ada."

    const val EMAIL_ONE_SYSTEM = "Ringkas email ini dalam bahasa Indonesia: inti pesan, hal penting (tanggal, jumlah, tautan), " +
        "dan tindakan yang perlu dilakukan pengguna kalau ada. Singkat, pakai poin."

    fun emailListText(mails: List<MailItem>): String = buildString {
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale("id", "ID"))
        mails.forEachIndexed { i, m ->
            append("Email ${i + 1}\nDari: ").append(m.from).append(" <").append(m.fromAddress).append(">\n")
            m.date?.let { append("Tanggal: ").append(fmt.format(it)).append('\n') }
            append("Subjek: ").append(m.subject).append('\n')
            append(m.body.take(if (mails.size == 1) 6000 else 700)).append("\n\n")
        }
    }

    fun chat(system: String, user: String) = listOf(
        ChatMessage(ChatMessage.SYSTEM, system),
        ChatMessage(ChatMessage.USER, user),
    )
}
