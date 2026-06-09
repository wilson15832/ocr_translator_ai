# Screen Translator — 架构与调用流程总览

文档基于 `wilson15832/ocr_translator_ai` 主分支（commit `13ad0c1`）整理，覆盖现有代码结构、模块职责、跨组件通信和典型调用链路。Mermaid 图块在 GitHub、VS Code (Markdown Preview Mermaid Support 插件)、Obsidian 均可直接渲染。

> 本次修订（2026-06-09）相对上一版的主要更正：
> - §8.1 LLM 路由：endpoint 实际硬编码在 `TranslationService` 内，与偏好里的 `llmApiEndpoint` 无关
> - §11 Room：实际有 **3 张表**（含一张未使用的 `recent_translations`），且 `translation_cache` 译文是单列 JSON
> - §6 手动模式：旧的 `hideForCapture/showAfterCapture` 已被 `clearShownTranslation()` 取代
> - §7 通信清单：补 `ACTION_CLEAR_TRANSLATION_AREA`、补 `clearShownTranslation()`、补 `autoMode` 静态状态，并标出 `ACTION_UPDATE_TRANSLATION_SETTINGS` 是无接收方的死广播
> - §9.1 Prompt：占位符其实是 `{source}` / `{target}` / `{text}` 三个

---

## 1. 模块清单

| 文件 | 行数 | 职责 |
|---|---:|---|
| `MainActivity.kt` | 356 | App 入口；语言选择；权限请求；启动/停止后台服务 |
| `MainViewModel.kt` | 50 | UI 状态；发广播 `ACTION_UPDATE_TRANSLATION_SETTINGS`（⚠ 当前无接收方，见 §7） |
| `SettingsActivity.kt` | 579 | 所有可调参数 UI（model、prompt、颜色、字号、缓存、区域…） |
| `PreferencesManager.kt` | 366 | `SharedPreferences` 包装层；单例；存读取所有偏好 |
| `SecureStorage.kt` | 59 | `EncryptedSharedPreferences` 封装；只用于 API key |
| `PermissionHelper.kt` | 381 | Overlay / 通知 / Accessibility / MediaProjection 权限编排 |
| **`ScreenCaptureService.kt`** | **1276** | **核心控制器**：截屏、调度、状态机（scanning/steady）、手动/自动请求分发 |
| **`OverlayService.kt`** | **1574** | 浮动控制条 + 翻译结果浮层（merged / in-place 两种渲染）+ 区域选择 |
| `TranslationPipeline.kt` | 258 | 把"截屏 → OCR → 翻译 → 结果"拼起来；处理 bitmap 回收 |
| `TranslationService.kt` | 254 | 构造 LLM 请求、缓存、解析响应 |
| `OCRProcessor.kt` | 100 | ML Kit TextRecognizer 包装（**Kotlin `object` 单例**，中/日/韩/拉丁/天城文） |
| `AccessibilityTextService.kt` | 91 | 用 AccessibilityNodeInfo 直接抓文字（对游戏无效，仅原生 App） |
| `ChangeDetector.kt` | 52 | Levenshtein 文本相似度，决定"画面是否变化" |
| `TranslationCache.kt` | 81 | Room DB 翻译缓存（SHA-256 key） |
| `AppDatabase.kt` | 164 | Room 数据库；含 3 个 entity + 3 个 DAO（见 §11） |
| `TranslationArea.kt` + `TranslationAreaDao.kt` | 50 | Room 实体 + DAO（区域表） |
| `LlmClient.kt` | 5 | 接口：`translate(systemPrompt, userPrompt) -> String` |
| `GeminiClient.kt` | 58 | Gemini REST 实现 |
| `OpenAiCompatibleClient.kt` | 59 | OpenAI / DeepSeek 等兼容端点实现 |
| `GestureControlPanel.kt` | 100 | 自定义 ViewGroup，支持长按拖动整个控制条 |
| `AreaSelectionOverlay.kt` | 83 | 框选 OCR 区域用的全屏 View |

---

## 2. 高层组件关系

