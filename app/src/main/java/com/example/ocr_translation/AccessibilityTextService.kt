package com.example.ocr_translation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional "enhanced OCR" source: reads on-screen text straight from the foreground app's
 * accessibility node tree instead of running ML Kit OCR on a screenshot.
 *
 * For apps that expose their text to accessibility (chat apps, browsers, native UIs) this is
 * exact — no OCR errors. It does NOT help for games/engines that draw text onto a canvas or
 * GL surface (e.g. FGO): those have no text nodes, so [getScreenText] returns nothing and the
 * caller should fall back to screen-capture OCR.
 */
class AccessibilityTextService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility text service connected")
    }

    // We pull text on demand (rootInActiveWindow), so we don't need to react to events.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
        Log.d(TAG, "Accessibility text service destroyed")
    }

    /** Walks the active window and collects every visible, non-blank text node with its screen bounds. */
    private fun collectText(): List<OCRProcessor.TextBlock> {
        val root: AccessibilityNodeInfo = try {
            rootInActiveWindow ?: return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "rootInActiveWindow failed", e)
            return emptyList()
        }
        val out = ArrayList<OCRProcessor.TextBlock>()
        try {
            traverse(root, out)
        } catch (e: Exception) {
            Log.e(TAG, "Node traversal failed", e)
        } finally {
            @Suppress("DEPRECATION") try { root.recycle() } catch (_: Exception) {}
        }
        return out
    }

    private fun traverse(node: AccessibilityNodeInfo?, out: MutableList<OCRProcessor.TextBlock>) {
        if (node == null) return
        val raw = node.text ?: node.contentDescription
        val text = raw?.toString()?.trim()
        if (!text.isNullOrEmpty() && node.isVisibleToUser) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                out.add(OCRProcessor.TextBlock(text = text, boundingBox = r, confidence = 1f))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverse(child, out)
            @Suppress("DEPRECATION") try { child?.recycle() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "AccessibilityText"

        @Volatile
        private var instance: AccessibilityTextService? = null

        /** True when the user has enabled the accessibility service and it is bound. */
        fun isConnected(): Boolean = instance != null

        /** On-demand screen text (empty if disabled, or if the foreground app exposes no text nodes). */
        fun getScreenText(): List<OCRProcessor.TextBlock> = instance?.collectText() ?: emptyList()
    }
}
