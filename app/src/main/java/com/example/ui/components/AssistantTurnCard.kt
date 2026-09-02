package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.CompanionPetState
import com.example.core.model.ModelMode
import com.example.core.model.RoutingReceipt
import com.example.core.model.ToolAffinity
import com.example.core.model.ToolCallRecord
import com.example.core.model.TurnRecord
import com.example.ui.theme.AmberHybrid
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.CyanBorder



import com.example.ui.theme.CyanNeonDark
import com.example.ui.theme.PurpSurfaceCard
import com.example.ui.theme.PurpSurfaceElevated
import com.example.ui.theme.RoseOffline

import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/** Blank text is only a terminal failure after the live turn has ended. */
internal fun assistantVisibleText(
  content: String,
  isStreaming: Boolean,
  activityStatus: String?
): String = when {
  content.isNotBlank() -> content
  isStreaming -> activityStatus?.takeIf { it.isNotBlank() } ?: "Working…"
  else -> "No response received · Retry"
}

@Composable
fun TurnView(
  turn: TurnRecord,
  selectedCompanion: String,
  runtimePetState: CompanionPetState? = null,
  modifier: Modifier = Modifier,
  onLongPress: (() -> Unit)? = null,
  onReadAloud: ((TurnRecord) -> Unit)? = null,
  onStopAloud: (() -> Unit)? = null,
  onRetryTurn: ((TurnRecord) -> Unit)? = null,
  onEditTurn: ((TurnRecord) -> Unit)? = null,
  speakingTurnId: String? = null,
  onLessonAction: ((com.example.core.runtime.lesson.LessonAction) -> Unit)? = null
) {
  if (turn.role == "user") {
    UserTurnCard(
      turn = turn, modifier = modifier, onLongPress = onLongPress,
      onReadAloud = onReadAloud, onStopAloud = onStopAloud, speakingTurnId = speakingTurnId,
      onRetryTurn = onRetryTurn, onEditTurn = onEditTurn
    )
  } else {
    AssistantCanvasTurn(
      turn = turn, selectedCompanion = selectedCompanion, runtimePetState = runtimePetState, modifier = modifier,
      onLongPress = onLongPress, onReadAloud = onReadAloud, onStopAloud = onStopAloud,
      onRetryTurn = onRetryTurn, speakingTurnId = speakingTurnId,
      onLessonAction = onLessonAction
    )
  }
}

