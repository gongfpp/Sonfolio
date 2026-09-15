# 总结质量评测集（P1③）

Sonfolio 的总结很容易在否定句、条件句、疑问句、多人讨论、“决定 vs 建议”、“待办 vs 猜测”、前后修正这些场景出错。
本目录保存一份**固定质量集**，用于回答“换模型值不值得”，而不是靠肉眼感觉。

## 文件

- `summary-quality-set.json`：权威数据集（20 段对话，覆盖七类场景）。
- `android/app/src/androidTest/assets/summary-quality-set.json`：真机评测使用的同一份副本；JVM 测试 `SummaryQualitySetTest` 保证两份完全一致，改动必须同时更新。

## 指标

- **事实命中率（factHitRate）**：`expectKeyPhrases` 中有多少短语以子串形式出现在输出（title/brief/keyPoints/decisions/followUps/questions）。只衡量“提到过正确事实”，不评分文采。
- **禁止内容（forbiddenHits）**：`forbidden` 短语出现在输出中的次数。目标是 0——典型错误是把否定句、疑问、建议升级成已决定的结论，或者重复已被更正的旧说法。

## 如何运行

1. **本地提取式引擎基线（无需真机）**：
   ```
   ./gradlew :app:testDebugUnitTest --tests "com.gongfpp.sonfolio.SummaryQualitySetTest"
   ```
   当前基线要求 factHitRate ≥ 90% 且禁止内容为 0。数据集或引擎任一方改动导致回归都会在这里失败。
2. **本地 GGUF 模型（真机，模型需已下载）**：
   ```
   adb shell am instrument -e class com.gongfpp.sonfolio.LocalSummaryQualityTest \
     -w com.gongfpp.sonfolio.test/androidx.test.runner.AndroidJUnitRunner
   ```
   输出每个 case 的原始输出与总指标。换模型（0.5B → 1.5B）前后各跑一次，对照报告即可回答“1 GB 模型体积换来了多少正确率”。
3. **在线模型（手动）**：把同一数据集按相同 prompt 发给待测在线服务，人工或脚本对照同一指标记录。

## 更新约定

- 数据集只追加、不修改历史 case；确需修正措辞时，在 PR 说明中注明并对新旧引擎各重跑一次留档。
- 每次 0.2.x 版本更换模型或提示词后，把结果记录在本文件末尾。

## 结果记录

（暂无：首个验收模型 Qwen2.5-0.5B 的结果待真机验收时补充。）
