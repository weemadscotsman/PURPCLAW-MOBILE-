@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.model.CompanionPetState
import com.example.core.model.InteractionMode
import com.example.core.model.MeshStatus
import com.example.ui.design.PurpButton
import com.example.ui.theme.Accent
import com.example.ui.theme.AmberHybrid
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.CyanBorder
import com.example.ui.theme.PurpSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

/**
 * Compact top micro-bar — ≤52dp. The whole OS is one gesture away; nothing
 * lives on the forehead. Brand tap = mesh sheet, mode pill = inline dropdown.
 */
@Composable
fun HeaderMeshBar(
  meshStatus: MeshStatus,
  homeNode: com.example.core.model.MeshNode,
  phoneNode: com.example.core.model.MeshNode,
  reconciliationLogs: List<String>,
  interactionMode: InteractionMode,
  fullSystemScope: Boolean,
  companionState: CompanionPetState,
  selectedCompanion: String,
  onModeChanged: (InteractionMode) -> Unit,
  onToggleFullSystem: () -> Unit,
  onSelectCompanion: (String) -> Unit,
  onToggleMeshStatus: (MeshStatus) -> Unit,
  onReconcile: () -> Unit,
  modifier: Modifier = Modifier
) {
  var showMeshSheet by remember { mutableStateOf(false) }
  var showModeMenu by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val meshBadgeColor by animateColorAsState(
    targetValue = when (meshStatus) {
      MeshStatus.HOME_ONLINE -> EmeraldOnline
      MeshStatus.SOVEREIGN_LOCAL -> Accent
      MeshStatus.HYBRID_DEGRADED -> AmberHybrid
      MeshStatus.DISCOVERING -> TextMuted
    },
    label = "meshColor"
  )

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceVariant,
    tonalElevation = 2.dp
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 9.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Brand + status orb — tap opens the full mesh topology sheet
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
          .clip(RoundedCornerShape(10.dp))
          .clickable { showMeshSheet = true }
          .padding(horizontal = 4.dp, vertical = 3.dp)
      ) {
        Box(
          modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(
              Brush.linearGradient(listOf(Accent.copy(alpha = 0.55f), Color(0xFF0D1F2D)))
            )
            .border(1.dp, Accent.copy(alpha = 0.6f), CircleShape)
        ) {
          Image(
            painter = painterResource(id = R.drawable.purpclaw_logo_canonical),
            contentDescription = "PurpClaw",
            modifier = Modifier
              .size(28.dp)
              .clip(CircleShape)
          )
        }
        Spacer(modifier = Modifier.width(9.dp))
        Column {
          Text(
            text = "PurpClaw",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 0.3.sp,
            color = TextPrimary
          )
          Text(
            text = when (meshStatus) {
              MeshStatus.HOME_ONLINE -> "${homeNode.name.take(22)} · ${meshStatus.label}"
              else -> meshStatus.label
            },
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = TextMuted,
            maxLines = 1
          )
        }
      }

      Spacer(modifier = Modifier.weight(1f))

      // Live status orb — colour IS the state; details in the sheet
      Box(
        modifier = Modifier
          .size(11.dp)
          .background(
            Brush.radialGradient(listOf(meshBadgeColor, meshBadgeColor.copy(alpha = 0.35f))),
            CircleShape
          )
          .clickable { showMeshSheet = true }
      )

      Spacer(modifier = Modifier.width(12.dp))

      // Mode indicator — display-only. The CHAT|WORK segmented control at the
      // composer is the ONE mode control (one-control law). Header shows truth.
      Surface(
        modifier = Modifier.testTag("mode_pill"),
        shape = RoundedCornerShape(12.dp),
        color = when (interactionMode) {
          InteractionMode.WORK -> Accent.copy(alpha = 0.25f)
          InteractionMode.CHAT -> PurpSurfaceElevated.copy(alpha = 0.7f)
        },
        border = androidx.compose.foundation.BorderStroke(
          1.dp,
          if (interactionMode == InteractionMode.WORK) Accent else CyanBorder
        )
      ) {
        Text(
          text = interactionMode.label,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace,
          color = TextPrimary,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
      }
    }
  }

  // --- Mesh topology sheet — summoned, never permanent ---
  if (showMeshSheet) {
    ModalBottomSheet(
      onDismissRequest = { showMeshSheet = false },
      sheetState = sheetState,
      containerColor = com.example.ui.theme.PurpSurfaceCard
    ) {
      Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        MeshSheetBody(
          meshStatus = meshStatus,
          homeNode = homeNode,
          phoneNode = phoneNode,
          reconciliationLogs = reconciliationLogs,
          fullSystemScope = fullSystemScope,
          companionState = companionState,
          selectedCompanion = selectedCompanion,
          onToggleFullSystem = onToggleFullSystem,
          onSelectCompanion = onSelectCompanion,
          onToggleMeshStatus = onToggleMeshStatus,
          onReconcile = onReconcile
        )
      }
    }
  }
}

