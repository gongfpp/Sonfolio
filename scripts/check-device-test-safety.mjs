#!/usr/bin/env node
// 静态扫描 androidTest，拦截会触碰真实用户数据的写法。
// 背景：AudioCompressionIntegrationTest 曾在真实 files/recordings 里建文件并 deleteRecursively，
// 每次跑真机套件都会删光用户录音。仪器测试与主应用同进程、同数据目录，必须只用自己的沙箱目录。
//
// 用法：node scripts/check-device-test-safety.mjs   （有 ERROR 时退出码非 0）
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = 'android/app/src/androidTest/java';
const errors = [];
const warnings = [];

function walk(dir) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) walk(path);
    else if (path.endsWith('.kt')) inspect(path);
  }
}

function inspect(path) {
  const text = readFileSync(path, 'utf8');
  const rel = path.replace(`${root}/`, '');
  // 1) 直接使用真实录音目录 —— 一律禁止。
  if (/filesDir\s*,\s*["']recordings["']/.test(text)) {
    errors.push(`${rel}: 使用了真实的 files/recordings 目录；测试请改用 context.cacheDir 下的唯一沙箱目录`);
  }
  // 2) 递归删除一个「固定名字」的 filesDir 目录 —— 会删到用户真实数据。
  //    统一文件/唯一沙箱（名字含 System.nanoTime/UUID/qa-）的单文件删除是安全的，不拦。
  const uniqueMarker = /nanoTime|randomUUID|UUID\.|qa-|tmp|cacheDir/;
  const filesDirVars = new Map();
  for (const line of text.split('\n')) {
    const m = line.match(/(?:val|var)\s+(\w+)\s*=\s*File\([^)]*filesDir/);
    if (m) filesDirVars.set(m[1], line.trim());
  }
  for (const [name, def] of filesDirVars) {
    if (new RegExp(`\\b${name}\\s*\\.\\s*deleteRecursively\\s*\\(`).test(text) && !uniqueMarker.test(def)) {
      errors.push(`${rel}: 对固定的 filesDir 目录 '${name}' 调用 deleteRecursively；测试目录必须带唯一标识（System.nanoTime/UUID/qa-）`);
    }
  }
  // 3) 其余 deleteRecursively 只提示，方便人工复核。
  if (/deleteRecursively\s*\(/.test(text)) {
    warnings.push(`${rel}: 含 deleteRecursively，请确认目标仅是自己创建的沙箱目录`);
  }
}

walk(root);
for (const w of warnings) console.log(`WARN  ${w}`);
for (const e of errors) console.error(`ERROR ${e}`);
if (errors.length) {
  console.error(`\n发现 ${errors.length} 处会触碰真实用户数据的测试写法；已中止。`);
  process.exit(1);
}
console.log('真机测试安全性检查通过。');
