package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.CanvasArtifact
import com.example.core.model.CanvasConflict
import com.example.core.model.CanvasEdge
import com.example.core.model.CanvasEvent
import com.example.core.model.CanvasNode
import com.example.core.model.CanvasNodeStatus
import com.example.core.model.CanvasNodeType
import com.example.core.model.CanvasPresence
import com.example.core.model.CanvasRecord
import com.example.core.model.CanvasSessionLink
import com.example.core.model.CrossSessionMessage
import com.example.core.model.ExecutionLease
import com.example.core.model.InteractionMode
import com.example.core.model.SessionHandoffCapsule
import com.example.core.runtime.CanvasManager
import com.example.core.runtime.CanvasViewMode
import com.example.ui.theme.AmberHybrid
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.LeaseActiveGold
import com.example.ui.theme.PurpBorder
import com.example.ui.theme.PurpDeep
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpPrimary
import com.example.ui.theme.PurpPrimaryDark
import com.example.ui.theme.PurpSurface
import com.example.ui.theme.PurpSurfaceCard
import com.example.ui.theme.PurpSurfaceElevated
import com.example.ui.theme.PurpVoid
import com.example.ui.theme.RoseOffline
import com.example.ui.theme.TextHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasScreen(
  canvasManager: CanvasManager,
  interactionMode: InteractionMode,
  activeLease: ExecutionLease?,
  onArmLease: () -> Unit,
  onRevokeLease: () -> Unit,
  modifier: Modifier = Modifier
) {
  val activeCanvasId by canvasManager.activeCanvasId.collectAsState()
  val activeViewMode by canvasManager.activeViewMode.collectAsState()
  val canvases by canvasManager.canvases.collectAsState()
  val linkedSessions by canvasManager.linkedSessions.collectAsState()
  val nodes by canvasManager.nodes.collectAsState()
  val edges by canvasManager.edges.collectAsState()
  val artifacts by canvasManager.artifacts.collectAsState()
  val presenceList by canvasManager.presenceList.collectAsState()
  val events by canvasManager.events.collectAsState()
  val handoffs by canvasManager.handoffs.collectAsState()
  val conflicts by canvasManager.conflicts.collectAsState()
  val crossSessionMessages by canvasManager.crossSessionMessages.collectAsState()
  val selectedNodeIds by canvasManager.selectedNodeIds.collectAsState()
  val selectedArtifactIds by canvasManager.selectedArtifactIds.collectAsState()

  val currentCanvas = canvases.find { it.canvasId == activeCanvasId } ?: canvases.first()
  val isHomeOnline = presenceList.find { it.actorId == "node_home_pc" }?.state == "online"

  // Dialog States
  var showAddNodeDialog by remember { mutableStateOf(false) }
  var showHandoffDialog by remember { mutableStateOf(false) }
  var showCrossSendDialog by remember { mutableStateOf(false) }
  var inspectedNode by remember { mutableStateOf<CanvasNode?>(null) }
  var inspectedArtifact by remember { mutableStateOf<CanvasArtifact?>(null) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(PurpVoid)
      .testTag("canvas_screen")
  ) {

    // 1. Shared Mission Canvas Header (Top Level Identity)
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp),
      colors = CardDefaults.cardColors(containerColor = PurpSurfaceElevated),
      shape = RoundedCornerShape(16.dp),
      border = BorderStroke(1.dp, PurpBorder)
    ) {
      Column(modifier = Modifier.padding(12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PurpDeep)
                .border(1.dp, PurpNeon.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
              contentAlignment = Alignment.Center
            ) {
              Text("🟣", fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  text = "CANVAS: ${currentCanvas.title}",
                  fontSize = 13.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = TextPrimary,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
              Text(
                text = "${linkedSessions.size} Sessions • ${nodes.size} Nodes • ${artifacts.size} Artifacts • Clock: ${currentCanvas.logicalClock}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
              )
            }
          }

          // Node Mesh Status & Failover test toggle
          Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = if (isHomeOnline) EmeraldOnline.copy(alpha = 0.15f) else AmberHybrid.copy(alpha = 0.2f),
              border = BorderStroke(1.dp, if (isHomeOnline) EmeraldOnline else AmberHybrid)
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Box(
                  modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isHomeOnline) EmeraldOnline else AmberHybrid)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                  text = if (isHomeOnline) "PC ONLINE" else "PHONE SOVEREIGN",
                  fontSize = 9.5.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                  color = if (isHomeOnline) EmeraldOnline else AmberHybrid
                )
              }
            }

            Spacer(modifier = Modifier.width(6.dp))
            // Button to toggle Home PC drop/reconnect for offline takeover testing
            IconButton(
              onClick = {
                if (isHomeOnline) canvasManager.triggerHomePcDisconnect() else canvasManager.triggerHomePcReconnect()
              },
              modifier = Modifier.size(30.dp)
            ) {
              Icon(
                imageVector = if (isHomeOnline) Icons.Default.WifiOff else Icons.Default.Sync,
                contentDescription = "Toggle Home PC Node",
                tint = if (isHomeOnline) RoseOffline else EmeraldOnline,
                modifier = Modifier.size(16.dp)
              )
            }
          }
        }

        // Linked Sessions Bar (A Canvas spans multiple sessions)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "LINKED SESSIONS:",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = TextMuted
          )
          linkedSessions.forEach { session ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = PurpSurfaceCard,
              border = BorderStroke(0.5.dp, PurpBorder),
              modifier = Modifier.clickable {
                canvasManager.sendCrossSessionMessage(
                  sourceSessionId = "ses_ui_01",
                  targetSessionId = session.sessionId,
                  targetType = "SESSION",
                  content = "Switched active context to ${session.domain}"
                )
              }
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Box(
                  modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(PurpNeon)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = session.domain,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.Medium,
                  color = TextPrimary
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                  text = "[${session.turnCount}]",
                  fontSize = 8.sp,
                  fontFamily = FontFamily.Monospace,
                  color = CyanAccent
                )
              }
            }
          }
        }

        // Selected Context Chip Bar (When objects are clicked for bounded AI questioning)
        if (selectedNodeIds.isNotEmpty() || selectedArtifactIds.isNotEmpty()) {
          Spacer(modifier = Modifier.height(8.dp))
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = PurpDeep.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.4f))
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "BOUNDED CONTEXT: ${selectedNodeIds.size} nodes, ${selectedArtifactIds.size} artifacts selected",
                  fontSize = 9.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                  color = CyanAccent
                )
              }
              Row {
                TextButton(
                  onClick = { showCrossSendDialog = true },
                  contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                  Text("Ask PurpClaw", fontSize = 9.sp, color = PurpNeon, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { canvasManager.clearSelection() }, modifier = Modifier.size(20.dp)) {
                  Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(12.dp))
                }
              }
            }
          }
        }
      }
    }

    // 2. View Mode Tabs (Graph, Board, Chat, Timeline, Presence, Artifacts, Reconciliation)
    ScrollableTabRow(
      selectedTabIndex = activeViewMode.ordinal,
      containerColor = PurpSurface,
      contentColor = PurpNeon,
      edgePadding = 12.dp,
      indicator = { tabPositions ->
        TabRowDefaults.SecondaryIndicator(
          modifier = Modifier.tabIndicatorOffset(tabPositions[activeViewMode.ordinal]),
          color = PurpNeon,
          height = 2.5.dp
        )
      },
      divider = { HorizontalDivider(color = PurpBorder, thickness = 0.5.dp) }
    ) {
      CanvasViewMode.values().forEach { mode ->
        val isSelected = activeViewMode == mode
        Tab(
          selected = isSelected,
          onClick = { canvasManager.setViewMode(mode) },
          text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(text = mode.iconEmoji, fontSize = 11.sp)
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = mode.label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) PurpNeon else TextMuted
              )
              if (mode == CanvasViewMode.RECONCILIATION && conflicts.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                  modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(RoseOffline)
                )
              }
            }
          },
          modifier = Modifier.testTag("tab_canvas_${mode.name.lowercase()}")
        )
      }
    }

    // 3. Main Dynamic Content Switcher
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
    ) {
      when (activeViewMode) {
        CanvasViewMode.GRAPH -> {
          WorkflowGraphView(
            nodes = nodes,
            edges = edges,
            selectedNodeIds = selectedNodeIds,
            onToggleNode = { canvasManager.toggleNodeSelection(it) },
            onInspectNode = { inspectedNode = it },
            onAddNodeClick = { showAddNodeDialog = true },
            onSteer = { id, act -> canvasManager.steerRemoteNode(id, act) }
          )
        }
        CanvasViewMode.BOARD -> {
          MissionBoardView(
            nodes = nodes,
            onInspectNode = { inspectedNode = it },
            onAddNode = { showAddNodeDialog = true },
            onUpdateStatus = { id, st -> canvasManager.updateNodeStatus(id, st) }
          )
        }
        CanvasViewMode.CHAT -> {
          CrossSessionChatView(
            messages = crossSessionMessages,
            linkedSessions = linkedSessions,
            selectedNodeIds = selectedNodeIds,
            selectedArtifactIds = selectedArtifactIds,
            onSendMessage = { src, tgt, typ, content, isDec ->
              canvasManager.sendCrossSessionMessage(src, tgt, typ, content, isDec)
            },
            onOpenHandoff = { showHandoffDialog = true }
          )
        }
        CanvasViewMode.PRESENCE -> {
          LivePresenceNodesView(
            presenceList = presenceList,
            nodes = nodes,
            onSteerNode = { id, act -> canvasManager.steerRemoteNode(id, act) }
          )
        }
        CanvasViewMode.ARTIFACTS -> {
          ArtifactsWorkspaceView(
            artifacts = artifacts,
            selectedArtifactIds = selectedArtifactIds,
            onToggleArtifact = { canvasManager.toggleArtifactSelection(it) },
            onInspectArtifact = { inspectedArtifact = it }
          )
        }
        CanvasViewMode.RECONCILIATION -> {
          HandoffReconciliationView(
            handoffs = handoffs,
            conflicts = conflicts,
            events = events,
            onCreateHandoff = { showHandoffDialog = true },
            onResolveConflict = { id, res -> canvasManager.resolveConflict(id, res) }
          )
        }
        CanvasViewMode.TIMELINE -> {
          EventTimelineView(events = events)
        }
      }
    }
  }

  // --- MODALS & DIALOGS ---

  // 1. Add Node Dialog
  if (showAddNodeDialog) {
    AddWorkflowNodeDialog(
      onDismiss = { showAddNodeDialog = false },
      onAddNode = { title, type, agent, node, parent ->
        canvasManager.addWorkflowNode(title, type, agent, node, parent)
        showAddNodeDialog = false
      },
      existingNodes = nodes
    )
  }

  // 2. Inspect Node Bottom Sheet / Dialog
  inspectedNode?.let { node ->
    NodeInspectionSheet(
      node = node,
      interactionMode = interactionMode,
      activeLease = activeLease,
      onDismiss = { inspectedNode = null },
      onSteerAction = { action ->
        canvasManager.steerRemoteNode(node.nodeId, action)
        inspectedNode = null
      },
      onArmLease = onArmLease
    )
  }

  // 3. Inspect Artifact Dialog
  inspectedArtifact?.let { art ->
    ArtifactInspectionDialog(
      artifact = art,
      onDismiss = { inspectedArtifact = null },
      onAskAbout = {
        canvasManager.toggleArtifactSelection(art.artifactId)
        canvasManager.setViewMode(CanvasViewMode.CHAT)
        inspectedArtifact = null
      }
    )
  }

  // 4. Create Session Handoff Dialog
  if (showHandoffDialog) {
    CreateHandoffDialog(
      linkedSessions = linkedSessions,
      onDismiss = { showHandoffDialog = false },
      onSubmit = { src, tgt, obj, state, next ->
        canvasManager.createSessionHandoff(src, tgt, obj, state, next)
        showHandoffDialog = false
      }
    )
  }

  // 5. Ask PurpClaw (Bounded Context) Dialog
  if (showCrossSendDialog) {
    BoundedContextPromptDialog(
      selectedNodeCount = selectedNodeIds.size,
      selectedArtifactCount = selectedArtifactIds.size,
      onDismiss = { showCrossSendDialog = false },
      onSubmit = { prompt ->
        canvasManager.sendCrossSessionMessage(
          sourceSessionId = "ses_ui_01",
          targetSessionId = "ALL",
          targetType = "SWARM",
          content = prompt,
          isDecision = false
        )
        canvasManager.setViewMode(CanvasViewMode.CHAT)
        showCrossSendDialog = false
      }
    )
  }
}