/** Sheet body extracted so the bar stays readable. */
@Composable
private fun MeshSheetBody(
  meshStatus: MeshStatus,
  homeNode: com.example.core.model.MeshNode,
  phoneNode: com.example.core.model.MeshNode,
  reconciliationLogs: List<String>,
  fullSystemScope: Boolean,
  companionState: CompanionPetState,
  selectedCompanion: String,
  onToggleFullSystem: () -> Unit,
  onSelectCompanion: (String) -> Unit,
  onToggleMeshStatus: (MeshStatus) -> Unit,
  onReconcile: () -> Unit
) {
  val row = @Composable { label: String, value: String, valueColor: androidx.compose.ui.graphics.Color ->
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      // WEIGHT LAW: the label side yields; the value side takes what it needs.
      // Without a weighted sibling a long label squeezed the value column.
      Text(
        label,
        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextMuted,
        maxLines = 1,
        modifier = Modifier.weight(1f, fill = false)
      )
      Spacer(Modifier.size(10.dp))
      Text(value, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = valueColor, maxLines = 1)
    }
  }

  Text(
    "MESH TOPOLOGY",
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    color = TextMuted,
    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
  )
  row("HOME", if (homeNode.online) homeNode.name else "unreachable", if (homeNode.online) EmeraldOnline else TextMuted)
  row("ENDPOINT", homeNode.address, TextPrimary)
  row("PHONE", phoneNode.nodeId, TextPrimary)
  row("SCOPE", if (fullSystemScope) "FULL SYSTEM" else "SCOPED", if (fullSystemScope) CyanNeon else TextMuted)

  Spacer(Modifier.size(8.dp))
  Text(
    "RECONCILIATION LOG",
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    color = TextMuted,
    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
  )
  reconciliationLogs.takeLast(6).forEach { line ->
    Text(
      line,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace,
      color = TextMuted,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 1.dp)
    )
  }

  Spacer(Modifier.size(12.dp))
  // VERTICAL-TEXT FIX (#54): three buttons in a bare Row squeezed each label
  // to one letter per line on phone width. Canonical PurpButton + horizontal
  // scroll keeps every label on one line instead of crushing the buttons.
  Row(
    Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 20.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    PurpButton(text = "Probe now", onClick = onReconcile)
    PurpButton(
      text = "Force failover drill",
      onClick = { onToggleMeshStatus(if (meshStatus == MeshStatus.HOME_ONLINE) MeshStatus.SOVEREIGN_LOCAL else MeshStatus.HOME_ONLINE) }
    )
    PurpButton(
      text = if (fullSystemScope) "Scope: Full" else "Scope: Scoped",
      onClick = onToggleFullSystem
    )
  }

  Spacer(Modifier.size(8.dp))
  Text(
    "COMPANION",
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    color = TextMuted,
    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
  )
  Row(
    Modifier.padding(horizontal = 20.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Avatar3DClips.COMPANION_NAMES.forEach { name ->
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selectedCompanion == name) PurpSurfaceElevated else com.example.ui.theme.PurpSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedCompanion == name) Accent else CyanBorder),
        modifier = Modifier.clickable { onSelectCompanion(name) }
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
          if (name == "PurpReaper") {
            PurpReaperAvatar(
              companionState = if (selectedCompanion == name) companionState else CompanionPetState.IDLE,
              isSelected = selectedCompanion == name,
              size = 20.dp
            )
            Spacer(Modifier.width(4.dp))
          }
          Text(
            name,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selectedCompanion == name) TextPrimary else TextMuted
          )
        }
      }
    }
  }
  // Live state preview — shows the PurpReaper's current animation state
  if (selectedCompanion == "PurpReaper") {
    Spacer(Modifier.size(6.dp))
    Row(
      Modifier.padding(horizontal = 20.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      PurpReaperAvatar(companionState = companionState, isSelected = true, size = 48.dp)
      Spacer(Modifier.width(10.dp))
      Column {
        Text(
          text = companionState.label,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = TextPrimary
        )
        Text(
          text = "PurpReaper · active",
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace,
          color = TextMuted
        )
      }
    }
  }
  // ONE-ACTIVITY-SURFACE LAW: header no longer shows the companion emoji —
  // the live turn card is the single execution/thinking indicator.
}
