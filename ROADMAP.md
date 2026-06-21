# 开发大纲 / ROADMAP

> 框架已基本稳定（2.0）。本文档统筹后续工作，分三块：
> **一、Step 2 收尾**（既有清理/重构的遗留项）→ **二、性能与体验优化** → **三、新功能**。
> 细节与背景见 [ARCHITECTURE.md](ARCHITECTURE.md) §12 / §12.2。状态标记：⬜ 待办 · 🔄 进行中 · ✅ 完成。

---

## 一、Step 2 收尾

第一轮（step 1）只做"删死代码、修明确 bug"。以下是从 step 1 拆出、**有副作用风险 / 需设计 / 需实测**的遗留项。

| # | 项 | 目标 | 性质 / 风险 | 优先级 | 状态 |
|---|---|---|---|---|---|
| 15 | `OCRProcessor.cleanup()` 释放 recognizer | recognizer 改可空 + 惰性重建，cleanup 置 null，再接入 `ScreenCaptureService.onDestroy` | 🟠 生命周期：现为进程级单例 `object`，直接在 onDestroy 调 cleanup 会让"停止→重开"用到已关闭 recognizer → OCR 失效。必须先做"用前判空重建" | 高（资源释放） | ⬜ |
| 23 | 区域通信改静态方法 | SCS 加 `setTranslationArea(rect)`/`clearTranslationArea()` 静态方法（仿 `requestManualTranslation`），OverlayService `sendAreaToCapture` 改调它，删 `onStartCommand` 的 `ACTION_SET/CLEAR` 分支 | 🔵 改运行时通信路径，改错框选就坏，需测"框选→生效" | 中 | ⬜ |
| 12 | `llmApiEndpoint` 偏好脱节 | 决策：**移除 UI**（清理向）或**做成"自定义端点"feature**（需协议选择 + 校验） | 🟡 产品决策，决定后归入"清理"或"新功能" | 中（先决策） | ⬜ |
| 22 | `clearCache()` / `deleteAll()` 接入 UI | 接 Settings 的"清除缓存"按钮（逻辑已完整、零调用） | 🟢 new feature，低风险 | 中 | ⬜ |
| 29 | `START_NOT_STICKY` 真机验证 | 已改返回值，需实测：后台被强杀后不出"无授权僵尸服务" | 🟡 仅缺真机后台强杀实测 | 中 | ⬜（待测） |

**建议顺序**：#15（资源，纯生命周期）→ #29（顺带真机验证）→ #23（通信统一）→ #12 决策 →（若决策为做 feature）并入第三块 → #22。

---

## 二、性能与体验优化

来自 [ARCHITECTURE.md](ARCHITECTURE.md) §12 标"待办"的优化项，按收益/成本归类。

### 2.1 延迟与吞吐（体感最强）
| # | 项 | 目标 | 状态 |
|---|---|---|---|
| 10 | 无连接预热 | 点击"开始翻译"时对当前 provider host 做 HEAD 预热，连接进池、首翻复用 | ✅ 已完成（首翻明显提速） |
| — | 延迟测量 | `PerfTrace`（OCR→显示三段）+ `LlmLatency`（EventListener 拆 net）| ✅ 已完成 |
| 9 | `max_tokens` | 做成 Settings 可调滑条（256–4096，默认 1024），统一 Gemini/OpenAI | ✅ 已完成。**定性：非延迟手段**——多数输出未撞上限，调低只会截断；留作防截断/控成本 |
| 4 | LLM 延迟 | 降低单次翻译耗时 | ✅ 已查清并优化到地板（见 §2.1.1）。真因＝选了推理模型 |
| 8 | Prompt 冗余 | `BLOCK_<hashCode>` → 短标记 `[n]` | ⬜ 暂缓（收益小：延迟主因是推理 token，非输出字节；待换非推理 provider 后再评估） |

