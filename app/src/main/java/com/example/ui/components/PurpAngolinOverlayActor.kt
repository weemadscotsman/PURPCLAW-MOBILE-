package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.R
import kotlin.math.roundToInt

/**
 * PurpAngolinOverlayActor — task #63.
 *
 * Live 3D companion rendered as a full-screen stage with the rig centered
 * in the middle of the screen, head-to-toes visible.
 *
 *   • The 3D stage is Modifier.fillMaxSize() — the rig spans the full
 *     screen so the operator sees the complete body, not a clipped head.
 *   • The gesture hit-area is a separate small Box aligned BottomEnd —
 *     only touches that begin inside it are consumed; everything outside
 *     the hit-area passes through to chat / composer untouched, so
 *     message bubbles, action sheets, and the composer all keep working.
 *   • Pinch-to-zoom inside the hit-area resizes the rig (0.2×–2.5×).
 *   • Drag pans the rig in its own world space, clamped to a head-to-toes
 *     envelope so it cannot drift off-screen.
 *   • One-finger horizontal drag spins the rig on Y.
 *   • Two fingers pinch and pan to resize/move the rig.
 *   • Double-tap hides; the compact visibility icon restores it.
 *   • Long-press toggles reduced-motion (static render, no animation).
 *   • Tap on the small reset square snaps the rig back to the centered
 *     default anchor at scale 1.0.
 *   • Scale + pan + hidden + reduced persist in SharedPreferences.
 *
 * The inner SceneView/Filament actor consumes the same mood contract as
 * the lightweight 2D per-reply avatar. Gestures and persistence remain
 * in this shell so swapping companion models never changes interaction law.
 */
