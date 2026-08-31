package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.PurpBorder
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpSurfaceCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MessageActionBar — canonical inline action rail (2026-08-27 Eddie law:
 * "no more cheap bottom-sheet detour for basic actions").
 *
 * Mounted under EVERY message bubble (user + assistant) via TurnView.
 * Primary actions live here, one tap, no drawer:
 *   Copy · Read Aloud/Stop · Retry (assistant) · Share · More
 *
 * More → opens the secondary action sheet (MessageActionSheet) which keeps
 * the less-common actions (quote/branch/pin/export/inspect receipt…).
 *
 * - Copy: immediate clipboard write, icon morphs to a tick ~1.2s.
 * - Read Aloud: speaks THIS bubble's text via the canonical TTS engine;
 *   while active the icon becomes a Stop square — tap again stops.
 *   Starting another bubble's playback stops the previous one (barge-in
 *   handled by the engine's QUEUE_FLUSH).
 * - Retry: disabled while the turn is streaming or a generation is in
 *   flight. Regenerates from the paired user turn's content, never a
 *   global "resend latest".
 * - Share: native Android share sheet with the plain text.
 * - reducedMotion=true skips the tick scale pop.
 *
 * Layout: single compact Row. At narrow widths it stays one row (5 × 32dp
 * icons + dividers ≈ 190dp — fits the smallest phone stage comfortably).
 */
@Composable
fun MessageActionBar(
  text: String,
  isAssistant: Boolean,
  isStreaming: Boolean,
  isSpeaking: Boolean,
  onReadAloud: () -> Unit,
  onStopAloud: () -> Unit,
  onRetry: () -> Unit,
  onMore: () -> Unit,
  onEdit: (() -> Unit)? = null,
  reducedMotion: Boolean = false,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var copied by remember { mutableStateOf(false) }

  val copyScale by animateFloatAsState(
    targetValue = if (copied && !reducedMotion) 1.25f else 1f,
    animationSpec = spring(stiffness = Spring.StiffnessHigh),
    label = "copyTickScale"
  )

  // Speaking pulse — subtle 1.0→1.15 breathing scale on the Stop icon while
  // this bubble's audio is live. Skipped under reduced motion.
  val pulse = if (isSpeaking && !reducedMotion) {
    val transition = rememberInfiniteTransition(label = "speakPulse")
    transition.animateFloat(
      initialValue = 1f,
      targetValue = 1.15f,
      animationSpec = infiniteRepeatable(
        animation = tween(420),
        repeatMode = RepeatMode.Reverse
      ),
      label = "speakPulseScale"
    ).value
  } else {
    1f
  }

  Surface(
    modifier = modifier,
    color = PurpSurfaceCard,
    shape = RoundedCornerShape(10.dp),
    border = BorderStroke(1.dp, PurpBorder.copy(alpha = 0.5f))
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 2.dp, vertical = 0.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // COPY — immediate clipboard write; tick morphs on success.
      BarIcon(
        icon = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
        label = if (copied) "Copied" else "Copy",
        tint = if (copied) EmeraldOnline else TextSecondary,
        scale = copyScale,
        enabled = text.isNotBlank(),
        onClick = {
          val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("PurpClaw message", text))
          copied = true
          scope.launch {
            delay(1200)
            copied = false
          }
        }
      )

      BarDivider()

      // READ ALOUD / STOP — speaks this bubble; tap again stops.
      if (isSpeaking) {
        BarIcon(
          icon = Icons.Default.Stop,
          label = "Stop reading",
          tint = EmeraldOnline,
          scale = pulse,
          onClick = onStopAloud
        )
      } else {
        BarIcon(
          icon = Icons.Default.VolumeUp,
          label = "Read aloud",
          tint = if (text.isNotBlank()) CyanNeon else TextMuted,
          enabled = text.isNotBlank(),
          onClick = onReadAloud
        )
      }

      // EDIT — user bubbles only; loads this message into the composer.
      if (!isAssistant && onEdit != null) {
        BarDivider()
        BarIcon(
          icon = Icons.Default.Edit,
          label = "Edit",
          tint = TextSecondary,
          onClick = onEdit
        )
      }

      BarDivider()

      // RETRY — assistant turns only, blocked while streaming.
      if (isAssistant) {
        BarIcon(
          icon = Icons.Default.Refresh,
          label = "Retry",
          tint = if (isStreaming) TextMuted else CyanNeon,
          enabled = !isStreaming,
          onClick = onRetry
        )
        BarDivider()
      }

      // SHARE — native share sheet.
      BarIcon(
        icon = Icons.Default.Share,
        label = "Share",
        tint = PurpNeon,
        enabled = text.isNotBlank(),
        onClick = {
          val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
          }
          context.startActivity(Intent.createChooser(send, "Share message"))
        }
      )

      BarDivider()

      // MORE — the ONLY path to the secondary bottom sheet.
      BarIcon(
        icon = Icons.Default.MoreHoriz,
        label = "More actions",
        tint = TextSecondary,
        onClick = onMore
      )
    }
  }
}

@Composable
private fun BarDivider() {
  VerticalDivider(
    modifier = Modifier.height(18.dp).padding(horizontal = 1.dp),
    color = PurpBorder.copy(alpha = 0.4f),
    thickness = 0.5.dp
  )
}

@Composable
private fun BarIcon(
  icon: ImageVector,
  label: String,
  tint: Color,
  enabled: Boolean = true,
  scale: Float = 1f,
  onClick: () -> Unit
) {
  IconButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier
      .size(32.dp)
      .scale(scale)
      .semantics { contentDescription = label }
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = if (enabled) tint else TextMuted.copy(alpha = 0.4f),
      modifier = Modifier.size(17.dp)
    )
  }
}
