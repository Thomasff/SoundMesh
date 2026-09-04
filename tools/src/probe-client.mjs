import { spawn } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { runAdb as defaultRunAdb } from './adb.mjs';

const PROBE_PACKAGE = 'com.soundmesh.probe';
const ACTIVITY = `${PROBE_PACKAGE}/.MainActivity`;
const SYNC_ACTIVITY = `${PROBE_PACKAGE}/.sync.SyncActivity`;
const SCAN_ACTIVITY = `${PROBE_PACKAGE}/.sync.ScanActivity`;
const SHOW_CODE_ACTIVITY = `${PROBE_PACKAGE}/.sync.ShowCodeActivity`;
// Fully qualified: the product path sits outside the probe package, so the leading-dot shorthand
// the harness activities use would resolve to the wrong name.
const SESSION_ACTIVITY = `${PROBE_PACKAGE}/com.soundmesh.session.SessionActivity`;
// Not under runs/: a scanned host outlives every case, which is the whole reason it is on disk.
const SCANNED_PAIRING_PATH = 'files/scanned-pairing';
// Same reason: a product session has no case id, because it has no report to file under one.
const SESSION_REPORT_PATH = 'files/session-report.json';
export class ProbeStatusNotReadyError extends Error { constructor() { super('Probe status is not ready'); this.code = 'NOT_READY'; } }

function requireSuccess(result, action) {
  if (result.exitCode !== 0) throw new Error(`${action} failed`);
  return result;
}

function privatePath(caseId, fileName) {
  if (!/^[A-Z][0-9]+$/.test(caseId)) throw new Error('Invalid Probe case ID');
  return `files/runs/${caseId}/${fileName}`;
}

export function createBinaryAdbRunner({ adbPath = process.env.SOUNDMESH_ADB || 'D:\\DevSoft\\AndroidSDK\\platform-tools\\adb.exe', spawnImpl = spawn } = {}) {
  return ({ serial, args, timeoutMs = 30_000 }) => new Promise((resolve, reject) => {
    if (!serial || !Array.isArray(args) || args.length === 0) { reject(new Error('Selected serial and ADB arguments are required')); return; }
    const child = spawnImpl(adbPath, ['-s', serial, ...args], { shell: false, windowsHide: true });
    const stdout = []; const stderr = [];
    const timer = setTimeout(() => { child.kill(); reject(new Error(`ADB command timed out after ${timeoutMs}ms`)); }, timeoutMs);
    child.stdout.on('data', chunk => stdout.push(Buffer.from(chunk)));
    child.stderr.on('data', chunk => stderr.push(Buffer.from(chunk)));
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('close', exitCode => { clearTimeout(timer); resolve({ exitCode, stdout: Buffer.concat(stdout), stderr: Buffer.concat(stderr) }); });
  });
}

