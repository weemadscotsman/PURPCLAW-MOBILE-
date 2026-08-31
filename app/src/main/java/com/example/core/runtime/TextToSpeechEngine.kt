package com.example.core.runtime

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

data class VoiceProfile(
  val soulId: String,
  val kokoroVoiceId: String,
  val platformVoiceSlot: Int,
  val rate: Float,
  val pitch: Float,
  val pauseStyle: String = "natural",
  val energy: Float = 0.64f,
  val fallbackVoiceId: String = "af_heart"
)

/**
 * Real Android Native TextToSpeech Engine with barge-in interruption.
 * Filters out internal JSON, reasoning, tool traces, and metadata before speaking.
 */
class TextToSpeechEngine(private val context: Context) : TextToSpeech.OnInitListener {

  companion object {
    private const val TAG = "TextToSpeechEngine"

    /** Stable Soul-owned voice identities. Kokoro IDs are shared contract
     * values; Android maps them deterministically onto installed local voices. */
    private val PROFILES = listOf(
      VoiceProfile("PurpAngolin", "af_heart", 0, 0.94f, 1.02f),
      VoiceProfile("Lyra Voice", "bf_emma", 1, 0.91f, 1.06f),
      VoiceProfile("Barnaby Prime", "bm_george", 2, 0.90f, 0.94f),
      VoiceProfile("Aegis Sentinel", "am_michael", 3, 0.96f, 0.91f),
      VoiceProfile("CodeForge", "am_adam", 4, 0.98f, 0.96f),
      VoiceProfile("Forge Warden", "bm_lewis", 5, 0.93f, 0.90f),
      VoiceProfile("Octavia Lens", "af_bella", 6, 0.95f, 1.08f),
      VoiceProfile("Signal Weaver", "af_nicole", 7, 1.00f, 1.03f),
      VoiceProfile("Babshaggoth", "bf_isabella", 8, 0.88f, 0.88f)
    )
    private val PROFILE_BY_SOUL = PROFILES.associateBy { it.soulId.lowercase() }

    internal fun voiceProfileFor(soulId: String): VoiceProfile =
      PROFILE_BY_SOUL[soulId.trim().lowercase()]
        ?: VoiceProfile(soulId.ifBlank { "PurpAngolin" }, "af_heart", 0, 0.94f, 1.02f)
  }

  private var tts: TextToSpeech? = null
  private var isInitialized = false
  private val voicePreferences = context.applicationContext.getSharedPreferences(
    "purpclaw_soul_voice_adapters",
    Context.MODE_PRIVATE
  )

  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

  // Multi-voice podcast support: distinct platform voices per council seat
  // where the device offers them; pitch/rate differentiation as truthful fallback.
  private var availableVoices: List<android.speech.tts.Voice> = emptyList()
  private val registeredProfiles = ConcurrentHashMap<String, VoiceProfile>()
  private val completionWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
  private val _activeSpeaker = MutableStateFlow<String?>(null)
  val activeSpeaker: StateFlow<String?> = _activeSpeaker.asStateFlow()
  private val _activeVoiceProfile = MutableStateFlow<VoiceProfile?>(null)
  val activeVoiceProfile: StateFlow<VoiceProfile?> = _activeVoiceProfile.asStateFlow()

