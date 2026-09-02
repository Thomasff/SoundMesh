import assert from 'node:assert/strict';
import test from 'node:test';
import { caseInstruction, expectedVolumeIndex, runCaptureMatrix } from '../src/capture-matrix.mjs';
import { main as runMatrixCli } from '../src/cli/run-matrix.mjs';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CASES = Object.freeze([
  { caseId: 'C2', durationSeconds: 20, volumeMode: 'MIN_NONZERO', instruction: 'Keep the same track playing.', prerequisite: 'C1' },
  { caseId: 'C3', durationSeconds: 20, volumeMode: 'ZERO', instruction: 'Keep the same track playing.', prerequisite: 'C1' }
]);

const harness = ({ cases = CASES, volumes, runCase, outcomes = { C1: 'PASS' }, unavailable = [], baselineVolume = { current: 7, max: 15 } } = {}) => {
  const prompts = []; const finished = []; const runs = [];
  const queue = [...(volumes ?? cases.map((_, index) => (index === 0 ? 1 : 0)))];
  return {
    prompts, finished, runs,
    options: {
      serial: 'confirmed', apkPath: 'probe.apk', appId: 'com.tencent.qqmusic', appName: 'QQ Music', cases, outcomes, unavailable, baselineVolume,
      probe: { finishSession: async options => { finished.push(options); } },
      acknowledge: async text => { prompts.push(text); },
      readVolume: async () => ({ current: queue.length > 1 ? queue.shift() : queue[0], max: 15 }),
      wavPathFor: caseId => `${caseId}.wav`,
      runCase: runCase ?? (async options => { runs.push(options); return { caseId: options.probeCase.caseId, outcome: 'PASS' }; })
    }
  };
};

test('derives the exact target volume index for each mode', () => {
  assert.equal(expectedVolumeIndex('UNCHANGED', { current: 7, max: 15 }), 7);
  assert.equal(expectedVolumeIndex('MIN_NONZERO', { current: 7, max: 15 }), 1);
  assert.equal(expectedVolumeIndex('ZERO', { current: 7, max: 15 }), 0);
  assert.throws(() => expectedVolumeIndex('LOUDEST', { current: 7, max: 15 }), /Unknown volume mode/);
});

test('states the app, the case, and the exact volume step in one instruction', () => {
  const text = caseInstruction({ probeCase: CASES[0], appName: 'QQ Music', targetVolume: 1, maxVolume: 15 });
  assert.match(text, /^HUMAN ACTION: \[C2\] QQ Music: /);
  assert.match(text, /Keep the same track playing\./);
  assert.match(text, /media volume to 1 of 15/);
});

test('shares one projection session across every case and finishes it exactly once', async () => {
  const { options, prompts, finished, runs } = harness();
  const result = await runCaptureMatrix({ ...options, sessionId: 'session-1' });
  assert.deepEqual(runs.map(({ sessionId }) => sessionId), ['session-1', 'session-1']);
  assert.deepEqual(runs.map(({ probeCase }) => probeCase.caseId), ['C2', 'C3']);
  assert.deepEqual(finished, [{ serial: 'confirmed', sessionId: 'session-1' }]);
  assert.equal(prompts.filter(text => text.startsWith('HUMAN ACTION: [')).length, 2);
  assert.deepEqual(result.cases.map(({ caseId, outcome }) => ({ caseId, outcome })), [{ caseId: 'C2', outcome: 'PASS' }, { caseId: 'C3', outcome: 'PASS' }]);
  assert.equal(result.aborted, false);
});

test('refuses to start a case whose media volume does not match its mode', async () => {
  const { options, runs, finished } = harness({ volumes: [9] });
  const result = await runCaptureMatrix({ ...options, sessionId: 'session-1' });
  assert.deepEqual(runs, []);
  assert.equal(result.aborted, true);
  assert.match(result.abortReason, /C2 expects media volume 1 of 15 but the device reports 9/);
  assert.equal(finished.length, 0, 'no session was opened, so none is finished');
});

