package com.pahlevi.levi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.pahlevi.levi.ai.LlmManager
import com.pahlevi.levi.ai.ModelDownloader
import com.pahlevi.levi.ai.WhisperManager
import com.pahlevi.levi.data.ChatRepo
import com.pahlevi.levi.data.MemoryRepo
import com.pahlevi.levi.data.NotesRepo
import com.pahlevi.levi.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Objek-objek bersama yang dipakai seluruh aplikasi. */
object AppGraph {
    lateinit var app: Application
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var prefs: Prefs
        private set
    lateinit var notes: NotesRepo
        private set
    lateinit var memory: MemoryRepo
        private set
    lateinit var chats: ChatRepo
        private set
    lateinit var downloads: ModelDownloader
        private set
    lateinit var llm: LlmManager
        private set
    lateinit var whisper: WhisperManager
        private set

    fun init(application: Application) {
        app = application
        prefs = Prefs(application)
        notes = NotesRepo(application, scope)
        memory = MemoryRepo(application, scope)
        chats = ChatRepo(application, scope)
        downloads = ModelDownloader(application, prefs, scope)
        llm = LlmManager(prefs, scope)
        whisper = WhisperManager(prefs)
    }
}

class LeviApp : Application() {
    companion object {
        const val CHANNEL_RECORD = "rekam"
        const val CHANNEL_WORK = "proses"
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORD, "Rekaman", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tampil selama Levi merekam suara"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, "Proses AI", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Kemajuan transkripsi rekaman"
            }
        )
    }
}
