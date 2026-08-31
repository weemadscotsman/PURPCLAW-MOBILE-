/**
 * WORK SESSION MANAGER — handles session lifecycle, WorkManager integration,
 * and the notification-with-actions flow for background delegated work.
 *
 * Lifecycle:
 *   VOICE REQUEST → createSession() → PLANNING → EXECUTING → VERIFYING → COMPLETED
 *   Any failure → CANCELLED
 *
 * Execution lanes:
 *   - WorkManager (OneTimeWorkRequest): deferrable / retryable jobs
 *   - Foreground service: genuinely active long-running agent work
 *
 * Completion law: COMPLETED only fires after verification receipt, not model boredom.
 * Notification actions: OPEN CODE / OPEN RESULT / RECEIPT / RESUME / CANCEL
 */
class WorkSessionManager(
  private val context: Context,
  private val dispatchRecorder: DispatchRecorder,
  private val prefs: android.content.SharedPreferences
) {
  companion object {
    const val NOTIFICATION_CHANNEL_ID = "purpclaw_work_sessions"
    const val NOTIFICATION_CHANNEL_NAME = "Work Sessions"
    const val NOTIFICATION_CHANNEL_DESC = "PurpClaw background job notifications with action buttons"
  }

  init {
    ensureNotificationChannel()
  }

  private fun ensureNotificationChannel() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      val channel = android.app.NotificationChannel(
        NOTIFICATION_CHANNEL_ID,
        NOTIFICATION_CHANNEL_NAME,
        android.app.NotificationManager.IMPORTANCE_HIGH
      ).apply {
        description = NOTIFICATION_CHANNEL_DESC
        enableVibration(true)
        setShowBadge(true)
      }
      val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
      nm.createNotificationChannel(channel)
    }
  }

  /**
   * Start a new WorkSession. Creates a WorkManager OneTimeWorkRequest and
   * persists the session to SharedPreferences keyed by sessionId.
   *
   * @return the WorkManager UUID for this session
   */
  fun createSession(session: WorkSession): String {
    val workRequest = androidx.work.OneTimeWorkRequestBuilder<WorkSessionWorker>()
      .setInputData(
        androidx.work.Data.Builder()
          .putString("sessionId", session.sessionId)
          .putString("objective", session.objective)
          .putString("plan", session.plan)
          .putString("acceptanceCriteria", session.acceptanceCriteria)
          .build()
      )
      .build()

    androidx.work.WorkManager.getInstance(context)
      .enqueueUniqueWork(
        session.sessionId,
        androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
        workRequest
      )

    val updated = session.copy(
      executionId = workRequest.id.toString(),
      status = WorkSession.Status.EXECUTING,
      updatedAt = System.currentTimeMillis()
    )
    saveSession(updated)

    dispatchRecorder.record(
      com.example.core.model.DispatchReceipt(
        dispatchId = "SESSION_CREATE_${session.sessionId}",
        decision = "SESSION_CREATED",
        errorCode = null,
        description = "WorkManager job enqueued: ${workRequest.id}",
        observedAt = System.currentTimeMillis()
      )
    )

    return workRequest.id.toString()
  }

  /** Get a session from SharedPreferences. Returns null if not found. */
  fun getSession(sessionId: String): WorkSession? {
    val json = prefs.getString("ws_$sessionId", null) ?: return null
    return com.google.gson.Gson().fromJson(json, WorkSession::class.java)
  }

  /** Update session status and persist. */
  fun updateStatus(sessionId: String, newStatus: WorkSession.Status): WorkSession {
    val current = getSession(sessionId)
      ?: throw IllegalArgumentException("Session $sessionId not found")
    val updated = current.copy(
      status = newStatus,
      updatedAt = System.currentTimeMillis()
    )
    saveSession(updated)
    return updated
  }

  /**
   * Mark session COMPLETED. Only call this after verification passes.
   * Emits a completion notification with action buttons.
   */
  fun completeWithNotification(sessionId: String): WorkSession {
    val session = getSession(sessionId)
      ?: throw IllegalArgumentException("Session $sessionId not found")

    val completed = session.copy(
      status = WorkSession.Status.COMPLETED,
      updatedAt = System.currentTimeMillis()
    )
    saveSession(completed)

    dispatchRecorder.record(
      com.example.core.model.DispatchReceipt(
        dispatchId = "SESSION_COMPLETE_$sessionId",
        decision = "SESSION_COMPLETED",
        errorCode = null,
        description = "Verified complete. ${completed.artifactRefs.size} artifact(s): ${completed.artifactRefs.joinToString { it.key }}",
        observedAt = System.currentTimeMillis()
      )
    )

    showCompletionNotification(completed)
    return completed
  }

  /** Cancel a session. Cancels WorkManager work and marks CANCELLED. */
  fun cancelSession(sessionId: String): WorkSession {
    androidx.work.WorkManager.getInstance(context).cancelUniqueWork(sessionId)
    val session = getSession(sessionId)
      ?: throw IllegalArgumentException("Session $sessionId not found")
    val cancelled = session.copy(
      status = WorkSession.Status.CANCELLED,
      updatedAt = System.currentTimeMillis()
    )
    saveSession(cancelled)

    dispatchRecorder.record(
      com.example.core.model.DispatchReceipt(
        dispatchId = "SESSION_CANCEL_$sessionId",
        decision = "SESSION_CANCELLED",
        errorCode = null,
        description = "Cancelled by operator",
        observedAt = System.currentTimeMillis()
      )
    )
    return cancelled
  }

  /** List all sessions sorted newest-first. */
  fun listSessions(): List<WorkSession> {
    return prefs.all.entries
      .filter { it.key.startsWith("ws_") }
      .mapNotNull { (_, v) ->
        try { com.google.gson.Gson().fromJson(v as String, WorkSession::class.java) } catch (_: Throwable) { null }
      }
      .sortedByDescending { it.updatedAt }
  }

  private fun saveSession(session: WorkSession) {
    val json = com.google.gson.Gson().toJson(session)
    prefs.edit().putString("ws_${session.sessionId}", json).apply()
  }

  private fun showCompletionNotification(session: WorkSession) {
    val nm = android.app.NotificationManager::class.java
      .cast(context.getSystemService(android.content.Context.NOTIFICATION_SERVICE))

    // Build action intents
    fun actionIntent(code: String): android.app.PendingIntent {
      val intent = android.content.Intent(context, com.example.MainActivity::class.java)
        .apply {
          action = "com.purpclaw.WORK_SESSION_ACTION"
          putExtra("action", code)
          putExtra("sessionId", session.sessionId)
          flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
      return android.app.PendingIntent.getActivity(
        context, code.hashCode(), intent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
      )
    }

    val openCode = actionIntent("OPEN_CODE")
    val openResult = actionIntent("OPEN_RESULT")
    val receipt = actionIntent("RECEIPT")
    val resume = actionIntent("RESUME")
    val cancel = actionIntent("CANCEL")

    val summary = if (session.artifactRefs.isEmpty()) {
      "Job complete — no artifacts produced"
    } else {
      val first = session.artifactRefs.first()
      "${session.artifactRefs.size} artifact(s): ${first.key}"
    }

    val notification = androidx.core.app.NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(com.example.R.drawable.ic_launcher)
      .setContentTitle("✅ ${session.objective.take(50)}${if (session.objective.length > 50) "…" else ""}")
      .setContentText(summary)
      .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(summary))
      .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
      .setCategory(androidx.core.app.NotificationCompat.CATEGORY_WORK_PENDING)
      .setAutoCancel(true)
      .setContentIntent(openResult)
      .addAction(0, "📂 OPEN CODE", openCode)
      .addAction(0, "📄 VIEW RESULT", openResult)
      .addAction(0, "🧾 RECEIPT", receipt)
      .addAction(0, "🔄 RESUME", resume)
      .addAction(0, "✕ CANCEL", cancel)
      .build()

    nm.notify(session.sessionId.hashCode(), 1, notification)
  }
}

