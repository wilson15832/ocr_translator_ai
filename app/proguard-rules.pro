# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep line numbers for crash reports; rename SourceFile to keep the original file name hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Kotlin / Coroutines ---
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }

# --- Gson model classes (TranslatedBlock, request/response DTOs in *Client.kt) ---
# Keep anything serialized to/from JSON: data classes' fields, generic signatures and SerializedName.
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.example.ocr_translation.TranslationService$TranslatedBlock { *; }
-keep class com.example.ocr_translation.TranslationService$TranslationConfig { *; }
-keep class com.example.ocr_translation.GeminiClient$* { *; }
-keep class com.example.ocr_translation.OpenAiCompatibleClient$* { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Room (entities + DAOs + generated _Impl) ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep class com.example.ocr_translation.AppDatabase { *; }
-keep class com.example.ocr_translation.TranslationCacheEntity { *; }
-keep interface com.example.ocr_translation.TranslationCacheDao { *; }
-dontwarn androidx.room.paging.**

# --- OkHttp / Okio (mostly self-contained but suppress noisy notes) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- ML Kit (text recognition) ---
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**

# --- Compose / AndroidX security-crypto ---
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# --- App services / receivers must be reachable by name from the manifest ---
-keep public class com.example.ocr_translation.ScreenCaptureService
-keep public class com.example.ocr_translation.OverlayService
-keep public class com.example.ocr_translation.AccessibilityTextService
-keep public class com.example.ocr_translation.MainActivity
-keep public class com.example.ocr_translation.SettingsActivity