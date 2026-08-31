package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.ToolAffinity
import com.example.core.runtime.ToolSpec
import com.example.ui.theme.AmberHybrid
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
import com.example.ui.theme.RoseOffline
import com.example.ui.theme.TextHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private fun toolIcon(name: String): ImageVector = when {
  name.contains("flashlight") -> Icons.Default.FlashlightOn
  name.contains("app.open") -> Icons.Default.Launch
  name.contains("settings") -> Icons.Default.Settings
  name.contains("vibrate") -> Icons.Default.Vibration
  name.contains("camera") -> Icons.Default.CameraAlt
  name.contains("tts") || name.contains("speak") -> Icons.Default.VolumeUp
  name.contains("mic") || name.contains("speech") -> Icons.Default.Mic
  name.contains("battery") || name.contains("sensor") -> Icons.Default.Sensors
  name.contains("storage") -> Icons.Default.Storage
  name.contains("device.info") || name.contains("phone") -> Icons.Default.PhoneAndroid
  name.contains("shell") || name.contains("powershell") || name.contains("build") -> Icons.Default.Build
  name.contains("browser") || name.contains("playwright") -> Icons.Default.Launch
  else -> Icons.Default.Bolt
}

@Composable
fun ToolsMeshScreen(
  tools: List<ToolSpec>,
  isHomeOnline: Boolean,
  onRunTool: (String, String) -> Unit,
  onTriggerIntake: () -> Unit,
  modifier: Modifier = Modifier
) {
  var lastOutput by remember { mutableStateOf<String?>(null) }
  var lastTool by remember { mutableStateOf<String?>(null) }

  fun run(tool: ToolSpec, args: String) {
    onRunTool(tool.name, args)
    lastTool = tool.displayName
    lastOutput = "Execution requested for ${tool.name}. Waiting for a signed result in Audit & Truth."
  }

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
      // ── Header ──
      item {
        Column {
          Text(
            text = "Capability Mesh",
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = TextPrimary,
            letterSpacing = 0.2.sp
          )
          Text(
            text = if (isHomeOnline) "Phone hands + Home rig bridge · all systems live"
                   else "Phone hands only · Home rig offline",
            fontSize = 12.sp,
            color = if (isHomeOnline) EmeraldOnline else AmberHybrid
          )
        }
      }

      // ── Live execution evidence card ──
      if (!lastOutput.isNullOrBlank()) {
        item {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = PurpSurfaceCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, CyanNeon.copy(alpha = 0.6f))
          ) {
            Column(Modifier.padding(14.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  Modifier.size(8.dp).background(EmeraldOnline, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                  text = lastTool ?: "EXECUTION",
                  fontFamily = FontFamily.Monospace,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  color = CyanNeon
                )
              }
              Spacer(Modifier.height(6.dp))
              Text(lastOutput ?: "", fontSize = 11.sp, color = TextPrimary)
            }
          }
        }
      }

      // ── Device control section ──
      item { SectionLabel("DEVICE CONTROL", PurpNeon) }
      items(tools.filter {
        it.name.contains("flashlight") || it.name.contains("app.open") ||
        it.name.contains("vibrate") || it.name.contains("settings.panel")
      }) { tool ->
        ToolRow(tool) { run(tool, "") }
      }

      // ── Native sensors & info ──
      item { SectionLabel("SENSORS & TELEMETRY", CyanAccent) }
      items(tools.filter {
        it.affinity == ToolAffinity.ANDROID_NATIVE &&
        !it.name.contains("flashlight") && !it.name.contains("app.open") &&
        !it.name.contains("vibrate") && !it.name.contains("settings.panel") &&
        !it.name.contains("notification")
      }) { tool ->
        ToolRow(tool) { run(tool, "read_all") }
      }

      // ── Messaging ──
      item { SectionLabel("OUTPUT & MESSAGING", EmeraldOnline) }
      items(tools.filter { it.name.contains("notification") || (it.name.contains("tts") && it.affinity == ToolAffinity.ANDROID_NATIVE) }) { tool ->
        ToolRow(tool) { run(tool, "PurpClaw capability mesh online.") }
      }

      // ── Intake capsule ──
      item {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = PurpSurfaceCard,
          border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder)
        ) {
          Column(Modifier.padding(14.dp)) {
            Row(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Archive, null, tint = PurpNeon, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Intake Capsule", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
              }
              ElevatedButton(
                onClick = {
                  onTriggerIntake()
                  lastTool = "INTAKE"
                  lastOutput = "Intake requested. Waiting for verified archive and memory receipts."
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpNeon, contentColor = Color.White),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
              ) { Text("Generate", fontSize = 10.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
              "Extracts safe tree manifests with SHA-256 checksums into 7-layer memory.",
              fontSize = 11.sp, color = TextSecondary
            )
          }
        }
      }

      // ── Home bridge ──
      item { SectionLabel("HOME WORKSTATION BRIDGE", if (isHomeOnline) EmeraldOnline else RoseOffline) }
      items(tools.filter { it.affinity == ToolAffinity.REMOTE_BRIDGE }) { tool ->
        ToolRow(tool) {
          if (isHomeOnline) run(tool, "Get-Process | Select-Object -First 5")
          else { lastTool = tool.displayName; lastOutput = "Home rig offline — bridge unavailable." }
        }
      }

      item { Spacer(Modifier.height(60.dp)) }
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
fun ToolRow(
  tool: ToolSpec,
  onExecute: () -> Unit
) {
  val affinityColor = when (tool.affinity) {
    ToolAffinity.ANDROID_NATIVE -> EmeraldOnline
    ToolAffinity.PORTABLE -> CyanAccent
    ToolAffinity.REMOTE_BRIDGE -> if (tool.isEnabled) AmberHybrid else RoseOffline
    ToolAffinity.UNAVAILABLE_ANDROID -> RoseOffline
  }

  Surface(
    modifier = Modifier.fillMaxWidth().testTag("tool_${tool.name}"),
    shape = RoundedCornerShape(14.dp),
    color = PurpSurfaceCard,
    border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder.copy(alpha = 0.7f))
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        // icon orb
        Box(
          Modifier
            .size(38.dp)
            .background(Brush.linearGradient(listOf(affinityColor.copy(alpha = 0.25f), PurpDeep)), CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(toolIcon(tool.name), null, tint = affinityColor, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column {
          Text(tool.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
          Text(tool.description, fontSize = 10.5.sp, color = TextSecondary, maxLines = 2)
          Text(
            tool.name,
            fontFamily = FontFamily.Monospace, fontSize = 8.5.sp, color = TextMuted
          )
        }
      }

      Spacer(Modifier.width(8.dp))

      Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (tool.isEnabled) affinityColor.copy(alpha = 0.16f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (tool.isEnabled) affinityColor else PurpBorder),
        modifier = Modifier.testTag("run_${tool.name}"),
        onClick = onExecute,
        enabled = tool.isEnabled
      ) {
        Row(
          Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Default.PlayArrow, null, tint = if (tool.isEnabled) affinityColor else TextMuted, modifier = Modifier.size(13.dp))
          Spacer(Modifier.width(3.dp))
          Text("Run", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = if (tool.isEnabled) affinityColor else TextMuted)
        }
      }
    }
  }
}