```mermaid
flowchart TB
    classDef ui        fill:#E3F2FD,stroke:#1976D2,stroke-width:1.5px,color:#0D47A1
    classDef service   fill:#FFF3E0,stroke:#EF6C00,stroke-width:2px,color:#E65100
    classDef pipeline  fill:#E8F5E9,stroke:#2E7D32,stroke-width:1.5px,color:#1B5E20
    classDef llm       fill:#F3E5F5,stroke:#6A1B9A,stroke-width:1.5px,color:#4A148C
    classDef storage   fill:#ECEFF1,stroke:#455A64,stroke-width:1.5px,color:#263238
    classDef star      fill:#FFE082,stroke:#EF6C00,stroke-width:2.5px,color:#BF360C

    subgraph UI["🖥️ UI 层 (Activity)"]
        direction LR
        MA["MainActivity<br/>语言 / 启动按钮"]:::ui
        SA["SettingsActivity<br/>所有可调设置"]:::ui
        MVM["MainViewModel"]:::ui
    end

    subgraph Services["⚙️ 后台服务 (Foreground)"]
        direction LR
        SCS["★ ScreenCaptureService<br/><i>核心控制器</i>"]:::star
        OS["★ OverlayService<br/><i>浮层 UI</i>"]:::star
        ATS["AccessibilityTextService<br/><i>(可选, 原生 app)</i>"]:::service
    end

    subgraph Pipeline["🔄 翻译流水线"]
        direction LR
        TP["TranslationPipeline"]:::pipeline
        TS["TranslationService"]:::pipeline
        OCR["OCRProcessor<br/>(ML Kit)"]:::pipeline
        CD["ChangeDetector"]:::pipeline
        TC["TranslationCache<br/>(Room)"]:::pipeline
    end

    subgraph LLM["🤖 LLM 客户端 (策略模式)"]
        direction LR
        LC{{"LlmClient<br/>interface"}}:::llm
        GC["GeminiClient"]:::llm
        OC["OpenAiCompatibleClient<br/>(OpenAI / DeepSeek)"]:::llm
    end

    subgraph Storage["💾 持久化"]
        direction LR
        PM["PreferencesManager<br/>(SharedPreferences)"]:::storage
        SS["SecureStorage<br/>(Encrypted, API key)"]:::storage
        DB["AppDatabase<br/>(Room, 3 tables)"]:::storage
    end

    MA --> MVM
    MA -. startService .-> SCS
    MA -. startService .-> OS
    MA --> PM
    SA --> PM
    SA --> SS

    SCS -- captureScreen --> TP
    SCS -- showTranslation --> OS
    OS -- click btnTranslate --> SCS
    ATS -- getScreenText --> SCS

    TP --> OCR
    TP --> CD
    TP --> TS
    TS --> TC
    TS --> LC
    LC --> GC
    LC --> OC

    TS --> PM
    PM --> SS
    TC --> DB
```

---

## 3. 启动与权限流程

```mermaid
sequenceDiagram
    autonumber
    actor U as 👤 User
    participant MA as MainActivity
    participant PH as PermissionHelper
    participant SCS as ScreenCaptureService
    participant OS as OverlayService

    U->>MA: Tap START TRANSLATION
    MA->>PH: areAllPermissionsGranted()?
    alt 缺少权限
        PH->>U: 弹 SYSTEM_ALERT_WINDOW / POST_NOTIFICATIONS / Accessibility 提示
        U->>PH: 授权
    end
    MA->>U: 弹系统 MediaProjection 同意对话框
    U->>MA: 同意 (onActivityResult)

    par 启动两个前台服务
        MA->>SCS: startForegroundService<br/>extras: resultCode + data
        and
        MA->>OS: startForegroundService
    end

    SCS->>SCS: initializeImageReader + startCapture()
    SCS->>SCS: startPeriodicCapture()  (auto loop)
    OS->>OS: createControlPanelWindow + createTranslationOverlay

    Note over SCS,OS: 两个服务通过 companion object 上的<br/>静态方法 + Broadcast 互相通信
```

---

## 4. ScreenCaptureService 状态机

`ScreenCaptureService` 是一个**双状态**主循环：

