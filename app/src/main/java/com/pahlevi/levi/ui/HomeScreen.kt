package com.pahlevi.levi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.ai.LlmStatus
import com.pahlevi.levi.ai.ModelType

private data class Tool(val title: String, val desc: String, val icon: ImageVector, val screen: Screen)

private val tools = listOf(
    Tool("Chat AI", "Tanya apa saja, offline", Icons.Filled.Forum, Screen.Chat(null)),
    Tool("Terjemah", "Offline, banyak bahasa", Icons.Filled.Translate, Screen.Translate()),
    Tool("Rekam & Dikte", "Suara jadi teks", Icons.Filled.Mic, Screen.Record()),
    Tool("Dokumen", "Foto / PDF jadi teks", Icons.Filled.DocumentScanner, Screen.Document),
    Tool("Ringkas", "Teks panjang jadi inti", Icons.Filled.Summarize, Screen.Summarize()),
    Tool("Bantu Tulis", "Surat, email, pesan", Icons.Filled.EditNote, Screen.Write),
    Tool("Catatan", "Semua hasil tersimpan", Icons.Filled.Description, Screen.Notes),
    Tool("Memori", "Hal yang Levi ingat", Icons.Filled.Psychology, Screen.Memory),
)

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val status by AppGraph.llm.status.collectAsState()
    val name = AppGraph.prefs.userName

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (name.isNotBlank()) "Halo, $name" else "Halo!",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Text(
                    "Levi siap bantu hal-hal kecil, langsung dari HP.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(span = { GridItemSpan(2) }) {
            when (val s = status) {
                LlmStatus.NoModel -> ModelMissingCard(ModelType.LLM)
                else -> {
                    val label = when (s) {
                        is LlmStatus.Ready -> "${s.name} • siap"
                        is LlmStatus.Idle -> "${s.name} • dimuat saat dipakai"
                        is LlmStatus.Loading -> "${s.name} • sedang dimuat…"
                        is LlmStatus.Failed -> "${s.name} • gagal dimuat, cek Pengaturan"
                        else -> ""
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth().clickable { nav.go(Screen.Models(ModelType.LLM)) },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Model AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
        items(tools) { tool ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().height(124.dp).clickable { nav.go(tool.screen) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Icon(tool.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(tool.title, style = MaterialTheme.typography.titleMedium)
                    Text(tool.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Semua diproses di HP. Internet hanya dipakai kalau kamu izinkan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
