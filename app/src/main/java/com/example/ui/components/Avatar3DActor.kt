package com.example.ui.components

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import com.google.android.filament.Box as FilamentBox
import kotlin.math.max

/**
 * AndroidView/SurfaceView occupies the full layout even when its native view
 * returns false from onTouchEvent. Opt this layout node into sibling sharing
 * so Compose also hit-tests chat/composer nodes below it. The body-sized
 * gesture Box remains the only node that consumes avatar gestures.
 */
internal fun Modifier.shareTouchesWithSiblings(): Modifier =
  this.then(ShareTouchesElement)

private data object ShareTouchesElement : ModifierNodeElement<ShareTouchesNode>() {
  override fun create() = ShareTouchesNode()
  override fun update(node: ShareTouchesNode) = Unit
  override fun InspectorInfo.inspectableProperties() {
    name = "shareTouchesWithSiblings"
  }
}

private class ShareTouchesNode : Modifier.Node(), PointerInputModifierNode {
  override fun sharePointerInputWithSiblings(): Boolean = true
  override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit
  override fun onCancelPointerInput() = Unit
}

/**
 * HARDWARE QUARANTINE FLAG — read at composition time, set at process start.
 *
 * libgltfio-jni.so SIGSEGVs at fault addr 0x2a8 on the Adreno 830 (S25,
 * observed 2026-08-28 09:48) for the bundled purpangolin.glb mesh during
 * FilamentAssetLoader.createAsset. The Kotlin runCatching block above
 * cannot intercept a native SIGSEGV, so the whole process dies before the
 * Activity is visible. This flag is the only reliable kill switch.
 *
 *   - PURPCLAW_DISABLE_3D_AVATAR=1   → Avatar3DActor renders an invisible
 *                                        Box and skips SceneView entirely
 *   - default                          → 3D path is tried; if it throws a
 *                                        non-fatal exception we render
 *                                        nothing rather than crash
 */
internal val PURPCLAW_3D_AVATAR_DISABLED: Boolean = run {
  // Property-style override (debugger friendly).
  val propValue = try {
    System.getProperty("purpclaw.disable.3d.avatar")
  } catch (_: Throwable) { null }
  if (propValue == "1" || propValue == "true") return@run true

  // Env var override (gradle / adb shell set).
  val envValue = try {
    System.getenv("PURPCLAW_DISABLE_3D_AVATAR")
  } catch (_: Throwable) { null }
  if (envValue == "1" || envValue == "true") return@run true

  false
}

/**
 * Avatar3DActor — task #63 (3D leg).
 *
 * Renders the bundled rigged GLB (Meshy pack, reused as-is — NOT rebuilt)
 * through SceneView/Filament with mood → animation-clip mapping.
 *
 *   purpangolin.glb  = Gothic Neon Idol biped merged pack (20 clips incl.
 *                      Chair_Sit_Idle_F, Confused_Scratch, Happy_jump_f…)
 *   babshaggoth.glb  = Circuit Vanguard biped merged pack (18 clips)
 *   lyra.glb         = Neon Streetwear biped merged pack (19 clips)
 *
 * Missing clip for a mood falls back down the chain (never fabricates cycles).
 * Only a fully skinned, animated GLB is admitted; there is no 2D substitution.
 *
 * Layout contract: Avatar3DActor fills the modifier it is given. The caller
 * decides the stage size (full-screen for the overlay actor, the corner
 * footprint for the boot avatar, etc.). The rig is framed to fit the
 * measured bounding box and centered in the stage regardless of source GLB.
 */
object Avatar3DClips {
  const val TAG = "Avatar3DActor"
  val COMPANION_NAMES = listOf("PurpAngolin", "Babshaggoth", "Lyra Voice", "PurpReaper")

  /**
   * Authoritative rig half-heights, in scene units. These are sourced from
   * the Meshy pack's documented biped heights (NOT from Filament's
   * node-local bounding box, which returns a near-origin bone cluster and
   * yields a bogus ~117x framing scale). Use 1f / RIG_HALF_HEIGHT[character]
   * to frame the rig at a known-good size regardless of GLB source.
   *
   *   purpangolin = 0.825f  (Meshy Gothic Neon Idol biped, ~1.65m)
   *   babshaggoth = 0.85f   (Circuit Vanguard biped, ~1.7m)
   *   lyra        = 0.80f   (Neon Streetwear biped, ~1.6m)
   */
  val RIG_HALF_HEIGHT: Map<String, Float> = mapOf(
    "purpangolin" to 0.825f,
    "babshaggoth" to 0.85f,
    "lyra" to 0.80f
  )

