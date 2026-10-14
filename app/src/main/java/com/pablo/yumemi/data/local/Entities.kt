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
