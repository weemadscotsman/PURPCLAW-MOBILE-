package com.example.ui.components

import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import android.webkit.JavascriptInterface

/**
 * DualViewBrowser — in-app embedded browser surface for web tasks.
 *
 * DUAL VIEW LAW: the browser lives INSIDE PurpClaw as a controllable upper
 * pane. PurpClaw owns browser state (URL, navigation, title) so actions can be
 * verified against real WebView state instead of fired-and-forgotten Intents.
 * Android system split-screen is NOT required and never assumed.
 *
 * Layout contract: target pane on top (~45-60%), draggable divider, collapsible.
 * The chat surface below is never remounted by browser state changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualViewBrowser(
  initialUrl: String,
  collapsed: Boolean,
  onToggleCollapse: () -> Unit,
  onClose: () -> Unit,
  onStateChange: (DualViewState) -> Unit,
  modifier: Modifier = Modifier
) {
  // Compose-owned title state — recomposes the collapsed pill; no cross-instance leak.
  val lastTitle = remember { mutableStateOf("") }

  if (collapsed) {
    // Compact pill — one tap restores the pane
    Surface(
      modifier = modifier
        .fillMaxWidth()
        .clickable { onToggleCollapse() },
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = MaterialTheme.shapes.small
    ) {
      Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Language, contentDescription = "Browser", Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          lastTitle.value.ifBlank { "Browser" },
          fontSize = 11.sp,
          maxLines = 1,
          modifier = Modifier.weight(1f)
        )
        Icon(Icons.Filled.ExpandLess, contentDescription = "Restore browser", Modifier.size(17.dp))
      }
    }
    return
  }

  var urlBar by remember { mutableStateOf(initialUrl) }
  var canGoBack by remember { mutableStateOf(false) }
  var canGoForward by remember { mutableStateOf(false) }
  val webViewRef = remember { mutableStateOf<WebView?>(null) }

  Surface(modifier = modifier.fillMaxWidth()) {
    Column {
      // URL + navigation bar
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        IconButton(onClick = { webViewRef.value?.goBack() }, enabled = canGoBack) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", Modifier.size(18.dp))
        }
        IconButton(onClick = { webViewRef.value?.goForward() }, enabled = canGoForward) {
          Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward", Modifier.size(18.dp))
        }
        OutlinedTextField(
          value = urlBar,
          onValueChange = { urlBar = it },
          singleLine = true,
          textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
          modifier = Modifier.weight(1f).heightIn(min = 40.dp),
          trailingIcon = {
            IconButton(onClick = onClose) {
              Icon(Icons.Default.Close, contentDescription = "Close browser", Modifier.size(16.dp))
            }
          }
        )
        TextButton(onClick = {
          val wv = webViewRef.value ?: return@TextButton
          wv.loadUrl(normalizeUrl(urlBar))
        }) { Text("GO", fontSize = 11.sp) }
        IconButton(onClick = onToggleCollapse) {
          Icon(Icons.Filled.ExpandMore, contentDescription = "Collapse browser", Modifier.size(18.dp))
        }
      }

      // Embedded WebView — PurpClaw owns navigation state
      AndroidView(
        factory = { ctx ->
          WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webViewClient = object : WebViewClient() {
              override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let {
                  urlBar = it
                  onStateChange(DualViewState(url = it, title = view?.title ?: "", loading = true))
                }
              }
              override fun onPageFinished(view: WebView?, u: String?) {
                canGoBack = view?.canGoBack() ?: false
                canGoForward = view?.canGoForward() ?: false
                lastTitle.value = view?.title ?: ""
                onStateChange(
                  DualViewState(
                    url = u ?: "",
                    title = view?.title ?: "",
                    loading = false
                  )
                )
              }
            }
            loadUrl(normalizeUrl(initialUrl))
            webViewRef.value = this
          }
        },
        update = { /* state flows via client callbacks; no remount */ },
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp)
      )
    }
  }
}

/** Browser title state — observable so the collapsed pill recomposes. */
private val lastTitle = androidx.compose.runtime.mutableStateOf("")

data class DualViewState(
  val url: String,
  val title: String,
  val loading: Boolean
)

internal fun normalizeUrl(raw: String): String =
  // java.io.File.toURI() is permitted to serialize an absolute Android path
  // as file:/data/... (one slash after the scheme). Treat the file scheme as
  // already absolute; prefixing it with https:// turns a verified local
  // artifact into the bogus host `file` and WebView reports NAME_NOT_RESOLVED.
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("file:/")) raw else "https://$raw"
