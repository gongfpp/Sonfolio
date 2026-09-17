#!/usr/bin/env node
// 真机仪器测试入口。CI 没有真机，只编译 androidTest；本脚本把手工验收命令固化下来，
// 避免 androidTest 长期只编译不运行。用法：
//   node scripts/run-device-tests.mjs <ADB序列号>            # 非 UI 套件（息屏也能跑）
//   node scripts/run-device-tests.mjs <ADB序列号> --ui       # 追加 Compose UI 套件（需亮屏解锁）
import { spawnSync } from 'node:child_process';

const [serial, ...flags] = process.argv.slice(2);
if (!serial) throw new Error('用法：node scripts/run-device-tests.mjs <ADB序列号> [--ui]');
const adb = process.env.ADB ?? 'adb';
const pkg = 'com.gongfpp.sonfolio';
const runner = `${pkg}.test/androidx.test.runner.AndroidJUnitRunner`;

// 不依赖界面渲染，息屏可用。
const nonUi = [
  'MigrationIntegrationTest',
  'SearchRepositoryIntegrationTest',
  'PipelineIntegrationTest',
  'BackupIntegrationTest',
  'RawCleanupIntegrationTest',
  'ConversationEditIntegrationTest',
  'SummaryIntegrationTest',
  'RecordingZoneIntegrationTest',
  'RecordingGapIntegrationTest',
  'AudioCompressionIntegrationTest',
  'LocalSummaryRuntimeTest',   // 未下载模型时按假设跳过
  'Qwen3AsrZhEnQualityTest',   // 未下载模型/未推送素材时按假设跳过
];
// 需要真实麦克风与厂商权限，只有显式 --recording 时才跑（会等待录音状态，常超时）。
const recording = ['RecordingReliabilityTest'];
// 需要亮屏且解锁；息屏时会报 “No compose hierarchies found”。
const ui = ['SearchResultsUiTest', 'ClientUiTest', 'ExperienceAcceptanceTest', 'PublicScreenshotsTest', 'LocalSummaryQualityTest'];

function run(classes, label) {
  const started = Date.now();
  const result = spawnSync('adb', ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'class', classes.map(c => `${pkg}.${c}`).join(','), runner],
    { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  const ok = /OK \(\d+ tests?\)/.test(output);
  const failed = /FAILURES!!!/.test(output);
  console.log(`\n===== ${label}：${ok ? '通过' : failed ? '失败' : '未确认'}（${((Date.now() - started) / 1000).toFixed(1)}s）=====`);
  // 只保留结果行，避免把整段堆栈刷屏。
  for (const line of output.split('\n')) {
    if (/^(OK \(|FAILURES!!!|Tests run:|Time:|\d+\) )/.test(line) || /Error in |AssertionError/.test(line)) console.log(line);
  }
  if (!ok) process.exitCode = 1;
}

const connected = spawnSync(adb, ['devices'], { encoding: 'utf8' }).stdout ?? '';
if (!connected.includes(serial)) throw new Error(`ADB 未连接 ${serial}；请先 adb connect`);

// UI 用例需要真实窗口：息屏或锁屏时会报 “No compose hierarchies found”，此处直接跳过而不是假失败。
const power = spawnSync('adb', ['-s', serial, 'shell', 'dumpsys', 'power'], { encoding: 'utf8' }).stdout ?? '';
const awake = /mWakefulness=Awake/.test(power);
const wantUi = flags.includes('--ui');
if (wantUi && !awake) console.log('提示：设备未亮屏，跳过 UI 套件（请解锁后加 --ui 重跑）。');

// 每个测试类各起一个 instrument 进程：本地总结相关用例会创建/杀死 :summary 绑定进程，
// ClientUiTest 会拉起真实 MainActivity，混在同一进程里会互相干扰（超时或 Snapshot 误报）。
const classes = [...nonUi, ...(flags.includes('--recording') ? recording : []), ...(wantUi && awake ? ui : [])];
for (const className of classes) run([className], className);
console.log(process.exitCode ? '\n有失败项。' : '\n全部通过。');
