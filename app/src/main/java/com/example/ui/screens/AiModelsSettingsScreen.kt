package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.core.model.CatalogueModel
import com.example.core.model.ModelMode
import com.example.core.model.ProviderSource
import com.example.core.model.ProviderType
import com.example.core.model.ReasoningEffort
import com.example.core.model.RoutingProfile
import com.example.core.model.RoutingState
import com.example.core.model.SpendMode
import com.example.core.model.SpendPolicy
import com.example.core.network.HomeRuntimeBridge
import com.example.core.runtime.ModelBenchmarkReport
import com.example.core.runtime.ProviderRouter
import com.example.core.runtime.SpendSnapshot
import com.example.core.runtime.VoiceModeController
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
import kotlinx.coroutines.launch

@Composable
fun AiModelsSettingsScreen(
  providerRouter: ProviderRouter,
  hasOpenRouterKey: Boolean,
  hasMiniMaxKey: Boolean,
  hasNimKey: Boolean,
  hasKimiKey: Boolean = false,
  hasQwenKey: Boolean = false,
  hasDeepseekKey: Boolean = false,
  hasOpenaiKey: Boolean = false,
  hasZaiKey: Boolean = false,
  hasLongcatKey: Boolean = false,
  onSaveOpenRouterKey: (String) -> Unit,
  onConnectOpenRouter: () -> Unit = {},
  onSaveMiniMaxKey: (String) -> Unit,
  onSaveNvidiaNimKey: (String) -> Unit,
  onSaveDirectProviderKey: (vaultKeyName: String, displayLabel: String, key: String) -> Unit = { _, _, _ -> },
  onClearKey: (String) -> Unit,
  onUpdateSpendPolicy: (SpendPolicy) -> Unit = {},
  spendSnapshot: SpendSnapshot = SpendSnapshot(0.0, 0, SpendMode.OFF),
  spendPolicy: SpendPolicy = SpendPolicy(),
  voiceModeController: VoiceModeController? = null,
  homeRuntimeBridge: HomeRuntimeBridge? = null,
  modifier: Modifier = Modifier
) {
  val routingState by providerRouter.routingState.collectAsState()
  val catalogue by providerRouter.openRouterCatalogue.collectAsState()
  val miniMaxStatus by providerRouter.miniMaxStatus.collectAsState()
  val nimCatalogue by providerRouter.nimCatalogue.collectAsState()
  val minimaxCatalogue by providerRouter.minimaxCatalogue.collectAsState()
  val kimiCatalogue by providerRouter.kimiCatalogue.collectAsState()
  val qwenCatalogue by providerRouter.qwenCatalogue.collectAsState()
  val deepseekCatalogue by providerRouter.deepseekCatalogue.collectAsState()
  val openaiCatalogue by providerRouter.openaiCatalogue.collectAsState()
  val zaiCatalogue by providerRouter.zaiCatalogue.collectAsState()
  val longcatCatalogue by providerRouter.longcatCatalogue.collectAsState()
  val coroutineScope = rememberCoroutineScope()

  var selectedSection by remember { mutableStateOf("LIBRARY") }
  // Sections (provider-law 2026-08-26): LIBRARY first = the new free-first world.
  // Legacy tabs preserved for parity while migration lands.
  // Sections: LIBRARY, ROUTING, AUTO_CONTROLS, VOICE, FREE_MODELS, OPENROUTER, MINIMAX, NVIDIA_NIM, LOCAL_MODELS, HOME_ROUTING, SPEND

  var showKeyDialogFor by remember { mutableStateOf<String?>(null) } // "OPENROUTER", "MINIMAX", or direct provider vault key
  var _keyDialogLabel by remember { mutableStateOf<String?>(null) }
  var inputApiKey by remember { mutableStateOf("") }
  var validationMessage by remember { mutableStateOf<String?>(null) }
  var isValidatingKey by remember { mutableStateOf(false) }

  var selectedModelForDialog by remember { mutableStateOf<CatalogueModel?>(null) }
  var benchmarkReport by remember { mutableStateOf<ModelBenchmarkReport?>(null) }
  var isBenchmarking by remember { mutableStateOf(false) }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(PurpVoid)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
      // Screen Title & Breadcrumb
      Text(
        text = "SETTINGS / AI & MODELS",
        fontSize = 15.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 1.sp,
        color = TextPrimary
      )
      Text(
        text = "Exact PurpClaw Canonical Provider/Model Routing Parity",
        fontSize = 10.5.sp,
        color = TextSecondary
      )

      Spacer(modifier = Modifier.height(10.dp))

      // Section Navigation Tabs (Horizontal scrollable)
      val sections = listOf(
        Pair("LIBRARY", "Library"),
        Pair("SPEND", "Spend"),
        Pair("ROUTING", "Routing"),
        Pair("AUTO_CONTROLS", "AUTO Controls"),
        Pair("VOICE", "Voice"),
        Pair("FREE_MODELS", "Free Models"),
        Pair("OPENROUTER", "OpenRouter"),
        Pair("MINIMAX", "MiniMax"),
        Pair("NVIDIA_NIM", "NVIDIA"),
        Pair("LOCAL_MODELS", "Local"),
        Pair("HOME_ROUTING", "Home-PC")
      )

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        sections.forEach { (key, label) ->
          val isSelected = selectedSection == key
          Surface(
            modifier = Modifier
              .clip(RoundedCornerShape(8.dp))
              .clickable { selectedSection = key }
              .testTag("ai_settings_tab_${key.lowercase()}"),
            color = if (isSelected) PurpPrimary else PurpSurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) PurpNeon else PurpBorder)
          ) {
            Text(
              text = label,
              fontSize = 10.5.sp,
              fontWeight = FontWeight.Bold,
              color = if (isSelected) Color.White else TextSecondary,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Content for selected section — weight(1f) so it owns the remaining
      // viewport; inner LazyColumns scroll inside their own bounded area
      // instead of being clipped by the parent Column.
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
      ) {
        when (selectedSection) {
          "LIBRARY" -> ProviderLibrarySection(
            openRouterCatalogue = catalogue,
            nimCatalogue = nimCatalogue,
            minimaxCatalogue = minimaxCatalogue,
            kimiCatalogue = kimiCatalogue,
            qwenCatalogue = qwenCatalogue,
            deepseekCatalogue = deepseekCatalogue,
            openaiCatalogue = openaiCatalogue,
            zaiCatalogue = zaiCatalogue,
            longcatCatalogue = longcatCatalogue,
            hasOpenRouterKey = hasOpenRouterKey,
            hasNimKey = hasNimKey,
            hasKimiKey = hasKimiKey,
            hasQwenKey = hasQwenKey,
            hasDeepseekKey = hasDeepseekKey,
            hasOpenaiKey = hasOpenaiKey,
            hasZaiKey = hasZaiKey,
            hasLongcatKey = hasLongcatKey,
            hasMinimaxKey = hasMiniMaxKey,
            onRefreshGateway = { coroutineScope.launch { providerRouter.refreshOpenRouterCatalogue() } },
            onConnectOpenRouter = onConnectOpenRouter,
            onRefreshNim = { coroutineScope.launch { providerRouter.refreshNimCatalogue() } },
            onOpenKeyDialog = { vaultKey, label ->
              inputApiKey = ""
              validationMessage = null
              showKeyDialogFor = vaultKey
              _keyDialogLabel = label
            },
            onClearKey = onClearKey,
            onSelectModel = { selectedModelForDialog = it },
            onPinModel = { id -> providerRouter.setSelectedModel("auto", id) },
            providerRouter = providerRouter
          )
          "SPEND" -> SpendSection(
            policy = spendPolicy,
            snapshot = spendSnapshot,
            onUpdatePolicy = onUpdateSpendPolicy
          )
          "VOICE" -> VoiceSettingsSection(
            voiceModeController = voiceModeController,
            homeBridge = homeRuntimeBridge
          )
          "ROUTING" -> RoutingMainSection(
            state = routingState,
            onUpdateState = { providerRouter.updateRoutingState(it) }
          )
          "AUTO_CONTROLS" -> AutoRoutingControlsSection(
            state = routingState,
            onUpdateState = { providerRouter.updateRoutingState(it) }
          )
          "FREE_MODELS" -> FreeModelRoutingSection(
            state = routingState,
            catalogue = catalogue,
            onUpdateState = { providerRouter.updateRoutingState(it) }
          )
          "OPENROUTER" -> OpenRouterSection(
            catalogue = catalogue,
            hasKey = hasOpenRouterKey,
            onRefresh = { coroutineScope.launch { providerRouter.refreshOpenRouterCatalogue() } },
            onOpenKeyDialog = {
              inputApiKey = ""
              validationMessage = null
              showKeyDialogFor = "OPENROUTER_API_KEY"
              _keyDialogLabel = "OpenRouter API Key"
            },
            onClearKey = { onClearKey("OPENROUTER_API_KEY") },
            onSelectModel = { selectedModelForDialog = it }
          )
          "MINIMAX" -> MiniMaxSection(
            status = miniMaxStatus,
            hasKey = hasMiniMaxKey,
            onOpenKeyDialog = {
              inputApiKey = ""
              validationMessage = null
              showKeyDialogFor = "MINIMAX_API_KEY"
              _keyDialogLabel = "MiniMax API Key"
            },
            onClearKey = { onClearKey("MINIMAX_API_KEY") },
            onProbe = { coroutineScope.launch { providerRouter.checkMiniMaxInstalledCapabilities() } }
          )
          "NVIDIA_NIM" -> NimSection(
            catalogue = nimCatalogue,
            hasKey = hasNimKey,
            onOpenKeyDialog = {
              inputApiKey = ""
              validationMessage = null
              showKeyDialogFor = "NVIDIA_NIM_API_KEY"
              _keyDialogLabel = "NVIDIA NIM API Key"
            },
            onClearKey = { onClearKey("NVIDIA_NIM_API_KEY") },
            onRefresh = { coroutineScope.launch { providerRouter.refreshNimCatalogue() } }
          )
          "LOCAL_MODELS" -> LocalAndroidModelsSection()
          "HOME_ROUTING" -> HomePcRoutingSection(
            state = routingState,
            onUpdateState = { providerRouter.updateRoutingState(it) }
          )
        }
      }
    }

    // Modal: API Key Entry & Test (Stored strictly in KeystoreVault)
    if (showKeyDialogFor != null) {
      val providerKey = showKeyDialogFor!!
      val isOR = providerKey == "OPENROUTER_API_KEY"
      val isNIM = providerKey == "NVIDIA_NIM_API_KEY"
      val isMinimax = providerKey == "MINIMAX_API_KEY"
      val isDirect = !isOR && !isNIM && !isMinimax
      val title = when (providerKey) {
        "OPENROUTER_API_KEY" -> "OpenRouter API Key Setup"
        "NVIDIA_NIM_API_KEY" -> "NVIDIA NIM API Key Setup"
        "MINIMAX_API_KEY" -> "MiniMax API Key Setup"
        "KIMI_API_KEY" -> "Kimi (Moonshot) API Key Setup"
        "QWEN_API_KEY" -> "Qwen (DashScope) API Key Setup"
        "DEEPSEEK_API_KEY" -> "DeepSeek API Key Setup"
        "OPENAI_API_KEY" -> "OpenAI API Key Setup"
        "ZAI_API_KEY" -> "Z.ai (GLM) API Key Setup"
        "LONGCAT_API_KEY" -> "LongCat API Key Setup"
        else -> "Provider API Key Setup"
      }

      AlertDialog(
        onDismissRequest = { if (!isValidatingKey) showKeyDialogFor = null },
        containerColor = PurpSurface,
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
          }
        },
        text = {
          Column {
            Text(
              text = "Keys are securely encrypted using Android Keystore AES-256 GCM. Never stored in Room, BuildConfig, or logs.",
              fontSize = 11.sp,
              color = TextSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
              value = inputApiKey,
              onValueChange = { inputApiKey = it; validationMessage = null },
              label = { Text("API Key") },
              placeholder = { Text(when {
                isOR -> "sk-or-v1-..."
                isNIM -> "nvapi-..."
                providerKey == "OPENAI_API_KEY" -> "sk-..."
                providerKey == "DEEPSEEK_API_KEY" -> "sk-..."
                providerKey == "KIMI_API_KEY" -> "sk-..."
                providerKey == "QWEN_API_KEY" -> "sk-..."
                providerKey == "ZAI_API_KEY" -> "eyJ..."
                providerKey == "LONGCAT_API_KEY" -> "ak-..."
                else -> "secret"
              }) },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("api_key_input"),
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PurpNeon,
                unfocusedBorderColor = PurpBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
              )
            )

            val vm = validationMessage
            if (vm != null) {
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = vm,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = if (vm.startsWith("✓")) EmeraldOnline else RoseOffline
              )
            }
          }
        },
        confirmButton = {
          ElevatedButton(
            onClick = {
              if (inputApiKey.isBlank()) {
                validationMessage = "Key cannot be empty"
                return@ElevatedButton
              }
              isValidatingKey = true
              validationMessage = "Verifying live provider authentication..."
              coroutineScope.launch {
                when {
                  isOR -> {
                    onSaveOpenRouterKey(inputApiKey)
                    val isValid = providerRouter.validateOpenRouterKey(inputApiKey)
                    isValidatingKey = false
                    if (isValid) {
                      validationMessage = "✓ Key verified! Live catalogue activated."
                      providerRouter.refreshOpenRouterCatalogue()
                      kotlinx.coroutines.delay(800)
                      showKeyDialogFor = null
                    } else {
                      validationMessage = "✕ Auth failed or offline: Saved to Keystore, marked DEGRADED."
                      kotlinx.coroutines.delay(1200)
                      showKeyDialogFor = null
                    }
                  }
                  isNIM -> {
                    onSaveNvidiaNimKey(inputApiKey)
                    providerRouter.refreshNimCatalogue()
                    isValidatingKey = false
                    validationMessage = "✓ Key saved in Keystore Vault."
                    kotlinx.coroutines.delay(800)
                    showKeyDialogFor = null
                  }
                  isMinimax -> {
                    onSaveMiniMaxKey(inputApiKey)
                    providerRouter.checkMiniMaxInstalledCapabilities()
                    providerRouter.refreshMinimaxCatalogue()
                    isValidatingKey = false
                    validationMessage = "✓ Key saved in Keystore Vault."
                    kotlinx.coroutines.delay(800)
                    showKeyDialogFor = null
                  }
                  isDirect -> {
                    val displayLabel = _keyDialogLabel ?: providerKey
                    onSaveDirectProviderKey(providerKey, displayLabel, inputApiKey)
                    isValidatingKey = false
                    validationMessage = "✓ Direct provider key saved — catalogue will refresh."
                    kotlinx.coroutines.delay(800)
                    showKeyDialogFor = null
                  }
                }
              }
            },
            colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpNeon, contentColor = Color.White),
            modifier = Modifier.testTag("save_api_key_button")
          ) {
            if (isValidatingKey) {
              CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
              Text("Save & Verify")
            }
          }
        },
        dismissButton = {
          OutlinedButton(
            onClick = { showKeyDialogFor = null },
            enabled = !isValidatingKey
          ) {
            Text("Cancel", color = TextSecondary)
          }
        }
      )
    }

    // Modal: Model Action & Benchmark Dialog
    if (selectedModelForDialog != null) {
      val model = selectedModelForDialog!!
      AlertDialog(
        onDismissRequest = {
          selectedModelForDialog = null
          benchmarkReport = null
        },
        containerColor = PurpSurface,
        title = {
          Text(model.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        },
        text = {
          Column {
            Text(model.id, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = CyanAccent)
            Spacer(modifier = Modifier.height(4.dp))
            Text(model.description, fontSize = 11.sp, color = TextSecondary)

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              if (model.isFree) TagChip("FREE", EmeraldOnline)
              if (model.isToolCapable) TagChip("TOOLS", PurpNeon)
              if (model.isVisionCapable) TagChip("VISION", CyanAccent)
              TagChip("${model.contextLength / 1024}K CTX", TextHighlight)
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (benchmarkReport != null) {
              val rep = benchmarkReport!!
              Surface(
                modifier = Modifier.fillMaxWidth(),
                color = PurpSurfaceElevated,
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (rep.success) EmeraldOnline else RoseOffline)
              ) {
                Column(modifier = Modifier.padding(8.dp)) {
                  Text(
                    text = if (rep.success) "BENCHMARK PASS: ${rep.latencyMs}ms | ${String.format("%.1f", rep.tokensPerSecond)} t/s" else "BENCHMARK FAILED: ${rep.error}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (rep.success) EmeraldOnline else RoseOffline
                  )
                  if (rep.outputSample.isNotBlank()) {
                    Text("Sample: \"${rep.outputSample}\"", fontSize = 9.5.sp, color = TextSecondary)
                  }
                }
              }
              Spacer(modifier = Modifier.height(8.dp))
            }

            // Action Buttons
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                  providerRouter.setSelectedModel(model.provider, model.id)
                  selectedModelForDialog = null
                }
              ) {
                Text("Use Manual", fontSize = 10.sp)
              }
              OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                  providerRouter.setModelAutoPreference(model.id, !model.isUserPreferredInAuto)
                  selectedModelForDialog = null
                }
              ) {
                Text(if (model.isUserPreferredInAuto) "Unprefer" else "Prefer AUTO", fontSize = 10.sp)
              }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                  providerRouter.setModelAutoExclusion(model.id, !model.isUserExcludedFromAuto)
                  selectedModelForDialog = null
                }
              ) {
                Text(if (model.isUserExcludedFromAuto) "Include" else "Exclude AUTO", fontSize = 10.sp)
              }
              ElevatedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                  isBenchmarking = true
                  coroutineScope.launch {
                    benchmarkReport = providerRouter.benchmarkModel(model.id)
                    isBenchmarking = false
                  }
                },
                colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpPrimary)
              ) {
                if (isBenchmarking) {
                  CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                  Text("Benchmark", fontSize = 10.sp)
                }
              }
            }
          }
        },
        confirmButton = {
          OutlinedButton(onClick = {
            selectedModelForDialog = null
            benchmarkReport = null
          }) {
            Text("Close", color = TextSecondary)
          }
        }
      )
    }
  }
}

