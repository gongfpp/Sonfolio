# Sonfolio 技术选型与实现边界

## 结论

Sonfolio 的正式客户端选择 Android 原生 Kotlin。界面使用 Jetpack Compose，数据使用 Room/SQLite，持续录音由 Foreground Service 持有，录音后的 VAD、ASR 和结构化整理进入本地可重试的处理队列。V0.1 的核心目标是“录音不被 AI 失败打断”，所以录音链路与理解链路必须是两个互相隔离的生命周期。

当前仓库中的 `android/` 已经完成可编译的客户端基线，并接入 Room 2.7.2、KSP、Repository、ViewModel 和数据库 Schema 导出。首页时间线的数据链路是 `Room Flow -> Repository -> ViewModel -> Compose`，首装为空列表，不再初始化演示 Conversation。`0.1.4` 的前后台录音、5 分钟切片、独立进程 VAD/ASR、标记、异常恢复、搜索、回放和导出已在 Redmi Note 8 Pro 真机验证；`0.1.5` 增加日期浏览及页面状态保存，验证边界分别以对应开发验收记录为准。

## 为什么选择 Android 原生 Kotlin

产品最难的部分不是绘制页面，而是 Android 后台录音、前台服务通知、麦克风权限、Doze/电量限制、音频焦点、异常恢复和设备厂商差异。原生 Kotlin 能直接使用 `AudioRecord`、Foreground Service、`MediaRecorder`/音频编码、通知渠道和系统电源管理 API，不需要经过跨平台运行时的后台桥接层。这样可以优先保证“开始记录以后持续工作”，也方便以后针对特定设备增加恢复策略。

没有选择 WebView 或纯跨平台方案作为录音核心，是因为浏览器页面在后台、锁屏和系统回收后的生命周期不可控；也没有先引入大型跨平台框架，因为 V0.1 只有一个 Android 客户端，跨平台抽象会增加录音权限、后台服务和本地模型接入的适配成本。将来如果需要 iOS，可以把领域模型和处理协议保持平台无关，再分别实现录音适配器，而不是现在牺牲 Android 可靠性。

## 为什么选择 Jetpack Compose

Compose 用声明式状态描述页面，适合时间线、对话详情、搜索筛选、设置开关这类状态密集型界面。页面已经拆成 `RecordingCard`、`TimelineCard`、`SummaryCard`、`AudioPlayer` 和 `RetentionRow` 等组件，当前首页已经订阅 Room 的 `Flow`，数据库插入或处理状态变化会自动刷新时间线，不需要手动通知页面。Material 3 提供可访问的基础控件，但颜色、间距、圆角和信息层级仍由 Sonfolio 自己的设计令牌控制，以保持原型的暖白、墨色、森林绿和琥珀色视觉语言。

Compose 只负责展示和交互，不负责录音、模型推理或文件清理。这样可以通过 ViewModel/Repository 将 UI 与设备服务隔开，也能让静态演示数据在真实数据接入前继续用于视觉回归。

`0.1.5` 将长列表改为 `LazyColumn`，使屏幕外的对话、搜索结果和转写行不必同时参与布局；这只优化界面层，不等于数据库分页或全文索引。导航用可保存的路由栈记录打开来源，`SaveableStateHolder` 分别保留主页日期、搜索输入、筛选和列表位置，避免返回详情时重置搜索。日期范围由本地时区的相邻零点生成，不按固定 24 小时计算；跨午夜原音与对话在相交日期都可找到，统计按 PCM 数据长度与当日范围的交集去重求和，回听仍使用完整原文件。

## 本地数据模型与可靠性边界

数据关系保持与产品设计一致：

```text
AudioChunk -> SpeechSegment -> Transcript -> Conversation -> DailyJournal
```

`AudioChunk` 是录音服务每 5 分钟创建的物理文件和时间范围；`SpeechSegment` 记录 VAD 命中的人声区间；`Transcript` 保存带时间戳的 ASR 文本；`Conversation` 按相邻间隔和环境连续性把多个语音段合并，因此一场对话可以跨越多个物理切片；`DailyJournal` 是一天级别的结构化回顾。每层都保存处理状态、错误信息和输入版本，任务重试采用幂等写入，避免重复转写或重复生成事件。

当前实现使用 Room 2.7.2/SQLite 保存元数据，Schema 已推进到版本 8，版本 1–8 的 JSON 均保留在 `android/app/schemas/` 作为迁移测试基线。Room 官方说明 2.7 开始以 Kotlin 2.0 为目标并推荐 KSP2，2.7.2 又修复了 Schema 导出问题，因此它与现有 Kotlin 2.0.21/KSP2 工具链边界一致。没有采用 2.8.4，是因为实测该版本在当前旧 KSP 插件下首次生成 Schema 能成功、第二次读取 Schema 却出现 `kotlinx.serialization` ABI 冲突；可重复构建优先于追逐较新的版本。等 Android Gradle Plugin、Kotlin 和 KSP 整体升级时再一起评估 Room 2.8 或 Room 3。录音写入应用私有目录，用户导出时选择副本的保存位置；数据库只保存路径、时间、大小、处理状态和摘要等元数据。搜索第一版先使用 SQLite `LIKE` 实现可用的全文关键词匹配并跳到对应 Conversation/时间点，后续再评估中文全文索引；语义向量搜索留到后续版本。