```mermaid
stateDiagram-v2
    direction LR
    [*] --> SCANNING: service start

    SCANNING --> SCANNING: poll every SCAN_POLL_MS (250ms)<br/>OCR signature 未变 / 无文字
    SCANNING --> TRANSLATING: 文本稳定 ≥ STABLE_TEXT_MS<br/>或连续 STABLE_FRAMES 帧匹配
    TRANSLATING --> STEADY: showTranslation 已渲染
    STEADY --> STEADY: 指纹差异 < STEADY_CHANGE_RATIO<br/>或仍在 STEADY_GRACE_MS 内
    STEADY --> SCANNING: 帧显著变化<br/>/ 用户输入<br/>/ 手动请求

    note right of SCANNING
      入口:
      • mergedAutoStep   (非 in-place)
      • inPlaceAutoStep  (in-place)
    end note

    note right of STEADY
      box 已显示，避免反复重译
      • lastShownResults  → 当前译文
      • lastOcrFingerprint → 当时指纹
    end note
```

### 关键调谐常数（`ScreenCaptureService.kt` 顶部 companion）

```kotlin
STABLE_FRAMES       = 2       // 合并模式：连续稳定帧数
STABLE_TEXT_MS      = 400     // in-place：OCR 文本必须保持此时长
STEADY_CHANGE_RATIO = 0.30    // 进入 steady 后，认为画面真变了的指纹差异阈值
STEADY_GRACE_MS     = 1000    // 显示框后这段时间内不做帧差判断
SCAN_POLL_MS        = 250     // 主循环 tick（scanning 状态下的高频轮询）
POST_TAP_WAIT_MS    = 4000    // 用户点了屏幕后等多久才允许从缓存恢复译文
```

---

## 5. 自动模式：一次翻译循环（in-place）

```mermaid
sequenceDiagram
    autonumber
    participant Loop as startPeriodicCapture<br/>(coroutine, while(running))
    participant SCS as ScreenCaptureService
    participant TP as TranslationPipeline
    participant OCR as OCRProcessor
    participant CD as ChangeDetector
    participant TS as TranslationService
    participant TC as TranslationCache
    participant LLM as LlmClient
    participant OS as OverlayService

    loop every SCAN_POLL_MS
        Loop->>SCS: inPlaceAutoStep()
        SCS->>SCS: captureScreen() → Bitmap
        SCS->>TP: ocrSignature(frame, area)
        TP->>OCR: recognize(cropped)
        OCR-->>TP: List<TextBlock>
        TP-->>SCS: signature string

        alt signature 未变
            SCS-->>LoopState: continue (stay in steady)
        else signature 变了 且 ≥ STABLE_TEXT_MS
            SCS->>SCS: handleStableScan()
            SCS->>TP: ocrFrame(frame) → OcrSnapshot
            SCS->>TP: translateSnapshot(snap, src, tgt, force=false)
            TP->>CD: hasChanged(blocks)?

            alt 文本几乎没变
                CD-->>TP: false → 跳过 LLM
            else 真的变了
                TP->>TS: translateText(blocks, src, tgt)
                TS->>TC: getTranslation(cacheKey)
                alt cache hit
                    TC-->>TS: List&lt;TranslatedBlock&gt;
                else cache miss
                    TS->>LLM: translate(systemPrompt, userPrompt)
                    LLM-->>TS: raw response string
                    TS->>TS: parseTranslationResult()
                    TS->>TC: saveTranslation()
                end
                TS-->>TP: List&lt;TranslatedBlock&gt;
            end

            TP->>TP: sampleBackgroundColor (TP 内方法)<br/>每块取背景色
            TP-->>SCS: onResult(finalResults) 回调
            SCS->>OS: showTranslation(results)
            SCS->>SCS: enterSteady()
        end
    end
```

---

## 6. 手动模式（短按 / 长按 manual icon）

> ⚠️ 本节相对旧版已更新：实际代码已经用 `clearShownTranslation()` 取代了原来的 `hideForCapture/showAfterCapture` 闪回路径；"重试同一画面"也是用**新 OCR 出的 blocks**走 LLM，不是直接复用 `lastShownResults.originalText`。