test('records a failing case and continues but aborts on a device error', async () => {
  const failing = harness({ runCase: async options => (options.probeCase.caseId === 'C2' ? { caseId: 'C2', outcome: 'FAIL', reasons: ['low amplitude'] } : { caseId: 'C3', outcome: 'PASS' }) });
  const continued = await runCaptureMatrix({ ...failing.options, sessionId: 'session-1' });
  assert.deepEqual(continued.cases.map(({ outcome }) => outcome), ['FAIL', 'PASS']);
  assert.equal(continued.aborted, false);

  const erroring = harness({ runCase: async options => { if (options.probeCase.caseId === 'C2') throw new Error('Probe failed with state FAILED'); return { caseId: 'C3', outcome: 'PASS' }; } });
  const aborted = await runCaptureMatrix({ ...erroring.options, sessionId: 'session-1' });
  assert.deepEqual(aborted.cases.map(({ caseId, outcome }) => ({ caseId, outcome })), [{ caseId: 'C2', outcome: 'ERROR' }]);
  assert.equal(aborted.aborted, true);
  assert.match(aborted.abortReason, /Probe failed with state FAILED/);
  assert.equal(erroring.finished.length, 1);
});

test('skips cases whose prerequisite did not pass or that the user reports unavailable', async () => {
  const blocked = harness({ outcomes: { C1: 'FAIL' } });
  const skipped = await runCaptureMatrix({ ...blocked.options, sessionId: 'session-1' });
  assert.deepEqual(skipped.cases.map(({ caseId, outcome }) => ({ caseId, outcome })), [{ caseId: 'C2', outcome: 'SKIPPED' }, { caseId: 'C3', outcome: 'SKIPPED' }]);
  assert.deepEqual(blocked.runs, []);
  assert.equal(blocked.finished.length, 0, 'no session is opened when nothing runs');

  const missing = harness({ unavailable: ['C3'] });
  const partial = await runCaptureMatrix({ ...missing.options, sessionId: 'session-1' });
  assert.deepEqual(partial.cases.map(({ caseId, outcome }) => ({ caseId, outcome })), [{ caseId: 'C2', outcome: 'PASS' }, { caseId: 'C3', outcome: 'NOT_AVAILABLE' }]);
});

test('asks for the baseline volume back and reports whether it was restored', async () => {
  const restored = harness({ volumes: [1, 0, 7] });
  const good = await runCaptureMatrix({ ...restored.options, sessionId: 'session-1', baselineVolume: { current: 7, max: 15 } });
  assert.equal(good.volumeRestored, true);
  assert.ok(restored.prompts.some(text => /restore the media volume to 7 of 15/i.test(text)));

  const stuck = harness({ volumes: [1, 0, 3] });
  const bad = await runCaptureMatrix({ ...stuck.options, sessionId: 'session-1', baselineVolume: { current: 7, max: 15 } });
  assert.equal(bad.volumeRestored, false);
});

test('reports a failed session release without hiding the case results', async () => {
  const { options } = harness();
  const result = await runCaptureMatrix({ ...options, sessionId: 'session-1', probe: { finishSession: async () => { throw new Error('Probe finish session failed'); } } });
  assert.deepEqual(result.cases.map(({ outcome }) => outcome), ['PASS', 'PASS']);
  assert.match(result.finishError, /Probe finish session failed/);
});

