package com.example.core.model

/**
 * A user-queued "guest" turn captured mid-podcast via the mic. Pushed to
 * the engine's guest queue while the seats are still talking; consumed by
 * chooseNextSpeaker as the very next role. The episode keeps flowing —
 * the seat rotation is not paused while the user is composing.
 */
data class GuestTurn(
  val id: String,             // "guest_<8hex>"
  val capturedText: String,
  val queuedAtMs: Long,
  val audioPath: String? = null
)
