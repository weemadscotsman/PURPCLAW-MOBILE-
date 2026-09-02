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
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.PurpSurfaceElevated
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

/**
 * One item in the model picker flyout — either a provider header or a model entry.
 * PROVIDER SEGREGATION LAW (operator 2026-09-01): every supported provider's
 * offerings render under its own labelled header; never a flat interleave.
 */
sealed class ModelPickerItem {
  data class Header(val provider: String, val label: String, val color: Long) : ModelPickerItem()
  data class Model(val id: String, val name: String, val provider: String) : ModelPickerItem()
}

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

  // Composer model flyout choices: AUTO + per-provider free catalogue, cleanly separated.
  // PROVIDER SEGREGATION LAW: each provider's models appear under a labelled header.
  // Google lanes purged — never surfaced.
  val openRouterCatalogue by viewModel.providerRouter.openRouterCatalogue.collectAsState()
  val nimCatalogue by viewModel.providerRouter.nimCatalogue.collectAsState()
  val minimaxCatalogue by viewModel.providerRouter.minimaxCatalogue.collectAsState()
  val groqCatalogue by viewModel.providerRouter.groqCatalogue.collectAsState()
  val cerebrasCatalogue by viewModel.providerRouter.cerebrasCatalogue.collectAsState()
  val googleAiCatalogue by viewModel.providerRouter.googleAiCatalogue.collectAsState()
  val cloudflareCatalogue by viewModel.providerRouter.cloudflareCatalogue.collectAsState()
  val kimiCatalogue by viewModel.providerRouter.kimiCatalogue.collectAsState()
  val qwenCatalogue by viewModel.providerRouter.qwenCatalogue.collectAsState()
  val deepseekCatalogue by viewModel.providerRouter.deepseekCatalogue.collectAsState()
  val openaiCatalogue by viewModel.providerRouter.openaiCatalogue.collectAsState()
  val zaiCatalogue by viewModel.providerRouter.zaiCatalogue.collectAsState()
  val longcatCatalogue by viewModel.providerRouter.longcatCatalogue.collectAsState()

  val modelChoices: List<ModelPickerItem> = remember(
    openRouterCatalogue.size, nimCatalogue.size, minimaxCatalogue.size,
    groqCatalogue.size, cerebrasCatalogue.size, googleAiCatalogue.size, cloudflareCatalogue.size,
    kimiCatalogue.size, qwenCatalogue.size, deepseekCatalogue.size, openaiCatalogue.size,
    zaiCatalogue.size, longcatCatalogue.size
  ) {
    // Shared chat-class filter for subscription/direct lanes (gateway lanes are
    // already classifier-filtered at ingestion in refreshGatewayCatalogue).
    fun chatOnly(models: List<com.example.core.model.CatalogueModel>) = models.filter { m ->
      m.modelClass == "chat"
    }
    buildList {
      add(ModelPickerItem.Model("AUTO", "Auto — free router picks best", "AUTO"))
      add(ModelPickerItem.Header("OPENROUTER", "OpenRouter", 0xFF22D3EE))
      openRouterCatalogue
        .filter { it.isFree && !it.id.contains("gemini", true) && !it.id.startsWith("google/") }
        .filter { m ->
          // Same honest rule as NIM: the upstream id is the source of truth.
          // Only drop models that are provably non-chat.
          m.modelClass == "chat" &&
            com.example.core.runtime.ProviderRouter.isCallableAutoModelId(m.id) &&
            !m.id.lowercase().let { l -> listOf("embed", "rerank", "whisper", "\\btts\\b").any { l.contains(it) } }
        }
        .take(20)
        .forEach { add(ModelPickerItem.Model(it.id, it.name, "OPENROUTER")) }
      add(ModelPickerItem.Header("NVIDIA_NIM", "NVIDIA NIM", 0xFF10B981))
      nimCatalogue
        .filter { m ->
          // Show all real upstream ids — the old hardcoded listOf("guard", …,
          // "stable-diffusion") dropped valid chat models like
          // llama-3.2-11b-vision-instruct, muse-glimmer-30b, kimi-k2.6 etc.
          m.isFree && m.modelClass == "chat"
        }
        .take(20)
        .forEach { add(ModelPickerItem.Model(it.id, it.name, "NVIDIA_NIM")) }
      add(ModelPickerItem.Header("MINIMAX", "MiniMax", 0xFF8B5CF6))
      minimaxCatalogue
        .filter { m -> m.modelClass == "chat" }
        .take(10)
        .forEach { add(ModelPickerItem.Model(it.id, it.name, "MINIMAX")) }
      // MULTI-LANE LAW: every supported free gateway + every subscription the
      // operator has wired in appears under its OWN header — no provider ghosts
      // (empty lanes are skipped), no flat interleave.
      if (groqCatalogue.isNotEmpty()) {
        add(ModelPickerItem.Header("GROQ", "Groq (free)", 0xFFF97316))
        chatOnly(groqCatalogue).take(10).forEach { add(ModelPickerItem.Model(it.id, it.name, "GROQ")) }
      }
      if (cerebrasCatalogue.isNotEmpty()) {
        add(ModelPickerItem.Header("CEREBRAS", "Cerebras (free)", 0xFFEF4444))
        chatOnly(cerebrasCatalogue).take(10).forEach { add(ModelPickerItem.Model(it.id, it.name, "CEREBRAS")) }
      }
      if (googleAiCatalogue.isNotEmpty()) {
        add(ModelPickerItem.Header("GOOGLE_AI", "Google AI Studio (free)", 0xFF60A5FA))
        chatOnly(googleAiCatalogue).take(10).forEach { add(ModelPickerItem.Model(it.id, it.name, "GOOGLE_AI")) }
      }
      if (cloudflareCatalogue.isNotEmpty()) {
        add(ModelPickerItem.Header("CLOUDFLARE", "Cloudflare Workers AI (free)", 0xFFF59E0B))
        chatOnly(cloudflareCatalogue).take(10).forEach { add(ModelPickerItem.Model(it.id, it.name, "CLOUDFLARE")) }
      }
      if (kimiCatalogue.isNotEmpty()) {
        add(ModelPickerItem.Header("KIMI", "Kimi (subscription)", 0xFFF472B6))
        chatOnly(kimiCatalogue).take(8).forEach { add(ModelPickerItem.Model(it.id, it.name, "KIMI")) }
      }
      if (qwenCatalogue.isNotEmpty()) {
        add(ModelPickerItem.Header("QWEN", "Qwen (subscription)", 0xFF818CF8))
        chatOnly(qwenCatalogue).take(8).forEach { add(ModelPickerItem.Model(it.id, it.name, "QWEN")) }
      }
      if (deepseekCatalogue.isNotEmpty()) {
        add(ModelPickerItem.Header("DEEPSEEK", "DeepSeek (subscription)", 0xFF38BDF8))
        chatOnly(deepseekCatalogue).take(8).forEach { add(ModelPickerItem.Model(it.id, it.name, "DEEPSEEK")) }
      }
      if (openaiCatalogue.isNotEmpty()) {
        add(ModelPickerItem.Header("OPENAI", "OpenAI (your key)", 0xFFE5E7EB))
        chatOnly(openaiCatalogue).take(8).forEach { add(ModelPickerItem.Model(it.id, it.name, "OPENAI")) }
      }
      if (zaiCatalogue.isNotEmpty()) {
        add(ModelPickerItem.Header("ZAI", "Z.ai (subscription)", 0xFFA78BFA))
        chatOnly(zaiCatalogue).take(8).forEach { add(ModelPickerItem.Model(it.id, it.name, "ZAI")) }
      }
      if (longcatCatalogue.isNotEmpty()) {
        add(ModelPickerItem.Header("LONGCAT", "LongCat (subscription)", 0xFF2DD4BF))
        chatOnly(longcatCatalogue).take(8).forEach { add(ModelPickerItem.Model(it.id, it.name, "LONGCAT")) }
      }
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
                  color = if (selected) CyanNeon else TextPrimary,
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
                color = if (activeSurface == destination) CyanNeon else TextPrimary,
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
                  color = if (isSelected) PurpSurfaceElevated else PurpSurfaceElevated,
                  border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) CyanNeon else CyanNeon.copy(alpha = 0.3f)),
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
                  color = if (isSelected) PurpSurfaceElevated else PurpSurfaceElevated,
                  border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) CyanNeon else CyanNeon.copy(alpha = 0.3f)),
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
            companionState = viewModel.companionState.collectAsState().value,
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
            onSelectModel = { provider, id -> viewModel.setSelectedModel(provider, id) },
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
            onEditTurn = { viewModel.editTurn(it) },
            // P0-7: wire real WebView page load result into ToolRuntimeEngine
            toolRuntime = viewModel.toolRuntime,
            // PURPCLAW Live Build Preview Card spec (2026-08-31)
            liveBuildCardState = viewModel.liveBuildCardState.collectAsState().value,
            // TASK #104 PLUS ACTION SHEET: one '+' button, canonical action registry
            agentRoster = agentRoster,
            isHomeOnline = homeNode.online,
            plusActionLine = viewModel.lastPlusAction.collectAsState().value,
            onAttachDocument = viewModel::attachSafDocument,
            onAttachFolder = viewModel::attachSafFolder,
            onAttachGalleryMedia = viewModel::attachGalleryMedia,
            onRemoveAttachment = viewModel::removePendingAttachment,
            onRunTool = { name -> viewModel.executeToolDirect(name, "") },
            onInjectContext = viewModel::appendComposerContext,
            onMentionAgent = { agent -> viewModel.appendComposerContext("@${agent.name} ") },
            onSwitchToWork = { viewModel.setInteractionMode(InteractionMode.WORK) },
            // LESSON TOOL: card buttons → lesson engine, card refresh via StateFlow
            onLessonAction = { viewModel.onLessonAction(it) }
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
            hasGroqKey = viewModel.vault.hasSecret("GROQ_API_KEY"),
            hasCerebrasKey = viewModel.vault.hasSecret("CEREBRAS_API_KEY"),
            hasGoogleAiKey = viewModel.vault.hasSecret("GOOGLE_AI_API_KEY"),
            hasCloudflareKey = viewModel.vault.hasSecret("CLOUDFLARE_API_KEY"),
            hasCloudflareAccountKey = viewModel.vault.hasSecret("CLOUDFLARE_ACCOUNT_ID"),
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
            homeRuntimeBridge = HomeRuntimeBridge,
            isPipEnabled = viewModel.isPipEnabled.collectAsState().value,
            selectedCompanion = viewModel.selectedCompanion.collectAsState().value,
            onSetPipEnabled = { viewModel.setPipEnabled(it) },
            onSetSelectedCompanion = { viewModel.setSelectedCompanion(it) }
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
