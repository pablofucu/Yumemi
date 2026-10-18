package com.pablo.yumemi.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadDao {
    @Query("SELECT * FROM threads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE id = :id")
    fun observe(id: String): Flow<ThreadEntity?>

    @Query("SELECT * FROM threads WHERE id = :id")
    suspend fun get(id: String): ThreadEntity?

    @Query("SELECT * FROM threads")
    suspend fun getAll(): List<ThreadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thread: ThreadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(threads: List<ThreadEntity>)

    @Query("DELETE FROM threads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM threads WHERE id NOT IN (:ids)")
    suspend fun deleteNotIn(ids: List<String>)

    @Query("UPDATE threads SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String)

    @Query("UPDATE threads SET updatedAt = :updatedAt, preview = :preview WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long, preview: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY createdAt ASC, rowid ASC")
    fun observe(threadId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE threadId = :threadId")
    suspend fun deleteForThread(threadId: String)

    @Query("DELETE FROM messages WHERE threadId NOT IN (SELECT id FROM threads)")
    suspend fun deleteOrphans()
}

@Dao
interface CronResultDao {
    @Query("SELECT * FROM cron_results ORDER BY runAt DESC")
    fun observeAll(): Flow<List<CronResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(results: List<CronResultEntity>)

    @Query("SELECT id FROM cron_results")
    suspend fun ids(): List<String>

    @Query("DELETE FROM cron_results WHERE id = :id")
    suspend fun delete(id: String)
}
