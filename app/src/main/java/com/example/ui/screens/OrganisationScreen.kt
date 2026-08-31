package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Diversity3
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.AgentRecord
import com.example.core.model.CouncilRecord
import com.example.core.network.HomeRuntimeBridge
import com.example.core.runtime.CapabilitySubsystem
import com.example.core.runtime.CapabilityTruthState
import com.example.ui.theme.AmberHybrid
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldOnline
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
import com.example.ui.theme.TextSecondary

private val CardShape = RoundedCornerShape(14.dp)

@Composable
fun OrganisationScreen(
  agents: List<AgentRecord>,
  canonicalAgents: List<HomeRuntimeBridge.RosterAgent> = emptyList(),
  skills: List<HomeRuntimeBridge.RosterSkill> = emptyList(),
  councils: List<CouncilRecord>,
  subsystems: List<CapabilitySubsystem> = emptyList(),
  toolsCount: Int? = null,
  providersCount: Int? = null,
  onSpawnAgent: (String, String, String, List<String>) -> Unit,
  onConveneCouncil: (String, String) -> Unit,
  modifier: Modifier = Modifier
) {
  var selectedTab by remember { mutableStateOf("TOWER") } // TOWER, SKILLS, COUNCIL, STUDIO
  var showSpawnDialog by remember { mutableStateOf(false) }
  var showCouncilDialog by remember { mutableStateOf(false) }

  val swarmSubsystem = subsystems.find { it.id == "subsystem.swarm.coordinator" }
  val councilSubsystem = subsystems.find { it.id == "subsystem.council.governance" }
  val studioSubsystem = subsystems.find { it.id == "subsystem.studio.broadcast" }

  var agentName by remember { mutableStateOf("") }
  var agentRole by remember { mutableStateOf("") }
  var agentDiv by remember { mutableStateOf("Engineering") }

  var councilIssue by remember { mutableStateOf("") }

  // Live registry counts — canonical rosters from the main stack, fallback to local mock tower
  val dynamicSoulsCount = if (canonicalAgents.isNotEmpty()) canonicalAgents.size else agents.map { it.soul }.distinct().size
  val dynamicAgentsCount = if (canonicalAgents.isNotEmpty()) canonicalAgents.size else agents.size
  val dynamicSkillsCount = skills.size
  val dynamicToolsCount = toolsCount
  val dynamicProvidersCount = providersCount

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(PurpVoid)
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      // ── Compact header ──
      item {
        Text(
          text = "Organisation",
          fontSize = 22.sp,
          fontWeight = FontWeight.Black,
          color = TextPrimary,
          letterSpacing = 0.2.sp
        )
        Text(
          text = "Agent tower · councils · studio",
          fontSize = 11.sp,
          color = TextSecondary
        )
      }

      // ── Compact stat grid (2 columns) ──
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          StatCounter("SOULS", dynamicSoulsCount, PurpNeon, Modifier.weight(1f))
          StatCounter("AGENTS", dynamicAgentsCount, CyanAccent, Modifier.weight(1f))
        }
      }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          StatCounter("SKILLS", dynamicSkillsCount, EmeraldOnline, Modifier.weight(1f))
          StatCounter("TOOLS", dynamicToolsCount, TextHighlight, Modifier.weight(1f))
        }
      }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          StatCounter("PROVIDERS", dynamicProvidersCount, AmberHybrid, Modifier.weight(1f))
          Spacer(Modifier.weight(1f))
        }
      }

      // ── Tab selector ──
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          listOf(
            Triple("TOWER", "Tower", Icons.Default.Diversity3),
            Triple("SKILLS", "Skills", Icons.Default.Build),
            Triple("COUNCIL", "Council", Icons.Default.Gavel),
            Triple("STUDIO", "Studio", Icons.Default.Podcasts)
          ).forEach { (key, label, icon) ->
            val active = selectedTab == key
            Surface(
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable { selectedTab = key }
                .testTag("tab_${key.lowercase()}"),
              shape = RoundedCornerShape(10.dp),
              color = if (active) PurpPrimary else PurpSurfaceCard,
              border = androidx.compose.foundation.BorderStroke(1.dp, if (active) PurpNeon else PurpBorder)
            ) {
              Row(
                modifier = Modifier.padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(imageVector = icon, contentDescription = null, tint = if (active) Color.White else TextMuted, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                  text = label,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (active) Color.White else TextSecondary
                )
              }
            }
          }
        }
      }

      when (selectedTab) {
        "TOWER" -> {
          if (swarmSubsystem != null && swarmSubsystem.state != CapabilityTruthState.LIVE_VERIFIED) {
            item {
              TruthContractNotice(
                title = "SWARM COORDINATOR: ${swarmSubsystem.state.label}",
                badge = swarmSubsystem.state.badge,
                description = swarmSubsystem.health,
                antiMockNote = "Anti-Mock Contract: Specialist swarm multi-agent DAG parallel engine requires hardware agent execution proof.",
                color = Color(swarmSubsystem.state.colorCode)
              )
            }
          }

          item { SectionLabel("CANONICAL SOULS — LIVE FROM MAIN STACK", CyanAccent) }

          if (canonicalAgents.isNotEmpty()) {
            // Division-grouped canonical roster (44 souls from /api/registry/agents)
            val canonDivisions = canonicalAgents.groupBy { it.division }.toSortedMap()
            canonDivisions.forEach { (division, divisionAgents) ->
              item(key = "cdiv_$division") { SectionLabel(division.uppercase(), PurpNeon) }
              items(divisionAgents, key = { it.id }) { ra ->
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = PurpSurface,
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(ra.icon, fontSize = 16.sp)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                      Text(ra.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                      if (ra.desc.isNotBlank()) {
                        Text(
                          ra.desc, fontSize = 10.sp, color = TextSecondary,
                          maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                      }
                    }
                    Text(ra.model.takeLastWhile { it != '/' }, fontSize = 9.sp,
                      fontFamily = FontFamily.Monospace, color = TextMuted)
                  }
                }
              }
            }
          } else {
            // Fallback: local mock tower while core unreachable
            val divisions = agents.groupBy { it.division }.toSortedMap()
            divisions.forEach { (division, divisionAgents) ->
              item(key = "div_$division") { SectionLabel("$division (LOCAL)", PurpNeon) }
              items(divisionAgents, key = { it.id }) { agent ->
                AgentCard(agent = agent)
              }
            }
          }

          item {
            OutlinedButton(
              onClick = { showSpawnDialog = true },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = PurpNeon)
              Spacer(modifier = Modifier.width(5.dp))
              Text("Spawn Agent", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PurpNeon)
            }
          }
        }

        "SKILLS" -> {
          item { SectionLabel("SKILL REGISTRY — LIVE FROM MAIN STACK", CyanAccent) }
          if (skills.isEmpty()) {
            item {
              Text(
                "No skills loaded. Check connection to the PurpClaw core (:7780).",
                fontSize = 11.sp, color = TextSecondary
              )
            }
          } else {
            items(skills, key = { it.name }) { skill ->
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = PurpSurface,
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                  Text(skill.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyanNeon)
                  if (skill.description.isNotBlank()) {
                    Text(
                      skill.description, fontSize = 10.sp, color = TextSecondary,
                      maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                  }
                }
              }
            }
          }
        }

        "COUNCIL" -> {
          if (councilSubsystem != null && councilSubsystem.state != CapabilityTruthState.LIVE_VERIFIED) {
            item {
              TruthContractNotice(
                title = "COUNCIL GOVERNANCE: ${councilSubsystem.state.label}",
                badge = councilSubsystem.state.badge,
                description = councilSubsystem.health,
                antiMockNote = "Anti-Mock Contract: Multi-agent consensus engine awaiting live quorum verification on connected mesh node.",
                color = Color(councilSubsystem.state.colorCode)
              )
            }
          }

          item { SectionLabel("GOVERNANCE & DELIBERATION RECORDS", CyanAccent) }

          items(councils, key = { it.id }) { council ->
            CouncilCard(council = council)
          }

          item {
            OutlinedButton(
              onClick = { showCouncilDialog = true },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = CyanNeon)
              Spacer(modifier = Modifier.width(5.dp))
              Text("Convene Council", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyanNeon)
            }
          }
        }

        "STUDIO" -> {
          if (studioSubsystem != null && studioSubsystem.state != CapabilityTruthState.LIVE_VERIFIED) {
            item {
              TruthContractNotice(
                title = "STUDIO MESH: ${studioSubsystem.state.label}",
                badge = studioSubsystem.state.badge,
                description = studioSubsystem.health,
                antiMockNote = "Anti-Mock Contract: Real-time podcast/broadcast streaming requires Kokoro/TTS host server connection.",
                color = Color(studioSubsystem.state.colorCode)
              )
            }
          }

          item { SectionLabel("STUDIO ROOMS", PurpNeon) }

          item {
            StudioRoomCard(
              title = "Podcast Studio",
              type = "Acoustic / Narrative",
              desc = "Autonomous multi-agent podcast recording with sentence-buffered Kokoro TTS synthesis.",
              icon = Icons.Default.Podcasts,
              activeAgents = listOf("PurpAngolin", "Lyra Voice", "Babshaggoth")
            )
          }
          item {
            StudioRoomCard(
              title = "Emergency War Room",
              type = "Triage / Incident",
              desc = "High-priority incident response with rapid failover to local sovereign execution.",
              icon = Icons.Default.Shield,
              activeAgents = listOf("Aegis Sentinel", "CodeForge")
            )
          }
          item {
            StudioRoomCard(
              title = "Radio Broadcast",
              type = "Continuous Stream",
              desc = "Continuous ambient monitoring, news digestion, and speech synthesis updates.",
              icon = Icons.Default.Radio,
              activeAgents = listOf("Lyra Voice", "OpticYOLO")
            )
          }
        }
      }

      item { Spacer(Modifier.height(60.dp)) }
    }

    // Spawn Agent Dialog
    if (showSpawnDialog) {
      AlertDialog(
        onDismissRequest = { showSpawnDialog = false },
        title = { Text("Spawn Bounded Specialist", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = agentName,
              onValueChange = { agentName = it },
              label = { Text("Agent Name") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
              value = agentRole,
              onValueChange = { agentRole = it },
              label = { Text("Specialist Role") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
              value = agentDiv,
              onValueChange = { agentDiv = it },
              label = { Text("Division (e.g. Security, Vision, Audio)") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
            )
          }
        },
        confirmButton = {
          ElevatedButton(
            onClick = {
              if (agentName.isNotBlank()) {
                onSpawnAgent(agentName, agentRole.ifBlank { "Specialist" }, agentDiv, listOf("base.execute", "memory.recall"))
                agentName = ""
                agentRole = ""
                showSpawnDialog = false
              }
            },
            colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpNeon, contentColor = Color.White)
          ) {
            Text("Spawn Agent")
          }
        },
        dismissButton = {
          OutlinedButton(onClick = { showSpawnDialog = false }) {
            Text("Cancel", color = TextMuted)
          }
        },
        containerColor = PurpSurfaceCard
      )
    }

    // Convene Council Dialog
    if (showCouncilDialog) {
      AlertDialog(
        onDismissRequest = { showCouncilDialog = false },
        title = { Text("Convene Governance Council", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
          Column {
            OutlinedTextField(
              value = councilIssue,
              onValueChange = { councilIssue = it },
              label = { Text("Issue / Policy Question") },
              modifier = Modifier.fillMaxWidth()
            )
          }
        },
        confirmButton = {
          ElevatedButton(
            onClick = {
              if (councilIssue.isNotBlank()) {
                onConveneCouncil(councilIssue, "System Law & Policy")
                councilIssue = ""
                showCouncilDialog = false
              }
            },
            colors = ButtonDefaults.elevatedButtonColors(containerColor = CyanAccent, contentColor = Color(0xFF00363F))
          ) {
            Text("Convene")
          }
        },
        dismissButton = {
          OutlinedButton(onClick = { showCouncilDialog = false }) {
            Text("Cancel", color = TextMuted)
          }
        },
        containerColor = PurpSurfaceCard
      )
    }
  }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
  Text(
    text = text,
    fontFamily = FontFamily.Monospace,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.sp,
    color = color,
    modifier = Modifier.padding(top = 4.dp)
  )
}

