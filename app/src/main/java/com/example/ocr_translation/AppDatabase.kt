package com.example.ocr_translation

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**

Main database class for the application
Contains all DAOs and database configuration
 */

@Database(
    entities = [
        TranslationCacheEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    // DAOs
    abstract fun translationCacheDao(): TranslationCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "screen_translator_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**

Entity for cached translations
 */
@androidx.room.Entity(tableName = "translation_cache")
data class TranslationCacheEntity(
    @androidx.room.PrimaryKey val cacheKey: String,
    val translationJson: String,
    val timestamp: Long,
    val sourceLanguage: String,
    val targetLanguage: String
)

/**

DAO for translation cache operations
 */
@androidx.room.Dao
interface TranslationCacheDao {
    @androidx.room.Query("SELECT * FROM translation_cache WHERE cacheKey = :cacheKey")
    suspend fun getTranslation(cacheKey: String): TranslationCacheEntity?
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertTranslation(entity: TranslationCacheEntity)
    @androidx.room.Query("SELECT COUNT(*) FROM translation_cache")
    suspend fun getCount(): Int
    @androidx.room.Query("DELETE FROM translation_cache WHERE timestamp < :expiryTime")
    suspend fun deleteExpired(expiryTime: Long)
    @androidx.room.Query("DELETE FROM translation_cache WHERE cacheKey IN (SELECT cacheKey FROM translation_cache ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)
    @androidx.room.Query("DELETE FROM translation_cache")
    suspend fun deleteAll()
}