// -------------------------------------------------------------------------------------
// 1. ROUTING SECTION
// -------------------------------------------------------------------------------------
@Composable
private fun RoutingMainSection(
  state: RoutingState,
  onUpdateState: ((RoutingState) -> RoutingState) -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      SectionCard(title = "MODEL MODE", icon = Icons.Default.Tune) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          listOf(ModelMode.AUTO, ModelMode.MANUAL).forEach { mode ->
            val isSelected = state.modelMode == mode
            Surface(
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onUpdateState { it.copy(modelMode = mode) } }
                .testTag("mode_${mode.name.lowercase()}"),
              color = if (isSelected) PurpNeon else PurpSurfaceElevated,
              border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) CyanNeon else PurpBorder)
            ) {
              Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                  text = mode.name,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (isSelected) Color.White else TextSecondary
                )
                Text(
                  text = if (mode == ModelMode.AUTO) "Smart candidate fit" else "Pinned provider/model",
                  fontSize = 9.sp,
                  color = if (isSelected) Color.White.copy(alpha = 0.8f) else TextMuted
                )
              }
            }
          }
        }
      }
    }

    item {
      SectionCard(title = "AUTO PROFILE", icon = Icons.Default.Speed) {
        val profiles = listOf(
          RoutingProfile.BALANCED,
          RoutingProfile.FAST,
          RoutingProfile.DEEP,
          RoutingProfile.FREE_FIRST,
          RoutingProfile.QUALITY_FIRST,
          RoutingProfile.LOCAL_FIRST
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          profiles.forEach { profile ->
            val isSelected = state.routingProfile == profile
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onUpdateState { it.copy(routingProfile = profile) } }
                .testTag("profile_${profile.name.lowercase()}"),
              color = if (isSelected) PurpPrimary else PurpSurfaceElevated,
              border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) PurpNeon else PurpBorder)
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "○ ${profile.label}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) Color.White else TextPrimary
                  )
                  Text(
                    text = profile.description,
                    fontSize = 9.5.sp,
                    color = if (isSelected) Color.White.copy(alpha = 0.8f) else TextSecondary
                  )
                }
                if (isSelected) {
                  Icon(Icons.Default.Check, contentDescription = null, tint = EmeraldOnline, modifier = Modifier.size(16.dp))
                }
              }
            }
          }
        }
      }
    }

    item {
      SectionCard(title = "REASONING EFFORT", icon = Icons.Default.Psychology) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          listOf(ReasoningEffort.AUTO, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH).forEach { effort ->
            val isSelected = state.reasoningEffort == effort
            Surface(
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .clickable { onUpdateState { it.copy(reasoningEffort = effort) } }
                .testTag("reasoning_${effort.name.lowercase()}"),
              color = if (isSelected) PurpPrimary else PurpSurfaceElevated,
              border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) PurpNeon else PurpBorder)
            ) {
              Text(
                text = effort.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.White else TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )
            }
          }
        }
      }
    }

    item {
      SectionCard(title = "SESSION & FALLBACK BEHAVIOUR", icon = Icons.Default.Router) {
        ToggleRow(
          title = "SESSION AFFINITY",
          description = "Maintain healthy winning model across turns in the same session",
          checked = state.sessionAffinity,
          onCheckedChange = { onUpdateState { s -> s.copy(sessionAffinity = it) } }
        )
        HorizontalDivider(color = PurpBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
        ToggleRow(
          title = "FALLBACK ENABLED",
          description = "Automatic failover to next qualified candidate on timeout/error",
          checked = state.fallbackEnabled,
          onCheckedChange = { onUpdateState { s -> s.copy(fallbackEnabled = it) } }
        )
      }
    }
  }
}