```mermaid
sequenceDiagram
    autonumber
    actor U as 👤 User
    participant OS as OverlayService<br/>btnTranslate
    participant SCS as ScreenCaptureService
    participant TP as TranslationPipeline
    participant TS as TranslationService

    alt 短按 (click)
        U->>OS: click
        OS->>SCS: requestManualTranslation(AUTO)
    else 长按 (long click)
        U->>OS: long press
        OS->>SCS: requestManualTranslation(FORCE_OCR)
    end

    Note over SCS: 主循环 consumeManualRequest() 取出 kind<br/>→ handleManualRequest(kind, useA11y, prefs)

    alt 启用了 useAccessibility
        SCS->>SCS: accessibilityTranslate(force=true)
        Note right of SCS: 用 AccessibilityNodeInfo 抓文字<br/>对游戏无效
    else 正常截屏路径
        opt hadShown (lastShownResults 非空)
            SCS->>OS: clearShownTranslation()<br/>(GONE + removeAllViews)
            SCS->>SCS: lastShownResults = emptyList()
            SCS->>SCS: delay(120ms)
        end
        SCS->>SCS: captureScreen()
        SCS->>SCS: fingerprint(frame) → nowFp

        alt kind == AUTO 且 fingerprintsSimilar(nowFp, lastOcrFingerprint)
            Note over SCS,TP: "同一张画面 → 重试"<br/>仍重新 OCR，但用 bypassCache=true 强制 LLM
            SCS->>TP: ocrFrame(frame) → snap
            SCS->>TP: processBlocks(snap.blocks,<br/>force=true, bypassCache=true)
        else 画面不同 或 FORCE_OCR
            SCS->>TP: ocrFrame(frame) → snap
            SCS->>TP: translateSnapshot(snap, force=true)
        end

        TP->>TS: translateText(...)
        TS-->>TP: results
        TP-->>SCS: onResult 回调
        SCS->>OS: showTranslation(results)
    end
```

> **已知 / 待解决 bug**：
> 1. 指纹判同分辨率太低（32×18 cell, 60 RGB 阈, 10% cell），新对话框常被误判为旧 → 短按不更新译文，必须长按。**根治需要改成 OCR 文本对比，而不是像素指纹**（见 §12 #1）。
> 2. ~~`hideForCapture()/showAfterCapture()` 闪回旧 box~~ **已修复**：手动路径改用 `clearShownTranslation()` + `delay(120)`，新结果到来时由 `showTranslation` 重新渲染。
> 3. in-place 模式宽对话框排版易乱（box 宽度 = OCR rect + 8），见 §12 #3。

---

## 7. 跨组件通信清单

服务之间不是直接持有对方引用，而是混用了**三种机制**——这是整个项目最容易出 bug 的地方。

```mermaid
flowchart LR
    classDef ok    fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20
    classDef warn  fill:#FFEBEE,stroke:#C62828,color:#B71C1C
    classDef bcast fill:#E1F5FE,stroke:#0277BD,color:#01579B

    subgraph A["SCS → OS (静态方法)"]
      direction TB
      A1["OverlayService.showTranslation(results)<br/><i>LiveData 背后</i>"]:::ok
      A2["OverlayService.hideForCapture()<br/>OverlayService.showAfterCapture()<br/><i>遗留 API，自动路径仍在用</i>"]:::ok
      A3["OverlayService.clearShownTranslation()<br/><i>手动路径用于清空旧 box</i>"]:::ok
    end

    subgraph B["OS → SCS (静态方法 + 字段)"]
      direction TB
      B1["ScreenCaptureService.requestManualTranslation(kind)<br/><i>写 volatile manualRequest</i>"]:::ok
      B2["ScreenCaptureService.onUserInput()<br/><i>sessionId++ 让在途翻译过期</i>"]:::ok
      B3["ScreenCaptureService.autoMode  (字段)<br/><i>OS 直接读写切换自动/手动模式</i>"]:::ok
    end

    subgraph C["Broadcast (隐式)"]
      direction TB
      C1["ACTION_UPDATE_OVERLAY_SETTINGS<br/>(SettingsActivity → OS)"]:::bcast
      C2["ACTION_ROTATION_CHANGED<br/>(SCS → OS, 重新放置控制条)"]:::bcast
      C3["ACTION_AREA_STATUS_UPDATE<br/>(SCS → OS, 更新角标)"]:::bcast
      C4["ACTION_SET_TRANSLATION_AREA<br/>(OS 框选后 → SCS)"]:::bcast
      C5["ACTION_CLEAR_TRANSLATION_AREA<br/>(OS → SCS, 清空区域)"]:::bcast
      C6["⚠ ACTION_UPDATE_TRANSLATION_SETTINGS<br/>(MainActivity/VM 发，无接收方 — 死广播)"]:::warn
    end
```

