package com.example.ocr_translation

import android.util.Log

/**
 * 体感延迟打点：OCR 开始 → 译文显示，拆成 ocr / net / render 三段。
 * 进程内单实例；假设翻译周期基本串行（auto 模式多次 OCR 时 t0 取最后一次 OCR）。
 * 仅用于测量，定稿后可整体移除。读 Logcat 过滤 tag "PerfTrace"。
 */
object PerfTrace {
    private const val TAG = "PerfTrace"
    private var t0 = 0L
    private var ocrMs = 0L
    private var netT0 = 0L
    private var netMs = 0L
    private var pending = false
    private fun now() = System.currentTimeMillis()

    fun ocrStart() { if (!BuildConfig.DEBUG) return; t0 = now(); pending = false }
    fun ocrDone()  { if (!BuildConfig.DEBUG) return; if (t0 != 0L) ocrMs = now() - t0 }
    fun netStart() { if (!BuildConfig.DEBUG) return; netT0 = now() }
    fun netDone()  { if (!BuildConfig.DEBUG) return; if (netT0 != 0L) netMs = now() - netT0 }
    /** 一条非空译文即将送显——标记本周期需计时。 */
    fun resultPending() { if (!BuildConfig.DEBUG) return; pending = true }

    /** 在 updateOverlays 入口调用：结果落屏时刻。 */
    fun displayed() {
        if (!BuildConfig.DEBUG) return
        if (!pending || t0 == 0L) return
        pending = false
        val total = now() - t0
        val render = (total - ocrMs - netMs).coerceAtLeast(0)
        Log.d(TAG, "perceived: ocr=$ocrMs net=$netMs render=$render total=${total}ms")
        PerceivedStats.record(total, ocrMs, netMs, render)
        t0 = 0L
    }
}

object PerceivedStats {
    private const val TAG = "PerfTrace"
    private val total = ArrayList<Long>(); private val ocr = ArrayList<Long>()
    private val net = ArrayList<Long>();   private val render = ArrayList<Long>()

    @Synchronized fun record(t: Long, o: Long, n: Long, r: Long) {
        total.add(t); ocr.add(o); net.add(n); render.add(r)
        Log.d(TAG, "n=${total.size} | total ${s(total)} | ocr ${s(ocr)} | net ${s(net)} | render ${s(render)}")
    }
    private fun s(xs: List<Long>): String {
        if (xs.isEmpty()) return "-"
        val v = xs.sorted(); fun p(q: Double) = v[((v.size - 1) * q).toInt()]
        return "p50=${p(0.5)} p90=${p(0.9)} max=${v.last()}ms"
    }
    @Synchronized fun reset() { total.clear(); ocr.clear(); net.clear(); render.clear() }
}