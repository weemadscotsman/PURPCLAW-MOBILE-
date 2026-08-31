package com.example.ui.navigation

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import com.example.R
import com.example.ui.NavigationSurface
import com.example.ui.theme.PurpBorder
import com.example.ui.theme.PurpDeep
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpSurfaceCard
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

// Destination = surface + operator asset + label. Emoji era is over.
private data class Quad(val surface: NavigationSurface, val icon: Int, val label: String)

/**
 * LiquidNavigation — ONE component, two presentation states.
 *
 * Persistently MOUNTED, not persistently visible. Visibility is presentation
 * only: the whole assembly translates below the safe-area boundary via
 * transform-only motion (no relayout, no remount of any screen).
 *
 * Active indicator is derived from REAL navigation state (activeSurface),
 * never a manual index. It travels between seats with a ~300ms spring;
 * the active glyph rises into it.
 */
@Composable
fun LiquidBottomNav(
  activeSurface: NavigationSurface,
  visibility: BottomNavVisibility,
  imeVisible: Boolean,
  menuOpen: Boolean,
  onSelect: (NavigationSurface) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  // Respect system animator scale — 0 means reduced motion: snap, don't spring.
  val animatorScale = remember {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
  }
  val reducedMotion = animatorScale == 0f

  // Operator-supplied holographic icon pack (assets/holographic-icon-pack).
  // Emoji glyphs are dead — every destination ships its real asset.
  val destinations = remember {
    listOf(
      Quad(NavigationSurface.COMMAND, R.drawable.ic_nav_chat, "Chat"),
      Quad(NavigationSurface.MISSIONS, R.drawable.ic_nav_missions, "Missions"),
      Quad(NavigationSurface.CANVAS, R.drawable.ic_nav_canvas, "Canvas"),
      Quad(NavigationSurface.ORGANISATION, R.drawable.ic_nav_agents, "Agents"),
      Quad(NavigationSurface.STUDIO, R.drawable.ic_nav_studio, "Studio"),
      Quad(NavigationSurface.MEMORY, R.drawable.ic_nav_memory, "Memory"),
      Quad(NavigationSurface.TOOLS_MESH, R.drawable.ic_nav_tools, "Tools"),
      Quad(NavigationSurface.AI_MODELS, R.drawable.ic_nav_models, "Models"),
      Quad(NavigationSurface.VAULT, R.drawable.ic_nav_vault, "Vault")
    )
  }

  val shouldHide = visibility.isHidden(imeVisible, menuOpen)

  // Transform-only hide/show of the WHOLE assembly (bar + label), 180–280ms.
  val hideFraction = remember { Animatable(if (shouldHide) 1f else 0f) }
  LaunchedEffect(shouldHide, reducedMotion) {
    val target = if (shouldHide) 1f else 0f
    if (reducedMotion || hideFraction.targetValue == target) {
      hideFraction.snapTo(target)
    } else {
      hideFraction.animateTo(target, tween(durationMillis = 220))
    }
  }

  // Idle sweep — one tick per second decides the AUTO-mode timeout.
  LaunchedEffect(Unit) {
    while (true) {
      kotlinx.coroutines.delay(1000)
      visibility.tickIdle(menuOpen = menuOpen, imeVisible = imeVisible)
    }
  }

  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    AnimatedContent(
      targetState = activeSurface,
      transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
      label = "liquidNavLabel"
    ) { surface ->
      val label = destinations.firstOrNull { it.surface == surface }?.label ?: surface.label
      Text(
        label.uppercase(),
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = TextMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .graphicsLayer { alpha = (1f - hideFraction.value).coerceIn(0f, 1f) }
          .padding(bottom = 2.dp)
      )
    }

    Box(
      modifier = Modifier
        .navigationBarsPadding()
        .graphicsLayer {
          // CLOSED LAW: fully out of view — bar height + nav inset + breathing
          // room, and alpha reaches exactly 0. No ghost hovering over the
          // composer. Pure transform; composable stays mounted.
          translationY = hideFraction.value * (58.dp.toPx() + 80.dp.toPx())
          alpha = 1f - hideFraction.value
        }
        .padding(horizontal = 14.dp)
    ) {
      BoxWithConstraints(
        modifier = Modifier
          .fillMaxWidth()
          .height(58.dp)
          .clip(RoundedCornerShape(20.dp))
          .background(PurpSurfaceCard)
          .border(1.dp, PurpBorder, RoundedCornerShape(20.dp))
      ) {
        val seatWidth = maxWidth / destinations.size
        val activeIndex = destinations.indexOfFirst { it.surface == activeSurface }
          .coerceAtLeast(0)

        // The travelling liquid indicator — spring ~300ms, real-state-derived.
        val indicatorX by animateDpAsState(
          targetValue = seatWidth * activeIndex + (seatWidth - 40.dp) / 2,
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
          ),
          label = "liquidIndicatorX"
        )
        Box(
          modifier = Modifier
            .offset(x = indicatorX)
            .align(Alignment.CenterStart)
            .size(width = 40.dp, height = 40.dp)
            .background(PurpDeep, CircleShape)
            .border(1.dp, PurpNeon, CircleShape)
        )

        Row(modifier = Modifier.fillMaxSize()) {
          destinations.forEach { dest ->
            val isActive = dest.surface == activeSurface
            val interaction = remember { MutableInteractionSource() }
            Box(
              modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .clickable(
                  interactionSource = interaction,
                  indication = null
                ) {
                  visibility.notifyInteraction()
                  if (!isActive) onSelect(dest.surface)
                },
              contentAlignment = Alignment.Center
            ) {
              Image(
                painter = painterResource(dest.icon),
                contentDescription = dest.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                  .size(if (isActive) 24.dp else 21.dp)
                  .graphicsLayer {
                    // Glyph rises into the indicator when seated.
                    translationY = if (isActive) -2.dp.toPx() else 0f
                    alpha = if (isActive) 1f else 0.55f
                  }
              )
            }
          }
        }
      }
    }

    // Bottom-edge reveal strip: survives while the bar is hidden so a short
    // upward swipe from the screen edge restores it. Never fights the system
    // gesture zone — it sits INSIDE our safe-area padding, above nav gutter.
    if (hideFraction.value > 0.5f) {
      Box(
        modifier = Modifier
          .navigationBarsPadding()
          .fillMaxWidth()
          .height(20.dp)
          .pointerInput(Unit) {
            detectVerticalDragGestures(
              onVerticalDrag = { _, dragAmount ->
                if (dragAmount < -12f) visibility.onRevealGesture()
              }
            )
          }
      )
    }
  }
}
