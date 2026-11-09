package com.pahlevi.levi.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.pahlevi.levi.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface DownloadState {
    data class Running(val done: Long, val total: Long, val paused: Boolean) : DownloadState
    data class Failed(val reason: String) : DownloadState
}

/**
 * Mengunduh file model memakai DownloadManager bawaan Android:
 * tetap jalan walau aplikasi ditutup, bisa lanjut kalau sinyal putus.
 */
class ModelDownloader(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val dm = context.getSystemService(DownloadManager::class.java)

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    /** kunci = "folder/namaFile" */
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    /** naik setiap ada file model yang selesai/berubah, supaya layar bisa menyegarkan daftar */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private var pollJob: Job? = null

    init {
        if (prefs.pendingDownloads().isNotEmpty()) ensurePolling()
    }

    private fun folder(type: ModelType) = if (type == ModelType.LLM) "models" else "whisper"

    fun dir(type: ModelType): File {
        val d = context.getExternalFilesDir(folder(type)) ?: File(context.filesDir, folder(type))
        d.mkdirs()
        return d
    }

    fun key(type: ModelType, fileName: String) = "${folder(type)}/$fileName"

    fun file(type: ModelType, fileName: String) = File(dir(type), fileName)

    fun isReady(type: ModelType, fileName: String): Boolean {
        val f = file(type, fileName)
        return f.exists() && f.length() > 0 && key(type, fileName) !in prefs.pendingDownloads()
    }

    /** semua file model yang sudah ada di HP (termasuk yang diimpor sendiri) */
    fun installed(type: ModelType): List<File> {
        val ext = if (type == ModelType.LLM) ".gguf" else ".bin"
        val pending = prefs.pendingDownloads().keys
        return dir(type).listFiles()
            ?.filter { it.isFile && it.name.endsWith(ext, ignoreCase = true) && key(type, it.name) !in pending }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun start(type: ModelType, url: String, fileName: String, title: String) {
        val k = key(type, fileName)
        if (k in prefs.pendingDownloads()) return
        val part = File(dir(type), "$fileName.part")
        part.delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Levi: $title")
            .setDescription("Mengunduh model untuk dipakai offline")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, folder(type), "$fileName.part")
        val id = dm.enqueue(request)
        prefs.setPendingDownload(k, id)
        _states.value = _states.value + (k to DownloadState.Running(0, 0, false))
        ensurePolling()
    }

    fun cancel(type: ModelType, fileName: String) {
        val k = key(type, fileName)
        prefs.pendingDownloads()[k]?.let { dm.remove(it) }
        prefs.setPendingDownload(k, null)
        File(dir(type), "$fileName.part").delete()
        _states.value = _states.value - k
        _revision.value++
    }

    fun delete(type: ModelType, fileName: String) {
        file(type, fileName).delete()
        _revision.value++
    }

    fun clearFailure(type: ModelType, fileName: String) {
        _states.value = _states.value - key(type, fileName)
    }

    /** Salin file model milik sendiri (misal .gguf hasil unduhan browser) ke folder Levi */
    suspend fun importFile(type: ModelType, uri: Uri, fileName: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = file(type, fileName)
            val tmp = File(dir(type), "$fileName.import")
            val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "File tidak bisa dibuka" }
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) onProgress(copied.toFloat() / total)
                    }
                }
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                error("Gagal menyimpan file model")
            }
            _revision.value++
            target
        }

    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pending = prefs.pendingDownloads()
                if (pending.isEmpty()) break
                pending.forEach { (k, id) -> refreshOne(k, id) }
                delay(1000)
            }
        }
    }

    private fun refreshOne(k: String, id: Long) {
        val (folderName, fileName) = k.split("/", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val type = if (folderName == "models") ModelType.LLM else ModelType.WHISPER
        val cursor = dm.query(DownloadManager.Query().setFilterById(id))
        cursor.use { c ->
            if (c == null || !c.moveToFirst()) {
                // dibatalkan dari notifikasi
                prefs.setPendingDownload(k, null)
                _states.value = _states.value - k
                _revision.value++
                return
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val part = File(dir(type), "$fileName.part")
                    val target = File(dir(type), fileName)
                    target.delete()
                    part.renameTo(target)
                    prefs.setPendingDownload(k, null)
                    _states.value = _states.value - k
                    _revision.value++
                }
                DownloadManager.STATUS_FAILED -> {
                    dm.remove(id)
                    prefs.setPendingDownload(k, null)
                    File(dir(type), "$fileName.part").delete()
                    _states.value = _states.value + (k to DownloadState.Failed(failReason(reason)))
                    _revision.value++
                }
                else -> {
                    val paused = status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_PENDING
                    _states.value = _states.value + (k to DownloadState.Running(done, total, paused))
                }
            }
        }
    }

    private fun failReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Memori HP penuh"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "Koneksi terputus, coba unduh lagi"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Server menolak unduhan"
        in 400..599 -> "Server menjawab kode $reason"
        else -> "Gagal mengunduh (kode $reason)"
    }

    companion object {
        fun fileNameFromUrl(url: String): String {
            val path = Uri.parse(url).lastPathSegment ?: "model.gguf"
            return path.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }
    }
}