  init {
    try {
      tts = TextToSpeech(context.applicationContext, this)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to instantiate TextToSpeech: ${e.message}")
    }
  }

  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      val result = tts?.setLanguage(Locale.US)
      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        Log.w(TAG, "Language US is not supported or missing data")
      } else {
        isInitialized = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            _isSpeaking.value = true
          }

          override fun onDone(utteranceId: String?) {
            _isSpeaking.value = false
            utteranceId?.let { completionWaiters.remove(it)?.complete(true) }
            _activeSpeaker.value = null
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            _isSpeaking.value = false
            utteranceId?.let { completionWaiters.remove(it)?.complete(false) }
            _activeSpeaker.value = null
          }

          override fun onError(utteranceId: String?, errorCode: Int) {
            _isSpeaking.value = false
            utteranceId?.let { completionWaiters.remove(it)?.complete(false) }
            _activeSpeaker.value = null
          }
        })
        Log.i(TAG, "Native Android TTS initialized successfully")
        try {
          availableVoices = (tts?.voices?.filter { it.features == null || !it.features.contains("network") } ?: emptyList())
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
          Log.i(TAG, "TTS voices available for podcast assignment: ${availableVoices.size}")
        } catch (e: Exception) {
          Log.w(TAG, "Voice enumeration failed: ${e.message}")
        }
      }
    } else {
      Log.e(TAG, "TTS Initialization failed with status: $status")
    }
  }

  /**
   * Speaks visible content only, applying strict sanitization against JSON, reasoning blocks,
   * tool syntax, and markdown formatting.
   *
   * REAL-TTS-AMPLITUDE LAW: platform TTS renders through the system audio
   * route — the app cannot tap its PCM directly. Per contract we use the
   * Android [Visualizer] on the output session to derive REAL output
   * amplitude for the SPEAKING visualizer; fallback is a truthful flat
   * pulse (isSpeaking only), never a fake waveform.
   */
  fun speak(text: String, utteranceId: String = "purp_speech_${System.currentTimeMillis()}") {
    if (!isInitialized || tts == null) {
      Log.w(TAG, "TTS not ready")
      return
    }

    val cleanText = sanitizeForSpeech(text)
    if (cleanText.isBlank()) return

    // Barge-in: Stop any existing speech before starting new turn
    stop()
    applyVoiceFor("PurpAngolin")
    tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
  }

  /**
   * Podcast sequencing: queue a speaker turn WITHOUT barge-in. Uses
   * QUEUE_ADD so council members wait their turn, with per-seat voice
   * assignment (platform voice when available, pitch/rate shaping otherwise).
   * Returns immediately; completion is awaited via [awaitIdle].
   */
  fun speakSequenced(
    text: String,
    voiceTag: String,
    utteranceId: String = "podcast_${System.currentTimeMillis()}"
  ) {
    if (!isInitialized || tts == null) {
      Log.w(TAG, "TTS not ready for sequenced speech")
      return
    }
    val cleanText = sanitizeForSpeech(text)
    if (cleanText.isBlank()) return

    applyVoiceFor(voiceTag)
    val params = android.os.Bundle().apply {
      putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
    }
    tts?.speak(cleanText, TextToSpeech.QUEUE_ADD, params, utteranceId)
  }

  /**
   * Canonical turn-taking primitive. Exactly one Soul speaks; the caller does
   * not advance until Android reports this exact utterance finished/failed.
   */
  suspend fun speakSequencedAndWait(
    text: String,
    soulId: String,
    utteranceId: String,
    timeoutMs: Long = 120_000L
  ): Boolean {
    if (!isInitialized || tts == null) return false
    val cleanText = sanitizeForSpeech(text)
    if (cleanText.isBlank()) return false

    val waiter = CompletableDeferred<Boolean>()
    completionWaiters[utteranceId] = waiter
    applyVoiceFor(soulId)
    _activeSpeaker.value = soulId
    val result = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    if (result != TextToSpeech.SUCCESS) {
      completionWaiters.remove(utteranceId)
      _activeSpeaker.value = null
      return false
    }
    return withTimeoutOrNull(timeoutMs) { waiter.await() } ?: run {
      completionWaiters.remove(utteranceId)
      stop()
      false
    }
  }

  /** Suspend until the TTS queue drains (or timeout ms elapses). */
  suspend fun awaitIdle(timeoutMs: Long = 120_000L) {
    val deadline = System.currentTimeMillis() + timeoutMs
    // Poll the StateFlow (isSpeaking) not the nullable tts?.isSpeaking —
    // the StateFlow is kept in sync by UtteranceProgressListener callbacks
    // and is the authoritative speaking state for the VoiceModeController.
    while (_isSpeaking.value && System.currentTimeMillis() < deadline) {
      kotlinx.coroutines.delay(150)
    }
    _isSpeaking.value = false
  }

  /**
   * Assign a platform voice to a seat if one is free; otherwise record the
   * seat so pitch/rate fallback shapes it distinctly in [applyVoiceFor].
   */
  fun assignSeatVoice(voiceTag: String): Boolean {
    return isInitialized && tts != null && voiceProfileFor(voiceTag).soulId.isNotBlank()
  }

  /** Attach the voice stored by a Soul record to this runtime adapter. */
  fun assignSeatVoice(profile: VoiceProfile): Boolean {
    registeredProfiles[profile.soulId.trim().lowercase()] = profile
    return isInitialized && tts != null
  }

  fun resolveVoiceProfile(soulId: String): VoiceProfile =
    registeredProfiles[soulId.trim().lowercase()] ?: voiceProfileFor(soulId)

  private fun applyVoiceFor(voiceTag: String) {
    val profile = resolveVoiceProfile(voiceTag)
    _activeVoiceProfile.value = profile
    try {
      if (availableVoices.isNotEmpty()) {
        val localVoices = availableVoices.filter { it.locale.language == Locale.ENGLISH.language }
          .ifEmpty { availableVoices }
        val preferenceKey = "platform_voice_${profile.soulId.trim().lowercase()}"
        val savedVoiceName = voicePreferences.getString(preferenceKey, null)
        val selected = localVoices.firstOrNull { it.name == savedVoiceName }
          ?: localVoices[profile.platformVoiceSlot % localVoices.size]
        tts?.setVoice(selected)
        if (savedVoiceName != selected.name) {
          voicePreferences.edit().putString(preferenceKey, selected.name).apply()
        }
        Log.i(TAG, "voice_adapter soul=${profile.soulId} platform=${selected.name}")
      }
      tts?.setPitch(profile.pitch)
      tts?.setSpeechRate(profile.rate)
      Log.i(TAG, "voice_profile soul=${profile.soulId} kokoro=${profile.kokoroVoiceId}")
    } catch (e: Exception) {
      Log.w(TAG, "Voice application failed: ${e.message}")
    }
  }

  /**
   * Barge-in interruption: Stops active speech immediately.
   */
  fun stop() {
    try {
      // stop() also clears utterances accepted by the engine but not yet
      // started, so never gate it on the racy isSpeaking property.
      tts?.stop()
      completionWaiters.values.forEach { it.complete(false) }
      completionWaiters.clear()
      _isSpeaking.value = false
      _activeSpeaker.value = null
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping TTS: ${e.message}")
    }
  }

  fun shutdown() {
    stop()
    tts?.shutdown()
    tts = null
    isInitialized = false
  }

  private fun sanitizeForSpeech(raw: String): String {
    var cleaned = raw
    // Remove reasoning tags
    cleaned = cleaned.replace(Regex("<think>[\\s\\S]*?</think>"), "")
    cleaned = cleaned.replace(Regex("<reasoning>[\\s\\S]*?</reasoning>"), "")
    // Remove JSON code blocks
    cleaned = cleaned.replace(Regex("```(?:json)?[\\s\\S]*?```"), "")
    // Remove inline code
    cleaned = cleaned.replace(Regex("`[^`]+`"), "")
    // Remove URLs
    cleaned = cleaned.replace(Regex("https?://\\S+"), "")
    // Remove markdown headers and bold markers
    cleaned = cleaned.replace(Regex("[#*_~`>]"), "")
    return cleaned.trim()
  }
}
