package com.pahlevi.levi.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Membaca teks dari foto atau PDF, sepenuhnya offline (ML Kit). */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun fromImage(context: Context, uri: Uri): String {
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
        return recognizer.process(image).await().text.trim()
    }

    private suspend fun fromBitmap(bitmap: Bitmap): String =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()

    fun pdfPageCount(context: Context, uri: Uri): Int =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        } ?: 0

    /** Render tiap halaman PDF jadi gambar lalu dibaca teksnya. */
    suspend fun fromPdf(
        context: Context,
        uri: Uri,
        maxPages: Int = 60,
        onProgress: (page: Int, total: Int) -> Unit,
    ): String {
        val sb = StringBuilder()
        val pfd = withContext(Dispatchers.IO) { context.contentResolver.openFileDescriptor(uri, "r") }
            ?: error("PDF tidak bisa dibuka")
        pfd.use {
            PdfRenderer(pfd).use { renderer ->
                val total = minOf(renderer.pageCount, maxPages)
                for (i in 0 until total) {
                    onProgress(i + 1, total)
                    val bitmap = withContext(Dispatchers.Default) {
                        renderer.openPage(i).use { page ->
                            val scale = (1600f / page.width).coerceIn(1f, 3f)
                            val w = (page.width * scale).toInt()
                            val h = (page.height * scale).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                    val text = try { fromBitmap(bitmap) } finally { bitmap.recycle() }
                    if (total > 1) sb.append("— Halaman ${i + 1} —\n")
                    sb.append(text).append("\n\n")
                }
                if (renderer.pageCount > maxPages) {
                    sb.append("(Hanya $maxPages halaman pertama yang dibaca dari ${renderer.pageCount} halaman.)")
                }
            }
        }
        return sb.toString().trim()
    }
}
