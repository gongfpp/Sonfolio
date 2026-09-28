import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

// CLI contract fixtures only. No phone, real ADB, personal files or database is accessed.
function exercise(mode, flags = []) {
  const root = mkdtempSync(join(tmpdir(), 'sonfolio-guard-fixture-'));
  try {
    const executable = join(root, 'adb-fixture.mjs');
    const counter = join(root, 'calls.json');
    writeFileSync(executable, `#!/usr/bin/env node
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
const args = process.argv.slice(2).join(' ');
const mode = process.env.GUARD_CASE;
const file = process.env.GUARD_COUNTER;
const calls = existsSync(file) ? JSON.parse(readFileSync(file)) : { snapshots: 0, tests: 0 };
function output(text) { console.log(text); writeFileSync(file, JSON.stringify(calls)); }
if (args.endsWith('get-state')) output(mode === 'offline' ? 'offline' : 'device');
else if (args.includes('ro.kernel.qemu')) output(mode === 'emulator' ? '1' : '');
else if (args.includes('pm list instrumentation')) output('instrumentation:com.gongfpp.sonfolio.qa.test/com.gongfpp.sonfolio.QaTestRunner (target=' + (mode === 'wrong-target' ? 'com.gongfpp.sonfolio' : 'com.gongfpp.sonfolio.qa') + ')');
else if (args.includes('dumpsys activity services')) output('');
else if (args.includes('dumpsys power')) output(mode === 'locked' ? 'mWakefulness=Asleep' : 'mWakefulness=Awake');
else if (args.includes('dumpsys window policy')) output('KeyguardServiceDelegate\\n showing=' + (mode === 'awake-locked' ? 'true' : 'false'));
else if (args.includes('run-as')) {
  calls.snapshots++;
  if (mode === 'unreadable') process.exit(1);
  if (mode === 'truncated') output('NO_RECORDINGS');
  else if (mode === 'delete' && calls.snapshots > 1) output('SNAPSHOT_END');
  else output((mode === 'overwrite' && calls.snapshots > 1 ? 'b' : 'a').repeat(64) + '  files/recordings/fixture.wav\\nSNAPSHOT_END');
} else if (args.includes('am instrument')) { calls.tests++; output('OK (1 test)'); }
else { console.error('Unexpected command: ' + args); process.exit(1); }
`, { mode: 0o700 });
    const result = spawnSync(process.execPath, ['scripts/run-device-tests.mjs', '192.0.2.1:5555', ...flags], {
      encoding: 'utf8', env: { ...process.env, ADB: executable, GUARD_CASE: mode, GUARD_COUNTER: counter },
    });
    return { ...result, calls: JSON.parse(readFileSync(counter)) };
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('unchanged snapshot passes without requiring sqlite3', () => {
  const result = exercise('normal', ['--class=MigrationIntegrationTest']);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.calls.tests, 1);
});
for (const mode of ['delete', 'overwrite']) test(`${mode} stops immediately after first class`, () => {
  const result = exercise(mode);
  assert.notEqual(result.status, 0);
  assert.equal(result.calls.tests, 1);
  assert.match(result.stderr, /立即停止/);
});
for (const mode of ['unreadable', 'truncated', 'offline', 'emulator', 'wrong-target', 'locked', 'awake-locked']) test(`${mode} blocks before instrumentation`, () => {
  const result = exercise(mode, ['--ui']);
  assert.notEqual(result.status, 0);
  assert.equal(result.calls.tests, 0);
});
