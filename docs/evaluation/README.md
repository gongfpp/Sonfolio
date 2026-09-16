# 质量评测集

## 总结质量评测集（P1③）

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

## 中英文离线识别质量集（ASR）

本地识别可选 SenseVoice（默认，239 MB）与 Qwen3-ASR 0.6B int8（约 987 MB，中英混说与方言更强）。
`asr-zh-en-set.json` 固定一组中文、英文与粤语素材，`Qwen3AsrZhEnQualityTest` 在真机上对每个样例
计算字错率（CER，去掉空白与标点后按字符编辑距离），回答“这个模型值不值得下载”。

### 文件

- `asr-zh-en-set.json`：权威素材集（中文 4 例、粤语 1 例、英文 1 例）。
- `android/app/src/androidTest/assets/asr-zh-en-set.json`：真机评测使用的同一份副本；
  JVM 测试 `AsrZhEnSetTest` 保证两份完全一致，改动必须同时更新。

素材取自 sherpa-onnx Qwen3-ASR 模型仓库自带的 `test_wavs`（Apache-2.0 仓库），不进入本仓库，
由脚本按固定 URL 拉取后推送到手机应用私有目录，便于随时替换成更严格的公司/个人素材。

### 如何运行

1. 在设置 → 转文字方式中下载 Qwen3-ASR 模型，并选择该引擎。
2. 连接 TCP ADB 后拉取并推送素材（脚本会把素材统一转成生产的 16 kHz 单声道 PCM16，再推送）：
   ```
   node scripts/prepare-asr-eval.mjs
   ```
3. 真机运行评测：
   ```
   adb shell am instrument -e class com.gongfpp.sonfolio.Qwen3AsrZhEnQualityTest \
     -w com.gongfpp.sonfolio.test/androidx.test.runner.AndroidJUnitRunner
   ```
   输出每个样例的期望、实际与 CER，并按 `maxCer` 上限断言。更换模型或引擎后各跑一次对照。

### 更新约定

- 素材只追加、不修改历史样例；`maxCer` 是首轮上限，跑出更优结果后再收紧并在此记录。
- 个人词汇（热词）是否真正改善中英混说，可在同一素材上分别以空 hotwords 与个人词汇 hotwords 各跑一次对照。

## 结果记录

（总结：暂无：首个验收模型 Qwen2.5-0.5B 的结果待真机验收时补充。）
（ASR：**Qwen3-ASR 0.6B int8 真机验收未通过**。2026-09-17 在 Redmi Note 8 Pro（Android 10）跑中英文 6 条素材，全部返回空文字，整轮仅 14.4 s，未真正生成 token；模型文件 SHA-256 校验通过，随包 sherpa-onnx 原生库为 2026-09-01 版本，`max_new_tokens` 已按官方示例设为 512 仍为空。已加防护：本地 Qwen3 若整段返回空会直接报错并提示改用 SenseVoice，不再静默丢弃文字。根因（原生推理/模型格式）待继续排查；SenseVoice 仍是默认且可用的本地引擎。）