// -------------------------------------------------------------------------------------
// 2. AUTO ROUTING CONTROLS SECTION
// -------------------------------------------------------------------------------------
@Composable
private fun AutoRoutingControlsSection(
  state: RoutingState,
  onUpdateState: ((RoutingState) -> RoutingState) -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      SectionCard(title = "PREFER CANDIDATE ATTRIBUTES", icon = Icons.Default.Done) {
        CheckboxRow("healthy models", state.preferHealthy) { onUpdateState { s -> s.copy(preferHealthy = it) } }
        CheckboxRow("tool-capable when tools required", state.preferToolCapableWhenRequired) { onUpdateState { s -> s.copy(preferToolCapableWhenRequired = it) } }
        CheckboxRow("vision-capable for image input", state.preferVisionCapableWhenRequired) { onUpdateState { s -> s.copy(preferVisionCapableWhenRequired = it) } }
        CheckboxRow("long-context when required", state.preferLongContextWhenRequired) { onUpdateState { s -> s.copy(preferLongContextWhenRequired = it) } }
        CheckboxRow("previous healthy session model", state.preferPreviousSessionModel) { onUpdateState { s -> s.copy(preferPreviousSessionModel = it) } }
      }
    }

    item {
      SectionCard(title = "AVOID CANDIDATES (SEMANTIC FILTER)", icon = Icons.Default.Close) {
        CheckboxRow("classifiers (safety/guardrail models)", state.avoidClassifiers) { onUpdateState { s -> s.copy(avoidClassifiers = it) } }
        CheckboxRow("embedding models", state.avoidEmbedders) { onUpdateState { s -> s.copy(avoidEmbedders = it) } }
        CheckboxRow("rerankers", state.avoidRerankers) { onUpdateState { s -> s.copy(avoidRerankers = it) } }
        CheckboxRow("image-only models for chat", state.avoidNonChat) { onUpdateState { s -> s.copy(avoidNonChat = it) } }
        CheckboxRow("malformed output", state.avoidMalformedOutput) { onUpdateState { s -> s.copy(avoidMalformedOutput = it) } }
        CheckboxRow("empty responses", state.avoidEmptyResponses) { onUpdateState { s -> s.copy(avoidEmptyResponses = it) } }
      }
    }

    item {
      SectionCard(title = "QUALITY GATE & AFFINITY", icon = Icons.Default.Security) {
        ToggleRow(
          title = "QUALITY GATE",
          description = "Validate non-empty output and error payload absence before accepting turn",
          checked = state.qualityGateEnabled,
          onCheckedChange = { onUpdateState { s -> s.copy(qualityGateEnabled = it) } }
        )
        HorizontalDivider(color = PurpBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
        ToggleRow(
          title = "SESSION AFFINITY",
          description = "Clear affinity immediately on failure and elect new healthy winner",
          checked = state.sessionAffinity,
          onCheckedChange = { onUpdateState { s -> s.copy(sessionAffinity = it) } }
        )
      }
    }
  }
}

