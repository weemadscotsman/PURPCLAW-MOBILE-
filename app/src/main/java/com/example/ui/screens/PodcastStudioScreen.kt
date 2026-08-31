package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.model.PodcastEpisode
import com.example.core.runtime.CouncilPodcastEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Podcast Studio:
 *  1. Seat Manager — 10 mutable seats, add/remove with role+soul+personality.
 *  2. Saved Episodes — newest first, opens inline transcript.
 *  3. Saved Podcasts — post-show records with .md transcript path the user
 *     can grab later (TASK #55).
 */
@Composable
fun PodcastStudioScreen(engine: CouncilPodcastEngine) {
  var episodes by remember { mutableStateOf<List<PodcastEpisode>>(emptyList()) }
  var savedPodcasts by remember { mutableStateOf<List<CouncilPodcastEngine.SavedEpisode>>(emptyList()) }
  var selected by remember { mutableStateOf<PodcastEpisode?>(null) }
  var showSeatManager by remember { mutableStateOf(false) }

  val activeSeats by engine.activeSeats.collectAsState()

  LaunchedEffect(Unit) {
    episodes = engine.loadEpisodes()
    savedPodcasts = engine.loadSavedIndex()
  }

  val current = selected
  if (current != null) {
    EpisodeTranscript(episode = current, onBack = { selected = null })
    return
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // Header
    item {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 16.dp)
      ) {
        Icon(Icons.Filled.Podcasts, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
          "Podcast Studio",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
      }
      Text(
        "Live multi-voice council episodes. Convene one from chat: \"convene a council podcast about …\"",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
      )
    }

    // TASK #54: Seat Manager card
    item {
      Card(
        modifier = Modifier.fillMaxWidth().clickable { showSeatManager = !showSeatManager },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
      ) {
        Column(Modifier.padding(14.dp)) {
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(Modifier.weight(1f)) {
              Text("Council Panel — ${activeSeats.size} seats",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
              Text(
                "${activeSeats.joinToString { "${it.soulName} (${it.role})" }.take(140)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
              )
            }
            Text(
              if (showSeatManager) "Hide" else "Edit panel",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary
            )
          }
          if (showSeatManager) {
            SeatManagerPanel(engine = engine, activeSeats = activeSeats)
          }
        }
      }
    }

    // TASK #55: Saved Podcasts (post-show records with .md paths)
    item {
      Text(
        "Saved Podcasts",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp)
      )
      Text(
        "Every show auto-saves here as a .md transcript. Tap to read.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    if (savedPodcasts.isEmpty()) {
      item {
        Text(
          "(none yet — convene your first council from chat)",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 8.dp)
        )
      }
    } else {
      items(savedPodcasts, key = { it.id }) { sp ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))
        ) {
          Column(Modifier.padding(12.dp)) {
            Text(sp.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
              "topic: ${sp.topic}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
              "${sp.turnCount} turns · ${sp.seatCount} seats · ~${(sp.estimatedDurationMs / 60_000)} min" +
                if (sp.breakUsed) " · break used" else "",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary
            )
            Text(
              "transcript: ${sp.transcriptMdPath}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 4.dp)
            )
          }
        }
      }
    }

    // Episodes (legacy list — same data as Saved Podcasts but in older
    // shape; keep for backward compat with existing users)
    item {
      Text(
        "Episode transcripts",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp)
      )
    }
    if (episodes.isEmpty()) {
      item {
        Text(
          "No episodes yet. Ask PurpAngolin to convene a council in CHAT.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 12.dp)
        )
      }
    } else {
      items(episodes, key = { it.id }) { ep ->
        Card(
          modifier = Modifier.fillMaxWidth().clickable { selected = ep },
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
          Column(Modifier.padding(14.dp)) {
            Text(ep.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
              "topic: ${ep.topic}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.padding(top = 6.dp)) {
              Text(
                "${ep.members.size} turns · ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(ep.createdAt))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
              )
            }
          }
        }
      }
    }
  }
}

