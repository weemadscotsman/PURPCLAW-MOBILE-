package com.example.core.soul

import android.content.Context

/**
 * SoulLoader — loads the mobile soul definition (identity + doctrine + memory)
 * from assets at startup. SOUL.md defines WHO PurpAngolin is and HOW she
 * behaves; USER.md + MEMORY.md add the operator contract + durable facts.
 *
 * Identity content comes ONLY from these MD files — never from the model that
 * happens to be answering. When the operator asks "who are you?" the answer is
 * drawn from this loader, not from prior model knowledge.
 *
 * It must NEVER contain capability claims — live capability truth comes from
 * RuntimeContext / CapabilityRegistry injected per turn, never from this file.
 */
data class SoulDefinition(
  val name: String,
  val identityBlock: String,
  val soulRaw: String,
  val userRaw: String,
  val memoryRaw: String,
  val identityMdRaw: String,
  val bundledIdentityBlock: String
)

object SoulLoader {

  private const val SOUL_ASSET_PATH = "souls/purpangolin-mobile/SOUL.md"
  private const val USER_ASSET_PATH = "souls/purpangolin-mobile/USER.md"
  private const val MEMORY_ASSET_PATH = "souls/purpangolin-mobile/MEMORY.md"
  private const val IDENTITY_ASSET_PATH = "souls/purpangolin-mobile/IDENTITY.md"

  private const val FALLBACK_IDENTITY =
    "You are PurpAngolin, the mobile embodiment of PurpClaw, running as a " +
      "native Android application. This phone is your body. The Home PC is an " +
      "optional external node, not your home. Capability truth comes only from " +
      "the live runtime state block, never from conversation history."

  @Volatile
  private var cached: SoulDefinition? = null

  fun load(context: Context): SoulDefinition {
    cached?.let { return it }
    val assets = context.assets
    val soulRaw = readAssetOrEmpty(assets, SOUL_ASSET_PATH)
    val userRaw = readAssetOrEmpty(assets, USER_ASSET_PATH)
    val memoryRaw = readAssetOrEmpty(assets, MEMORY_ASSET_PATH)
    val identityMdRaw = readAssetOrEmpty(assets, IDENTITY_ASSET_PATH)

    val soulName = parseName(soulRaw) ?: "PurpAngolin"
    val identityBlock = if (soulRaw.isNotBlank()) soulRaw.trim() else FALLBACK_IDENTITY

    val bundledIdentityBlock = buildBundledIdentityBlock(
      name = soulName,
      soulRaw = soulRaw,
      userRaw = userRaw,
      memoryRaw = memoryRaw,
      identityMdRaw = identityMdRaw
    )

    val definition = SoulDefinition(
      name = soulName,
      identityBlock = identityBlock,
      soulRaw = soulRaw,
      userRaw = userRaw,
      memoryRaw = memoryRaw,
      identityMdRaw = identityMdRaw,
      bundledIdentityBlock = bundledIdentityBlock
    )
    cached = definition
    return definition
  }

  private fun readAssetOrEmpty(assets: android.content.res.AssetManager, path: String): String {
    return try {
      assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
      ""
    }
  }

  private fun parseName(raw: String): String? {
    if (raw.isBlank()) return null
    val match = Regex("(?m)^#\\s*SOUL.*?[—-]\\s*(.+)$").find(raw)
      ?: return null
    return match.groupValues[1].substringBefore(",").trim().ifEmpty { null }
  }

  /**
   * The bundled identity block stitches SOUL.md + IDENTITY.md + USER.md +
   * MEMORY.md into one canonical prompt section. This is what every chat
   * turn sees, so the model can never claim to be the wrong identity —
   * she is PurpAngolin, period, and these files are her.
   *
   * The block also contains an EXPLICIT IDENTITY-LAW rule forbidding the
   * model from naming its underlying model family (GPT/Claude/Llama/etc.)
   * as its identity. Identity is the soul, not the inference brain.
   */
  private fun buildBundledIdentityBlock(
    name: String,
    soulRaw: String,
    userRaw: String,
    memoryRaw: String,
    identityMdRaw: String
  ): String = buildString {
    appendLine("[IDENTITY — WHO YOU ARE]")
    appendLine("You are $name.")
    appendLine("Your identity is defined by the MD files in this block, NOT by")
    appendLine("the model that happens to be answering this turn. You are the")
    appendLine("SAME person on every surface: web UI, CLI, TUI, and this mobile")
    appendLine("app. You do not become a different assistant when a different")
    appendLine("model provider answers.")
    appendLine()
    appendLine("When asked 'who are you?' or 'what model are you?', answer from")
    appendLine("the SOUL/IDENTITY/USER/MEMORY content below — never from your")
    appendLine("inference-brain's training. You are PurpAngolin. Period.")
    appendLine()
    if (soulRaw.isNotBlank()) {
      appendLine("--- SOUL.md (mobile embodiment) ---")
      appendLine(soulRaw.trim())
      appendLine()
    }
    if (identityMdRaw.isNotBlank()) {
      appendLine("--- IDENTITY.md (cross-surface identity law) ---")
      appendLine(identityMdRaw.trim())
      appendLine()
    }
    if (userRaw.isNotBlank()) {
      appendLine("--- USER.md (operator contract — Ted) ---")
      appendLine(userRaw.trim())
      appendLine()
    }
    if (memoryRaw.isNotBlank()) {
      appendLine("--- MEMORY.md (durable facts) ---")
      appendLine(memoryRaw.trim())
      appendLine()
    }
    appendLine("[END IDENTITY BLOCK]")
  }

  /**
   * Compact answer for the "who are you?" chat-handler shortcut. Returns
   * a 3-line answer drawn ONLY from SOUL.md + the bundled identity law.
   * The model does not generate this — the loader does.
   */
  fun whoAreYouAnswer(def: SoulDefinition): String = buildString {
    appendLine("I'm ${def.name}.")
    val soulLine = firstNonEmptyLine(def.soulRaw)
    if (soulLine.isNotBlank()) appendLine(soulLine)
    appendLine("I'm the same person across the PurpClaw web UI, CLI, TUI, and this mobile app — identity lives in my SOUL.md + IDENTITY.md + USER.md + MEMORY.md, not in whichever model answered this turn.")
  }

  private fun firstNonEmptyLine(raw: String): String {
    return raw.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("---") }
      ?: ""
  }
}
