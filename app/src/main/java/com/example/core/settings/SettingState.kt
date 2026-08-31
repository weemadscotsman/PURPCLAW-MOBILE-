package com.example.core.settings

/**
 * Typed wrapper for the current value of a [SettingDefinition].
 *
 * The state is what the UI renders. It carries:
 *   - the current value (typed against [SettingDefinition.type])
 *   - the resolved [Availability] from the authoritative runtime
 *   - an optional error message (validation failure, missing capability, etc.)
 *   - the source timestamp so the UI can show "Updated 5 min ago"
 *
 * State MUST be produced by [SettingsRegistry] reading the source runtime.
 * UI code that creates a SettingState directly is violating the contract.
 */
sealed class SettingState {
  abstract val definition: SettingDefinition
  abstract val availability: Availability
  abstract val reason: String?
  abstract val errorMessage: String?
  abstract val updatedAtMs: Long

  /** Typed accessor. Throws if the wrong [SettingState] subtype is asked. */
  @Suppress("UNCHECKED_CAST")
  fun <T> valueAs(): T = when (this) {
    is BoolState -> currentValue as T
    is IntState -> currentValue as T
    is FloatState -> currentValue as T
    is StrState -> currentValue as T
    is EnumState -> currentValue as T
    is SecretState -> currentValue as T
    is DurationMsState -> currentValue as T
  }

  data class BoolState(
    override val definition: SettingDefinition,
    val currentValue: Boolean,
    override val availability: Availability,
    override val reason: String? = null,
    override val errorMessage: String? = null,
    override val updatedAtMs: Long = System.currentTimeMillis()
  ) : SettingState()

  data class IntState(
    override val definition: SettingDefinition,
    val currentValue: Int,
    override val availability: Availability,
    override val reason: String? = null,
    override val errorMessage: String? = null,
    override val updatedAtMs: Long = System.currentTimeMillis()
  ) : SettingState()

  data class FloatState(
    override val definition: SettingDefinition,
    val currentValue: Float,
    override val availability: Availability,
    override val reason: String? = null,
    override val errorMessage: String? = null,
    override val updatedAtMs: Long = System.currentTimeMillis()
  ) : SettingState()

  data class StrState(
    override val definition: SettingDefinition,
    val currentValue: String,
    override val availability: Availability,
    override val reason: String? = null,
    override val errorMessage: String? = null,
    override val updatedAtMs: Long = System.currentTimeMillis()
  ) : SettingState()

  data class EnumState(
    override val definition: SettingDefinition,
    val currentValue: String,
    val options: List<String>,
    override val availability: Availability,
    override val reason: String? = null,
    override val errorMessage: String? = null,
    override val updatedAtMs: Long = System.currentTimeMillis()
  ) : SettingState() {
    init {
      require(currentValue in options) {
        "EnumState value '$currentValue' not in $options for ${definition.id}"
      }
    }
  }

  /** Secret state never echoes the value to UI; only its presence/absence. */
  data class SecretState(
    override val definition: SettingDefinition,
    val currentValue: String,
    override val availability: Availability,
    override val reason: String? = null,
    override val errorMessage: String? = null,
    override val updatedAtMs: Long = System.currentTimeMillis()
  ) : SettingState() {
    /** True when a non-empty secret is currently stored. */
    val isStored: Boolean get() = currentValue.isNotBlank()
  }

  data class DurationMsState(
    override val definition: SettingDefinition,
    val currentValue: Long,
    override val availability: Availability,
    override val reason: String? = null,
    override val errorMessage: String? = null,
    override val updatedAtMs: Long = System.currentTimeMillis()
  ) : SettingState()
}

/**
 * Outcome of a mutation attempt. The UI MUST only treat Ok as a success and
 * MUST persist the returned state on its own. The registry never silently
 * coerces — every failure path returns [MutationResult.Failed] with a reason.
 */
sealed class MutationResult {
  abstract val settingId: String

  data class Ok(
    override val settingId: String,
    val newState: SettingState
  ) : MutationResult()

  data class Failed(
    override val settingId: String,
    val errorCode: String,
    val reason: String
  ) : MutationResult()

  /** Setting rejected by validator before reaching the source runtime. */
  data class ValidationFailed(
    override val settingId: String,
    val reason: String
  ) : MutationResult()

  /** Setting is read-only or requires an unavailable capability. */
  data class Refused(
    override val settingId: String,
    val reason: String
  ) : MutationResult()
}
