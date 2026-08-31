package com.example.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.core.model.ExecutionLease
import com.example.core.model.InteractionMode
import com.example.core.model.MediaAttachment
import com.example.core.model.TurnRecord
import com.example.core.runtime.ConversationMode
import com.example.ui.components.VoiceVisualizerOverlay
import com.example.core.runtime.VoiceMode
import com.example.ui.components.DualViewBrowser
import com.example.ui.components.TurnView
import coil.compose.AsyncImage
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.LeaseActiveGold
import com.example.ui.theme.PurpBorder
import com.example.ui.theme.PurpDeep
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpPrimary
import com.example.ui.theme.PurpSurface
import com.example.ui.theme.PurpSurfaceCard
import com.example.ui.theme.PurpSurfaceElevated
import com.example.ui.theme.PurpVoid
import com.example.ui.theme.RoseOffline
import com.example.ui.theme.TextHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

/**
 * COMMAND — chat is the hero. Chrome reduced to: nothing above, one-row
 * composer below with a [+] intake sheet. Target ≥72% of viewport for chat.
 */
@Composable
fun CommandScreen(
  turns: List<TurnRecord>,
  inputText: String,
  pendingMediaAttachments: List<MediaAttachment> = emptyList(),
  interactionMode: InteractionMode,
  selectedCompanion: String,
  isGenerating: Boolean,
  isListening: Boolean,
  voiceMode: VoiceMode = VoiceMode.OFF,
  voiceInputLevel: Float = 0f,
  voiceOutputLevel: Float = 0f,
  voiceConversationMode: ConversationMode = ConversationMode.VOICE_INOUT,
  liveTurn: com.example.core.model.TurnRecord?,
  liveStatus: String = "",
  tokenBurnCents: Double = 0.0,
  activeLease: ExecutionLease?,
  selectedModel: String,
  onInputChanged: (String) -> Unit,
  onSendMessage: () -> Unit,
  onCancelTurn: () -> Unit = {},
  onArmLease: () -> Unit,
  onRevokeLease: () -> Unit,
  onVoiceTrigger: () -> Unit,
  onPodcastPushToTalkStart: () -> Unit = {},
  onPodcastPushToTalkEnd: () -> Unit = {},
  onCameraTrigger: () -> Unit,
  onIntakeTrigger: () -> Unit,
  onModeChanged: (InteractionMode) -> Unit = {},
  onCompanionTrigger: () -> Unit = {},
  onCycleVoiceMode: () -> Unit = {},   // deprecated: kept for ABI compat, never invoked (mic is tap-only)
  dualViewUrl: String? = null,
  onDualViewClose: () -> Unit = {},
  modelChoices: List<Pair<String, String>> = emptyList(),
  onSelectModel: (String) -> Unit = {},
  // TASK #54/#55/#56: podcast UI surface
  isPodcastActive: Boolean = false,
  latestSavedEpisode: com.example.core.runtime.CouncilPodcastEngine.SavedEpisode? = null,
  podcastBreak: com.example.core.runtime.CouncilPodcastEngine.BreakState? = null,
  onEnterPodcastBreak: () -> Unit = {},
  onExitPodcastBreak: () -> Unit = {},
  onDismissSavedEpisode: () -> Unit = {},
  onOpenSavedEpisode: (com.example.core.runtime.CouncilPodcastEngine.SavedEpisode) -> Unit = {},
  // INLINE ACTION RAIL (2026-08-27): per-bubble primary actions.
  speakingTurnId: String? = null,
  onReadAloud: (com.example.core.model.TurnRecord) -> Unit = {},
  onStopAloud: () -> Unit = {},
  onRetryTurn: (com.example.core.model.TurnRecord) -> Unit = {},
  // EDIT loads this user turn's content back into the composer for resending.
  onEditTurn: (com.example.core.model.TurnRecord) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val listState = rememberLazyListState()
  var showIntakeSheet by remember { mutableStateOf(false) }
  var showModelFlyout by remember { mutableStateOf(false) }
  var dualViewCollapsed by remember { mutableStateOf(false) }
  // Per-bubble action rail: long-press target + last dispatch receipt line.
  var actionSheetTurn by remember { mutableStateOf<com.example.core.model.TurnRecord?>(null) }
  var actionResultLine by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(turns.lastOrNull()?.id, liveTurn?.id) {
    if (turns.isNotEmpty()) {
      val targetIndex = when {
        liveTurn != null -> turns.indexOfLast { it.role == "user" }.takeIf { it >= 0 } ?: turns.lastIndex
        // Once the final assistant row is persisted, reveal that row itself.
        // Scrolling back to its parent user turn left the reply just below
        // the fold on tall bubbles, creating the false "no visible reply"
        // state even though Room contained the model output.
        else -> turns.lastIndex
      }
      listState.animateScrollToItem(targetIndex)
    }
  }

  // ===== TASK #63: overlay actor mood law =====
  // typing → LISTENING (holds sit + watches, never re-triggers the turn clip);
  // generating → THINKING; TTS speaking → SPEAKING; completion → FLOURISH
  // one-shot (~1.6s) then back to SIT idle. Same enum the 2D renderer and the
  // boot overlay consume — one contract across every surface.
  var flourishPulse by remember { mutableStateOf(false) }
  var failurePulse by remember { mutableStateOf(false) }
  var wasGenerating by remember { mutableStateOf(isGenerating) }
  val latestAssistantTurn = turns.lastOrNull { it.role == "assistant" }
  val parentRunActive = isGenerating || liveTurn != null || voiceMode in setOf(
    VoiceMode.THINKING,
    VoiceMode.SPEAKING,
    VoiceMode.TTS_DRAINING,
    VoiceMode.LISTENING,
    VoiceMode.TRANSCRIBING
  )
  val visibleTurns = if (parentRunActive) {
    turns.filterNot { it.id == latestAssistantTurn?.id && it.content.startsWith("No response received") }
  } else turns
  LaunchedEffect(isGenerating, latestAssistantTurn?.id) {
    if (wasGenerating && !isGenerating && turns.isNotEmpty()) {
      val failed = latestAssistantTurn?.content?.startsWith("No response received") == true
      if (failed) {
        failurePulse = true
        flourishPulse = false
        kotlinx.coroutines.delay(2400)
        failurePulse = false
      } else {
        flourishPulse = true
        failurePulse = false
        kotlinx.coroutines.delay(1600)
        flourishPulse = false
      }
    }
    wasGenerating = isGenerating
  }
  val overlayMood = when {
    failurePulse || voiceMode == VoiceMode.ERROR -> com.example.ui.components.PurpAngolinMood.FAILED
    flourishPulse -> com.example.ui.components.PurpAngolinMood.FLOURISH
    voiceMode == VoiceMode.SPEAKING -> com.example.ui.components.PurpAngolinMood.SPEAKING
    isGenerating && interactionMode == InteractionMode.WORK -> com.example.ui.components.PurpAngolinMood.ACTIVE
    isGenerating || voiceMode == VoiceMode.THINKING -> com.example.ui.components.PurpAngolinMood.THINKING
    voiceMode == VoiceMode.LISTENING || voiceMode == VoiceMode.TRANSCRIBING -> com.example.ui.components.PurpAngolinMood.LISTENING
    inputText.isNotBlank() -> com.example.ui.components.PurpAngolinMood.LISTENING
    else -> com.example.ui.components.PurpAngolinMood.IDLE
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(PurpVoid)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .imePadding()
    ) {
      // ===== DUAL VIEW BROWSER PANE — above conversation, collapsible =====
      dualViewUrl?.let { url ->
        DualViewBrowser(
          initialUrl = url,
          collapsed = dualViewCollapsed,
          onToggleCollapse = { dualViewCollapsed = !dualViewCollapsed },
          onClose = onDualViewClose,
          onStateChange = { /* browser state flows to turn receipts via tool layer */ },
          modifier = Modifier.fillMaxWidth()
        )
      }

      // ===== THE CONVERSATION — owns the screen =====
      LazyColumn(
        state = listState,
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 10.dp)
      ) {
        items(visibleTurns, key = { it.id }) { turn ->
          TurnView(turn = turn, selectedCompanion = selectedCompanion,
            onLongPress = { actionSheetTurn = turn },
            onReadAloud = onReadAloud,
            onStopAloud = onStopAloud,
            onRetryTurn = onRetryTurn,
            onEditTurn = onEditTurn,
            speakingTurnId = speakingTurnId)
        }

        // LIVE TURN — mounted immediately, streams status until final lands
        liveTurn?.let { lt ->
          item(key = lt.id) {
            TurnView(turn = lt, selectedCompanion = selectedCompanion,
              onLongPress = { actionSheetTurn = lt },
              onReadAloud = onReadAloud,
              onStopAloud = onStopAloud,
              onRetryTurn = onRetryTurn,
              onEditTurn = onEditTurn,
              speakingTurnId = speakingTurnId)
            // STEP 13.6 (2026-08-27): status row + token burn chip under the
            // live turn so the operator can see what the system is doing and
            // how much it cost. Previously this was a closure variable inside
            // processTurn and never published to the UI.
            if (liveStatus.isNotBlank()) {
              androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
              androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(start = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
              ) {
                androidx.compose.foundation.layout.Box(
                  Modifier
                    .size(6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(PurpNeon)
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Text(
                  liveStatus.uppercase(),
                  style = MaterialTheme.typography.labelSmall,
                  color = PurpNeon,
                  fontFamily = FontFamily.Monospace
                )
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                Text(
                  "${"%.2f".format(tokenBurnCents)}¢",
                  style = MaterialTheme.typography.labelSmall,
                  color = TextMuted,
                  fontFamily = FontFamily.Monospace
                )
              }
            }
          }
        }
        // ONE-ACTIVITY-SURFACE LAW: live turn card is the only execution/thinking
        // indicator. No separate "thinking" chip, no duplicate spinner.
      }

      // ===== PODCAST OVERLAY BAR — sits between chat and composer.
      // Two surfaces: (a) live podcast break-mode chip; (b) post-show
      // "Download Pod" banner that auto-fades after ~6s. =====

      // TASK #56: shit-stir break-mode chip
      podcastBreak?.let { br ->
        androidx.compose.foundation.layout.Row(
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(16.dp)
          )
          androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
          Text("SHIT-CHAIR", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Bold)
          androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
          Text(
            br.tangent.take(80) + (if (br.tangent.length > 80) "…" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
            maxLines = 1
          )
          Text(
            "${br.roundsRemaining}r",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp)
          )
          androidx.compose.material3.TextButton(onClick = onExitPodcastBreak) {
            Text("end break", style = MaterialTheme.typography.labelMedium)
          }
        }
      }

      // TASK #55: post-show Download Pod banner (auto-fades from MainViewModel).
      latestSavedEpisode?.let { sp ->
        androidx.compose.foundation.layout.Row(
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
            .clickable { onOpenSavedEpisode(sp) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            Icons.Default.CloudDownload,
            contentDescription = "Download podcast",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp)
          )
          androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
          androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(
              "Download Pod — ${sp.title}",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              maxLines = 1
            )
            Text(
              "${sp.turnCount} turns · ${sp.seatCount} seats · ~${(sp.estimatedDurationMs / 60_000)} min" +
                if (sp.breakUsed) " · break used" else "",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer
            )
          }
          androidx.compose.material3.TextButton(onClick = onDismissSavedEpisode) {
            Text("dismiss", style = MaterialTheme.typography.labelMedium)
          }
        }
      }

      // ===== ACTION-RAIL RECEIPT LINE — last /act dispatch outcome, auto-fades =====
      actionResultLine?.let { line ->
        androidx.compose.foundation.layout.Row(
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PurpSurfaceElevated.copy(alpha = 0.8f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            line,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            color = TextHighlight,
            maxLines = 2
          )
        }
        LaunchedEffect(line) {
          kotlinx.coroutines.delay(6000)
          if (actionResultLine == line) actionResultLine = null
        }
      }

      // ===== COMPOSER — cockpit-migrated: glowing rounded box on top,
      // toolbar of tiny mono pills below. Same functions, cockpit skin.
      // BLEND LAW: the composer band is transparent — it floats over the chat
      // void; only the textbox and buttons are visible, no slab behind them. =====
      // Composer controls always win Compose hit-testing even when the
      // movable avatar is deliberately parked over this visual region.
      Surface(
        modifier = Modifier.zIndex(20f),
        color = Color.Transparent,
        tonalElevation = 0.dp
      ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {

          if (pendingMediaAttachments.isNotEmpty()) {
            Surface(
              modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
              shape = RoundedCornerShape(14.dp),
              color = PurpSurfaceElevated,
              border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder.copy(alpha = 0.5f))
            ) {
              Column(Modifier.padding(8.dp)) {
                val photo = pendingMediaAttachments.first()
                AsyncImage(
                  model = photo.uri,
                  contentDescription = photo.label ?: "Attached camera photo",
                  contentScale = ContentScale.Fit,
                  modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(10.dp))
                )
                Text(
                  "ATTACHED · ${photo.width ?: "?"}×${photo.height ?: "?"} · ${photo.sha256?.take(12) ?: "verifying"}",
                  modifier = Modifier.padding(top = 5.dp),
                  fontSize = 9.sp,
                  fontFamily = FontFamily.Monospace,
                  color = CyanAccent
                )
              }
            }
          }

          // ── The Textbox — rounded-2xl, glows when non-empty ──
          val workSelected = interactionMode == InteractionMode.WORK
          val accent = if (workSelected) PurpNeon else PurpPrimary
          Box(
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(18.dp))
              .background(PurpSurfaceElevated)
              .border(
                1.dp,
                if (inputText.isNotBlank()) accent.copy(alpha = 0.55f) else PurpBorder.copy(alpha = 0.35f),
                RoundedCornerShape(18.dp)
              )
          ) {
            OutlinedTextField(
              value = inputText,
              onValueChange = onInputChanged,
              placeholder = {
                Text(
                  when {
                    voiceMode == VoiceMode.LISTENING &&
                      voiceConversationMode == ConversationMode.HANDS_FREE -> "Listening hands-free…"
                    voiceMode == VoiceMode.LISTENING -> "Listening…"
                    voiceMode == VoiceMode.SPEAKING -> "Speaking…"
                    voiceMode != VoiceMode.OFF -> voiceMode.label
                    workSelected -> "What should PurpClaw execute?"
                    else -> "Message PURPCLAW…"
                  },
                  fontSize = 12.sp, color = TextMuted.copy(alpha = 0.6f)
                )
              },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("command_input_field"),
              colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
              ),
              shape = RoundedCornerShape(18.dp),
              singleLine = false,
              maxLines = 4,
              textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 17.sp)
            )
          }

          Spacer(Modifier.height(6.dp))

          // ── Toolbar Row — rounded pills, labels centered ──
          // BLEND LAW: buttons wider, evenly spaced, almost touching (2dp gaps).
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
          ) {
            // CHAT | WORK sliding toggle pill
            Box(
              modifier = Modifier
                .width(118.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .clickable { onModeChanged(if (workSelected) InteractionMode.CHAT else InteractionMode.WORK) }
            ) {
              Box(
                Modifier
                  .fillMaxHeight()
                  .fillMaxWidth(0.5f)
                  .offset(x = if (workSelected) 59.dp else 0.dp)
                  .animateContentSize()
                  .background(accent.copy(alpha = 0.75f))
              )
              Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                  Text("CHAT", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (!workSelected) Color.White else TextMuted)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                  Text("WORK", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (workSelected) Color.White else TextMuted)
                }
              }
            }

            Spacer(Modifier.width(4.dp))

            // Model selector pill — cockpit clone: tap for flyout of AUTO + free models
            // weight(1f, fill=false): pill flexes/shrinks so paw+send never clip off-screen
            Box(Modifier.weight(1f, fill = false)) {
              Box(
                modifier = Modifier
                  .height(30.dp)
                  .clip(RoundedCornerShape(16.dp))
                  .border(0.75.dp, TextMuted.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                  .clickable { showModelFlyout = !showModelFlyout }
                  .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  if (selectedModel == "AUTO") "AUTO" else selectedModel.takeLastWhile { it != '/' }.let {
                    if (it.length > 14) it.take(13) + "…" else it
                  },
                  fontSize = 10.sp, fontWeight = FontWeight.Bold,
                  fontFamily = FontFamily.Monospace, color = TextMuted,
                  maxLines = 1,
                  overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
              }
              DropdownMenu(
                expanded = showModelFlyout,
                onDismissRequest = { showModelFlyout = false },
                containerColor = PurpSurfaceCard
              ) {
                Text(
                  "  MODEL ROUTER — pick stays locked",
                  fontSize = 9.sp, fontWeight = FontWeight.Bold,
                  fontFamily = FontFamily.Monospace, color = CyanAccent
                )
                modelChoices.forEach { (id, name) ->
                  DropdownMenuItem(
                    leadingIcon = {
                      if (id == selectedModel) Icon(Icons.Default.Check, "Selected",
                        tint = CyanAccent, modifier = Modifier.size(14.dp))
                    },
                    text = {
                      Column(Modifier.padding(vertical = 2.dp)) {
                        Text(
                          if (id == "AUTO") "AUTO — free router" else id.takeLastWhile { c -> c != '/' },
                          fontSize = 12.sp, fontWeight = FontWeight.Bold,
                          fontFamily = FontFamily.Monospace,
                          color = if (id == selectedModel) CyanAccent else TextPrimary
                        )
                        if (id != "AUTO") {
                          Text(name, fontSize = 9.sp, color = TextMuted, maxLines = 2)
                        }
                      }
                    },
                    onClick = { onSelectModel(id); showModelFlyout = false }
                  )
                }
                if (modelChoices.size <= 1) {
                  DropdownMenuItem(
                    text = { Text("Catalogue loading… pull to refresh in Settings", fontSize = 10.sp, color = TextMuted) },
                    onClick = {}
                  )
                }
              }
            }

            // [+] intake launcher — clean transparent square
            Box {
              Box(
                modifier = Modifier
                  .size(36.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .border(0.75.dp, TextMuted.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                  .clickable { showIntakeSheet = true },
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Default.Add, "Intake & vision",
                  tint = TextMuted.copy(alpha = 0.75f), modifier = Modifier.size(17.dp))
              }
              IntakeMenu(
                expanded = showIntakeSheet,
                onDismiss = { showIntakeSheet = false },
                onCamera = { showIntakeSheet = false; onCameraTrigger() },
                onZip = { showIntakeSheet = false; onIntakeTrigger() }
              )
            }

            // Mic — professional icon + tint convey the canonical voice state.
            val vm = voiceMode
            val (micIcon, micTint, micBg) = when (vm) {
              VoiceMode.OFF -> Triple(Icons.Default.Mic, TextMuted.copy(alpha = 0.75f), Color.Transparent)
              VoiceMode.LISTENING -> Triple(Icons.Default.Hearing, Color.White, PurpNeon.copy(alpha = 0.85f))
              VoiceMode.TRANSCRIBING -> Triple(Icons.Default.GraphicEq, CyanAccent.copy(alpha = 0.9f), PurpDeep)
              VoiceMode.THINKING -> Triple(Icons.Default.Psychology, TextHighlight, PurpSurfaceElevated)
              VoiceMode.SPEAKING -> Triple(Icons.AutoMirrored.Filled.VolumeUp, Color.White, RoseOffline.copy(alpha = 0.85f))
              else -> Triple(Icons.Default.MicOff, RoseOffline, RoseOffline.copy(alpha = 0.25f))
            }
            Box(
              modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(micBg)
                .border(
                  0.75.dp,
                  if (vm == VoiceMode.OFF) TextMuted.copy(alpha = 0.25f) else micTint,
                  RoundedCornerShape(10.dp)
                )
                // CONTRACT: mic does ONE thing — tap toggles Voice ON/OFF
                // using saved canonical Settings → Voice configuration.
                // Conversation mode / engines / visualizer NEVER change from
                // the mic; they live only in Settings → Voice. (Web parity:
                // voice-mode.js boot() has the same single-purpose law.)
                .pointerInput(isPodcastActive) {
                  if (isPodcastActive) {
                    detectTapGestures(
                      onPress = {
                        onPodcastPushToTalkStart()
                        try {
                          tryAwaitRelease()
                        } finally {
                          onPodcastPushToTalkEnd()
                        }
                      }
                    )
                  } else {
                    detectTapGestures(onTap = { onVoiceTrigger() })
                  }
                }
                .testTag("voice_ptt_button"),
              contentAlignment = Alignment.Center
            ) {
              if (vm == VoiceMode.LISTENING && voiceInputLevel > 0.02f) {
                // REAL level ring — scales with actual mic amplitude
                Box(
                  Modifier
                    .size((24.dp + 8.dp * voiceInputLevel))
                    .clip(RoundedCornerShape(50))
                    .background(PurpNeon.copy(alpha = 0.35f * voiceInputLevel + 0.1f))
                )
              }
              Icon(
                imageVector = micIcon,
                contentDescription = "Voice: ${vm.label}",
                tint = micTint,
                modifier = Modifier.size(18.dp)
              )
            }

            Spacer(Modifier.width(4.dp))

            // Canonical navigation — opens sessions, surfaces, mode and companion.
            Box(
              modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(0.75.dp, TextMuted.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .clickable { onCompanionTrigger() },
              contentAlignment = Alignment.Center
            ) {
              Icon(
                Icons.Default.Menu,
                contentDescription = "Open PurpClaw navigation",
                tint = TextMuted.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
              )
            }

            Spacer(Modifier.weight(1f))

            // Send / Cancel — the composer remains locked during an active
            // turn, but the operator always retains this explicit stop.
            Box(
              modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = if (isGenerating || inputText.isNotBlank()) 0.9f else 0.4f))
                .clickable(enabled = isGenerating || inputText.isNotBlank()) {
                  if (isGenerating) onCancelTurn() else onSendMessage()
                }
                .testTag("send_command_button")
                .padding(horizontal = 14.dp),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                if (isGenerating) Icons.Default.Stop
                else if (interactionMode == InteractionMode.WORK) Icons.Default.Bolt
                else Icons.AutoMirrored.Filled.Send,
                if (isGenerating) "Cancel active turn" else "Send",
                tint = Color.White, modifier = Modifier.size(19.dp)
              )
            }
          }
        }
      }
    }

    // ===== VOICE VISUALIZER OVERLAY — truthful audio-state canvas, tap-through =====
    VoiceVisualizerOverlay(
      voiceMode = voiceMode,
      inputLevel = voiceInputLevel,
      outputLevel = voiceOutputLevel,
      modifier = Modifier.matchParentSize()
    )

    // ===== TASK #63: 3D AVATAR OVERLAY ACTOR — sits on the composer's far
    // right. Rigged GLB + existing Meshy cycles; pinch=resize, drag=move,
    // ⟲ chip=reset to composer-right, double-tap=hide (ghost paw restores),
    // long-press=reduced-motion. Placement persists between sessions. =====
    // The SceneView is now physically bounded to the mascot stage above the
    // composer, so it can remain mounted while the IME opens. Destroying and
    // recreating the merged glTF engine during the keyboard transition caused
    // a reproducible libgltfio null dereference on the S25.
    com.example.ui.components.PurpAngolinOverlayActor(
      mood = overlayMood,
      character = selectedCompanion,
      outputLevel = voiceOutputLevel,
      interactionEnabled = actionSheetTurn == null,
      gesturesEnabled = actionSheetTurn == null,
      modifier = Modifier.matchParentSize()
    )

    // --- Per-bubble action rail bottom sheet (long-press a bubble) ---
    actionSheetTurn?.let { st ->
      com.example.ui.components.MessageActionSheet(
        bubbleId = st.id,
        sessionId = st.sessionId,
        bubbleRole = st.role,
        bubbleText = st.content,
        onDismiss = { actionSheetTurn = null },
        onResult = { line -> actionResultLine = line },
      )
    }
  }
}

@Composable
private fun IntakeMenu(
  expanded: Boolean,
  onDismiss: () -> Unit,
  onCamera: () -> Unit,
  onZip: () -> Unit
) {
  DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
    DropdownMenuItem(
      leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
      text = { Text("Camera", fontSize = 13.sp) },
      onClick = onCamera
    )
    DropdownMenuItem(
      leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null) },
      text = { Text("ZIP intake capsule", fontSize = 13.sp) },
      onClick = onZip
    )
  }
}
