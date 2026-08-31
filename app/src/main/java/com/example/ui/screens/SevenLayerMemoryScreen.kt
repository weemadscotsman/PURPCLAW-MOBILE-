package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.example.core.database.MemoryItemEntity
import com.example.core.model.MemoryLayer
import com.example.core.runtime.MemoryGateway
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.PurpBorder
import com.example.ui.theme.PurpDeep
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpPrimary
import com.example.ui.theme.PurpSurfaceCard
import com.example.ui.theme.PurpSurfaceElevated
import com.example.ui.theme.PurpVoid
import com.example.ui.theme.TextHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private fun layerColor(layer: MemoryLayer): Color = when (layer) {
  MemoryLayer.EPISODIC -> CyanAccent
  MemoryLayer.SEMANTIC -> EmeraldOnline
  MemoryLayer.PROCEDURAL -> PurpNeon
  MemoryLayer.SYMBOLIC -> Color(0xFFF472B6)
  MemoryLayer.TEMPORAL -> Color(0xFFFB923C)
  MemoryLayer.COUNTERFACTUAL -> Color(0xFF818CF8)
  MemoryLayer.AFFECTIVE -> Color(0xFFF87171)
}

@Composable
fun SevenLayerMemoryScreen(
  memoryItems: List<MemoryItemEntity>,
  workingMemory: List<String>,
  spineStatus: MemoryGateway.SpineStatus = MemoryGateway.SpineStatus.OFFLINE,
  onAddMemory: (MemoryLayer, String, String) -> Unit,
  onDeleteMemory: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  var selectedLayerFilter by remember { mutableStateOf<MemoryLayer?>(null) }
  var searchQuery by remember { mutableStateOf("") }
  var showAddDialog by remember { mutableStateOf(false) }

  var newKey by remember { mutableStateOf("") }
  var newContent by remember { mutableStateOf("") }
  var newLayer by remember { mutableStateOf(MemoryLayer.EPISODIC) }

  val filteredItems = memoryItems.filter { item ->
    val matchesLayer = selectedLayerFilter == null || item.layer == selectedLayerFilter?.name
    val matchesSearch = searchQuery.isBlank() ||
      item.key.contains(searchQuery, ignoreCase = true) ||
      item.content.contains(searchQuery, ignoreCase = true)
    matchesLayer && matchesSearch
  }

  Box(
    modifier = modifier.fillMaxSize().background(PurpVoid)
  ) {
    Column(
      Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
      // ── Header ──
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(Modifier.weight(1f)) {
          Text("Memory", fontSize = 22.sp, fontWeight = FontWeight.Black, color = TextPrimary)
          val populated = memoryItems.map { it.layer }.distinct().size
          Text(
            "$populated/7 layers populated · ${memoryItems.size} records",
            fontSize = 12.sp,
            color = if (populated == 7) EmeraldOnline else TextSecondary
          )
        }
        Surface(
          shape = RoundedCornerShape(11.dp),
          color = PurpNeon.copy(alpha = 0.16f),
          border = androidx.compose.foundation.BorderStroke(1.dp, PurpNeon),
          onClick = { showAddDialog = true },
          modifier = Modifier.testTag("add_memory_button")
        ) {
          Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, tint = PurpNeon, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Store", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PurpNeon)
          }
        }
      }

      Spacer(Modifier.height(12.dp))

      // ── Cognitive spine status (evidence pass paste_24) ──
      // EXPOSES GENUINE state: ONLINE means Home /memory endpoint returned
      // content; OFFLINE means spineRecallProvider was null or network failed;
      // EMPTY means Home responded but had no entries. No fabrication.
      val spineColor = when (spineStatus) {
        MemoryGateway.SpineStatus.ONLINE -> EmeraldOnline
        MemoryGateway.SpineStatus.OFFLINE -> TextMuted
        MemoryGateway.SpineStatus.EMPTY -> CyanNeon
      }
      val spineLabel = when (spineStatus) {
        MemoryGateway.SpineStatus.ONLINE -> "● ONLINE"
        MemoryGateway.SpineStatus.OFFLINE -> "● OFFLINE"
        MemoryGateway.SpineStatus.EMPTY -> "● EMPTY"
      }
      Surface(
        shape = RoundedCornerShape(9.dp),
        color = PurpDeep.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(1.dp, spineColor.copy(alpha = 0.5f))
      ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(
            "Cognitive Spine: ",
            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            color = TextMuted, fontWeight = FontWeight.Bold
          )
          Text(
            spineLabel,
            fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            color = spineColor, fontWeight = FontWeight.Bold
          )
          Spacer(Modifier.width(7.dp))
          Text(
            "Home /memory/recall",
            fontSize = 8.sp, fontFamily = FontFamily.Monospace,
            color = TextMuted
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      // ── Working memory strip ──
      if (workingMemory.isNotEmpty()) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = PurpDeep.copy(alpha = 0.45f),
          border = androidx.compose.foundation.BorderStroke(1.dp, CyanNeon.copy(alpha = 0.25f))
        ) {
          Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
              "WORKING BUFFER",
              fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold, color = CyanNeon, letterSpacing = 1.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
              workingMemory.takeLast(3).joinToString("  ·  "),
              fontSize = 10.5.sp, color = TextSecondary, maxLines = 2
            )
          }
        }
        Spacer(Modifier.height(12.dp))
      }

      // ── Layer grid — the seven orbs ──
      Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
      ) {
        MemoryLayer.values().forEach { layer ->
          val count = memoryItems.count { it.layer == layer.name }
          val lc = layerColor(layer)
          val isSel = selectedLayerFilter == layer
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
              Modifier
                .size(if (isSel) 52.dp else 46.dp)
                .background(
                  Brush.radialGradient(listOf(lc.copy(alpha = if (count > 0) 0.5f else 0.14f), Color.Transparent)),
                  CircleShape
                )
                .clip(CircleShape)
                .clickable { selectedLayerFilter = if (isSel) null else layer }
                .testTag("layer_${layer.name.lowercase()}"),
              contentAlignment = Alignment.Center
            ) {
              Text(
                "${layer.number}",
                fontSize = 17.sp, fontWeight = FontWeight.Black,
                color = if (count > 0 || isSel) lc else TextMuted
              )
            }
            Spacer(Modifier.height(3.dp))
            Text(
              layer.name.take(4).lowercase().replaceFirstChar { it.uppercase() },
              fontSize = 8.sp, fontFamily = FontFamily.Monospace,
              color = if (isSel) TextPrimary else TextMuted
            )
          }
        }
      }

      Spacer(Modifier.height(12.dp))

      // ── Search ──
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Search memory…", fontSize = 12.sp, color = TextMuted) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(17.dp)) },
        modifier = Modifier.fillMaxWidth().testTag("memory_search"),
        singleLine = true,
        shape = RoundedCornerShape(13.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = PurpSurfaceElevated,
          unfocusedContainerColor = PurpSurfaceElevated,
          focusedBorderColor = PurpNeon.copy(alpha = 0.6f),
          unfocusedBorderColor = PurpBorder,
          focusedTextColor = TextPrimary,
          unfocusedTextColor = TextPrimary
        )
      )

      Spacer(Modifier.height(10.dp))

      // ── Records ──
      LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        items(filteredItems, key = { it.id }) { item ->
          MemoryItemRow(item = item, onDelete = { onDeleteMemory(item.id) })
        }
        item { Spacer(Modifier.height(60.dp)) }
      }
    }

    // ── Store dialog ──
    if (showAddDialog) {
      AlertDialog(
        onDismissRequest = { showAddDialog = false },
        title = { Text("Store memory", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              MemoryLayer.values().forEach { layer ->
                val lc = layerColor(layer)
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = if (newLayer == layer) lc.copy(alpha = 0.22f) else PurpSurfaceElevated,
                  border = androidx.compose.foundation.BorderStroke(1.dp, if (newLayer == layer) lc else PurpBorder),
                  onClick = { newLayer = layer }
                ) {
                  Text("${layer.number}. ${layer.name.take(4)}", fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (newLayer == layer) lc else TextMuted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
              }
            }
            OutlinedTextField(value = newKey, onValueChange = { newKey = it },
              label = { Text("Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = newContent, onValueChange = { newContent = it },
              label = { Text("Content") }, modifier = Modifier.fillMaxWidth())
          }
        },
        confirmButton = {
          ElevatedButton(
            onClick = {
              if (newKey.isNotBlank()) {
                onAddMemory(newLayer, newKey, newContent)
                newKey = ""; newContent = ""; showAddDialog = false
              }
            },
            colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpNeon, contentColor = Color.White)
          ) { Text("Commit") }
        },
        dismissButton = { OutlinedButton(onClick = { showAddDialog = false }) { Text("Cancel") } },
        containerColor = PurpSurfaceCard
      )
    }
  }
}

