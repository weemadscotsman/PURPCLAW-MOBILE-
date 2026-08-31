package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.core.network.HomeRuntimeBridge
import com.example.core.runtime.ConversationMode
import com.example.core.runtime.VoiceModeController
import com.example.ui.theme.PurpPrimary
import com.example.ui.theme.PurpSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * SETTINGS → VOICE — the ONLY place conversation mode / visualizer config
 * changes (canonical settings law, web parity). Mic button never cycles.
 * Server (/api/settings voice.*) is source of truth; local persistence is a
 * cache. Changes push to core so Web + Android share one truth.
 */
@Composable
fun VoiceSettingsSection(
  voiceModeController: VoiceModeController?,
  homeBridge: HomeRuntimeBridge?
) {
  val coroutineScope = rememberCoroutineScope()
  val currentMode = voiceModeController?.conversationMode?.collectAsState()?.value

  // local echo of server state for non-controller fields (visualizer etc.)
  var vizType by remember { mutableStateOf("orb") }
  var opacity by remember { mutableStateOf(0.35f) }
  var showState by remember { mutableStateOf(true) }
  var reactInput by remember { mutableStateOf(true) }
  var reactOutput by remember { mutableStateOf(true) }
  var syncNote by remember { mutableStateOf<String?>(null) }

  // hydrate from canonical server on entry (core wins)
  LaunchedEffect(Unit) {
    val remote = homeBridge?.fetchVoiceSettings()
    if (remote != null) {
      remote.visualizerType?.let { vizType = it.lowercase() }
      remote.opacity?.let { opacity = it }
      remote.showState?.let { showState = it }
      remote.reactInput?.let { reactInput = it }
      remote.reactOutput?.let { reactOutput = it }
      syncNote = "synced from core"
    } else {
      syncNote = "core unreachable — cached values"
    }
  }

  fun push(patch: HomeRuntimeBridge.VoiceSettings) {
    coroutineScope.launch { homeBridge?.pushVoiceSettings(patch) }
    syncNote = "pushed to core"
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Text(
      text = "VOICE — CONVERSATION MODE",
      style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
      color = TextMuted
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf(
        ConversationMode.PTT,
        ConversationMode.VOICE_IN,
        ConversationMode.VOICE_INOUT,
        ConversationMode.HANDS_FREE
      ).forEach { mode ->
        val selected = currentMode == mode
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (selected) PurpPrimary else PurpSurfaceElevated,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
              voiceModeController?.setConversationMode(mode)
              push(HomeRuntimeBridge.VoiceSettings(mode = mode.id))
            }
        ) {
          Text(
            text = mode.id.uppercase(),
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
          )
        }
      }
    }

    Text(
      text = "REACTIVE VISUALIZER",
      style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
      color = TextMuted
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      listOf("off", "orb", "wave", "spectrum", "bars").forEach { t ->
        val selected = vizType == t
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (selected) PurpPrimary else PurpSurfaceElevated,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
              vizType = t
              push(HomeRuntimeBridge.VoiceSettings(visualizerType = t))
            }
        ) {
          Text(
            text = t.uppercase(),
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
          )
        }
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked = showState, onCheckedChange = {
        showState = it
        push(HomeRuntimeBridge.VoiceSettings(showState = it))
      })
      Text("Show state label", color = TextPrimary)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked = reactInput, onCheckedChange = {
        reactInput = it
        push(HomeRuntimeBridge.VoiceSettings(reactInput = it))
      })
      Text("React to my voice", color = TextPrimary)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Checkbox(checked = reactOutput, onCheckedChange = {
        reactOutput = it
        push(HomeRuntimeBridge.VoiceSettings(reactOutput = it))
      })
      Text("React to Purp's voice", color = TextPrimary)
    }

    syncNote?.let {
      Text(text = it, color = TextMuted)
    }
  }
}
