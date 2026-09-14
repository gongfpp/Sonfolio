#!/usr/bin/env node
// Read-only verification of the installed APK and pre-upgrade original WAV manifest.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';

const [serial, backupDirectory, apk] = process.argv.slice(2);
if (!serial?.includes(':') || !backupDirectory || !apk) throw new Error('用法：node scripts/verify-test-device.mjs TCP设备 私密备份目录 APK路径');
const adb = process.env.ADB ?? 'adb';
const pkg = 'com.gongfpp.sonfolio';
function run(args) {
  const r = spawnSync(adb, ['-s', serial, ...args], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
  if (r.status !== 0) throw new Error('设备只读核对失败，请确认 TCP ADB 连接');
  return r.stdout;
}
if (run(['get-state']).trim() !== 'device') throw new Error('TCP ADB 未就绪');
const parse = text => new Map(text.trim().split('\n').filter(Boolean).map(line => {
  const match = line.match(/^([0-9a-f]{64})\s+(.+)$/);
  if (!match) throw new Error('原音哈希清单格式错误');
  return [match[2].trim(), match[1]];
}));
const before = parse(readFileSync(join(backupDirectory, 'wav-sha256.txt'), 'utf8'));
const after = parse(run(['shell', 'run-as', pkg, 'sh', '-c', "'find files/recordings -type f -name \"*.wav\" -exec sha256sum {} \\;'" ]));
const missing = [...before.keys()].filter(path => !after.has(path));
const changed = [...before].filter(([path, hash]) => after.has(path) && after.get(path) !== hash);
console.log(`原有 WAV：${before.size} 份；缺失 ${missing.length} 份；内容变化 ${changed.length} 份；新增 ${[...after.keys()].filter(path => !before.has(path)).length} 份`);
if (missing.length || changed.length) throw new Error('原音完整性检查未通过，不输出私人文件名');
const remotePath = run(['shell', 'pm', 'path', pkg]).trim().split('\n')[0]?.replace(/^package:/, '');
if (!remotePath?.match(/^\/data\/app\/[A-Za-z0-9_./+=-]+\.apk$/)) throw new Error('无法核对主 APK 路径');
const localHash = createHash('sha256').update(readFileSync(apk)).digest('hex');
const installedHash = run(['shell', 'sha256sum', remotePath]).split(/\s+/)[0];
console.log(`本地 APK SHA-256：${localHash}`);
console.log(`手机 APK 与本地产物：${installedHash === localHash ? '一致' : '不一致'}`);
if (installedHash !== localHash) throw new Error('手机尚未安装此 APK，不能宣称已交付');
console.log(run(['shell', 'dumpsys', 'package', pkg]).split('\n').filter(line => /versionCode=|versionName=|lastUpdateTime=/.test(line)).map(line => line.trim()).join('\n'));
