package com.pablo.yumemi.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ThreadEntity::class, MessageEntity::class, CronResultEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class YumemiDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun cronResultDao(): CronResultDao

    companion object {
        /** Adds the cron results table without touching cached chats. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cron_results` (`id` TEXT NOT NULL, `jobId` TEXT NOT NULL, " +
                        "`jobName` TEXT NOT NULL, `runAt` INTEGER NOT NULL, `text` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cron_results_runAt` ON `cron_results` (`runAt`)")
            }
        }

        /** Latest-message snippet, source and favourites on threads. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `threads` ADD COLUMN `lastMessage` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `threads` ADD COLUMN `lastFromUser` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `threads` ADD COLUMN `lastAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `threads` ADD COLUMN `source` TEXT")
                db.execSQL("ALTER TABLE `threads` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
