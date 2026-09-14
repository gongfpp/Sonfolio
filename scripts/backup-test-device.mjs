#!/usr/bin/env node
// Private pre-migration diagnostic backup, not the in-app user backup feature.
import { spawnSync } from 'node:child_process';
import { mkdirSync, openSync, closeSync, writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const adb = process.env.ADB ?? 'adb';
const serial = process.argv[2];
if (!serial) throw new Error('请指定 adb 设备序列号。');
const pkg = 'com.gongfpp.sonfolio';
const run = args => {
  const result = spawnSync(adb, ['-s', serial, ...args], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
  if (result.status !== 0) throw new Error(`ADB 操作失败：${result.stderr}`);
  return result.stdout;
};
const services = run(['shell', 'dumpsys', 'activity', 'services', pkg]);
if (/recording\.RecordingService/.test(services)) throw new Error('存在录音服务，拒绝停止应用或迁移；请先由用户结束录音。');
run(['shell', 'am', 'force-stop', pkg]);
const directory = mkdtempSync(join(tmpdir(), 'sonfolio-pre-migration-'));
mkdirSync(join(directory, 'metadata'), { mode: 0o700 });
const fd = openSync(join(directory, 'metadata.tar'), 'wx', 0o600);
try {
  const result = spawnSync(adb, ['-s', serial, 'exec-out', 'run-as', pkg, 'tar', '-cf', '-', 'databases', 'shared_prefs'],
    { stdio: ['ignore', fd, 'pipe'] });
  if (result.status !== 0) throw new Error('元数据备份失败。');
} finally { closeSync(fd); }
const hashes = run(['shell', 'run-as', pkg, 'sh', '-c', "'find files/recordings -type f -name \"*.wav\" -exec sha256sum {} \\;'" ]);
writeFileSync(join(directory, 'wav-sha256.txt'), hashes, { mode: 0o600, flag: 'wx' });
const unpack = spawnSync('tar', ['-xf', join(directory, 'metadata.tar'), '-C', join(directory, 'metadata')]);
if (unpack.status !== 0) throw new Error('无法读取备份包。');
const check = spawnSync('sqlite3', [join(directory, 'metadata/databases/sonfolio.db'),
  'PRAGMA quick_check; PRAGMA user_version; SELECT count(*) FROM audio_chunks; SELECT count(*) FROM transcripts; SELECT count(*) FROM markers;'], { encoding: 'utf8' });
if (check.status !== 0 || !check.stdout.startsWith('ok\n')) throw new Error('数据库备份未通过完整性检查。');
console.log(`私密备份：${directory}`);
console.log(`校验结果（完整性、版本、原音数、转写数、标记数）：\n${check.stdout.trim()}`);
console.log(`原音哈希条目：${hashes.trim().split('\n').filter(Boolean).length}；未修改或删除任何原音。`);