@Composable
fun PurpAngolinOverlayActor(
  mood: PurpAngolinMood,
  character: String = "purpangolin",
  outputLevel: Float = 0f,
  interactionEnabled: Boolean = true,
  gesturesEnabled: Boolean = true,
  modifier: Modifier = Modifier
) {
  // v11 invalidates the pre-safe-region transforms persisted while the
  // boots-only and first composer-seat builds were being device-tested.
  // Once v11 is written, ordinary user scale/position/yaw persist again.
  val layoutVersion = 15
  val context = LocalContext.current
  val prefs = remember {
    context.getSharedPreferences("purpclaw_avatar_prefs", Context.MODE_PRIVATE)
  }
  val resetLegacyGeometry = remember { prefs.getInt("layout_version", 0) < layoutVersion }
  // Full-screen stage, small mascot default: seat her at the composer's
  // right edge while leaving the reply column readable. The stage remains
  // full-screen; only the rig transform is small and offset.
  //
  // DEFENSIVE LOAD: previous sessions could leave offset values that far
  // exceed any plausible screen extent. On a 1440x3120 S25 a stored
  // dX=-1354 / dY=-2847 pans the rig hundreds of pixels off-screen and
  // pushes the on-screen reset chip completely out of reach. Clamp to
  // ±screen on load so the avatar can always be re-anchored by tapping
  // reset inside the gesture handle, even after a bad drag.
  val configuration0 = LocalConfiguration.current
  // Configuration exposes dp; px derived via density.
  val density0 = LocalDensity.current
  val screenW = with(density0) { configuration0.screenWidthDp.dp.toPx() }.toInt()
  val screenH = with(density0) { configuration0.screenHeightDp.dp.toPx() }.toInt()
  // World-bounds clamp: |panWorld| = |offset / widthPx| * 1.6f must be <= 1.0 so the rig stays
  // inside the camera frustum. On the 1440x3120 S25 the legacy +/-60% screen was +/-864px =
  // +/-0.96 world-units, just outside the frustum edge. Anything larger pushes the rig off-screen.
  val maxOffX = (screenW / 1.6f).toInt()
  val maxOffY = (screenH / 1.6f).toInt()
  fun sanitizeOffset(rawX: Int, rawY: Int): IntOffset =
    IntOffset(rawX.coerceIn(-maxOffX, maxOffX), rawY.coerceIn(-maxOffY, maxOffY))
  val defaultOffset = IntOffset.Zero
  var scale by remember { mutableStateOf(if (resetLegacyGeometry) 0.40f else prefs.getFloat("scale", 0.40f)) }
  var offset by remember {
    mutableStateOf(if (resetLegacyGeometry) defaultOffset else
      sanitizeOffset(prefs.getInt("dX", 0), prefs.getInt("dY", 0))
    )
  }
  var hidden by remember { mutableStateOf(if (resetLegacyGeometry) false else prefs.getBoolean("hidden", false)) }
  var reducedMotion by remember { mutableStateOf(prefs.getBoolean("reduced", false)) }
  var rotationY by remember { mutableStateOf(if (resetLegacyGeometry) 0f else prefs.getFloat("rotation_y", 0f)) }
  var activePointerCount by remember { mutableIntStateOf(0) }
  var relocating by remember { mutableStateOf(false) }
  var showControls by remember { mutableStateOf(false) }
  var manualMood by remember { mutableStateOf<PurpAngolinMood?>(null) }

  // Runtime truth always outranks a manual pose. Manual one-shots return to
  // idle automatically; dance intentionally remains active until Sit or a
  // real chat/voice state takes over.
  val displayedMood = if (mood != PurpAngolinMood.IDLE) mood else manualMood ?: mood
  LaunchedEffect(manualMood) {
    when (manualMood) {
      PurpAngolinMood.BOUNCING,
      PurpAngolinMood.FLOURISH,
      PurpAngolinMood.FAILED,
      PurpAngolinMood.THINKING,
      PurpAngolinMood.SPEAKING -> {
        kotlinx.coroutines.delay(2400)
        manualMood = null
      }
      else -> Unit
    }
  }

  fun persist() {
    prefs.edit()
      .putFloat("scale", scale)
      .putInt("dX", offset.x)
      .putInt("dY", offset.y)
      .putBoolean("hidden", hidden)
      .putBoolean("reduced", reducedMotion)
      .putFloat("rotation_y", rotationY)
      .putString("pose", "Chair_Sit_Idle_F")
      .putInt("layout_version", layoutVersion)
      .apply()
  }

  LaunchedEffect(Unit) {
    // Always-on sanity check: if stored offset/scale/rotation are absurd
    // (e.g. dX=-1354 on a 1440px screen, or scale/rotation out of range),
    // force a full reset to defaults and persist. This runs UNCONDITIONALLY,
    // even when layout_version already matches current, because legacy
    // clamping at 2x screen still admits off-screen offsets.
    val rawX = prefs.getInt("dX", 0)
    val rawY = prefs.getInt("dY", 0)
    val absX = kotlin.math.abs(offset.x)
    val absY = kotlin.math.abs(offset.y)
    val scaleOk = scale in 0.15f..1.0f
    val rotOk = rotationY in -720f..720f
    val outOfRange = absX > maxOffX || absY > maxOffY || !scaleOk || !rotOk
    if (outOfRange) {
      scale = 0.40f
      offset = defaultOffset
      rotationY = 0f
      hidden = false
      reducedMotion = false
      persist()
    } else if (resetLegacyGeometry) {
      scale = 0.40f
      offset = defaultOffset
      rotationY = 0f
      hidden = false
      reducedMotion = false
      persist()
    } else {
      // Always run the viewport clamp on load so a stored dX within 2x
      // screen but beyond 0.6x gets pulled back into the rig's roam zone.
      val clamped = sanitizeOffset(offset.x, offset.y)
      if (clamped != offset) {
        offset = clamped
      }
      if (rawX != offset.x || rawY != offset.y) {
        persist()
      }
    }
  }

  val configuration = LocalConfiguration.current
  if (hidden) {
    Box(
      modifier = modifier.fillMaxSize(),
      contentAlignment = Alignment.BottomEnd
    ) {
      Box(
        modifier = Modifier
          .padding(end = 12.dp, bottom = 148.dp)
          .size(36.dp)
          .clip(RoundedCornerShape(8.dp))
          .then(
            if (interactionEnabled) Modifier.pointerInput(Unit) {
              detectTapGestures(onTap = {
                hidden = false
                persist()
              })
            } else Modifier
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Rounded.Visibility,
          contentDescription = "Show avatar",
          modifier = Modifier.size(18.dp).alpha(0.55f)
        )
      }
    }
    return
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
    val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
    // SceneView is backed by a native SurfaceView. Samsung's IME can cover
    // that surface even while Compose relocates the composer, so anchor the
    // complete avatar stage above the larger of the composer band or the
    // live keyboard inset. This keeps head and boots visible while typing.
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    val composerBottomPx = with(density) { 156.dp.toPx() }
    // Never feed the animated IME inset into SurfaceView padding/size.
    // SceneView/Filament can crash natively while its buffers are resized
    // during an active frame. Keep one fixed surface and translate it.
    val keyboardLiftPx = if (imeBottomPx > 0f) {
      (imeBottomPx - composerBottomPx).coerceAtLeast(0f)
    } else 0f
    val renderedOffset = IntOffset(
      offset.x,
      offset.y - keyboardLiftPx.roundToInt()
    )
    // The old fixed 308x392dp hit box was several times larger than the
    // small mascot. Clamping that invisible rectangle made the character
    // feel trapped in one corner even though the renderer was full-screen.
    // Track the approximate *visible body* instead: at the canonical 0.40
    // scale she occupies roughly 110x220dp, and the hit/clamp envelope grows
    // with the user's pinch setting. Minimums keep a tiny mascot touchable;
    // maximums keep a large one inside the viewport.
    val avatarScaleRatio = (scale / 0.40f).coerceIn(0.375f, 2.5f)
    // Seated/turned clips extend sideways well beyond the narrow standing
    // torso. A 150dp envelope at canonical scale covers hands, knees and the
    // hip-pivot silhouette, so touching what is visibly the character always
    // reaches the avatar gesture owner even at the left/right clamps.
    val stageWidth = (150.dp * avatarScaleRatio)
      .coerceIn(90.dp, minOf(maxWidth, 360.dp))
    val stageHeight = (220.dp * avatarScaleRatio)
      .coerceIn(120.dp, minOf(maxHeight, 600.dp))
    val stageWidthPx = with(density) { stageWidth.toPx() }
    val stageHeightPx = with(density) { stageHeight.toPx() }
    val viewportAspect = widthPx / heightPx.coerceAtLeast(1f)
    val speechPulse = 1f + (outputLevel.coerceIn(0f, 1f) * 0.18f)

    fun clampAvatarOffset(candidate: IntOffset): IntOffset {
      val rightInsetPx = with(density) { 8.dp.toPx() }
      val bottomInsetPx = composerBottomPx
      val baseLeft = widthPx - rightInsetPx - stageWidthPx
      val baseTop = heightPx - bottomInsetPx - stageHeightPx
      // The complete fixed renderer stage may travel edge-to-edge. Because
      // the entire stage is clamped, no scale/rotation can push the model
      // outside the application viewport. Positive maxima cancel the
      // default right/composer insets and allow the stage to touch those
      // edges instead of remaining pinned to its original rectangle.
      val minX = -baseLeft
      val maxX = rightInsetPx
      val minY = -baseTop
      // The rendered body may visually overlap/perch on the composer, but
      // its gesture owner must never cover the textbox or control row. This
      // keeps Send/CHAT/WORK/mic/paw tappable even when the model's animated
      // legs extend into that band.
      val maxY = 0f
      // IME/PiP can make the available viewport smaller than the current
      // avatar envelope for a frame. Never construct an empty coerce range;
      // pin to the nearest valid edge until the viewport expands again.
      val safeMinX = minX.coerceAtMost(maxX)
      val safeMinY = minY.coerceAtMost(maxY)
      return IntOffset(
        candidate.x.coerceIn(safeMinX.toInt(), maxX.toInt()),
        candidate.y.coerceIn(safeMinY.toInt(), maxY.toInt())
      )
    }
    LaunchedEffect(widthPx, heightPx, stageWidthPx, stageHeightPx) {
      val clamped = clampAvatarOffset(offset)
      if (clamped != offset) {
        offset = clamped
        persist()
      }
    }
    var lastTapUptime by remember { mutableStateOf(0L) }

    // One invariant EDGE-TO-EDGE renderer surface. It never moves or
    // resizes, so there is no travelling rectangular SurfaceView cut-out.
    // Native touch is disabled inside Avatar3DActor; the character-sized
    // Compose gesture target below is the only interaction owner.
    //
    // Avatar3DActor owns its own SceneView/Filament stage, camera framing,
    // and modelNode transform — we hand it the user-driven offsets in
    // world units (1 unit ≈ head height, per AGENT_NOTES §5). The rig is
    // rendered as a static pose (animation playback is disabled at the
    // model side to keep libgltfio-jni.so from SIGSEGV-ing the Adreno 830
    // skinning path; see Avatar3DActor.kt:283-291 + the inline notes).
    // Speech pulse + reduced-motion still affect scale through the
    // modelScale argument.
    val effectiveScale = scale *
      (if (reducedMotion) 0.85f else 1f) * speechPulse
    // Seat anchor (task #63, 2026-08-28 01:30Z operator spec). The rig sits
    // on the composer's top edge so her hips/feet visually land on the
    // composer band. In world units (post-framing, 1 unit ≈ head height),
    // we push the rig's feet down to the composer top and shift her left
    // toward the chat column's right edge.
    //
    // These are screen-aspect-stable approximations; the on-screen
    // ComposerModifier padding above already keeps her inside the right
    // inset. The values match the rig-half-height (≈0.825) so her pelvis
    // lands roughly on the composer top in portrait S25.
    // The stage itself is the seat anchor. Keep the centred GLB at the
    // camera origin; shifting the rig independently can push its complete
    // body outside the camera even while the stage remains on-screen.
    val rightInsetPx = with(density) { 8.dp.toPx() }
    val baseLeftPx = widthPx - rightInsetPx - stageWidthPx
    val baseTopPx = heightPx - composerBottomPx - stageHeightPx
    val avatarCenterX = baseLeftPx + offset.x + stageWidthPx / 2f
    val avatarCenterY = baseTopPx + renderedOffset.y + stageHeightPx / 2f
    // SceneView applies its 45° camera projection vertically. At z=3.4 the
    // visible world height is 2 * 3.4 * tan(22.5°) ≈ 2.817 units and the
    // portrait width follows from the live surface aspect.
    val visibleWorldHeight = 2.8165f
    val visibleWorldWidth = visibleWorldHeight * viewportAspect
    val seatTranslationX = ((avatarCenterX / widthPx) - 0.5f) * visibleWorldWidth
    val seatTranslationY = -((avatarCenterY / heightPx) - 0.5f) * visibleWorldHeight
    // Render in a separate transparent, NOT_TOUCHABLE Android window. A
    // full-screen SurfaceView embedded in the activity always wins Android's
    // hit test before Compose, even when SceneView.onTouchEvent returns false;
    // that is why the composer and buttons became dead. The renderer window
    // now owns pixels only. The body-sized Compose Box below remains in the
    // activity and is the sole avatar gesture owner.
    Dialog(
      onDismissRequest = {},
      properties = DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false
      )
    ) {
      val dialogWindow = (LocalView.current.parent as DialogWindowProvider).window
      SideEffect {
        dialogWindow.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialogWindow.addFlags(
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        dialogWindow.setLayout(
          WindowManager.LayoutParams.MATCH_PARENT,
          WindowManager.LayoutParams.MATCH_PARENT
        )
      }
      Avatar3DActor(
        mood = displayedMood,
        character = character,
        rotationY = rotationY,
        modelScale = effectiveScale,
        translationX = seatTranslationX,
        translationY = seatTranslationY,
        aspect = viewportAspect,
        touchHandler = null,
        modifier = Modifier
          .fillMaxSize()
          .alpha(if (reducedMotion) 0.85f else 1f)
      )
    }

    // Gesture hit-area — body-sized footprint at the exact same screen
    // centre used by the renderer. Only
    // touches that begin inside this Box are consumed; everything outside
    // is passed through to chat. Long-press = reduced-motion toggle,
    // double-tap = hide. One-finger horizontal drag spins the model; a
    // two-finger transform pinches and pans it through the full-screen stage.
    Box(
      modifier = Modifier
        .offset {
          IntOffset(
            (baseLeftPx + renderedOffset.x).roundToInt(),
            (baseTopPx + renderedOffset.y).roundToInt()
          )
        }
        .size(stageWidth, stageHeight)
        .clip(RoundedCornerShape(12.dp))
        // This body-sized target owns touches that begin on the avatar. The
        // full-screen renderer remains NOT_TOUCHABLE, and the envelope is
        // clamped above the composer, so ordinary chat/buttons outside the
        // visible body remain fully interactive. Sharing stationary taps here
        // made the first tap open the message action sheet before the second
        // tap could open avatar controls.
        .then(
          // Keyboard/composer priority is absolute. Samsung's Compose hit
          // dispatcher does not reliably deliver a shared stationary tap to
          // the underlying Send/WORK button when this pointer node overlaps.
          // Keep her rendered/reactive while typing, but release all avatar
          // touch ownership until the IME closes.
          if (interactionEnabled && gesturesEnabled && imeBottomPx <= 0f) Modifier
            // One gesture owner, three unambiguous contracts:
            // quick one-finger horizontal swipe = hip-centred yaw;
            // hold >=420ms then drag = relocate; two fingers = pinch+move.
            // Competing transform/long-press detectors previously claimed
            // each other's pointer streams and left the avatar immovable.
            .pointerInput(widthPx, heightPx, stageWidthPx, stageHeightPx) {
              awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startPosition = down.position
                val startUptime = down.uptimeMillis
                var endUptime = startUptime
                var previousCentroid = startPosition
                var previousSpan = 0f
                var changedTransform = false

                while (true) {
                  val event = awaitPointerEvent(PointerEventPass.Main)
                  event.changes.firstOrNull()?.let { endUptime = it.uptimeMillis }
                  val pressed = event.changes.filter { it.pressed }
                  activePointerCount = pressed.size
                  if (pressed.isEmpty()) break

                  if (pressed.size >= 2) {
                    val centroid = Offset(
                      pressed.sumOf { it.position.x.toDouble() }.toFloat() / pressed.size,
                      pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressed.size
                    )
                    val span = pressed.map { (it.position - centroid).getDistance() }.average().toFloat()
                    if (previousSpan > 0.5f && span > 0.5f) {
                      scale = (scale * (span / previousSpan)).coerceIn(0.15f, 1.0f)
                    }
                    val delta = centroid - previousCentroid
                    offset = clampAvatarOffset(
                      IntOffset(offset.x + delta.x.roundToInt(), offset.y + delta.y.roundToInt())
                    )
                    previousCentroid = centroid
                    previousSpan = span
                    changedTransform = true
                    pressed.forEach { it.consume() }
                  } else {
                    val change = pressed.first()
                    val delta = change.position - change.previousPosition
                    val elapsed = change.uptimeMillis - startUptime
                    if (relocating || elapsed >= 420L) {
                      relocating = true
                      offset = clampAvatarOffset(
                        IntOffset(offset.x + delta.x.roundToInt(), offset.y + delta.y.roundToInt())
                      )
                      if (delta.getDistance() > 0.5f) changedTransform = true
                    } else if (kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y) &&
                      kotlin.math.abs(delta.x) > 0.5f) {
                      rotationY = (rotationY + delta.x * 0.45f) % 360f
                      changedTransform = true
                    }
                    previousCentroid = change.position
                    previousSpan = 0f
                    // A stationary tap belongs to any underlying UI control
                    // as well. Consume only after this has become an actual
                    // avatar transform (yaw or held relocation).
                    if (changedTransform) change.consume()
                  }
                }

                if (!changedTransform && endUptime - startUptime < 240L) {
                  if (startUptime - lastTapUptime in 1L..320L) {
                    showControls = !showControls
                    lastTapUptime = 0L
                  } else {
                    lastTapUptime = startUptime
                  }
                }
                relocating = false
                activePointerCount = 0
                offset = clampAvatarOffset(offset)
                persist()
              }
            }
          else Modifier
        )
    )

    // Reset target — tiny invisible corner square anchored to the gesture
    // handle's top-left. A plain single tap restores the seated mascot
    // without conflicting with double-tap (hide) or long-press
    // (reduced-motion) inside the main gesture area.
    //
    // Anchored ABSOLUTELY to BottomEnd of the stage — NOT offset by the
    // rig's stored offset. If we offset it, a corrupt dX/dY pushes the
    // reset chip off-screen and the user can't recover the avatar.
    Box(
      modifier = Modifier
        .offset {
          IntOffset(
            (baseLeftPx + renderedOffset.x).roundToInt(),
            (baseTopPx + renderedOffset.y).roundToInt()
          )
        }
        .size(36.dp)
        .shareTouchesWithSiblings()
        .then(
          if (interactionEnabled) Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = {
              scale = 0.40f
              offset = defaultOffset
              rotationY = 0f
              persist()
            })
          } else Modifier
        ),
      contentAlignment = Alignment.Center
    ) { }

    if (showControls) {
      Surface(
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(end = 16.dp, bottom = 430.dp)
          .size(width = 230.dp, height = 370.dp),
        color = Color(0xF21A171F),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 12.dp
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text("PURPANGOLIN", color = Color(0xFF32D5F2))
          Text("${displayedMood.label.uppercase()} · fully rigged", color = Color.White.copy(alpha = 0.72f))
          Row(modifier = Modifier.fillMaxWidth()) {
            listOf(
              "Sit" to PurpAngolinMood.IDLE,
              "Wave" to PurpAngolinMood.BOUNCING,
              "Dance" to PurpAngolinMood.DANCING
            ).forEach { (label, target) ->
              Text(
                label,
                color = Color(0xFFB06CFF),
                modifier = Modifier
                  .weight(1f)
                  .padding(vertical = 10.dp)
                  .clickable { manualMood = if (target == PurpAngolinMood.IDLE) null else target }
              )
            }
          }
          Row(modifier = Modifier.fillMaxWidth()) {
            listOf(
              "Think" to PurpAngolinMood.THINKING,
              "Angry" to PurpAngolinMood.FAILED,
              "Talk" to PurpAngolinMood.SPEAKING
            ).forEach { (label, target) ->
              Text(
                label,
                color = Color.White,
                modifier = Modifier
                  .weight(1f)
                  .padding(vertical = 8.dp)
                  .clickable { manualMood = target }
              )
            }
          }
          Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Mini" to 0.18f, "Small" to 0.28f).forEach { (label, target) ->
              Text(
                label,
                color = Color.White,
                modifier = Modifier
                  .weight(1f)
                  .padding(vertical = 10.dp)
                  .clickable {
                    scale = target
                    persist()
                  }
              )
            }
          }
          Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Normal" to 0.40f, "Large" to 0.75f).forEach { (label, target) ->
              Text(
                label,
                color = Color.White,
                modifier = Modifier
                  .weight(1f)
                  .padding(vertical = 10.dp)
                  .clickable {
                    scale = target
                    persist()
                  }
              )
            }
          }
          Text(
            "FIT / RESET",
            color = Color(0xFFB06CFF),
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 10.dp)
              .clickable {
                scale = 0.40f
                offset = defaultOffset
                rotationY = 0f
                hidden = false
                persist()
              }
          )
          Text(
            "CLOSE",
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.clickable { showControls = false }
          )
        }
      }
    }
  }
}