// =========================================================================
// 1. WORKFLOW GRAPH VIEW (DAG)
// =========================================================================

@Composable
fun WorkflowGraphView(
  nodes: List<CanvasNode>,
  edges: List<CanvasEdge>,
  selectedNodeIds: Set<String>,
  onToggleNode: (String) -> Unit,
  onInspectNode: (CanvasNode) -> Unit,
  onAddNodeClick: () -> Unit,
  onSteer: (String, String) -> Unit,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(PurpVoid)
      .testTag("workflow_graph_view")
  ) {

    // Graph Visual Background with Nodes & Connecting Lines
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 14.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = "EXECUTION WORKFLOW DAG",
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = CyanAccent
            )
            Text(
              text = "Live dependencies & execution authority • Tap node to select / steer",
              fontSize = 10.sp,
              color = TextMuted
            )
          }
          OutlinedButton(
            onClick = onAddNodeClick,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = PurpNeon),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
          ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Node", fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
        }
      }

      items(nodes, key = { it.nodeId }) { node ->
        val isSelected = selectedNodeIds.contains(node.nodeId)
        val statusColor = Color(node.status.colorCode)
        val typeColor = Color(node.type.colorCode)

        // Show dependency indicator link if depends on previous node
        if (node.dependencies.isNotEmpty()) {
          Row(
            modifier = Modifier
              .padding(start = 24.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .width(2.dp)
                .height(14.dp)
                .background(PurpBorder)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "↳ Depends on: ${node.dependencies.joinToString(", ")}",
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace,
              color = TextMuted
            )
          }
        }

        Card(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onInspectNode(node) }
            .testTag("node_card_${node.nodeId}"),
          colors = CardDefaults.cardColors(
            containerColor = if (isSelected) PurpSurfaceElevated else PurpSurfaceCard
          ),
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) CyanNeon else PurpBorder
          )
        ) {
          Column(modifier = Modifier.padding(12.dp)) {

            // Top metadata row
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                // Type badge
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = typeColor.copy(alpha = 0.15f),
                  border = BorderStroke(0.5.dp, typeColor)
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(text = node.type.iconEmoji, fontSize = 10.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                      text = node.type.label.uppercase(),
                      fontSize = 8.5.sp,
                      fontFamily = FontFamily.Monospace,
                      fontWeight = FontWeight.Bold,
                      color = typeColor
                    )
                  }
                }

                Spacer(modifier = Modifier.width(6.dp))
                // Node platform badge
                Text(
                  text = "📍 ${node.assignedNode}",
                  fontSize = 9.sp,
                  fontFamily = FontFamily.Monospace,
                  color = if (node.assignedNode.contains("Home")) EmeraldOnline else PurpNeon
                )
              }

              // Status Pill
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusColor.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, statusColor)
              ) {
                Text(
                  text = node.status.label,
                  fontSize = 9.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                  color = statusColor,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title & Description
            Text(
              text = node.title,
              fontSize = 13.5.sp,
              fontWeight = FontWeight.SemiBold,
              color = TextPrimary
            )

            node.resultSummary?.let { summary ->
              Spacer(modifier = Modifier.height(3.dp))
              Text(
                text = summary,
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 15.sp
              )
            }

            // Live telemetry (e.g. if compiling / running)
            if (node.status == CanvasNodeStatus.RUNNING || node.filesChanged > 0) {
              Spacer(modifier = Modifier.height(6.dp))
              Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = PurpVoid,
                border = BorderStroke(0.5.dp, PurpBorder)
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = PurpNeon, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                      text = "Agent: ${node.assignedAgent} • ${node.callCount} calls • ${node.filesChanged} files",
                      fontSize = 9.5.sp,
                      fontFamily = FontFamily.Monospace,
                      color = TextPrimary
                    )
                  }
                  Text(
                    text = "${node.durationMs / 1000}s",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CyanAccent
                  )
                }
              }
            }

            // Action footer
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                  onClick = { onToggleNode(node.nodeId) },
                  modifier = Modifier.size(24.dp)
                ) {
                  Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Link,
                    contentDescription = "Select context",
                    tint = if (isSelected) CyanNeon else TextMuted,
                    modifier = Modifier.size(14.dp)
                  )
                }
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                  text = if (isSelected) "Selected Context" else "Select Context",
                  fontSize = 9.5.sp,
                  color = if (isSelected) CyanNeon else TextMuted
                )
              }

              // Remote Steer Controls
              Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (node.status == CanvasNodeStatus.RUNNING) {
                  OutlinedButton(
                    onClick = { onSteer(node.nodeId, "PAUSE") },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberHybrid),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                  ) {
                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(11.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Pause", fontSize = 9.5.sp)
                  }
                } else if (node.status == CanvasNodeStatus.BLOCKED || node.status == CanvasNodeStatus.WAITING_FOR_HOME) {
                  Button(
                    onClick = { onSteer(node.nodeId, "RESUME") },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldOnline),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                  ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(11.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Resume", fontSize = 9.5.sp)
                  }
                } else if (node.status == CanvasNodeStatus.WAITING_FOR_APPROVAL) {
                  Button(
                    onClick = { onSteer(node.nodeId, "APPROVE") },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PurpNeon),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                  ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(11.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Arm & Run", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                  }
                }
              }
            }
          }
        }
      }

      item {
        Spacer(modifier = Modifier.height(20.dp))
      }
    }
  }
}

