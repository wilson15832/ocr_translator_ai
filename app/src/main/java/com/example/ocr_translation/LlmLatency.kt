package com.example.ocr_translation

import android.util.Log
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

private const val TAG = "LlmLatency"

/**
 * 逐次 LLM 请求分阶段计时 + 字节量。挂在共享 OkHttpClient 上。
 * 用 Factory 保证每 call 一个新实例（单实例会被并发请求互相覆盖字段）。
 * 仅 POST（真实翻译）计入分布；HEAD（预热）只打日志、不计入。
 */
class LlmLatencyListener : EventListener() {
    private var callStart = 0L
    private var dnsStart = 0L;     private var dns = 0L
    private var connectStart = 0L; private var connect = 0L
    private var tlsStart = 0L;     private var tls = 0L
    private var reqHeadersEnd = 0L
    private var ttfb = 0L
    private var reused = false
    private var reqBytes = 0L
    private var respBytes = 0L

    private fun now() = System.currentTimeMillis()

    override fun callStart(call: Call) { callStart = now() }
    override fun dnsStart(call: Call, domainName: String) { dnsStart = now() }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dns = now() - dnsStart }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { connectStart = now() }
    override fun secureConnectStart(call: Call) { tlsStart = now() }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { tls = now() - tlsStart }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { connect = now() - connectStart }
    override fun connectionAcquired(call: Call, connection: Connection) { reused = (connectStart == 0L) }
    override fun requestHeadersEnd(call: Call, request: Request) { reqHeadersEnd = now() }
    override fun requestBodyEnd(call: Call, byteCount: Long) { reqBytes = byteCount }
    override fun responseHeadersStart(call: Call) { ttfb = now() - reqHeadersEnd }
    override fun responseBodyEnd(call: Call, byteCount: Long) { respBytes = byteCount }

    override fun callEnd(call: Call) {
        val total = now() - callStart
        val method = call.request().method
        Log.d(TAG, "$method ${if (reused) "REUSED" else "NEW"} " +
                "dns=$dns tls=$tls connect=$connect ttfb=$ttfb total=${total}ms " +
                "req=${reqBytes}B resp=${respBytes}B")
        if (method == "POST") LatencyStats.record(total, ttfb, respBytes)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        Log.d(TAG, "${call.request().method} FAILED after ${now() - callStart}ms: ${ioe.message}")
    }
}

/** 进程内累计分布，每次翻译后打印。读 Logcat 过滤 tag "LlmLatency"。 */
object LatencyStats {
    private val totals = ArrayList<Long>()
    private val ttfbs = ArrayList<Long>()
    private val genMsPerKB = ArrayList<Long>()   // ttfb 归一到每 KB 输出 = 生成速度倒数

    @Synchronized fun record(total: Long, ttfb: Long, respBytes: Long) {
        totals.add(total); ttfbs.add(ttfb)
        if (respBytes > 0) genMsPerKB.add(ttfb * 1024 / respBytes)
        Log.d(TAG, "n=${totals.size} | total ${s(totals)} | ttfb ${s(ttfbs)} | gen ${s(genMsPerKB)}/KB")
    }

    private fun s(xs: List<Long>): String {
        if (xs.isEmpty()) return "-"
        val v = xs.sorted(); fun p(q: Double) = v[((v.size - 1) * q).toInt()]
        return "p50=${p(0.5)} p90=${p(0.9)} max=${v.last()}ms"
    }

    @Synchronized fun reset() { totals.clear(); ttfbs.clear(); genMsPerKB.clear() }
}

/** 累计 token 消耗（输入/输出/推理）。读 Logcat 过滤 tag "TokenStats"。仅 dev 用。 */
object TokenStats {
    private const val TAG = "TokenStats"
    private var calls = 0
    private var promptSum = 0L
    private var completionSum = 0L
    private var reasoningSum = 0L

    @Synchronized fun record(model: String, prompt: Int, completion: Int, reasoning: Int) {
        if (!BuildConfig.DEBUG) return
        calls++
        promptSum += prompt
        completionSum += completion
        reasoningSum += reasoning
        val reasonPct = if (completion > 0) reasoning * 100 / completion else 0
        Log.d(TAG, "$model | in=$prompt out=$completion (reasoning=$reasoning ${reasonPct}%) " +
                "| 累计 n=$calls in=$promptSum out=$completionSum reasoning=$reasoningSum")
    }

    @Synchronized fun reset() { calls = 0; promptSum = 0; completionSum = 0; reasoningSum = 0 }
}