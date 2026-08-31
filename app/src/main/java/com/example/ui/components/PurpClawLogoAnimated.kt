package com.example.ui.components

import android.media.MediaPlayer
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R

/**
 * Canonical animated PurpClaw logo — plays the ORIGINAL brand asset bundled at
 * app/src/main/res/raw/purpclaw_logo_animated.webm (source of truth: /brand/logo/manifest.json
 * in the PurpClaw repo). No redesign, no regeneration.
 *
 * Behaviour law (brand contract):
 *   APP START      -> plays once (loop=false)
 *   HEADER/COMPACT -> subtle loop (loop=true)
 *   reduced-motion -> use PurpClawLogoStatic instead (caller decides)
 */
@Composable
fun PurpClawLogoAnimated(
  modifier: Modifier = Modifier,
  loop: Boolean = false,
  autoStart: Boolean = true
) {
  val context = LocalContext.current
  val player = remember {
    try {
      MediaPlayer.create(context, R.raw.purpclaw_logo_animated)?.apply {
        isLooping = loop
        setVolume(0f, 0f)
      }
    } catch (_: Exception) { null }
  }

  DisposableEffect(Unit) {
    onDispose { player?.release() }
  }

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      TextureView(ctx).also { view ->
        player?.setSurface(android.view.Surface(view.surfaceTexture))
        if (autoStart) player?.start()
      }
    },
    update = { view ->
      if (view is TextureView && player != null && !player.isPlaying && autoStart) {
        // surface re-attached after recomposition — restart playback
        try { player.start() } catch (_: IllegalStateException) {}
      }
    }
  )
}