### 7.1 ScreenCaptureService companion-level 静态状态

```kotlin
@Volatile var sessionId: Int = 0           // 每次"屏幕变了/用户输入"++，丢弃过期翻译结果
@Volatile var autoMode: Boolean = false    // OS 控制条直接翻转此字段切换模式
@Volatile private var manualRequest: ManualKind? = null
@Volatile private var userInputPending: Boolean = false
```

`sessionId` 是为了：翻译异步进行时，如果中途屏幕变了，等结果回来时 `sessionId` 已经变了，回调里发现就丢弃这个结果，避免显示过期译文。

### 7.2 ScreenCaptureService instance 状态

```kotlin
private var lastShownResults: List<TranslatedBlock>  // 当前显示的译文
@Volatile private var lastOcrFingerprint: IntArray?  // 上次成功翻译时的画面指纹
@Volatile private var pendingOcrFingerprint: IntArray?  // 翻译进行中的指纹，成功后提升为 last
```

### 7.3 关于 `ACTION_UPDATE_TRANSLATION_SETTINGS`

`MainActivity` 和 `MainViewModel` 在改完源/目标语言时都会 `sendBroadcast` 这条 action，但**全工程没有任何 `BroadcastReceiver` 注册它**。意思是：

- UI 改语言确实把值写进了 `PreferencesManager`
- 服务下一次进入主循环 reload prefs 时会读到新值（事实上是这条路径生效）
- 这条广播本身**没有任何效果**——属于历史残留，要么实现接收端，要么直接删掉避免误导。

---

## 8. 外部 API

### 8.1 LLM 接口（统一抽象）

```kotlin
interface LlmClient {
    suspend fun translate(systemPrompt: String, userPrompt: String): String
}
```

`TranslationService.createLlmClient()` 根据 `modelName` 字段路由（注意**endpoint 全部硬编码**在该方法内，与偏好里的 `llmApiEndpoint` 字段无关——后者目前在 Settings UI 仍可见但运行时未读取，见 `PreferencesManager.kt:120-127` 的注释）：

| 条件 (`model.startsWith(...)`) | 实现 | Endpoint（硬编码） |
|---|---|---|
| `"deepseek"` | `OpenAiCompatibleClient` | `https://api.deepseek.com/chat/completions` |
| `"gpt"` | `OpenAiCompatibleClient` | `https://api.openai.com/v1/chat/completions` |
| 其他（默认 `gemini-*`） | `GeminiClient` | `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` |

请求超时（`TranslationService.kt:47-53`）：
- `connectTimeout = 10s`
- `readTimeout = 30s`
- `writeTimeout = 15s`
- `callTimeout = 40s` ← 整体上限

### 8.2 ML Kit OCR

`OCRProcessor` 是 **Kotlin `object`（顶层单例）**，全应用共享同一个 recognizer。`Mutex` 保护 recognize / setLanguage / cleanup，避免并发关闭崩溃。

| 语言 code | Recognizer |
|---|---|
| `zh` | `ChineseTextRecognizerOptions` |
| `ja` | `JapaneseTextRecognizerOptions` (默认值) |
| `ko` | `KoreanTextRecognizerOptions` |
| `hi` | `DevanagariTextRecognizerOptions` |
| 其他 | `TextRecognizerOptions.DEFAULT_OPTIONS` (拉丁) |

注意事项：
- `TextBlock.confidence` 字段恒为 `1f`（ML Kit 不暴露 per-block 置信度，accessibility 路径也照搬 1f）。读者**不要**把它当真实置信度用。
- `setScreenRotation()` 会更新 `currentRotation`，但 `recognize()` 实际用 `InputImage.fromBitmap(bitmap, 0)`——rotation 字段当前保留未用（因为 MediaProjection 镜像已经是正向的）。

### 8.3 MediaProjection 截屏

```
MediaProjectionManager.getMediaProjection(resultCode, data)
  → createVirtualDisplay(... ImageReader.surface ...)
  → ImageReader.acquireLatestImage()
  → Image.planes[0].buffer → Bitmap
```