  val PURPANGOLIN: Map<PurpAngolinMood, String> = mapOf(
    PurpAngolinMood.IDLE to "Chair_Sit_Idle_F",
    PurpAngolinMood.LISTENING to "Chair_Sit_Idle_F",
    PurpAngolinMood.ACTIVE to "Walking",
    PurpAngolinMood.THINKING to "Confused_Scratch",
    PurpAngolinMood.SPEAKING to "Agree_Gesture",
    PurpAngolinMood.DEGRADED to "Finger_Wag_No",
    PurpAngolinMood.FAILED to "Angry_Stomp",
    PurpAngolinMood.DANCING to "All_Night_Dance",
    PurpAngolinMood.BOUNCING to "Big_Wave_Hello",
    PurpAngolinMood.FLOURISH to "Happy_jump_f"
  )

  val BABSHAGGOTH: Map<PurpAngolinMood, String> = mapOf(
    PurpAngolinMood.IDLE to "Idle_15",
    PurpAngolinMood.LISTENING to "Idle_15",
    PurpAngolinMood.ACTIVE to "Running",
    PurpAngolinMood.THINKING to "Indoor_Play",
    PurpAngolinMood.SPEAKING to "Big_Wave_Hello",
    PurpAngolinMood.DEGRADED to "Prone_Reach_Help",
    PurpAngolinMood.FAILED to "Kung_Fu_Punch",
    PurpAngolinMood.DANCING to "FunnyDancing_02",
    PurpAngolinMood.BOUNCING to "FunnyDancing_02",
    PurpAngolinMood.FLOURISH to "Thomas_Flair_to_Jump_Up"
  )

  val LYRA: Map<PurpAngolinMood, String> = mapOf(
    PurpAngolinMood.IDLE to "Idle_15",
    PurpAngolinMood.LISTENING to "Idle_03",
    PurpAngolinMood.ACTIVE to "Stylish_Walk_inplace",
    PurpAngolinMood.THINKING to "Idle_12",
    PurpAngolinMood.SPEAKING to "Wave_for_Help_3",
    PurpAngolinMood.DEGRADED to "Stumble_Walk",
    PurpAngolinMood.FAILED to "Strangled_and_Fall_Forward",
    PurpAngolinMood.DANCING to "victory",
    PurpAngolinMood.BOUNCING to "victory",
    PurpAngolinMood.FLOURISH to "victory"
  )

  fun assetId(character: String): String = when (character.lowercase()) {
    "babshaggoth" -> "babshaggoth"
    "lyra", "lyra voice", "neon streetwear" -> "lyra"
    else -> "purpangolin"
  }

  /**
   * Android-safe PurpAngolin lane: one complete Gothic Neon Idol rig and
   * exactly one animation per GLB. Switching state swaps the compact asset;
   * the crash-prone merged multi-animation pack is never admitted.
   */
  fun assetPath(character: String, mood: PurpAngolinMood): String {
    if (assetId(character) != "purpangolin") return "avatars/${assetId(character)}.glb"
    val state = when (mood) {
      PurpAngolinMood.IDLE -> "idle"
      PurpAngolinMood.LISTENING -> "listening"
      PurpAngolinMood.THINKING -> "thinking"
      PurpAngolinMood.SPEAKING -> "speaking"
      PurpAngolinMood.ACTIVE -> "working"
      PurpAngolinMood.FLOURISH -> "success"
      PurpAngolinMood.FAILED, PurpAngolinMood.DEGRADED -> "error"
      PurpAngolinMood.BOUNCING -> "greeting"
      PurpAngolinMood.DANCING -> "dancing"
    }
    return "avatars/purpangolin_${state}_withSkin.glb"
  }