// =========================================================================
// 2. MISSION BOARD VIEW (KANBAN)
// =========================================================================

@Composable
fun MissionBoardView(
  nodes: List<CanvasNode>,
  onInspectNode: (CanvasNode) -> Unit,
  onAddNode: () -> Unit,
  onUpdateStatus: (String, CanvasNodeStatus) -> Unit,
  modifier: Modifier = Modifier
) {
  val columns = listOf(
    Pair("TODO", CanvasNodeStatus.TODO),
    Pair("RUNNING", CanvasNodeStatus.RUNNING),
    Pair("APPROVAL / BLOCKED", CanvasNodeStatus.WAITING_FOR_APPROVAL),
    Pair("VERIFY", CanvasNodeStatus.VERIFY),
    Pair("DONE", CanvasNodeStatus.DONE)
  )

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(12.dp)
      .testTag("mission_board_view")
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "COLLABORATIVE MISSION BOARD",
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = PurpNeon
      )
      IconButton(onClick = onAddNode, modifier = Modifier.size(28.dp)) {
        Icon(Icons.Default.Add, contentDescription = "Add Task", tint = PurpNeon)
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    LazyRow(
      modifier = Modifier.fillMaxSize(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      items(columns) { (colTitle, colStatus) ->
        val colNodes = nodes.filter {
          if (colStatus == CanvasNodeStatus.WAITING_FOR_APPROVAL) {
            it.status == CanvasNodeStatus.WAITING_FOR_APPROVAL || it.status == CanvasNodeStatus.BLOCKED || it.status == CanvasNodeStatus.WAITING_FOR_HOME
          } else {
            it.status == colStatus
          }
        }

        Card(
          modifier = Modifier
            .width(260.dp)
            .fillMaxHeight(),
          colors = CardDefaults.cardColors(containerColor = PurpSurface),
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, PurpBorder)
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            // Column Header
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = colTitle,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(colStatus.colorCode)
              )
              Surface(
                shape = CircleShape,
                color = PurpSurfaceCard
              ) {
                Text(
                  text = "${colNodes.size}",
                  fontSize = 9.sp,
                  fontFamily = FontFamily.Monospace,
                  color = TextPrimary,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
              }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = PurpBorder, thickness = 0.5.dp)

            // Cards in column
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              items(colNodes) { node ->
                Card(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onInspectNode(node) },
                  colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
                  shape = RoundedCornerShape(10.dp),
                  border = BorderStroke(0.5.dp, PurpBorder)
                ) {
                  Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                      text = node.title,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Medium,
                      color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = "🤖 ${node.assignedAgent}",
                        fontSize = 9.sp,
                        color = PurpNeon
                      )
                      Text(
                        text = "📍 ${node.assignedNode.take(8)}",
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

// =========================================================================
// 3. CROSS-SESSION CHAT VIEW (UNIFIED CONVERSATION STREAM)
// =========================================================================

@Composable
fun CrossSessionChatView(
  messages: List<CrossSessionMessage>,
  linkedSessions: List<CanvasSessionLink>,
  selectedNodeIds: Set<String>,
  selectedArtifactIds: Set<String>,
  onSendMessage: (String, String, String, String, Boolean) -> Unit,
  onOpenHandoff: () -> Unit,
  modifier: Modifier = Modifier
) {
  var chatInput by remember { mutableStateOf("") }
  var filterTarget by remember { mutableStateOf("ALL") }
  var selectedTargetType by remember { mutableStateOf("SESSION") }
  var selectedTargetSessionId by remember { mutableStateOf("ses_ui_01") }

  val filteredMessages = messages.filter {
    when (filterTarget) {
      "ALL" -> true
      "DECISIONS" -> it.isDecision
      "EXECUTION" -> it.isExecutionHandoff
      else -> it.sourceSessionId == filterTarget || it.targetSessionId == filterTarget
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(12.dp)
      .testTag("cross_session_chat_view")
  ) {

    // Filter Chips
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      listOf("ALL", "DECISIONS", "EXECUTION")
        .plus(linkedSessions.map { it.sessionId })
        .forEach { chip ->
          val isSelected = filterTarget == chip
          FilterChip(
            selected = isSelected,
            onClick = { filterTarget = chip },
            label = {
              Text(
                text = if (chip == "ALL" || chip == "DECISIONS" || chip == "EXECUTION") chip else linkedSessions.find { it.sessionId == chip }?.domain ?: chip,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
              )
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = PurpDeep,
              selectedLabelColor = PurpNeon,
              containerColor = PurpSurfaceCard,
              labelColor = TextMuted
            ),
            border = BorderStroke(0.5.dp, if (isSelected) PurpNeon else PurpBorder)
          )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Messages Stream
    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      items(filteredMessages) { msg ->
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(
            containerColor = if (msg.senderActor.contains("Operator")) PurpDeep.copy(alpha = 0.5f) else PurpSurfaceCard
          ),
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(0.5.dp, if (msg.isDecision) EmeraldOnline.copy(alpha = 0.6f) else PurpBorder)
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  text = msg.senderActor,
                  fontSize = 10.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                  color = if (msg.senderActor.contains("Operator")) CyanNeon else PurpNeon
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "→ [${msg.targetType}: ${msg.targetSessionId}]",
                  fontSize = 9.sp,
                  fontFamily = FontFamily.Monospace,
                  color = TextMuted
                )
              }
              if (msg.isDecision) {
                Surface(
                  shape = RoundedCornerShape(4.dp),
                  color = EmeraldOnline.copy(alpha = 0.15f),
                  border = BorderStroke(0.5.dp, EmeraldOnline)
                ) {
                  Text(
                    text = "DECISION RECORD",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = EmeraldOnline,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = msg.content.takeIf { it.isNotBlank() && it.lowercase() != "null" }
                ?: "(No response received — the assistant produced empty output)",
              fontSize = 12.5.sp,
              color = TextPrimary,
              lineHeight = 17.sp
            )

            // Bounded Context Tags
            if (msg.boundedContextNodeIds.isNotEmpty() || msg.boundedContextArtifactIds.isNotEmpty()) {
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "📎 Attached Context: ${msg.boundedContextNodeIds.joinToString(", ")}",
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                color = CyanAccent
              )
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Cross-Session Send Bar
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = PurpSurfaceElevated),
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, PurpBorder)
    ) {
      Column(modifier = Modifier.padding(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Target:", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "$selectedTargetType ($selectedTargetSessionId)",
              fontSize = 9.5.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = PurpNeon
            )
          }
          TextButton(
            onClick = onOpenHandoff,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
          ) {
            Text("+ Structured Handoff", fontSize = 9.5.sp, color = CyanAccent)
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          OutlinedTextField(
            value = chatInput,
            onValueChange = { chatInput = it },
            placeholder = { Text("Send cross-session message or instruction...", fontSize = 11.sp, color = TextMuted) },
            modifier = Modifier
              .weight(1f)
              .testTag("cross_chat_input"),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = PurpNeon,
              unfocusedBorderColor = PurpBorder,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            ),
            singleLine = true
          )
          Spacer(modifier = Modifier.width(8.dp))
          IconButton(
            onClick = {
              if (chatInput.isNotBlank()) {
                onSendMessage(
                  "ses_ui_01",
                  selectedTargetSessionId,
                  selectedTargetType,
                  chatInput,
                  chatInput.contains("decision", ignoreCase = true)
                )
                chatInput = ""
              }
            },
            modifier = Modifier
              .size(44.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(PurpNeon)
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(18.dp))
          }
        }
      }
    }
  }
}

// =========================================================================
// 4. LIVE PRESENCE & REMOTE NODES VIEW
// =========================================================================

@Composable
fun LivePresenceNodesView(
  presenceList: List<CanvasPresence>,
  nodes: List<CanvasNode>,
  onSteerNode: (String, String) -> Unit,
  modifier: Modifier = Modifier
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Text(
        text = "CANVAS PRESENCE & REMOTE RUNTIME MESH",
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = PurpNeon
      )
      Text(
        text = "Real-time state, heartbeats & remote steering for PC, Phone, and Agents",
        fontSize = 10.sp,
        color = TextMuted
      )
    }

    items(presenceList) { pres ->
      val isOnline = pres.state == "online" || pres.state == "executing" || pres.state == "verifying"
      val stateColor = when (pres.state) {
        "executing" -> PurpNeon
        "online" -> EmeraldOnline
        "verifying" -> CyanNeon
        "waiting" -> AmberHybrid
        else -> RoseOffline
      }

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, PurpBorder)
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(32.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(PurpDeep),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = when (pres.type) {
                    "HOME_PC" -> "🖥️"
                    "OPERATOR" -> "👤"
                    "OMNI" -> "🔎"
                    else -> "🤖"
                  },
                  fontSize = 15.sp
                )
              }
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  text = pres.name,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
                  color = TextPrimary
                )
                Text(
                  text = "${pres.nodePlatform} • Latency: ${pres.latencyMs}ms",
                  fontSize = 9.sp,
                  fontFamily = FontFamily.Monospace,
                  color = TextSecondary
                )
              }
            }

            Surface(
              shape = RoundedCornerShape(12.dp),
              color = stateColor.copy(alpha = 0.15f),
              border = BorderStroke(0.5.dp, stateColor)
            ) {
              Text(
                text = pres.state.uppercase(),
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = stateColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))
          // Current Task
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = PurpVoid,
            border = BorderStroke(0.5.dp, PurpBorder)
          ) {
            Text(
              text = "Current Activity: ${pres.currentTask}",
              fontSize = 10.5.sp,
              fontFamily = FontFamily.Monospace,
              color = CyanAccent,
              modifier = Modifier.padding(8.dp)
            )
          }

          // Capabilities
          Spacer(modifier = Modifier.height(6.dp))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            pres.capabilities.forEach { cap ->
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = PurpSurfaceElevated
              ) {
                Text(
                  text = cap,
                  fontSize = 8.sp,
                  fontFamily = FontFamily.Monospace,
                  color = TextMuted,
                  modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
              }
            }
          }
        }
      }
    }
  }
}

