package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.core.model.MediaAttachment
import com.example.core.model.MediaKind

/**
 * Native inline media renderer for chat bubbles.
 * Borderless by law: images clip to rounded corners, video is a raw PlayerView
 * (no card, no border, controls overlay on the surface itself), files are chips.
 */
@Composable
fun MediaAttachmentsRow(attachments: List<MediaAttachment>) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    attachments.forEach { attachment ->
      when (attachment.kind) {
        MediaKind.IMAGE -> InlineImage(attachment)
        MediaKind.VIDEO -> InlineVideo(attachment)
        MediaKind.FILE -> FileChip(attachment)
      }
    }
  }
}

@Composable
private fun InlineImage(attachment: MediaAttachment) {
  AsyncImage(
    model = attachment.uri,
    contentDescription = attachment.label ?: "inline image",
    contentScale = ContentScale.Fit,
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(max = 320.dp)
      .clip(RoundedCornerShape(12.dp))
  )
}

@Composable
private fun InlineVideo(attachment: MediaAttachment) {
  val context = LocalContext.current

  val exoPlayer = remember {
    ExoPlayer.Builder(context).build().apply {
      setMediaItem(MediaItem.fromUri(attachment.uri))
      repeatMode = Player.REPEAT_MODE_OFF
      prepare()
    }
  }

  DisposableEffect(attachment.uri) {
    onDispose { exoPlayer.release() }
  }

  AndroidView(
    factory = { ctx ->
      PlayerView(ctx).apply {
        useController = true
        player = exoPlayer
      }
    },
    update = { view -> if (view.player != exoPlayer) view.player = exoPlayer },
    modifier = Modifier
      .fillMaxWidth()
      .height(220.dp)
      .clip(RoundedCornerShape(12.dp))
  )
}

@Composable
private fun FileChip(attachment: MediaAttachment) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(
      imageVector = Icons.Filled.Description,
      contentDescription = "Attached file",
      tint = Color.White,
      modifier = Modifier.padding(end = 6.dp).size(18.dp)
    )
    Column {
      Text(
        text = attachment.label ?: attachment.uri.substringAfterLast('/'),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = Color.White
      )
      if (attachment.mime != null) {
        Spacer(Modifier.height(1.dp))
        Text(
          text = attachment.mime.uppercase(),
          fontSize = 8.sp,
          fontFamily = FontFamily.Monospace,
          color = Color.White.copy(alpha = 0.6f)
        )
      }
    }
    Spacer(Modifier.width(4.dp))
  }
}
