# 首页重构提案（2026-09-16）

两轮共七套方案，均为静态 HTML，可直接用浏览器打开（手机宽度模拟 390px，桌面居中展示 + 方案说明）：

```bash
cd design/prototypes/home-redesign
python3 -m http.server 4174
# http://127.0.0.1:4174/index.html
```

第一轮（A/B/C，其中 B 缺少「标记刚才」入口，由第二轮 B2 修订）：

| 文件 | 方向 | 一句话 |
| --- | --- | --- |
| [proposal-A-timeline.html](proposal-A-timeline.html) | 时间轴优先 | 一天就是一条时间轴，卡片按发生时间锚定，缺口即虚线 |
| [proposal-B-dashboard.html](proposal-B-dashboard.html) | 状态仪表优先 | 统计+处理进度+热力常驻首屏（无标记直达，已被 B2 取代） |
| [proposal-C-recorder.html](proposal-C-recorder.html) | 录音优先 | 首屏是录音驾驶舱：计时器、波形、标记直达，回看是配菜 |

第二轮（B2/D/E/F，全部含「标记刚才」一级入口）：

| 文件 | 方向 | 一句话 | 标记位置 |
| --- | --- | --- | --- |
| [proposal-B2-dashboard-markers.html](proposal-B2-dashboard-markers.html) | B 修订版 | 仪表布局不变，录音主卡并入标记区与内联反馈 | 主卡内固定区 |
| [proposal-D-journal.html](proposal-D-journal.html) | 日记优先 | 首页先读「今日故事」，对话按章节展开 | 底部悬浮条（随滚动常驻） |
| [proposal-E-chat.html](proposal-E-chat.html) | 对话流 | 聊天线程心智，对话预览引述原话 | 置顶线程快捷条 |
| [proposal-F-minimal.html](proposal-F-minimal.html) | 极简录音条 | 白底极简列表 + 深色录音条 | 录音条内（录/标/停一根条） |

## 通用约束（全部方案一致）

- 沿用现有品牌色（Green `#1E7046`、PaleGreen `#E3F0DE`、Line `#E6E5DE`、卡片 `#FFFEFA`）；标记体系沿用应用的琥珀色（AmberPale 底 / `#694E00` 字）与现有交互（★主按钮显示分钟数 + 两个圆钮），三个时长可在设置修改。
- 保留现有信息架构中的关键事实：录音状态、切片保存节奏（5 分钟）、处理状态对原音的可见性、缺口透明、一日回顾入口。
- 均为视觉/交互方向提案，未包含代码改动；确定方案后下一步是 Compose 实现（只动 `TodayScreen` 及其子组件，`ConversationScreen`/`Search`/`Settings` 不在本次范围）。
