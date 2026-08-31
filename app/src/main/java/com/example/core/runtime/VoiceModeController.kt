package com.example.core.runtime

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Canonical voice loop states — one truth source for the mic icon AND the
 * visualizer overlay on every surface.
 */
enum class VoiceMode(val label: String) {
  OFF("OFF"),
  LISTENING("LISTENING"),
  TRANSCRIBING("TRANSCRIBING"),
  THINKING("THINKING"),
  SPEAKING("SPEAKING"),
  TTS_DRAINING("TTS DRAINING"),
  ERROR("ERROR"),
  MUTED("MUTED"),
  INTERRUPTED("INTERRUPTED")
}

/** One audio owner at a time. This extends the existing controller; it is not
 * another voice state machine. */
enum class VoiceOwner { NONE, USER_MIC, ASSISTANT_TTS, EXTERNAL_CHAT_MIC }

/**
 * Conversation mode — HOW a voice session behaves once started.
 * Mirrors the web cockpit contract exactly: ptt | voice-in | voice-inout |
 * hands-free. CANONICAL SETTINGS LAW (2026-08-25 correction): mode is
 * selected in Settings only — the mic button NEVER cycles configuration.
 * Tap = voice ON/OFF toggle, full stop.
 */
enum class ConversationMode(val id: String, val label: String) {
  PTT("ptt", "push-to-talk"),
  VOICE_IN("voice-in", "voice in"),
  VOICE_INOUT("voice-inout", "voice in+out"),
  HANDS_FREE("hands-free", "hands-free");
  companion object {
    fun next(cur: ConversationMode): ConversationMode =
      entries[(entries.indexOf(cur) + 1) % entries.size]
    fun fromId(id: String?): ConversationMode? =
      entries.firstOrNull { it.id == id }
  }
}

/**
 * VOICE MODE CONTROLLER — owns the continuous
 * OFF -> LISTENING -> TRANSCRIBING -> THINKING -> SPEAKING -> LISTENING loop.
 *
 * Interaction law:
 *   Mic tap            = direct VOICE ON/OFF toggle (no submenu)
 *   Conversation mode  = Settings → Voice ONLY (web parity; mic never cycles)
 *   Mic tap @ SPEAKING = BARGE-IN: halt TTS, back to LISTENING
 *   Hands-free         = after each spoken reply, auto-relisten
 *
 * TRUTH LAW: levels are real. inputLevel comes from the recognizer's RMS
 * callback; outputLevel animates only while TTS playback is genuinely
 * active (isSpeaking == true). TRANSCRIBING/THINKING never show audio
 * activity — they get a state pulse only.
 */
