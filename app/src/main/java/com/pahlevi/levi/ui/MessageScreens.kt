package com.pahlevi.levi.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.ai.Channel
import com.pahlevi.levi.ai.GmailClient
import com.pahlevi.levi.ai.Messaging
import com.pahlevi.levi.ai.OutgoingMessage
import com.pahlevi.levi.ai.Prompts
import com.pahlevi.levi.audio.Dictation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Pilih nomor dari kontak tanpa perlu izin baca semua kontak. */
private class PickPhoneContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

private fun readPicked(context: Context, uri: Uri): Pair<String, String>? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
        null, null, null,
    )?.use { c -> if (c.moveToFirst()) (c.getString(0) ?: "") to (c.getString(1) ?: "") else null }
}.getOrNull()

class SendMessageState(screen: Screen.SendMessage) {
    var channel by mutableStateOf(screen.channel ?: Channel.WHATSAPP)
    var to by mutableStateOf(screen.to)
    var toName by mutableStateOf(screen.toName)
    var subject by mutableStateOf(screen.subject)
    var body by mutableStateOf(screen.body)
    var brief by mutableStateOf(screen.brief)
    var status by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var confirm by mutableStateOf<OutgoingMessage?>(null)
    var sentInfo by mutableStateOf<String?>(null)
    val inReplyTo: String? = screen.inReplyTo
    var autoDraftDone = false
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun draft(scope: CoroutineScope) {
        if (brief.isBlank() || running) return
        error = null
        job = scope.launch {
            status = "Levi menulis pesan…"
            try {
                body = AppGraph.llm.complete(
                    Prompts.chat(Prompts.MESSAGE_SYSTEM, Prompts.messageUser(brief, channel.label, toName)),
                    AppGraph.llm.defaultParams(maxTokens = 500, temperature = 0.5f),
                ) { status = null; body = it }
                if (channel == Channel.EMAIL && subject.isBlank()) {
                    subject = brief.lineSequence().first().take(60)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message
            } finally {
                status = null
            }
        }
    }

    fun buildMessage() = OutgoingMessage(channel, to.trim(), toName.trim(), subject.trim(), body.trim(), inReplyTo)
}

@Composable
fun SendMessageScreen(entry: Entry, screen: Screen.SendMessage) {
    val st = remember(entry) { entry.keep("send") { SendMessageState(screen) } }
    val context = LocalContext.current
    val dictation = remember { Dictation(context) }
    DisposableEffect(Unit) { onDispose { dictation.stop() } }
    val mail = AppGraph.mailAccount

    val pickContact = rememberLauncherForActivityResult(PickPhoneContract()) { uri ->
        if (uri != null) readPicked(context, uri)?.let { (num, name) -> st.to = num; st.toName = name }
    }

    LaunchedEffect(Unit) {
        if (screen.autoDraft && !st.autoDraftDone && st.body.isBlank()) {
            st.autoDraftDone = true
            st.draft(entry.scope)
        }
    }

    ScreenScaffold(topBar = { LeviTopBar("Kirim Pesan", subtitle = "Levi selalu minta izin sebelum mengirim") }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Kirim lewat", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(Channel.entries.map { it.label }, st.channel.label, { label ->
                st.channel = Channel.entries.first { it.label == label }
            })

