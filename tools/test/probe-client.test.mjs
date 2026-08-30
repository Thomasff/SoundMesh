import assert from 'node:assert/strict';
import test from 'node:test';
import { createProbeClient } from '../src/probe-client.mjs';

test('uses explicit argument arrays for install, start, and private JSON export', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '{"state":"COMPLETE"}', stderr: '' }; } });
  await client.install({ serial: 'serial;bad', apkPath: 'probe apk.apk' });
  await client.start({ serial: 'serial;bad', sessionId: 's 1', caseId: 'C1', durationSeconds: 20, expectedPackage: 'com.netease.cloudmusic' });
  await client.readStatus({ serial: 'serial;bad', caseId: 'C1' });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['install', '-r', 'probe apk.apk'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.MainActivity', '--es', 'session_id', 's 1', '--es', 'case_id', 'C1', '--ei', 'duration_seconds', '20', '--es', 'expected_package', 'com.netease.cloudmusic'],
    ['exec-out', 'run-as', 'com.soundmesh.probe', 'cat', 'files/runs/C1/status.json']
  ]);
  assert.ok(calls.every(({ serial }) => serial === 'serial;bad'));
});

test('exports WAV bytes without decoding stdout as text', async () => {
  const bytes = Buffer.from([0, 255, 82, 73, 70, 70]);
  let written;
  const client = createProbeClient({ runAdbBinary: async call => ({ exitCode: 0, stdout: bytes, stderr: Buffer.alloc(0), call }), writeBinary: async (_path, data) => { written = data; } });
  await client.exportWav({ serial: 'device-1', caseId: 'C1', path: 'capture.wav' });
  assert.deepEqual(written, bytes);
});
