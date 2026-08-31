package com.example.core.runtime

import com.example.core.database.MemoryDao
import com.example.core.database.MemoryItemEntity
import com.example.core.model.MemoryLayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

class MemoryGateway(private val memoryDao: MemoryDao) {
  /** Honest spine states — no fabrication. */
  enum class SpineStatus { OFFLINE, EMPTY, ONLINE }


  /**
   * STEP 13 (#49, 2026-08-27) — cognitive-spine recall resolver. Wired by
   * MainViewModel to HomeRuntimeBridge GET /memory/recall when home is
   * online. Null / failure / offline ⇒ empty list. NEVER fabricate.
   */
  @Volatile
  var spineRecallProvider: (suspend (query: String) -> List<String>)? = null

  companion object {
    /** The honest rendered marker for a layer with no rows. */
    const val EMPTY_MARKER = "(empty)"
    private const val OFFLINE_MARKER = "(offline — no home)"
  }

  // ─────────── TYPED 7-LAYER ACCESSORS (task #49, plan 13.4) ───────────
  // Each returns rendered bullet lines. Empty layers render the honest
  // EMPTY_MARKER — one string — never a fabricated entry. The mobile DB
  // layers map onto the home-stack contracts as noted per accessor.

  /** USER.md contract → mobile AFFECTIVE layer (operator rapport/preferences). */
  suspend fun readUserMdHead(n: Int = 3): List<String> =
    layerHead(MemoryLayer.AFFECTIVE.name, n)

  /** MEMORY.md contract → live working-memory StateFlow head. */
  fun readMemoryMdHead(n: Int = 5): List<String> =
    workingMemory.value.take(n).ifEmpty { listOf(EMPTY_MARKER) }

  /** EVENT SPINE contract → mobile EPISODIC layer (session/timeline history). */
  suspend fun readEventSpineTail(n: Int = 3): List<String> =
    layerHead(MemoryLayer.EPISODIC.name, n)

  /** SKILLS contract → mobile PROCEDURAL layer (execution recipes/runbooks). */
  suspend fun readSkillsTail(n: Int = 3): List<String> =
    layerHead(MemoryLayer.PROCEDURAL.name, n)

  /** CHECKPOINT contract → SYMBOLIC rows keyed "checkpoint.*" (none seeded yet ⇒ honest empty). */
  suspend fun readCheckpointHead(n: Int = 1): List<String> =
    keyPrefixHead("checkpoint.", n)

  /** CRYOSLEEP contract → SYMBOLIC rows keyed "cryosleep.*" (none seeded yet ⇒ honest empty). */
  suspend fun readCryosleepHead(n: Int = 1): List<String> =
    keyPrefixHead("cryosleep.", n)

  /** COGNITIVE SPINE → live recall over the home stack's memory HTTP API. */
  suspend fun recallFromCognitiveSpine(query: String): List<String> {
    val provider = spineRecallProvider ?: return listOf(OFFLINE_MARKER)
    return try {
      val hits = provider(query).take(5)
      if (hits.isEmpty()) listOf(EMPTY_MARKER) else hits
    } catch (e: Exception) {
      listOf(OFFLINE_MARKER)
    }
  }

  /** EXPOSES GENUINE SPINE STATE (evidence pass paste_24): the Memory page
   * shows whether the home cognitive spine is ONLINE, OFFLINE, or recall
   * is EMPTY — no fabrication, just what recallFromCognitiveSpine actually
   * returned last. */
  private val _spineStatus = MutableStateFlow<SpineStatus>(SpineStatus.OFFLINE)
  val spineStatus: StateFlow<SpineStatus> = _spineStatus.asStateFlow()

  suspend fun probeCognitiveSpine(query: String = "session") {
    val result = recallFromCognitiveSpine(query)
    _spineStatus.value = when {
      result.isEmpty() -> SpineStatus.EMPTY
      result.any { it.contains(OFFLINE_MARKER) || it.contains("error") } -> SpineStatus.OFFLINE
      result.any { it == EMPTY_MARKER } -> SpineStatus.EMPTY
      else -> SpineStatus.ONLINE
    }
  }

  private suspend fun layerHead(layer: String, n: Int): List<String> {
    val rows = memoryDao.getMemoryByLayer(layer).first()
    return renderRows(rows, n)
  }

  private suspend fun keyPrefixHead(prefix: String, n: Int): List<String> {
    val rows = memoryDao.getMemoryByLayer(MemoryLayer.SYMBOLIC.name).first()
      .filter { it.key.startsWith(prefix) }
    return renderRows(rows, n)
  }

  private fun renderRows(rows: List<MemoryItemEntity>, n: Int): List<String> {
    if (rows.isEmpty()) return listOf(EMPTY_MARKER)
    return rows.take(n).map { "[${it.key}] ${it.content}" }
  }

  /** The full 7-layer weave as one pre-rendered prompt block for a seat. */
  suspend fun buildWeaveBlock(topic: String): String = buildString {
    appendLine("[MEMORY WEAVE — 7 layers, honest empties]")
    appendLine("USER.md (operator):"); readUserMdHead(3).forEach { appendLine("  - $it") }
    appendLine("MEMORY.md (hot):"); readMemoryMdHead(5).forEach { appendLine("  - $it") }
    appendLine("EVENT SPINE (recent):"); readEventSpineTail(3).forEach { appendLine("  - $it") }
    appendLine("SKILLS (procedural):"); readSkillsTail(3).forEach { appendLine("  - $it") }
    appendLine("CHECKPOINT:"); readCheckpointHead(1).forEach { appendLine("  - $it") }
    appendLine("CRYOSLEEP:"); readCryosleepHead(1).forEach { appendLine("  - $it") }
    appendLine("COGNITIVE SPINE (recall \"$topic\"):")
    recallFromCognitiveSpine(topic).forEach { appendLine("  - $it") }
  }.trimEnd()


  // Starts honestly empty. Runtime events and persisted Room rows populate it;
  // product copy must never masquerade as an observed memory.
  private val _workingMemory = MutableStateFlow<List<String>>(emptyList())
  val workingMemory: StateFlow<List<String>> = _workingMemory.asStateFlow()

  val allMemoryItems: Flow<List<MemoryItemEntity>> = memoryDao.getAllMemoryItems()

  suspend fun recallForQuery(query: String): List<MemoryItemEntity> {
    val results = memoryDao.searchMemory(query)
    results.forEach { memoryDao.incrementAccess(it.id) }
    return results
  }

  suspend fun storeMemory(
    layer: MemoryLayer,
    key: String,
    content: String,
    score: Float = 0.95f,
    isPinned: Boolean = false
  ): Long {
    val entity = MemoryItemEntity(
      layer = layer.name,
      key = key,
      content = content,
      score = score,
      timestamp = System.currentTimeMillis(),
      isPinned = isPinned
    )
    val id = memoryDao.insertMemoryItem(entity)
    _workingMemory.value = (_workingMemory.value + "Recall: [${layer.displayName}] $key").takeLast(10)
    return id
  }

  suspend fun deleteMemory(id: Long) {
    memoryDao.deleteMemoryItem(id)
  }

  fun updateWorkingMemory(newItem: String) {
    _workingMemory.value = (_workingMemory.value + newItem).takeLast(8)
  }
}
