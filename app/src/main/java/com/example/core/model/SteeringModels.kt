package com.example.core.model

/**
 * Steering Capsule — Android-side projection of the canonical
 * PURPCLAW_STEERING_RESOLVER_CONTRACT. Immutable once compiled.
 *
 * DRIVE is delegated steering authority, NOT a chatbot mode. Arming DRIVE
 * compiles a capsule with authority 900 / effect DELEGATE; the runtime may then
 * self-route CHAT -> PLAN -> EXECUTE -> SWARM -> VERIFY -> CHAT inside this
 * envelope without further operator gestures (gates C23-C26).
 */
enum class DriveState(val label: String) {
  OFF("MANUAL"),
  GUIDED("GUIDED"),
  AUTONOMOUS("AUTONOMOUS")
}

data class SteeringCapsule(
  val capsuleId: String,
  val sourceType: String,
  val authority: Int,
  val effect: String,
  val driveState: DriveState,
  val driveArmed: Boolean,
  val allowedControlSurfaces: List<InteractionMode> =
    listOf(InteractionMode.CHAT, InteractionMode.WORK),
  val forbiddenTools: List<String> = emptyList(),
  val allowedProviders: List<String> = emptyList(),      // empty = unconstrained
  val forbiddenProviders: List<String> = emptyList(),
  val humanGateRequired: Boolean = true,                 // boundary ops always gated
  val maxParallelism: Int = 2,
  val destructiveMutationsAllowed: Boolean = false,
  val compiledAtMs: Long = System.currentTimeMillis()
) {
  companion object {
    fun manual(): SteeringCapsule = SteeringCapsule(
      capsuleId = "caps_manual_${System.currentTimeMillis().toString().takeLast(6)}",
      sourceType = "operator-turn",
      authority = 100,
      effect = "DIRECT",
      driveState = DriveState.OFF,
      driveArmed = false
    )

    fun drive(state: DriveState, maxParallelism: Int = 2): SteeringCapsule = SteeringCapsule(
      capsuleId = "caps_drive_${System.currentTimeMillis().toString().takeLast(6)}",
      sourceType = "operator-delegation",
      authority = 900,
      effect = "DELEGATE",
      driveState = state,
      driveArmed = state == DriveState.AUTONOMOUS || state == DriveState.GUIDED,
      maxParallelism = maxParallelism,
      humanGateRequired = true,
      destructiveMutationsAllowed = false
    )
  }

  /** C24 — minimum necessary action: trivial prompts never escalate surfaces. */
  fun permitsEscalation(promptWordCount: Int): Boolean {
    if (!driveArmed) return false
    return promptWordCount > 8   // "what is 87?" stays in CHAT
  }

  /** C25 — boundary enforcement: destructive/external effects need the human gate. */
  fun requiresHumanGate(toolName: String?): Boolean {
    if (!destructiveMutationsAllowed) {
      val destructive = toolName?.contains("delete", true) == true ||
        toolName?.contains("shell", true) == true ||
        toolName?.contains("format", true) == true
      if (destructive) return true
    }
    return humanGateRequired && !driveArmed
  }

  fun summary(): String = buildString {
    appendLine("CAPSULE ${capsuleId}")
    appendLine("authority=$authority effect=$effect state=${driveState.label}")
    appendLine("surfaces=${allowedControlSurfaces.joinToString(",") { it.name }}")
    appendLine("humanGate=$humanGateRequired maxParallel=$maxParallelism")
    appendLine("destructive=$destructiveMutationsAllowed")
  }
}