// =========================================================================
// 5. ARTIFACTS WORKSPACE VIEW
// =========================================================================

@Composable
fun ArtifactsWorkspaceView(
  artifacts: List<CanvasArtifact>,
  selectedArtifactIds: Set<String>,
  onToggleArtifact: (String) -> Unit,
  onInspectArtifact: (CanvasArtifact) -> Unit,
  modifier: Modifier = Modifier
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Text(
        text = "SHARED ARTIFACTS WORKSPACE",
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = PurpNeon
      )
      Text(
        text = "Build outputs, verification receipts & logs shared across all sessions",
        fontSize = 10.sp,
        color = TextMuted
      )
    }

    items(artifacts) { art ->
      val isSelected = selectedArtifactIds.contains(art.artifactId)

      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onInspectArtifact(art) },
        colors = CardDefaults.cardColors(
          containerColor = if (isSelected) PurpSurfaceElevated else PurpSurfaceCard
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
          if (isSelected) 1.5.dp else 1.dp,
          if (isSelected) CyanNeon else PurpBorder
        )
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = when (art.type) {
                  "BUILD_APK" -> "📦"
                  "REPORT" -> "📊"
                  "RECEIPT" -> "🛡️"
                  else -> "📄"
                },
                fontSize = 16.sp
              )
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text(
                  text = art.title,
                  fontSize = 12.5.sp,
                  fontWeight = FontWeight.Bold,
                  color = TextPrimary
                )
                Text(
                  text = art.uri,
                  fontSize = 9.sp,
                  fontFamily = FontFamily.Monospace,
                  color = CyanAccent
                )
              }
            }

            IconButton(
              onClick = { onToggleArtifact(art.artifactId) },
              modifier = Modifier.size(24.dp)
            ) {
              Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Link,
                contentDescription = "Select artifact context",
                tint = if (isSelected) CyanNeon else TextMuted,
                modifier = Modifier.size(16.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(6.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Size: ${art.sizeBytes / 1024} KB • Hash: ${art.checksumSha256}",
              fontSize = 8.5.sp,
              fontFamily = FontFamily.Monospace,
              color = TextMuted
            )
            Text(
              text = "Lineage: ${art.versionLineage}",
              fontSize = 8.5.sp,
              fontFamily = FontFamily.Monospace,
              color = EmeraldOnline
            )
          }
        }
      }
    }
  }
}

