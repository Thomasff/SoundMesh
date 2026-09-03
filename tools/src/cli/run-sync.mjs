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
 * The earliest either handset can have written its report, counted from the audio segment's end:
 * the 2s calibration gap, the 1.5s chunk lead already inside it, the chirp, its 1s drain and the
 * host's 2s recording tail come to about seven. Five is deliberately under that, so the floor can
 * never sleep past a report and turn a fast run into a slow one.
 */
const COMPLETION_FLOOR_SECONDS = 5;

/** What is left of the old fixed (seconds + 30) wait, spent polling rather than sleeping. */
const COMPLETION_BUDGET_SECONDS = 25;

/** Echo requests per link probe. Five is enough for a floor and costs about four seconds. */
const LINK_PROBE_COUNT = 5;

/** The capture paths the probe implements. MIC is its default and the measured baseline. */
const CAPTURE_SOURCES = ['MIC', 'VOICE_RECOGNITION', 'UNPROCESSED'];

/**
 * Floor on the gap between repeated chirp pairs. The pairs are told apart by slicing the recording
 * at the interval, so a slice has to comfortably hold the probe's 1s record lead, the 500ms stagger
 * and the sweep itself; five seconds leaves room for the input latency and start jitter on top,
 * both of which run to tens of milliseconds.
 */
const MIN_CHIRP_INTERVAL_SECONDS = 5;

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
 * Reads `mWakefulness` out of `dumpsys power` and refuses anything but Awake.
 *
 * A sleeping handset still accepts `am start` and still makes sound - the audio thread keeps
 * producing at a degraded priority - so a run against one looks like it worked. What doze takes
 * away is timing, which is the only thing this harness measures: three runs launched onto
 * handsets reporting Asleep and Dozing produced one missed chirp deadline and two recordings whose
 * chirps the correlator could not locate at all. Cheaper to refuse up front than to spend 90
 * seconds producing a number nobody should trust. Only the role is named, never the serial.
 */
export function assertAwake(dumpsysOutput, role) {
  const text = Buffer.isBuffer(dumpsysOutput) ? dumpsysOutput.toString('utf8') : String(dumpsysOutput ?? '');
  const match = /mWakefulness=([A-Za-z]+)/.exec(text);
  if (!match) throw new Error(`${role} wakefulness could not be read; refusing to run blind`);
  if (match[1] !== 'Awake') throw new Error(`${role} is ${match[1]}, not Awake. Wake both handsets and keep their screens on for the whole run.`);
}

