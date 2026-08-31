package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.network.HomeRuntimeBridge
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Per-bubble action rail — MOBILE renderer (bottom sheet).
 *
 * Spec: docs/PURPCLAW_ACTION_RAIL_RENDERERS_SPEC.md
 *   - Long-press a bubble → numbered action sheet.
 *   - Rows come from GET /api/message-action?surface=mobile (AR.list).
 *   - Tap → POST /api/message-action (AR.dispatch) — never bridge modules.
 *   - Gated rows render disabled with their gate reason.
 *
 * @param onResult pretty outcome line pushed into the conversation log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
  bubbleId: String,
  sessionId: String,
  bubbleRole: String,
  bubbleText: String,
  onDismiss: () -> Unit,
  onResult: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var rows by remember { mutableStateOf<List<HomeRuntimeBridge.MessageActionRow>>(emptyList()) }
  var loaded by remember { mutableStateOf(false) }
  var firing by remember { mutableStateOf(false) }

  LaunchedEffect(bubbleId) {
    // Message actions are supplied by Home. The sheet is modal, so its
    // network lookup must never be allowed to hold the entire phone UI
    // hostage when Home is offline or slow.
    rows = withTimeoutOrNull(2_500L) {
      HomeRuntimeBridge.listMessageActions(bubbleRole = bubbleRole)
    }.orEmpty()
    loaded = true
  }

  // An empty modal still owns the whole Compose input layer. When Home is
  // offline there are legitimately no remote message actions, so leaving the
  // sheet open makes the composer and navigation appear completely dead.
  // Dismiss as soon as the authoritative lookup completes with no rows.
  LaunchedEffect(loaded, rows) {
    if (loaded && rows.isEmpty()) onDismiss()
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = PurpSurfaceElevated,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp)
        .padding(bottom = 22.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          ":$bubbleId — actions",
          fontFamily = FontFamily.Monospace,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          color = TextPrimary,
        )
      }
      Spacer(Modifier.height(10.dp))
      when {
        !loaded -> Text(
          "loading actions…",
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          color = TextMuted,
        )
        rows.isEmpty() -> Text(
          "(no actions available)",
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          color = TextMuted,
        )
        else -> LazyColumn(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          items(rows, key = { it.action }) { row ->
            val idx = rows.indexOf(row) + 1
            val enabled = !row.gated && !firing
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) {
                  firing = true
                  scope.launch {
                    val r = HomeRuntimeBridge.dispatchMessageAction(
                      action = row.action,
                      bubbleId = bubbleId,
                      sessionId = sessionId,
                      bubbleRole = bubbleRole,
                      bubbleText = bubbleText,
                    )
                    onResult(formatActionResult(row.action, r))
                    firing = false
                    onDismiss()
                  }
                }
                .padding(vertical = 10.dp, horizontal = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                "$idx.",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = PurpNeon,
                modifier = Modifier.width(28.dp),
              )
              Column {
                Text(
                  row.label,
                  fontFamily = FontFamily.Monospace,
                  fontSize = 13.sp,
                  color = if (row.gated) TextMuted else TextPrimary,
                )
                if (row.gated) {
                  Text(
                    "gated: ${row.gateReason ?: "unavailable"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextMuted,
                  )
                }
              }
            }
            HorizontalDivider(color = TextMuted.copy(alpha = 0.12f))
          }
        }
      }
    }
  }
}

/** Pretty-printer mirroring lib/commands/act.js _formatHint. */
fun formatActionResult(action: String, r: HomeRuntimeBridge.MessageActionResult): String {
  if (!r.ok) return "/act: $action denied — ${r.errorReason ?: "unknown"}"
  val hint = r.hint
  return when {
    r.path != null -> "$action → exported to ${r.path}"
    hint == "fire-regenerate" -> "$action → regenerating ${bubbleTag(r.newBubbleId)}"
    hint == "tts-playing" -> "$action → reading aloud"
    hint == "tts-stopped" -> "$action → stopped"
    hint == "open-thread" -> "$action → opened thread"
    hint == "pin-toggle" -> "$action → pin: ${if (r.pinned == true) "on" else "off"}"
    hint == "branched" -> "$action → branched to ${r.newSessionId ?: ""}"
    else -> "$action → done"
  }
}

private fun bubbleTag(id: String?): String = id ?: ""