> **延迟根因与定论（2026-06-20，DeepSeek `deepseek-v4-flash`）**：经"预热→流式→gzip→prompt"一路排查，真因是**所用模型本身是推理模型**——dump 实测 `usage.reasoning_tokens` 占 `completion_tokens` 的 **82–94%**（翻译「何か?」竟烧 179 个推理 token）。模型出译文前先生成大段思维链（`reasoning_content`，被我们解析时丢弃），这才是"静默 N 秒后突发吐完""响应忽大忽小（1–200KB）""延迟 2–13s"的全部来源。**与网络、流式、gzip、prompt 啰嗦都无关。**
>
> **✅ 已解决（2026-06-20）：关闭推理。** `reasoning_tokens` 实测降到 **0**，输出 token 从 75–340 降到 10–46。延迟 `total` **p50 ≈ 1.0s / p90 ≈ 1.27s / max 1.37s**，**11–13s 长尾彻底消失**（对比推理开时 p50 2.3s / 尾 13s）。`ttfb` ~200ms（网络底，预热稳定）。成本同步解决：**每 1000 次翻译 ≈ $0.06**。
>
> **配套优化(均保留)**：预热（首翻提速）＋ 中文 prompt ＋ 严格"只输出译文"约束。流式已试并回退（见 §2.1.1）。
>
> **结论**：慢与贵同根——推理模型。关掉推理同时解决延迟+成本。剩下的 ~1s = `ttfb`(网络) + 少量真实生成，是此 provider/中国网络的实际地板。要再压只能换更快/更近的非推理 provider，但 ~1s 已可用，优化线收尾。
> **TODO**：~~`TokenStats`（`LlmLatency.kt`）已加，发布前一并清理/门控~~ ✅ 已 `BuildConfig.DEBUG` 门控（见"Release 2.0 收尾"）；确认"关推理"的具体实现（非推理模型 id / 参数）并固化到代码与默认配置。

#### 2.1.1 流式显示（#4）—— 已试并回退
**结论：此路不通，已删回非流式。** 曾实现"边收边解析、块级增量送显"（`LlmClient.translate` 加 `onDelta`、两 client SSE、`translateText` 加 `onPartial`、`updateOverlays` 多次重渲染）。但实测 DeepSeek 对本场景是**假流式**：真译文 token 不逐个到达，而是模型"想完"后在末尾 ~150ms 内突发吐完（前面 N 秒都在生成被丢弃的 `reasoning_content`）。流式期间没有可展示的中间译文 → 体感零改善 → 已回退。
- **唯一保留的副产物**：`OverlayService.showTranslation` 用 `mainHandler.post{ translationData.value=… }` 取代 `postValue`（避免快速连发被合并、投递更即时），无害且更稳，保留。
- 若将来换成**真流式的非推理 provider**，这套增量渲染思路仍可复用。

> **✅ 发布前清理（dev 调试代码）—— 2.0 已完成**：①`/sdcard/.../llm_dump.txt` 落盘日志已删（仅留 `appendTranslationsToFile` 这个用户可选的"保存到文件"功能）；②`PerfTrace` / `LlmLatencyListener`+`LatencyStats`/`TokenStats` + `eventListenerFactory` 全部 `BuildConfig.DEBUG` 门控（代码留在 master，release 由 R8 strip）；③`reasoning_effort` 已按 `model.startsWith("deepseek")` 分流（deepseek→null+`thinking:disabled`，其余→`"minimal"`）。详见末尾"Release 2.0 收尾"。

### 2.2 OCR / 翻译质量 / 渲染
| # | 项 | 目标 | 状态 |
|---|---|---|---|
| — | 振假名注音与正文框重叠 | 汉字上方小字假名被 OCR 成独立 block，统一尺寸渲染后压到正文框上 | ✅ 已做。in-place 渲染按"几何重叠"分组(`groupOverlapping`/`related`)+ 字号按 block 高度缩放;Settings 开关 `mergeOverlapBoxes`:**关**=独立框+丢注音(组内丢矮块),**开**=合并单背景框+保留注音(各行透明叠加不互挡)。`renderInPlace` 拆 `addSeparateBox`/`addMergedBox` |
| 3 | in-place box 宽度截断长译文 | 1.5× 自适应(窄 1.5 / 宽 1.1)+ 右沿钳制 | ✅ 已在 in-place 渲染落地 |
| 1 | 手动 AUTO 短按指纹误判 | 32×18 像素指纹 → 改 OCR 文本对比 | ⬜ 与 #37 已落地的文本对比思路一致 |