export async function requireBothDevicesAwake({ hostSerial, sinkSerial, runAdbHost }) {
  for (const [role, serial] of [['Host', hostSerial], ['Sink', sinkSerial]]) {
    const result = await runAdbHost({ args: ['-s', serial, 'shell', 'dumpsys', 'power'] });
    if (result.exitCode !== 0) throw new Error(`${role} power state query failed`);
    assertAwake(result.stdout, role);
  }
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

/**
 * Reads min/avg/max and packet loss out of `ping`'s summary, or null when it printed no summary.
 *
 * Null rather than zeros: 100% loss prints the packet line and no round-trip line at all, and a
 * zero RTT recorded for a dead link is worse than no reading, because a later comparison would
 * read it as the best link ever measured.
 */
export function parseLinkRtt(pingOutput) {
  const text = Buffer.isBuffer(pingOutput) ? pingOutput.toString('utf8') : String(pingOutput ?? '');
  const rtt = /(?:rtt|round-trip)\s+min\/avg\/max\/(?:mdev|stddev)\s*=\s*([\d.]+)\/([\d.]+)\/([\d.]+)\//.exec(text);
  if (!rtt) return null;
  const loss = /([\d.]+)\s*% packet loss/.exec(text);
  return {
    minMs: Number(rtt[1]),
    avgMs: Number(rtt[2]),
    maxMs: Number(rtt[3]),
    lossPercent: loss ? Number(loss[1]) : null
  };
}

/**
 * Measures the link from the sink towards the host, once, before either role starts.
 *
 * Every run is compared against runs from other days, and the link is the condition that moves
 * most between them: a single day already saw the floor go from 3.57ms to 13.10ms, which was
 * enough to make one robustness comparison unreadable (part three, section 15.2). Without the
 * link recorded alongside the result, a cross-day difference cannot be told from a cross-link one.
 *
 * Before, never during. The probe is ICMP traffic on the very link whose timing is being measured,
 * so running it alongside the measurement would perturb the thing it is meant to describe.
 *
 * Diagnostic, not a gate: an unreachable host is recorded as unavailable and the run goes ahead,
 * because the run itself is what says whether the link works.
 */
export async function measureLink({ serial, address, runAdbHost, count = LINK_PROBE_COUNT }) {
  const result = await runAdbHost({ args: ['-s', serial, 'shell', 'ping', '-c', String(count), '-W', '1', address] });
  const parsed = parseLinkRtt(result.stdout);
  if (!parsed) return { unavailable: 'link probe returned no round-trip summary' };
  return parsed;
}

/**
 * Waits for the run to finish by watching for the sync.json each role writes on completion,
 * instead of sleeping a fixed (seconds + 30).
 *
 * The fixed wait was sized for the worst case and paid on every run: the handsets are done about
 * (seconds + 7) in - the audio segment, the 2s calibration gap, the chirp, its 1s drain and the
 * host's 2s recording tail - so roughly twenty seconds of every two-minute cycle was spent
 * watching two idle phones. Polling from the start would be worse than the wait it replaces: every
 * poll is an `exec-out run-as` on a device in the middle of the measurement this harness exists to
 * take. So [floorMs] sleeps out the stretch during which no report can exist yet, and polling only
 * begins once both handsets have stopped playing.
 *
 * [budgetMs] is what is left of the old fixed wait, so a run that genuinely needs the full margin
 * is never cut short and the worst case is exactly what it was before. RunStore writes sync.json
 * through a temp file and a rename, so a poll can never catch a half-written report.
 */
export async function awaitBothReports({ client, hostSerial, sinkSerial, caseId, floorMs, budgetMs, pollMs = 2000, sleep = wait }) {
  await sleep(floorMs);
  const roles = [['host', hostSerial], ['sink', sinkSerial]];
  const reports = {};
  for (let waited = 0; ; waited += pollMs) {
    for (const [name, serial] of roles) {
      // A report already read is kept: only a role still missing is asked again. A real report
      // never carries `unavailable`, so it is a safe marker for "not there yet".
      if (reports[name] && !reports[name].unavailable) continue;
      reports[name] = await client.readSync({ serial, caseId }).catch(error => ({ unavailable: error.message }));
    }
    if (!reports.host.unavailable && !reports.sink.unavailable) return reports;
    if (waited >= budgetMs) return reports;
    await sleep(pollMs);
  }
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

/**
 * Reads every chirp pair the run played out of the one recording that holds them all.
 *
 * One slice of the recording per pair: the probe spaces the pairs by exactly [chirpIntervalSeconds]
 * in host time and the recording opens a fixed lead before the first, so slice i holds pair i and
 * nothing else. Only the search for the louder of a pair is windowed - the partner is still looked
 * for across the whole recording, so a pair straddling a slice boundary still reads correctly.
 *
 * A run without repeats searches the whole recording once and gains no `repeats` field, so it stays
 * byte-identical in shape to every artifact stored before this option existed. When there are
 * repeats the first pair still sits at the top level, for the same reason, and the rest are
 * additive: their spread is what tells a per-session residual from a per-emission one.
 */
export function readAlignment({ recorded, reference, separationMetres, chirpRepeats, chirpIntervalSeconds }) {
  const intervalFrames = (chirpIntervalSeconds ?? 0) * SAMPLE_RATE;
  const repeats = [];
  for (let index = 0; index < (chirpRepeats ?? 1); index++) {
    repeats.push(analyzeAlignment({
      recorded, reference,
      sampleRate: SAMPLE_RATE, staggerFrames: STAGGER_FRAMES, searchRadiusFrames: SEARCH_RADIUS_FRAMES,
      separationMetres,
      searchFrom: index * intervalFrames,
      searchTo: intervalFrames === 0 ? Infinity : (index + 1) * intervalFrames - 1
    }));
  }
  return repeats.length > 1 ? { ...repeats[0], repeats } : repeats[0];
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
  // Same both-or-neither rule as --low-latency, for the same reason: the alignment number is a
  // comparison between the two handsets, so a threshold applied to one loop only would put the
  // difference between the two loops straight into it.
  const reacquireRaw = value(args, '--reacquire-threshold');
  const reacquireThresholdFrames = reacquireRaw === undefined ? undefined : Number(reacquireRaw);
  if (reacquireRaw !== undefined && (!Number.isInteger(reacquireThresholdFrames) || reacquireThresholdFrames <= 0)) {
    throw new Error('--reacquire-threshold takes a positive whole number of frames. Leave it out to use the probe\'s own threshold; lower it far below the drift-sample noise floor only to force the TRACKING fallback to fire, which is the one way to see the mechanism execute on a device.');
  }
  // Only the recording device opens a capture path, but the extra goes to both roles so an
  // artifact from either side says what the run asked for. MIC is the default the whole measured
  // baseline was taken on; UNPROCESSED and VOICE_RECOGNITION are the two that skip the vendor's
  // noise suppression and beamforming - see CalibrationAudioSource for why that matters.
  const audioSource = value(args, '--audio-source');
  if (audioSource !== undefined && !CAPTURE_SOURCES.includes(audioSource)) {
    throw new Error(`--audio-source takes one of ${CAPTURE_SOURCES.join(', ')}. Leave it out for MIC, the source every measurement so far was taken on.`);
  }
  // Repeating the chirp inside ONE clock session is what separates a per-session residual from a
  // per-emission one: every pair in a run shares the same offset estimate, so a residual coming
  // from that estimate is common to them all, while playout jitter is redrawn for each pair.
  const repeatsRaw = value(args, '--chirp-repeats');
  const chirpRepeats = repeatsRaw === undefined ? undefined : Number(repeatsRaw);
  if (repeatsRaw !== undefined && (!Number.isInteger(chirpRepeats) || chirpRepeats < 1)) {
    throw new Error('--chirp-repeats takes a whole number of chirp pairs, at least 1. Leave it out for the single pair every measurement so far was taken on.');
  }
  const intervalRaw = value(args, '--chirp-interval-s');
  const chirpIntervalSeconds = intervalRaw === undefined ? undefined : Number(intervalRaw);
  if (intervalRaw !== undefined && (!Number.isInteger(chirpIntervalSeconds) || chirpIntervalSeconds < MIN_CHIRP_INTERVAL_SECONDS)) {
    throw new Error(`--chirp-interval-s takes a whole number of seconds, at least ${MIN_CHIRP_INTERVAL_SECONDS}: below that a slice of the recording cannot hold one pair on its own.`);
  }
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
  await requireBothDevicesAwake({ hostSerial, sinkSerial, runAdbHost });
  await grantRecordAudio({ serial: hostSerial, runAdbHost });
  const link = await measureLink({ serial: sinkSerial, address: hostAddress, runAdbHost });
  log(link.unavailable
    ? `link RTT           unavailable (${link.unavailable})`
    : `link RTT           min ${link.minMs} / avg ${link.avgMs} / max ${link.maxMs} ms, ${link.lossPercent}% loss`);

  for (const serial of [hostSerial, sinkSerial]) await client.clearSyncArtifacts({ serial, caseId });
  await client.startSync({ serial: hostSerial, caseId, role: 'HOST', seconds, mode, lowLatency, reacquireThresholdFrames, audioSource, chirpRepeats, chirpIntervalSeconds });
  await wait(2000);
  await client.startSync({ serial: sinkSerial, caseId, role: 'SINK', seconds, mode, hostAddress, lowLatency, reacquireThresholdFrames, audioSource, chirpRepeats, chirpIntervalSeconds });
  log(`Both roles started for ${seconds}s on the ${lowLatency ? 'low latency' : 'default'} output path. Keep the room quiet and do not touch either phone.`);
  const reports = await awaitBothReports({
    client, hostSerial, sinkSerial, caseId,
    floorMs: (seconds + COMPLETION_FLOOR_SECONDS) * 1000,
    budgetMs: COMPLETION_BUDGET_SECONDS * 1000
  });

  const directory = resolve(root, 'sync', caseId);
  await mkdir(directory, { recursive: true });
  // The link goes in the same artifact as the reports so a stored run always carries the condition
  // it was taken under, not just its result.
  await writeFile(resolve(directory, 'sync-reports.json'), `${JSON.stringify({ link, ...reports }, null, 2)}\n`, 'utf8');

  // The sink now waits (bounded at 40s) for its clock offset estimate to converge before it
  // plays anything; on that timeout sync.json carries a failureCode instead of estimates, and
  // neither phone ever wrote calibration.wav/chirp.wav. Skip the doomed export/analysis so the
  // person at the terminal gets a clear failure line instead of a WAV-export stack trace.
  const failureCode = reports.host?.failureCode ?? reports.sink?.failureCode;
  let alignment = null;
  if (mode === 'FULL' && !failureCode) {
    await client.exportNamedWav({ serial: hostSerial, caseId, fileName: 'calibration.wav', path: resolve(directory, 'calibration.wav') });
    await client.exportNamedWav({ serial: hostSerial, caseId, fileName: 'chirp.wav', path: resolve(directory, 'chirp.wav') });
    alignment = readAlignment({
      recorded: readPcm16(await readFile(resolve(directory, 'calibration.wav'))),
      reference: readPcm16(await readFile(resolve(directory, 'chirp.wav'))),
      separationMetres, chirpRepeats, chirpIntervalSeconds
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
    const pairs = alignment.repeats ?? [alignment];
    pairs.forEach((pair, index) => {
      const label = pairs.length > 1 ? `alignment error #${index + 1}` : 'alignment error   ';
      log(`${label} ${pair.alignmentErrorMs?.toFixed(3) ?? 'n/a'} ms  (${pair.confidence})`);
    });
    // The whole point of repeating: pairs sharing one clock session should agree far better than
    // separate runs do if the run-to-run scatter is the offset estimate's own residual.
    const measured = pairs.map(pair => pair.alignmentErrorMs).filter(value => value != null);
    if (measured.length > 1) log(`  within-session   spread ${(Math.max(...measured) - Math.min(...measured)).toFixed(3)} ms across ${measured.length} pairs`);
    log(`  of which air     ${alignment.propagationCorrectionMs.toFixed(3)} ms added back for ${separationMetres} m of separation`);
  }
  return { reports, alignment, link };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
