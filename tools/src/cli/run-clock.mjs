import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConfirmedSerial } from './inspect-device.mjs';
import { createProbeClient, ProbeStatusNotReadyError } from '../probe-client.mjs';
import { analyzeClockProbe } from '../clock-analysis.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../artifacts');
const selectionPath = resolve(root, 'session', 'selected-device.json');
const value = (args, key) => { const index = args.indexOf(key); return index === -1 ? undefined : args[index + 1]; };
const wait = ms => new Promise(resolveTimer => setTimeout(resolveTimer, ms));

const POLL_INTERVAL_MS = 5_000;
const SLACK_SECONDS = 45;

function render(analysis) {
  const number = (input, digits = 3) => (input === null || input === undefined ? 'n/a' : input.toFixed(digits));
  return [
    `verdict            ${analysis.verdict}${analysis.reasons.length ? `  (${analysis.reasons.join(', ')})` : ''}`,
    `samples            ${analysis.sampleCount} kept of ${analysis.requested} polls, ${analysis.unavailable} unavailable, ${analysis.duplicates} repeated`,
    `timestamp refresh  median ${number(analysis.timestampUpdate.medianMs, 1)} ms, worst ${number(analysis.timestampUpdate.maxMs, 1)} ms`,
    `actual rate        ${number(analysis.actualSampleRate, 3)} Hz against a nominal ${analysis.nominalSampleRate} Hz`,
    `crystal offset     ${number(analysis.ppmDeviation, 2)} ppm`,
    `fit residual       max ${number(analysis.residual.maxMs)} ms, rms ${number(analysis.residual.rmsMs)} ms`,
    `holdout            fit ${number(analysis.holdout.fitSeconds, 1)} s, predict ${number(analysis.holdout.predictSeconds, 1)} s, worst error ${number(analysis.holdout.maxErrorMs)} ms`,
    `output buffer      ${analysis.latency.medianFrames ?? 'n/a'} frames, ${number(analysis.latency.medianMs, 1)} ms`,
    `monotonic          ${analysis.monotonic}`
  ].join('\n');
}

export async function main(args = process.argv.slice(2), { loadSerial = loadConfirmedSerial, client = createProbeClient(), paths = { root, selectionPath }, log = text => process.stdout.write(`${text}\n`) } = {}) {
  const caseId = value(args, '--case') || 'S1';
  const durationSeconds = Number(value(args, '--seconds') || 300);
  if (!/^[A-Z][0-9]+$/.test(caseId)) throw new Error('Use a case ID like S1');
  if (!Number.isInteger(durationSeconds) || durationSeconds < 30 || durationSeconds > 900) throw new Error('Use --seconds between 30 and 900');
  const selection = JSON.parse(await readFile(paths.selectionPath, 'utf8'));
  const serial = await loadSerial(); // Revalidates the confirmed selection immediately before any device action.

  // A leftover log from an earlier run would otherwise be read as this run's result.
  await client.clearClockArtifacts({ serial, caseId });
  await client.startClockProbe({ serial, caseId, durationSeconds });
  log(`Clock probe started for ${durationSeconds}s. Leave the phone alone until it reports.`);

  const deadline = Date.now() + (durationSeconds + SLACK_SECONDS) * 1000;
  let deviceReport;
  while (Date.now() < deadline) {
    await wait(POLL_INTERVAL_MS);
    try {
      deviceReport = await client.readClock({ serial, caseId });
      break;
    } catch {
      const status = await client.readStatus({ serial, caseId }).catch(error => (error instanceof ProbeStatusNotReadyError ? null : { state: 'UNREADABLE' }));
      if (status?.state === 'FAILED') throw new Error(`Probe rejected the clock probe: ${status.failureCode ?? 'unknown'}`);
    }
  }
  if (!deviceReport) throw new Error('Clock probe produced no log before the deadline');

  const analysis = analyzeClockProbe(deviceReport);
  const directory = resolve(paths.root, 'feasibility', selection.fingerprintHash, 'clock', caseId);
  await mkdir(directory, { recursive: true });
  await writeFile(resolve(directory, 'clock-raw.json'), `${JSON.stringify(deviceReport)}\n`, 'utf8');
  await writeFile(resolve(directory, 'clock-analysis.json'), `${JSON.stringify(analysis, null, 2)}\n`, 'utf8');
  log(render(analysis));
  return analysis;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
