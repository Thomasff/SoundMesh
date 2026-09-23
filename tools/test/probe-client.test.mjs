import assert from 'node:assert/strict';
import test from 'node:test';
import { createProbeClient, ProbeStatusNotReadyError } from '../src/probe-client.mjs';

test('classifies missing status as NOT_READY but malformed status as fatal', async () => {
  const client = createProbeClient({ runAdb: async () => ({ exitCode: 1, stdout: Buffer.alloc(0), stderr: Buffer.from('No such file or directory') }) });
  await assert.rejects(() => client.readStatus({ serial: 's', caseId: 'C1' }), error => error instanceof ProbeStatusNotReadyError && error.code === 'NOT_READY');
  const malformed = createProbeClient({ runAdb: async () => ({ exitCode: 0, stdout: Buffer.from('{bad'), stderr: Buffer.alloc(0) }) });
  await assert.rejects(() => malformed.readStatus({ serial: 's', caseId: 'C1' }), /invalid status/);
});

test('classifies run-as missing-file text emitted on stdout as NOT_READY', async () => {
  const client = createProbeClient({ runAdb: async () => ({ exitCode: 0, stdout: Buffer.from('cat: files/runs/C1/status.json: No such file or directory\n'), stderr: Buffer.alloc(0) }) });
  await assert.rejects(() => client.readStatus({ serial: 's', caseId: 'C1' }), error => error instanceof ProbeStatusNotReadyError && error.code === 'NOT_READY');
});

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

test('clears only the four private artifacts of one case with an explicit serial', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '', stderr: '' }; } });
  await client.clearCaseArtifacts({ serial: 'serial;bad', caseId: 'C1' });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['exec-out', 'run-as', 'com.soundmesh.probe', 'rm', '-f', 'files/runs/C1/status.json', 'files/runs/C1/capture.json', 'files/runs/C1/capture.wav', 'files/runs/C1/replay.json']
  ]);
  assert.deepEqual(calls.map(({ serial }) => serial), ['serial;bad']);
});

test('rejects an invalid case ID and a failed clear instead of continuing', async () => {
  const client = createProbeClient({ runAdb: async () => ({ exitCode: 0, stdout: '', stderr: '' }) });
  await assert.rejects(() => client.clearCaseArtifacts({ serial: 's', caseId: '../..' }), /Invalid Probe case ID/);
  const failing = createProbeClient({ runAdb: async () => ({ exitCode: 1, stdout: '', stderr: 'run-as: package not debuggable' }) });
  await assert.rejects(() => failing.clearCaseArtifacts({ serial: 's', caseId: 'C1' }), /Clear case artifacts failed/);
});

test('finishes a projection session through the launcher with an explicit serial', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '', stderr: '' }; } });
  await client.finishSession({ serial: 'device-1', sessionId: 's 1' });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.MainActivity', '--es', 'session_id', 's 1', '--ez', 'finish_session', 'true']
  ]);
  assert.deepEqual(calls.map(({ serial }) => serial), ['device-1']);
});

test('passes replay mode and playback usage only when the case asks for them', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '', stderr: '' }; } });
  await client.start({ serial: 's', sessionId: 'x', caseId: 'C1', durationSeconds: 20, expectedPackage: 'com.tencent.qqmusic' });
  await client.start({ serial: 's', sessionId: 'x', caseId: 'R2', durationSeconds: 20, expectedPackage: 'com.tencent.qqmusic', mode: 'DELAYED_LOCAL_PLAYBACK', playbackUsage: 'ACCESSIBILITY' });
  assert.equal(calls[0].args.includes('mode'), false);
  assert.equal(calls[0].args.includes('playback_usage'), false);
  assert.deepEqual(calls[1].args.slice(-6), ['--es', 'mode', 'DELAYED_LOCAL_PLAYBACK', '--es', 'playback_usage', 'ACCESSIBILITY']);
});

test('reads the private replay report of a case', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '{"startedPlayback":true}', stderr: '' }; } });
  assert.deepEqual(await client.readReplay({ serial: 's', caseId: 'R2' }), { startedPlayback: true });
  assert.deepEqual(calls[0].args, ['exec-out', 'run-as', 'com.soundmesh.probe', 'cat', 'files/runs/R2/replay.json']);
});

