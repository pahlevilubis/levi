package com.pahlevi.levi.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.ai.AiTasks
import com.pahlevi.levi.ai.Channel
import com.pahlevi.levi.ai.GmailClient
import com.pahlevi.levi.ai.MailItem
import com.pahlevi.levi.ai.Prompts
import com.pahlevi.levi.data.NoteKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class EmailState {
    var connected by mutableStateOf(AppGraph.mailAccount.isConnected)
    var emailInput by mutableStateOf(AppGraph.mailAccount.email ?: "")
    var passInput by mutableStateOf("")
    var mails by mutableStateOf<List<MailItem>>(emptyList())
    var onlyUnread by mutableStateOf(true)
    var selected by mutableStateOf<MailItem?>(null)
    var digest by mutableStateOf<String?>(null)
    var mailSummary by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var loadedOnce = false
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun connect(scope: CoroutineScope) {
        val email = emailInput.trim()
        val pass = passInput.replace(" ", "")
        if (email.isEmpty() || pass.isEmpty()) return
        job = scope.launch {
            error = null
            status = "Mencoba masuk ke Gmail…"
            try {
                GmailClient.test(email, pass)
                AppGraph.mailAccount.save(email, pass)
                passInput = ""
                connected = true
                load(scope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Gagal terhubung"
            } finally {
                if (status == "Mencoba masuk ke Gmail…") status = null
            }
        }
    }

    fun disconnect() {
        AppGraph.mailAccount.clear()
        connected = false
        mails = emptyList()
        digest = null
        selected = null
    }

    fun load(scope: CoroutineScope) {
        val email = AppGraph.mailAccount.email ?: return
        val pass = AppGraph.mailAccount.password ?: return
        job = scope.launch {
            error = null
            status = "Mengambil email…"
            try {
                mails = GmailClient.fetchInbox(email, pass, count = 30)
                loadedOnce = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Gagal mengambil email"
            } finally {
                status = null
            }
        }
    }

    fun summarizeInbox(scope: CoroutineScope) {
        val list = (if (onlyUnread) mails.filter { it.unread } else mails).take(12)
        if (list.isEmpty() || running) return
        job = scope.launch {
            error = null
            digest = ""
            status = "Levi membaca ${list.size} email…"
            try {
                val text = AiTasks.fitDocument(AppGraph.llm, Prompts.emailListText(list), reserve = 900)
                digest = AppGraph.llm.complete(
                    Prompts.chat(Prompts.EMAIL_DIGEST_SYSTEM, text),
                    AppGraph.llm.defaultParams(maxTokens = 900, temperature = 0.3f),
                ) { status = null; digest = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message
                if (digest.isNullOrBlank()) digest = null
            } finally {
                status = null
            }
        }
    }

    fun summarizeOne(scope: CoroutineScope, m: MailItem) {
        if (running) return
        job = scope.launch {
            error = null
            mailSummary = ""
            status = "Meringkas email…"
            try {
                val text = AiTasks.fitDocument(AppGraph.llm, Prompts.emailListText(listOf(m)), reserve = 600)
                mailSummary = AppGraph.llm.complete(
                    Prompts.chat(Prompts.EMAIL_ONE_SYSTEM, text),
                    AppGraph.llm.defaultParams(maxTokens = 500, temperature = 0.3f),
                ) { status = null; mailSummary = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message
                if (mailSummary.isNullOrBlank()) mailSummary = null
            } finally {
                status = null
            }
        }
    }
}

@Composable
fun EmailScreen(entry: Entry, screen: Screen.Email) {
    val st = remember(entry) { entry.keep("email") { EmailState() } }
    val gate = LocalInternetGate.current
    val nav = LocalNav.current
    val context = LocalContext.current
    var confirmLogout by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale("id", "ID")) }

    fun refresh() = gate.request("Levi akan mengambil email terbaru dari Gmail kamu (hanya membaca, email tidak ditandai sudah dibaca).") {
        st.load(entry.scope)
    }

    LaunchedEffect(Unit) {
        if (st.connected && !st.loadedOnce && !st.running) {
            gate.request("Levi akan mengambil email terbaru dari Gmail kamu (hanya membaca, email tidak ditandai sudah dibaca).") {
                st.load(entry.scope)
                if (screen.autoSummarize) {
                    entry.scope.launch {
                        // tunggu email selesai diambil lalu ringkas
                        while (st.running) kotlinx.coroutines.delay(300)
                        st.summarizeInbox(entry.scope)
                    }
                }
            }
        }
    }

    BackHandler(enabled = st.selected != null) { st.selected = null; st.mailSummary = null }

    ScreenScaffold(
        topBar = {
            LeviTopBar(
                "Email",
                subtitle = AppGraph.mailAccount.email ?: "Gmail belum dihubungkan",
                actions = {
                    if (st.connected) {
                        IconButton(onClick = { refresh() }, enabled = !st.running) { Icon(Icons.Filled.Refresh, "Muat ulang") }
                        IconButton(onClick = { confirmLogout = true }) { Icon(Icons.AutoMirrored.Filled.Logout, "Putuskan Gmail") }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!st.connected) {
                ConnectGmail(st, onConnect = {
                    gate.request("Levi akan masuk ke Gmail kamu untuk menguji sandi aplikasi.") { st.connect(entry.scope) }
                }, onOpenSettings = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))) }
                })
                StatusLine(st.status)
                ErrorText(st.error)
            } else if (st.selected != null) {
                val selected = st.selected!!
                MailDetail(selected, st, entry, onReply = {
                    nav.go(
                        Screen.SendMessage(
                            channel = Channel.EMAIL,
                            to = selected.fromAddress,
                            toName = selected.from,
                            subject = if (selected.subject.startsWith("Re:", true)) selected.subject else "Re: ${selected.subject}",
                            brief = "Balas email ini. Isi balasan saya: ",
                            inReplyTo = selected.messageId,
                            replyContext = "Dari: ${selected.from}\nSubjek: ${selected.subject}\n\n${selected.body}",
                        )
                    )
                })
            } else {
            ChoiceChips(listOf("Belum dibaca", "Semua"), if (st.onlyUnread) "Belum dibaca" else "Semua", { st.onlyUnread = it == "Belum dibaca" })
            val shown = if (st.onlyUnread) st.mails.filter { it.unread } else st.mails
            Button(
                onClick = { st.summarizeInbox(entry.scope) },
                enabled = shown.isNotEmpty() && !st.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (st.onlyUnread) "Ringkas email belum dibaca" else "Ringkas email terbaru") }
            StatusLine(st.status)
            ErrorText(st.error)
            st.digest?.takeIf { it.isNotBlank() }?.let {
                ResultCard("Ringkasan email", it, onSave = { AppGraph.notes.add("Ringkasan email", it, NoteKind.SUMMARY) })
            }
            if (st.loadedOnce && shown.isEmpty()) {
                Text(
                    if (st.onlyUnread) "Tidak ada email belum dibaca di 30 email terbaru. 🎉" else "Kotak masuk kosong.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shown.forEach { m ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().clickable { st.selected = m; st.mailSummary = null },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        if (m.unread) {
                            Box(Modifier.padding(top = 6.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(
                                    m.from, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                    fontWeight = if (m.unread) FontWeight.Bold else FontWeight.Normal,
                                )
                                m.date?.let { Text(fmt.format(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                            }
                            Text(m.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                m.body.replace('\n', ' ').take(140),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Text(
                "Levi hanya membaca 30 email terbaru di Kotak Masuk. Email diproses di HP, tidak dikirim ke server lain.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Putuskan Gmail?") },
            text = { Text("Sandi aplikasi akan dihapus dari HP. Sebaiknya cabut juga sandi aplikasinya di akun Google kamu.") },
            confirmButton = { TextButton(onClick = { st.disconnect(); confirmLogout = false }) { Text("Putuskan") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun ConnectGmail(st: EmailState, onConnect: () -> Unit, onOpenSettings: () -> Unit) {
    Text("Hubungkan Gmail", style = MaterialTheme.typography.titleLarge)
    Text(
        "Levi bisa membaca & meringkas email, dan mengirim balasan setelah kamu setujui. " +
            "Untuk itu Google memakai \"Sandi Aplikasi\" — sandi khusus 16 huruf, bukan sandi Gmail kamu.",
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Cara membuat Sandi Aplikasi:", style = MaterialTheme.typography.labelLarge)
            Text("1. Pastikan Verifikasi 2 Langkah di akun Google sudah aktif.")
            Text("2. Buka halaman Sandi Aplikasi Google (tombol di bawah).")
            Text("3. Isi nama aplikasi: Levi, lalu tekan Buat.")
            Text("4. Salin 16 huruf yang muncul dan tempel di bawah.")
            OutlinedButton(onClick = onOpenSettings) { Text("Buka halaman Sandi Aplikasi") }
        }
    }
    OutlinedTextField(
        value = st.emailInput,
        onValueChange = { st.emailInput = it },
        label = { Text("Alamat Gmail") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = st.passInput,
        onValueChange = { st.passInput = it },
        label = { Text("Sandi Aplikasi (16 huruf)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onConnect,
        enabled = st.emailInput.contains("@") && st.passInput.replace(" ", "").length >= 16 && !st.running,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Hubungkan") }
    Text(
        "Sandi disimpan terenkripsi di HP ini saja. Kamu bisa mencabutnya kapan pun dari akun Google.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MailDetail(m: MailItem, st: EmailState, entry: Entry, onReply: () -> Unit) {
    val context = LocalContext.current
    val nav = LocalNav.current
    TextButton(onClick = { st.selected = null; st.mailSummary = null }) { Text("← Kembali ke daftar") }
    Text(m.subject, style = MaterialTheme.typography.titleLarge)
    Text("${m.from} <${m.fromAddress}>", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(onClick = { st.summarizeOne(entry.scope, m) }, enabled = !st.running) { Text("Ringkas") }
        FilledTonalButton(onClick = onReply) { Text("Balas (AI)") }
        FilledTonalButton(onClick = { nav.go(Screen.Translate(m.body)) }) { Text("Terjemah") }
    }
    StatusLine(st.status)
    ErrorText(st.error)
    st.mailSummary?.takeIf { it.isNotBlank() }?.let { ResultCard("Inti email", it) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        SelectionContainer { Text(m.body.ifBlank { "(isi kosong)" }, modifier = Modifier.padding(12.dp)) }
    }
    TextButton(onClick = {
        AppGraph.notes.add(m.subject, "Dari: ${m.from} <${m.fromAddress}>\n\n${m.body}", NoteKind.NOTE)
        toast(context, "Disimpan ke Catatan")
    }) { Text("Simpan ke catatan") }
}
