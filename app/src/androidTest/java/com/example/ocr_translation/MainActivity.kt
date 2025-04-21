/*
 * File: MainActivity.kt
 * Description: OCR + AI 翻译 Android 应用主入口
 */

package com.example.ocrtranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.ocrtranslator.OCRProcessor
import com.example.ocrtranslator.translate.Translator

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ActivityCompat.requestPermissions(this, arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
        ), 0)

        setContent {
            OCRTranslateApp()
        }
    }
}

@Composable
fun OCRTranslateApp() {
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var extractedText by remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            imageUri = it
            bitmap = MediaStore.Images.Media.getBitmap(LocalContext.current.contentResolver, it)
            bitmap?.let { bmp ->
                extractedText = OCRProcessor.process(LocalContext.current, bmp)
            }
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Button(onClick = { launcher.launch("image/*") }) {
            Text("选择图片进行OCR")
        }

        Spacer(modifier = Modifier.height(16.dp))

        bitmap?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.height(200.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("提取文本:")
        Text(extractedText, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            translatedText = Translator.translate(extractedText)
        }) {
            Text("翻译")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("翻译结果:")
        Text(translatedText, modifier = Modifier.fillMaxWidth())
    }
} // Main UI