test('CLI revalidates the serial once and writes only redacted per-case artifacts', async () => {
  const root = await mkdtemp(join(tmpdir(), 'soundmesh-matrix-'));
  const selectionPath = join(root, 'selected-device.json');
  await writeFile(selectionPath, JSON.stringify({ serial: 'secret', fingerprintHash: 'device-hash' }));
  const priorDirectory = join(root, 'feasibility', 'device-hash', 'com.tencent.qqmusic', 'C1');
  await mkdir(priorDirectory, { recursive: true });
  await writeFile(join(priorDirectory, 'case-outcome.json'), JSON.stringify({ caseId: 'C1', outcome: 'PASS' }));
  let serialCalls = 0; let received;
  await runMatrixCli(['--app', 'com.tencent.qqmusic', '--cases', 'C2,C5', '--unavailable', 'C5'], {
    paths: { root, selectionPath },
    loadSerial: async () => { serialCalls++; return 'confirmed'; },
    readVolume: async () => ({ current: 7, max: 15 }),
    acknowledge: async () => {},
    runMatrix: async options => {
      received = options;
      return { appId: options.appId, sessionId: 'secret-session', aborted: false, volumeRestored: true, cases: [{ caseId: 'C2', outcome: 'PASS', verifier: { serial: 'secret' }, format: { sampleRate: 48_000, channelCount: 2, bitsPerSample: 16 } }, { caseId: 'C5', outcome: 'NOT_AVAILABLE' }] };
    }
  });
  assert.equal(serialCalls, 1);
  assert.deepEqual(received.cases.map(({ caseId }) => caseId), ['C2', 'C5']);
  assert.deepEqual(received.unavailable, ['C5']);
  assert.deepEqual(received.outcomes, { C1: 'PASS' });
  assert.deepEqual(received.baselineVolume, { current: 7, max: 15 });
  const artifact = await readFile(join(root, 'feasibility', 'device-hash', 'com.tencent.qqmusic', 'C2', 'case-outcome.json'), 'utf8');
  assert.doesNotMatch(artifact, /secret/);
  const matrixArtifact = await readFile(join(root, 'feasibility', 'device-hash', 'com.tencent.qqmusic', 'matrix-outcome.json'), 'utf8');
  assert.doesNotMatch(matrixArtifact, /secret-session/);
  assert.match(matrixArtifact, /"NOT_AVAILABLE"/);
});

test('CLI rejects an unsupported app and an unknown case ID', async () => {
  const root = await mkdtemp(join(tmpdir(), 'soundmesh-matrix-'));
  const selectionPath = join(root, 'selected-device.json');
  await writeFile(selectionPath, JSON.stringify({ serial: 'secret', fingerprintHash: 'device-hash' }));
  const options = { paths: { root, selectionPath }, loadSerial: async () => 'confirmed', readVolume: async () => ({ current: 7, max: 15 }), acknowledge: async () => {}, runMatrix: async () => ({ cases: [] }) };
  await assert.rejects(() => runMatrixCli(['--app', 'com.example.other', '--cases', 'C2'], options), /supported --app/);
  await assert.rejects(() => runMatrixCli(['--app', 'com.tencent.qqmusic', '--cases', 'C9'], options), /Unknown case C9/);
  await assert.rejects(() => runMatrixCli(['--app', 'com.tencent.qqmusic', '--cases', ''], options), /at least one/);
});

test('installs the probe only for the first case so a reinstall cannot kill the session', async () => {
  const { options, runs } = harness();
  await runCaptureMatrix({ ...options, sessionId: 'session-1' });
  assert.deepEqual(runs.map(({ installProbe }) => installProbe), [true, false]);
});

