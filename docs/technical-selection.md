# Sonfolio 技术选型与实现边界

## 结论

Sonfolio 的正式客户端选择 Android 原生 Kotlin。界面使用 Jetpack Compose，数据使用 Room/SQLite，持续录音由 Foreground Service 持有，录音后的 VAD、ASR 和结构化整理进入本地可重试的处理队列。V0.1 的核心目标是“录音不被 AI 失败打断”，所以录音链路与理解链路必须是两个互相隔离的生命周期。

当前仓库中的 `android/` 已经完成可编译、可在真机冷启动的客户端基线，并接入 Room 2.7.2、KSP、Repository、ViewModel 和数据库 Schema 导出。首页时间线的数据链路已经是 `Room Flow -> Repository -> ViewModel -> Compose`，演示 Conversation 会在空数据库中一次性初始化；详情和搜索仍有部分固定演示内容，真实麦克风、VAD 和 ASR 尚未接入。

## 为什么选择 Android 原生 Kotlin

产品最难的部分不是绘制页面，而是 Android 后台录音、前台服务通知、麦克风权限、Doze/电量限制、音频焦点、异常恢复和设备厂商差异。原生 Kotlin 能直接使用 `AudioRecord`、Foreground Service、`MediaRecorder`/音频编码、通知渠道和系统电源管理 API，不需要经过跨平台运行时的后台桥接层。这样可以优先保证“开始记录以后持续工作”，也方便以后针对特定设备增加恢复策略。

没有选择 WebView 或纯跨平台方案作为录音核心，是因为浏览器页面在后台、锁屏和系统回收后的生命周期不可控；也没有先引入大型跨平台框架，因为 V0.1 只有一个 Android 客户端，跨平台抽象会增加录音权限、后台服务和本地模型接入的适配成本。将来如果需要 iOS，可以把领域模型和处理协议保持平台无关，再分别实现录音适配器，而不是现在牺牲 Android 可靠性。

## 为什么选择 Jetpack Compose

Compose 用声明式状态描述页面，适合时间线、对话详情、搜索筛选、设置开关这类状态密集型界面。页面已经拆成 `RecordingCard`、`TimelineCard`、`SummaryCard`、`AudioPlayer` 和 `RetentionRow` 等组件，当前首页已经订阅 Room 的 `Flow`，数据库插入或处理状态变化会自动刷新时间线，不需要手动通知页面。Material 3 提供可访问的基础控件，但颜色、间距、圆角和信息层级仍由 Sonfolio 自己的设计令牌控制，以保持原型的暖白、墨色、森林绿和琥珀色视觉语言。

Compose 只负责展示和交互，不负责录音、模型推理或文件清理。这样可以通过 ViewModel/Repository 将 UI 与设备服务隔开，也能让静态演示数据在真实数据接入前继续用于视觉回归。

## 本地数据模型与可靠性边界

数据关系保持与产品设计一致：

```text
AudioChunk -> SpeechSegment -> Transcript -> Conversation -> DailyJournal
```

`AudioChunk` 是录音服务每 30 分钟创建的物理文件和时间范围；`SpeechSegment` 记录 VAD 命中的人声区间；`Transcript` 保存带时间戳的 ASR 文本；`Conversation` 按相邻间隔和环境连续性把多个语音段合并；`DailyJournal` 是一天级别的结构化回顾。每层都保存处理状态、错误信息和输入版本，任务重试采用幂等写入，避免重复转写或重复生成事件。

当前实现使用 Room 2.7.2/SQLite 保存元数据，并提交版本 1 的 Schema JSON，为后续迁移测试留下基线。Room 官方说明 2.7 开始以 Kotlin 2.0 为目标并推荐 KSP2，2.7.2 又修复了 Schema 导出问题，因此它与现有 Kotlin 2.0.21/KSP2 工具链边界一致。没有采用 2.8.4，是因为实测该版本在当前旧 KSP 插件下首次生成 Schema 能成功、第二次读取 Schema 却出现 `kotlinx.serialization` ABI 冲突；可重复构建优先于追逐较新的版本。等 Android Gradle Plugin、Kotlin 和 KSP 整体升级时再一起评估 Room 2.8 或 Room 3。音频文件保留在应用私有存储或用户指定的本地目录，数据库只保存路径、时间、大小、处理状态和摘要等元数据。全文搜索第一版使用 SQLite FTS5/Room FTS，先解决“能找到并跳到原始时间点”，语义向量搜索留到后续版本。

## 录音链路与处理链路

Foreground Service 是录音链路的所有者。它负责启动 `AudioRecord`、滚动写入 30 分钟 chunk、记录开始/结束时间、监听异常并尝试恢复，同时通过持久化状态暴露“正在记录、最近一次成功写入、缺失时间段”。录音服务不等待模型、不调用网络，也不因为 OOM、模型崩溃或 AI 任务失败而停止；发生缺口时写入明确的 `RecordingGap` 记录，时间线必须能展示该缺口。

WorkManager 或本地任务队列只处理已经落盘的 chunk。任务顺序为：读取 chunk → Silero VAD → 生成 SpeechSegment → SenseVoice ASR（通过 sherpa-onnx 运行）→ 写入 Transcript → 规则合并 Conversation → 更新搜索索引。每一步都可单独重试，模型内存异常只会将当前任务标记为失败并保留原始音频。网络 API 如果将来用于更高质量总结，也只能订阅已经完成的本地结构化输入，不能成为录音前置条件。

## 模型选择

V0.1 使用 Silero VAD、SenseVoice 和 sherpa-onnx，原因是三者可以在 Android 本地运行，覆盖中文语音场景，并且模型运行时与模型文件可以独立替换。Silero VAD 负责降低静音和环境声带来的 ASR 成本；SenseVoice 负责带时间戳的中文语音识别；sherpa-onnx 作为统一的端侧推理运行时，减少直接绑定单一模型框架的风险。模型版本、语言、采样率、耗时、内存峰值和失败原因需要写入处理记录，便于比较模型升级是否影响历史结果。

第一版不做说话人识别，不把“未知人物”误判为具体联系人；也不把摘要按钮扩展成 Todo 管理器。每段对话保留一份小总结，信息量高的对话再生成结构化大总结，一日总结以日记式回顾为主，辅助列出少量值得记住和可能需要处理的事项。

## 版本推进顺序

1. 已完成 Compose 信息架构、交互状态和静态 Web 视觉对照；导航状态已通过自定义 Saver 支持 Activity 重建。
2. 已完成 Room 基础实体、Schema 版本 1、演示数据初始化和首页时间线读取；下一步把详情、转写和搜索也切换到同一数据库。
3. 实现 Foreground Service、30 分钟切片、异常恢复、录音缺口记录和用户可见的录音健康状态。
4. 接入 Silero VAD、SenseVoice/sherpa-onnx 和可重试处理队列，先跑通单个 chunk 的端侧流程。
5. 加入基于间隔与环境连续性的 Conversation 合并、全文搜索和时间点回听。
6. 最后实现结构化小结、大总结和一日总结，并用真实设备完成至少一整天的持续运行验证。

当前仓库不把“UI 壳构建成功”表述成“全天录音已完成”。V0.1 的验收必须同时看到：持续录音没有非预期缺口、处理失败不影响录音、事件可读可搜可回听、原始音频保留策略完全由用户控制。
