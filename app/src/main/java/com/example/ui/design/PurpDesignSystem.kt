package com.example.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberHybrid
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.PurpBorder
import com.example.ui.theme.PurpNeon
import com.example.ui.theme.PurpSurfaceCard
import com.example.ui.theme.PurpVoid
import com.example.ui.theme.RoseOffline
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/** Chat-derived semantic values. New screens use names, not private magic numbers. */
object PurpSpacing {
  val xs = 4.dp
  val sm = 8.dp
  val control = 10.dp
  val md = 14.dp
  val lg = 16.dp
  val xl = 20.dp
  val bottomClearance = 72.dp
}

object PurpShapes {
  val small = RoundedCornerShape(8.dp)
  val medium = RoundedCornerShape(10.dp)
  val large = RoundedCornerShape(14.dp)
  val dialog = RoundedCornerShape(16.dp)
  val pill = RoundedCornerShape(999.dp)
}

object PurpType {
  val pageTitle = TextStyle(fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black)
  val sectionTitle = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
  val cardTitle = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
  val body = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp)
  val bodyMuted = body.copy(color = TextSecondary)
  val label = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
  val caption = TextStyle(fontSize = 9.sp, lineHeight = 12.sp)
  val code = caption.copy(fontFamily = FontFamily.Monospace)
  val button = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
}

enum class PurpUiStatus { READY, BUSY, DEGRADED, OFFLINE, BLOCKED, UNAVAILABLE, ERROR, RECOVERING, COMPLETED }

private fun PurpUiStatus.color(): Color = when (this) {
  PurpUiStatus.READY, PurpUiStatus.COMPLETED -> EmeraldOnline
  PurpUiStatus.BUSY, PurpUiStatus.RECOVERING -> PurpNeon
  PurpUiStatus.DEGRADED -> AmberHybrid
  PurpUiStatus.OFFLINE, PurpUiStatus.UNAVAILABLE -> TextMuted
  PurpUiStatus.BLOCKED, PurpUiStatus.ERROR -> RoseOffline
}

@Composable
fun PurpPageScaffold(
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(horizontal = PurpSpacing.md, vertical = PurpSpacing.control),
  content: @Composable ColumnScope.() -> Unit
) {
  Column(
    modifier = modifier.fillMaxSize().background(PurpVoid).padding(contentPadding),
    verticalArrangement = Arrangement.spacedBy(PurpSpacing.control),
    content = content
  )
}

@Composable
fun PurpHeader(
  title: String,
  subtitle: String? = null,
  modifier: Modifier = Modifier,
  subtitleColor: Color = TextSecondary,
  status: PurpUiStatus? = null,
  actions: @Composable RowScope.() -> Unit = {}
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = PurpType.pageTitle, color = TextPrimary)
      if (!subtitle.isNullOrBlank()) Text(subtitle, style = PurpType.bodyMuted, color = subtitleColor)
    }
    if (status != null) PurpStatusChip(status)
    actions()
  }
}

@Composable
fun PurpCard(
  modifier: Modifier = Modifier,
  borderColor: Color = PurpBorder.copy(alpha = 0.7f),
  containerColor: Color = PurpSurfaceCard,
  contentPadding: Dp = PurpSpacing.md,
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = PurpShapes.large,
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    onClick = onClick ?: {},
    enabled = onClick != null
  ) {
    Column(Modifier.padding(contentPadding), content = content)
  }
}

@Composable
fun PurpSectionHeader(text: String, color: Color = PurpNeon, modifier: Modifier = Modifier) {
  Text(text, style = PurpType.sectionTitle, color = color, modifier = modifier.padding(top = PurpSpacing.xs))
}

@Composable
fun PurpStatusChip(status: PurpUiStatus, label: String = status.name, modifier: Modifier = Modifier) {
  val color = status.color()
  Surface(
    modifier = modifier,
    shape = PurpShapes.pill,
    color = color.copy(alpha = 0.14f),
    border = BorderStroke(1.dp, color.copy(alpha = 0.65f))
  ) {
    Text(label, style = PurpType.code, color = color, modifier = Modifier.padding(horizontal = PurpSpacing.sm, vertical = PurpSpacing.xs))
  }
}

@Composable
fun PurpEmptyState(
  title: String,
  message: String,
  icon: ImageVector,
  modifier: Modifier = Modifier
) {
  PurpCard(modifier = modifier) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(PurpSpacing.sm)
    ) {
      Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = PurpNeon, modifier = Modifier.size(22.dp))
      }
      Text(title, style = PurpType.cardTitle, color = TextPrimary)
      Text(message, style = PurpType.bodyMuted, color = TextSecondary)
    }
  }
}
