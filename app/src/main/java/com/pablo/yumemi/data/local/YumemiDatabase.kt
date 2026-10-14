package com.pablo.yumemi.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ThreadEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class YumemiDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
}