// =========================================================================
// 6. HANDOFF & RECONCILIATION VIEW
// =========================================================================

@Composable
fun HandoffReconciliationView(
  handoffs: List<SessionHandoffCapsule>,
  conflicts: List<CanvasConflict>,
  events: List<CanvasEvent>,
  onCreateHandoff: () -> Unit,
  onResolveConflict: (String, String) -> Unit,
  modifier: Modifier = Modifier
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {

    // Conflict Resolution Console (Never database overwrite roulette!)
    if (conflicts.isNotEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = PurpDeep),
          shape = RoundedCornerShape(14.dp),
          border = BorderStroke(1.dp, AmberHybrid)
        ) {
          Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Warning, contentDescription = null, tint = AmberHybrid, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "CONCURRENCY CONFLICT DETECTED (${conflicts.size})",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = AmberHybrid
              )
            }
            Text(
              text = "PC and Mobile nodes diverged while offline. Select resolution strategy below:",
              fontSize = 10.sp,
              color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            conflicts.forEach { conflict ->
              Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
                shape = RoundedCornerShape(8.dp)
              ) {
                Column(modifier = Modifier.padding(8.dp)) {
                  Text(
                    text = "Node: ${conflict.nodeTitle} (Field: ${conflict.fieldKey})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text("🖥️ Home PC Version:", fontSize = 9.sp, color = CyanAccent)
                      Text(conflict.pcValue, fontSize = 10.sp, color = TextPrimary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                      Text("📱 Phone Version:", fontSize = 9.sp, color = PurpNeon)
                      Text(conflict.phoneValue, fontSize = 10.sp, color = TextPrimary)
                    }
                  }
                  Spacer(modifier = Modifier.height(8.dp))
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    OutlinedButton(
                      onClick = { onResolveConflict(conflict.conflictId, "KEEP_PC") },
                      shape = RoundedCornerShape(6.dp),
                      contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                      Text("Keep PC", fontSize = 9.5.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedButton(
                      onClick = { onResolveConflict(conflict.conflictId, "KEEP_PHONE") },
                      shape = RoundedCornerShape(6.dp),
                      contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                      Text("Keep Phone", fontSize = 9.5.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(
                      onClick = { onResolveConflict(conflict.conflictId, "MERGE") },
                      shape = RoundedCornerShape(6.dp),
                      colors = ButtonDefaults.buttonColors(containerColor = EmeraldOnline),
                      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                      Text("Merge", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    // Structured Session Handoffs
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "STRUCTURED SESSION HANDOFFS",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = PurpNeon
          )
          Text(
            text = "First-class context capsules across Home PC and Android",
            fontSize = 10.sp,
            color = TextMuted
          )
        }
        OutlinedButton(
          onClick = onCreateHandoff,
          shape = RoundedCornerShape(8.dp),
          colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
          Spacer(modifier = Modifier.width(3.dp))
          Text("New Capsule", fontSize = 10.sp)
        }
      }
    }

    items(handoffs) { ho ->
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, PurpBorder)
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "HANDOFF: ${ho.sourceNode} → ${ho.targetNode}",
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = CyanAccent
            )
            Text(
              text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ho.timestamp)),
              fontSize = 9.sp,
              fontFamily = FontFamily.Monospace,
              color = TextMuted
            )
          }

          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Objective: ${ho.objective}",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
          )

          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Current State: ${ho.currentState}",
            fontSize = 11.sp,
            color = TextSecondary
          )

          Spacer(modifier = Modifier.height(6.dp))
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
            color = PurpVoid
          ) {
            Column(modifier = Modifier.padding(6.dp)) {
              Text(
                text = "Next Action: ${ho.nextRecommendedAction}",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                color = EmeraldOnline
              )
              Text(
                text = "Active Agents: ${ho.activeAgents.joinToString(", ")}",
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted
              )
            }
          }
        }
      }
    }
  }
}

