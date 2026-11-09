package com.pahlevi.levi.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.Date
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

data class MailItem(
    val uid: Long,
    val from: String,
    val fromAddress: String,
    val subject: String,
    val date: Date?,
    val unread: Boolean,
    val body: String,
    val messageId: String?,
)

class MailAuthException(msg: String) : Exception(msg)

/**
 * Membaca & mengirim email Gmail memakai IMAP/SMTP dengan "sandi aplikasi" Google.
 * Kotak masuk dibuka hanya-baca, jadi email yang dibaca Levi TIDAK ditandai sudah dibaca.
 */
object GmailClient {
    private const val IMAP_HOST = "imap.gmail.com"
    private const val SMTP_HOST = "smtp.gmail.com"
    private const val MAX_BODY = 6000

    private fun imapSession(): Session = Session.getInstance(Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", IMAP_HOST)
        put("mail.imaps.port", "993")
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.connectiontimeout", "20000")
        put("mail.imaps.timeout", "30000")
        put("mail.imaps.peek", "true")
    })

    /** Ambil [count] email terbaru dari Kotak Masuk. */
    suspend fun fetchInbox(email: String, appPassword: String, count: Int = 25, onlyUnread: Boolean = false): List<MailItem> =
        withContext(Dispatchers.IO) {
            val store = imapSession().getStore("imaps")
            try {
                store.connect(IMAP_HOST, email, appPassword)
            } catch (e: javax.mail.AuthenticationFailedException) {
                throw MailAuthException("Login Gmail ditolak. Pastikan alamat email benar dan pakai Sandi Aplikasi (bukan sandi biasa).")
            }
            try {
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                try {
                    val total = inbox.messageCount
                    if (total == 0) return@withContext emptyList()
                    val start = maxOf(1, total - count + 1)
                    val msgs = inbox.getMessages(start, total)
                    val fp = FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(FetchProfile.Item.FLAGS)
                        add(UIDFolder.FetchProfileItem.UID)
                    }
                    inbox.fetch(msgs, fp)
                    val uidFolder = inbox as? UIDFolder
                    msgs.reversed()
                        .filter { !onlyUnread || !it.isSet(Flags.Flag.SEEN) }
                        .map { m -> toItem(m, uidFolder) }
                } finally {
                    inbox.close(false)
                }
            } finally {
                store.close()
            }
        }

    private fun toItem(m: Message, uidFolder: UIDFolder?): MailItem {
        val addr = (m.from?.firstOrNull() as? InternetAddress)
        return MailItem(
            uid = runCatching { uidFolder?.getUID(m) ?: m.messageNumber.toLong() }.getOrDefault(m.messageNumber.toLong()),
            from = addr?.personal ?: addr?.address ?: "(tanpa nama)",
            fromAddress = addr?.address ?: "",
            subject = m.subject ?: "(tanpa subjek)",
            date = m.sentDate ?: m.receivedDate,
            unread = !m.isSet(Flags.Flag.SEEN),
            body = runCatching { extractText(m) }.getOrDefault("").let { cleanBody(it) }.take(MAX_BODY),
            messageId = (m as? MimeMessage)?.messageID,
        )
    }

    private fun extractText(p: Part): String {
        return when {
            p.isMimeType("text/plain") -> p.content as? String ?: ""
            p.isMimeType("text/html") -> htmlToText(p.content as? String ?: "")
            p.isMimeType("multipart/alternative") -> {
                val mp = p.content as Multipart
                var html: String? = null
                for (i in 0 until mp.count) {
                    val bp = mp.getBodyPart(i)
                    if (bp.isMimeType("text/plain")) return bp.content as? String ?: ""
                    if (bp.isMimeType("text/html")) html = htmlToText(bp.content as? String ?: "")
                    else if (bp.isMimeType("multipart/*")) extractText(bp).takeIf { it.isNotBlank() }?.let { return it }
                }
                html ?: ""
            }
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                val sb = StringBuilder()
                for (i in 0 until mp.count) {
                    val bp = mp.getBodyPart(i)
                    if (Part.ATTACHMENT.equals(bp.disposition, ignoreCase = true)) {
                        sb.append("\n[Lampiran: ").append(bp.fileName ?: "file").append("]\n")
                    } else {
                        sb.append(extractText(bp)).append('\n')
                    }
                }
                sb.toString()
            }
            p.isMimeType("message/rfc822") -> extractText(p.content as Part)
            else -> ""
        }
    }

    private fun htmlToText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("style, script, head").remove()
        doc.select("br").append("\n")
        doc.select("p, div, tr, li, h1, h2, h3").prepend("\n")
        return doc.wholeText().lines().joinToString("\n") { it.trim() }
    }

    private fun cleanBody(text: String): String =
        text.lines()
            .map { it.trimEnd() }
            .filterNot { it.startsWith(">") } // kutipan email lama
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Kirim email lewat Gmail. Dipanggil HANYA setelah pengguna menyetujui isinya. */
    suspend fun send(
        email: String,
        appPassword: String,
        to: String,
        subject: String,
        body: String,
        inReplyTo: String? = null,
    ) = withContext(Dispatchers.IO) {
        val session = Session.getInstance(Properties().apply {
            put("mail.smtp.host", SMTP_HOST)
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
        }, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(email, appPassword)
        })
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(email))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
            if (inReplyTo != null) {
                setHeader("In-Reply-To", inReplyTo)
                setHeader("References", inReplyTo)
            }
            sentDate = Date()
        }
        try {
            Transport.send(msg)
        } catch (e: javax.mail.AuthenticationFailedException) {
            throw MailAuthException("Gmail menolak login untuk mengirim. Periksa Sandi Aplikasi.")
        }
    }

    /** Uji koneksi (dipakai saat menghubungkan akun). */
    suspend fun test(email: String, appPassword: String) {
        fetchInbox(email, appPassword, count = 1)
    }
}
