package com.pahlevi.levi.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import com.pahlevi.levi.ai.Channel
import com.pahlevi.levi.ai.ModelType
import com.pahlevi.levi.ai.Prompts
import com.pahlevi.levi.data.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

sealed interface Screen {
    data object Home : Screen
    data object Chats : Screen
    data class Chat(val id: String?, val prefill: String = "") : Screen
    data class Translate(val text: String = "") : Screen
    data object Write : Screen
    data class Summarize(val text: String = "", val style: Prompts.SummaryStyle = Prompts.SummaryStyle.POINTS) : Screen
    data object Document : Screen
    data class Record(val tab: Int = 0) : Screen
    data object Notes : Screen
    data class NoteDetail(val id: String?) : Screen
    data object Memory : Screen
    data object Settings : Screen
    data class Models(val type: ModelType) : Screen
    data class SharedText(val text: String) : Screen
    data class Email(val autoSummarize: Boolean = false) : Screen
    data class SendMessage(
        val channel: Channel? = null,
        val to: String = "",
        val toName: String = "",
        val subject: String = "",
        val body: String = "",
        val brief: String = "",
        val inReplyTo: String? = null,
        val autoDraft: Boolean = false,
    ) : Screen
    data object Knowledge : Screen
}

/** Satu halaman di tumpukan navigasi, dengan penyimpanan state & coroutine miliknya sendiri. */
class Entry(val screen: Screen) {
    val key: String = newId()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bag = HashMap<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T> keep(name: String, init: () -> T): T = bag.getOrPut(name) { init() } as T

    fun dispose() = scope.cancel()
}

class NavViewModel : ViewModel() {
    val stack = mutableStateListOf(Entry(Screen.Home))

    val current: Entry get() = stack[stack.lastIndex]

    fun go(screen: Screen) {
        stack.add(Entry(screen))
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex).dispose()
        return true
    }

    /** Pindah tab utama: kosongkan tumpukan */
    fun tab(screen: Screen) {
        if (stack.size == 1 && stack[0].screen == screen) return
        while (stack.isNotEmpty()) stack.removeAt(stack.lastIndex).dispose()
        stack.add(Entry(screen))
    }

    /** Ganti halaman sekarang dengan yang lain */
    fun replace(screen: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex).dispose()
        stack.add(Entry(screen))
    }

    override fun onCleared() {
        stack.forEach { it.dispose() }
    }
}

val LocalNav = staticCompositionLocalOf<NavViewModel> { error("NavViewModel belum tersedia") }
