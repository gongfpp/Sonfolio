#!/usr/bin/env node
// 拉取 docs/evaluation/asr-zh-en-set.json 里的中英文素材并推送到真机，
// 供 Qwen3AsrZhEnQualityTest 做字错率验收。仅需联网与 TCP ADB，不改动应用数据。
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [serial] = process.argv.slice(2);
const adb = process.env.ADB ?? 'adb';
const pkg = 'com.gongfpp.sonfolio';
const remoteDirectory = `/sdcard/Android/data/${pkg}/files/asr-eval`;
const manifest = JSON.parse(readFileSync('docs/evaluation/asr-zh-en-set.json', 'utf8'));
const staging = join(process.env.TMPDIR ?? '/tmp', 'sonfolio-asr-eval');
mkdirSync(staging, { recursive: true });

function run(args, options = {}) {
  const result = spawnSync(adb, args, { encoding: 'utf8', ...options });
  if (result.status !== 0) throw new Error(`adb ${args.join(' ')} 失败：${result.stderr?.trim() || result.stdout?.trim()}`);
  return result.stdout;
}

// HF 直连不稳时退回镜像；素材是公开模型仓库里的固定文件。
const mirrors = url => [url, url.replace('https://huggingface.co/', 'https://hf-mirror.com/')];

for (const item of manifest.cases) {
  const target = join(staging, item.file);
  if (existsSync(target)) {
    console.log(`已存在，跳过下载：${item.file}`);
    continue;
  }
  let downloaded = false;
  for (const url of mirrors(item.url)) {
    try {
      const response = await fetch(url, { redirect: 'follow' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      writeFileSync(target, Buffer.from(await response.arrayBuffer()));
      console.log(`已下载：${item.file}（${url.split('/')[2]}）`);
      downloaded = true;
      break;
    } catch (error) {
      console.warn(`下载失败 ${url}：${error.message}`);
    }
  }
  if (!downloaded) throw new Error(`无法下载 ${item.file}，请检查网络`);
}

const deviceArgs = serial ? ['-s', serial] : [];
if (run([...deviceArgs, 'get-state']).trim() !== 'device') throw new Error('TCP ADB 未就绪，请先连接真机');
run([...deviceArgs, 'shell', 'mkdir', '-p', remoteDirectory]);
for (const item of manifest.cases) {
  run([...deviceArgs, 'push', join(staging, item.file), `${remoteDirectory}/${item.file}`, '-f']);
}
console.log(`已推送 ${manifest.cases.length} 份素材到 ${remoteDirectory}`);
console.log(`在手机上运行：adb shell am instrument -e class com.gongfpp.sonfolio.Qwen3AsrZhEnQualityTest -w ${pkg}.test/androidx.test.runner.AndroidJUnitRunner`);
