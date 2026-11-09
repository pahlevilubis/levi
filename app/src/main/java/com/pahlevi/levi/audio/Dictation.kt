package com.pahlevi.levi.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dikte: ubah ucapan jadi teks memakai pengenal suara bawaan HP (Google/Samsung).
 * Terus mendengarkan sampai dihentikan. Harus dipakai dari thread utama.
 */
class Dictation(private val context: Context) {
    data class State(
        val listening: Boolean = false,
        val partial: String = "",
        val level: Float = 0f,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var wanted = false
    private var language = "id-ID"
    private var onFinal: (String) -> Unit = {}

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String, onFinal: (String) -> Unit) {
        this.language = language
        this.onFinal = onFinal
        wanted = true
        _state.value = State(listening = true)
        listenOnce()
    }

    fun stop() {
        wanted = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        _state.value = _state.value.copy(listening = false, partial = "", level = 0f)
    }

    private fun listenOnce() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            _state.value = _state.value.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _state.value = _state.value.copy(partial = text)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _state.value = _state.value.copy(partial = "")
            if (text.isNotBlank()) onFinal(text)
            if (wanted) listenOnce() else _state.value = _state.value.copy(listening = false)
        }

        override fun onError(error: Int) {
            when (error) {
                // diam terlalu lama / tidak ada yang cocok: dengarkan lagi
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (wanted) listenOnce()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    recognizer?.destroy()
                    recognizer = null
                    if (wanted) listenOnce()
                }
                else -> {
                    wanted = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                            "Pengenal suara HP butuh internet untuk bahasa ini. Unduh paket bahasa offline di Pengaturan HP → Google → Ucapan, atau pakai Rekam + Transkrip."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "Bahasa ini belum tersedia di pengenal suara HP"
                        else -> "Dikte berhenti (kode $error)"
                    }
                    _state.value = State(listening = false, error = msg)
                }
            }
        }
    }
}