// -------------------------------------------------------------------------------------
// 3. FREE MODEL ROUTING SECTION
// -------------------------------------------------------------------------------------
@Composable
private fun FreeModelRoutingSection(
  state: RoutingState,
  catalogue: List<CatalogueModel>,
  onUpdateState: ((RoutingState) -> RoutingState) -> Unit
) {
  val freeModels = catalogue.filter { it.isFree }
  val qualifiedFree = freeModels.filter { it.isQualifiedFree }
  val excludedFree = freeModels.filter { !it.isQualifiedFree }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      SectionCard(title = "FREE MODELS CONFIGURATION", icon = Icons.Default.AutoAwesome) {
        ToggleRow(
          title = "Free models enabled",
          description = "Allow free model candidates in AUTO pool",
          checked = state.freeModelsEnabled,
          onCheckedChange = { onUpdateState { s -> s.copy(freeModelsEnabled = it) } }
        )
        HorizontalDivider(color = PurpBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
        ToggleRow(
          title = "Prefer free models",
          description = "Route to free models before paid providers",
          checked = state.preferFreeModels,
          onCheckedChange = { onUpdateState { s -> s.copy(preferFreeModels = it) } }
        )
        HorizontalDivider(color = PurpBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
        ToggleRow(
          title = "Free-only mode",
          description = "Strictly reject all paid models",
          checked = state.freeOnly,
          onCheckedChange = { onUpdateState { s -> s.copy(freeOnly = it) } }
        )
      }
    }

    item {
      SectionCard(title = "MINIMUM REQUIREMENTS FOR FREE CANDIDATES", icon = Icons.Default.FilterList) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Minimum Context", fontSize = 11.sp, color = TextPrimary)
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(16, 32, 64, 128).forEach { k ->
              val isSel = state.minFreeContextK == k
              Surface(
                modifier = Modifier
                  .clip(RoundedCornerShape(4.dp))
                  .clickable { onUpdateState { s -> s.copy(minFreeContextK = k) } },
                color = if (isSel) PurpNeon else PurpSurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) CyanNeon else PurpBorder)
              ) {
                Text(
                  "${k}K",
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (isSel) Color.White else TextSecondary,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )
              }
            }
          }
        }
      }
    }

    item {
      Text(
        text = "QUALIFIED FREE POOL (${qualifiedFree.size} MODELS)",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = EmeraldOnline
      )
    }

    items(qualifiedFree) { model ->
      ModelRowCard(model = model, isQualified = true)
    }

    if (excludedFree.isNotEmpty()) {
      item {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "DISQUALIFIED / EXCLUDED FREE POOL (${excludedFree.size} MODELS)",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace,
          color = RoseOffline
        )
      }

      items(excludedFree) { model ->
        ModelRowCard(model = model, isQualified = false)
      }
    }
  }
}

// -------------------------------------------------------------------------------------
// 4. OPENROUTER PAGE
// -------------------------------------------------------------------------------------
@Composable
private fun OpenRouterSection(
  catalogue: List<CatalogueModel>,
  hasKey: Boolean,
  onRefresh: () -> Unit,
  onOpenKeyDialog: () -> Unit,
  onClearKey: () -> Unit,
  onSelectModel: (CatalogueModel) -> Unit
) {
  var searchQuery by remember { mutableStateOf("") }
  var filterFreeOnly by remember { mutableStateOf(false) }
  var filterToolsOnly by remember { mutableStateOf(false) }
  var filterVisionOnly by remember { mutableStateOf(false) }
  var filterReasoningOnly by remember { mutableStateOf(false) }

  val filteredList = catalogue.filter { model ->
    (searchQuery.isBlank() || model.name.contains(searchQuery, ignoreCase = true) || model.id.contains(searchQuery, ignoreCase = true)) &&
      (!filterFreeOnly || model.isFree) &&
      (!filterToolsOnly || model.isToolCapable) &&
      (!filterVisionOnly || model.isVisionCapable) &&
      (!filterReasoningOnly || model.isReasoningCapable)
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    item {
      SectionCard(title = "OPENROUTER ADAPTER STATUS", icon = Icons.Default.Cloud) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = if (hasKey) "Status: ONLINE" else "Status: DEGRADED / UNCONFIGURED",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = if (hasKey) EmeraldOnline else RoseOffline
            )
            Text(
              text = if (hasKey) "Credential: Ingested in Keystore Vault" else "Credential: Missing (Free models only)",
              fontSize = 10.sp,
              color = TextSecondary
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasKey) {
              OutlinedButton(onClick = onClearKey) {
                Text("Clear", fontSize = 10.sp, color = RoseOffline)
              }
            }
            ElevatedButton(
              onClick = onOpenKeyDialog,
              colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpNeon)
            ) {
              Text(if (hasKey) "Update Key" else "Enter API Key", fontSize = 10.sp, color = Color.White)
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text("Catalogue: ${catalogue.size} models", fontSize = 10.sp, color = TextMuted)
          Text("Free qualified: ${catalogue.count { it.isQualifiedFree }}", fontSize = 10.sp, color = EmeraldOnline)
          Text("Tools: ${catalogue.count { it.isToolCapable }}", fontSize = 10.sp, color = PurpNeon)
          Text("Vision: ${catalogue.count { it.isVisionCapable }}", fontSize = 10.sp, color = CyanAccent)
        }

        Spacer(modifier = Modifier.height(6.dp))
        OutlinedButton(
          modifier = Modifier.fillMaxWidth(),
          onClick = onRefresh
        ) {
          Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Refresh OpenRouter Catalogue", fontSize = 11.sp)
        }
      }
    }

    item {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Search 400+ models (e.g. llama, deepseek, flash)...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("model_search_input"),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = PurpNeon,
          unfocusedBorderColor = PurpBorder,
          focusedTextColor = TextPrimary,
          unfocusedTextColor = TextPrimary
        )
      )
    }

    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        FilterChip(
          selected = filterFreeOnly,
          onClick = { filterFreeOnly = !filterFreeOnly },
          label = { Text("Free") },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EmeraldOnline)
        )
        FilterChip(
          selected = filterToolsOnly,
          onClick = { filterToolsOnly = !filterToolsOnly },
          label = { Text("Tools") },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = PurpNeon)
        )
        FilterChip(
          selected = filterVisionOnly,
          onClick = { filterVisionOnly = !filterVisionOnly },
          label = { Text("Vision") },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyanAccent)
        )
        FilterChip(
          selected = filterReasoningOnly,
          onClick = { filterReasoningOnly = !filterReasoningOnly },
          label = { Text("Reasoning") },
          colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AmberHybrid)
        )
      }
    }

    items(filteredList) { model ->
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { onSelectModel(model) }
          .testTag("model_row_${model.id}"),
        color = PurpSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder)
      ) {
        Column(modifier = Modifier.padding(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = model.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
              )
              Text(
                text = model.id,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = TextMuted
              )
            }
            Text(
              text = "${model.avgLatencyMs}ms",
              fontSize = 9.5.sp,
              fontFamily = FontFamily.Monospace,
              color = EmeraldOnline
            )
          }

          Spacer(modifier = Modifier.height(6.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (model.isFree) TagChip("FREE", EmeraldOnline)
            if (model.isToolCapable) TagChip("TOOLS", PurpNeon)
            if (model.isVisionCapable) TagChip("VISION", CyanAccent)
            if (model.isReasoningCapable) TagChip("REASONING", AmberHybrid)
            TagChip("${model.contextLength / 1024}K CTX", TextSecondary)
            if (model.isUserPreferredInAuto) TagChip("★ PREFERRED", CyanNeon)
            if (model.isUserExcludedFromAuto) TagChip("✕ EXCLUDED", RoseOffline)
          }
        }
      }
    }
  }
}

