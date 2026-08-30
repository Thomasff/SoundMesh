import assert from 'node:assert/strict';
import test from 'node:test';
import { driveProbeStates, runCaptureCase } from '../src/case-runner.mjs';
import { requireAuthorizedSerial } from '../src/adb.mjs';

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
  const states = [{ state: 'SESSION_READY' }, { state: 'COMPLETE' }]; const prompts = []; const outcome = await runCaptureCase({ serial: 'confirmed', apkPath: 'probe.apk', probe: { install: async () => {}, start: async () => {}, readStatus: async () => states.shift(), readCapture: async () => ({ expectedBytes: 4, capturedBytes: 4, actualFormat: { sampleRate: 48_000, channelCount: 2, encoding: 'PCM16' } }), exportWav: async () => {} }, acknowledge: async text => { prompts.push(text); }, analyze: () => ({ outcome: 'PASS' }), wavPath: 'capture.wav', readWav: async () => Buffer.alloc(0), maxPolls: 2, deviceAlias: 'Honor X10', appName: 'NetEase Cloud Music', probeCase: { caseId: 'C1', durationSeconds: 20, expectedPackage: 'com.netease.cloudmusic' } });
  assert.equal(prompts.length, 1); assert.match(prompts[0], /^HUMAN ACTION:/); assert.deepEqual(outcome, { caseId: 'C1', appId: 'com.netease.cloudmusic', outcome: 'PASS' });
});