@Composable
fun MemoryItemRow(
  item: MemoryItemEntity,
  onDelete: () -> Unit
) {
  val lc = try {
    layerColor(MemoryLayer.valueOf(item.layer))
  } catch (_: Exception) { CyanAccent }

  Surface(
    shape = RoundedCornerShape(13.dp),
    color = PurpSurfaceCard,
    border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder.copy(alpha = 0.7f)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
      // Layer spine
      Box(
        Modifier
          .width(4.dp)
          .height(44.dp)
          .background(lc, RoundedCornerShape(2.dp))
      )
      Spacer(Modifier.width(11.dp))
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (item.isPinned) {
            Icon(Icons.Default.PushPin, "Pinned", tint = CyanNeon, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(4.dp))
          }
          Text(
            item.key,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1
          )
        }
        Spacer(Modifier.height(3.dp))
        Text(item.content, fontSize = 11.5.sp, lineHeight = 15.sp, color = TextSecondary, maxLines = 3)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(item.layer.lowercase(), fontFamily = FontFamily.Monospace, fontSize = 8.sp,
            color = lc, modifier = Modifier.background(lc.copy(alpha = 0.13f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
          Spacer(Modifier.width(7.dp))
          Text("accessed ${item.accessCount}×", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = TextMuted)
        }
      }
      IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
        Icon(Icons.Default.Delete, "Delete", tint = TextMuted.copy(alpha = 0.55f), modifier = Modifier.size(14.dp))
      }
    }
  }
}
