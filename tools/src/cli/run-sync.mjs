import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createProbeClient } from '../probe-client.mjs';
import { analyzeAlignment } from '../calibration-analysis.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../artifacts');
const value = (args, key) => { const index = args.indexOf(key); return index === -1 ? undefined : args[index + 1]; };
const wait = ms => new Promise(done => setTimeout(done, ms));
const SAMPLE_RATE = 48000;
const STAGGER_FRAMES = SAMPLE_RATE / 2;

/** Reads a mono PCM16 WAV written by the probe into an Int16Array. */
function readPcm16(buffer) {
  let offset = 12;
  while (offset + 8 <= buffer.length) {
    const id = buffer.toString('ascii', offset, offset + 4);
    const length = buffer.readUInt32LE(offset + 4);
    if (id === 'data') {
      const out = new Int16Array(length / 2);
      for (let index = 0; index < out.length; index++) out[index] = buffer.readInt16LE(offset + 8 + index * 2);
      return out;
    }
    offset += 8 + length + (length % 2);
  }
  throw new Error('WAV has no data chunk');
}

export async function main(args = process.argv.slice(2), { client = createProbeClient(), log = text => process.stdout.write(`${text}\n`) } = {}) {
  const caseId = value(args, '--case') || 'S2';
  const seconds = Number(value(args, '--seconds') || 90);
  const mode = value(args, '--mode') || 'FULL';
  const hostSerial = value(args, '--host-serial');
  const sinkSerial = value(args, '--sink-serial');
  const hostAddress = value(args, '--host-address');
  if (!hostSerial || !sinkSerial || !hostAddress) throw new Error('Use --host-serial, --sink-serial and --host-address');
  if (!/^[A-Z][0-9]+$/.test(caseId)) throw new Error('Use a case ID like S2');

  for (const serial of [hostSerial, sinkSerial]) await client.clearSyncArtifacts({ serial, caseId });
  await client.startSync({ serial: hostSerial, caseId, role: 'HOST', seconds, mode });
  await wait(2000);
  await client.startSync({ serial: sinkSerial, caseId, role: 'SINK', seconds, mode, hostAddress });
  log(`Both roles started for ${seconds}s. Keep the room quiet and do not touch either phone.`);
  await wait((seconds + 30) * 1000);

  const directory = resolve(root, 'sync', caseId);
  await mkdir(directory, { recursive: true });
  const reports = {};
  for (const [name, serial] of [['host', hostSerial], ['sink', sinkSerial]]) {
    reports[name] = await client.readSync({ serial, caseId }).catch(error => ({ unavailable: error.message }));
  }
  await writeFile(resolve(directory, 'sync-reports.json'), `${JSON.stringify(reports, null, 2)}\n`, 'utf8');

  // The sink now waits (bounded at 40s) for its clock offset estimate to converge before it
  // plays anything; on that timeout sync.json carries a failureCode instead of estimates, and
  // neither phone ever wrote calibration.wav/chirp.wav. Skip the doomed export/analysis so the
  // person at the terminal gets a clear failure line instead of a WAV-export stack trace.
  const failureCode = reports.host?.failureCode ?? reports.sink?.failureCode;
  let alignment = null;
  if (mode === 'FULL' && !failureCode) {
    await client.exportNamedWav({ serial: hostSerial, caseId, fileName: 'calibration.wav', path: resolve(directory, 'calibration.wav') });
    await client.exportNamedWav({ serial: hostSerial, caseId, fileName: 'chirp.wav', path: resolve(directory, 'chirp.wav') });
    alignment = analyzeAlignment({
      recorded: readPcm16(await readFile(resolve(directory, 'calibration.wav'))),
      reference: readPcm16(await readFile(resolve(directory, 'chirp.wav'))),
      sampleRate: SAMPLE_RATE, staggerFrames: STAGGER_FRAMES, searchRadiusFrames: 4800
    });
    await writeFile(resolve(directory, 'alignment.json'), `${JSON.stringify(alignment, null, 2)}\n`, 'utf8');
  }

  if (failureCode) {
    log(`clock sync         FAILED (${failureCode})`);
  } else {
    const uncertainties = (reports.sink?.estimates ?? []).map(entry => entry.uncertaintyNanos / 1e6);
    log(`clock uncertainty  last ${uncertainties.at(-1)?.toFixed(3) ?? 'n/a'} ms over ${uncertainties.length} estimates`);
    log(`clock drift        ${reports.sink?.estimates?.at(-1)?.driftPpm?.toFixed(2) ?? 'n/a'} ppm`);
  }
  if (alignment) {
    log(`alignment error    ${alignment.alignmentErrorMs?.toFixed(3) ?? 'n/a'} ms  (${alignment.confidence})`);
  }
  return { reports, alignment };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