// =========================================================================
// 7. EVENT TIMELINE VIEW
// =========================================================================

@Composable
fun EventTimelineView(
  events: List<CanvasEvent>,
  modifier: Modifier = Modifier
) {
  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    item {
      Text(
        text = "CANVAS EVENT SPINE TIMELINE",
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = PurpNeon
      )
      Text(
        text = "Deterministic, append-only event stream with logical clock provenance",
        fontSize = 10.sp,
        color = TextMuted
      )
    }

    items(events.reversed()) { ev ->
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.5.dp, PurpBorder)
      ) {
        Column(modifier = Modifier.padding(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "Clock: #${ev.logicalClock}",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = CyanAccent
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = ev.operation.name,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = PurpNeon
              )
            }
            Text(
              text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ev.timestamp)),
              fontSize = 8.5.sp,
              fontFamily = FontFamily.Monospace,
              color = TextMuted
            )
          }

          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = ev.payloadSummary,
            fontSize = 11.5.sp,
            color = TextPrimary
          )

          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = "Actor: ${ev.actor} • Node: ${ev.sourceNode} • Hash: ${ev.payloadHash}",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = TextMuted
          )
        }
      }
    }
  }
}

// =========================================================================
// MODALS & DIALOG IMPLEMENTATIONS
// =========================================================================