### 2.3 已知 Bug / 待修
| # | Bug | 根因 | 修法 | 状态 |
|---|---|---|---|---|
| F1 | 偶发:渲染新框前旧框闪一下 | 自动 `performTranslation` 截屏后 `fadeInAfterCapture()` 立刻恢复**旧框**,新译文 ~1s 后才渲染 | ✅ **2026-06-21 最终修**:window 模式注释掉 `performTranslation` 里**立即**的 `fadeInAfterCapture()`(`onResult→updateOverlays` 置 alpha=1 出新框 / `onUnchanged`→fadeIn 恢复旧框);`onWillTranslate`/`onUnchanged` 回调用 `inPlaceMode` 门控(window 模式不提前清框→不空窗)。`updateOverlays` 入口补 `alpha=1f` 复位 |
| F2 | 潜在 race:fade 重入把 `savedAlpha` 存成 0 → 框卡不可见 | `fadeOutForCapture` 无重入保护,两次 fadeOut 叠加覆盖已存 alpha | ✅ **已修**:`fadeOut/In` 加 `faded` 标志守护 | ✅ |
| F3 | **window 模式持续闪烁**(静止画面每秒闪一下) | `mergedAutoStep` 整屏指纹 + `performTranslation` 末尾 `lastFingerprint=null` 自反馈:每次置 null 被当成"画面变了"→无限重译→每次 fade 一下 | ✅ **2026-06-21**:加 `skipNextChange` 标志,翻译后置 null 造成的那次"假变化"被吞掉、不再触发重译 |
| F4 | **window 模式有时不自动翻译**(要手动点一次) | `mergedAutoStep` 拿**整屏** 32×18 指纹、阈值 2% ≈ 11 格;一句对话框只占整屏 ~5–10 格 → 够不到阈值 → 漏判 | ✅ **2026-06-21**:`fingerprint(bmp, area)` 支持裁剪到 `activeTranslationArea`,区域内变化占比大、可靠检出。(整屏无区域时仍粗,见"待补充") |
| F5 | in-place 首个框偶发偏左上 | `getLocationOnScreen` 在窗口定位完成前读到脏值,框跳到 (0,0) | ✅ **2026-06-21**:`renderInPlace` 加 `!isLaidOut → post 重渲染一次` 守护(`loc` 仍由 `getLocationOnScreen` 读,确为窗口真实偏移、非 0) |
| F6 | auto 开着时按 stop,重启后图标错显示"已开启" | `ScreenCaptureService.autoMode` 静态字段停止时未复位,残留 `true` 被面板读到 | ✅ **2026-06-21**:`onDestroy` 加 `autoMode = false` |

### 2.4 待补充
> (后续把性能 profiling、内存、冷启动等新发现项填进来)

---

## 三、新功能

### 3.1 已在 backlog
| # / 项 | 描述 | 依赖 / 备注 |
|---|---|---|
| 5 | OCR 引擎抽象 `OcrEngine`，加 PaddleOCR / LLM Vision | 架构改动较大，需先定接口 |
| 6 | UI 主题：多 theme 变体 + 圆圈颜色选择器 | 纯前端 |
| 7 | App 图标可选：activity-alias + 预置图标 | 纯资源 + manifest |
| 12 | 自定义 LLM 端点（若 §一 决策为"做 feature"） | 协议选择 + 端点校验 |
| 13 / 17 | "最近翻译 / 历史"界面（复用闲置的 `recent_translations` 思路） | 当前表/DAO 已清，需重新设计存储 |
| — | Phase 2b：per-provider API keys（现共用一个 `llmApiKey`，切 provider 要重贴 key） | 改 SecureStorage 存储结构 |
| **加载动画** | 翻译等待期显示循环动画,降低等待焦虑。触发:发请求时显示、收到译文/出错时隐藏;位置:浮层上(in-place 框附近或控制条旁);实现:`ImageView`+帧动画 / `AnimatedVectorDrawable`,与"请求中"状态联动 | 🔄 **贴图素材用户正在 AI 画图工具 + PS 制作中**;代码侧等素材好再接 |
| 多 provider 接入 | 试 Gemini / OpenAI / Claude。**Gemini/OpenAI 已接**(`GeminiClient`/`OpenAiCompatibleClient`,选便宜档 `gemini-2.0-flash`/`gpt-4o-mini`);**Claude 未真正接**——下拉里 `claude-3-*` 落到 `else→GeminiClient` 必失败 | ⚠️ 两个坑:① `model_codes` 里 `claude-3-sonnet` **已退役(404)**、`claude-3-haiku` 将退役,要换 `claude-haiku-4-5`/`claude-sonnet-4-6`;② Claude 需新 `ClaudeClient`(Messages API 非 OpenAI 格式:`x-api-key`+`anthropic-version`、`/v1/messages`、`system` 单独、`content[].text`)。**成本**:每千次翻译 DeepSeek/Gemini-flash ~$0.06–0.2、Haiku ~$0.87、Sonnet/GPT-4-turbo $2.6–5.7(贵 40×,翻译用不上) |
| 非推理 provider | 换境内可达、**不强制推理**的档以突破推理地板。**注**:DeepSeek 关推理后已 ~1s,此项优先级下降;`TokenStats` 已加用于成本监控 | 低优先(已 ~1s 可用) |

