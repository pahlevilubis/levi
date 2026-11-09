package com.pahlevi.levi.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.data.KnowledgeItem
import com.pahlevi.levi.data.SiteRatingRepo
import com.pahlevi.levi.data.Source

@Composable
fun KnowledgeScreen() {
    var tab by remember { mutableIntStateOf(0) }
    ScreenScaffold(topBar = { LeviTopBar("Pengetahuan Levi", subtitle = "Dipelajari dari situs yang kamu percayai") }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pengetahuan") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Situs") })
            }
            if (tab == 0) KnowledgeTab() else SitesTab()
        }
    }
}

@Composable
private fun KnowledgeTab() {
    val items by AppGraph.knowledge.items.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<KnowledgeItem?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Setiap kali Levi mencari di web, kamu bisa menilai situsnya dan memilih untuk menyimpan jawabannya. " +
                    "Yang tersimpan di sini dipakai Levi untuk menjawab berikutnya, walau tanpa internet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (items.isEmpty()) {
            item {
                Text(
                    "Belum ada pengetahuan. Coba di Chat: nyalakan ikon bola dunia, tanya sesuatu, lalu simpan jawabannya.",
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
        items(items, key = { it.id }) { k ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(k.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    k.sources.forEach { s ->
                        AssistChip(
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.url))) } },
                            label = { Text(SiteRatingRepo.normalize(s.url), maxLines = 1) },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { editing = k }) { Icon(Icons.Filled.Edit, "Ubah") }
                        IconButton(onClick = { AppGraph.knowledge.delete(k.id) }) { Icon(Icons.Filled.Delete, "Hapus") }
                    }
                }
            }
        }
    }

    editing?.let { k ->
        var text by remember(k.id) { mutableStateOf(k.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Ubah pengetahuan") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { AppGraph.knowledge.edit(k.id, text); editing = null }) { Text("Simpan") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun SitesTab() {
    val sites by AppGraph.sites.items.collectAsState()
    var newSite by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Situs tepercaya diutamakan saat mencari. Situs yang tidak dipercaya tidak akan dipakai sama sekali.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newSite,
                    onValueChange = { newSite = it.trim() },
                    placeholder = { Text("Tambah situs tepercaya, misal kemkes.go.id") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { AppGraph.sites.set(newSite, true); newSite = "" }, enabled = newSite.contains('.')) {
                    Icon(Icons.Filled.Add, "Tambah")
                }
            }
        }
        val sorted = sites.sortedWith(compareByDescending<com.pahlevi.levi.data.SiteRating> { it.trusted }.thenBy { it.domain })
        items(sorted, key = { it.domain }) { s ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (s.trusted) Icons.Filled.Verified else Icons.Filled.Block,
                        null,
                        tint = if (s.trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(s.domain)
                        Text(if (s.trusted) "Dipercaya" else "Tidak dipercaya", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { AppGraph.sites.set(s.domain, !s.trusted) }) { Text(if (s.trusted) "Jangan percaya" else "Percaya") }
                    IconButton(onClick = { AppGraph.sites.remove(s.domain) }) { Icon(Icons.Filled.Delete, "Hapus") }
                }
            }
        }
    }
}

/**
 * Setelah Levi memakai situs baru, tanyakan ke pengguna apakah situs itu bisa dipercaya.
 * [onDone] dipanggil setelah semua dinilai atau dilewati.
 */
@Composable
fun TrustSitesDialog(domains: List<String>, onDone: () -> Unit) {
    val ratings by AppGraph.sites.items.collectAsState()
    val pending = domains.filter { d -> ratings.none { it.domain == d } }
    if (pending.isEmpty()) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDone() }
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Situs ini bisa dipercaya?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Levi baru saja memakai situs berikut. Penilaianmu menentukan apakah Levi boleh belajar darinya.",
                    style = MaterialTheme.typography.bodySmall,
                )
                pending.forEach { d ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(d, style = MaterialTheme.typography.titleSmall)
                            Row {
                                TextButton(onClick = { AppGraph.sites.set(d, true) }) { Text("Percaya") }
                                TextButton(onClick = { AppGraph.sites.set(d, false) }) { Text("Tidak") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Nanti saja") } },
    )
}

/** Tawarkan menyimpan jawaban dari situs tepercaya sebagai pengetahuan. */
@Composable
fun SaveKnowledgeDialog(answer: String, sources: List<Source>, onDone: () -> Unit) {
    var text by remember(answer) { mutableStateOf(answer.replace(Regex("\\s*\\[\\d+]"), "").trim()) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Simpan sebagai pengetahuan Levi?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Sumber: " + sources.joinToString(", ") { SiteRatingRepo.normalize(it.url) },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Periksa dan rapikan dulu kalau perlu. Levi akan memakai ini di jawaban berikutnya.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppGraph.knowledge.add(text, sources)
                toast(context, "Levi mempelajari ini")
                onDone()
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Jangan") } },
    )
}
