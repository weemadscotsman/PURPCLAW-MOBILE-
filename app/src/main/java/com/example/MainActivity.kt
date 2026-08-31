package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.widget.VideoView
import com.example.ui.MainViewModel
import com.example.ui.PurpClawApp
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()
  private var pendingChatPhoto: File? = null

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
    // Voice/camera become usable the moment grants land; the engines read state live.
    viewModel.onPermissionsChanged(grants)
    if (grants[Manifest.permission.CAMERA] == true) bindCameraRuntime()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
    )
    window.isNavigationBarContrastEnforced = false
    requestEssentialPermissions()
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      bindCameraRuntime()
    }
    setContent {
      MyApplicationTheme {
        // Boot animation state: remembers across recompositions.
        // Starts true; set false when the intro video finishes.
        var showBootAnimation by remember { mutableStateOf(true) }
        if (showBootAnimation) {
          BootAnimationOverlay(
            rawResId = R.raw.animate_my_agent_stack_icon_fo,
            onAnimationComplete = { showBootAnimation = false }
          )
        } else {
          PurpClawApp(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
          )
        }
      }
    }
    intent?.data?.let(viewModel::handleProviderOAuthCallback)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.data?.let(viewModel::handleProviderOAuthCallback)
  }

  /** CameraX must be lifecycle-bound before the native capture tool can run. */
  private fun bindCameraRuntime() {
    viewModel.cameraEngine.bindCamera(this) { bound ->
      android.util.Log.i("MainActivity", "CameraX runtime bound=$bound")
    }
  }

  /** Human camera attachment. This intentionally does not use autonomous CameraX. */
  fun launchUserCamera() {
    val dir = File(filesDir, "chat-images").apply { mkdirs() }
    val file = File(dir, "chat_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")
    pendingChatPhoto = file
    val output: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    userCameraLauncher.launch(output)
  }

  /** Fullscreen boot animation. Plays the MP4 once, then dismisses and reveals the
   *  main app. The system splash (from the Android theme) shows through during
   *  the window launch transition, giving a purple first-frame before
   *  setContent drives the real boot video. */
  @Composable
  private fun BootAnimationOverlay(
    rawResId: Int,
    onAnimationComplete: () -> Unit
  ) {
    AndroidView(
      factory = { ctx ->
        VideoView(ctx).apply {
          val afd = ctx.resources.openRawResourceFd(rawResId)
          setVideoDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
          afd.close()
          setOnCompletionListener {
            android.util.Log.i("MainActivity", "Boot animation complete")
            onAnimationComplete()
          }
          setOnErrorListener { _, what, extra ->
            android.util.Log.e("MainActivity", "Boot animation error: what=$what extra=$extra")
            // Dismiss on error so a broken video can't brick the app.
            onAnimationComplete()
            true
          }
          start()
        }
      },
      modifier = Modifier.fillMaxSize()
    )
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