// -------------------------------------------------------------------------------------
// 5b. NVIDIA NIM — free-tier integrate.api.nvidia.com, live /v1/models catalogue
// -------------------------------------------------------------------------------------
@Composable
private fun NimSection(
  catalogue: List<CatalogueModel>,
  hasKey: Boolean,
  onOpenKeyDialog: () -> Unit,
  onClearKey: () -> Unit,
  onRefresh: () -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      SectionCard(title = "NVIDIA NIM ADAPTER", icon = Icons.Default.Computer) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = if (hasKey) "Credential: Configured in Keystore Vault" else "Credential: NOT INSTALLED",
              fontSize = 11.5.sp,
              fontWeight = FontWeight.Bold,
              color = if (hasKey) EmeraldOnline else RoseOffline
            )
            Text(
              text = "integrate.api.nvidia.com/v1 · free tier · OpenAI-compatible",
              fontSize = 9.5.sp,
              color = TextSecondary
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasKey) {
              OutlinedButton(onClick = onClearKey) { Text("Clear", fontSize = 10.sp, color = RoseOffline) }
            }
            ElevatedButton(
              onClick = onOpenKeyDialog,
              colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpNeon)
            ) {
              Text(if (hasKey) "Update Key" else "Set Key", fontSize = 10.sp, color = Color.White)
            }
          }
        }
      }
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "LIVE CATALOGUE (${catalogue.size} models)",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace,
          color = TextHighlight
        )
        OutlinedButton(onClick = onRefresh, enabled = hasKey) {
          Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Refresh", fontSize = 10.sp)
        }
      }
    }

    if (catalogue.isEmpty()) {
      item {
        Text(
          text = if (hasKey) "Catalogue empty — tap Refresh to pull latest free endpoints."
                 else "Set your NVIDIA NIM key to activate auto-routing and pull the live catalogue.",
          fontSize = 11.sp,
          color = TextSecondary
        )
      }
    } else {
      items(catalogue) { model ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(PurpSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(model.id, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(
              model.description,
              fontSize = 9.sp,
              color = TextSecondary,
              maxLines = 1
            )
          }
          Text(
            text = "FREE",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = EmeraldOnline
          )
        }
      }
    }
  }
}

// -------------------------------------------------------------------------------------
// 5. MINIMAX PAGE (Independent Capability Health)
// -------------------------------------------------------------------------------------
@Composable
private fun MiniMaxSection(
  status: com.example.core.model.MiniMaxCapabilityStatus,
  hasKey: Boolean,
  onOpenKeyDialog: () -> Unit,
  onClearKey: () -> Unit,
  onProbe: () -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      SectionCard(title = "MINIMAX SOVEREIGN ADAPTER", icon = Icons.Default.SmartToy) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = if (hasKey) "Credential: Configured in Keystore Vault" else "Credential: NOT INSTALLED",
              fontSize = 11.5.sp,
              fontWeight = FontWeight.Bold,
              color = if (hasKey) EmeraldOnline else RoseOffline
            )
            Text(
              text = "Disaggregated capability health status",
              fontSize = 9.5.sp,
              color = TextSecondary
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasKey) {
              OutlinedButton(onClick = onClearKey) { Text("Clear", fontSize = 10.sp, color = RoseOffline) }
            }
            ElevatedButton(
              onClick = onOpenKeyDialog,
              colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpNeon)
            ) {
              Text(if (hasKey) "Update Key" else "Set Key", fontSize = 10.sp, color = Color.White)
            }
          }
        }
      }
    }

    item {
      Text(
        text = "INDEPENDENT CAPABILITY PIPELINES",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = TextHighlight
      )
    }

    item {
      CapabilityStatusCard(
        title = "Text / Reasoning (ABAB 6.5s)",
        description = "Large language model with tool calling & dialogue",
        status = status.textReasoning,
        icon = Icons.Default.Code
      )
    }

    item {
      CapabilityStatusCard(
        title = "Speech Synthesis (t2a_v2 / Speech-01)",
        description = "High fidelity neural voice streaming",
        status = status.speech,
        icon = Icons.Default.Mic
      )
    }

    item {
      CapabilityStatusCard(
        title = "Video Generation (video-01)",
        description = "Asynchronous generative video synthesis task queue",
        status = status.video,
        icon = Icons.Default.Videocam
      )
    }

    item {
      CapabilityStatusCard(
        title = "Music Generation (music-01)",
        description = "Song and backing track audio composition",
        status = status.music,
        icon = Icons.Default.MusicNote
      )
    }

    item {
      OutlinedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onProbe
      ) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Probe MiniMax Endpoints", fontSize = 11.sp)
      }
    }
  }
}

