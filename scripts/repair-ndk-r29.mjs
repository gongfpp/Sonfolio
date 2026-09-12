// Restore only missing build metadata and linker from the official r29 ZIP.
// Uses bounded byte ranges, never downloads the whole 1 GB distribution.
import fs from 'node:fs/promises';
import path from 'node:path';
import zlib from 'node:zlib';
const root = path.resolve(process.argv[2] || '');
if (!root.endsWith('/ndk/29.0.14206865')) throw Error('Pass the exact existing NDK 29.0.14206865 directory');
const pkg = await fs.readFile(path.join(root, 'package.xml'), 'utf8');
if (!pkg.includes('14206865')) throw Error('NDK package revision mismatch');
const url = 'https://dl.google.com/android/repository/android-ndk-r29-darwin.zip';
const head = await fetch(url, { method: 'HEAD', headers: {'Accept-Encoding':'identity'}, signal: AbortSignal.timeout(20000) });
const size = Number(head.headers.get('content-length'));
if (!head.ok || size !== 1049519838) throw Error('Official r29 archive changed; stop and verify it');
let downloaded = 0;
async function range(start, end) {
  if (end - start + 1 + downloaded > 56_000_000) throw Error('Download budget exceeded');
  const r = await fetch(url, {headers:{Range:`bytes=${start}-${end}`,'Accept-Encoding':'identity'},signal:AbortSignal.timeout(60000)});
  if (r.status !== 206 || Number(r.headers.get('content-length')) !== end-start+1) {
    await r.body?.cancel(); throw Error('Server ignored bounded range');
  }
  const b=Buffer.from(await r.arrayBuffer());downloaded+=b.length;return b;
}
const tail=await range(size-65536,size-1);
const end=tail.lastIndexOf(Buffer.from([0x50,0x4b,0x05,0x06]));
if(end<0)throw Error('Invalid ZIP directory');
const offset=tail.readUInt32LE(end+16), length=tail.readUInt32LE(end+12);
const directory=await range(offset,offset+length-1);
for(let at=0;at<directory.length;) {
  if(directory.readUInt32LE(at)!==0x02014b50)throw Error('Invalid ZIP entry');
  const n=directory.readUInt16LE(at+28),x=directory.readUInt16LE(at+30),c=directory.readUInt16LE(at+32);
  const name=directory.subarray(at+46,at+46+n).toString().replace(/^android-ndk-r29\//,'');
  const target=path.resolve(root,name);
  const wanted=/^(build\/cmake\/|meta\/|source\.properties$|NOTICE$|toolchains\/llvm\/prebuilt\/darwin-x86_64\/bin\/lld$)/.test(name) && !name.endsWith('/');
  if(wanted && target.startsWith(root+'/')) {
    const exists=await fs.lstat(target).then(()=>true,()=>false);
    if(!exists) {
      const localOffset=directory.readUInt32LE(at+42),bytes=directory.readUInt32LE(at+20);
      const header=await range(localOffset,localOffset+29);
      const start=localOffset+30+header.readUInt16LE(26)+header.readUInt16LE(28);
      const compressed=await range(start,start+bytes-1);
      const data=directory.readUInt16LE(at+10)===8?zlib.inflateRawSync(compressed):compressed;
      if((zlib.crc32(data)>>>0)!==directory.readUInt32LE(at+16))throw Error('CRC mismatch');
      await fs.mkdir(path.dirname(target),{recursive:true});await fs.writeFile(target,data,{flag:'wx'});
      if(name.endsWith('/bin/lld'))await fs.chmod(target,0o755);
      console.log('Restored',name);
    }
  }
  at+=46+n+x+c;
}
const clang=path.join(root,'toolchains/llvm/prebuilt/darwin-x86_64/bin/clang');
try{await fs.lstat(clang);}catch{await fs.symlink('clang-21',clang);}
console.log('Downloaded bytes:',downloaded,'No model weights downloaded.');