@Composable
private fun StatCounter(label: String, count: Int?, color: Color, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    shape = CardShape,
    color = PurpSurfaceCard,
    border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f))
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        Modifier
          .size(30.dp)
          .background(Brush.linearGradient(listOf(color.copy(alpha = 0.25f), PurpDeep)), CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Box(
          Modifier
            .size(7.dp)
            .background(color, CircleShape)
        )
      }
      Spacer(Modifier.width(10.dp))
      Column {
        Text(
          text = count?.toString() ?: "—",
          fontSize = 16.sp,
          fontWeight = FontWeight.Black,
          fontFamily = FontFamily.Monospace,
          color = color
        )
        Text(
          text = label,
          fontSize = 8.5.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.8.sp,
          color = TextSecondary
        )
      }
    }
  }
}

@Composable
fun AgentCard(agent: AgentRecord) {
  val accent = Color(agent.avatarColor)
  Surface(
    modifier = Modifier.fillMaxWidth().testTag("agent_${agent.id}"),
    shape = CardShape,
    color = PurpSurfaceCard,
    border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder.copy(alpha = 0.7f))
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Avatar orb with tinted gradient
      Box(
        modifier = Modifier
          .size(38.dp)
          .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), PurpDeep)), CircleShape)
          .border(1.dp, accent.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Text(text = agent.name.take(1), fontWeight = FontWeight.Black, fontSize = 15.sp, color = Color.White)
      }

      Spacer(modifier = Modifier.width(11.dp))

      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = agent.name,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = TextPrimary
          )
          Text(
            text = "${(agent.reputationScore * 100).toInt()}% REP",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = EmeraldOnline
          )
        }
        Text(
          text = "${agent.division} · ${agent.role}",
          fontSize = 10.5.sp,
          color = CyanAccent
        )
        Text(
          text = agent.declaredCaps.joinToString(", "),
          fontFamily = FontFamily.Monospace,
          fontSize = 8.5.sp,
          color = TextMuted,
          maxLines = 1
        )
      }
    }
  }
}

