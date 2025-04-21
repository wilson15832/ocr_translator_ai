package com.example.ocr_translation

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.graphics.Rect
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete

/**

Main database class for the application
Contains all DAOs and database configuration
 */

@Database(
    entities = [
        TranslationCacheEntity::class,
        RecentTranslationEntity::class,
        TranslationArea::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    // DAOs
    abstract fun translationCacheDao(): TranslationCacheDao
    abstract fun recentTranslationsDao(): RecentTranslationsDao
    abstract fun translationAreaDao(): TranslationAreaDao

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

Type converters for complex data types in the database
 */
class Converters {
    private val gson = Gson()
    @TypeConverter
    fun fromRectToString(rect: Rect?): String? {
        if (rect == null) return null
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    @TypeConverter
    fun fromStringToRect(value: String?): Rect? {
        if (value == null) return null
        val parts = value.split(",")
        if (parts.size != 4) return null
        return Rect(
            parts[0].toInt(),
            parts[1].toInt(),
            parts[2].toInt(),
            parts[3].toInt()
        )
    }
    @TypeConverter
    fun fromStringMap(value: String?): Map<String, String>? {
        if (value == null) return null
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(value, mapType)
    }
    @TypeConverter
    fun fromMap(map: Map<String, String>?): String? {
        if (map == null) return null
        return gson.toJson(map)
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
    @androidx.room.Query("SELECT * FROM translation_cache ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTranslations(limit: Int): List<TranslationCacheEntity>
}

/**

Entity for storing recent translations for history feature
 */
@androidx.room.Entity(tableName = "recent_translations")
data class RecentTranslationEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalText: String,
    val translatedText: String,
    val timestamp: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val isFavorite: Boolean = false
)

/**

DAO for recent translations history
 */
@androidx.room.Dao
interface RecentTranslationsDao {
    @androidx.room.Insert
    suspend fun insertTranslation(entity: RecentTranslationEntity): Long
    @androidx.room.Query("SELECT * FROM recent_translations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTranslations(limit: Int): List<RecentTranslationEntity>
    @androidx.room.Query("SELECT * FROM recent_translations WHERE isFavorite = 1 ORDER BY timestamp DESC")
    suspend fun getFavoriteTranslations(): List<RecentTranslationEntity>
    @androidx.room.Update
    suspend fun updateTranslation(entity: RecentTranslationEntity)
    @androidx.room.Query("DELETE FROM recent_translations WHERE id = :id")
    suspend fun deleteTranslation(id: Long)
    @androidx.room.Query("DELETE FROM recent_translations WHERE timestamp < :timestamp AND isFavorite = 0")
    suspend fun deleteOldTranslations(timestamp: Long)
}
