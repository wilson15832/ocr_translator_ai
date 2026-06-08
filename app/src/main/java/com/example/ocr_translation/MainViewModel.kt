package com.example.ocr_translation

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import android.util.Log

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager.getInstance(application)
    private val _translationActive = MutableLiveData<Boolean>()
    val translationActive: LiveData<Boolean> = _translationActive

    init {
        // Check if translation service was running before app was closed
        _translationActive.value = preferencesManager.isTranslationActive()
    }

    fun setTranslationActive(active: Boolean) {
        Log.d("MainViewModel", "Setting translation active: $active")
        _translationActive.value = active
        preferencesManager.setTranslationActive(active)
    }

    val selectedSourceLanguage: String
        get() = preferencesManager.sourceLanguage

    val selectedTargetLanguage: String
        get() = preferencesManager.targetLanguage

    // Persist the source language. TranslationService picks it up via PreferencesManager on the
    // next translate call (config is reloaded each time), so no in-memory setter is needed.
    fun updateSourceLanguage(languageCode: String) {
        preferencesManager.saveSourceLanguage(languageCode)
    }

    fun updateTranslationLanguages(sourceLanguage: String, targetLanguage: String) {
        preferencesManager.sourceLanguage = sourceLanguage
        preferencesManager.targetLanguage = targetLanguage

        // Notify service of changes if active
        if (translationActive.value == true) {
            val intent = Intent("com.example.ocr_translation.ACTION_UPDATE_TRANSLATION_SETTINGS")
            intent.putExtra("sourceLanguage", sourceLanguage)
            intent.putExtra("targetLanguage", targetLanguage)
            getApplication<Application>().sendBroadcast(intent)
        }
    }
}