@Composable
fun CouncilCard(council: CouncilRecord) {
  Surface(
    modifier = Modifier.fillMaxWidth().testTag("council_${council.id}"),
    shape = CardShape,
    color = PurpSurfaceCard,
    border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder.copy(alpha = 0.7f))
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.Top
    ) {
      // Orb
      Box(
        Modifier
          .size(38.dp)
          .background(Brush.linearGradient(listOf(CyanNeon.copy(alpha = 0.25f), PurpDeep)), CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Icon(Icons.Default.Gavel, null, tint = CyanNeon, modifier = Modifier.size(18.dp))
      }
      Spacer(Modifier.width(11.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = council.domain.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = CyanNeon
          )
          Text(
            text = "${council.votesFor}/${council.votesAgainst}",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = EmeraldOnline
          )
        }
        Text(
          text = council.issue,
          fontSize = 12.5.sp,
          fontWeight = FontWeight.SemiBold,
          color = TextPrimary
        )
        Text(
          text = "↳ Dissent: ${council.dissentSummary}",
          fontSize = 10.5.sp,
          color = AmberHybrid
        )
        Text(
          text = "✓ Outcome: ${council.consensusAction}",
          fontSize = 10.5.sp,
          fontWeight = FontWeight.Medium,
          color = EmeraldOnline
        )
      }
    }
  }
}

