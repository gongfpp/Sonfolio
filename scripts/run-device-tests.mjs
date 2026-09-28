#!/usr/bin/env node
// Instrumentation targets the isolated QA application, never the personal app.
import { spawnSync } from 'node:child_process';

const [serial, ...flags] = process.argv.slice(2);
if (!serial || !/^[\w.-]+:\d+$/.test(serial)) throw new Error('请提供 TCP 真机序列号：node scripts/run-device-tests.mjs IP:端口 [--ui] [--recording] [--quality]');
const adb = process.env.ADB ?? 'adb';
const pkg = 'com.gongfpp.sonfolio.qa';
const namespace = 'com.gongfpp.sonfolio';
const runner = `${pkg}.test/com.gongfpp.sonfolio.QaTestRunner`;
function command(args) {
  const r = spawnSync(adb, ['-s', serial, ...args], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  if (r.error || r.status !== 0) throw new Error(`ADB 检查失败，停止测试：${r.error?.message ?? r.stderr}`);
  return r.stdout;
}
if (command(['get-state']).trim() !== 'device') throw new Error('真机未连接，请连接并解锁');
if (command(['shell', 'getprop', 'ro.kernel.qemu']).trim() === '1') throw new Error('禁止使用 Android 模拟器');
const safety = spawnSync(process.execPath, ['scripts/check-device-test-safety.mjs'], { encoding: 'utf8' });
process.stdout.write(safety.stdout ?? ''); process.stderr.write(safety.stderr ?? '');
if (safety.status !== 0) throw new Error('测试安全检查未通过');
const instrumentation = command(['shell', 'pm', 'list', 'instrumentation']);
if (!instrumentation.split('\n').some(line => line.includes(`instrumentation:${runner} (target=${pkg})`))) {
  throw new Error('未安装隔离 QA 套件。请构建并安装 assembleQa、assembleQaAndroidTest；禁止运行主应用测试包。');
}

// Read-only guard: hash names AND contents before/after EACH class; unreadable is blocked.
function personalSnapshot() {
  const root = 'com.gongfpp.sonfolio';
  const services = command(['shell', 'dumpsys', 'activity', 'services', root]);
  if (/ServiceRecord[^\n]*com\.gongfpp\.sonfolio\/\.recording\.RecordingService/.test(services)) {
    throw new Error('个人主应用正在录音，请先停止；未启动任何测试');
  }
  const text = command(['shell', `run-as ${root} sh -c 'if [ -d files/recordings ]; then find files/recordings -type f -exec sha256sum {} + || exit 1; else echo NO_RECORDINGS; fi; echo SNAPSHOT_END'`]);
  if (!text.trim() || /Permission denied|not debuggable|No such package|sha256sum:/.test(text)) throw new Error('无法核对主应用录音，已阻塞测试');
  return text.trim().split('\n').sort().join('\n');
}
const before = personalSnapshot();
const nonUi = ['MigrationIntegrationTest', 'RemoteAsrCheckpointTest', 'SearchRepositoryIntegrationTest', 'PipelineIntegrationTest', 'BackupIntegrationTest', 'RawCleanupIntegrationTest', 'ConversationEditIntegrationTest', 'SummaryIntegrationTest', 'RecordingZoneIntegrationTest', 'RecordingGapIntegrationTest', 'AudioCompressionIntegrationTest', 'LocalSummaryRuntimeTest', 'Qwen3AsrZhEnQualityTest', 'FireRedAsrZhEnQualityTest'];
if (flags.includes('--ui') && !/mWakefulness=Awake/.test(command(['shell', 'dumpsys', 'power']))) throw new Error('手机未亮屏，UI 验收阻塞，请解锁');
const classes = [...nonUi, ...(flags.includes('--recording') ? ['RecordingReliabilityTest'] : []), ...(flags.includes('--ui') ? ['SearchResultsUiTest', 'ClientUiTest', 'ExperienceAcceptanceTest', 'PublicScreenshotsTest'] : []), ...(flags.includes('--quality') ? ['LocalSummaryQualityTest'] : [])];
let skipped = 0;
for (const name of classes) {
  let output;
  try {
    output = command(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', `${namespace}.${name}`, runner]);
  } finally {
    if (personalSnapshot() !== before) throw new Error('个人录音文件或哈希发生变化，立即停止；不得继续运行套件');
  }
  const skipCount = (output.match(/INSTRUMENTATION_STATUS_CODE: -[34]/g) ?? []).length;
  skipped += skipCount;
  const failed = /FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[12]/.test(output);
  const completed = /OK \(\d+ tests?\)/.test(output);
  console.log(`${name}: ${failed ? '失败' : !completed ? '阻塞' : skipCount ? `完成，${skipCount} 项跳过` : '通过'}`);
  if (failed || !completed) {
    console.error(output.slice(-12000)); process.exitCode = 1; break;
  }
}
console.log(process.exitCode ? '已停止，请先修复失败项。' : `套件结束；跳过 ${skipped} 项（跳过不代表通过）。`);
