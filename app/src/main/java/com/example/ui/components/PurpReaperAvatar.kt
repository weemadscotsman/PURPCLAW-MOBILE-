package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.example.core.model.CompanionPetState
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpDeep

/**
 * PurpReaper companion avatar — rendered from assets/purpreaper/.
 *
 * State → asset mapping:
 *   IDLE        → static/idle_neutral.png
 *   LISTENING   → static/audio_listening.png
 *   THINKING    → animations/thinking_spinner (.gif, loops)
 *   PLANNING    → static/thinking.png
 *   TOOL_CALL   → static/working_laptop.png
 *   RUNNING     → static/working_laptop.png
 *   FIXING      → animations/recovery_loop (.gif, loops)
 *   REPLYING    → static/coding.png
 *   SUCCESS     → static/success.png
 *   ERROR       → animations/retry_loop (.gif, loops)
 *   RETRY       → animations/retry_loop (.gif, loops)
 *
 * The GIF loop is handled by Coil's decoder; no separate animation controller needed.
 * Static states render the PNG once (no animation).
 */
@Composable
fun PurpReaperAvatar(
  companionState: CompanionPetState,
  isSelected: Boolean,
  modifier: Modifier = Modifier,
  size: Dp = 72.dp
) {
  val context = LocalContext.current

  // Build an ImageLoader that can decode GIFs from assets.
  // ImageDecoderDecoder (API 28+) is preferred; GIF decoder is the fallback.
  val gifLoader = ImageLoader.Builder(context)
    .components {
      if (android.os.Build.VERSION.SDK_INT >= 28) {
        add(ImageDecoderDecoder.Factory())
      } else {
        add(GifDecoder.Factory())
      }
    }
    .build()

  val assetPath = assetPathForState(companionState)
  val model = ImageRequest.Builder(context)
    .data("file:///android_asset/$assetPath")
    .crossfade(true)
    .build()

  Box(
    modifier = modifier
      .size(size)
      .clip(CircleShape)
      .background(PurpDeep.copy(alpha = 0.6f))
      .then(
        if (isSelected) {
          Modifier.border(2.dp, PurpNeon, CircleShape)
        } else {
          Modifier
        }
      )
      .padding(4.dp),
    contentAlignment = Alignment.Center
  ) {
    AsyncImage(
      model = model,
      imageLoader = gifLoader,
      contentDescription = "PurpReaper — ${companionState.label}",
      modifier = Modifier
        .size(size - 8.dp)
        .clip(CircleShape),
      contentScale = ContentScale.Fit
    )
  }
}

/** Map a CompanionPetState to the corresponding purpreaper asset path. */
private fun assetPathForState(state: CompanionPetState): String {
  return when (state) {
    CompanionPetState.IDLE     -> "purpreaper/static/idle_neutral.png"
    CompanionPetState.LISTENING -> "purpreaper/static/audio_listening.png"
    CompanionPetState.THINKING  -> "purpreaper/animations/thinking_spinner.gif"
    CompanionPetState.PLANNING  -> "purpreaper/static/thinking.png"
    CompanionPetState.TOOL_CALL -> "purpreaper/static/working_laptop.png"
    CompanionPetState.RUNNING   -> "purpreaper/static/working_laptop.png"
    CompanionPetState.FIXING    -> "purpreaper/animations/recovery_loop.gif"
    CompanionPetState.REPLYING  -> "purpreaper/static/coding.png"
    CompanionPetState.SUCCESS   -> "purpreaper/static/success.png"
    CompanionPetState.ERROR     -> "purpreaper/animations/retry_loop.gif"
    CompanionPetState.RETRY     -> "purpreaper/animations/retry_loop.gif"
  }
}
