package com.example.ocr_translation

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TranslationAreaDao {
    @Query("SELECT * FROM translation_areas")
    suspend fun getAllAreas(): List<TranslationArea>

    @Insert
    suspend fun insertArea(area: TranslationArea): Long

    @Delete
    suspend fun deleteArea(area: TranslationArea)

    @Query("SELECT * FROM translation_areas WHERE id = :id")
    suspend fun getAreaById(id: Long): TranslationArea?
}