export function createProbeClient({ runAdb = defaultRunAdb, runAdbBinary = createBinaryAdbRunner(), writeBinary = writeFile } = {}) {
  const json = async ({ serial, caseId, fileName }) => {
    const result = await runAdb({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'cat', privatePath(caseId, fileName)] });
    const stderr = Buffer.isBuffer(result.stderr) ? result.stderr.toString('utf8') : String(result.stderr ?? '');
    const stdoutText = Buffer.isBuffer(result.stdout) ? result.stdout.toString('utf8') : String(result.stdout ?? '');
    if (fileName === 'status.json' && (result.stdout?.length === 0 || /no such file or directory/i.test(`${stdoutText}\n${stderr}`))) throw new ProbeStatusNotReadyError();
    requireSuccess(result, `Export ${fileName}`);
    try { return JSON.parse(result.stdout); } catch { throw new Error(`Probe returned invalid ${fileName}`); }
  };
  return Object.freeze({
    install: async ({ serial, apkPath }) => requireSuccess(await runAdb({ serial, args: ['install', '-r', apkPath] }), 'Probe install'),
    start: async ({ serial, sessionId, caseId, durationSeconds, expectedPackage, mode, playbackUsage }) => requireSuccess(await runAdb({ serial, args: ['shell', 'am', 'start', '-n', ACTIVITY, '--es', 'session_id', sessionId, '--es', 'case_id', caseId, '--ei', 'duration_seconds', String(durationSeconds), '--es', 'expected_package', expectedPackage, ...(mode ? ['--es', 'mode', mode] : []), ...(playbackUsage ? ['--es', 'playback_usage', playbackUsage] : [])] }), 'Probe start'),
    finishSession: async ({ serial, sessionId }) => requireSuccess(await runAdb({ serial, args: ['shell', 'am', 'start', '-n', ACTIVITY, '--es', 'session_id', sessionId, '--ez', 'finish_session', 'true'] }), 'Probe finish session'),
    readStatus: options => json({ ...options, fileName: 'status.json' }),
    readCapture: options => json({ ...options, fileName: 'capture.json' }),
    readReplay: options => json({ ...options, fileName: 'replay.json' }),
    readClock: options => json({ ...options, fileName: 'clock.json' }),
    // The clock probe measures this device's own audio clock; it needs no session and no projection.
    startClockProbe: async ({ serial, caseId, durationSeconds }) => requireSuccess(await runAdb({ serial, args: ['shell', 'am', 'start', '-n', ACTIVITY, '--es', 'case_id', caseId, '--ei', 'clock_seconds', String(durationSeconds)] }), 'Clock probe start'),
    clearClockArtifacts: async ({ serial, caseId }) => requireSuccess(await runAdb({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'rm', '-f', privatePath(caseId, 'clock.json'), privatePath(caseId, 'status.json')] }), 'Clear clock artifacts'),
    // lowLatency is only sent when asked for: the probe defaults the extra to false, which is the
    // original output path, so an absent extra and `--ez low_latency false` mean the same thing
    // and the shorter argv keeps every run so far byte-identical.
    // reacquireThresholdFrames is sent on the same terms as lowLatency: absent means the probe's
    // own REACQUIRE_THRESHOLD_FRAMES applies. It exists to be lowered far below the noise floor so
    // the TRACKING fallback can be made to fire on demand - eight two-handset runs passed with the
    // production threshold and reacquisitions == 0 on every role, which says the mechanism was
    // never executed, not that it works.
    // chirpRepeats/chirpIntervalSeconds follow the same absent-means-default rule as the extras
    // above: without them the probe plays the single pair every stored measurement was taken on.
    // clockIntervalMs is the sink's clock exchange cadence, absent-means-default like the rest: only
    // the sink runs a clock client, and a denser cadence is a data collection choice, not production.
    // sinkRecords is sent to the sink alone and on the same terms: absent leaves the run on the
    // one-recording arrangement, where only the host opens a capture path.
    startSync: async ({ serial, caseId, role, seconds, mode, hostAddress, lowLatency, reacquireThresholdFrames, trimFrames, audioSource, chirpRepeats, chirpIntervalSeconds, deadbandFrames, sinkRecords, clockIntervalMs, alignmentOffsetMicros, capturePackage, sourceFile, networkMode, discover, paired, playbackUsage }) => requireSuccess(await runAdb({ serial, args: ['shell', 'am', 'start', '-n', SYNC_ACTIVITY, '--es', 'case_id', caseId, '--es', 'role', role, '--ei', 'seconds', String(seconds), '--es', 'mode', mode, ...(hostAddress ? ['--es', 'host_address', hostAddress] : []), ...(lowLatency ? ['--ez', 'low_latency', 'true'] : []), ...(reacquireThresholdFrames ? ['--ei', 'reacquire_threshold_frames', String(reacquireThresholdFrames)] : []), ...(trimFrames ? ['--ei', 'trim_frames', String(trimFrames)] : []), ...(audioSource ? ['--es', 'audio_source', audioSource] : []), ...(chirpRepeats ? ['--ei', 'chirp_repeats', String(chirpRepeats)] : []), ...(chirpIntervalSeconds ? ['--ei', 'chirp_interval_seconds', String(chirpIntervalSeconds)] : []), ...(deadbandFrames ? ['--ei', 'deadband_frames', String(deadbandFrames)] : []), ...(sinkRecords ? ['--ez', 'sink_records', 'true'] : []), ...(clockIntervalMs ? ['--ei', 'clock_interval_ms', String(clockIntervalMs)] : []), ...(alignmentOffsetMicros !== undefined ? ['--ei', 'alignment_offset_us', String(alignmentOffsetMicros)] : []), ...(capturePackage ? ['--es', 'capture_package', capturePackage] : []), ...(sourceFile ? ['--es', 'source_file', sourceFile] : []), ...(networkMode ? ['--es', 'network_mode', networkMode] : []), ...(discover ? ['--ez', 'discover', 'true'] : []), ...(paired ? ['--ez', 'paired', 'true'] : []), ...(playbackUsage ? ['--es', 'playback_usage', playbackUsage] : [])] }), 'Sync start'),
    readSync: options => json({ ...options, fileName: 'sync.json' }),
    // Pairing is its own act, on its own screen, and outside every case: someone holds the handset
    // up to another one's display once, and every run after that starts without a hand on either
    // phone. Which is the point - a run measures timing that being touched destroys.
    // The host's half, and the reason it is not just a side effect of a run: pairing belongs before
    // anyone presses play, and a code that only appears while a run plays cannot be scanned first.
    startCodeDisplay: async ({ serial }) => requireSuccess(await runAdb({ serial, args: ['shell', 'am', 'start', '-n', SHOW_CODE_ACTIVITY] }), 'Code display start'),
    startScan: async ({ serial }) => requireSuccess(await runAdb({ serial, args: ['shell', 'am', 'start', '-n', SCAN_ACTIVITY] }), 'Scanner start'),
    // The product path, not the harness. SessionService is not exported, so the activity is what
    // takes the intent and hands it on - the same reason MainActivity fronts the capture service.
    // deadbandFrames follows the absent-means-default rule the sync extras use: a session started
    // without it has to behave exactly as one started before the extra existed, or the control arm
    // of a comparison is not the thing being compared against.
    startSession: async ({ serial, role, sourceFile, deadbandFrames, trimFrames }) => requireSuccess(await runAdb({ serial, args: ['shell', 'am', 'start', '-n', SESSION_ACTIVITY, '--es', 'role', role, ...(sourceFile ? ['--es', 'source_file', sourceFile] : []), ...(deadbandFrames ? ['--ei', 'deadband_frames', String(deadbandFrames)] : []), ...(trimFrames ? ['--ei', 'trim_frames', String(trimFrames)] : [])] }), 'Session start'),
    stopSession: async ({ serial }) => requireSuccess(await runAdb({ serial, args: ['shell', 'am', 'start', '-n', SESSION_ACTIVITY, '--ez', 'stop', 'true'] }), 'Session stop'),
    // Null rather than an error when there is none: a handset whose session was never started has
    // no report, and that is an ordinary answer rather than a failure. A run-as cat of a missing
    // file exits 0 and puts its complaint on stdout, so the text has to be inspected either way.
    readSessionReport: async ({ serial }) => {
      const result = await runAdb({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'cat', SESSION_REPORT_PATH] });
      if (result.exitCode !== 0) return null;
      const text = (Buffer.isBuffer(result.stdout) ? result.stdout.toString('utf8') : String(result.stdout ?? '')).trim();
      if (text.length === 0 || /no such file or directory/i.test(text)) return null;
      try { return JSON.parse(text); } catch { return null; }
    },
    clearSessionReport: async ({ serial }) => requireSuccess(await runAdb({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'rm', '-f', SESSION_REPORT_PATH] }), 'Clear session report'),
    // Null rather than an error when nothing has been scanned yet: this is polled while a person is
    // still aiming the camera, so "not there yet" is the ordinary case - and the device says so in
    // an unhelpful way. A run-as cat of a file that is not there exits 0 and puts its complaint on
    // stdout, so without the pattern below the first poll reads the words "No such file" as a
    // pairing code. Matched the same way readStatus matches it, for the same reason.
    readScannedPairing: async ({ serial }) => {
      const result = await runAdb({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'cat', SCANNED_PAIRING_PATH] });
      if (result.exitCode !== 0) return null;
      const text = (Buffer.isBuffer(result.stdout) ? result.stdout.toString('utf8') : String(result.stdout ?? '')).trim();
      if (text.length === 0 || /no such file or directory/i.test(text)) return null;
      return text;
    },
    clearScannedPairing: async ({ serial }) => requireSuccess(await runAdb({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'rm', '-f', SCANNED_PAIRING_PATH] }), 'Clear scanned pairing'),
    clearSyncArtifacts: async ({ serial, caseId }) => requireSuccess(await runAdb({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'rm', '-f', privatePath(caseId, 'sync.json'), privatePath(caseId, 'calibration.wav'), privatePath(caseId, 'chirp.wav')] }), 'Clear sync artifacts'),
    exportNamedWav: async ({ serial, caseId, fileName, path }) => {
      const result = requireSuccess(await runAdbBinary({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'cat', privatePath(caseId, fileName)] }), `${fileName} export`);
      await writeBinary(path, result.stdout);
      return path;
    },
    clearCaseArtifacts: async ({ serial, caseId }) => requireSuccess(await runAdb({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'rm', '-f', privatePath(caseId, 'status.json'), privatePath(caseId, 'capture.json'), privatePath(caseId, 'capture.wav'), privatePath(caseId, 'replay.json')] }), 'Clear case artifacts'),
    exportWav: async ({ serial, caseId, path }) => {
      const result = requireSuccess(await runAdbBinary({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'cat', privatePath(caseId, 'capture.wav')] }), 'WAV export');
      await writeBinary(path, result.stdout);
      return path;
    }
  });
}
