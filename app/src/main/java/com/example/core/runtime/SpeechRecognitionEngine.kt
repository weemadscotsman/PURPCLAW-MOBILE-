package com.example.core.runtime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Real Android SpeechRecognizer for microphone transcription.
 */
class SpeechRecognitionEngine(private val context: Context) {

  companion object {
    private const val TAG = "SpeechRecognitionEngine"
  }

  private var speechRecognizer: SpeechRecognizer? = null
  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

  private val _transcribedText = MutableStateFlow("")
  val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

  private val _recognitionError = MutableStateFlow<String?>(null)
  val recognitionError: StateFlow<String?> = _recognitionError.asStateFlow()

  /** Real microphone amplitude 0..1 fed from onRmsChanged (input truth). */
  private val _micLevel = MutableStateFlow(0f)
  val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

  fun isAvailable(): Boolean {
    return SpeechRecognizer.isRecognitionAvailable(context)
  }

  fun startListening(onResult: (String) -> Unit) {
    if (!isAvailable()) {
      _recognitionError.value = "Speech recognition is not available on this device"
      return
    }

    try {
      stopListening()
      speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
        setRecognitionListener(object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _recognitionError.value = null
          }

          override fun onBeginningOfSpeech() {}
          override fun onRmsChanged(rmsdB: Float) {
            // rmsdB typically lands in -2..10 — normalize to 0..1.
            _micLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
          }
          override fun onBufferReceived(buffer: ByteArray?) {}
          override fun onEndOfSpeech() {
            _isListening.value = false
            _micLevel.value = 0f
          }

          override fun onError(error: Int) {
            _isListening.value = false
            val errorMsg = when (error) {
              SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
              SpeechRecognizer.ERROR_CLIENT -> "Client side error"
              SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions (RECORD_AUDIO required)"
              SpeechRecognizer.ERROR_NETWORK -> "Network error"
              SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
              SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognition match"
              SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
              SpeechRecognizer.ERROR_SERVER -> "Server error"
              SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
              else -> "Recognition error: $error"
            }
            _recognitionError.value = errorMsg
            _micLevel.value = 0f
            Log.w(TAG, errorMsg)
          }

          override fun onResults(results: Bundle?) {
            _isListening.value = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val bestMatch = matches?.firstOrNull().orEmpty()
            if (bestMatch.isNotBlank()) {
              _transcribedText.value = bestMatch
              onResult(bestMatch)
            }
          }

          override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull().orEmpty()
            if (partial.isNotBlank()) {
              _transcribedText.value = partial
            }
          }

          override fun onEvent(eventType: Int, params: Bundle?) {}
        })
      }

      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      }

      speechRecognizer?.startListening(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start speech listening: ${e.message}", e)
      _isListening.value = false
      _recognitionError.value = "Failed to start microphone: ${e.message}"
    }
  }

  fun stopListening() {
    try {
      speechRecognizer?.stopListening()
      speechRecognizer?.destroy()
      speechRecognizer = null
      _isListening.value = false
      _micLevel.value = 0f
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping speech recognizer: ${e.message}")
    }
  }

  /**
   * Finish the current utterance and allow Android to deliver onResults.
   * Unlike stopListening(), this deliberately does not destroy the recognizer.
   * Podcast push-to-talk uses this on button release so the held speech is not
   * discarded before the final transcript callback arrives.
   */
  fun finishListening() {
    try {
      speechRecognizer?.stopListening()
      _isListening.value = false
      _micLevel.value = 0f
    } catch (e: Exception) {
      Log.e(TAG, "Error finalizing speech recognizer: ${e.message}")
      _recognitionError.value = "Failed to finalize microphone input: ${e.message}"
    }
  }
}
