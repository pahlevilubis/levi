@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.pahlevi.levi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.pahlevi.levi.ui.ChatListScreen
import com.pahlevi.levi.ui.ChatScreen
import com.pahlevi.levi.ui.DocumentScreen
import com.pahlevi.levi.ui.HomeScreen
import com.pahlevi.levi.ui.InternetGate
import com.pahlevi.levi.ui.InternetGateDialogs
import com.pahlevi.levi.ui.LocalInternetGate
import com.pahlevi.levi.ui.LocalNav
import com.pahlevi.levi.ui.MemoryScreen
import com.pahlevi.levi.ui.ModelsScreen
import com.pahlevi.levi.ui.NavViewModel
import com.pahlevi.levi.ui.NoteDetailScreen
import com.pahlevi.levi.ui.NotesScreen
import com.pahlevi.levi.ui.RecordScreen
import com.pahlevi.levi.ui.LeviTheme
import com.pahlevi.levi.ui.Screen
import com.pahlevi.levi.ui.SettingsScreen
import com.pahlevi.levi.ui.SharedTextScreen
import com.pahlevi.levi.ui.SummarizeScreen
import com.pahlevi.levi.ui.TranslateScreen
import com.pahlevi.levi.ui.WriteScreen

class MainActivity : ComponentActivity() {
    private val nav: NavViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            LeviTheme {
                LeviRoot(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) nav.go(Screen.SharedText(text))
        }
    }
}

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Screen.Home, "Beranda", Icons.Filled.Home),
    Tab(Screen.Chats, "Chat", Icons.Filled.Forum),
    Tab(Screen.Notes, "Catatan", Icons.Filled.Description),
    Tab(Screen.Settings, "Pengaturan", Icons.Filled.Settings),
)

@Composable
fun LeviRoot(nav: NavViewModel) {
    val gate = remember { InternetGate() }
    CompositionLocalProvider(LocalNav provides nav, LocalInternetGate provides gate) {
        val entry = nav.current
        val isRoot = nav.stack.size == 1 && tabs.any { it.screen == entry.screen }
        BackHandler(enabled = nav.stack.size > 1) { nav.back() }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (isRoot) {
                    NavigationBar {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = entry.screen == t.screen,
                                onClick = { nav.tab(t.screen) },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
            Box(Modifier.fillMaxSize().padding(bottom).consumeWindowInsets(bottom)) {
                key(entry.key) {
                    when (val s = entry.screen) {
                        Screen.Home -> HomeScreen()
                        Screen.Chats -> ChatListScreen()
                        is Screen.Chat -> ChatScreen(entry, s)
                        is Screen.Translate -> TranslateScreen(entry, s)
                        Screen.Write -> WriteScreen(entry)
                        is Screen.Summarize -> SummarizeScreen(entry, s)
                        Screen.Document -> DocumentScreen(entry)
                        is Screen.Record -> RecordScreen(entry, s)
                        Screen.Notes -> NotesScreen(entry)
                        is Screen.NoteDetail -> NoteDetailScreen(entry, s)
                        Screen.Memory -> MemoryScreen()
                        Screen.Settings -> SettingsScreen()
                        is Screen.Models -> ModelsScreen(s)
                        is Screen.SharedText -> SharedTextScreen(s)
                    }
                }
            }
        }
        InternetGateDialogs(gate)
    }
}
