#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync, statSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
const run = (command, args) => execFileSync(command, args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
run('git', ['diff', '--check']);
if (!readFileSync('LICENSE', 'utf8').includes('GNU GENERAL PUBLIC LICENSE')) throw Error('缺少 GPL 许可');
const forbidden = /(?:^|\/)(?:signing\.properties|local\.properties|\.env|[^/]+\.(?:p12|keystore|jks|db|wav|apk))$/i;
const current = run('git', ['ls-files', '-z']).split('\0').filter(Boolean);
const historic = run('git', ['rev-list', '--objects', '--all']).split('\n').map(line => line.slice(line.indexOf(' ') + 1));
const bad = [...new Set([...current, ...historic].filter(path => forbidden.test(path)))];
if (bad.length) throw Error(`当前树或历史中有敏感/构建文件路径，请人工核查：${bad.join(', ')}`);
const patterns = [/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/, /\bgh[pousr]_[a-zA-Z0-9]{30,}\b/, /\bsk-[a-zA-Z0-9]{32,}\b/, /\bAKIA[0-9A-Z]{16}\b/];
let checked = 0;
const blobs = run('git', ['rev-list', '--objects', '--all']).split('\n').filter(Boolean);
for (const line of blobs) {
  const space = line.indexOf(' '); if (space < 0) continue;
  const oid = line.slice(0, space); const path = line.slice(space + 1);
  if (!/\.(?:kt|java|js|mjs|json|ya?ml|toml|properties|md|txt|xml|sh|kts)$|(?:^|\/)\.env/.test(path)) continue;
  if (run('git', ['cat-file', '-t', oid]).trim() !== 'blob') continue;
  const body = run('git', ['cat-file', 'blob', oid]);
  if (patterns.some(pattern => pattern.test(body))) throw Error(`历史中检测到疑似密钥：${path} @ ${oid.slice(0, 8)}（不输出内容）`);
  checked++;
}
for (const path of current) {
  if (!existsSync(path) || statSync(path).size > 2_000_000) continue;
  if (patterns.some(pattern => pattern.test(readFileSync(path, 'utf8')))) throw Error(`当前文件存在疑似密钥：${path}（不输出内容）`);
}
console.log(`源码规则检查通过：历史文本对象 ${checked} 个；不代表完成专业安全审计。`);
if (!process.argv.includes('--source-only')) {
  let apk = process.argv[2] || 'android/app/build/outputs/apk/release/app-release.apk';
  if (!existsSync(apk)) {
    // 无签名配置的构建产物是 app-release-unsigned.apk；按目录里实际的 apk 解析。
    const directory = 'android/app/build/outputs/apk/release';
    const candidates = existsSync(directory) ? readdirSync(directory).filter(name => name.endsWith('.apk')) : [];
    if (candidates.length === 0) throw Error('未找到 release 构建产物 APK');
    apk = join(directory, candidates[0]);
  }
  if (statSync(apk).size > 80_000_000) throw Error('APK 超过 80 MB，请检查大模型是否误打包');
  const listing = run('unzip', ['-Z1', apk]);
  if (/\.gguf$|sense-voice-model\.int8\.onnx$/m.test(listing)) throw Error('APK 中包含应按需下载的大模型');
  if (!listing.includes('assets/licenses/sonfolio/GPL-3.0.txt')) throw Error('APK 缺少应用许可证');
  console.log(`APK 内容检查通过：${(statSync(apk).size / 1_000_000).toFixed(1)} MB；仍需单独校验签名与设备功能。`);
}
