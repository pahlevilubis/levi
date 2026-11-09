package com.pahlevi.levi.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import java.net.URLEncoder

enum class Channel(val label: String) {
    WHATSAPP("WhatsApp"),
    SMS("SMS"),
    EMAIL("Email"),
}

/** Satu pesan yang ingin dikirim Levi — selalu ditampilkan dulu ke pengguna sebelum dikirim. */
data class OutgoingMessage(
    val channel: Channel,
    val to: String,
    val toName: String = "",
    val subject: String = "",
    val body: String,
    val inReplyTo: String? = null,
)

/** Cara mengirim pesan. Semua fungsi di sini hanya dipanggil setelah pengguna menekan "Ya, kirim". */
object Messaging {
    private val waPackages = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun whatsappInstalled(context: Context): String? = waPackages.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    /** Nomor Indonesia 08xx / +62xx -> 62xx (format WhatsApp). */
    fun normalizePhone(raw: String): String {
        var d = raw.filter { it.isDigit() || it == '+' }.removePrefix("+")
        if (d.startsWith("0")) d = "62" + d.drop(1)
        return d
    }

    /**
     * WhatsApp tidak mengizinkan aplikasi lain mengirim otomatis: Levi membuka chat
     * dengan pesan sudah terisi, lalu kamu tinggal tekan kirim di WhatsApp.
     */
    fun openWhatsApp(context: Context, phone: String, text: String) {
        val pkg = whatsappInstalled(context)
        val intent = if (phone.isNotBlank()) {
            val url = "https://api.whatsapp.com/send?phone=${normalizePhone(phone)}&text=${URLEncoder.encode(text, "UTF-8")}"
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } else {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        }
        if (pkg != null) intent.setPackage(pkg)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun hasSmsPermission(context: Context) =
        context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Kirim SMS langsung (memakai pulsa). */
    fun sendSms(context: Context, phone: String, text: String) {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(text)
        if (parts.size > 1) sms.sendMultipartTextMessage(phone, null, parts, null, null)
        else sms.sendTextMessage(phone, null, text, null, null)
    }

    /** Buka aplikasi email dengan isi terisi (dipakai kalau Gmail belum dihubungkan ke Levi). */
    fun openEmailApp(context: Context, to: String, subject: String, body: String) {
        val uri = Uri.parse("mailto:" + Uri.encode(to) + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body))
        context.startActivity(Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
