#!/usr/bin/env node
// 真机仪器测试入口。CI 没有真机，只编译 androidTest；本脚本把手工验收命令固化下来，
// 避免 androidTest 长期只编译不运行。用法：
//   node scripts/run-device-tests.mjs <ADB序列号>            # 非 UI 套件（息屏也能跑）
//   node scripts/run-device-tests.mjs <ADB序列号> --ui       # 追加 Compose UI 套件（需亮屏解锁）
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

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
  'FireRedAsrZhEnQualityTest', // 同上
];
// 需要真实麦克风与厂商权限，只有显式 --recording 时才跑（会等待录音状态，常超时）。
const recording = ['RecordingReliabilityTest'];
// 需要亮屏且解锁；息屏时会报 “No compose hierarchies found”。
const ui = ['SearchResultsUiTest', 'ClientUiTest', 'ExperienceAcceptanceTest', 'PublicScreenshotsTest'];
// 20 段 × 0.5B 的本地总结质量报告，耗时很长且结果受模型波动影响，只有显式 --quality 才跑。
const quality = ['LocalSummaryQualityTest'];

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

// 静态安全检查：拦截会写入/删除真实 filesDir（尤其 files/recordings）的测试写法。
const safety = spawnSync(process.execPath, ['scripts/check-device-test-safety.mjs'], { encoding: 'utf8' });
process.stdout.write(safety.stdout ?? '');
process.stderr.write(safety.stderr ?? '');
if (safety.status !== 0) throw new Error('真机测试安全静态检查未通过，已中止：避免再次破坏真实用户数据');

// 运行前后核对真实录音清单：任何原有文件消失都说明测试在删用户数据。
function recordingsFiles() {
  const result = spawnSync(adb, ['-s', serial, 'shell',
    "run-as com.gongfpp.sonfolio sh -c 'find files/recordings -type f 2>/dev/null'"], { encoding: 'utf8' });
  return new Set((result.stdout ?? '').trim().split('\n').filter(Boolean));
}
const recordingsBefore = recordingsFiles();

// 同时核对真实数据库行数：测试若删了对话/转写/标记/切片，也立刻报错。取不到 sqlite3 时跳过。
function dbRowCounts() {
  const dir = mkdtempSync(join(tmpdir(), 'sonfolio-db-'));
  try {
    for (const suffix of ['', '-wal', '-shm']) {
      const r = spawnSync(adb, ['-s', serial, 'exec-out', 'run-as', pkg, 'cat', `databases/sonfolio.db${suffix}`],
        { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 });
      if (r.status === 0 && r.stdout?.length) writeFileSync(join(dir, `sonfolio.db${suffix}`), r.stdout);
    }
    const query = "SELECT (SELECT count(*) FROM conversations)||','||(SELECT count(*) FROM transcripts)" +
      "||','||(SELECT count(*) FROM markers)||','||(SELECT count(*) FROM audio_chunks);";
    const r = spawnSync('sqlite3', [join(dir, 'sonfolio.db'), query], { encoding: 'utf8' });
    if (r.status !== 0) return null;
    return r.stdout.trim().split(',').map(Number);
  } finally { rmSync(dir, { recursive: true, force: true }); }
}
const rowsBefore = dbRowCounts();
if (rowsBefore == null) console.log('提示：未找到 sqlite3，本次跳过数据库行数核对。');

// UI 用例需要真实窗口：息屏或锁屏时会报 “No compose hierarchies found”，此处直接跳过而不是假失败。
const power = spawnSync('adb', ['-s', serial, 'shell', 'dumpsys', 'power'], { encoding: 'utf8' }).stdout ?? '';
const awake = /mWakefulness=Awake/.test(power);
const wantUi = flags.includes('--ui');
if (wantUi && !awake) console.log('提示：设备未亮屏，跳过 UI 套件（请解锁后加 --ui 重跑）。');

// 每个测试类各起一个 instrument 进程：本地总结相关用例会创建/杀死 :summary 绑定进程，
// ClientUiTest 会拉起真实 MainActivity，混在同一进程里会互相干扰（超时或 Snapshot 误报）。
const classes = [...nonUi, ...(flags.includes('--recording') ? recording : []), ...(wantUi && awake ? ui : []), ...(flags.includes('--quality') ? quality : [])];
for (const className of classes) run([className], className);

const recordingsAfter = recordingsFiles();
const removed = [...recordingsBefore].filter(file => !recordingsAfter.has(file));
if (removed.length) {
  console.error(`\n⚠️ 真机套件删除了 ${removed.length} 个原有录音文件（例如 ${removed.slice(0, 3).join(', ')}）。` +
    '这是测试缺陷，禁止在个人手机上继续重跑，请先修复对应测试。');
  process.exitCode = 1;
}
const rowsAfter = dbRowCounts();
if (rowsBefore && rowsAfter) {
  const names = ['对话', '转写', '标记', '切片'];
  const shrunk = rowsAfter.map((n, i) => [names[i], rowsBefore[i], n]).filter(([, b, a]) => a < b);
  if (shrunk.length) {
    console.error(`\n⚠️ 真机套件删除了真实数据库记录：${shrunk.map(([n, b, a]) => `${n} ${b}→${a}`).join('，')}。` +
      '这是测试缺陷，请先修复对应测试。');
    process.exitCode = 1;
  }
}
console.log(process.exitCode ? '\n有失败项。' : '\n全部通过。');
