package com.example.ocr_translation

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Translation cache backed by Room.
 *
 * TTL and maximum entry count are read from [PreferencesManager] on every operation, so the
 * Settings sliders for `cacheTtlHours` / `maxCacheEntries` actually take effect (the previous
 * implementation ignored both and used hard-coded 24h / 100 entries).
 */
class TranslationCache(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val dao = db.translationCacheDao()
    private val gson = Gson()

    private val prefs get() = PreferencesManager.getInstance(appContext)
    private fun ttlMillis(): Long = prefs.cacheTtlHours.coerceAtLeast(1) * 60L * 60L * 1000L
    private fun maxEntries(): Int = prefs.maxCacheEntries.coerceAtLeast(10)

    // Get translation from cache
    suspend fun getTranslation(cacheKey: String): List<TranslationService.TranslatedBlock>? {
        return withContext(Dispatchers.IO) {
            val cacheEntry = dao.getTranslation(cacheKey)
            if (cacheEntry != null && !isCacheExpired(cacheEntry.timestamp, ttlMillis())) {
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
            val translationJson = gson.toJson(translations)

            val cacheEntry = TranslationCacheEntity(
                cacheKey = cacheKey,
                translationJson = translationJson,
                timestamp = System.currentTimeMillis(),
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage
            )

            dao.insertTranslation(cacheEntry)
            cleanupOldEntries(maxEntries(), ttlMillis())
        }
    }

    private fun isCacheExpired(timestamp: Long, ttlMillis: Long): Boolean {
        return System.currentTimeMillis() - timestamp > ttlMillis
    }

    private suspend fun cleanupOldEntries(maxEntries: Int, ttlMillis: Long) {
        val count = dao.getCount()
        if (count > maxEntries) {
            dao.deleteOldest(count - maxEntries)
        }
        val expiryTime = System.currentTimeMillis() - ttlMillis
        dao.deleteExpired(expiryTime)
    }

//    // Clear cache
//    suspend fun clearCache() {
//        withContext(Dispatchers.IO) {
//            dao.deleteAll()
//        }
//    }
}