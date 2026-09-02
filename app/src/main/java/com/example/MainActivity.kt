package com.example

import android.Manifest
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.ui.MainViewModel
import com.example.ui.PurpClawApp
import com.example.ui.mini.MiniSurface
import com.example.ui.theme.MyApplicationTheme
import com.example.core.runtime.WorkSessionForegroundService
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
  // ONE BRAIN LAW: process-singleton runtime — recreation must never spawn a
  // second MainViewModel or cancel in-flight turns (see MainViewModel.get).
  private val viewModel: MainViewModel by lazy { MainViewModel.get(application) }
  private var pendingChatPhoto: File? = null
  private var isInPipMode = false

  private val overlayMicReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == WorkSessionForegroundService.ACTION_OVERLAY_MIC) {
        viewModel.toggleVoiceMode()
      }
    }
  }

  private val userCameraLauncher = registerForActivityResult(
    ActivityResultContracts.TakePicture()
  ) { captured ->
    val file = pendingChatPhoto
    pendingChatPhoto = null
    if (captured && file != null) {
      viewModel.attachUserCameraPhoto(file)
    } else {
      file?.delete()
    }
  }

  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { grants ->
    viewModel.onPermissionsChanged(grants)
    if (grants[Manifest.permission.CAMERA] == true) prepareCameraRuntime()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Initialize the routing telemetry singleton FIRST so every router has
    // it from the very first routing decision onward. Operators can grep
    // logcat for tag "RouteTel" to see every router decision live.
    com.example.core.runtime.RoutingTelemetry.of(this)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
    )
    window.isNavigationBarContrastEnforced = false
    requestEssentialPermissions()
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      prepareCameraRuntime()
    }

    // Lifecycle observer: detect when the user leaves (home press, recents, etc.)
    // This fires BEFORE onUserLeaveHint so the ViewModel stays in sync.
    val lifecycleObserver = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> {
          // System is about to background us — check if we should enter PiP.
          maybeEnterPip()
        }
        Lifecycle.Event.ON_RESUME -> {
          // Returning from background — leave PiP if we were in it.
          if (isInPipMode) {
            // PiP is handled by onPictureInPictureModeChanged
          }
        }
        else -> {}
      }
    }
    lifecycle.addObserver(lifecycleObserver)

    setContent {
      MyApplicationTheme {
        val isInPip by viewModel.isInPip.collectAsState()
        val isPipPaused by viewModel.isPipPaused.collectAsState()
        val companionState by viewModel.companionState.collectAsState()
        val isGenerating by viewModel.isGenerating.collectAsState()
        val isPipEnabled by viewModel.isPipEnabled.collectAsState()
        val selectedCompanion by viewModel.selectedCompanion.collectAsState()

        var showBootAnimation by remember { mutableStateOf(true) }

        if (showBootAnimation) {
          BootAnimationOverlay(
            rawResId = R.raw.animate_my_agent_stack_icon_fo,
            onAnimationComplete = { showBootAnimation = false }
          )
        } else if (isInPip) {
          // PiP surface — full-screen mini view, system controls its window.
          MiniSurface(
            companionState = companionState,
            isGenerating = isGenerating,
            taskTitle = viewModel.pipTaskTitle,
            replySnippet = viewModel.pipReplySnippet,
            selectedCompanion = selectedCompanion,
            isPaused = isPipPaused,
            onExpand = { exitPipMode() },
            onPause = { viewModel.onPauseFromPip() },
            onStop = { viewModel.onStopFromPip() },
            modifier = Modifier.fillMaxSize()
          )
        } else {
          PurpClawApp(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
          )
        }
      }
    }

    ContextCompat.registerReceiver(
      this,
      overlayMicReceiver,
      IntentFilter(WorkSessionForegroundService.ACTION_OVERLAY_MIC),
      ContextCompat.RECEIVER_NOT_EXPORTED
    )
    handleIncomingIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingIntent(intent)
  }

  override fun onStart() {
    super.onStart()
    WorkSessionForegroundService.setAppVisible(this, true)
  }

  override fun onStop() {
    WorkSessionForegroundService.setAppVisible(this, false)
    super.onStop()
  }

  override fun onDestroy() {
    runCatching { unregisterReceiver(overlayMicReceiver) }
    super.onDestroy()
  }

  // ══════════════════════════════════════════════════════════════
  // PiP / Mochi overlay wiring
  // ══════════════════════════════════════════════════════════════

  /**
   * Fires when the user leaves the activity — home press, recents, app switcher.
   * This is the signal to enter PiP if a session is active.
   *
   * We only enter PiP when:
   * 1. A session is actually generating (not idle)
   * 2. We aren't already in PiP
   * 3. The device supports PiP
   */
  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    maybeEnterPip()
  }

  private fun maybeEnterPip() {
    if (isInPipMode) return
    // Enter PiP only when:
    // 1. isPipEnabled == true (user has not disabled PiP in Settings)
    // 2. actively generating (not idle/intro)
    val pipEnabled = viewModel.isPipEnabled.value
    val isActive = viewModel.isGenerating.value
    if (!pipEnabled || !isActive) return

    val params = PictureInPictureParams.Builder()
      .setAspectRatio(Rational(9, 10))
      .build()

    if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
      enterPictureInPictureMode(params)
      android.util.Log.i("MainActivity", "Entered PiP — session is active")
    }
  }

  /**
   * Called by the system when PiP mode changes — both on enter and on exit.
   * Also called manually when we want to exit.
   */
  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    isInPipMode = isInPictureInPictureMode
    viewModel.onPipChanged(isInPictureInPictureMode)
    android.util.Log.i("MainActivity", "PiP mode changed: $isInPictureInPictureMode")
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // System may resize the PiP window on orientation change — re-enter PiP
    // with the new aspect ratio if we were already in it.
    if (isInPipMode) {
      val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(9, 10))
        .build()
      setPictureInPictureParams(params)
    }
  }

  /** Programmatically exit PiP and return to the full app.
   *  On Android, PiP exits automatically when the user taps the expand button
   *  or swipes PiP away. The notification "OPEN" action also brings us back.
   *  No manual moveTaskToFront needed — the activity simply resumes. */
  private fun exitPipMode() {
    // The system handles PiP dismissal. Our notification action already calls
    // MainActivity which will restore the full UI via onPictureInPictureModeChanged(false).
    // Just log it for traceability.
    android.util.Log.i("MainActivity", "exitPipMode called — system handles dismissal")
  }

  private fun handleIncomingIntent(intent: Intent?) {
    intent?.data?.let(viewModel::handleProviderOAuthCallback)
    intent?.getStringExtra(WorkSessionForegroundService.EXTRA_OPEN_ARTIFACT_URL)
      ?.takeIf { it.isNotBlank() }
      ?.let(viewModel::openDualView)
  }

  /**
   * Register the lifecycle owner without opening camera hardware. CameraX is
   * bound lazily for a real android.camera.capture call and released again
   * after the frame is written. Keeping camera 0 open for the whole chat
   * session stole audio focus on some Samsung devices and made the mic look
   * dead while also wasting battery.
   */
  private fun prepareCameraRuntime() {
    viewModel.cameraEngine.prepare(this)
    android.util.Log.i("MainActivity", "CameraX runtime prepared (hardware closed until capture)")
  }

  /** Human camera attachment. This intentionally does not use autonomous CameraX. */
  fun launchUserCamera() {
    val dir = File(filesDir, "chat-images").apply { mkdirs() }
    val file = File(dir, "chat_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")
    pendingChatPhoto = file
    val output: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    userCameraLauncher.launch(output)
  }

  /**
   * Canonical PurpClaw boot splash — no video, no white rect, no letterboxing.
   *
   * The `purpclaw_logo_canonical.png` asset is displayed at ~68% of the
   * shorter screen dimension on a pure black background, centred with a
   * subtle purple core glow, then fades out to reveal the live runtime.
   * Total duration: ~1.9 s. No ExoPlayer dependency.
   *
   * Operator spec (2026-08-31): logo must own the screen, not look like it
   * wandered in looking for its seat.
   */
  @Composable
  private fun BootAnimationOverlay(
    @Suppress("UNUSED_PARAMETER") rawResId: Int,
    onAnimationComplete: () -> Unit
  ) {
    var visible by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(
      targetValue = if (visible) 1f else 0f,
      animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
      finishedListener = { if (!visible) onAnimationComplete() },
      label = "splashAlpha"
    )

    // Hold logo for 1.2s, then fade out over 350ms.
    LaunchedEffect(Unit) {
      delay(1200L)
      visible = false
    }

    BoxWithConstraints(
      Modifier
        .fillMaxSize()
        .background(Color.Black),
      contentAlignment = Alignment.Center
    ) {
      // Logo sized to 60% of the shorter screen dimension.
      val logoSize = minOf(maxWidth, maxHeight) * 0.68f

      // Subtle purple radial glow behind the logo.
      Box(
        Modifier
          .fillMaxSize()
          .background(
            Brush.radialGradient(
              colors = listOf(
                Color(0xFF9B4DFF).copy(alpha = alpha * 0.22f),
                Color.Black
              )
            )
          )
      )

      Image(
        painter = painterResource(id = R.drawable.purpclaw_logo_canonical),
        contentDescription = "PurpClaw",
        modifier = Modifier
          .size(logoSize)
          .graphicsLayer { this.alpha = alpha },
        contentScale = ContentScale.Fit
      )
    }
  }

  /** RECORD_AUDIO + CAMERA are the voice/photo hands. Ask up-front, once. */
  private fun requestEssentialPermissions() {
    val needed = listOf(
      Manifest.permission.RECORD_AUDIO,
      Manifest.permission.CAMERA,
      Manifest.permission.POST_NOTIFICATIONS
    ).filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (needed.isNotEmpty()) {
      permissionLauncher.launch(needed.toTypedArray())
    }
  }
}