// -------------------------------------------------------------------------------------
// 6. LOCAL ANDROID MODELS
// -------------------------------------------------------------------------------------
@Composable
private fun LocalAndroidModelsSection(
  localHost: com.example.core.runtime.LocalModelHost? = null,
  onWeightsChanged: () -> Unit = {}
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var importMessage by remember { mutableStateOf<String?>(null) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      SectionCard(title = "ON-DEVICE SOVEREIGN COMPUTE (arm64-v8a)", icon = Icons.Default.Computer) {
        Text(
          text = "Run open-weights LLMs entirely on local hardware with zero network transmission.",
          fontSize = 11.sp,
          color = TextSecondary
        )
        val im = importMessage
        if (im != null) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(im, fontSize = 11.sp, color = EmeraldOnline, fontWeight = FontWeight.SemiBold)
        }
      }
    }

    item {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PurpSurfaceCard,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder)
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("Gemma 2 2B Instruct", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
              Text("Q4_K_M · ~1.6 GB · Google DeepMind", fontSize = 10.sp, color = TextMuted)
            }
            TagChip("NOT INSTALLED", AmberHybrid)
          }

          Spacer(modifier = Modifier.height(8.dp))
          Text(
            "Expected location: app files dir /models/ · RAM: ~2.4 GB · Context: 8192",
            fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, color = TextSecondary
          )

          Spacer(modifier = Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
              // REAL ACTION: probe the app's models dir for any .gguf weights
              try {
                val modelsDir = java.io.File(context.filesDir, "models")
                val found = modelsDir.listFiles { f -> f.name.endsWith(".gguf", ignoreCase = true) }
                importMessage = if (!found.isNullOrEmpty()) {
                  "Found ${found.size} weight file(s): ${found.joinToString { it.name }}"
                } else {
                  "No .gguf in ${modelsDir.absolutePath}. Push via: adb push model.gguf <filesDir>/models/"
                }
              } catch (e: Exception) {
                importMessage = "Probe failed: ${e.message}"
              }
              onWeightsChanged()
            }) {
              Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Scan for .GGUF", fontSize = 10.sp)
            }
            ElevatedButton(
              onClick = {
                // REAL ACTION: show exact adb command for this device
                importMessage = "adb push gemma.gguf /data/data/com.aistudio.purpclaw.osv7/files/models/"
              },
              colors = ButtonDefaults.elevatedButtonColors(containerColor = PurpPrimary)
            ) {
              Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Install guide", fontSize = 10.sp)
            }
          }
        }
      }
    }
  }
}

// -------------------------------------------------------------------------------------
// 7. HOME-PC ROUTING SECTION
// -------------------------------------------------------------------------------------
@Composable
private fun HomePcRoutingSection(
  state: RoutingState,
  onUpdateState: ((RoutingState) -> RoutingState) -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      SectionCard(title = "HOME PURPCLAW WORKSTATION", icon = Icons.Default.Computer) {
        ToggleRow(
          title = "Use Home routing",
          description = "When attached, Home ProviderRouter is authority. Phone displays Home state without re-running AUTO",
          checked = state.useHomeRouting,
          onCheckedChange = { onUpdateState { s -> s.copy(useHomeRouting = it) } }
        )
        HorizontalDivider(color = PurpBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
        ToggleRow(
          title = "Prefer Home compute",
          description = "Route heavy reasoning and tool workflows to Home Rig hardware",
          checked = state.preferHomeCompute,
          onCheckedChange = { onUpdateState { s -> s.copy(preferHomeCompute = it) } }
        )
      }
    }

    item {
      SectionCard(title = "HOME ROUTING STATE", icon = Icons.Default.Info) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Home Provider: ${state.homeProvider}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
          Text("Resolved: Home PC · ${state.homeResolvedModel ?: "pending core report"}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = EmeraldOnline)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "DEFAULT LAW: When Home is online, Home ProviderRouter is authority. When Home dies, LOCAL TAKEOVER activates Android ProviderRouter seamlessly.",
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            color = TextMuted
          )
        }
      }
    }
  }
}

// -------------------------------------------------------------------------------------
// COMMON UI HELPERS
// -------------------------------------------------------------------------------------
@Composable
private fun SectionCard(
  title: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  content: @Composable () -> Unit
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = PurpSurfaceCard),
    shape = RoundedCornerShape(8.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder)
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
      ) {
        Icon(icon, contentDescription = null, tint = PurpNeon, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = title,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace,
          color = TextPrimary
        )
      }
      content()
    }
  }
}

@Composable
private fun ToggleRow(
  title: String,
  description: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
      Text(description, fontSize = 9.5.sp, color = TextSecondary)
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = PurpNeon,
        uncheckedThumbColor = TextMuted,
        uncheckedTrackColor = PurpSurfaceElevated
      )
    )
  }
}

@Composable
private fun CheckboxRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onCheckedChange(!checked) }
      .padding(vertical = 2.dp)
  ) {
    Checkbox(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = CheckboxDefaults.colors(
        checkedColor = PurpNeon,
        checkmarkColor = Color.White,
        uncheckedColor = PurpBorder
      )
    )
    Spacer(modifier = Modifier.width(4.dp))
    Text(
      text = label,
      fontSize = 11.sp,
      color = if (checked) TextPrimary else TextSecondary
    )
  }
}

@Composable
private fun ModelRowCard(
  model: CatalogueModel,
  isQualified: Boolean
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp)),
    color = PurpSurfaceElevated,
    border = androidx.compose.foundation.BorderStroke(1.dp, if (isQualified) PurpBorder else RoseOffline.copy(alpha = 0.5f))
  ) {
    Column(modifier = Modifier.padding(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = if (isQualified) "✓" else "✕",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isQualified) EmeraldOnline else RoseOffline
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = model.name,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
          )
        }
        Text(
          text = "${model.avgLatencyMs}ms avg",
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace,
          color = EmeraldOnline
        )
      }

      Spacer(modifier = Modifier.height(3.dp))
      if (isQualified) {
        Text(
          text = "chat · ${if (model.isToolCapable) "tools · " else ""}${model.contextLength / 1024}K context",
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace,
          color = TextSecondary
        )
      } else {
        Text(
          text = "${model.exclusionReason ?: "excluded"}",
          fontSize = 9.sp,
          fontFamily = FontFamily.Monospace,
          color = RoseOffline
        )
      }
    }
  }
}

@Composable
private fun CapabilityStatusCard(
  title: String,
  description: String,
  status: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector
) {
  val statusColor = when (status) {
    "LIVE" -> EmeraldOnline
    "DEGRADED" -> AmberHybrid
    else -> TextMuted
  }

  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = PurpSurfaceCard,
    shape = RoundedCornerShape(6.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder)
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
        Icon(icon, contentDescription = null, tint = PurpNeon, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Text(title, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
          Text(description, fontSize = 9.sp, color = TextSecondary)
        }
      }
      TagChip(status, statusColor)
    }
  }
}

@Composable
private fun TagChip(text: String, color: Color) {
  Text(
    text = text,
    fontSize = 7.5.sp,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    color = color,
    modifier = Modifier
      .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
      .padding(horizontal = 4.dp, vertical = 2.dp)
  )
}

// =====================================================================================
// PROVIDER LIBRARY — provider-law 2026-08-26
//
// Two clearly-separated sections:
//   1. FREE GATEWAYS  — OpenRouter + NVIDIA NIM. AUTO always pulls from this pool.
//      No key required for limited browsing, but real calls need an OpenRouter key.
//   2. DIRECT / PRO   — MiniMax, Kimi, Qwen, DeepSeek, OpenAI, Z.ai. Each is a
//      separate user-configured account. Until the key is present the row
//      renders an "Add API key" tile; once configured the live model list
//      appears. AUTO never reaches here — direct providers are MANUAL-only.
//
// Every row carries provenance (providerType, sourceProvider, free, configured)
// so the UI never has to guess what lane a model belongs to.
// =====================================================================================