@Composable
fun AddWorkflowNodeDialog(
  onDismiss: () -> Unit,
  onAddNode: (String, CanvasNodeType, String, String, String?) -> Unit,
  existingNodes: List<CanvasNode>
) {
  var title by remember { mutableStateOf("") }
  var selectedType by remember { mutableStateOf(CanvasNodeType.TASK) }
  var assignedAgent by remember { mutableStateOf("Hermes Codex") }
  var assignedNode by remember { mutableStateOf("Home PC") }
  var parentNodeId by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text("Add Workflow Node", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Node Title") },
          modifier = Modifier.fillMaxWidth()
        )

        Text("Node Type:", fontSize = 11.sp, color = TextMuted)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          CanvasNodeType.values().take(6).forEach { type ->
            FilterChip(
              selected = selectedType == type,
              onClick = { selectedType = type },
              label = { Text(type.label, fontSize = 9.sp) }
            )
          }
        }

        OutlinedTextField(
          value = assignedAgent,
          onValueChange = { assignedAgent = it },
          label = { Text("Assigned Agent") },
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = assignedNode,
          onValueChange = { assignedNode = it },
          label = { Text("Assigned Node (Home PC / Android)") },
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (title.isNotBlank()) {
            onAddNode(title, selectedType, assignedAgent, assignedNode, parentNodeId)
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = PurpNeon)
      ) {
        Text("Add to DAG")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
    containerColor = PurpSurfaceElevated
  )
}

