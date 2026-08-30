import { spawn } from 'node:child_process';

const DEFAULT_ADB_PATH = 'D:\\DevSoft\\AndroidSDK\\platform-tools\\adb.exe';

export function parseAdbDevices(text) {
  return text.split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('List of devices attached'))
    .map((line) => {
      const [serial, state, ...details] = line.split(/\s+/);
      return Object.freeze({ serial, state, details: details.join(' ') });
    });
}

export function requireAuthorizedSerial(entries, requestedSerial) {
  if (entries.length === 0) {
    throw new Error('No ADB devices are attached');
  }

  if (requestedSerial) {
    const entry = entries.find(({ serial }) => serial === requestedSerial);
    if (!entry) {
      throw new Error(`Unknown requested serial: ${requestedSerial}`);
    }
    if (entry.state !== 'device') {
      throw new Error(`Requested device is ${entry.state}`);
    }
    return entry.serial;
  }

  const unavailable = entries.find(({ state }) => state !== 'device');
  if (unavailable) {
    throw new Error(`Attached device is ${unavailable.state}`);
  }
  if (entries.length !== 1) {
    throw new Error('Multiple authorized devices require an explicit serial');
  }
  return entries[0].serial;
}

export function commandResult(exitCode, stdout, stderr) {
  return Object.freeze({ exitCode, stdout, stderr });
}

export function createAdbRunner({
  adbPath = process.env.SOUNDMESH_ADB || DEFAULT_ADB_PATH,
  spawnImpl = spawn
} = {}) {
  return ({ serial, args, timeoutMs = 30_000 }) => new Promise((resolve, reject) => {
    if (!serial) {
      reject(new Error('A selected device serial is required'));
      return;
    }
    if (!Array.isArray(args) || args.length === 0) {
      reject(new Error('ADB arguments are required'));
      return;
    }

    const child = spawnImpl(adbPath, ['-s', serial, ...args], { shell: false, windowsHide: true });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error(`ADB command timed out after ${timeoutMs}ms`));
    }, timeoutMs);
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.once('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.once('close', (exitCode) => {
      clearTimeout(timer);
      resolve(commandResult(exitCode, stdout, stderr));
    });
  });
}

export const runAdb = createAdbRunner();

export function createAdbHostRunner({
  adbPath = process.env.SOUNDMESH_ADB || DEFAULT_ADB_PATH,
  spawnImpl = spawn
} = {}) {
  return ({ args, timeoutMs = 30_000 }) => new Promise((resolve, reject) => {
    if (!Array.isArray(args) || args.length === 0) {
      reject(new Error('ADB arguments are required'));
      return;
    }

    const child = spawnImpl(adbPath, args, { shell: false, windowsHide: true });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error(`ADB command timed out after ${timeoutMs}ms`));
    }, timeoutMs);
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.once('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.once('close', (exitCode) => {
      clearTimeout(timer);
      resolve(commandResult(exitCode, stdout, stderr));
    });
  });
}
