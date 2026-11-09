package com.pahlevi.levi.audio

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pahlevi.levi.AppGraph
import com.pahlevi.levi.MainActivity
import com.pahlevi.levi.R
import com.pahlevi.levi.LeviApp
import com.pahlevi.levi.data.NoteKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class TranscribeUiState(
    val running: Boolean = false,
    val file: String? = null,
    val progress: Int = 0,
    val text: String = "",
    val doneNoteId: String? = null,
    val error: String? = null,
)

object TranscribeState {
    internal val _state = MutableStateFlow(TranscribeUiState())
    val state: StateFlow<TranscribeUiState> = _state.asStateFlow()

    fun start(context: Context, wav: File) {
        val intent = Intent(context, TranscribeService::class.java)
            .setAction(TranscribeService.ACTION_START)
            .putExtra(TranscribeService.EXTRA_FILE, wav.absolutePath)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, TranscribeService::class.java).setAction(TranscribeService.ACTION_STOP))
    }

    fun clearResult() {
        if (!_state.value.running) _state.value = TranscribeUiState()
    }
}

/** Layanan latar depan untuk transkripsi panjang (rekaman rapat) dengan Whisper. */
class TranscribeService : LifecycleService() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_FILE = "file"
        private const val NOTIF_ID = 42
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    @Volatile private var cancelled = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_FILE)
                if (path != null && !started) begin(File(path))
            }
            ACTION_STOP -> {
                cancelled = true
                AppGraph.whisper.engine.stop()
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(wav: File) {
        started = true
        ServiceCompat.startForeground(this, NOTIF_ID, notification(0, wav.nameWithoutExtension), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Levi:transkrip")
            .apply { acquire(4 * 60 * 60 * 1000L) }
        TranscribeState._state.value = TranscribeUiState(running = true, file = wav.absolutePath)

        lifecycleScope.launch {
            val sb = StringBuilder()
            try {
                AppGraph.whisper.ensureLoaded()
                val prefs = AppGraph.prefs
                AppGraph.whisper.engine.transcribe(
                    wavPath = wav.absolutePath,
                    language = prefs.whisperLanguage,
                    threads = prefs.threads,
                    onProgress = { p ->
                        TranscribeState._state.value = TranscribeState._state.value.copy(progress = p)
                        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(p, wav.nameWithoutExtension))
                    },
                    onSegment = { seg ->
                        if (seg.text.isNotBlank()) {
                            sb.append('[').append(formatDuration(seg.startMs)).append("] ").append(seg.text).append('\n')
                            TranscribeState._state.value = TranscribeState._state.value.copy(text = sb.toString())
                        }
                        true
                    },
                )
                val body = sb.toString().trim()
                if (body.isEmpty()) {
                    TranscribeState._state.value = TranscribeUiState(error = "Tidak ada suara yang bisa dikenali.")
                } else {
                    val label = if (cancelled) "Transkrip (sebagian)" else "Transkrip"
                    val id = AppGraph.notes.add("$label ${wav.nameWithoutExtension.replace('_', ' ')}", body, NoteKind.TRANSCRIPT)
                    TranscribeState._state.value = TranscribeUiState(doneNoteId = id, text = body, progress = 100)
                }
            } catch (e: Throwable) {
                val partial = sb.toString().trim()
                val id = if (partial.isNotEmpty()) {
                    AppGraph.notes.add("Transkrip (sebagian) ${wav.nameWithoutExtension.replace('_', ' ')}", partial, NoteKind.TRANSCRIPT)
                } else null
                TranscribeState._state.value = TranscribeUiState(error = e.message ?: "Transkripsi gagal", doneNoteId = id, text = partial)
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun notification(progress: Int, name: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, TranscribeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, LeviApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Mentranskrip $name")
            .setContentText("$progress%")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Hentikan", stop)
            .build()
    }
}
