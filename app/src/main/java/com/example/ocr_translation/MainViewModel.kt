package com.example.ocr_translation

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.ocr_translation.PreferencesManager
import android.util.Log

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager.getInstance(application)
    private val _translationActive = MutableLiveData<Boolean>()
    val translationActive: LiveData<Boolean> = _translationActive

    // Store media projection data
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionData: Intent? = null

    init {
        // Check if translation service was running before app was closed
        _translationActive.value = preferencesManager.isTranslationActive()
    }

    fun setTranslationActive(active: Boolean) {
        Log.d("MainViewModel", "Setting translation active: $active")
        _translationActive.value = active
        preferencesManager.setTranslationActive(active)
    }

    fun saveMediaProjectionData(resultCode: Int, data: Intent) {
        mediaProjectionResultCode = resultCode
        mediaProjectionData = data

        // Store in preferences if you have a method for this
        // If you don't have saveMediaProjectionResultCode, you might use:
        preferencesManager.saveMediaProjectionData(resultCode, data)
    }

    fun getMediaProjectionResultCode(): Int {
        return mediaProjectionResultCode
    }

    fun getMediaProjectionData(): Intent? {
        return mediaProjectionData
    }

    val selectedSourceLanguage: String
        get() = preferencesManager.sourceLanguage // 直接读取属性

    val selectedTargetLanguage: String
        get() = preferencesManager.targetLanguage // 直接读取属性

    fun startTranslationService(resultCode: Int, data: Intent) {
        // Save projection data
        saveMediaProjectionData(resultCode, data)

        // Start overlay service
        val overlayIntent = Intent(getApplication(), OverlayService::class.java)
        getApplication<Application>().startService(overlayIntent)

        // Start capture service
        val captureIntent = Intent(getApplication(), ScreenCaptureService::class.java)
        captureIntent.putExtra("resultCode", resultCode)
        captureIntent.putExtra("data", data)
        getApplication<Application>().startService(captureIntent)

        // Update state
        setTranslationActive(true)
    }

    // Add this method to your ViewModel class
    fun updateSourceLanguage(languageCode: String) {
        // Get the translation service
        val translationService = TranslationService.getInstance(getApplication())

        // Update the source language in the translation service
        translationService.setSourceLanguage(languageCode)

        // Save to preferences if needed
        val preferencesManager = PreferencesManager.getInstance(getApplication())
        preferencesManager.saveSourceLanguage(languageCode)

        // If you have a LiveData for source language, update it
        // _sourceLanguage.value = languageCode
    }

    fun stopTranslationService() {
        // Stop services
        getApplication<Application>().stopService(Intent(getApplication(), OverlayService::class.java))
        getApplication<Application>().stopService(Intent(getApplication(), ScreenCaptureService::class.java))

        // Update state
        setTranslationActive(false)
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

    fun hasValidMediaProjection(): Boolean {
        return mediaProjectionData != null && mediaProjectionResultCode != 0
    }
}