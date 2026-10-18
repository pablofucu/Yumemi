package com.pablo.yumemi.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: Long,
    /** Snippet reported by the server (usually the first message). */
    val preview: String,
    /** Snippet of the latest message, from a finished turn or a fetched transcript. */
    @ColumnInfo(defaultValue = "") val lastMessage: String = "",
    @ColumnInfo(defaultValue = "0") val lastFromUser: Boolean = false,
    /** Server updatedAt the [lastMessage] corresponds to; older than updatedAt means it may be stale. */
    @ColumnInfo(defaultValue = "0") val lastAt: Long = 0,
    val source: String? = null,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
)

@Entity(tableName = "messages", indices = [Index("threadId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val role: String,
    val partsJson: String,
    val createdAt: Long,
    val error: String?,
)

/** A message a cron job run produced; [id] is "jobId:runAt" so re-seeing the same run is idempotent. */
@Entity(tableName = "cron_results", indices = [Index("runAt")])
data class CronResultEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val jobName: String,
    val runAt: Long,
    val text: String,
)
