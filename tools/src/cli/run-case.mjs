import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConfirmedSerial } from './inspect-device.mjs';
import { createProbeClient } from '../probe-client.mjs';
import { runCaptureCase } from '../case-runner.mjs';
import { renderSummary, redactOutcome } from '../report.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../artifacts');
const selectionPath = resolve(root, 'session', 'selected-device.json');
const value = (args, key) => { const index = args.indexOf(key); return index === -1 ? undefined : args[index + 1]; };
const APP_NAMES = Object.freeze({ 'com.netease.cloudmusic': 'NetEase Cloud Music', 'com.tencent.qqmusic': 'QQ Music' });

export async function main(args = process.argv.slice(2), { loadSerial = loadConfirmedSerial, client = createProbeClient(), acknowledge = async text => { process.stdout.write(`${text}\n`); await new Promise(resolveInput => process.stdin.once('data', resolveInput)); } } = {}) {
  const appId = value(args, '--app'); const caseId = value(args, '--case');
  if (!APP_NAMES[appId] || !/^[A-Z][0-9]+$/.test(caseId || '')) throw new Error('Use a supported --app and case ID');
  const selection = JSON.parse(await readFile(selectionPath, 'utf8'));
  const serial = await loadSerial(); // Revalidates the confirmed selection immediately before any action.
  const apkPath = value(args, '--apk') || resolve(dirname(fileURLToPath(import.meta.url)), '../../../app/build/outputs/apk/debug/app-debug.apk');
  const directory = resolve(root, 'feasibility', selection.fingerprintHash, appId, caseId);
  await mkdir(directory, { recursive: true });
  const outcome = await runCaptureCase({ serial, apkPath, probe: client, acknowledge, deviceAlias: 'Selected device', appName: APP_NAMES[appId], probeCase: { caseId, durationSeconds: 20, expectedPackage: appId }, wavPath: resolve(directory, 'capture.wav') });
  const redacted = redactOutcome(outcome);
  await writeFile(resolve(directory, 'case-outcome.json'), `${JSON.stringify(redacted, null, 2)}\n`, 'utf8');
  await writeFile(resolve(directory, 'summary.md'), renderSummary(redacted), 'utf8');
  process.stdout.write(`${JSON.stringify(redacted)}\n`);
  return redacted;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