/**
 * Canonical lean assistant renderer: prose on canvas, then provenance and
 * actions. This is deliberately independent of the legacy expandable card
 * below, whose nested layout could measure a persisted reply as an invisible
 * region on the physical S25.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantCanvasTurn(
  turn: TurnRecord,
  selectedCompanion: String,
  runtimePetState: CompanionPetState? = null,
  modifier: Modifier = Modifier,
  onLongPress: (() -> Unit)? = null,
  onReadAloud: ((TurnRecord) -> Unit)? = null,
  onStopAloud: (() -> Unit)? = null,
  onRetryTurn: ((TurnRecord) -> Unit)? = null,
  speakingTurnId: String? = null,
  onLessonAction: ((com.example.core.runtime.lesson.LessonAction) -> Unit)? = null
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
      .combinedClickable(onClick = { onLongPress?.invoke() }, onLongClick = onLongPress)
      .testTag("assistant_canvas_turn")
  ) {
    Row(verticalAlignment = Alignment.Top) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = assistantVisibleText(turn.content, turn.isStreaming, turn.activityStatus),
          fontSize = 10.sp,
          lineHeight = 14.sp,
          color = Color(0xFFF8F5FF)
        )
        Spacer(Modifier.height(5.dp))
        Text(
          text = turn.providerModel.ifBlank { "PurpClaw · mobile" },
          fontSize = 8.5.sp,
          fontFamily = FontFamily.Monospace,
          color = CyanAccent
        )
      }
      Spacer(Modifier.width(8.dp))
      // PurpReaper shows its 2D state sprite; other companions use the 3D-rendered avatar.
      if (selectedCompanion == "PurpReaper") {
        PurpReaperAvatar(
          companionState = if (turn.isStreaming) runtimePetState ?: CompanionPetState.THINKING else CompanionPetState.IDLE,
          isSelected = true,
          size = 32.dp
        )
      } else {
        PurpAngolinAvatar(
          mood = if (turn.isStreaming) petStateToMood(runtimePetState ?: CompanionPetState.THINKING) else PurpAngolinMood.IDLE,
          size = 32.dp,
          showGlow = turn.isStreaming
        )
      }
    }
    Spacer(Modifier.height(4.dp))
    MessageActionBar(
      text = turn.content,
      isAssistant = true,
      isStreaming = turn.isStreaming,
      isSpeaking = speakingTurnId == turn.id,
      onReadAloud = { onReadAloud?.invoke(turn) },
      onStopAloud = { onStopAloud?.invoke() },
      onRetry = { onRetryTurn?.invoke(turn) },
      onMore = { onLongPress?.invoke() }
    )
    Text(
      text = "$selectedCompanion · canonical",
      fontSize = 8.5.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      color = TextMuted,
      modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
    // LESSON TOOL: interactive lesson card projected from the live lesson engine
    turn.lessonCard?.let { card ->
      ChatLessonCard(
        card = card,
        onAction = { action -> onLessonAction?.invoke(action) },
        modifier = Modifier.padding(top = 6.dp)
      )
    }
  }
}

/** One semantic bridge: canonical runtime state drives every companion pack. */
internal fun petStateToMood(state: CompanionPetState): PurpAngolinMood = when (state) {
  // ── Idle ────────────────────────────────────────────────────────────────────
  CompanionPetState.IDLE -> PurpAngolinMood.IDLE
  // ── Voice / audio ───────────────────────────────────────────────────────────
  CompanionPetState.LISTENING, CompanionPetState.TRANSCRIBING -> PurpAngolinMood.LISTENING
  CompanionPetState.SPEAKING, CompanionPetState.REPLYING, CompanionPetState.WRITING -> PurpAngolinMood.SPEAKING
  // ── Reasoning ────────────────────────────────────────────────────────────────
  CompanionPetState.THINKING, CompanionPetState.PLANNING,
  CompanionPetState.SEARCHING, CompanionPetState.VERIFYING -> PurpAngolinMood.THINKING
  // ── Tool / execution ────────────────────────────────────────────────────────
  CompanionPetState.TOOL_CALL, CompanionPetState.RUNNING, CompanionPetState.CODING,
  CompanionPetState.SAVED -> PurpAngolinMood.ACTIVE
  // ── Repair / recovery ───────────────────────────────────────────────────────
  CompanionPetState.FIXING, CompanionPetState.RECOVERING,
  CompanionPetState.RETRY, CompanionPetState.WARNING -> PurpAngolinMood.DEGRADED
  // ── Output / completion ─────────────────────────────────────────────────────
  CompanionPetState.SUCCESS, CompanionPetState.VERIFIED -> PurpAngolinMood.FLOURISH
  // ── Terminal / error ────────────────────────────────────────────────────────
  CompanionPetState.ERROR, CompanionPetState.FAILED,
  CompanionPetState.CANCELLED -> PurpAngolinMood.FAILED
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserTurnCard(
  turn: TurnRecord,
  modifier: Modifier = Modifier,
  onLongPress: (() -> Unit)? = null,
  onReadAloud: ((TurnRecord) -> Unit)? = null,
  onStopAloud: (() -> Unit)? = null,
  speakingTurnId: String? = null,
  onRetryTurn: ((TurnRecord) -> Unit)? = null,
  onEditTurn: ((TurnRecord) -> Unit)? = null
) {
  // ROW OWNS LAYOUT: [user avatar] [gap] [message card]
  // Avatar is a sibling, never a child of the card.
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.Start,
    verticalAlignment = Alignment.Top
  ) {
    // User avatar — outside the bubble
    Box(
      modifier = Modifier
        .size(38.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(
          Brush.linearGradient(listOf(CyanAccent.copy(alpha = 0.7f), PurpSurfaceElevated))
        )
        .border(1.dp, CyanAccent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
      contentAlignment = Alignment.Center
    ) {
      Text("YOU", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.White)
    }

    Spacer(modifier = Modifier.width(10.dp))

    Column(modifier = Modifier.weight(1f)) {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .combinedClickable(onClick = { onLongPress?.invoke() }, onLongClick = onLongPress)
          .testTag("user_turn_card"),
        colors = CardDefaults.cardColors(containerColor = CyanNeon),
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          if (turn.fullSystemScope || turn.mode != com.example.core.model.InteractionMode.CHAT) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "OPERATOR [${turn.mode.label}]",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.8f)
              )
              if (turn.fullSystemScope) {
                Text(
                  text = "FULL SYS",
                  fontSize = 8.5.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                  color = CyanAccent
                )
              }
            }
            Spacer(modifier = Modifier.height(4.dp))
          }
          Text(
            text = turn.content,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = Color.White
          )
          // Inline media attached to an operator turn.
          if (turn.mediaAttachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            MediaAttachmentsRow(attachments = turn.mediaAttachments)
          }
        }
      }
      Spacer(modifier = Modifier.height(4.dp))
      // INLINE ACTION RAIL (2026-08-27 law): primary actions live directly
      // under the bubble — no bottom-sheet detour. More → secondary sheet.
      MessageActionBar(
        text = turn.content,
        isAssistant = false,
        isStreaming = false,
        isSpeaking = speakingTurnId == turn.id,
        onReadAloud = { onReadAloud?.invoke(turn) },
        onStopAloud = { onStopAloud?.invoke() },
        onRetry = { onRetryTurn?.invoke(turn) },
        onEdit = onEditTurn?.let { cb -> { cb(turn) } },
        onMore = { onLongPress?.invoke() }
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "YOU · MOBILE NODE",
        fontSize = 8.5.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = TextMuted,
        modifier = Modifier.padding(start = 4.dp)
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantTurnCard(
  turn: TurnRecord,
  selectedCompanion: String,
  modifier: Modifier = Modifier,
  onLongPress: (() -> Unit)? = null,
  onReadAloud: ((TurnRecord) -> Unit)? = null,
  onStopAloud: (() -> Unit)? = null,
  onRetryTurn: ((TurnRecord) -> Unit)? = null,
  speakingTurnId: String? = null
) {
  // Live activity defaults to expanded while streaming, collapsed when done.
  var showActivity by remember(turn.id) { mutableStateOf(turn.isStreaming) }
  var showRoutingReceipt by remember { mutableStateOf(false) }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    horizontalAlignment = Alignment.Start
  ) {
    // ROW OWNS LAYOUT: [assistant card] [gap] [PurpAngolin avatar]
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.Start,
      verticalAlignment = Alignment.Top
    ) {
      // LANE #95 (2026-08-28): AI reply renders directly on the chat canvas —
      // no enclosing rectangle, no card background, no border. The content
      // sits flush against the LazyColumn padding so the reply reads like the
      // reference OpenPurp/white-screenshot layout. Long answers, markdown,
      // tools, images, agent output all flow at full available width.
      Column(
        modifier = Modifier
          .weight(1f)
          .animateContentSize()
          .combinedClickable(onClick = { onLongPress?.invoke() }, onLongClick = onLongPress)
          .testTag("assistant_turn_card")
      ) {

          // 1. REPLY BODY FIRST — AI replies take the space given to them so
          //    users can read properly. STATUS BOX LAW (operator, 2026-09-02):
          //    thinking/reasoning lives INSIDE the status box below — with the
          //    token burn and kaomoji faces — never as a fake reply line in
          //    the chat body.

          // Reply body (clean formatted answer, full width)
          if (turn.content.isNotBlank()) {
            Text(
              text = turn.content,
              fontSize = 9.5.sp,
              lineHeight = 13.sp,
              // The chat canvas is dark in the canonical shell. Reply copy
              // gets an explicit high-contrast foreground so accent/theme
              // inheritance can never produce black-on-black assistant text.
              color = Color(0xFFF8F5FF)
            )
          }

          // 1b. Inline media — images, borderless video player, file chips.
          if (turn.mediaAttachments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            MediaAttachmentsRow(attachments = turn.mediaAttachments)
          }

          // 2. STATUS BOX BELOW THE REPLY — one tidy block holding everything:
          //    thinking/reasoning (collapsible), tool calls, tokens, latency,
          //    model + route. Neatly stacked, never thrown on. It renders from
          //    the first streamed token through completion, so THINKING and
          //    WORKING phases are always visible in the box — never two
          //    thinking states, never thinking leaked into the chat body.
          val hasActivity = turn.isStreaming || !turn.reasoning.isNullOrBlank() || turn.toolCalls.isNotEmpty()
          if (hasActivity) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { showActivity = !showActivity }
                .testTag("activity_card"),
              color = if (turn.isStreaming) PurpSurfaceElevated.copy(alpha = 0.55f) else PurpSurfaceElevated,
              border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (turn.isStreaming) CyanNeon.copy(alpha = 0.6f) else CyanBorder
              )
            ) {
              Column(modifier = Modifier.padding(9.dp)) {
                // Kaomoji face law: decoration only, machine status stays canonical.
                // Streaming turns carry the animated thinking face in the header.
                val streamFace = kaomojiFrames("thinking_face").ifEmpty { listOf("(._.)") }
                var streamFaceIdx by remember { mutableStateOf(0) }
                LaunchedEffect(streamFace.size) {
                  while (true) {
                    kotlinx.coroutines.delay(420)
                    streamFaceIdx = (streamFaceIdx + 1) % streamFace.size.coerceAtLeast(1)
                  }
                }
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                      imageVector = Icons.Default.Psychology,
                      contentDescription = null,
                      tint = if (turn.isStreaming) CyanNeon else CyanNeon,
                      modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                      text = when {
                        turn.isStreaming && turn.toolCalls.isEmpty() -> "${streamFace[streamFaceIdx]} THINKING…"
                        turn.isStreaming -> "WORKING · ${turn.toolCalls.size} calls"
                        turn.toolCalls.isNotEmpty() -> "ACTIVITY · ${turn.toolCalls.size} calls"
                        else -> "REASONING"
                      },
                      fontSize = 9.5.sp,
                      fontFamily = FontFamily.Monospace,
                      fontWeight = FontWeight.Bold,
                      letterSpacing = 0.8.sp,
                      color = if (turn.isStreaming) CyanNeon else CyanNeon
                    )
                  }
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    // Tokens used — always visible in the header when known
                    if (turn.tokenCount > 0) {
                      Text(
                        text = "${turn.tokenCount} tok",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        modifier = Modifier.padding(end = 6.dp)
                      )
                    }
                    Icon(
                      imageVector = if (showActivity) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                      contentDescription = null,
                      tint = TextMuted,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }

                AnimatedVisibility(visible = showActivity) {
                  Column(modifier = Modifier.padding(top = 7.dp)) {
                    // Thinking / reasoning stream — visible here (collapsed by
                    // default) in every phase. The status box is the ONLY place
                    // thinking renders; the chat body never duplicates it.
                    if (!turn.reasoning.isNullOrBlank()) {
                      Text(
                        text = "REASONING",
                        fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, color = TextMuted, letterSpacing = 1.sp
                      )
                      Spacer(modifier = Modifier.height(3.dp))
                      Text(
                        text = turn.reasoning ?: "",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                        color = CyanNeon
                      )
                    }
                    // Tool call ledger inside the same status box
                    if (turn.toolCalls.isNotEmpty()) {
                      if (!turn.reasoning.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(7.dp))
                        HorizontalDivider(color = CyanBorder, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(5.dp))
                      }
                      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        turn.toolCalls.forEach { call ->
                          ToolCallLedgerItem(call = call)
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }

        // 3. Per-Turn Routing Receipt (Collapsible)
        if (turn.routingReceipt != null) {
          Spacer(modifier = Modifier.height(8.dp))
          RoutingReceiptCard(
            receipt = turn.routingReceipt,
            isExpanded = showRoutingReceipt,
            onToggle = { showRoutingReceipt = !showRoutingReceipt }
          )
        }

        // 5. Telemetry Footer
        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = CyanBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          // ROUTE TRUTH LAW: prominent route line from the completed turn's
          // receipt (mode → resolved model); fallback secondary; legacy turns
          // without receipts fall back to raw providerModel text.
          Column {
            val receipt = turn.routingReceipt
            if (receipt != null) {
              Text(
                text = when (receipt.modelMode) {
                  ModelMode.MANUAL -> "MANUAL · ${receipt.resolvedModel}"
                  else -> "AUTO → ${receipt.resolvedModel}"
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = CyanAccent
              )
              val fallback = receipt.fallbackPath.lastOrNull()
              if (!fallback.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                  text = "Fallback: $fallback",
                  fontSize = 8.sp,
                  fontFamily = FontFamily.Monospace,
                  color = TextMuted
                )
              }
            } else {
              Text(
                text = turn.providerModel,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted
              )
            }
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            if (turn.latencyMs > 0) {
              Text(
                text = "${turn.latencyMs}ms",
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                color = EmeraldOnline
              )
            }
            if (turn.proofReceipt != null) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  Icons.Default.VerifiedUser,
                  contentDescription = "Verified Receipt",
                  tint = EmeraldOnline,
                  modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                  text = "PROOF",
                  fontSize = 8.5.sp,
                  fontFamily = FontFamily.Monospace,
                  color = EmeraldOnline
                )
              }
            }
          }
        }

      Spacer(modifier = Modifier.width(10.dp))

      // Canonical illustrated companion badge replaces the placeholder initial.
      PurpAngolinAvatar(
        mood = if (turn.isStreaming) PurpAngolinMood.THINKING else PurpAngolinMood.IDLE,
        size = 38.dp,
        showGlow = turn.isStreaming,
        modifier = Modifier
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, CyanNeon.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    // INLINE ACTION RAIL (2026-08-27 law): Copy / Read Aloud / Retry / Share
    // directly under every assistant bubble. "More" is the ONLY path to the
    // secondary bottom sheet. Retry blocked while the turn is streaming.
    MessageActionBar(
      text = turn.content,
      isAssistant = true,
      isStreaming = turn.isStreaming,
      isSpeaking = speakingTurnId == turn.id,
      onReadAloud = { onReadAloud?.invoke(turn) },
      onStopAloud = { onStopAloud?.invoke() },
      onRetry = { onRetryTurn?.invoke(turn) },
      onMore = { onLongPress?.invoke() }
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = "$selectedCompanion · ${if (turn.isStreaming) "working" else "canonical"}",
      fontSize = 8.5.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold,
      color = TextMuted,
      modifier = Modifier.padding(start = 4.dp)
    )
  }
}

