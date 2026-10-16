package com.pablo.yumemi.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
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
