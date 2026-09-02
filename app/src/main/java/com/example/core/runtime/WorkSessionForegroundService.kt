package com.example.core.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlin.math.abs

/** P0-5: Structured execution state fed to the floating overlay. */
data class ExecutionState(
  val isActive: Boolean,
  val isSuccess: Boolean?,
  val isVerified: Boolean?,
  val toolName: String?,
  val message: String
)

/**
 * Android lifecycle shell for the existing WorkSession/turn owner.
 *
 * It keeps the process foreground-capable, mirrors canonical turn status into
 * the movable Lil U HUD, and publishes completion actions. It never performs
 * inference, tools, routing, verification, or voice itself.
 */
class WorkSessionForegroundService : Service() {
  private lateinit var notifications: NotificationManager
  private var windowManager: WindowManager? = null
  private var overlay: View? = null
  private var overlayParams: WindowManager.LayoutParams? = null
  private var objectiveView: TextView? = null
  private var feedView: TextView? = null
  private var petView: ImageView? = null
  private var expanded = true
  private val feed = ArrayDeque<String>()
  private var sessionId = ""
  private var objective = ""
  private var artifactUrl: String? = null
  private var active = false

  override fun onCreate() {
    super.onCreate()
    notifications = getSystemService(NotificationManager::class.java)
    windowManager = getSystemService(WindowManager::class.java)
    createChannels()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> startSession(intent)
      ACTION_PROGRESS -> updateProgress(intent.getStringExtra(EXTRA_STATUS).orEmpty())
      ACTION_COMPLETE -> completeSession(intent, blocked = false)
      ACTION_BLOCKED -> completeSession(intent, blocked = true)
      ACTION_CANCEL -> stopSession()
      ACTION_APP_VISIBLE -> hideOverlay()
      ACTION_APP_HIDDEN -> if (active) showOverlay()
    }
    return START_STICKY
  }

  private fun startSession(intent: Intent) {
    sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
    objective = intent.getStringExtra(EXTRA_OBJECTIVE).orEmpty().ifBlank { "Active WORK job" }
    artifactUrl = null
    active = true
    feed.clear()
    appendFeed("Received job")
    persistDisplayState()
    startForeground(ONGOING_ID, ongoingNotification("Starting…"))
    if (!appVisible) showOverlay()
    // TELEMETRY: every WORK session start is a routing decision.
    com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
      router = "WorkSessionForegroundService",
      kind = "session_state",
      decision = "STARTED",
      success = true,
      sessionId = sessionId,
      input = "objective.len=${objective.length} appVisible=$appVisible",
      output = "sessionId=$sessionId"
    )
  }

  private fun updateProgress(state: ExecutionState) {
    if (!active || state.message.isBlank()) return
    appendFeed(state.message)
    notifications.notify(ONGOING_ID, ongoingNotification(state.message))
    setPetForExecutionState(state)
    persistDisplayState()
  }

  // P0-5: Backwards-compatible string overload for callers that still pass raw strings
  private fun updateProgress(status: String) {
    updateProgress(ExecutionState(isActive = true, isSuccess = null, isVerified = null, toolName = null, message = status))
  }

  private fun completeSession(intent: Intent, blocked: Boolean) {
    val summary = intent.getStringExtra(EXTRA_SUMMARY).orEmpty()
    artifactUrl = intent.getStringExtra(EXTRA_ARTIFACT_URL)?.takeIf { it.isNotBlank() }
    appendFeed(if (blocked) "Blocked · $summary" else "Complete · $summary")
    updatePetFor(if (blocked) "failed" else "complete")
    notifications.notify(COMPLETION_ID, completionNotification(summary, blocked))
    active = false
    clearPersistedActiveState()
    stopForeground(STOP_FOREGROUND_REMOVE)
    overlay?.postDelayed({ hideOverlay(); stopSelf() }, 3_500L)
    // TELEMETRY: every WORK session completion (or block) is a routing decision.
    com.example.core.runtime.RoutingTelemetry.getOrNull()?.record(
      router = "WorkSessionForegroundService",
      kind = "session_state",
      decision = if (blocked) "BLOCKED" else "COMPLETED",
      success = !blocked,
      sessionId = sessionId,
      input = "blocked=$blocked",
      output = "summary.len=${summary.length} artifactUrl=${artifactUrl != null}"
    )
  }

  private fun stopSession() {
    active = false
    clearPersistedActiveState()
    hideOverlay()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun createChannels() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    notifications.createNotificationChannel(
      NotificationChannel(CHANNEL_WORK, "PurpClaw active work", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shows an active PurpClaw WORK session"
        setSound(null, null)
      }
    )
    notifications.createNotificationChannel(
      NotificationChannel(CHANNEL_COMPLETE, "PurpClaw work results", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Verified WORK results and blocked outcomes"
      }
    )
  }

  private fun ongoingNotification(status: String) = NotificationCompat.Builder(this, CHANNEL_WORK)
    .setSmallIcon(R.drawable.ic_launcher_foreground)
    .setContentTitle("PurpClaw working")
    .setContentText("${objective.take(54)} · ${status.take(42)}")
    .setOngoing(true)
    .setOnlyAlertOnce(true)
    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    .setContentIntent(appPendingIntent(artifactUrl))
    .addAction(0, "OPEN PURPCLAW", appPendingIntent(artifactUrl))
    .apply {
      if (!Settings.canDrawOverlays(this@WorkSessionForegroundService)) {
        addAction(0, "ENABLE FLOATING AGENT", overlayPermissionPendingIntent())
      }
    }
    .build()

  private fun overlayPermissionPendingIntent(): PendingIntent {
    val intent = Intent(
      Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
      android.net.Uri.parse("package:$packageName")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return PendingIntent.getActivity(
      this,
      7303,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun completionNotification(summary: String, blocked: Boolean): android.app.Notification {
    val open = appPendingIntent(artifactUrl)
    return NotificationCompat.Builder(this, CHANNEL_COMPLETE)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(if (blocked) "PurpClaw work needs attention" else "PurpClaw work complete")
      .setContentText(summary.ifBlank { objective }.take(100))
      .setStyle(NotificationCompat.BigTextStyle().bigText(summary.ifBlank { objective }))
      .setAutoCancel(true)
      .setContentIntent(open)
      .addAction(0, if (artifactUrl != null) "OPEN RESULT" else "OPEN PURPCLAW", open)
      .build()
  }

  private fun appPendingIntent(url: String?): PendingIntent {
    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra(EXTRA_OPEN_ARTIFACT_URL, url)
      putExtra(EXTRA_SESSION_ID, sessionId)
    }
    return PendingIntent.getActivity(
      this,
      (sessionId.hashCode() * 31 + (url?.hashCode() ?: 0)),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun showOverlay() {
    if (overlay != null || !Settings.canDrawOverlays(this)) return
    val root = buildOverlayView()
    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("overlay_x", 24)
      y = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("overlay_y", 180)
    }
    overlay = root
    overlayParams = params
    runCatching { windowManager?.addView(root, params) }
      .onFailure { overlay = null; overlayParams = null }
  }

  private fun hideOverlay() {
    overlay?.let { runCatching { windowManager?.removeView(it) } }
    overlay = null
    overlayParams = null
    objectiveView = null
    feedView = null
    petView = null
  }

  private fun buildOverlayView(): View {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(8), dp(8), dp(8), dp(8))
      background = roundedBackground(0xE6181020.toInt(), 18f)
      elevation = dp(8).toFloat()
    }
    objectiveView = TextView(this).apply {
      text = objective
      setTextColor(Color.WHITE)
      textSize = 13f
      maxWidth = dp(260)
      maxLines = 2
      setPadding(dp(10), dp(7), dp(10), dp(7))
      background = roundedBackground(0xF02B1744.toInt(), 16f)
    }.also(root::addView)
    petView = ImageView(this).apply {
      layoutParams = LinearLayout.LayoutParams(dp(112), dp(112))
      scaleType = ImageView.ScaleType.CENTER_INSIDE
      contentDescription = "PurpClaw floating agent"
      setOnClickListener { toggleExpanded() }
    }.also(root::addView)
    setAnimatedPet(R.drawable.lil_u_working)
    feedView = TextView(this).apply {
      text = feed.joinToString("\n") { "• $it" }
      setTextColor(0xFFE8DCF8.toInt())
      textSize = 11f
      maxWidth = dp(260)
      maxLines = 7
      setPadding(dp(10), dp(6), dp(10), dp(6))
      background = roundedBackground(0xE6100B16.toInt(), 12f)
    }.also(root::addView)
    val controls = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
    }
    controls.addView(Button(this).apply {
      text = "MIC"
      textSize = 11f
      setOnClickListener { sendBroadcast(Intent(ACTION_OVERLAY_MIC).setPackage(packageName)) }
    })
    controls.addView(Button(this).apply {
      text = "QUEUE / APP"
      textSize = 11f
      setOnClickListener { startActivity(Intent(this@WorkSessionForegroundService, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_OPEN_WORK_QUEUE, true)
      }) }
    })
    root.addView(controls)
    installDrag(root)
    return root
  }

  private fun roundedBackground(color: Int, radiusDp: Float) = android.graphics.drawable.GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusDp * resources.displayMetrics.density
    setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), 0xAA8B5CF6.toInt())
  }

  private fun installDrag(view: View) {
    var downX = 0f
    var downY = 0f
    var originX = 0
    var originY = 0
    view.setOnTouchListener { _, event ->
      val params = overlayParams ?: return@setOnTouchListener false
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y
          true
        }
        MotionEvent.ACTION_MOVE -> {
          if (abs(event.rawX - downX) + abs(event.rawY - downY) < 8f) return@setOnTouchListener true
          val bounds = windowManager?.currentWindowMetrics?.bounds
          val maxX = ((bounds?.width() ?: resources.displayMetrics.widthPixels) - view.width).coerceAtLeast(0)
          val maxY = ((bounds?.height() ?: resources.displayMetrics.heightPixels) - view.height).coerceAtLeast(0)
          params.x = (originX + (event.rawX - downX).toInt()).coerceIn(0, maxX)
          params.y = (originY + (event.rawY - downY).toInt()).coerceIn(0, maxY)
          windowManager?.updateViewLayout(view, params)
          true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("overlay_x", params.x).putInt("overlay_y", params.y).apply()
          true
        }
        else -> false
      }
    }
  }

  private fun toggleExpanded() {
    expanded = !expanded
    objectiveView?.visibility = if (expanded) View.VISIBLE else View.GONE
    feedView?.visibility = if (expanded) View.VISIBLE else View.GONE
    overlay?.let { view -> overlayParams?.let { windowManager?.updateViewLayout(view, it) } }
  }

  private fun appendFeed(status: String) {
    if (status.isBlank() || feed.lastOrNull() == status) return
    feed.addLast(status.take(90))
    while (feed.size > 7) feed.removeFirst()
    feedView?.text = feed.joinToString("\n") { "• $it" }
  }

  // P0-5: replaces keyword-matching updatePetFor with structured state-driven pet
  private fun setPetForExecutionState(state: ExecutionState) {
    val res = when {
      state.isVerified == false -> R.drawable.lil_u_error
      state.isSuccess == false -> R.drawable.lil_u_error
      state.isSuccess == true && state.isVerified == true -> R.drawable.lil_u_done
      state.toolName != null -> R.drawable.lil_u_tool_use
      else -> R.drawable.lil_u_loading
    }
    setAnimatedPet(res)
  }

  // Backward-compat keyword pet for string-only callers
  private fun updatePetFor(status: String) {
    val value = status.lowercase()
    val res = when {
      "fail" in value || "block" in value || "error" in value -> R.drawable.lil_u_error
      "recover" in value || "retry" in value -> R.drawable.lil_u_recover
      "complete" in value || "success" in value || "verified" in value -> R.drawable.lil_u_done
      "tool" in value || "execut" in value || "writ" in value -> R.drawable.lil_u_tool_use
      "verify" in value || "observ" in value -> R.drawable.lil_u_working
      else -> R.drawable.lil_u_loading
    }
    setAnimatedPet(res)
  }

  private fun setAnimatedPet(resId: Int) {
    val image = petView ?: return
    val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, resId)) }.getOrNull()
    } else ContextCompat.getDrawable(this, resId)
    image.setImageDrawable(drawable)
    (drawable as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
  }

  private fun persistDisplayState() {
    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
      .putBoolean("active", active)
      .putString("session_id", sessionId)
      .putString("objective", objective)
      .putString("status", feed.lastOrNull())
      .apply()
  }

  private fun clearPersistedActiveState() {
    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("active", false).apply()
  }

  override fun onDestroy() {
    hideOverlay()
    super.onDestroy()
  }

  companion object {
    const val ACTION_OVERLAY_MIC = "com.purpclaw.action.OVERLAY_MIC"
    const val EXTRA_OPEN_ARTIFACT_URL = "purpclaw.open_artifact_url"
    const val EXTRA_OPEN_WORK_QUEUE = "purpclaw.open_work_queue"
    private const val ACTION_START = "purpclaw.work.START"
    private const val ACTION_PROGRESS = "purpclaw.work.PROGRESS"
    private const val ACTION_COMPLETE = "purpclaw.work.COMPLETE"
    private const val ACTION_BLOCKED = "purpclaw.work.BLOCKED"
    private const val ACTION_CANCEL = "purpclaw.work.CANCEL"
    private const val ACTION_APP_VISIBLE = "purpclaw.work.APP_VISIBLE"
    private const val ACTION_APP_HIDDEN = "purpclaw.work.APP_HIDDEN"
    private const val EXTRA_SESSION_ID = "work_session_id"
    private const val EXTRA_OBJECTIVE = "objective"
    private const val EXTRA_STATUS = "status"
    private const val EXTRA_SUMMARY = "summary"
    private const val EXTRA_ARTIFACT_URL = "artifact_url"
    private const val CHANNEL_WORK = "purpclaw_worksession"
    private const val CHANNEL_COMPLETE = "purpclaw_work_complete"
    private const val ONGOING_ID = 7301
    private const val COMPLETION_ID = 7302
    private const val PREFS = "purpclaw_work_overlay"
    @Volatile private var appVisible = false

    private fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
      val intent = Intent(context, WorkSessionForegroundService::class.java).setAction(action).apply(extras)
      if (action == ACTION_START) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
    }

    fun start(context: Context, id: String, objective: String) = send(context, ACTION_START) {
      putExtra(EXTRA_SESSION_ID, id); putExtra(EXTRA_OBJECTIVE, objective)
    }
    fun progress(context: Context, status: String) = send(context, ACTION_PROGRESS) { putExtra(EXTRA_STATUS, status) }
    fun complete(context: Context, summary: String, artifactUrl: String?) = send(context, ACTION_COMPLETE) {
      putExtra(EXTRA_SUMMARY, summary); putExtra(EXTRA_ARTIFACT_URL, artifactUrl)
    }
    fun blocked(context: Context, summary: String) = send(context, ACTION_BLOCKED) { putExtra(EXTRA_SUMMARY, summary) }
    fun cancel(context: Context) = send(context, ACTION_CANCEL)
    fun setAppVisible(context: Context, visible: Boolean) {
      appVisible = visible
      val hasActiveWork = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("active", false)
      if (hasActiveWork) send(context, if (visible) ACTION_APP_VISIBLE else ACTION_APP_HIDDEN)
    }
  }
}