旋转处理在 `updateCaptureWithRotation()` —— 旋转时重建 VirtualDisplay。

---

## 9. Prompt 与缓存

### 9.1 Prompt 模板（默认值在 `PreferencesManager`）

```
[system]
You are a professional translator. Translate naturally and accurately,
preserving tone and formatting.

[user]
Translate the following text from {source} to {target}.
Maintain the original formatting and layout as much as possible.
Keep the BLOCK_XXX: prefixes in the output but don't translate them.

Text to translate:
{text}

Translation:
```

`createTranslationPrompt` 会替换 **三个**占位符：

| 占位符 | 替换为 |
|---|---|
| `{source}` | 源语言代码 |
| `{target}` | 目标语言代码 |
| `{text}` | 由各 block 拼接成的 `BLOCK_<hashCode>: <ocr text>` 多行字符串 |

`BLOCK_${boundingBox.hashCode()}:` 用 `android.graphics.Rect` 的 hashCode 作为 ID；LLM 返回后 `parseTranslationResult` 按 `BLOCK_xxx:` 切回去并按 hashCode 找回 originalBlock。

⚠️ 自定义 prompt 时**必须保留 `{text}` 占位符**，否则 OCR 内容根本不会被拼进去。

### 9.2 缓存键

```kotlin
seed = "<text1|text2|…>|<sourceLanguage>|<targetLanguage>|<modelName>"
cacheKey = SHA-256(seed)  // 输出十六进制小写串
```

切换模型 / 语言会自然 miss，符合预期。命中后**直接返回缓存的 `TranslatedBlock` 列表**，不走 LLM。

`bypassCache=true` 路径（用于"手动重试"）：

1. 仍按 cacheKey 算
2. **跳过查询**直接调 LLM
3. 新结果**覆盖** (`OnConflictStrategy.REPLACE`) 旧缓存

---

## 10. UI 渲染：两种模式

```mermaid
flowchart TB
    classDef start fill:#FFF3E0,stroke:#EF6C00,color:#E65100
    classDef inplace fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20
    classDef merged fill:#E1F5FE,stroke:#0277BD,color:#01579B

    R["TranslatedBlock 列表<br/>到达 OverlayService.showTranslation"]:::start
    R --> Q{"prefs.inPlaceMode?"}
    Q -- "true" --> IP["renderInPlace<br/>每块按 OCR rect 位置<br/>叠一个 TextView<br/>(覆盖在原文上方)"]:::inplace
    Q -- "false" --> MG["合并模式<br/>所有译文拼到 translationOverlay 顶部<br/>统一的浮动框"]:::merged

    IP --> IPO["inPlaceOverlay (FrameLayout)<br/>每块 LayoutParams = rect.width()+8"]:::inplace
    MG --> TO["translationOverlay (LinearLayout)<br/>WRAP_CONTENT"]:::merged
```

两个 overlay 都是 `WindowManager.addView()` 加到系统 window 层（`TYPE_APPLICATION_OVERLAY` on API 26+，`TYPE_PHONE` 之前）。控制条 `controlPanelWindow` 是第三个独立 window。

---

## 11. 数据持久化

```mermaid
flowchart LR
    classDef pref fill:#E3F2FD,stroke:#1976D2,color:#0D47A1
    classDef sec  fill:#FCE4EC,stroke:#C2185B,color:#880E4F
    classDef room fill:#E8F5E9,stroke:#2E7D32,color:#1B5E20
    classDef dead fill:#ECEFF1,stroke:#90A4AE,color:#546E7A,stroke-dasharray: 5 5

    subgraph Pref["SharedPreferences (PreferencesManager)"]
        P1["源/目标语言"]:::pref
        P2["model / endpoint(⚠ 未用)"]:::pref
        P3["systemPrompt / userPrompt"]:::pref
        P4["overlay 字号/颜色/字体"]:::pref
        P5["cacheTtlHours / maxCacheEntries"]:::pref
        P6["activeTranslationArea (RectF)"]:::pref
        P7["inPlaceMode flag"]:::pref
        P8["captureInterval, autoCaptureEnabled"]:::pref
    end

    subgraph Secure["EncryptedSharedPreferences"]
        S1["llm_api_key"]:::sec
    end

    subgraph Room["Room DB (screen_translator_db, v1)"]
        R1["translation_cache<br/>(cacheKey PK, translationJson, timestamp,<br/>sourceLanguage, targetLanguage)"]:::room
        R2["translation_areas<br/>(id, name, left, top, right, bottom)"]:::room
        R3["recent_translations<br/>(id, originalText, translatedText, timestamp,<br/>sourceLanguage, targetLanguage, isFavorite)<br/><i>⚠ 已定义但代码内无任何调用</i>"]:::dead
    end
```

