import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createProbeClient } from '../probe-client.mjs';
import { analyzeAlignment } from '../calibration-analysis.mjs';
import { createAdbHostRunner, parseAdbDevices } from '../adb.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../artifacts');
const value = (args, key) => { const index = args.indexOf(key); return index === -1 ? undefined : args[index + 1]; };
const wait = ms => new Promise(done => setTimeout(done, ms));
const SAMPLE_RATE = 48000;
const STAGGER_FRAMES = SAMPLE_RATE / 2;
const PROBE_PACKAGE = 'com.soundmesh.probe';

/**
 * 250 ms either side of where the partner chirp is expected. One handset's output buffer measured
 * 210.7 ms; at the old 100 ms a second model differing by more than that puts the partner outside
 * the window, and a chirp just past the edge is worse than a miss - it leaves a partial overlap
 * peak sitting on the boundary that can still clear the confidence ratio. Still well under the
 * 24000 frame stagger, which analyzeAlignment requires so the two chirps stay distinguishable.
 */
const SEARCH_RADIUS_FRAMES = 12000;

/**
 * Checks both roles against what ADB actually reports, without ever putting a full serial into
 * an error message: only the failing role and the reason are said aloud. Two devices are attached
 * at once here (the only task in this plan where that is true), so a mistyped or swapped serial
 * would otherwise silently target the wrong phone instead of failing loudly.
 */
export function assertAuthorizedPair(entries, { hostSerial, sinkSerial }) {
  if (hostSerial === sinkSerial) throw new Error('--host-serial and --sink-serial must refer to different devices');
  for (const [role, serial] of [['Host', hostSerial], ['Sink', sinkSerial]]) {
    const entry = entries.find(candidate => candidate.serial === serial);
    if (!entry) throw new Error(`${role} serial is not among the attached, authorised devices`);
    if (entry.state !== 'device') throw new Error(`${role} serial is attached but not authorised (state: ${entry.state})`);
  }
}

export async function requireBothSerialsAuthorized({ hostSerial, sinkSerial, runAdbHost }) {
  const result = await runAdbHost({ args: ['devices', '-l'] });
  if (result.exitCode !== 0) throw new Error('ADB device discovery failed');
  assertAuthorizedPair(parseAdbDevices(result.stdout), { hostSerial, sinkSerial });
}

/**
 * Grants RECORD_AUDIO to the probe on the recording device.
 *
 * Only MainActivity asks for it at runtime; SyncActivity never does. Without this the run works
 * only on a handset where an earlier capture probe happened to be granted it, and fails as a
 * silent recording everywhere else. The serial is the one already confirmed attached and
 * authorised, passed in argv form so nothing is handed to a shell.
 */
export async function grantRecordAudio({ serial, runAdbHost }) {
  const result = await runAdbHost({ args: ['-s', serial, 'shell', 'pm', 'grant', PROBE_PACKAGE, 'android.permission.RECORD_AUDIO'] });
  if (result.exitCode !== 0) throw new Error('Granting RECORD_AUDIO to the probe on the recording device failed');
}

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

export async function main(args = process.argv.slice(2), { client = createProbeClient(), log = text => process.stdout.write(`${text}\n`), runAdbHost = createAdbHostRunner() } = {}) {
  const caseId = value(args, '--case') || 'S2';
  const seconds = Number(value(args, '--seconds') || 90);
  const mode = value(args, '--mode') || 'FULL';
  const hostSerial = value(args, '--host-serial');
  const sinkSerial = value(args, '--sink-serial');
  const hostAddress = value(args, '--host-address');
  const separationMetres = Number(value(args, '--separation-m'));
  // Both roles or neither: the measurement is a comparison between the two handsets, so running
  // one on the fast mixer path and the other on the deep one would put the difference between the
  // paths straight into the alignment number.
  const lowLatency = args.includes('--low-latency');
  if (!hostSerial || !sinkSerial || !hostAddress) throw new Error('Use --host-serial, --sink-serial and --host-address');
  if (!/^[A-Z][0-9]+$/.test(caseId)) throw new Error('Use a case ID like S2');
  // No default is safe here. The host records its own chirp from a few centimetres away and the
  // sink's from across the room, so the raw measurement always carries the flight time of that
  // gap - about -2.9 ms per metre against a 5 ms gate. Assuming a distance instead of measuring
  // it turns a real failure into a clean-looking pass, so the run is refused without one.
  if (!Number.isFinite(separationMetres) || separationMetres < 0) {
    throw new Error('Use --separation-m <metres>: measure the distance between the two handsets and pass it. It cannot be assumed - leaving it out biases every reported error by roughly -2.9 ms per metre of separation, which is enough to hide a real failure behind a passing number.');
  }
  // Revalidates both confirmed serials immediately before any device action: with two phones
  // attached at once, a mistyped or swapped serial would otherwise silently target the wrong one.
  await requireBothSerialsAuthorized({ hostSerial, sinkSerial, runAdbHost });
  await grantRecordAudio({ serial: hostSerial, runAdbHost });

  for (const serial of [hostSerial, sinkSerial]) await client.clearSyncArtifacts({ serial, caseId });
  await client.startSync({ serial: hostSerial, caseId, role: 'HOST', seconds, mode, lowLatency });
  await wait(2000);
  await client.startSync({ serial: sinkSerial, caseId, role: 'SINK', seconds, mode, hostAddress, lowLatency });
  log(`Both roles started for ${seconds}s on the ${lowLatency ? 'low latency' : 'default'} output path. Keep the room quiet and do not touch either phone.`);
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
      sampleRate: SAMPLE_RATE, staggerFrames: STAGGER_FRAMES, searchRadiusFrames: SEARCH_RADIUS_FRAMES,
      separationMetres
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
    log(`  of which air     ${alignment.propagationCorrectionMs.toFixed(3)} ms added back for ${separationMetres} m of separation`);
  }
  return { reports, alignment };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
