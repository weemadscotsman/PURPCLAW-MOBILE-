package com.example.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.InteractionMode
import com.example.core.model.MissionRecord
import com.example.core.model.MissionStep
import com.example.ui.theme.AmberHybrid
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.PurpBorder
import com.example.ui.theme.PurpDeep
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpSurfaceCard
import com.example.ui.theme.PurpSurfaceElevated
import com.example.ui.theme.PurpVoid
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.design.PurpEmptyState
import com.example.ui.design.PurpHeader
import com.example.ui.design.PurpSectionHeader
import com.example.ui.design.PurpSpacing

@Composable
fun MissionsScreen(
  missions: List<MissionRecord>,
  onCreateMission: (String, String, InteractionMode) -> Unit,
  modifier: Modifier = Modifier
) {
  var showNewMissionDialog by remember { mutableStateOf(false) }
  var newTitle by remember { mutableStateOf("") }
  var newGoal by remember { mutableStateOf("") }
  var expandedId by remember { mutableStateOf<String?>(null) }

  val active = missions.filter { it.status == "active" || it.status == "in_progress" }
  val done = missions.filter { it.status == "completed" }
  val other = missions.filter { it.status != "active" && it.status != "completed" && it.status != "in_progress" }

  Box(
    modifier = modifier.fillMaxSize().background(PurpVoid)
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(horizontal = PurpSpacing.md, vertical = PurpSpacing.control),
      verticalArrangement = Arrangement.spacedBy(PurpSpacing.control)
    ) {
      // ── Header ──
      item {
        val running = active.count { it.progress > 0f && it.progress < 1f }
        PurpHeader(
          title = "Missions",
          subtitle = when {
            active.isEmpty() -> "No active missions — spin one up"
            else -> "${active.size} active" + (if (running > 0) " · $running in flight" else "")
          },
          subtitleColor = if (active.isEmpty()) TextSecondary else EmeraldOnline
        )
      }

      if (missions.isEmpty()) {
        item {
          PurpEmptyState(
            title = "Nothing in flight",
            message = "Tap + to dispatch your first mission.",
            icon = Icons.Default.Add
          )
        }
      }

      // ── Active missions ──
      if (active.isNotEmpty()) {
        item { PurpSectionHeader("ACTIVE", EmeraldOnline) }
        items(active, key = { it.id }) { mission ->
          MissionCard(
            mission = mission,
            expanded = expandedId == mission.id,
            onToggle = { expandedId = if (expandedId == mission.id) null else mission.id }
          )
        }
      }

      // ── Completed ──
      if (done.isNotEmpty()) {
        item { PurpSectionHeader("COMPLETED", CyanAccent) }
        items(done, key = { it.id }) { mission ->
          MissionCard(mission = mission, expanded = false, onToggle = {})
        }
      }

      // ── Other (paused/draft) ──
      if (other.isNotEmpty()) {
        item { PurpSectionHeader("PAUSED / DRAFT", TextMuted) }
        items(other, key = { it.id }) { mission ->
          MissionCard(mission = mission, expanded = false, onToggle = {})
        }
      }

      item { Spacer(Modifier.height(72.dp)) }
    }

    // ── FAB ──
    Box(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(18.dp)
        .size(52.dp)
        .background(Brush.linearGradient(listOf(PurpNeon, PurpDeep)), CircleShape)
        .clickable { showNewMissionDialog = true }
        .testTag("create_mission_fab"),
      contentAlignment = Alignment.Center
    ) {
      Icon(Icons.Default.Add, contentDescription = "Create Mission", tint = Color.White, modifier = Modifier.size(26.dp))
    }

    // ── Create dialog ──
    if (showNewMissionDialog) {
      AlertDialog(
        onDismissRequest = { showNewMissionDialog = false },
        title = { Text("New mission", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedTextField(
              value = newTitle,
              onValueChange = { newTitle = it },
              label = { Text("Title") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp)
            )
            OutlinedTextField(
              value = newGoal,
              onValueChange = { newGoal = it },
              label = { Text("Goal / outcome") },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp)
            )
          }
        },
        confirmButton = {
          ElevatedButton(
            onClick = {
              if (newTitle.isNotBlank()) {
                onCreateMission(newTitle, newGoal.ifBlank { newTitle }, InteractionMode.WORK)
                newTitle = ""; newGoal = ""; showNewMissionDialog = false
              }
            },
            colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpNeon, contentColor = Color.White),
            shape = RoundedCornerShape(9.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
          ) { Text("Dispatch", fontSize = 12.sp) }
        },
        dismissButton = {
          OutlinedButton(
            onClick = { showNewMissionDialog = false },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
          ) { Text("Cancel", fontSize = 12.sp, color = TextMuted) }
        },
        containerColor = PurpSurfaceCard,
        shape = RoundedCornerShape(16.dp)
      )
    }
  }
}

