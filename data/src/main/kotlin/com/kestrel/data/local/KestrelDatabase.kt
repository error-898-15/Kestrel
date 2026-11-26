// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface RepoDao {
    @Query("SELECT * FROM repositories ORDER BY addedAtEpochMs DESC")
    fun observeAll(): Flow<List<RepoEntity>>

    @Query("SELECT * FROM repositories WHERE id = :repoId LIMIT 1")
    suspend fun getById(repoId: String): RepoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RepoEntity)

    @Query("DELETE FROM repositories WHERE id = :repoId")
    suspend fun deleteById(repoId: String)
}

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugins ORDER BY displayName ASC")
    fun observeAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugins WHERE repoId = :repoId")
    suspend fun getByRepo(repoId: String): List<PluginEntity>

    @Query("SELECT * FROM plugins WHERE id = :pluginId LIMIT 1")
    suspend fun getById(pluginId: String): PluginEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PluginEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<PluginEntity>)

    @Query("DELETE FROM plugins WHERE repoId = :repoId")
    suspend fun deleteByRepo(repoId: String)

    @Query("UPDATE plugins SET isEnabled = :enabled WHERE id = :pluginId")
    suspend fun setEnabled(pluginId: String, enabled: Boolean)
}

@Database(
    entities = [RepoEntity::class, PluginEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KestrelDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao
    abstract fun pluginDao(): PluginDao
}
