# 首页重构提案（2026-09-16）

三个方向的原型均为静态 HTML，可直接用浏览器打开（手机宽度模拟 390px，桌面居中展示 + 方案说明）：

```bash
cd design/prototypes/home-redesign
python3 -m http.server 4174
# http://127.0.0.1:4174/index.html
```

| 文件 | 方向 | 一句话 |
| --- | --- | --- |
| [proposal-A-timeline.html](proposal-A-timeline.html) | 时间轴优先 | 一天就是一条时间轴，卡片按发生时间锚定，缺口即虚线 |
| [proposal-B-dashboard.html](proposal-B-dashboard.html) | 状态仪表优先 | 统计+处理进度+热力常驻首屏，回答“还在录吗、处理到哪了” |
| [proposal-C-recorder.html](proposal-C-recorder.html) | 录音优先 | 首屏是录音驾驶舱：计时器、波形、标记直达，回看是配菜 |
| [index.html](index.html) | 对比与决策 | 三方案对比表 + 采纳后的改动范围 |

## 通用约束（三方案一致）

- 沿用现有品牌色（Green `#1E7046`、PaleGreen `#E3F0DE`、Line `#E6E5DE`、卡片 `#FFFEFA`）。
- 保留现有信息架构中的关键事实：录音状态、切片保存节奏（5 分钟）、处理状态对原音的可见性、缺口透明、一日回顾入口。
- 均为视觉/交互方向提案，未包含代码改动；确定方案后下一步是 Compose 实现（只动 `TodayScreen` 及其子组件，`ConversationScreen`/`Search`/`Settings` 不在本次范围）。