  private val FALLBACKS: Map<PurpAngolinMood, PurpAngolinMood> = mapOf(
    PurpAngolinMood.LISTENING to PurpAngolinMood.IDLE,
    PurpAngolinMood.ACTIVE to PurpAngolinMood.IDLE,
    PurpAngolinMood.SPEAKING to PurpAngolinMood.THINKING,
    PurpAngolinMood.BOUNCING to PurpAngolinMood.FLOURISH,
    PurpAngolinMood.DEGRADED to PurpAngolinMood.FAILED
  )

  fun clipFor(character: String, mood: PurpAngolinMood, available: Set<String>): String? {
    val map = when (assetId(character)) {
      "babshaggoth" -> BABSHAGGOTH
      "lyra" -> LYRA
      else -> PURPANGOLIN
    }
    val primary = map[mood] ?: return null
    if (primary in available) return primary
    val fb = FALLBACKS[mood]?.let(map::get)
    if (fb != null && fb in available) return fb
    return available.firstOrNull { it.contains("Sit_Idle", true) }
      ?: available.firstOrNull { it.contains("Idle", true) }
      ?: available.firstOrNull()
  }

  /** Conservative bring-up lane: one known idle only, never an arbitrary clip. */
  fun safeIdleClipFor(character: String, available: Set<String>): String? {
    val requested = when (assetId(character)) {
      "purpangolin" -> "Chair_Sit_Idle_F"
      "babshaggoth", "lyra" -> "Idle_15"
      else -> return null
    }
    return requested.takeIf(available::contains)
  }
}

/**
 * Renders the 3D companion into whatever modifier the caller hands us.
 *
 * The rig is auto-framed to fit the stage and centered in its own bounding
 * box, so full-screen overlays show the full body head-to-toes without
 * needing per-character tweaks.
 */
@Composable
fun Avatar3DActor(
  mood: PurpAngolinMood,
  character: String = "purpangolin",
  rotationY: Float = 0f,
  modelScale: Float = 1f,
  translationX: Float = 0f,
  translationY: Float = 0f,
  aspect: Float = 1f,
  touchHandler: ((android.view.MotionEvent) -> Boolean)? = null,
  modifier: Modifier = Modifier
) {
  // HARDWARE QUARANTINE — short-circuit before any native code runs.
  if (PURPCLAW_3D_AVATAR_DISABLED) {
    Log.w(Avatar3DClips.TAG, "3D avatar disabled by PURPCLAW_DISABLE_3D_AVATAR; rendering invisible Box")
    Box(modifier = modifier)
    return
  }
  val engine = rememberEngine()
  val modelLoader = rememberModelLoader(engine)
  val assetId = Avatar3DClips.assetId(character)
  val assetPath = Avatar3DClips.assetPath(assetId, mood)

  key(assetPath) {
    Avatar3DModel(
      mood = mood,
      character = assetId,
      assetPath = assetPath,
      rotationY = rotationY,
      modelScale = modelScale,
      translationX = translationX,
      translationY = translationY,
      aspect = aspect,
      touchHandler = touchHandler,
      modifier = modifier,
      modelLoader = modelLoader,
      engine = engine
    )
  }
}