@Composable
private fun ProviderLibrarySection(
  openRouterCatalogue: List<CatalogueModel>,
  nimCatalogue: List<CatalogueModel>,
  minimaxCatalogue: List<CatalogueModel>,
  kimiCatalogue: List<CatalogueModel>,
  qwenCatalogue: List<CatalogueModel>,
  deepseekCatalogue: List<CatalogueModel>,
  openaiCatalogue: List<CatalogueModel>,
  zaiCatalogue: List<CatalogueModel>,
  longcatCatalogue: List<CatalogueModel>,
  hasOpenRouterKey: Boolean,
  hasNimKey: Boolean,
  hasMinimaxKey: Boolean,
  hasKimiKey: Boolean,
  hasQwenKey: Boolean,
  hasDeepseekKey: Boolean,
  hasOpenaiKey: Boolean,
  hasZaiKey: Boolean,
  hasLongcatKey: Boolean,
  onRefreshGateway: () -> Unit,
  onConnectOpenRouter: () -> Unit,
  onRefreshNim: () -> Unit,
  onOpenKeyDialog: (vaultKey: String, label: String) -> Unit,
  onClearKey: (String) -> Unit,
  onSelectModel: (CatalogueModel) -> Unit,
  onPinModel: (String) -> Unit,
  providerRouter: ProviderRouter
) {
  val freePool = providerRouter.queryAllFreeGateways()
  val directPool = providerRouter.queryAllDirect()
  var freeExpanded by remember { mutableStateOf(true) }
  var directExpanded by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      SectionCard(title = "PROVIDER LAW", icon = Icons.Default.Info) {
        Text(
          text = "Free by default. Powerful by choice. Paid only when the operator deliberately asks for it.",
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          color = EmeraldOnline
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "AUTO mode only routes through FREE GATEWAYS (OpenRouter + NVIDIA NIM). " +
            "Direct providers are MANUAL-only and gated by the Spend policy below.",
          fontSize = 10.sp,
          color = TextSecondary
        )
      }
    }

    // ── FREE GATEWAYS ──────────────────────────────────────────────────────────
    item {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { freeExpanded = !freeExpanded },
        color = PurpSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldOnline)
      ) {
        Row(
          modifier = Modifier.padding(12.dp).fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "FREE GATEWAYS",
              fontSize = 11.sp,
              fontWeight = FontWeight.Black,
              fontFamily = FontFamily.Monospace,
              letterSpacing = 2.sp,
              color = EmeraldOnline
            )
            Text(
              text = "${freePool.size} live free models · AUTO routes from here",
              fontSize = 10.sp,
              color = TextSecondary
            )
          }
          Text(if (freeExpanded) "▾" else "▸", color = EmeraldOnline, fontSize = 14.sp)
        }
      }
    }
    if (freeExpanded) {
      item {
        FreeGatewayTile(
          name = "OpenRouter",
          baseUrl = "openrouter.ai",
          hasKey = hasOpenRouterKey,
          modelCount = openRouterCatalogue.count { it.isFree },
          onRefresh = onRefreshGateway,
          onConnect = onConnectOpenRouter,
          onOpenKeyDialog = { onOpenKeyDialog("OPENROUTER_API_KEY", "OpenRouter API Key") },
          onClearKey = { onClearKey("OPENROUTER_API_KEY") }
        )
      }
      item {
        FreeGatewayTile(
          name = "NVIDIA NIM",
          baseUrl = "integrate.api.nvidia.com",
          hasKey = hasNimKey,
          modelCount = nimCatalogue.size,
          onRefresh = onRefreshNim,
          onConnect = null,
          onOpenKeyDialog = { onOpenKeyDialog("NVIDIA_NIM_API_KEY", "NVIDIA NIM API Key") },
          onClearKey = { onClearKey("NVIDIA_NIM_API_KEY") }
        )
      }
      if (freePool.isEmpty()) {
        item {
          Text(
            text = "Add at least one gateway key to populate the AUTO free pool. " +
              "OpenRouter is recommended (multi-vendor free models).",
            fontSize = 11.sp,
            color = TextSecondary
          )
        }
      } else {
        items(freePool) { model ->
          FreeModelRow(model = model, onClick = { onSelectModel(model) })
        }
      }
    }

    // ── DIRECT / PRO ───────────────────────────────────────────────────────────
    item {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { directExpanded = !directExpanded },
        color = PurpSurfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, AmberHybrid)
      ) {
        Row(
          modifier = Modifier.padding(12.dp).fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "DIRECT / PRO",
              fontSize = 11.sp,
              fontWeight = FontWeight.Black,
              fontFamily = FontFamily.Monospace,
              letterSpacing = 2.sp,
              color = AmberHybrid
            )
            Text(
              text = "${directPool.size} live paid models · MANUAL only · spend-gated",
              fontSize = 10.sp,
              color = TextSecondary
            )
          }
          Text(if (directExpanded) "▾" else "▸", color = AmberHybrid, fontSize = 14.sp)
        }
      }
    }
    if (directExpanded) {
      item {
        DirectProviderTile(
          source = ProviderSource.MINIMAX,
          hasKey = hasMinimaxKey,
          catalogue = minimaxCatalogue,
          onOpenKeyDialog = { onOpenKeyDialog("MINIMAX_API_KEY", "MiniMax API Key") },
          onClearKey = { onClearKey("MINIMAX_API_KEY") },
          onSelectModel = onSelectModel
        )
      }
      item {
        DirectProviderTile(
          source = ProviderSource.KIMI,
          hasKey = hasKimiKey,
          catalogue = kimiCatalogue,
          onOpenKeyDialog = { onOpenKeyDialog("KIMI_API_KEY", "Kimi API Key") },
          onClearKey = { onClearKey("KIMI_API_KEY") },
          onSelectModel = onSelectModel
        )
      }
      item {
        DirectProviderTile(
          source = ProviderSource.QWEN,
          hasKey = hasQwenKey,
          catalogue = qwenCatalogue,
          onOpenKeyDialog = { onOpenKeyDialog("QWEN_API_KEY", "Qwen API Key") },
          onClearKey = { onClearKey("QWEN_API_KEY") },
          onSelectModel = onSelectModel
        )
      }
      item {
        DirectProviderTile(
          source = ProviderSource.DEEPSEEK,
          hasKey = hasDeepseekKey,
          catalogue = deepseekCatalogue,
          onOpenKeyDialog = { onOpenKeyDialog("DEEPSEEK_API_KEY", "DeepSeek API Key") },
          onClearKey = { onClearKey("DEEPSEEK_API_KEY") },
          onSelectModel = onSelectModel
        )
      }
      item {
        DirectProviderTile(
          source = ProviderSource.OPENAI,
          hasKey = hasOpenaiKey,
          catalogue = openaiCatalogue,
          onOpenKeyDialog = { onOpenKeyDialog("OPENAI_API_KEY", "OpenAI API Key") },
          onClearKey = { onClearKey("OPENAI_API_KEY") },
          onSelectModel = onSelectModel
        )
      }
      item {
        DirectProviderTile(
          source = ProviderSource.ZAI,
          hasKey = hasZaiKey,
          catalogue = zaiCatalogue,
          onOpenKeyDialog = { onOpenKeyDialog("ZAI_API_KEY", "Z.ai API Key") },
          onClearKey = { onClearKey("ZAI_API_KEY") },
          onSelectModel = onSelectModel
        )
      }
      item {
        DirectProviderTile(
          source = ProviderSource.LONGCAT,
          hasKey = hasLongcatKey,
          catalogue = longcatCatalogue,
          onOpenKeyDialog = { onOpenKeyDialog("LONGCAT_API_KEY", "LongCat API Key") },
          onClearKey = { onClearKey("LONGCAT_API_KEY") },
          onSelectModel = onSelectModel
        )
      }
    }
  }
}