/**
 * TASK #54: seat-manager panel. Lists the active panel with per-seat delete
 * (HOST protected). Inline form adds a custom seat. Reset button restores the
 * canonical 10.
 */
@Composable
private fun SeatManagerPanel(
  engine: CouncilPodcastEngine,
  activeSeats: List<CouncilPodcastEngine.Seat>
) {
  var newRole by remember { mutableStateOf("") }
  var newSoul by remember { mutableStateOf("") }
  var newPersonality by remember { mutableStateOf("") }

  Column(Modifier.padding(top = 10.dp)) {
    Text(
      "Use the remove button to drop a seat. HOST is permanent. Add a custom seat below.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    activeSeats.forEach { seat ->
      Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          Modifier
            .background(seatColor(seat.role).copy(alpha = 0.20f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
          Text(seat.role, style = MaterialTheme.typography.labelSmall, color = seatColor(seat.role))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
          Text(seat.soulName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
          Text(
            seat.personality.take(80) + if (seat.personality.length > 80) "…" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
          )
        }
        if (!seat.role.equals("HOST", true)) {
          IconButton(onClick = { engine.removeSeat(seat.role) }) {
            Icon(Icons.Filled.Close, contentDescription = "remove seat")
          }
        }
      }
    }

    // Inline add form
    OutlinedTextField(
      value = newRole, onValueChange = { newRole = it },
      label = { Text("Role (e.g. MEMORY)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
    OutlinedTextField(
      value = newSoul, onValueChange = { newSoul = it },
      label = { Text("Soul name (e.g. Lyra)") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    )
    OutlinedTextField(
      value = newPersonality, onValueChange = { newPersonality = it },
      label = { Text("Personality (voice + tone + opinion)") },
      minLines = 2,
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    )
    Row(
      Modifier.fillMaxWidth().padding(top = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      IconButton(
        onClick = {
          if (newRole.isNotBlank() && newSoul.isNotBlank() && newPersonality.isNotBlank()) {
            engine.addSeat(
              CouncilPodcastEngine.Seat(
                role = newRole.trim().uppercase(),
                soulName = newSoul.trim(),
                personality = newPersonality.trim()
              )
            )
            newRole = ""; newSoul = ""; newPersonality = ""
          }
        }
      ) {
        Icon(Icons.Filled.Add, contentDescription = "add seat")
      }
      IconButton(onClick = { engine.resetActiveSeats() }) {
        Icon(Icons.Filled.RestartAlt, contentDescription = "reset panel")
      }
      Text(
        "${activeSeats.size}/10 seats",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp).align(Alignment.CenterVertically)
      )
    }
  }
}

@Composable
private fun EpisodeTranscript(episode: PodcastEpisode, onBack: () -> Unit) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text("← Episodes", style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.clickable { onBack() })
        Text("receipts: ${episode.routingReceipts}", style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    items(episode.members) { turn ->
      Column {
        Text(
          "${turn.role} · ${turn.speakerName}",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = seatColor(turn.role)
        )
        Text(
          turn.text,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 2.dp, start = 4.dp)
        )
      }
    }
    if (episode.verdictSummary.isNotBlank()) {
      item {
        Box(
          Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp)
        ) {
          Text("VERDICT\n\n${episode.verdictSummary}", style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

/** Deterministic per-seat tint so roles are scannable in the transcript. */
private fun seatColor(role: String): Color = when (role.uppercase(Locale.ROOT)) {
  "HOST" -> Color(0xFFB388FF)
  "AUDITOR" -> Color(0xFFFF8A80)
  "ARCHITECTURE" -> Color(0xFF80D8FF)
  "ANDROID" -> Color(0xFFB9F6CA)
  "AGENTS" -> Color(0xFFFFE57F)
  "MEMORY" -> Color(0xFFB388FF)
  "TOOLS" -> Color(0xFFFF8A80)
  "ROUTING" -> Color(0xFF82B1FF)
  "VISUAL" -> Color(0xFFFF80AB)
  "GUEST" -> Color(0xFFB0BEC5)
  else -> Color(0xFFCFD8DC)
}
