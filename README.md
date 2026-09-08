# Sonfolio 客户端原型

这是根据 `design/prototypes/sonfolio-v0.1-summary-flow.png` 还原的纯本地客户端界面原型，不包含后端、录音、VAD、ASR 或外部 API。页面使用原生 HTML、CSS 和 JavaScript，所有数据与交互状态都在前端内存中。

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

`android/` 是正式客户端的 Kotlin + Jetpack Compose 工程，包含 Today、Conversation、Search、Settings 和 Daily 页面。当前已经接入 Room 本地数据库：首页时间线通过 `Flow -> ViewModel -> Compose` 读取数据库中的演示 Conversation，数据库同时建立了 AudioChunk、SpeechSegment、Transcript、ConversationSummary、Marker、RecordingGap 和 DailyJournal 等 V0.1 基础表。详情和搜索仍有部分演示内容，真实录音、VAD 和 ASR 尚未接入。

```bash
cd android
./gradlew :app:assembleDebug
```

完整的技术选型、数据边界和分阶段实现顺序见 [`docs/technical-selection.md`](docs/technical-selection.md)。
