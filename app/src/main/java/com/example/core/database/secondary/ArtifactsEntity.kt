package com.example.core.database.secondary

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * MEDIA ARTIFACT — index row for one generated/captured media asset.
 *
 * Spec §2 (Gate B — Android DB Migrations) of
 * docs/PURPCLAW_MOBILE_SURFACE_CERTIFICATION_SPEC.md:
 *   id, parentArtifactId, chatId, createdAt, sha256_prefix,
 *   type, visibility, mime, bytes, metadata JSON
 *
 * Required indexes per §2.3: id (PK), parentArtifactId, chatId,
 * createdAt, sha256_prefix. Indexes declared via @Entity(indices=...)
 * so Room emits CREATE INDEX in v1 — no manual migration needed for the
 * baseline set.
 *
 * Lane note: this entity lives in `core/database/secondary/` so the
 * active monolith PurpClawDatabase stays wire-side owned. Consumers
 * wire it up once tool wire lands.
 */
@Entity(
  tableName = "media_artifacts",
  indices = [
    Index(value = ["parentArtifactId"]),
    Index(value = ["chatId"]),
    Index(value = ["createdAt"]),
    Index(value = ["sha256Prefix"])
  ]
)
data class ArtifactsEntity(
  @PrimaryKey val id: String,
  val parentArtifactId: String?,
  val chatId: String?,
  val createdAt: Long,
  val sha256Prefix: String,
  val type: String,        // IMAGE | VIDEO | AUDIO | FILE | ...
  val visibility: String,  // PRIVATE | PUBLIC | BOTH
  val mime: String,
  val bytes: Long,
  val metadataJson: String, // serialized metadata blob (Moshi)
  val integrityStatus: String = "OK" // OK | INTEGRITY_FAILED
)
