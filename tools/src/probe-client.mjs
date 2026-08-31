import { spawn } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { runAdb as defaultRunAdb } from './adb.mjs';

const PROBE_PACKAGE = 'com.soundmesh.probe';
const ACTIVITY = `${PROBE_PACKAGE}/.MainActivity`;
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
    start: async ({ serial, sessionId, caseId, durationSeconds, expectedPackage }) => requireSuccess(await runAdb({ serial, args: ['shell', 'am', 'start', '-n', ACTIVITY, '--es', 'session_id', sessionId, '--es', 'case_id', caseId, '--ei', 'duration_seconds', String(durationSeconds), '--es', 'expected_package', expectedPackage] }), 'Probe start'),
    readStatus: options => json({ ...options, fileName: 'status.json' }),
    readCapture: options => json({ ...options, fileName: 'capture.json' }),
    exportWav: async ({ serial, caseId, path }) => {
      const result = requireSuccess(await runAdbBinary({ serial, args: ['exec-out', 'run-as', PROBE_PACKAGE, 'cat', privatePath(caseId, 'capture.wav')] }), 'WAV export');
      await writeBinary(path, result.stdout);
      return path;
    }
  });
}
