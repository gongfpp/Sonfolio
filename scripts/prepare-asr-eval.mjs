#!/usr/bin/env node
// 拉取 docs/evaluation/asr-zh-en-set.json 里的中英文素材并推送到真机，
// 供 Qwen3AsrZhEnQualityTest 做字错率验收。仅需联网与 TCP ADB，不改动应用数据。
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
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

// 素材是公开模型仓库里的固定文件；大陆优先走镜像，失败再回退上游。
const mirrors = url => [url.replace('https://huggingface.co/', 'https://hf-mirror.com/'), url];

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

// 生产链路只处理 16 kHz 单声道 PCM16（录音格式），评测素材必须先转成同一格式。
// ffmpeg 优先，macOS 自带 afconvert 作回退。
function toProductionFormat(file) {
  const temporary = `${file}.16k.wav`;
  const ffmpeg = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', file, '-ac', '1', '-ar', '16000', '-sample_fmt', 's16', temporary], { encoding: 'utf8' });
  if (!(ffmpeg.status === 0 && existsSync(temporary))) {
    const afconvert = spawnSync('afconvert', ['-f', 'WAVE', '-d', 'LEI16@16000', '-c', '1', file, temporary], { encoding: 'utf8' });
    if (!(afconvert.status === 0 && existsSync(temporary))) throw new Error(`无法把 ${file} 转成 16 kHz 单声道；请安装 ffmpeg 后重试`);
  }
  renameSync(temporary, file);
  canonicalizeWav(file);
  return 'ffmpeg';
}

// ffmpeg/afconvert 会在 fmt 与 data 之间插入 LIST/FLLR 等 chunk，而应用内的 WavPcmReader
// 只解析标准 44 字节头（录音由 WavChunkWriter 生成，恒为该布局）。这里重写成同样布局，
// 否则素材会被当成空音频，评测结论无效。
function canonicalizeWav(file) {
  const buffer = readFileSync(file);
  if (buffer.length < 12 || buffer.toString('latin1', 0, 4) !== 'RIFF' || buffer.toString('latin1', 8, 12) !== 'WAVE') {
    throw new Error(`${file} 不是 RIFF/WAVE`);
  }
  let offset = 12, fmt = null, data = null;
  while (offset + 8 <= buffer.length) {
    const id = buffer.toString('latin1', offset, offset + 4);
    const size = buffer.readUInt32LE(offset + 4);
    if (id === 'fmt ') fmt = { offset: offset + 8, size };
    if (id === 'data') { data = { offset: offset + 8, size }; break; }
    offset += 8 + size + (size & 1);
  }
  if (!fmt || fmt.size !== 16 || !data) throw new Error(`${file} 缺少标准 PCM fmt/data chunk`);
  const output = Buffer.alloc(44 + data.size);
  output.write('RIFF', 0, 'latin1');
  output.writeUInt32LE(36 + data.size, 4);
  output.write('WAVE', 8, 'latin1');
  output.write('fmt ', 12, 'latin1');
  output.writeUInt32LE(16, 16);
  buffer.copy(output, 20, fmt.offset, fmt.offset + 16);
  output.write('data', 36, 'latin1');
  output.writeUInt32LE(data.size, 40);
  buffer.copy(output, 44, data.offset, data.offset + data.size);
  writeFileSync(file, output);
}

run([...deviceArgs, 'shell', 'mkdir', '-p', remoteDirectory]);
for (const item of manifest.cases) {
  const local = join(staging, item.file);
  const tool = toProductionFormat(local);
  run([...deviceArgs, 'push', local, `${remoteDirectory}/${item.file}`]);
  console.log(`已推送：${item.file}（已用 ${tool} 转为 16 kHz 单声道）`);
}
console.log(`已推送 ${manifest.cases.length} 份素材到 ${remoteDirectory}`);
console.log(`在手机上运行：adb shell am instrument -e class com.gongfpp.sonfolio.Qwen3AsrZhEnQualityTest -w ${pkg}.test/androidx.test.runner.AndroidJUnitRunner`);