            val isPhone = st.channel != Channel.EMAIL
            OutlinedTextField(
                value = st.to,
                onValueChange = { st.to = it; st.toName = "" },
                label = { Text(if (isPhone) "Nomor HP tujuan" else "Alamat email tujuan") },
                supportingText = {
                    if (st.toName.isNotBlank()) Text(st.toName)
                    else if (st.channel == Channel.WHATSAPP) Text("Boleh dikosongkan: nanti pilih chat di WhatsApp")
                },
                singleLine = true,
                trailingIcon = {
                    if (isPhone) {
                        IconButton(onClick = { pickContact.launch(Unit) }) { Icon(Icons.Filled.Contacts, "Pilih dari kontak") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (st.channel == Channel.EMAIL) {
                OutlinedTextField(value = st.subject, onValueChange = { st.subject = it }, label = { Text("Subjek") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Minta Levi menuliskan (opsional)", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = st.brief,
                        onValueChange = { st.brief = it },
                        placeholder = { Text("Contoh: bilang ke Budi rapat diundur jam 3, minta maaf") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { MicButton(dictation, onText = { st.brief = (st.brief + " " + it).trim() }) },
                    )
                    RunButton("Buatkan isi pesan", st.running, st.brief.isNotBlank(), { st.draft(entry.scope) }, { AppGraph.llm.stop() })
                }
            }
            StatusLine(st.status)
            ErrorText(st.error)

            OutlinedTextField(
                value = st.body,
                onValueChange = { st.body = it },
                label = { Text("Isi pesan") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                readOnly = st.running,
            )

            val canSend = st.body.isNotBlank() && !st.running &&
                (st.channel == Channel.WHATSAPP || st.to.isNotBlank())
            Button(onClick = { st.confirm = st.buildMessage() }, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Send, null)
                Spacer(Modifier.padding(4.dp))
                Text("Periksa & kirim")
            }
            st.sentInfo?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(it, modifier = Modifier.padding(12.dp))
                }
            }
            Text(
                when (st.channel) {
                    Channel.WHATSAPP -> "WhatsApp tidak mengizinkan aplikasi lain mengirim otomatis. Levi akan membuka chat dengan pesan sudah terisi — kamu tinggal tekan kirim."
                    Channel.SMS -> "SMS dikirim langsung dari HP (memakai pulsa) setelah kamu setujui."
                    Channel.EMAIL -> if (mail.isConnected) "Email dikirim langsung dari ${mail.email} setelah kamu setujui."
                    else "Gmail belum dihubungkan, jadi aplikasi email akan terbuka dengan isi terisi. Hubungkan di menu Email supaya Levi bisa mengirim langsung."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    st.confirm?.let { msg ->
        ConfirmSendDialog(
            msg = msg,
            onDismiss = { st.confirm = null },
            onSent = { info ->
                st.confirm = null
                st.sentInfo = info
            },
        )
    }
}

/**
 * Dialog izin sebelum Levi mengirim apa pun: menampilkan saluran, tujuan, dan isi lengkap.
 * Tidak ada yang terkirim kalau kamu tidak menekan "Ya, kirim".
 */
@Composable
fun ConfirmSendDialog(msg: OutgoingMessage, onDismiss: () -> Unit, onSent: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val mail = AppGraph.mailAccount

    fun doSend() {
        sending = true
        error = null
        scope.launch {
            try {
                val info = when (msg.channel) {
                    Channel.WHATSAPP -> {
                        Messaging.openWhatsApp(context, msg.to, msg.body)
                        "WhatsApp dibuka dengan pesan terisi. Tekan kirim di WhatsApp."
                    }
                    Channel.SMS -> {
                        Messaging.sendSms(context, msg.to, msg.body)
                        "SMS terkirim ke ${msg.toName.ifBlank { msg.to }}."
                    }
                    Channel.EMAIL -> {
                        val email = mail.email
                        val pass = mail.password
                        if (email != null && pass != null) {
                            GmailClient.send(email, pass, msg.to, msg.subject.ifBlank { "(tanpa subjek)" }, msg.body, msg.inReplyTo)
                            "Email terkirim ke ${msg.to}."
                        } else {
                            Messaging.openEmailApp(context, msg.to, msg.subject, msg.body)
                            "Aplikasi email dibuka dengan isi terisi."
                        }
                    }
                }
                onSent(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Gagal mengirim: ${e.message}"
            } finally {
                sending = false
            }
        }
    }

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doSend() else error = "Izin SMS ditolak, SMS tidak dikirim."
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Levi mau mengirim pesan ini") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text("Lewat: ", style = MaterialTheme.typography.labelLarge); Text(msg.channel.label) }
                Row(verticalAlignment = Alignment.Top) {
                    Text("Kepada: ", style = MaterialTheme.typography.labelLarge)
                    Text(
                        when {
                            msg.to.isBlank() -> "(pilih chat di WhatsApp)"
                            msg.toName.isNotBlank() -> "${msg.toName} (${msg.to})"
                            else -> msg.to
                        },
                    )
                }
                if (msg.channel == Channel.EMAIL && msg.subject.isNotBlank()) {
                    Row { Text("Subjek: ", style = MaterialTheme.typography.labelLarge); Text(msg.subject) }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Text(msg.body, modifier = Modifier.padding(10.dp))
                }
                Text(
                    when (msg.channel) {
                        Channel.SMS -> "SMS akan langsung terkirim dan memakai pulsa."
                        Channel.WHATSAPP -> "WhatsApp akan terbuka; pesan baru terkirim setelah kamu tekan kirim di sana."
                        Channel.EMAIL -> if (mail.isConnected) "Email akan langsung terkirim dari ${mail.email}." else "Aplikasi email akan terbuka."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (sending) StatusLine("Mengirim…")
            }
        },
        confirmButton = {
            TextButton(enabled = !sending, onClick = {
                if (msg.channel == Channel.SMS && !Messaging.hasSmsPermission(context)) smsPermission.launch(Manifest.permission.SEND_SMS)
                else doSend()
            }) { Text("Ya, kirim") }
        },
        dismissButton = { TextButton(enabled = !sending, onClick = onDismiss) { Text("Jangan kirim") } },
    )
}
