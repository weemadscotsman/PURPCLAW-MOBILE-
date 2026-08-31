@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.model.InteractionMode
import com.example.core.network.HomeRuntimeBridge
import com.example.ui.screens.AiModelsSettingsScreen
import com.example.ui.screens.AuditProofScreen
import com.example.ui.screens.CanvasScreen
import com.example.ui.screens.CommandScreen
import com.example.ui.screens.MissionsScreen
import com.example.ui.screens.PodcastStudioScreen
import com.example.ui.screens.OrganisationScreen
import com.example.ui.screens.SevenLayerMemoryScreen
import com.example.ui.screens.ToolsMeshScreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.PurpBorder
import com.example.ui.theme.PurpDeep
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpSurface
import com.example.ui.theme.PurpSurfaceCard
import com.example.ui.theme.PurpSurfaceElevated
import com.example.ui.theme.PurpVoid
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.navigation.BottomNavVisibility
import com.example.ui.navigation.LiquidBottomNav
import androidx.compose.foundation.layout.ime
import com.example.ui.theme.TextSecondary

@Composable
fun PurpClawApp(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier
) {
  val homeNode by viewModel.meshCoordinator.homeNode.collectAsState()
  val context = androidx.compose.ui.platform.LocalContext.current

  val activeSurface by viewModel.activeSurface.collectAsState()
  val interactionMode by viewModel.interactionMode.collectAsState()
  val selectedCompanion by viewModel.selectedCompanion.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val inputText by viewModel.inputText.collectAsState()
  val pendingMediaAttachments by viewModel.pendingMediaAttachments.collectAsState()
  val isGenerating by viewModel.isGenerating.collectAsState()
  val isListening by viewModel.isListening.collectAsState()
  val activeLease by viewModel.activeLease.collectAsState()

  val turns by viewModel.turns.collectAsState()
  val chatSessions by viewModel.chatSessions.collectAsState()
  val activeSessionId by viewModel.activeSessionId.collectAsState()
  val memoryItems by viewModel.memoryItems.collectAsState()
  val workingMemory by viewModel.memoryGateway.workingMemory.collectAsState()
  val agents by viewModel.agentTower.agents.collectAsState()
  val agentRoster by viewModel.agentRoster.collectAsState()
  val skillRoster by viewModel.skillRoster.collectAsState()
  val missions by viewModel.agentTower.activeMissions.collectAsState()
  val councils by viewModel.agentTower.councils.collectAsState()
  val receipts by viewModel.proofReceipts.collectAsState()
  val snapshots by viewModel.snapshots.collectAsState()
  val vaultItems by viewModel.vaultItems.collectAsState()
  val subsystems by viewModel.subsystems.collectAsState()

  val availableTools = viewModel.toolRuntime.getAvailableToolsList(homeNode.online)

  // Composer model flyout choices: AUTO + free catalogue (OpenRouter-free, MiniMax, NIM).
  // Google lanes purged — never surfaced.
  val openRouterCatalogue by viewModel.providerRouter.openRouterCatalogue.collectAsState()
  val nimCatalogue by viewModel.providerRouter.nimCatalogue.collectAsState()
  val modelChoices: List<Pair<String, String>> = remember(openRouterCatalogue, nimCatalogue) {
    buildList {
      add("AUTO" to "Auto — free router picks best")
      openRouterCatalogue
        .filter { it.isFree && !it.id.contains("gemini", true) && !it.id.startsWith("google/") }
        .filter { m ->
          val lower = m.id.lowercase()
          m.modelClass == "chat" && listOf("guard", "moderation", "classifier", "embed", "rerank", "whisper", "tts").none { lower.contains(it) }
        }
        .take(20)
        .forEach { add(it.id to it.name) }
      nimCatalogue
        .filter { m ->
          val lower = m.id.lowercase()
          m.modelClass == "chat" && listOf("guard", "moderation", "classifier", "embed", "rerank", "whisper", "tts", "clip", "vision-language", "stable-diffusion").none { lower.contains(it) }
        }
        .take(10)
        .forEach { add(it.id to it.name) }
    }
  }

  // CHAT-FIRST LAW: conversation owns the viewport. No fixed header, no tab bar.
  // One slim contextual dock + liquid nav + one action drawer expose every destination.
  val showSystemDrawer = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

  // Liquid navigation — persistently MOUNTED, not persistently visible.
  // Single visibility authority shared by scroll/idle/IME/drawer inputs.
  val bottomNavVisibility = androidx.compose.runtime.remember { BottomNavVisibility() }
  val imeInsets = androidx.compose.foundation.layout.WindowInsets.ime
  val density = androidx.compose.ui.platform.LocalDensity.current
  val imeVisible = imeInsets.getBottom(density) > 0
  LaunchedEffect(activeSurface) { bottomNavVisibility.notifyRouteChanged() }
  LaunchedEffect(showSystemDrawer.value) {
    if (showSystemDrawer.value) bottomNavVisibility.notifyInteraction()
  }

  // BACK-TO-CHAT LAW: system back never leaves the app from a surface.
  // Priority: close system drawer → return to Chat (the conversation is home).
  // From Chat itself, back exits.
  androidx.activity.compose.BackHandler(
    enabled = showSystemDrawer.value || activeSurface != NavigationSurface.COMMAND
  ) {
    when {
      showSystemDrawer.value -> showSystemDrawer.value = false
      activeSurface != NavigationSurface.COMMAND -> viewModel.setNavigationSurface(NavigationSurface.COMMAND)
    }
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = PurpVoid
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      if (showSystemDrawer.value) {
        val drawerState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        androidx.compose.material3.ModalBottomSheet(
          onDismissRequest = { showSystemDrawer.value = false },
          sheetState = drawerState,
          containerColor = PurpSurfaceCard
        ) {
          Column(
            Modifier
              .verticalScroll(rememberScrollState())
              .padding(bottom = 24.dp)
          ) {
            Text(
              "PURPCLAW",
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = TextMuted,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            Text(
              "NEW CHAT",
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
              color = TextPrimary,
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  viewModel.createNewChat()
                  showSystemDrawer.value = false
                }
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("navNewChat")
            )

            Text(
              "RECENT CHATS",
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = TextMuted,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            if (chatSessions.isEmpty()) {
              Text(
                "No saved chats yet",
                fontSize = 12.sp,
                color = TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
              )
            } else {
              chatSessions.take(8).forEach { session ->
                val selected = session.sessionId == activeSessionId
                Text(
                  text = (session.title?.trim()?.take(72)?.ifBlank { null } ?: "Untitled chat") +
                    "  ·  ${session.turnCount}",
                  fontSize = 12.sp,
                  color = if (selected) PurpNeon else TextPrimary,
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                      viewModel.selectChatSession(session.sessionId)
                      showSystemDrawer.value = false
                    }
                    .padding(horizontal = 20.dp, vertical = 9.dp)
                    .testTag("navChat_${session.sessionId}")
                )
              }
            }

            Text(
              "SURFACES",
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = TextMuted,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            listOf(
              "Chat" to NavigationSurface.COMMAND,
              "Missions" to NavigationSurface.MISSIONS,
              "Canvas & Library" to NavigationSurface.CANVAS,
              "Agents & Skills" to NavigationSurface.ORGANISATION,
              "Council" to NavigationSurface.COUNCIL,
              "Podcast Studio" to NavigationSurface.STUDIO,
              "Memory" to NavigationSurface.MEMORY,
              "Tools & Plugins" to NavigationSurface.TOOLS_MESH,
              "Settings · AI & Models" to NavigationSurface.AI_MODELS,
              "Audit & Vault" to NavigationSurface.VAULT
            ).forEach { (label, destination) ->
              Text(
                label,
                fontSize = 13.sp,
                color = if (activeSurface == destination) PurpNeon else TextPrimary,
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable {
                    viewModel.setNavigationSurface(destination)
                    showSystemDrawer.value = false
                  }
                  .padding(horizontal = 20.dp, vertical = 9.dp)
                  .testTag("navSurface_${destination.name}")
              )
            }

            Text(
              "MODE",
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = TextMuted,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            Row(
              Modifier.padding(horizontal = 20.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              listOf(InteractionMode.CHAT, InteractionMode.WORK).forEach { mode ->
                val isSelected = interactionMode == mode
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = if (isSelected) PurpDeep else PurpSurfaceElevated,
                  border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) PurpNeon else PurpBorder),
                  onClick = { viewModel.setInteractionMode(mode) }
                ) {
                  Text(
                    mode.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) TextPrimary else TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                  )
                }
              }
            }

            Spacer(Modifier.height(10.dp))
            Text(
              "COMPANION",
              fontSize = 10.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = TextMuted,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            Row(
              Modifier.padding(horizontal = 20.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              com.example.ui.components.Avatar3DClips.COMPANION_NAMES.forEach { name ->
                val isSelected = selectedCompanion == name
                Surface(
                  shape = RoundedCornerShape(10.dp),
                  color = if (isSelected) PurpDeep else PurpSurfaceElevated,
                  border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) PurpNeon else PurpBorder),
                  onClick = { viewModel.setSelectedCompanion(name) }
                ) {
                  Text(
                    name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) TextPrimary else TextMuted,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                  )
                }
              }
            }
          }
        }
      }

      when (activeSurface) {
        NavigationSurface.COMMAND -> {
          CommandScreen(
            turns = turns,
            liveTurn = viewModel.liveTurn.collectAsState().value,
            liveStatus = viewModel.lastLiveStatus.collectAsState().value,
            tokenBurnCents = viewModel.tokenBurnCents.collectAsState().value,
            inputText = inputText,
            pendingMediaAttachments = pendingMediaAttachments,
            interactionMode = interactionMode,
            selectedCompanion = selectedCompanion,
            isGenerating = isGenerating,
            isListening = isListening,
            voiceMode = viewModel.voiceMode.collectAsState().value,
            voiceInputLevel = viewModel.voiceInputLevel.collectAsState().value,
            voiceOutputLevel = viewModel.voiceOutputLevel.collectAsState().value,
            voiceConversationMode = viewModel.voiceConversationMode.collectAsState().value,
            activeLease = activeLease,
            selectedModel = selectedModel,
            onInputChanged = { viewModel.updateInputText(it) },
            onSendMessage = { viewModel.sendCurrentMessage() },
            onCancelTurn = { viewModel.cancelActiveTurn() },
            onArmLease = { viewModel.armExecutionLease() },
            onRevokeLease = { viewModel.revokeExecutionLease() },
            onVoiceTrigger = { viewModel.toggleVoiceMode() },
            onPodcastPushToTalkStart = { viewModel.beginPodcastPushToTalk() },
            onPodcastPushToTalkEnd = { viewModel.endPodcastPushToTalk() },
            onCameraTrigger = { (context as? com.example.MainActivity)?.launchUserCamera() },
            onIntakeTrigger = { viewModel.triggerFileIntakeCapsule() },
            onModeChanged = { viewModel.setInteractionMode(it) },
            onCompanionTrigger = { showSystemDrawer.value = true },
            dualViewUrl = viewModel.dualViewUrl.collectAsState().value,
            onDualViewClose = { viewModel.closeDualView() },
            modelChoices = modelChoices,
            onSelectModel = { viewModel.setSelectedModel(it) },
            // TASK #54/#55/#56: podcast overlay surfaces
            isPodcastActive = viewModel.isPodcastActive.collectAsState().value,
            latestSavedEpisode = viewModel.latestSavedEpisode.collectAsState().value,
            podcastBreak = viewModel.breakMode.collectAsState().value,
            onEnterPodcastBreak = { viewModel.enterPodcastBreak() },
            onExitPodcastBreak = { viewModel.exitPodcastBreak() },
            onDismissSavedEpisode = { viewModel.dismissLatestSavedEpisode() },
            onOpenSavedEpisode = { sp ->
              // Future: navigate to a dedicated transcript viewer. For now
              // we open the file in Dual View if it's a URL; the .md path
              // is rendered inside PodcastStudioScreen instead.
              Log.i("PurpClawApp", "open saved episode ${sp.id} → ${sp.transcriptMdPath}")
            },
            // INLINE ACTION RAIL: per-bubble Read Aloud / Stop / Retry / Edit.
            speakingTurnId = viewModel.speakingTurnId.collectAsState().value,
            onReadAloud = { viewModel.readAloud(it) },
            onStopAloud = { viewModel.stopAloud() },
            onRetryTurn = { viewModel.retryTurn(it) },
            onEditTurn = { viewModel.editTurn(it) }
          )
        }

        NavigationSurface.CANVAS -> {
          CanvasScreen(
            canvasManager = viewModel.canvasManager,
            interactionMode = interactionMode,
            activeLease = activeLease,
            onArmLease = { viewModel.armExecutionLease() },
            onRevokeLease = { viewModel.revokeExecutionLease() }
          )
        }

        NavigationSurface.MISSIONS -> {
          MissionsScreen(
            missions = missions,
            onCreateMission = { title, goal, mode ->
              viewModel.agentTower.createMission(title, goal, mode)
            }
          )
        }

        NavigationSurface.STUDIO -> {
          PodcastStudioScreen(engine = viewModel.getOrCreatePodcastEnginePublic())
        }

        NavigationSurface.ORGANISATION, NavigationSurface.COUNCIL -> {
          LaunchedEffect(Unit) { viewModel.refreshRosters() }
          OrganisationScreen(
            agents = agents,
            canonicalAgents = agentRoster,
            skills = skillRoster,
            councils = councils,
            subsystems = subsystems,
            toolsCount = availableTools.size,
            providersCount = null,
            onSpawnAgent = { name, role, div, caps ->
              viewModel.agentTower.spawnAgent(name, role, div, caps)
            },
            onConveneCouncil = { issue, domain ->
              viewModel.agentTower.conveneCouncil(issue, domain)
            }
          )
        }

        NavigationSurface.MEMORY -> {
          SevenLayerMemoryScreen(
            memoryItems = memoryItems,
            workingMemory = workingMemory,
            spineStatus = viewModel.spineStatus.value,
            onAddMemory = { layer, key, content ->
              viewModel.addMemoryItem(layer, key, content)
            },
            onDeleteMemory = { id ->
              viewModel.deleteMemoryItem(id)
            }
          )
        }

        NavigationSurface.TOOLS_MESH -> {
          ToolsMeshScreen(
            tools = availableTools,
            isHomeOnline = homeNode.online,
            onRunTool = { name, args ->
              viewModel.executeToolDirect(name, args)
            },
            onTriggerIntake = {
              viewModel.triggerFileIntakeCapsule("mesh_archive_sample.zip")
            }
          )
        }


        NavigationSurface.AUDIT_PROOF, NavigationSurface.VAULT -> {
          AuditProofScreen(
            subsystems = subsystems,
            receipts = receipts,
            snapshots = snapshots,
            vaultItems = vaultItems,
            onCreateSnapshot = { reason ->
              viewModel.createSnapshot(reason)
            },
            onAddVaultSecret = { key, value, cat ->
              viewModel.addVaultSecret(key, value, cat)
            },
            onReverifySubsystem = { id ->
              viewModel.reverifySubsystem(id)
            }
          )
        }

        NavigationSurface.AI_MODELS -> {
          AiModelsSettingsScreen(
            providerRouter = viewModel.providerRouter,
            hasOpenRouterKey = viewModel.vault.hasSecret("OPENROUTER_API_KEY"),
            hasMiniMaxKey = viewModel.vault.hasSecret("MINIMAX_API_KEY"),
            hasNimKey = viewModel.vault.hasSecret("NVIDIA_NIM_API_KEY"),
            hasKimiKey = viewModel.vault.hasSecret("KIMI_API_KEY"),
            hasQwenKey = viewModel.vault.hasSecret("QWEN_API_KEY"),
            hasDeepseekKey = viewModel.vault.hasSecret("DEEPSEEK_API_KEY"),
            hasOpenaiKey = viewModel.vault.hasSecret("OPENAI_API_KEY"),
            hasZaiKey = viewModel.vault.hasSecret("ZAI_API_KEY"),
            hasLongcatKey = viewModel.vault.hasSecret("LONGCAT_API_KEY"),
            onSaveOpenRouterKey = { viewModel.saveOpenRouterKey(it) },
            onConnectOpenRouter = { viewModel.beginOpenRouterOAuth() },
            onSaveMiniMaxKey = { viewModel.saveMiniMaxKey(it) },
            onSaveNvidiaNimKey = { viewModel.saveNvidiaNimKey(it) },
            onSaveDirectProviderKey = { vaultKey, displayLabel, key ->
              viewModel.saveDirectProviderKey(vaultKey, displayLabel, key)
            },
            onClearKey = { key -> viewModel.clearVaultSecret(key) },
            onUpdateSpendPolicy = { viewModel.updateSpendPolicy(it) },
            spendSnapshot = viewModel.spendSnapshot.collectAsState().value,
            spendPolicy = viewModel.spendPolicy.collectAsState().value,
            voiceModeController = viewModel.voiceModeController,
            homeRuntimeBridge = HomeRuntimeBridge
          )
        }
      }

      // Liquid nav — mounted once, outside the when() so screens never own it.
      // Sits above screen content; slides away as one unit per visibility law.
      // Chat already owns the bottom interaction zone (composer + mode +
      // send). Mounting the global dock there put its Vault seat physically
      // over Send even when the dock looked hidden. Every non-chat surface
      // retains this single canonical dock, including its direct Chat seat.
      if (activeSurface != NavigationSurface.COMMAND) {
        LiquidBottomNav(
          activeSurface = activeSurface,
          visibility = bottomNavVisibility,
          imeVisible = imeVisible,
          menuOpen = showSystemDrawer.value,
          onSelect = { viewModel.setNavigationSurface(it) },
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 4.dp)
        )
      }
    }
  }
}
