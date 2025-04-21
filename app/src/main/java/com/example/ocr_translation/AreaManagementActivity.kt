package com.example.ocr_translation

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.ocr_translation.R

class AreaManagementActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_area_management)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, AreaManagementFragment())
                .commit()
        }
    }
}
