package com.footprint.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, TrackPointEntity::class, AttachmentEntity::class, TrackMomentEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class FootprintDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile private var instance: FootprintDatabase? = null

        fun getInstance(context: Context): FootprintDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FootprintDatabase::class.java,
                    "footprint.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