@Composable
fun StudioRoomCard(
  title: String,
  type: String,
  desc: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  activeAgents: List<String>
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = CardShape,
    color = PurpSurfaceCard,
    border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder.copy(alpha = 0.7f))
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.Top
    ) {
      // Icon orb
      Box(
        Modifier
          .size(38.dp)
          .background(Brush.linearGradient(listOf(PurpNeon.copy(alpha = 0.25f), PurpDeep)), CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Icon(icon, null, tint = PurpNeon, modifier = Modifier.size(19.dp))
      }
      Spacer(Modifier.width(11.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
          Text(type, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyanAccent)
        }
        Text(desc, fontSize = 11.sp, color = TextSecondary)
        Text(
          "Cast: ${activeAgents.joinToString(", ")}",
          fontFamily = FontFamily.Monospace,
          fontSize = 9.sp,
          color = TextMuted
        )
      }
    }
  }
}

@Composable
fun TruthContractNotice(
  title: String,
  badge: String,
  description: String,
  antiMockNote: String,
  color: Color
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = CardShape,
    color = color.copy(alpha = 0.10f),
    border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.40f))
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.Top
    ) {
      Box(
        Modifier
          .size(34.dp)
          .background(Brush.linearGradient(listOf(color.copy(alpha = 0.25f), PurpDeep)), CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Text(text = badge, fontSize = 13.sp)
      }
      Spacer(Modifier.width(11.dp))
      Column {
        Text(
          text = title,
          fontFamily = FontFamily.Monospace,
          fontSize = 10.5.sp,
          fontWeight = FontWeight.Black,
          color = color
        )
        Text(description, fontSize = 10.5.sp, color = TextPrimary)
        Text(
          "⚖️ $antiMockNote",
          fontSize = 9.5.sp,
          fontFamily = FontFamily.Monospace,
          color = TextSecondary
        )
      }
    }
  }
}