test('starts the clock probe with no session, duration or projection extras attached', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '{}', stderr: '' }; } });
  await client.clearClockArtifacts({ serial: 'device-1', caseId: 'S1' });
  await client.startClockProbe({ serial: 'device-1', caseId: 'S1', durationSeconds: 300 });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['exec-out', 'run-as', 'com.soundmesh.probe', 'rm', '-f', 'files/runs/S1/clock.json', 'files/runs/S1/status.json'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.MainActivity', '--es', 'case_id', 'S1', '--ei', 'clock_seconds', '300']
  ]);
});

test('starts a sync role without leaking extras the other role needs', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '{}', stderr: '' }; } });
  await client.clearSyncArtifacts({ serial: 'device-1', caseId: 'S2' });
  await client.startSync({ serial: 'device-1', caseId: 'S2', role: 'HOST', seconds: 90, mode: 'FULL' });
  await client.startSync({ serial: 'device-2', caseId: 'S2', role: 'SINK', seconds: 90, mode: 'FULL', hostAddress: '192.168.1.7' });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['exec-out', 'run-as', 'com.soundmesh.probe', 'rm', '-f', 'files/runs/S2/sync.json', 'files/runs/S2/calibration.wav', 'files/runs/S2/chirp.wav'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'HOST', '--ei', 'seconds', '90', '--es', 'mode', 'FULL'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'SINK', '--ei', 'seconds', '90', '--es', 'mode', 'FULL', '--es', 'host_address', '192.168.1.7']
  ]);
});

test('asks for the low latency output path only when the run wants it', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '{}', stderr: '' }; } });
  await client.startSync({ serial: 'device-1', caseId: 'S2', role: 'HOST', seconds: 90, mode: 'FULL', lowLatency: true });
  await client.startSync({ serial: 'device-2', caseId: 'S2', role: 'SINK', seconds: 90, mode: 'FULL', hostAddress: '192.168.1.7', lowLatency: true });
  await client.startSync({ serial: 'device-3', caseId: 'S2', role: 'HOST', seconds: 90, mode: 'FULL', lowLatency: false });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'HOST', '--ei', 'seconds', '90', '--es', 'mode', 'FULL', '--ez', 'low_latency', 'true'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'SINK', '--ei', 'seconds', '90', '--es', 'mode', 'FULL', '--es', 'host_address', '192.168.1.7', '--ez', 'low_latency', 'true'],
    // Not asked for, so the extra is absent entirely and the probe's own false default applies.
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'HOST', '--ei', 'seconds', '90', '--es', 'mode', 'FULL']
  ]);
});

test('carries an overridden reacquire threshold to the probe, and omits it otherwise', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '{}', stderr: '' }; } });
  await client.startSync({ serial: 'device-1', caseId: 'S2', role: 'HOST', seconds: 90, mode: 'FULL', reacquireThresholdFrames: 8 });
  await client.startSync({ serial: 'device-2', caseId: 'S2', role: 'HOST', seconds: 90, mode: 'FULL' });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'HOST', '--ei', 'seconds', '90', '--es', 'mode', 'FULL', '--ei', 'reacquire_threshold_frames', '8'],
    // Absent means the probe's own REACQUIRE_THRESHOLD_FRAMES applies, so every run measured so
    // far stays byte-identical on the wire.
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'HOST', '--ei', 'seconds', '90', '--es', 'mode', 'FULL']
  ]);
});

test('carries the capture source to the probe, and omits it otherwise', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '{}', stderr: '' }; } });
  await client.startSync({ serial: 'device-1', caseId: 'S2', role: 'HOST', seconds: 90, mode: 'FULL', audioSource: 'UNPROCESSED' });
  await client.startSync({ serial: 'device-2', caseId: 'S2', role: 'HOST', seconds: 90, mode: 'FULL' });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'HOST', '--ei', 'seconds', '90', '--es', 'mode', 'FULL', '--es', 'audio_source', 'UNPROCESSED'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'HOST', '--ei', 'seconds', '90', '--es', 'mode', 'FULL']
  ]);
});

test('asks the sink to record only when told to, and clears its recording alongside the host one', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '{}', stderr: '' }; } });
  await client.startSync({ serial: 'device-1', caseId: 'S2', role: 'SINK', seconds: 90, mode: 'FULL', sinkRecords: true });
  await client.startSync({ serial: 'device-2', caseId: 'S2', role: 'SINK', seconds: 90, mode: 'FULL' });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'SINK', '--ei', 'seconds', '90', '--es', 'mode', 'FULL', '--ez', 'sink_records', 'true'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'SINK', '--ei', 'seconds', '90', '--es', 'mode', 'FULL']
  ]);
});