### 3.2 待规划
> （新想法填这里：如截图翻译/相册导入、划词复制、翻译方向快捷切换等）

---

## 四、长期遗留 / 待确认
- ~~**撤销历史中硬编码的 Gemini key**（commit `e776daa`）~~ ✅ **已处理（2026-06-21）**：key 已在控制台注销；用 BFG `--replace-text` 重写全历史（`e776daa`/`70f7720` 两提交里的明文已替换为 `***REMOVED***`），force-push 所有分支，`git rev-list --all` 全历史 grep 无残留。
- OCR 引擎、provider key、历史功能三者可作为 2.x 的主要 feature 主题。

---

## 五、Release 2.0 收尾（2026-06-21）

发布前一轮集中修复 + 清理，均已落地（代码由用户改、本文档留痕）。

### 5.1 window / in-place 渲染稳定性（本轮重点）
见 §2.3 表 F1/F3/F4/F5/F6。核心是**两种模式的自动检测/渲染分叉**梳理清楚：
- **window 模式** = 整屏指纹 → 改为**裁剪 `activeTranslationArea`** 检测（F4），加 `skipNextChange` 断开"翻译后 `lastFingerprint=null`"自反馈循环（F3），注释 `performTranslation` 立即 fadeIn + 用 `inPlaceMode` 门控 `onWillTranslate`/`onUnchanged`（F1）。
- **in-place 模式**（OCR 文本对比 + 点击 + peek 那套）逻辑不变，仅修首框定位（F5）。
- `onResult` guard 回退为 `translationStartSession == sessionId` 单条件（删掉了误加的 `&& !userInputPending`——它在 window 模式永不被 `consumeUserInput` 清、导致结果恒被丢、框不出）。
- `onDestroy` 复位 `autoMode`（F6）。

> **待回归/已知边角**：①window 模式每次真换台词后，新框出现会被整屏…（实为区域）指纹算作一次变化、多触发一次"重译→skip"，残留一下，非持续；②整屏（未框选区域）的 window 模式指纹仍粗、可能漏小变化——建议引导框选或对 `area==null` 调低阈值；③`updateOverlays` 直接置 alpha 未复位 `faded` 标志，window 模式（框在区域外）无害，若框压区域/整屏可补 `faded=false`。

### 5.2 发布前清理（dev → release）
| 项 | 处理 | 状态 |
|---|---|---|
| 版本号 | `versionCode 1→2`、`versionName "1.0"→"2.0"` | ✅ |
| 日志 | `proguard-rules.pro` 加 `-assumenosideeffects android.util.Log {...}`，release(minify 已开)整体 strip 自家日志(系统/原生日志如 BLASTBufferQueue 无法也无需删) | ✅ |
| 延迟脚手架 | 开 `buildConfig=true`；`PerfTrace`/`TokenStats` 方法首行 `if(!BuildConfig.DEBUG) return`、`eventListenerFactory` 用 `apply{ if(BuildConfig.DEBUG) }` 门控；`LatencyStats` 经 listener 不可达 → release 自动 strip。代码留 master | ✅ |
| 中转端点 | gpt 端点从测试中转 `api.ooapi.cc` 改回 `api.openai.com`（`createLlmClient` + `warmUp` 两处一致） | ✅ |
| 刷屏日志 | 注释 `captureScreen` 每帧 `Log.d "Creating bitmap..."`（且被 proguard 兜底） | ✅ |
| Gemini key | 见 §四（注销 + BFG 清历史 + force-push） | ✅ |
| arrays 对齐 | `models`↔`model_codes` 15/15 对齐，旧 `gemini-2.1-pro` 笔误已修为 `gemini-2.5-pro` | ✅ |

### 5.3 发布后/下一步（未做，不挡 2.0）
- **ClaudeClient minify keep**：`proguard-rules.pro` 只 keep 了 `GeminiClient$*`/`OpenAiCompatibleClient$*` 的 Gson 类，**漏了 `ClaudeClient$*`**。Claude DTO 里没标 `@SerializedName`、靠字段名直对 json 的字段，minify 后会混淆 → Claude 解析失败。补 `-keep class com.example.ocr_translation.ClaudeClient$* { *; }` 或给字段全标 `@SerializedName`。**用户主动延后**。
- **#15** `OCRProcessor.cleanup()` 仍禁用（recognizer 不释放，资源泄漏但功能正常）。
- **#29** `START_NOT_STICKY` 真机后台强杀实测。
- §5.1 三个边角。
