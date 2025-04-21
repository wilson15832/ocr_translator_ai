package com.example.ocr_translation

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Translation cache system using Room Database
 */
class TranslationCache(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.translationCacheDao()
    private val gson = Gson()

    // Get translation from cache
    suspend fun getTranslation(cacheKey: String): List<TranslationService.TranslatedBlock>? {
        return withContext(Dispatchers.IO) {
            val cacheEntry = dao.getTranslation(cacheKey)
            if (cacheEntry != null && !isCacheExpired(cacheEntry.timestamp)) {
                // Convert JSON back to object
                val type = object : TypeToken<List<TranslationService.TranslatedBlock>>() {}.type
                return@withContext gson.fromJson<List<TranslationService.TranslatedBlock>>(
                    cacheEntry.translationJson, type)
            }
            null
        }
    }

    // Save translation to cache
    suspend fun saveTranslation(
        cacheKey: String,
        translations: List<TranslationService.TranslatedBlock>,
        sourceLanguage: String,
        targetLanguage: String
    ) {
        withContext(Dispatchers.IO) {
            // Convert object to JSON
            val translationJson = gson.toJson(translations)

            val cacheEntry = TranslationCacheEntity(
                cacheKey = cacheKey,
                translationJson = translationJson,
                timestamp = System.currentTimeMillis(),
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage
            )

            dao.insertTranslation(cacheEntry)

            // Clean up old entries if needed
            cleanupOldEntries()
        }
    }

    // Check if cache is expired (default 24 hours)
    private fun isCacheExpired(timestamp: Long, ttlMillis: Long = 24 * 60 * 60 * 1000): Boolean {
        val age = System.currentTimeMillis() - timestamp
        return age > ttlMillis
    }

    // Clean up old entries
    private suspend fun cleanupOldEntries(maxEntries: Int = 100) {
        val count = dao.getCount()
        if (count > maxEntries) {
            val entriesToDelete = count - maxEntries
            dao.deleteOldest(entriesToDelete)
        }

        // Also delete expired entries
        val expiryTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        dao.deleteExpired(expiryTime)
    }

    // Clear cache
    suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            dao.deleteAll()
        }
    }
}