class VoiceModeController(
  private val speechEngine: SpeechRecognitionEngine,
  private val ttsEngine: TextToSpeechEngine,
  /** Persisted mode store ("purpclaw_state" prefs) — localStorage parity with web. */
  private val prefs: android.content.SharedPreferences? = null
) {

  companion object {
    private const val TAG = "VoiceModeController"
    private const val RELISTEN_SETTLE_MS = 350L
    private const val ERROR_LINGER_MS = 2500L
    private const val PREF_CONVERSATION_MODE = "voice.conversation_mode"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  /** Persisted conversation mode — mirrors web cockpit CFG.mode exactly. */
  private val _conversationMode = MutableStateFlow(
    ConversationMode.fromId(prefs?.getString(PREF_CONVERSATION_MODE, null))
      ?: ConversationMode.VOICE_INOUT
  )
  val conversationMode: StateFlow<ConversationMode> = _conversationMode.asStateFlow()

  /** Set by MainViewModel: final transcript -> fill composer -> send. */
  var onTranscript: (String) -> Unit = {}

  private val _mode = MutableStateFlow(VoiceMode.OFF)
  val mode: StateFlow<VoiceMode> = _mode.asStateFlow()

  private val _owner = MutableStateFlow(VoiceOwner.NONE)
  val owner: StateFlow<VoiceOwner> = _owner.asStateFlow()

  private val _selfCaptureRejections = MutableStateFlow(0)
  val selfCaptureRejections: StateFlow<Int> = _selfCaptureRejections.asStateFlow()

  /** Real microphone amplitude 0..1 fed from onRmsChanged. */
  private val _inputLevel = MutableStateFlow(0f)
  val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

  /** Playback-driven output level 0..1; zero whenever TTS is not live. */
  private val _outputLevel = MutableStateFlow(0f)
  val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()

  private var continuous = false
  private var collectingInput = false
  private var ownershipEpoch = 0L

  init {
    // INPUT TRUTH: recognizer RMS -> inputLevel while the ear is open.
    scope.launch {
      speechEngine.micLevel.collect { lvl ->
        when (_mode.value) {
          VoiceMode.LISTENING, VoiceMode.INTERRUPTED -> _inputLevel.value = lvl
          else -> {}
        }
      }
    }

    // OUTPUT TRUTH: TTS playback drives SPEAKING + a real-state envelope.
    // No playback => zero output. Never fakes activity during THINKING.
    scope.launch {
      ttsEngine.isSpeaking.collect { speaking ->
        if (speaking) {
          val playbackEpoch = ownershipEpoch
          // Half-duplex ownership is mandatory: Android STT must be dead
          // before speaker output can become audible.
          speechEngine.stopListening()
          collectingInput = false
          _owner.value = VoiceOwner.ASSISTANT_TTS
          if (_mode.value != VoiceMode.OFF) _mode.value = VoiceMode.SPEAKING
          runPlaybackEnvelope()
          finishPlaybackOwnership(playbackEpoch)
        } else if (!speaking && _mode.value == VoiceMode.SPEAKING) {
          _outputLevel.value = 0f
        }
      }
    }

    // ERROR TRUTH: recognizer failures surface as a real ERROR state.
    scope.launch {
      speechEngine.recognitionError.collect { err ->
        val earOpen = _mode.value == VoiceMode.LISTENING ||
            _mode.value == VoiceMode.TRANSCRIBING ||
            _mode.value == VoiceMode.INTERRUPTED
        if (err != null && earOpen) {
          Log.w(TAG, "voice loop error: $err")
          _mode.value = VoiceMode.ERROR
          _inputLevel.value = 0f
          delay(ERROR_LINGER_MS)
          if (_mode.value == VoiceMode.ERROR) off()
        }
      }
    }
  }

  /**
   * Mic button tap — direct toggle.
   * OFF/ERROR/MUTED -> start listening. SPEAKING -> barge-in. Otherwise -> off.
   */
  fun toggle(handsFree: Boolean = false) {
    when (_mode.value) {
      VoiceMode.OFF, VoiceMode.ERROR, VoiceMode.MUTED -> start(handsFree)
      VoiceMode.SPEAKING -> {
        // BARGE-IN: kill playback, keep the loop alive, reopen the ear.
        ttsEngine.stop()
        _mode.value = VoiceMode.INTERRUPTED
        continuous = true
        relisten()
      }
      else -> off()
    }
  }

  /**
   * Podcast floor control. Holding the mic immediately revokes TTS ownership,
   * opens the real Android recognizer, and prevents an older playback callback
   * from reclaiming the audio device. This is not a second voice runtime.
   */
  fun beginPodcastPushToTalk() {
    ownershipEpoch += 1
    continuous = false
    ttsEngine.stop()
    speechEngine.stopListening()
    collectingInput = false
    _inputLevel.value = 0f
    _outputLevel.value = 0f
    _owner.value = VoiceOwner.USER_MIC
    _mode.value = VoiceMode.LISTENING
    beginRecognition()
  }

  /** Release the podcast floor and preserve the recognizer until onResults. */
  fun endPodcastPushToTalk() {
    if (_owner.value != VoiceOwner.USER_MIC || !collectingInput) return
    _mode.value = VoiceMode.TRANSCRIBING
    speechEngine.finishListening()
  }

  /**
   * DEPRECATED (2026-08-25 canonical-settings correction): the mic button
   * NEVER cycles configuration — mode lives in Settings → Voice only, on
   * every surface. Kept as a no-op-compatible shim so stale call sites
   * compile; UI long-press handlers must be removed.
   */
  @Deprecated("Mic never changes configuration. Use setConversationMode from Settings.")
  fun cycleMode(): ConversationMode = _conversationMode.value

  /** Settings surface — set a specific mode directly (persisted). */
  fun setConversationMode(m: ConversationMode) {
    _conversationMode.value = m
    try { prefs?.edit()?.putString(PREF_CONVERSATION_MODE, m.id)?.apply() } catch (e: Exception) {
      Log.w(TAG, "persist conversationMode failed: ${e.message}")
    }
  }

  private fun start(handsFree: Boolean) {
    // Mode semantics own the loop shape; the parameter is only an override.
    continuous = if (handsFree) true
      else when (_conversationMode.value) {
        ConversationMode.HANDS_FREE -> true
        else -> false
      }
    _inputLevel.value = 0f
    _outputLevel.value = 0f
    _mode.value = VoiceMode.LISTENING
    _owner.value = VoiceOwner.USER_MIC
    beginRecognition()
  }

  private fun beginRecognition() {
    if (collectingInput) return
    collectingInput = true
    try {
      speechEngine.startListening { transcript ->
        collectingInput = false
        if (_owner.value != VoiceOwner.USER_MIC || ttsEngine.isSpeaking.value || _mode.value == VoiceMode.TTS_DRAINING) {
          _selfCaptureRejections.value += 1
          Log.w(TAG, "VOICE_RECOGNITION_REJECTED reason=POSSIBLE_SELF_CAPTURE insertedIntoConversation=false")
          speechEngine.stopListening()
          return@startListening
        }
        // Final result in hand: brief TRANSCRIBING beat, then the caller
        // (MainViewModel.onTranscript) flips us to THINKING on send.
        if (_mode.value == VoiceMode.OFF) return@startListening
        _mode.value = VoiceMode.TRANSCRIBING
        onTranscript(transcript)
        // One-shot modes close the ear after a final result (web: r.stop())
        // but KEEP the loop alive — THINKING -> SPEAKING still follows.
        // A final transcript always closes the ear. Hands-free controls
        // whether it is re-armed after TTS completion, never whether it stays
        // open during THINKING/SPEAKING.
        collectingInput = false
        speechEngine.stopListening()
        _owner.value = VoiceOwner.NONE
      }
    } catch (e: Exception) {
      collectingInput = false
      Log.e(TAG, "beginRecognition failed: ${e.message}")
      _mode.value = VoiceMode.ERROR
      scope.launch {
        delay(ERROR_LINGER_MS)
        if (_mode.value == VoiceMode.ERROR) off()
      }
    }
  }

  /** Called by MainViewModel the moment the model call starts. */
  fun notifyThinking() {
    if (_mode.value != VoiceMode.OFF) {
      speechEngine.stopListening()
      collectingInput = false
      _owner.value = VoiceOwner.NONE
      _mode.value = VoiceMode.THINKING
    }
  }

  /**
   * Called by MainViewModel when a reply lands: returns true when the loop
   * itself asked for voice-out (voice-initiated turn still active).
   */
  fun wantsVoiceOut(): Boolean = _mode.value != VoiceMode.OFF

  /**
   * Output envelope during SPEAKING.
   * REAL-TTS-AMPLITUDE LAW: prefer the Android Visualizer waveform tap on
   * the system output (real PCM-derived amplitude). If the Visualizer
   * cannot attach (permission/route), fall back to a truthful flat pulse
   * keyed to isSpeaking — never fabricate a waveform.
   */
  private var outputVisualizer: android.media.audiofx.Visualizer? = null

  private suspend fun runPlaybackEnvelope() {
    // Try real output tap first
    if (outputVisualizer == null) {
      try {
        val v = android.media.audiofx.Visualizer(0 /* output session */)
        v.enabled = true
        v.captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[1]
        outputVisualizer = v
      } catch (e: Exception) {
        Log.w(TAG, "Visualizer unavailable, falling back to state pulse: ${e.message}")
        outputVisualizer = null
      }
    }
    val viz = outputVisualizer
    if (viz != null) {
      val wave = ByteArray(viz.captureSize)
      while (ttsEngine.isSpeaking.value && scope.isActive) {
        viz.getWaveForm(wave)
        // RMS over unsigned bytes → 0..1
        var sum = 0.0
        for (b in wave) { val s = b.toInt() - 128; sum += s * s }
        val rms = (kotlin.math.sqrt(sum / wave.size) / 128.0).toFloat()
        _outputLevel.value = rms.coerceIn(0f, 1f)
        delay(50)
      }
    } else {
      // Truthful fallback: flat level while speaking, no fake motion
      while (ttsEngine.isSpeaking.value && scope.isActive) {
        _outputLevel.value = 0.55f
        delay(80)
      }
    }
    _outputLevel.value = 0f
  }

  private suspend fun finishPlaybackOwnership(playbackEpoch: Long) {
    if (playbackEpoch != ownershipEpoch) return
    if (_mode.value != VoiceMode.OFF) _mode.value = VoiceMode.TTS_DRAINING
    delay(RELISTEN_SETTLE_MS)
    if (playbackEpoch != ownershipEpoch) return
    _owner.value = VoiceOwner.NONE
    if (continuous) relisten() else off()
  }

  private fun relisten() {
    scope.launch {
      _inputLevel.value = 0f
      _owner.value = VoiceOwner.USER_MIC
      _mode.value = VoiceMode.LISTENING
      beginRecognition()
    }
  }

  /** Hard OFF — everything stops, everything fades. */
  fun off() {
    ownershipEpoch += 1
    continuous = false
    collectingInput = false
    speechEngine.stopListening()
    ttsEngine.stop()
    _inputLevel.value = 0f
    _outputLevel.value = 0f
    _owner.value = VoiceOwner.NONE
    _mode.value = VoiceMode.OFF
  }

  fun shutdown() {
    off()
    scope.cancel()
  }
}
