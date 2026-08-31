package com.example.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Deterministic auto-hide authority for the liquid bottom nav.
 *
 * Priority ladder (highest wins):
 *   program mode (user/app) > open menu (LOCKED_VISIBLE) > keyboard
 *   (LOCKED_HIDDEN) > scroll hysteresis > idle timer.
 *
 * Scroll hysteresis: hide after ~28px cumulative downward drag,
 * show again after ~14px cumulative upward drag. Whole-bar motion is
 * transform-only (handled by the caller animating a 0..1 fraction);
 * this class only owns the boolean truth.
 */
class BottomNavVisibility {

  enum class ProgramMode { AUTO, VISIBLE, HIDDEN, IMMERSIVE }

  var programMode by mutableStateOf(ProgramMode.AUTO)
    private set
  var hiddenByScroll by mutableStateOf(false)
    private set
  var hiddenByIdle by mutableStateOf(false)
    private set

  private var scrollAccum = 0f
  private var lastInteraction = System.currentTimeMillis()
  private var routeGraceUntil = 0L

  fun applyProgramMode(mode: ProgramMode) {
    programMode = mode
    hiddenByScroll = false
    hiddenByIdle = false
  }

  /**
   * Feed raw vertical scroll deltas (positive = content scrolling up/downward).
   * dy < 0 means finger dragged up (content scrolls down) → candidate hide.
   */
  fun onScrollDelta(dy: Float) {
    lastInteraction = System.currentTimeMillis()
    if (programMode != ProgramMode.AUTO) return
    if (dy < 0f) {
      scrollAccum += -dy
      if (scrollAccum >= 28f) hiddenByScroll = true
    } else if (dy > 0f) {
      scrollAccum -= dy
      if (scrollAccum <= -14f) {
        hiddenByScroll = false
        hiddenByIdle = false
        scrollAccum = 0f
      }
    }
  }

  /** Any tap anywhere refreshes the idle window. */
  fun notifyInteraction() {
    lastInteraction = System.currentTimeMillis()
  }

  /** Fresh route always lands with the bar visible and an idle grace window. */
  fun notifyRouteChanged() {
    val now = System.currentTimeMillis()
    lastInteraction = now
    routeGraceUntil = now + 3000
    hiddenByScroll = false
    hiddenByIdle = false
  }

  /** Bottom-edge reveal gesture (swipe up on the thin strip). */
  fun onRevealGesture() {
    scrollAccum = 0f
    hiddenByScroll = false
    hiddenByIdle = false
    lastInteraction = System.currentTimeMillis()
  }

  /**
   * Idle sweep — call roughly once per second. Hides after 4.5s of quiet,
   * never while a menu is open, the keyboard is up, or inside the
   * post-route-change grace window.
   */
  fun tickIdle(menuOpen: Boolean, imeVisible: Boolean) {
    if (programMode != ProgramMode.AUTO) return
    if (menuOpen || imeVisible) return
    val now = System.currentTimeMillis()
    if (now < routeGraceUntil) return
    if (now - lastInteraction > 4500) hiddenByIdle = true
  }

  /**
   * Single source of truth for "should the bar be off-screen right now".
   * Menu open forces visible; keyboard forces hidden; explicit program
   * modes override everything in their tier.
   */
  fun isHidden(imeVisible: Boolean, menuOpen: Boolean): Boolean = when (programMode) {
    ProgramMode.VISIBLE -> false
    ProgramMode.HIDDEN, ProgramMode.IMMERSIVE -> true
    ProgramMode.AUTO -> !menuOpen && !imeVisible && (hiddenByScroll || hiddenByIdle)
  }
}