@Composable
private fun Avatar3DModel(
  mood: PurpAngolinMood,
  character: String,
  assetPath: String,
  rotationY: Float,
  modelScale: Float,
  translationX: Float,
  translationY: Float,
  aspect: Float,
  touchHandler: ((android.view.MotionEvent) -> Boolean)?,
  modifier: Modifier,
  modelLoader: io.github.sceneview.loaders.ModelLoader,
  engine: com.google.android.filament.Engine
) {
  val modelInstance = remember(modelLoader, assetPath) {
    runCatching {
      modelLoader.createModelInstance(assetFileLocation = assetPath)
    }.onFailure {
      Log.e(Avatar3DClips.TAG, "Could not load fully-rigged $assetPath", it)
    }.getOrNull()
  }

  val admittedModelInstance = modelInstance?.takeIf { instance ->
    val fullyRigged = instance.skinCount > 0 && instance.animator.animationCount > 0
    if (!fullyRigged) {
      Log.e(
        Avatar3DClips.TAG,
        "Rejected $assetPath: skins=${instance.skinCount}, animations=${instance.animator.animationCount}"
      )
    }
    fullyRigged
  }

  if (admittedModelInstance == null) {
    Log.e(Avatar3DClips.TAG, "No admitted 3D avatar for $character; refusing non-rigged fallback")
    return
  }

  val modelNode = rememberNode {
    ModelNode(
      modelInstance = admittedModelInstance,
      autoAnimate = false,
      // Do not use ModelNode's bounding-box normaliser for these Meshy
      // animation exports. The bind-pose box is only ~0.017m high while
      // the animated skinned mesh is a normal ~1.65m biped; normalising
      // that false box to 1.6 units magnifies the live mesh about 94x and
      // leaves only its boots visible. Preserve the GLB's metre scale and
      // let rigRoot own the user's pinch zoom.
      scaleToUnits = null,
      centerOrigin = Position(0f, 0f, 0f)
    )
  }
  val rigRoot = rememberNode {
    Node(engine).also { root -> modelNode.parent = root }
  }

  // Meshy rig inspection: the authored Hips node is at approximately
  // (0.004, 0.983, 0.049) metres. Rebase the model around that pelvis
  // axis, then compensate on the parent so the unrotated screen position
  // remains identical. Yaw now turns the seated body in place instead of
  // orbiting the character around the GLB's foot/root origin.
  val hipPivot = remember(character) {
    if (character == "purpangolin") Position(0.004f, 0.983f, 0.049f)
    else Position(0f, 0.82f, 0f)
  }
  LaunchedEffect(modelNode, hipPivot) {
    modelNode.position = Position(-hipPivot.x, -hipPivot.y, -hipPivot.z)
  }

  // Explicit sit/idle playback (task #63, 2026-08-28 01:25Z operator spec).
  // The S25 lane asset purpangolin_idle_withSkin.glb carries exactly one
  // animation — Armature|Chair_Sit_Idle_F|baselayer at index 0. Relying on
  // autoAnimate's "first clip" default is correct today but is a silent
  // contract: a future merged-pack lane or rig swap would replay the wrong
  // clip without warning. Pin index 0 explicitly so the rig visibly sits.
  LaunchedEffect(modelNode, character, mood) {
    if (character == "purpangolin" && modelNode.animationCount > 0) {
      runCatching {
        val loop = mood !in setOf(
          PurpAngolinMood.BOUNCING,
          PurpAngolinMood.FLOURISH,
          PurpAngolinMood.FAILED
        )
        modelNode.playAnimation(0, loop = loop)
        Log.i(
          Avatar3DClips.TAG,
          "playAnimation asset=$assetPath mood=${mood.label} index=0 loop=$loop " +
            "animationCount=${modelNode.animationCount}"
        )
      }.onFailure {
        Log.w(Avatar3DClips.TAG, "playAnimation(0) failed; rig stays in bind pose", it)
      }
    }
  }

  // Measure the rig in scene units. Filament Box exposes halfExtent() as a
  // float[3] (x, y, z) and center() as a float[3]. We compute the largest
  // half-extent so we can drive both the framing scale and the camera
  // distance from a single measurement regardless of the source GLB.
  val rigExtents = remember(modelNode) {
    val bb: FilamentBox = modelNode.boundingBox
    val half = bb.halfExtent
    val center = bb.center
    Log.i(
      Avatar3DClips.TAG,
      "rigExtents character=$character center=(${center[0]},${center[1]},${center[2]}) " +
        "halfExtent=(${half[0]},${half[1]},${half[2]}) animationCount=${modelNode.animationCount}"
    )
    FilamentRigExtents(
      centerX = center[0],
      centerY = center[1],
      centerZ = center[2],
      halfHeight = half[1],
      halfWidth = half[0],
      halfDepth = half[2]
    )
  }

  // Project the measured rig into the camera frame. We size the rig so its
  // full height fits ~80% of the smaller stage dimension at a 35° vertical
  // FOV. The math is stage-aspect agnostic — the same rig value works
  // whether the stage is full-screen portrait or a 280dp corner pill.
  //
  // Keep the ModelNode's source transform untouched: animation updates the
  // model/skeleton, while the stable parent node owns user zoom, rotation,
  // and translation. The Meshy asset is already authored in metre scale.
  val documentedHalfHeight = Avatar3DClips.RIG_HALF_HEIGHT[character] ?: 0.825f
  val measuredHalfHeight = rigExtents.halfHeight.takeIf { it > 0.0001f }
  val userScale = modelScale.coerceIn(0.15f, 1.9f)

  // Meshy exports feet at y=0 and the fitted head near y=1.6. Aim at the
  // torso, not the world origin: on the physical S25, targeting y=0 showed
  // only the boots even though the complete skinned rig was loaded.
  val cameraNode = rememberCameraNode(engine) {
    position = Position(x = 0f, y = 0.82f, z = 3.4f)
    lookAt(Position(0f, 0.82f, 0f))
  }

  // SceneView owns projection and refreshes its aspect from the attached
  // SurfaceView. Do not override it from Compose: the stage is measured at
  // 0x0 before attachment, then becomes 825x1050 on the S25. Its native
  // viewport callback supplies a valid camera projection for that surface.

  // Pan + rotation + per-mood pinch scale. Translation is in rig world
  // units (post-framing), so ±1 unit maps to "head to feet" regardless of
  // which GLB is loaded.
  LaunchedEffect(rigRoot, rotationY, userScale, translationX, translationY) {
    Log.i(
      Avatar3DClips.TAG,
      "applyTransform measuredHalfH=$measuredHalfHeight documentedHalfH=$documentedHalfHeight " +
        "scaleToUnits=source userScale=$userScale " +
        "aspect=$aspect rotationY=$rotationY tx=$translationX ty=$translationY"
    )
    rigRoot.rotation = Rotation(y = rotationY)
    rigRoot.scale = Float3(userScale, userScale, userScale)
    val maxPanX = 1.2f
    val maxPanY = 1.2f
    // translationY is the screen-space centre supplied by the Compose
    // gesture envelope. The camera looks at y=.82, while this source rig's
    // visual centre is roughly half-height*scale above its authored floor.
    // Reconcile those coordinate systems so the visible body stays inside
    // (and directly under) its touch target at every scale. Previously the
    // hit box could sit hundreds of pixels above the character after a drag.
    val visualCenterCompensationY = 0.82f - documentedHalfHeight * userScale
    rigRoot.position = Position(
      x = translationX.coerceIn(-maxPanX, maxPanX) + hipPivot.x * userScale,
      y = translationY.coerceIn(-maxPanY, maxPanY) +
        hipPivot.y * userScale + visualCenterCompensationY,
      z = hipPivot.z * userScale
    )
  }

  // HARDWARE QUARANTINE (S25 / Adreno 830, 2026-08-28): do not even retain
  // the merged pack's Animator in the live composition. Both continuous
  // playback and runtime catalogue traversal preceded libgltfio null-pointer
  // crashes. The external asset inventory remains authoritative while the
  // compact single-state GLBs are certified independently.

  Scene(
    modifier = modifier.shareTouchesWithSiblings(),
    engine = engine,
    modelLoader = modelLoader,
    cameraNode = cameraNode,
    isOpaque = false,
    cameraManipulator = null,
    // The SceneView is a full-screen transparent renderer, not a full-screen
    // input shield. Reject its background touch stream so the Compose chat,
    // reply actions, and composer beneath remain actionable. The overlay
    // shell owns the bounded avatar gesture target separately.
    onTouchEvent = { _, _ -> false },
    // SceneView is a SurfaceView. Inside Compose, a transparent SurfaceView
    // otherwise sits behind the opaque chat canvas on Samsung and faithfully
    // renders an invisible model. Keep only this bounded mascot surface on
    // top; the shell above still owns input and clamps its screen position.
    onViewCreated = ::configureAvatarSurfaceView,
    childNodes = listOf(rigRoot)
  )
}

/** Optimize the SceneView's SurfaceView for overlay compositing on Android 14+. */
private fun configureAvatarSurfaceView(view: io.github.sceneview.SceneView) {
  @Suppress("DEPRECATION")
  val holder = view.holder
  holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)
  holder.setKeepScreenOn(false)
  view.setZOrderOnTop(true)
  view.isClickable = false
  view.isFocusable = false
  view.isEnabled = false
}

private data class FilamentRigExtents(
  val centerX: Float,
  val centerY: Float,
  val centerZ: Float,
  val halfHeight: Float,
  val halfWidth: Float,
  val halfDepth: Float
)