@Composable
fun ToolCallLedgerItem(
  call: ToolCallRecord,
  modifier: Modifier = Modifier
) {
  val affinityColor = when (call.affinity) {
    ToolAffinity.ANDROID_NATIVE -> EmeraldOnline
    ToolAffinity.PORTABLE -> CyanAccent
    ToolAffinity.REMOTE_BRIDGE -> AmberHybrid
    ToolAffinity.UNAVAILABLE_ANDROID -> RoseOffline
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = PurpSurfaceElevated,
    shape = RoundedCornerShape(6.dp),
    border = androidx.compose.foundation.BorderStroke(0.5.dp, CyanBorder)
  ) {
    Column(modifier = Modifier.padding(6.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f, fill = false)
        ) {
          Icon(
            imageVector = Icons.Default.Terminal,
            contentDescription = null,
            tint = affinityColor,
            modifier = Modifier.size(12.dp)
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = call.toolName,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = call.affinity.name,
          fontSize = 7.5.sp,
          fontFamily = FontFamily.Monospace,
          color = affinityColor,
          maxLines = 1,
          modifier = Modifier
            .background(affinityColor.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp)
        )
      }
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = call.output,
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        color = TextSecondary
      )
    }
  }
}

@Composable
fun RoutingReceiptCard(
  receipt: RoutingReceipt,
  isExpanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .clickable { onToggle() }
      .testTag("routing_receipt_card"),
    color = PurpSurfaceElevated,
    border = androidx.compose.foundation.BorderStroke(1.dp, CyanBorder)
  ) {
    Column(modifier = Modifier.padding(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          Text(
            text = "ROUTING RECEIPT",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = CyanAccent
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            // PIN TRUTH LAW: the chip must prove requested → resolved → served.
            // A chip showing only mode/profile is UI theatre — AUTO failover or a
            // pin miss can serve a different model than the one requested.
            text = buildString {
              append(receipt.modelMode.name)
              append(" · ").append(receipt.routingProfile.name)
              if (receipt.requestedModel != "AUTO") {
                append(" · PIN ")
                if (receipt.requestedProvider != "AUTO") append(receipt.requestedProvider).append("/")
                append(receipt.requestedModel)
              }
              append(" · ").append(receipt.resolvedProvider).append("/").append(receipt.resolvedModel)
              if (!receipt.servedModel.isNullOrBlank() && receipt.servedModel != receipt.resolvedModel) {
                append(" · SERVED ").append(receipt.servedProvider).append("/").append(receipt.servedModel)
              }
            },
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = TextMuted
          )
        }
        Icon(
          imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = if (isExpanded) "Collapse" else "Expand",
          tint = TextMuted,
          modifier = Modifier.size(14.dp)
        )
      }

      Spacer(modifier = Modifier.height(2.dp))

      // Compact summary line
      if (receipt.isHomeAttached) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "Home Resolve: ",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary
          )
          Text(
            text = "Home PC · ${receipt.homeResolvedModel ?: receipt.resolvedModel}",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = EmeraldOnline
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "[Phone displays Home state]",
            fontSize = 7.5.sp,
            fontFamily = FontFamily.Monospace,
            color = TextMuted
          )
        }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "Resolved: ",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary
          )
          Text(
            text = "${receipt.resolvedProvider} · ${receipt.resolvedModel}",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = CyanNeon
          )
        }
      }

      // Expanded Audit Details
      AnimatedVisibility(visible = isExpanded) {
        Column(modifier = Modifier.padding(top = 6.dp)) {
          HorizontalDivider(color = CyanBorder, thickness = 0.5.dp)
          Spacer(modifier = Modifier.height(4.dp))

          Text(
            text = "REASON: ${receipt.routingReason}",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = CyanNeon
          )

          if (receipt.fallbackPath.isNotEmpty()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
              text = "FALLBACK & CANDIDATE EVALUATION:",
              fontSize = 8.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = TextMuted
            )
            receipt.fallbackPath.forEach { step ->
              Text(
                text = "  • $step",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = if (step.contains("ACCEPTED")) EmeraldOnline else if (step.contains("FAILED") || step.contains("Excluded")) RoseOffline else TextSecondary
              )
            }
          }

          Spacer(modifier = Modifier.height(3.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text(
              text = "AFFINITY: ${receipt.sessionAffinityBefore ?: "none"} → ${receipt.sessionAffinityAfter ?: "none"}",
              fontSize = 8.sp,
              fontFamily = FontFamily.Monospace,
              color = TextMuted
            )
            Text(
              text = "GATE: ${receipt.qualityGateResult}",
              fontSize = 8.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = if (receipt.qualityGateResult.startsWith("PASS")) EmeraldOnline else RoseOffline
            )
          }
        }
      }
    }
  }
}

