#!/usr/bin/env node
// Creates private release signing material outside the repository. Never prints passwords.
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, readdirSync, realpathSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const target = process.argv[2];
if (!target || !isAbsolute(target)) {
  throw new Error('用法：JAVA_HOME=<JDK 路径> node scripts/create-release-key.mjs <仓库外的绝对目录>');
}
const repository = realpathSync(resolve(dirname(fileURLToPath(import.meta.url)), '..'));
const requested = resolve(target);
if (requested === repository || requested.startsWith(repository + sep)) {
  throw new Error('签名材料必须保存在仓库外。');
}
mkdirSync(requested, { recursive: true, mode: 0o700 });
const directory = realpathSync(requested);
if (directory === repository || directory.startsWith(repository + sep)) {
  throw new Error('不能通过符号链接将签名材料写入仓库。');
}
const keystore = join(directory, 'sonfolio-release.p12');
const properties = join(directory, 'signing.properties');
if (existsSync(keystore) || existsSync(properties)) {
  throw new Error('已有签名材料；拒绝覆盖或重新生成，以免后续版本无法更新。');
}
if (readdirSync(directory).length !== 0 || repository.startsWith(directory + sep)) {
  throw new Error('请选择专门用于签名的空目录，不能使用现有数据目录或仓库的上级目录。');
}
const keytool = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'keytool') : 'keytool';
const check = spawnSync(keytool, ['-help'], { stdio: 'ignore' });
if (check.status !== 0) throw new Error('未找到可用的 keytool，请配置 JAVA_HOME。');
chmodSync(directory, 0o700);
process.umask(0o077);
const password = randomBytes(48).toString('base64url');
const propertyValue = value => value.replace(/\\/g, '\\\\').replace(/\r/g, '\\r').replace(/\n/g, '\\n');
// Save the password before invoking keytool so an interrupted generation never loses it.
writeFileSync(properties, [
  `storeFile=${propertyValue(keystore)}`,
  'storeType=PKCS12',
  `storePassword=${password}`,
  'keyAlias=sonfolio-release',
  `keyPassword=${password}`,
  '',
].join('\n'), { flag: 'wx', mode: 0o600 });
const generated = spawnSync(keytool, [
  '-genkeypair', '-noprompt', '-keystore', keystore,
  '-storetype', 'PKCS12', '-alias', 'sonfolio-release',
  '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA',
  '-validity', '10950', '-dname', 'CN=Sonfolio Release, O=Sonfolio',
  '-storepass:env', 'SONFOLIO_KEY_PASSWORD', '-keypass:env', 'SONFOLIO_KEY_PASSWORD',
], { env: { ...process.env, SONFOLIO_KEY_PASSWORD: password }, stdio: 'inherit' });
if (generated.status !== 0) {
  throw new Error('证书生成失败；已保留私密配置以便恢复，请勿自动重建。');
}
chmodSync(keystore, 0o600);
const verified = spawnSync(keytool, [
  '-list', '-v', '-keystore', keystore, '-alias', 'sonfolio-release',
  '-storepass:env', 'SONFOLIO_KEY_PASSWORD',
], { env: { ...process.env, SONFOLIO_KEY_PASSWORD: password }, stdio: 'inherit' });
if (verified.status !== 0) throw new Error('证书生成后校验失败。');
console.log(`签名材料已生成并校验：${directory}`);
console.log('请将整个目录加密备份。不要上传 Git，不要只备份证书而遗漏密码配置。');
