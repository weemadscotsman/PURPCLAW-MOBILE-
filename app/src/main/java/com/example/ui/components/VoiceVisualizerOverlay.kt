package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.runtime.VoiceMode
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.TextHighlight
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * VOICE VISUALIZER OVERLAY — transparent canvas OVER the chat canvas.
 * Pointer-transparent: taps pass through to the conversation underneath.
 *
 * TRUTH LAW (web parity, voice-mode.js):
 *  - LISTENING reacts to REAL mic level (voiceInputLevel).
 *  - SPEAKING pulses ONLY while TTS playback is genuinely live
 *    (voiceOutputLevel > 0 — controller zeroes it when playback ends).
 *  - TRANSCRIBING / THINKING get a truthful state pulse, NEVER fake audio
 *    activity. OFF fades the whole overlay out.
 */
@Composable
fun VoiceVisualizerOverlay(
  voiceMode: VoiceMode,
  inputLevel: Float,
  outputLevel: Float,
  modifier: Modifier = Modifier,
) {
  val visible = voiceMode != VoiceMode.OFF
  if (!visible) return

  // Truthful activity source per state
  val audioActive = when (voiceMode) {
    VoiceMode.LISTENING -> inputLevel > 0.02f
    VoiceMode.SPEAKING -> outputLevel > 0.02f
    else -> false
  }
  val level = when (voiceMode) {
    VoiceMode.LISTENING -> inputLevel
    VoiceMode.SPEAKING -> outputLevel
    else -> 0f
  }

  // State pulse for non-audio states (TRANSCRIBING/THINKING)
  val pulse = rememberInfiniteTransition(label = "statePulse")
  val pulseAlpha by pulse.animateFloat(
    initialValue = 0.25f,
    targetValue = if (audioActive) 0.9f else 0.55f,
    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
    label = "pulseAlpha"
  )

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center
  ) {
    // DIM LAW: voice overlay darkens the chat underneath so the visualizer
    // owns the screen — conversation fades back, no text competing with rings.
    Box(
      Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.55f))
    )
    Canvas(
      Modifier
        .fillMaxSize()
        .padding(horizontal = 48.dp, vertical = 40.dp)
    ) {
      val c = center
      val baseR = min(size.width, size.height) * 0.22f
      val accent = when (voiceMode) {
        VoiceMode.SPEAKING -> TextHighlight
        VoiceMode.LISTENING -> PurpNeon
        else -> CyanAccent
      }
      val a = if (audioActive) 0.35f + 0.45f * level else pulseAlpha * 0.5f

      // Orb: breathing rings driven by REAL level (or state pulse).
      // THICK LAW: heavy strokes + layered shades so rings read boldly over
      // the dimmed chat instead of ghosting into it.
      drawCircle(color = accent.copy(alpha = a * 0.20f), radius = baseR * (1f + 0.90f * level), center = c, style = Stroke(width = 5.5f))   // outermost faint halo
      drawCircle(color = accent.copy(alpha = a * 0.45f), radius = baseR * (1f + 0.70f * level), center = c, style = Stroke(width = 4.5f))   // mid halo
      drawCircle(color = accent.copy(alpha = a * 0.60f), radius = baseR * (1f + 0.55f * level + 0.06f * sin(pulseAlpha * 12f)), center = c, style = Stroke(width = 5f)) // breathing band
      drawCircle(color = accent.copy(alpha = a * 0.95f), radius = baseR * (1f + 0.30f * level), center = c, style = Stroke(width = 3.5f))  // bright core ring
      drawCircle(color = Color.White.copy(alpha = a * 0.35f), radius = baseR * (1f + 0.30f * level), center = c, style = Stroke(width = 1.2f)) // white inner glint

      // Spectrum spokes — amplitude from REAL level; idle = faint tick ring.
      // Two-tone per spoke: bright tip over dimmer root gives depth/shading.
      val spokes = 48
      for (i in 0 until spokes) {
        val ang = (i.toFloat() / spokes) * (2.0 * Math.PI).toFloat()
        val amp = if (audioActive) {
          // pseudo-spectrum shaped around the single real level value
          (level * (0.6f + 0.4f * sin(i * 1.7f)) * (0.7f + 0.3f * cos(i * 0.53f)))
            .coerceIn(0.04f, 1f)
        } else 0.05f
        val r0 = baseR * (1f + 0.30f * level) + 12f
        val r1 = r0 + baseR * 0.75f * amp
        val dir = Offset(cos(ang).toFloat(), sin(ang).toFloat())
        val start = c + Offset((r0 * cos(ang)).toFloat(), (r0 * sin(ang)).toFloat())
        val end = c + Offset((r1 * cos(ang)).toFloat(), (r1 * sin(ang)).toFloat())
        val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
        drawLine(
          color = accent.copy(alpha = a * 0.45f),
          start = start, end = mid,
          strokeWidth = 3.5f
        )
        drawLine(
          color = accent.copy(alpha = a),
          start = mid, end = end,
          strokeWidth = 4.5f
        )
      }
    }
    if (true) {
      Text(
        "● " + voiceMode.label,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = TextHighlight.copy(alpha = 0.8f),
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 28.dp)
      )
    }
  }
}
