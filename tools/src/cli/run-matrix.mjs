import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConfirmedSerial } from './inspect-device.mjs';
import { createProbeClient } from '../probe-client.mjs';
import { readMediaVolume } from '../device-inventory.mjs';
import { runCaptureMatrix } from '../capture-matrix.mjs';
import { renderSummary, redactOutcome } from '../report.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../../artifacts');
const selectionPath = resolve(root, 'session', 'selected-device.json');
const matrixPath = resolve(here, '../../config/capture-matrix.json');
const CASE_ID = /^[A-Z][0-9]+$/;
const APP_NAMES = Object.freeze({ 'com.netease.cloudmusic': 'NetEase Cloud Music', 'com.tencent.qqmusic': 'QQ Music' });

const value = (args, key) => { const index = args.indexOf(key); return index === -1 ? undefined : args[index + 1]; };
const list = (args, key) => (value(args, key) || '').split(',').map(entry => entry.trim()).filter(Boolean);

async function loadKnownOutcomes(appDirectory) {
  const outcomes = {};
  let entries;
  try { entries = await readdir(appDirectory, { withFileTypes: true }); } catch { return outcomes; }
  for (const entry of entries) {
    if (!entry.isDirectory() || !CASE_ID.test(entry.name)) continue;
    try {
      const recorded = JSON.parse(await readFile(resolve(appDirectory, entry.name, 'case-outcome.json'), 'utf8'));
      if (recorded.outcome) outcomes[entry.name] = recorded.outcome;
    } catch { /* an unfinished case contributes no prerequisite evidence */ }
  }
  return outcomes;
}

export async function main(args = process.argv.slice(2), {
  loadSerial = loadConfirmedSerial,
  client = createProbeClient(),
  runMatrix = runCaptureMatrix,
  readVolume = readMediaVolume,
  paths = { root, selectionPath },
  matrix,
  acknowledge = async text => { process.stdout.write(`${text}\n`); await new Promise(resolveInput => process.stdin.once('data', resolveInput)); },
  ask = async ({ caseId, key, question, answers }) => {
    process.stdout.write(`OBSERVATION [${caseId}] ${key}: ${question} (${answers.join(' / ')})\n`);
    const reply = await new Promise(resolveInput => process.stdin.once('data', resolveInput));
    return String(reply).trim().toUpperCase();
  }
} = {}) {
  const appId = value(args, '--app');
  if (!APP_NAMES[appId]) throw new Error('Use a supported --app');
  const requested = list(args, '--cases');
  if (requested.length === 0) throw new Error('Name at least one case ID with --cases');
  const definitions = matrix ?? JSON.parse(await readFile(matrixPath, 'utf8'));
  const cases = requested.map(caseId => {
    const found = CASE_ID.test(caseId) && definitions.cases.find(entry => entry.caseId === caseId);
    if (!found) throw new Error(`Unknown case ${caseId}`);
    return found;
  });
  const unavailable = list(args, '--unavailable');

  const selection = JSON.parse(await readFile(paths.selectionPath, 'utf8'));
  const serial = await loadSerial(); // Revalidates the confirmed selection immediately before any action.
  const baselineVolume = await readVolume({ serial });
  const appDirectory = resolve(paths.root, 'feasibility', selection.fingerprintHash, appId);
  const outcomes = await loadKnownOutcomes(appDirectory);
  for (const { caseId } of cases) await mkdir(resolve(appDirectory, caseId), { recursive: true });

  const apkPath = value(args, '--apk') || resolve(here, '../../../app/build/outputs/apk/debug/app-debug.apk');
  const result = await runMatrix({
    serial, apkPath, probe: client, acknowledge, ask, appId, appName: APP_NAMES[appId], cases, unavailable, outcomes,
    baselineVolume, readVolume, wavPathFor: caseId => resolve(appDirectory, caseId, 'capture.wav')
  });

  for (const outcome of result.cases ?? []) {
    const directory = resolve(appDirectory, outcome.caseId);
    const redacted = redactOutcome(outcome);
    await writeFile(resolve(directory, 'case-outcome.json'), `${JSON.stringify(redacted, null, 2)}\n`, 'utf8');
    if (redacted.format) await writeFile(resolve(directory, 'summary.md'), renderSummary({ ...redacted, appId }), 'utf8');
  }
  const redactedMatrix = redactOutcome(result);
  await writeFile(resolve(appDirectory, 'matrix-outcome.json'), `${JSON.stringify(redactedMatrix, null, 2)}\n`, 'utf8');
  process.stdout.write(`${JSON.stringify(redactedMatrix)}\n`);
  return redactedMatrix;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
