import assert from 'node:assert/strict';
import test from 'node:test';
import { driveProbeStates, runCaptureCase } from '../src/case-runner.mjs';
import { requireAuthorizedSerial } from '../src/adb.mjs';
import { main as runCaseCli } from '../src/cli/run-case.mjs';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

test('waits for the user only at permission and playback checkpoints', async () => {
  const prompts = []; const outcome = await driveProbeStates(['AWAITING_PERMISSION', 'SESSION_READY', 'CAPTURING', 'COMPLETE'], async prompt => prompts.push(prompt));
  assert.deepEqual(prompts, ['Approve MediaProjection on the phone']); assert.equal(outcome.state, 'COMPLETE');
});

for (const [name, states, message] of [
  ['unauthorized device', [], 'unauthorized'], ['probe failure', ['FAILED'], 'Probe failed'], ['timeout', ['CAPTURING'], 'timed out'], ['volume restore required', ['VOLUME_RESTORE_REQUIRED'], 'volume restoration']
]) test(`fails closed for ${name}`, async () => await assert.rejects(() => driveProbeStates(states, async () => {}, { maxPolls: 1, deviceState: name === 'unauthorized device' ? 'unauthorized' : 'device' }), new RegExp(message, 'i')));

test('rejects two authorized devices without an explicit serial', () => {
  assert.throws(() => requireAuthorizedSerial([{ serial: 'one', state: 'device' }, { serial: 'two', state: 'device' }]), /explicit serial/i);
});

test('runs playback checkpoint one action at a time and returns redacted outcome', async () => {
  const states = [{ state: 'AWAITING_PERMISSION' }, { state: 'AWAITING_PERMISSION' }, { state: 'SESSION_READY' }, { state: 'COMPLETE' }]; const prompts = []; const outcome = await runCaptureCase({ serial: 'confirmed', apkPath: 'probe.apk', probe: { install: async () => {}, start: async () => {}, readStatus: async () => states.shift(), readCapture: async () => ({ expectedBytes: 4, capturedBytes: 4, actualFormat: { sampleRate: 48_000, channelCount: 2, encoding: 'PCM16' } }), exportWav: async () => {} }, acknowledge: async text => { prompts.push(text); }, analyze: () => ({ outcome: 'PASS', reasons: [], format: { sampleRate: 48_000 }, dataBytes: 4, nonZeroRatio: 1, maxOneSecondPeakDbfs: -12, maxOneSecondRmsDbfs: -20, longestZeroWindowFrames: 0 }), wavPath: 'capture.wav', readWav: async () => Buffer.alloc(0), maxPolls: 4, deviceAlias: 'Honor X10', appName: 'NetEase Cloud Music', probeCase: { caseId: 'C1', durationSeconds: 20, expectedPackage: 'com.netease.cloudmusic' } });
  assert.equal(prompts.length, 2); assert.match(prompts[0], /^HUMAN ACTION: Approve/); assert.match(prompts[1], /^HUMAN ACTION: On Honor/); assert.deepEqual(outcome, { caseId: 'C1', appId: 'com.netease.cloudmusic', outcome: 'PASS', expectedBytes: 4, capturedBytes: 4, reasons: [], format: { sampleRate: 48_000 }, dataBytes: 4, nonZeroRatio: 1, maxOneSecondPeakDbfs: -12, maxOneSecondRmsDbfs: -20, longestZeroWindowFrames: 0 });
});

test('polls through missing status and capturing without delaying tests', async () => {
  const statuses = [new (class extends Error { constructor() { super(); this.code = 'NOT_READY'; } })(), { state: 'AWAITING_PERMISSION' }, { state: 'CAPTURING' }, { state: 'CAPTURING' }, { state: 'COMPLETE' }];
  const prompts = []; const result = await runCaptureCase({ serial: 's', apkPath: 'p', probe: { install: async () => {}, start: async () => {}, readStatus: async () => { const value = statuses.shift(); if (value instanceof Error) throw value; return value; }, readCapture: async () => ({ expectedBytes: 1, capturedBytes: 1 }), exportWav: async () => {} }, acknowledge: async p => prompts.push(p), pollDelay: async () => {}, analyze: () => ({ outcome: 'PASS' }), wavPath: 'x', readWav: async () => Buffer.alloc(0), probeCase: { caseId: 'C1', expectedPackage: 'pkg' } });
  assert.equal(prompts.length, 1); assert.equal(result.outcome, 'PASS');
});

test('CLI revalidates its confirmed serial and writes only a redacted outcome artifact', async () => {
  const root = await mkdtemp(join(tmpdir(), 'soundmesh-case-')); const selectionPath = join(root, 'selected-device.json'); await writeFile(selectionPath, JSON.stringify({ serial: 'secret', fingerprintHash: 'device-hash' }));
  let calls = 0;
  await runCaseCli(['--app', 'com.netease.cloudmusic', '--case', 'C1', '--apk', 'probe.apk'], { paths: { root, selectionPath }, loadSerial: async () => { calls++; return 'confirmed'; }, runCase: async options => ({ caseId: options.probeCase.caseId, appId: options.probeCase.expectedPackage, outcome: 'PASS', verifier: { serial: 'secret', reasons: [] } }), acknowledge: async () => {} });
  assert.equal(calls, 1);
  const artifact = await readFile(join(root, 'feasibility', 'device-hash', 'com.netease.cloudmusic', 'C1', 'case-outcome.json'), 'utf8');
  assert.doesNotMatch(artifact, /secret/); assert.match(artifact, /"reasons": \[\]/);
});
