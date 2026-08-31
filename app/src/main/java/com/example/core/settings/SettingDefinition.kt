package com.example.core.settings

/**
 * SettingDefinition / Section / Availability / SettingType
 *
 * Lane 1 (operator 2026-08-28) — single source of truth for Settings.
 * Settings UI must read from [SettingsRegistry], never invent its own state.
 * Anything not declared here MUST NOT appear as a control in the UI.
 */
enum class SettingsSection(val label: String, val order: Int) {
  MODEL_ROUTING("Model Routing", 10),
  SPEND("Spend", 20),
  VOICE("Voice", 30),
  AVATAR("Avatar", 40),
  PROVIDERS("Providers", 50),
  PERMISSIONS("Permissions", 60),
  HEALTH("System Health", 70),
  MEMORY("Memory", 80),
  AGENTS_TOOLS("Agents & Tools", 90),
  DEVICES_MESH("Devices & Mesh", 100),
  PRIVACY("Privacy & Authority", 110),
  DIAGNOSTICS("Diagnostics", 120),
  DEVELOPER("Developer", 999); // hidden behind developer mode

  companion object {
    val PUBLIC: List<SettingsSection> = entries
      .filter { it != DEVELOPER }
      .sortedBy { it.order }
  }
}

enum class SettingType {
  BOOLEAN, INT, FLOAT, STRING, ENUM, SECRET, DURATION_MS
}

/**
 * Hard 9-value availability enum. Every setting in [SettingsRegistry] resolves
 * to exactly one of these — never to a derived local boolean.
 */
enum class Availability(val displayLabel: String, val actionable: Boolean) {
  AVAILABLE("Available", true),
  UNAVAILABLE("Unavailable", false),
  NOT_CONFIGURED("Not configured", true),
  PERMISSION_REQUIRED("Permission required", true),
  OFFLINE("Offline", false),
  DEGRADED("Degraded", true),
  UNSUPPORTED_ON_DEVICE("Unsupported on device", false),
  RESTART_REQUIRED("Restart required", true),
  ERROR("Error", true);

  /** Truthful concatenation: never "ON", always a status string with source. */
  fun describe(reason: String?): String =
    if (reason.isNullOrBlank()) displayLabel else "$displayLabel · $reason"
}

/**
 * Validator for a value before it is accepted into the registry. Returns the
 * cleaned value or an error message. Used by [SettingsRegistry.setTyped] to
 * refuse bad input without silently coercing it.
 */
sealed interface SettingValidator {
  data class IntRange(val min: Int, val max: Int) : SettingValidator
  data class FloatRange(val min: Float, val max: Float) : SettingValidator
  data class OneOf(val options: List<String>) : SettingValidator
  data class RegexMatch(val pattern: String, val hint: String) : SettingValidator
  object NonBlank : SettingValidator
}

/**
 * Lightweight provenance pointer. The registry does not depend on these
 * classes directly — it only stores the string name — so consumers can
 * reference services without creating a cyclic dependency from
 * core.settings → core.runtime.
 */
enum class SettingSource(val authorityName: String) {
  CAPABILITY_REGISTRY("CapabilityTruthRegistry"),
  PROVIDER_ROUTER("ProviderRouter"),
  VOICE_RUNTIME("VoiceModeController"),
  MESH_COORDINATOR("SessionMeshCoordinator"),
  KEYSTORE_VAULT("KeyStoreVault"),
  AVATAR_OVERLAY("PurpAngolinOverlayActor"),
  TOOL_RUNTIME("ToolRuntimeEngine"),
  MEMORY_GATEWAY("MemoryGateway"),
  SPEND_POLICY("ProviderRouter.SpendPolicy"),
  USER_PREFERENCE("UserPreference");
}

/**
 * Metadata for one setting. Does NOT carry the current value — that lives in
 * [SettingState]. Definition is immutable for the lifetime of the registry.
 *
 * @param id canonical id, e.g. "voice.tts.enabled"
 * @param section grouping for UI
 * @param label human-readable short label
 * @param description longer human description
 * @param type [SettingType]
 * @param defaultValue default; used when no persisted state yet exists
 * @param source which runtime service is authority
 * @param capabilityRequired capability id this setting depends on (nullable)
 * @param permissionRequired Android permission id (nullable)
 * @param restartRequired true if changing this requires a process restart
 * @param mutable false = read-only (e.g. device info, runtime identifiers)
 * @param validator optional validator
 */
data class SettingDefinition(
  val id: String,
  val section: SettingsSection,
  val label: String,
  val description: String,
  val type: SettingType,
  val defaultValue: Any,
  val source: SettingSource,
  val capabilityRequired: String? = null,
  val permissionRequired: String? = null,
  val restartRequired: Boolean = false,
  val mutable: Boolean = true,
  val validator: SettingValidator? = null
) {
  init {
    require(id.isNotBlank()) { "SettingDefinition id must not be blank" }
    require(id.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$"))) {
      "SettingDefinition id '$id' must be dot-namespaced (e.g. voice.tts.enabled)"
    }
  }
}
