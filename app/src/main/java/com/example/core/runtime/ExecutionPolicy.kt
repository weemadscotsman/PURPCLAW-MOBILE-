package com.example.core.runtime

/**
 * ExecutionPolicy — the single authority that decides whether CHAT or WORK
 * may execute a tool, replacing all legacy gates (review mode, execution
 * lease enforcement, sovereign fallback).
 *
 * Law:
 *  - WORK  → mutating + read tools execute on ANDROID_NATIVE.
 *  - CHAT  → only read-only inspection tools (system.*, status queries) run.
 *            Anything else returns a policy denial the model can act on
 *            (tell the operator to flip CHAT→WORK), never a silent failure.
 */
object ExecutionPolicy {

  enum class Mode { CHAT, WORK }

  /** Tools that are safe in CHAT: pure reads / self-inspection. */
  private val CHAT_ALLOWED = setOf(
    "android.device.info",
    "android.battery.status",
    "android.network.status",
    "android.app.list",
    "android.app.foreground",
    "system.runtime.get",
    "system.identity.get",
    "system.tools.list",
    "system.tools.search",
    "system.capabilities.list",
    "system.capabilities.resolve",
    "system.permissions.list",
    "system.settings.list",
    "system.settings.get",
    "system.home.status",
    "system.provider.status",
    "system.execution.mode",
    "system.resource.inspect",
    "ui.routes.list",
    "ui.route.current",
    "ui.panels.list",
    "ui.settings.schema"
  )

  data class Decision(
    val allowed: Boolean,
    val reason: String,
    val requiresWorkMode: Boolean = false
  )

  fun decide(mode: Mode, toolName: String): Decision = when {
    mode == Mode.WORK ->
      Decision(allowed = true, reason = "WORK mode — full device authority")
    toolName.startsWith("system.") || toolName.startsWith("ui.") ||
      toolName in CHAT_ALLOWED ->
      Decision(allowed = true, reason = "read-only/self-inspection allowed in CHAT")
    else ->
      Decision(
        allowed = false,
        reason = "CHAT mode is conversation-only. Tool '$toolName' needs WORK mode. " +
          "Tell the operator to switch the composer selector to WORK.",
        requiresWorkMode = true
      )
  }
}