test('asks each listening question with fixed answers and records them on the case', async () => {
  const asked = [];
  const cases = [{ caseId: 'R2', durationSeconds: 20, volumeMode: 'ZERO', instruction: 'Keep it playing.', prerequisite: 'C3', mode: 'DELAYED_LOCAL_PLAYBACK', playbackUsage: 'ACCESSIBILITY', observations: [
    { key: 'soundmeshAudible', question: 'Did you hear SoundMesh replay the music?', answers: ['YES', 'NO', 'UNCLEAR'] },
    { key: 'feedbackHeard', question: 'Did any echo or feedback build up?', answers: ['YES', 'NO', 'UNCLEAR'] }
  ] }];
  const runs = [];
  const result = await runCaptureMatrix({
    serial: 'confirmed', apkPath: 'p', appId: 'com.tencent.qqmusic', appName: 'QQ Music', cases, sessionId: 'session-1',
    outcomes: { C3: 'PASS' }, baselineVolume: { current: 5, min: 0, max: 15 },
    probe: { finishSession: async () => {}, readReplay: async () => ({ startedPlayback: true }) }, acknowledge: async () => {},
    readVolume: async () => ({ current: 0, min: 0, max: 15 }), wavPathFor: id => `${id}.wav`,
    runCase: async options => { runs.push(options); return { caseId: 'R2', outcome: 'PASS' }; },
    ask: async question => { asked.push(question); return question.answers[0]; }
  });
  assert.deepEqual(asked.map(({ key }) => key), ['soundmeshAudible', 'feedbackHeard']);
  assert.deepEqual(asked.map(({ caseId }) => caseId), ['R2', 'R2']);
  assert.deepEqual(result.cases[0].observations, { soundmeshAudible: 'YES', feedbackHeard: 'YES' });
  assert.equal(runs[0].probeCase.mode, 'DELAYED_LOCAL_PLAYBACK');
  assert.equal(runs[0].probeCase.playbackUsage, 'ACCESSIBILITY');
});

test('refuses an answer outside the fixed set instead of recording free text', async () => {
  const cases = [{ caseId: 'R1', durationSeconds: 20, volumeMode: 'ZERO', instruction: 'Keep it playing.', prerequisite: null, observations: [
    { key: 'soundmeshMuted', question: 'Was SoundMesh muted too?', answers: ['YES', 'NO', 'UNCLEAR'] }
  ] }];
  await assert.rejects(() => runCaptureMatrix({
    serial: 'confirmed', apkPath: 'p', appId: 'com.tencent.qqmusic', appName: 'QQ Music', cases, sessionId: 'session-1',
    baselineVolume: { current: 5, min: 0, max: 15 }, probe: { finishSession: async () => {} }, acknowledge: async () => {},
    readVolume: async () => ({ current: 0, min: 0, max: 15 }), wavPathFor: id => `${id}.wav`,
    runCase: async () => ({ caseId: 'R1', outcome: 'PASS' }),
    ask: async () => 'it sounded a bit like the song from my playlist'
  }), /not one of the allowed answers/);
});

test('attaches the private replay report only to delayed playback cases', async () => {
  const replayCalls = [];
  const base = {
    serial: 'confirmed', apkPath: 'p', appId: 'com.tencent.qqmusic', appName: 'QQ Music', sessionId: 'session-1',
    baselineVolume: { current: 5, min: 0, max: 15 }, acknowledge: async () => {},
    readVolume: async () => ({ current: 0, min: 0, max: 15 }), wavPathFor: id => `${id}.wav`,
    probe: { finishSession: async () => {}, readReplay: async call => { replayCalls.push(call.caseId); return { startedPlayback: false, failureCode: 'IllegalStateException' }; } },
    runCase: async options => ({ caseId: options.probeCase.caseId, outcome: 'PASS' })
  };
  const replayed = await runCaptureMatrix({ ...base, cases: [{ caseId: 'R2', durationSeconds: 20, volumeMode: 'ZERO', instruction: 'x', prerequisite: null, mode: 'DELAYED_LOCAL_PLAYBACK', playbackUsage: 'ACCESSIBILITY' }] });
  assert.deepEqual(replayed.cases[0].replay, { startedPlayback: false, failureCode: 'IllegalStateException' });
  assert.deepEqual(replayCalls, ['R2']);

  const captured = await runCaptureMatrix({ ...base, cases: [{ caseId: 'C3', durationSeconds: 20, volumeMode: 'ZERO', instruction: 'x', prerequisite: null }] });
  assert.equal(captured.cases[0].replay, undefined);
  assert.deepEqual(replayCalls, ['R2']);
});