@Composable
fun NodeInspectionSheet(
  node: CanvasNode,
  interactionMode: InteractionMode,
  activeLease: ExecutionLease?,
  onDismiss: () -> Unit,
  onSteerAction: (String) -> Unit,
  onArmLease: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(node.type.iconEmoji, fontSize = 18.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(node.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
      }
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Text("Status: ${node.status.label}", fontSize = 11.sp, color = Color(node.status.colorCode), fontWeight = FontWeight.Bold)
        Text("Assigned Node: ${node.assignedNode}", fontSize = 11.sp, color = TextPrimary)
        Text("Assigned Agent: ${node.assignedAgent}", fontSize = 11.sp, color = PurpNeon)
        Text("Provider/Model: ${node.providerModel}", fontSize = 11.sp, color = CyanAccent)
        Text("Execution Authority: ${node.executionAuthority}", fontSize = 11.sp, color = LeaseActiveGold)

        node.resultSummary?.let {
          Spacer(modifier = Modifier.height(4.dp))
          Text("Result Summary:", fontSize = 10.sp, color = TextMuted)
          Text(it, fontSize = 11.sp, color = TextPrimary)
        }

        if (node.evidenceDigest != null) {
          Spacer(modifier = Modifier.height(4.dp))
          Text("Evidence Digest:", fontSize = 10.sp, color = TextMuted)
          Text(node.evidenceDigest, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = EmeraldOnline)
        }
      }
    },
    confirmButton = {
      Row {
        if (node.status == CanvasNodeStatus.RUNNING) {
          OutlinedButton(onClick = { onSteerAction("PAUSE") }) {
            Text("Pause")
          }
        } else if (node.status == CanvasNodeStatus.WAITING_FOR_APPROVAL) {
          Button(
            onClick = { onSteerAction("APPROVE") },
            colors = ButtonDefaults.buttonColors(containerColor = PurpNeon)
          ) {
            Text("Arm & Run")
          }
        }
        Spacer(modifier = Modifier.width(6.dp))
        TextButton(onClick = onDismiss) {
          Text("Close")
        }
      }
    },
    containerColor = PurpSurfaceElevated
  )
}

@Composable
fun ArtifactInspectionDialog(
  artifact: CanvasArtifact,
  onDismiss: () -> Unit,
  onAskAbout: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(artifact.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("URI: ${artifact.uri}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = CyanAccent)
        Text("Size: ${artifact.sizeBytes / 1024} KB", fontSize = 10.sp, color = TextPrimary)
        Text("Checksum SHA-256: ${artifact.checksumSha256}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = EmeraldOnline)
        Text("Version Lineage: ${artifact.versionLineage}", fontSize = 10.sp, color = TextSecondary)
      }
    },
    confirmButton = {
      Button(onClick = onAskAbout, colors = ButtonDefaults.buttonColors(containerColor = PurpNeon)) {
        Text("Ask PurpClaw with this Artifact")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Close") }
    },
    containerColor = PurpSurfaceElevated
  )
}

@Composable
fun CreateHandoffDialog(
  linkedSessions: List<CanvasSessionLink>,
  onDismiss: () -> Unit,
  onSubmit: (String, String, String, String, String) -> Unit
) {
  var objective by remember { mutableStateOf("Handoff Android UI findings to Home PC") }
  var currentState by remember { mutableStateOf("All tests passed locally. Room DB migration v2 verified.") }
  var nextAction by remember { mutableStateOf("Assemble release APK on Home PC") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Create Structured Session Handoff", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
          value = objective,
          onValueChange = { objective = it },
          label = { Text("Objective") },
          modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
          value = currentState,
          onValueChange = { currentState = it },
          label = { Text("Current State") },
          modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
          value = nextAction,
          onValueChange = { nextAction = it },
          label = { Text("Next Recommended Action") },
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onSubmit("ses_ui_01", "ses_core_02", objective, currentState, nextAction) },
        colors = ButtonDefaults.buttonColors(containerColor = PurpNeon)
      ) {
        Text("Commit Handoff")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
    containerColor = PurpSurfaceElevated
  )
}

@Composable
fun BoundedContextPromptDialog(
  selectedNodeCount: Int,
  selectedArtifactCount: Int,
  onDismiss: () -> Unit,
  onSubmit: (String) -> Unit
) {
  var prompt by remember { mutableStateOf("Why is the provider routing latency test still failing?") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Ask PurpClaw with Bounded Context", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          text = "Selected Context: $selectedNodeCount nodes, $selectedArtifactCount artifacts attached.",
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          color = CyanAccent
        )
        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it },
          label = { Text("Your question") },
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    confirmButton = {
      Button(onClick = { onSubmit(prompt) }, colors = ButtonDefaults.buttonColors(containerColor = PurpNeon)) {
        Text("Send Query")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
    containerColor = PurpSurfaceElevated
  )
}
