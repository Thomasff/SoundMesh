import { spawn } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { runAdb as defaultRunAdb } from './adb.mjs';

const PROBE_PACKAGE = 'com.soundmesh.probe';
const ACTIVITY = `${PROBE_PACKAGE}/.MainActivity`;
const SYNC_ACTIVITY = `${PROBE_PACKAGE}/.sync.SyncActivity`;
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
    startSync: async ({ serial, caseId, role, seconds, mode, hostAddress, lowLatency }) => requireSuccess(await runAdb({ serial, args: ['shell', 'am', 'start', '-n', SYNC_ACTIVITY, '--es', 'case_id', caseId, '--es', 'role', role, '--ei', 'seconds', String(seconds), '--es', 'mode', mode, ...(hostAddress ? ['--es', 'host_address', hostAddress] : []), ...(lowLatency ? ['--ez', 'low_latency', 'true'] : [])] }), 'Sync start'),
    readSync: options => json({ ...options, fileName: 'sync.json' }),
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