@Composable
fun MissionCard(
  mission: MissionRecord,
  modifier: Modifier = Modifier,
  expanded: Boolean = true,
  onToggle: () -> Unit = {}
) {
  val statusColor = when (mission.status) {
    "active", "in_progress" -> EmeraldOnline
    "completed" -> CyanAccent
    "paused" -> AmberHybrid
    else -> TextMuted
  }

  Surface(
    modifier = modifier.fillMaxWidth().animateContentSize(),
    shape = RoundedCornerShape(14.dp),
    color = PurpSurfaceCard,
    border = androidx.compose.foundation.BorderStroke(1.dp, if (expanded) PurpNeon.copy(alpha = 0.5f) else PurpBorder.copy(alpha = 0.7f)),
    onClick = onToggle
  ) {
    Column(Modifier.padding(14.dp)) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          // progress orb — matches ToolsMesh icon-orb language
          Box(
            Modifier.size(38.dp).background(
              Brush.linearGradient(listOf(statusColor.copy(alpha = 0.28f), PurpDeep)),
              CircleShape
            ),
            contentAlignment = Alignment.Center
          ) {
            Text(
              "${(mission.progress * 100).toInt()}%",
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = statusColor,
              textAlign = TextAlign.Center,
              maxLines = 1
            )
          }
          Spacer(Modifier.width(11.dp))
          Column(Modifier.weight(1f, fill = false)) {
            Text(
              mission.title,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = TextPrimary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              "${mission.steps.size} stages · ${mission.activeAgents.size} agents",
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              color = TextMuted,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
          if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          null, tint = TextMuted, modifier = Modifier.size(18.dp)
        )
      }

      Spacer(Modifier.height(8.dp))
      LinearProgressIndicator(
        progress = { mission.progress },
        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
        color = statusColor,
        trackColor = PurpDeep
      )

      // Expanded: goal + steps + agents
      if (expanded) {
        Spacer(Modifier.height(10.dp))
        Text(
          mission.goal,
          fontSize = 12.sp,
          lineHeight = 16.sp,
          color = TextSecondary,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          mission.steps.forEachIndexed { index, step ->
            MissionStepRow(step = step, index = index + 1)
          }
        }

        if (!mission.activeAgents.isNullOrEmpty()) {
          Spacer(Modifier.height(8.dp))
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
          ) {
            mission.activeAgents.forEach { agent ->
              Surface(shape = RoundedCornerShape(6.dp), color = PurpNeon.copy(alpha = 0.14f)) {
                Text(
                  agent.take(12),
                  fontSize = 9.sp,
                  fontFamily = FontFamily.Monospace,
                  color = PurpNeon,
                  maxLines = 1,
                  modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
              }
            }
          }
        }
      } else if (!mission.activeAgents.isNullOrEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
          mission.activeAgents.joinToString(" · "),
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace,
          color = TextMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
fun MissionStepRow(
  step: MissionStep,
  index: Int,
  modifier: Modifier = Modifier
) {
  val (statusIcon, statusColor) = when (step.status) {
    "completed" -> Pair(Icons.Default.CheckCircle, EmeraldOnline)
    "in_progress" -> Pair(Icons.Default.HourglassTop, PurpNeon)
    else -> Pair(Icons.Default.Pending, TextMuted)
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = PurpSurfaceElevated,
    shape = RoundedCornerShape(9.dp),
    border = androidx.compose.foundation.BorderStroke(0.5.dp, PurpBorder)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(imageVector = statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(14.dp))
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          "$index. ${step.title}",
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Medium,
          color = TextPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        if (!step.outputEvidence.isNullOrBlank()) {
          Text(
            "↳ ${step.outputEvidence}",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            color = CyanNeon,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
      Spacer(Modifier.width(6.dp))
      Text(
        step.assignedAgent.take(10),
        fontFamily = FontFamily.Monospace,
        fontSize = 8.5.sp,
        color = TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}