@Composable
private fun FreeGatewayTile(
  name: String,
  baseUrl: String,
  hasKey: Boolean,
  modelCount: Int,
  onRefresh: () -> Unit,
  onConnect: (() -> Unit)? = null,
  onOpenKeyDialog: () -> Unit,
  onClearKey: () -> Unit
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = PurpSurface,
    shape = RoundedCornerShape(6.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder)
  ) {
    Row(
      modifier = Modifier.padding(10.dp).fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(baseUrl, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
        Text(
          text = if (hasKey) "$modelCount live models · key configured" else "$modelCount seed models · set key to refresh",
          fontSize = 9.5.sp,
          color = if (hasKey) EmeraldOnline else TextSecondary
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onConnect != null) {
          OutlinedButton(onClick = onConnect) { Text("Connect", fontSize = 10.sp, color = CyanAccent) }
        }
        if (hasKey) {
          OutlinedButton(onClick = onRefresh) { Text("Refresh", fontSize = 10.sp) }
          OutlinedButton(onClick = onClearKey) { Text("Clear", fontSize = 10.sp, color = RoseOffline) }
        }
        ElevatedButton(
          onClick = onOpenKeyDialog,
          colors = ButtonDefaults.elevatedButtonColors(containerColor = if (hasKey) PurpPrimary else PurpNeon)
        ) { Text(if (hasKey) "Update Key" else "Add API Key", fontSize = 10.sp, color = Color.White) }
      }
    }
  }
}

@Composable
private fun DirectProviderTile(
  source: ProviderSource,
  hasKey: Boolean,
  catalogue: List<CatalogueModel>,
  onOpenKeyDialog: () -> Unit,
  onClearKey: () -> Unit,
  onSelectModel: (CatalogueModel) -> Unit
) {
  var expanded by remember { mutableStateOf(false) }
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp)),
    color = PurpSurface,
    border = androidx.compose.foundation.BorderStroke(1.dp, if (hasKey) AmberHybrid else PurpBorder)
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(source.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
          Text(source.baseUrl.removePrefix("https://"), fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted)
          Text(
            text = if (hasKey) "key configured · ${catalogue.size} models" else "key missing — AUTO never routes here",
            fontSize = 9.5.sp,
            color = if (hasKey) AmberHybrid else TextSecondary
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (hasKey) {
            OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Models", fontSize = 10.sp) }
            OutlinedButton(onClick = onClearKey) { Text("Clear", fontSize = 10.sp, color = RoseOffline) }
          }
          ElevatedButton(
            onClick = onOpenKeyDialog,
            colors = ButtonDefaults.elevatedButtonColors(containerColor = if (hasKey) PurpPrimary else PurpNeon)
          ) { Text(if (hasKey) "Update Key" else "Add API Key", fontSize = 10.sp, color = Color.White) }
        }
      }
      if (expanded && hasKey && catalogue.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        catalogue.take(8).forEach { m ->
          DirectModelRow(model = m, onClick = { onSelectModel(m) })
        }
        if (catalogue.size > 8) {
          Text("+ ${catalogue.size - 8} more", fontSize = 9.sp, color = TextMuted)
        }
      }
    }
  }
}

@Composable
private fun DirectModelRow(model: CatalogueModel, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onClick() }
      .padding(vertical = 4.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(model.id.substringAfter("/"), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
      Text(model.description, fontSize = 9.sp, color = TextSecondary, maxLines = 1)
    }
    TagChip(if (model.isFree) "FREE" else "PAID", if (model.isFree) EmeraldOnline else AmberHybrid)
  }
}

@Composable
private fun FreeModelRow(model: CatalogueModel, onClick: () -> Unit) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .clickable { onClick() },
    color = PurpSurfaceElevated,
    border = androidx.compose.foundation.BorderStroke(1.dp, PurpBorder)
  ) {
    Row(
      modifier = Modifier.padding(8.dp).fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(model.name, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(model.id, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMuted, maxLines = 1)
        Text(
          text = "${model.sourceProvider} · ${model.endpointSource}",
          fontSize = 8.5.sp,
          color = TextSecondary
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (model.isToolCapable) TagChip("TOOLS", PurpNeon)
        if (model.isVisionCapable) TagChip("VISION", CyanAccent)
        if (model.isReasoningCapable) TagChip("REASONING", AmberHybrid)
        TagChip("FREE", EmeraldOnline)
      }
    }
  }
}

// =====================================================================================
// SPEND SECTION — provider-law 2026-08-26
//
// Three modes:
//   OFF               — paid lanes blocked; AUTO FREE only.
//   ASK               — each paid call requires explicit operator approval per turn.
//   AUTO_WITH_LIMIT   — paid calls auto-proceed if under daily + per-job cap.
// Counter persists in SharedPreferences and resets on local-date rollover.
// =====================================================================================

@Composable
private fun SpendSection(
  policy: SpendPolicy,
  snapshot: SpendSnapshot,
  onUpdatePolicy: (SpendPolicy) -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      SectionCard(title = "SPEND POLICY", icon = Icons.Default.Security) {
        listOf(SpendMode.OFF, SpendMode.ASK, SpendMode.AUTO_WITH_LIMIT).forEach { mode ->
          val isSel = policy.mode == mode
          val (label, desc) = when (mode) {
            SpendMode.OFF -> "OFF" to "Paid lanes blocked. AUTO routes free gateways only."
            SpendMode.ASK -> "ASK" to "Each paid call requires explicit operator approval per turn."
            SpendMode.AUTO_WITH_LIMIT -> "AUTO WITH LIMIT" to "Paid calls auto-proceed if under cap."
          }
          Surface(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 3.dp)
              .clip(RoundedCornerShape(6.dp))
              .clickable { onUpdatePolicy(policy.copy(mode = mode)) },
            color = if (isSel) PurpPrimary else PurpSurfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) PurpNeon else PurpBorder)
          ) {
            Column(modifier = Modifier.padding(8.dp)) {
              Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isSel) Color.White else TextPrimary)
              Text(desc, fontSize = 9.5.sp, color = if (isSel) Color.White.copy(alpha = 0.8f) else TextSecondary)
            }
          }
        }
      }
    }

    item {
      SectionCard(title = "LIMITS", icon = Icons.Default.Tune) {
        SpendLimitField(
            title = "Daily cap (¢ USD)",
            value = policy.dailyCapCents,
            onValueChange = { onUpdatePolicy(policy.copy(dailyCapCents = it.coerceAtLeast(0))) },
            enabled = policy.mode == SpendMode.AUTO_WITH_LIMIT
          )
        Spacer(modifier = Modifier.height(6.dp))
        SpendLimitField(
          title = "Per-job cap (¢ USD)",
          value = policy.perJobCapCents,
          onValueChange = { onUpdatePolicy(policy.copy(perJobCapCents = it.coerceAtLeast(0))) },
          enabled = policy.mode == SpendMode.AUTO_WITH_LIMIT
        )
      }
    }

    item {
      SectionCard(title = "TODAY", icon = Icons.Default.Info) {
        Text(
          text = "Spent: %.2f¢ / %s¢".format(snapshot.spentCentsToday, snapshot.capCents.toString()),
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace,
          color = TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Resets at local midnight. Receipts carry costCents per turn.",
          fontSize = 9.5.sp,
          color = TextSecondary
        )
      }
    }
  }
}

@Composable
private fun SpendLimitField(
  title: String,
  value: Int,
  onValueChange: (Int) -> Unit,
  enabled: Boolean
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Text(title, fontSize = 11.sp, color = TextPrimary)
    OutlinedTextField(
      value = value.toString(),
      onValueChange = { txt -> onValueChange(txt.toIntOrNull() ?: 0) },
      singleLine = true,
      enabled = enabled,
      modifier = Modifier.width(110.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = PurpNeon,
        unfocusedBorderColor = PurpBorder,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary
      )
    )
  }
}
