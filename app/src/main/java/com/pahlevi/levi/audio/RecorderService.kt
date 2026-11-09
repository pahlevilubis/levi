package com.pahlevi.levi.audio

import android.app.Notification
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
import com.pahlevi.levi.MainActivity
import com.pahlevi.levi.R
import com.pahlevi.levi.LeviApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecorderUiState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val file: File? = null,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val lastSaved: File? = null,
    val error: String? = null,
)

object RecorderState {
    internal val _state = MutableStateFlow(RecorderUiState())
    val state: StateFlow<RecorderUiState> = _state.asStateFlow()

    fun recordingsDir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun newFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(context), "Rekaman_$stamp.wav")
    }

    fun start(context: Context) {
        val intent = Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun send(context: Context, action: String) {
        context.startService(Intent(context, RecorderService::class.java).setAction(action))
    }
}

/** Layanan latar depan: rekaman tetap jalan walau layar mati atau pindah aplikasi. */
class RecorderService : LifecycleService() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        private const val NOTIF_ID = 41
    }

    private var recorder: WavRecorder? = null
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> recorder?.let { it.paused = true; update() }
            ACTION_RESUME -> recorder?.let { it.paused = false; update() }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recorder != null) return
        val file = RecorderState.newFile(this)
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(0, false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val rec = WavRecorder(file)
        try {
            rec.start()
        } catch (e: Throwable) {
            RecorderState._state.value = RecorderUiState(error = e.message ?: "Gagal mulai merekam")
            file.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        recorder = rec
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Levi:rekam")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        RecorderState._state.value = RecorderUiState(recording = true, file = file)
        ticker = lifecycleScope.launch {
            var n = 0
            while (isActive) {
                update(updateNotification = n % 2 == 0)
                n++
                delay(500)
            }
        }
    }

    private fun update(updateNotification: Boolean = true) {
        val rec = recorder ?: return
        RecorderState._state.value = RecorderState._state.value.copy(
            recording = true,
            paused = rec.paused,
            elapsedMs = rec.durationMs,
            level = if (rec.paused) 0f else rec.level,
        )
        if (updateNotification) {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(rec.durationMs, rec.paused))
        }
    }

    private fun stopRecording() {
        ticker?.cancel()
        val rec = recorder
        recorder = null
        rec?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        val file = RecorderState._state.value.file
        RecorderState._state.value = RecorderUiState(lastSaved = file)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording()
        super.onDestroy()
    }

    private fun buildNotification(elapsedMs: Long, paused: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(act: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, RecorderService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, LeviApp.CHANNEL_RECORD)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (paused) "Rekaman dijeda" else "Levi sedang merekam")
            .setContentText(formatDuration(elapsedMs))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "Lanjut" else "Jeda", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Selesai", action(ACTION_STOP, 2))
            .build()
    }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