### 11.1 几点说明

- **`translation_cache` 实际只有 5 列**——译文是整段 JSON 字符串塞在 `translationJson` 里，由 `TranslationCache` 序列化/反序列化。
- **`recent_translations` 是预留**——entity、DAO、表都建了，但全工程 `grep` 不到任何调用方。属于历史/收藏功能的占位脚手架。
- **`translation_areas`** 目前业务上只用一个 active area（存在 `PreferencesManager` 里的 `RectF`），表保留供未来扩展多区域。

---

## 12. 当前已识别的优化点

| # | 项 | 当前 | 目标 | 状态 |
|---|---|---|---|---|
| 1 | 手动 AUTO 短按指纹误判 | 32×18 像素指纹 | 改成 OCR 文本对比 | 待办 |
| 2 | ~~手动按下旧 box 闪回~~ | ~~hide → restore 旧 visibility~~ | `clearShownTranslation()`，由新渲染恢复 | ✅ **已完成** |
| 3 | in-place box 宽度截断长译文 | rect.width() + 8 | 1.5× 自适应（窄 1.5 / 宽 1.1）+ 右沿钳制 | 待办 |
| 4 | LLM 延迟 1-25s | 阻塞等完整 response | 流式渲染 + 模型换 flash 档 | 待办 |
| 5 | OCR 引擎单一 | ML Kit only | 抽象 `OcrEngine`，加 PaddleOCR / LLM Vision | 待办 |
| 6 | UI 主题色硬编码 | `@color/primary` | 多 theme 变体 + 圆圈选择器 | 待办 |
| 7 | App 图标固定 | 单 icon | activity-alias + 预置图标 | 待办 |
| 8 | Prompt 冗余 | `BLOCK_<hashCode>` + 三行说明 | 短前缀 `B1`，单块时跳过协议 | 待办 |
| 9 | `max_tokens = 2048` | 偏大 | 400-512 | 待办 |
| 10 | 无连接预热 | 首次请求 TLS 慢 | 服务启动时 HEAD 预热 | 待办 |
| 11 | `ACTION_UPDATE_TRANSLATION_SETTINGS` 是死广播 | 发但无人收 | 删除发送端，或加 SCS 接收端实现热切换 | 新增 |
| 12 | `llmApiEndpoint` 偏好与运行时脱节 | UI 可改但不生效 | 决定是恢复读取还是从 Settings UI 移除 | 新增 |
| 13 | `recent_translations` 表与 DAO 闲置 | dead code | 接入历史/收藏界面，或移除 entity | 新增 |

---

## 13. 阅读路径建议

第一次走代码顺序建议：

1. `MainActivity.onCreate` → `requestScreenCapture` → `startTranslationService`
2. `ScreenCaptureService.onCreate` → `onStartCommand` → `startCapture` → `startPeriodicCapture`
3. 主循环里的 `inPlaceAutoStep` 或 `mergedAutoStep`
4. `handleStableScan` → `TranslationPipeline.ocrFrame` → `translateSnapshot`
5. `TranslationService.translateText` → `LlmClient.translate`
6. 回调 `onResult` → `OverlayService.showTranslation` → `renderInPlace`

手动路径独立看 `handleManualRequest` 即可，会调到第 4-6 步同样的下游。

设置变更路径：`SettingsActivity` 改 prefs → 服务下次进入循环读 prefs 即生效（注意：`ACTION_UPDATE_TRANSLATION_SETTINGS` 这条广播目前没有接收方，详见 §7.3）。

---

*生成日期：2026-06-09*
*基于 commit：`13ad0c1` (main HEAD)*
