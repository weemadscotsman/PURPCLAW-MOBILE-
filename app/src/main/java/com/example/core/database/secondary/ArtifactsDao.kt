package com.example.core.database.secondary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * ARTIFACTS DAO — read patterns for the media artifact index.
 *
 * Lane-owned (DB migrations lane). Consumer wiring arrives with the
 * tool-wire lane.
 */
@Dao
interface ArtifactsDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(artifact: ArtifactsEntity)

  @Query("SELECT * FROM media_artifacts WHERE id = :id")
  suspend fun byId(id: String): ArtifactsEntity?

  @Query("SELECT * FROM media_artifacts WHERE chatId = :chatId ORDER BY createdAt DESC")
  fun forChat(chatId: String): Flow<List<ArtifactsEntity>>

  @Query("SELECT * FROM media_artifacts WHERE parentArtifactId = :parentId ORDER BY createdAt DESC")
  fun childrenOf(parentId: String): Flow<List<ArtifactsEntity>>

  @Query("SELECT * FROM media_artifacts WHERE sha256Prefix = :prefix LIMIT 1")
  suspend fun bySha256Prefix(prefix: String): ArtifactsEntity?

  @Query("SELECT COUNT(*) FROM media_artifacts")
  suspend fun count(): Int
}
