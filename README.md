# Sonfolio 客户端原型

这是根据 `design/prototypes/sonfolio-v0.1-summary-flow.png` 还原的 Sonfolio 客户端原型与 Android V0.1 实现。Web 目录用于视觉对照，`android/` 是正式的本地优先客户端：录音、VAD、ASR、对话合并、搜索和原音回听都在手机端完成，不依赖后端或外部 API。

## 本地预览

在项目目录运行（如果 `4173` 被其它本地原型占用，使用 `4174`）：

```bash
python3 -m http.server 4173
# 若 4173 被占用：
python3 -m http.server 4174
```

然后打开 <http://127.0.0.1:4173>。当前环境中 `4173` 已被其它本地页面占用，本次 Sonfolio 预览使用 <http://127.0.0.1:4174>。

## 已还原的界面

- 今日时间线：录音状态、单按钮标记、四场对话及每场一句小结、今日总结入口。
- 对话详情：本段小结、结构化字段、原始转写展开、星标时间戳和播放状态。
- 详细总结：信息量较大的对话按讨论主题、关键观点、共识与决定、未决问题展示。
- 今日回顾：日记式一日总结、值得记住、可能需要处理和重新生成。
- 搜索：关键词输入、筛选、匹配内容和结果进入详情。
- 录音与存储：录音服务、容量估算、保留策略和本地处理开关。

原型界面会根据屏幕宽度自动切换为移动端全屏或桌面端手机壳预览。

## Android 客户端工程

`android/` 是正式客户端的 Kotlin + Jetpack Compose 工程，包含 Today、Conversation、Search、Settings 和 Daily 页面。Room 本地数据库保存 `AudioChunk -> SpeechSegment -> Transcript -> Conversation -> DailyJournal` 链路以及标记和录音缺口；首页、详情、搜索和每日回顾均从同一份数据库状态刷新。

`0.1.3` 在真实录音链路上接入了 Silero VAD、sherpa-onnx SenseVoice int8 本地 ASR 和串行 WorkManager 队列：录音文件落盘后自动提取人声窗口、写入带时间戳转写，按相邻间隔不超过 2 分钟合并为 Conversation，并生成可阅读的小结；信息量较大的对话另外保存本地提取式要点，一日回顾也会写入 Room。搜索页支持转写全文关键词匹配，详情页可展开结构化转写并从首段时间点回听原始 WAV。VAD、ASR 和真实时间线已经在 Redmi Note 8 Pro 上跑通，APK 约 244 MB（本地模型占主要体积）。

```bash
cd android
./gradlew :app:assembleDebug
```

模型和 arm64 native 运行库由 Git LFS 管理；从全新工作区构建前先执行 `git lfs pull`。

安装 Debug APK：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

完整的技术选型、数据边界和分阶段实现顺序见 [`docs/technical-selection.md`](docs/technical-selection.md)。
