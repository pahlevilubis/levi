package com.pahlevi.levi.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * Perekam suara ke file WAV 16 kHz mono (format yang langsung bisa ditranskrip Whisper).
 * Ukuran ±1,9 MB per menit.
 */
class WavRecorder(private val file: File) {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val HEADER_SIZE = 44
    }

    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile var level: Float = 0f
        private set
    @Volatile var bytesWritten: Long = 0
        private set

    val durationMs: Long get() = bytesWritten * 1000 / (SAMPLE_RATE * 2)

    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, SAMPLE_RATE) // ±0,5 detik
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon tidak bisa dipakai (mungkin sedang dipakai aplikasi lain)" }

        file.parentFile?.mkdirs()
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        writeHeader(raf, 0)
        bytesWritten = 0
        running = true

        record.startRecording()
        thread = Thread({
            val buf = ShortArray(SAMPLE_RATE / 10) // 100 ms
            val bytes = ByteArray(buf.size * 2)
            var sinceHeader = 0L
            try {
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        sum += (s * s).toDouble()
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    level = (sqrt(sum / n) / 32768.0 * 4).coerceAtMost(1.0).toFloat()
                    if (paused) continue
                    raf.write(bytes, 0, n * 2)
                    bytesWritten += n * 2
                    sinceHeader += n * 2
                    // perbarui header berkala supaya file tetap terbaca kalau HP mati mendadak
                    if (sinceHeader > SAMPLE_RATE * 2 * 10) {
                        writeHeader(raf, bytesWritten)
                        raf.seek(HEADER_SIZE + bytesWritten)
                        sinceHeader = 0
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                writeHeader(raf, bytesWritten)
                raf.close()
            }
        }, "levi-recorder").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
        level = 0f
    }

    private fun writeHeader(raf: RandomAccessFile, dataLen: Long) {
        val byteRate = SAMPLE_RATE * 2
        val h = ByteArray(HEADER_SIZE)
        fun putInt(off: Int, v: Int) { h[off] = v.toByte(); h[off + 1] = (v shr 8).toByte(); h[off + 2] = (v shr 16).toByte(); h[off + 3] = (v shr 24).toByte() }
        fun putShort(off: Int, v: Int) { h[off] = v.toByte(); h[off + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(h, 0)
        putInt(4, (36 + dataLen).toInt())
        "WAVE".toByteArray().copyInto(h, 8)
        "fmt ".toByteArray().copyInto(h, 12)
        putInt(16, 16)
        putShort(20, 1)        // PCM
        putShort(22, 1)        // mono
        putInt(24, SAMPLE_RATE)
        putInt(28, byteRate)
        putShort(32, 2)
        putShort(34, 16)
        "data".toByteArray().copyInto(h, 36)
        putInt(40, dataLen.toInt())
        raf.seek(0)
        raf.write(h)
    }
}