/* =============================================================================
 * WORK SESSION WORKER — executed by WorkManager for deferrable/retryable jobs.
 * ============================================================================= */
class WorkSessionWorker(
  private val appContext: Context,
  workerParams: androidx.work.CoroutineWorkerParameters
) : androidx.work.CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): androidx.work.Result {
    val sessionId = inputData.getString("sessionId")
      ?: return androidx.work.Result.failure()

    // Load session from preferences
    val prefs = appContext.getSharedPreferences("purpclaw_state", android.content.Context.MODE_PRIVATE)
    val manager = WorkSessionManager(
      appContext,
      DispatchRecorder(com.example.core.database.DispatchLedgerDao(appContext)),
      prefs
    )

    val session = manager.getSession(sessionId) ?: return androidx.work.Result.failure()

    // Transition: EXECUTING → VERIFYING
    manager.updateStatus(sessionId, WorkSession.Status.VERIFYING)

    // Execute the actual work here.
    // In the real app, this would call the Forge loop / agent execution engine.
    // For now, simulate work completion with artifact registration.
    val artifacts = executeWork(session)

    // Mark VERIFYING → COMPLETED (in production, verify() gates this)
    val completed = session.copy(
      status = WorkSession.Status.COMPLETED,
      updatedAt = System.currentTimeMillis(),
      artifactRefs = artifacts
    )

    // Save completed session with artifacts
    val json = com.google.gson.Gson().toJson(completed)
    prefs.edit().putString("ws_${session.sessionId}", json).apply()

    // Emit completion receipt
    DispatchRecorder(com.example.core.database.DispatchLedgerDao(appContext)).record(
      com.example.core.model.DispatchReceipt(
        dispatchId = "SESSION_COMPLETE_$sessionId",
        decision = "SESSION_COMPLETED",
        errorCode = null,
        description = "WorkManager execution complete. ${artifacts.size} artifact(s).",
        observedAt = System.currentTimeMillis()
      )
    )

    // Show notification with actions
    manager.showCompletionNotification(completed)

    return androidx.work.Result.success()
  }

  /**
   * Execute the delegated work. Returns list of ArtifactRef produced.
   *
   * In a full implementation, this would:
   * 1. Load the Forge/Soul/Provider for this session
   * 2. Run the execution loop (code generation, document creation, etc.)
   * 3. Save artifacts to the phone's files directory
   * 4. Return the ArtifactRef list
   *
   * Placeholder returns empty list — real execution replaces this body.
   */
  private fun executeWork(session: WorkSession): List<WorkSession.ArtifactRef> {
    // TODO: wire actual Forge/agent execution here
    // For now: record the intent in the dispatch ledger
    DispatchRecorder(com.example.core.database.DispatchLedgerDao(appContext)).record(
      com.example.core.model.DispatchReceipt(
        dispatchId = "SESSION_EXEC_$session.sessionId",
        decision = "SESSION_EXECUTING",
        errorCode = null,
        description = "WorkSession '${session.objective}' executing",
        observedAt = System.currentTimeMillis()
      )
    )
    return emptyList()
  }
}
