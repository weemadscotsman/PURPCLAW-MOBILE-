package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.PurpNeon

/**
 * Mood drives both visual state and accessibility semantics — same
 * naming everywhere so the operator can read state out of a screenshot.
 */
enum class PurpAngolinMood(val label: String) {
  IDLE("idle"),
  ACTIVE("active"),
  THINKING("thinking"),
  LISTENING("listening"),
  SPEAKING("speaking"),
  DEGRADED("degraded"),
  FAILED("failed"),
  DANCING("dancing"),      // manual character action; never a runtime success alias
  BOUNCING("bouncing"),     // task #60: feed came back
  FLOURISH("flourish");     // task #50: boot complete

  val isFailed: Boolean get() = this == FAILED
  val isBoot: Boolean get() = this == BOUNCING || this == FLOURISH
}

/**
 * PurpAngolin — the canonical mascot. Used on:
 *   • chat header (24–32dp),
 *   • boot / BIOS overlay (96–200dp),
 *   • live status row (next to the typing indicator),
 *   • podcast tile (48dp),
 *   • reconnect overlay (128dp).
 *
 * Render is purely vector + animation, no GLB dependency, so the same
 * avatar shows up identically on every device that boots PurpClaw. When
 * the real Meshy GLB arrives (task #50 follow-up), replace the
 * `painterResource(...)` with a SceneView and keep this composable as the
 * mood → animation contract so consumers don't change.
 */
@Composable
fun PurpAngolinAvatar(
  mood: PurpAngolinMood,
  modifier: Modifier = Modifier,
  size: Dp? = null,
  showGlow: Boolean = true,
  tint: Color? = null
) {
  // Default sizing by context. Phone portrait = 64dp chat header. Tablet /
  // landscape / boot screen = 128dp. Caller can override.
  val configuration = LocalConfiguration.current
  val resolvedSize = size ?: when {
    configuration.screenWidthDp >= 720 -> 96.dp
    mood.isBoot -> 128.dp
    else -> 56.dp
  }

  // Mood → animation. Keeps the rules in one place so the boot overlay,
  // the chat assistant card, the podcast host badge, and the reconnect
  // overlay all read identically.
  val transition = rememberInfiniteTransition(label = "purpangolin")
  val breathing by transition.animateFloat(
    initialValue = 0.96f,
    targetValue = 1.04f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1600, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "breathe"
  )
  val wobble by transition.animateFloat(
    initialValue = -3f,
    targetValue = 3f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 800, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "wobble"
  )
  // One-shot bounce/flourish when mood = BOUNCING/FLOURISH. The avatar
  // hops into place then settles. Implemented as a finite Animatable
  // mounted on first appearance of those moods.
  val bounceScale = remember { Animatable(1f) }
  LaunchedEffect(mood) {
    when (mood) {
      PurpAngolinMood.BOUNCING -> bounceScale.animateTo(1.15f, tween(350)) { /* settle */ }
      PurpAngolinMood.FLOURISH -> {
        bounceScale.animateTo(0.9f, tween(220))
        bounceScale.animateTo(1.18f, tween(320))
        bounceScale.animateTo(1f, tween(260))
      }
      else -> bounceScale.snapTo(1f)
    }
  }

  val baseAlpha = when (mood) {
    PurpAngolinMood.FAILED -> 0.55f
    PurpAngolinMood.DEGRADED -> 0.85f
    else -> 1f
  }
  val redBoost = if (mood == PurpAngolinMood.FAILED) 1f else 0f

  val tintFilter = tint?.let { ColorFilter.tint(it) }
  val finalAlpha = baseAlpha * breathing.coerceIn(0.85f, 1.05f).let { 1f }

  Box(
    modifier = modifier
      .size(resolvedSize)
      .semantics { contentDescription = "PurpAngolin (${mood.label})" },
    contentAlignment = Alignment.Center
  ) {
    if (showGlow) {
      // Mood-driven halo. Failed = pulsing red. Boot = bright purple
      // ring that fades in. Idle/active = soft purple halo at 35%.
      val haloAlpha = when (mood) {
        PurpAngolinMood.FAILED -> 0.55f + (0.2f * (1f - breathing))
        PurpAngolinMood.FLOURISH -> 0.7f
        PurpAngolinMood.BOUNCING -> 0.6f
        else -> 0.35f
      }
      val haloColor = when {
        mood == PurpAngolinMood.FAILED -> Color(0xFFFF5252)
        mood.isBoot -> PurpNeon
        else -> PurpNeon
      }
      Box(
        modifier = Modifier
          .size(resolvedSize * 1.45f)
          .background(haloColor.copy(alpha = haloAlpha), CircleShape)
          .alpha(if (redBoost > 0f) 1f else 1f)
      )
    }

    Image(
      painter = painterResource(id = R.drawable.purpangolin_avatar),
      contentDescription = null,
      modifier = Modifier
        .size(resolvedSize)
        .scale(bounceScale.value * breathing)
        .rotate(wobble * when (mood) {
          PurpAngolinMood.DEGRADED -> 1.6f
          PurpAngolinMood.THINKING -> 1.2f
          PurpAngolinMood.FAILED -> 0.4f
          else -> 1f
        })
        .graphicsLayer {
          // Mood-driven desaturation for failed/degraded, full color otherwise.
          if (mood == PurpAngolinMood.FAILED) {
            alpha = finalAlpha * 0.7f
          } else {
            alpha = finalAlpha
          }
        },
      colorFilter = tintFilter
    )
  }
}

/** Convenience for the chat assistant: same icon, smaller, fixed position. */
@Composable
fun PurpAngolinInline(
  mood: PurpAngolinMood = PurpAngolinMood.IDLE,
  size: Dp = 28.dp
) {
  PurpAngolinAvatar(mood = mood, size = size, showGlow = false)
}