test('passes a denser clock cadence through only when one was asked for', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '{}', stderr: '' }; } });
  // A denser cadence is what makes a 2s-cadence estimator measurable against itself offline: the
  // recorded exchanges decimate into several independent runs of the real configuration, and their
  // disagreement is the estimator's own noise with no model of the true clock in it.
  await client.startSync({ serial: 'device-1', caseId: 'S2', role: 'SINK', seconds: 90, mode: 'FULL', clockIntervalMs: 500 });
  await client.startSync({ serial: 'device-2', caseId: 'S2', role: 'SINK', seconds: 90, mode: 'FULL' });
  await client.startSync({ serial: 'device-3', caseId: 'S2', role: 'SINK', seconds: 90, mode: 'FULL', estimatorWindow: 512, estimatorBest: 64, reopenTrackSeconds: 60 });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'SINK', '--ei', 'seconds', '90', '--es', 'mode', 'FULL', '--ei', 'clock_interval_ms', '500'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'SINK', '--ei', 'seconds', '90', '--es', 'mode', 'FULL'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'SINK', '--ei', 'seconds', '90', '--es', 'mode', 'FULL', '--ei', 'estimator_window', '512', '--ei', 'estimator_best', '64', '--ei', 'reopen_track_seconds', '60']
  ]);
});

test('sends the scanned host only to the role that scanned one', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: '{}', stderr: '' }; } });
  await client.startSync({ serial: 'device-1', caseId: 'S2', role: 'SINK', seconds: 90, mode: 'FULL', hostAddress: '192.168.1.7', paired: true });
  await client.startSync({ serial: 'device-2', caseId: 'S2', role: 'SINK', seconds: 90, mode: 'FULL', hostAddress: '192.168.1.7' });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'SINK', '--ei', 'seconds', '90', '--es', 'mode', 'FULL', '--es', 'host_address', '192.168.1.7', '--ez', 'paired', 'true'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.SyncActivity', '--es', 'case_id', 'S2', '--es', 'role', 'SINK', '--ei', 'seconds', '90', '--es', 'mode', 'FULL', '--es', 'host_address', '192.168.1.7']
  ]);
});

// Pairing is outside every case, which is the whole reason the scanned host is kept where it is:
// under files/ rather than under files/runs/<case>/, so it outlives the run that used it.
test('pairs outside any case at all', async () => {
  const calls = [];
  const client = createProbeClient({ runAdb: async call => { calls.push(call); return { exitCode: 0, stdout: 'soundmesh-pairing 2 da3fe1c00de55dc6 192.168.43.1 45124', stderr: '' }; } });
  await client.startCodeDisplay({ serial: 'device-1' });
  await client.clearScannedPairing({ serial: 'device-2' });
  await client.startScan({ serial: 'device-2' });
  const payload = await client.readScannedPairing({ serial: 'device-2' });
  assert.deepEqual(calls.map(({ args }) => args), [
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.ShowCodeActivity'],
    ['exec-out', 'run-as', 'com.soundmesh.probe', 'rm', '-f', 'files/scanned-pairing'],
    ['shell', 'am', 'start', '-n', 'com.soundmesh.probe/.sync.ScanActivity'],
    ['exec-out', 'run-as', 'com.soundmesh.probe', 'cat', 'files/scanned-pairing']
  ]);
  assert.equal(payload, 'soundmesh-pairing 2 da3fe1c00de55dc6 192.168.43.1 45124');
});

test('reads no scanned host rather than failing when nothing has been scanned', async () => {
  const client = createProbeClient({ runAdb: async () => ({ exitCode: 1, stdout: '', stderr: 'No such file or directory' }) });
  assert.equal(await client.readScannedPairing({ serial: 'device-2' }), null);
});

// The device says "nothing scanned" in an unhelpful way: run-as cat of a missing file exits 0 and
// puts its complaint on stdout, so the first poll of a fresh sink read the words "No such file" as
// a pairing code and the whole pairing command died one second in.
test('reads no scanned host from a device that answers with a complaint', async () => {
  const client = createProbeClient({ runAdb: async () => ({ exitCode: 0, stdout: 'cat: files/scanned-pairing: No such file or directory', stderr: '' }) });
  assert.equal(await client.readScannedPairing({ serial: 'device-2' }), null);
});