搜索在 `0.1.6` 改用由代码构造条件、参数绑定的 Room `@RawQuery`，用于处理可变数量的关键词，并显式观察转写、对话和标记三个表；官方说明这种可观察查询需要声明 `observedEntities`，不能依赖固定 SQL 的编译期验证，因此另外用 SQLite 内存库执行生产查询，并已在 Android 真机的 Room 集成测试中验证映射与变更通知。[Room RawQuery 文档](https://developer.android.com/reference/androidx/room/RawQuery)。关键词只作为参数值传入，`%`、`_` 和反斜杠先转义为字面匹配。0.2.2 起结果按「每场对话」聚合而不是逐句平铺：一场对话一张卡片，返回标题、正文命中句数、最匹配的一句片段和标记状态，排序为标题命中 > 正文命中句数 > 最近命中时间；空关键词时按最新对话列出（仍以 100 条为一批）。高亮与查询共用同一套拆词规则，多个关键词都会标出。每次比当前展示范围多读取一条判断是否还有内容，用户点击“加载更多”时扩大范围；这避免原先 100 条后的内容无入口，但尚未实现游标分页、全文索引或跨转写行的语义匹配，也不需要迁移数据库。桌面测试用的 SQLite JDBC 只加入测试依赖，不进入 APK。

## 录音链路与处理链路

Foreground Service 是录音链路的所有者。当前实现要求用户在可见 Activity 中授予 `RECORD_AUDIO` 后启动服务，并在 Manifest 声明 `microphone` 类型和 `FOREGROUND_SERVICE_MICROPHONE`；这符合 Android 对 while-in-use 麦克风权限的限制。服务使用 `VOICE_RECOGNITION` 音源，以 16 kHz、单声道、PCM 16-bit 写入标准 WAV，5 分钟滚动生成一个 chunk，约占 9.6 MB，便于录音仍在继续时及时启动 VAD/ASR；相邻切片仍由规则合并为完整 Conversation。每个切片开始前先写入 `RECORDING` 状态，正常停止后补齐 WAV 头并更新为 `RECORDED`；进程异常退出时，下次打开应用或重新启动服务会按实际文件长度修复 WAV 头、把切片标记为 `RECOVERED`，并为最后写入时间至恢复时间创建 `RecordingGap`。

录音期间持有带 35 分钟硬超时的 Partial WakeLock，并在每个 5 分钟切片开始时续期。通知栏提供默认 3 分钟标记和停止，应用内还可选择回溯 10 或 20 分钟；新标记只记录向前窗口，命中后将整段连续 Conversation 高亮，不代表已经具备语义分段能力。服务使用 `START_STICKY`，每 5 秒刷新 WAV 头并同步文件；重启时按 PCM 样本数计算真正结束时间和缺口，不能使用修复文件之后的修改时间。系统强制停止或厂商禁止后台重启时，需要用户重新打开应用，不能保证服务一定自动恢复。

WorkManager 或本地任务队列只处理已经落盘的 chunk。任务顺序为：读取 chunk → Silero VAD → 生成 SpeechSegment → SenseVoice ASR（通过 sherpa-onnx 运行）→ 写入 Transcript → 规则合并 Conversation → 更新搜索索引。每一步都可单独重试，模型内存异常只会将当前任务标记为失败并保留原始音频。网络 API 如果将来用于更高质量总结，也只能订阅已经完成的本地结构化输入，不能成为录音前置条件。

`0.1.4` 将 VAD/ASR 原生计算放进非导出的 `:inference` 服务进程，用 Messenger 请求和返回时间窗/文字；数据库、队列和录音仍由主进程持有。选择系统绑定服务，是为了避免 native 崩溃直接退出录音进程，也避免将 Room 和偏好配置变成跨进程共享写入；实现依据 [Android 绑定服务说明](https://developer.android.com/develop/background-work/services/bound-services) 和 [进程与线程说明](https://developer.android.com/guide/components/processes-and-threads)。模型进程断开会把当前音频标为失败，主进程可继续录音；ASR 的业务失败写入数据库后结束当前队列项，后续音频仍继续处理。这个隔离机制不能替代整机低内存、锁屏和厂商省电策略的长时间验收。

## 模型选择

V0.1 使用 Silero VAD、SenseVoice 和 sherpa-onnx，原因是三者可以在 Android 本地运行，覆盖中文语音场景，并且模型运行时与模型文件可以独立替换。Silero VAD 负责降低静音和环境声带来的 ASR 成本；SenseVoice 负责带时间戳的中文语音识别；sherpa-onnx 作为统一的端侧推理运行时，减少直接绑定单一模型框架的风险。模型版本、语言、采样率、耗时、内存峰值和失败原因需要写入处理记录，便于比较模型升级是否影响历史结果。

0.2.2 起本地识别引擎可插拔：`ModelCatalog` 用多文件模型条目登记 SenseVoice（239 MB）与 Qwen3-ASR 0.6B int8（约 987 MB，conv-frontend + encoder + decoder + tokenizer），`LocalAsrEngine` 决定下载与推理使用哪一个，默认仍是 SenseVoice；两者是并列备选而不是替换关系。转录记录写入实际使用的 `modelName`／`modelVersion`。Qwen3-ASR 的 LLM 提示支持 hotwords，因此新增「个人词汇」：用户修正转写时按「原识别 → 修正」抽取候选词，达到确认阈值后进入候选列表，用户确认或多次命中后加入，转写时拼接为热词串；只影响本地 Qwen3-ASR，不上传、不自动改写历史文字。中英文素材与字错率验收见 `docs/evaluation/`：2026-09-17 真机实测粤语 CER 0.000、英文 0.069、中文快语速 0.154、绕口令 0.079，带噪对话 0.321；歌曲类素材返回固定 `language` 为已知缺陷。默认仍是 SenseVoice（体积小、速度快），Qwen3 作为可选更强引擎。

第一版不做说话人识别，不把“未知人物”误判为具体联系人；也不把摘要按钮扩展成 Todo 管理器。每段对话保留一份小总结，信息量高的对话再生成结构化大总结，一日总结以日记式回顾为主，辅助列出少量值得记住和可能需要处理的事项。

0.1.7 在用户确认两条路线均可且由用户选择后，增加本地生成式总结和外部文本 API，并保留基础整理作为默认模式。选择 llama.cpp 是因为它可通过固定 C++ 源码和 JNI 在 arm64 Android 上运行 GGUF，不要求同步升级当前 Kotlin/Room 工具链，模型文件和运行时仍可独立管理；实现参考 [官方 Android 构建说明](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)。本地模型置于独立 `:summary` 进程，外部服务使用用户配置的 HTTPS Chat Completions / JSON 协议，密钥以 [Android Keystore](https://developer.android.com/privacy-and-security/keystore) 加密，默认不发送文字、不自动下载权重，也不上传原音。真实模型质量、速度、长内容成本和内存尚待验收，当前自动重算与反复加载模型的效率问题仍未解决。

0.1.8 将系统输入监测、持续缺口和总结的不完整信息提示接入主链路，Schema 3 将缺口结束时间改为可空，并从旧版本显式迁移数据。音量仅代表实际样本能量，系统静音与环境安静分开判断；VAD/ASR 超时解绑后销毁独立进程，等待 Binder 死亡再执行下一项，不能依赖 `quitSafely()` 取消已经执行的 native 代码。会话与日回顾目前依然全量重建，采集切片仍可能等待数据库事务，因此下一阶段优先做增量更新及采集/元数据写入隔离，不能凭本轮异常提示和单元测试就宣称解决了长期积压导致的漏音风险。

## 版本推进顺序

1. 已完成 Compose 信息架构、交互状态和静态 Web 视觉对照；导航栈与页面状态支持保存，0.1.5 新增日期及返回场景已在 0.1.6 真机验收中通过，加载更多、长转写固定播放器及搜索返回位置也已检查，证据见对应开发验收记录。
2. 已完成 Room 基础实体、Schema 版本 1 和首页时间线读取；真实 Conversation、详情转写、搜索和每日回顾使用同一数据库，首装不注入演示内容。
3. 已实现 Foreground Service、真实 `AudioRecord`、5 分钟 WAV 切片、通知栏标记/停止、标记即时反馈、异常切片修复和 `RecordingGap` 写入；旧版本的前台/后台录音已在 Redmi Note 8 Pro 验证，新版本验证状态以开发验收记录为准。
4. 已接入 Silero VAD、SenseVoice/sherpa-onnx 和串行 WorkManager 队列；现有真实录音在 Redmi Note 8 Pro 上生成 SpeechSegment 和 Transcript，模型 OOM/进程退出会保留原音并在下次启动重新排队。
5. 已加入相邻间隔不超过 2 分钟的 Conversation 合并、按文本生成短标题、每段提取式小结、长对话重点整理、搜索结果进入详情，以及跨切片按转写行跳转和连续回听原 WAV。
6. 已接入可选择的本地/外部生成式总结框架，但真实本地模型与服务端到端尚待验收；未完成项还包括全天锁屏、增量整理与采集数据库隔离、合并 ID 重定向、完整备份恢复、FTS/中文检索方案和说话人识别，具体状态以 0.1.8 记录为准。

当前仓库不把“UI 壳构建成功”表述成“全天录音已完成”。V0.1 的验收必须同时看到：持续录音没有非预期缺口、处理失败不影响录音、事件可读可搜可回听、原始音频保留策略完全由用户控制。
