import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createProbeClient } from '../probe-client.mjs';
import { analyzeAlignment, combineFacingPair } from '../calibration-analysis.mjs';
import { createAdbHostRunner, parseAdbDevices } from '../adb.mjs';
import { assertCaseDirectoryIsFree, storedCaseIds } from '../case-directory.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../artifacts');
const value = (args, key) => { const index = args.indexOf(key); return index === -1 ? undefined : args[index + 1]; };
const wait = ms => new Promise(done => setTimeout(done, ms));
const SAMPLE_RATE = 48000;
export const STAGGER_FRAMES = SAMPLE_RATE / 2;
const PROBE_PACKAGE = 'com.soundmesh.probe';
// The probe's own external files directory - the one place on the handset this tool writes.
// adb reaches it without run-as, and the app reads it with getExternalFilesDir(null).
const PROBE_FILES_DIR = `/sdcard/Android/data/${PROBE_PACKAGE}/files`;
// The shape SyncActivity matches on its side. A name it would reject is refused here instead,
// because the probe falls back to the generated tone rather than failing - and a run that
// quietly played a sine under a music run's name is worse than one that never started.
const SAFE_SOURCE_FILE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
/**
 * Which handset is the access point. `hotspot` is the host itself, `shared` is anything else -
 * a router, or a third handset - where both are ordinary clients. Two values are enough because
 * what a reader needs to know is whether the host role and the AP role are the same device.
 */
const NETWORK_MODES = ['hotspot', 'shared'];

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

/**
 * Extra polling allowed per chirp pair, for the correlation the handset now runs on itself.
 *
 * A guess, and knowingly a loose one - the same measurement on a PC took about a second a pair,
 * and how much slower a handset is at it has never been measured. Being too generous costs a
 * couple of minutes on a run that has genuinely hung; being too tight throws away a run that
 * worked. Revisit once a run has reported its own elapsed time.
 */
const ON_DEVICE_ANALYSIS_SECONDS_PER_PAIR = 20;

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
 * Floor on the drift loop's deadband. DriftController's own comment puts the measured drift-sample
 * noise floor at about a twentieth of its 48 frame default, so roughly 2.4 frames; at or below that
 * the loop chases its own noise, which jitters worse than leaving the error uncorrected. Five keeps
 * a narrowed run clear of it while still being a tenth of the production band.
 */
const MIN_DEADBAND_FRAMES = 5;

/** The clock cadence range the probe honours; outside it the probe clamps to its own default. */
const MIN_CLOCK_INTERVAL_MS = 100;
const MAX_CLOCK_INTERVAL_MS = 10_000;

/**
 * Ceiling on the chirp repeat count, set by the probe's scheduler capacity.
 *
 * Every repeat is queued up front, in one go, and each costs six of the scheduler's 150 chunk
 * slots. The queue is not empty when they arrive: submission happens the moment the audio loop
 * ends, with the whole 1.5s chunk lead - about 75 chunks - still pending. Twenty repeats asked for
 * 120 slots on top of that and a P1 run duly reported 40 dropped to overflow. Twelve keeps the
 * total under capacity with the lead still in flight.
 */
export const MAX_CHIRP_REPEATS = 12;

/**
 * Ceiling on the magnitude of the standing alignment correction, in milliseconds. The two chirps
 * are told apart by a 500 ms stagger, and the correction moves the sink's; at or past the stagger
 * the peaks cross and analyzeAlignment's premise fails. Half the stagger leaves the ordering
 * unambiguous while being an order of magnitude above the ~35 ms the pair actually needs.
 */
export const MAX_ALIGNMENT_OFFSET_MS = 250;

/** How long a capture run waits for the person to answer the consent dialog, in seconds. */
const DEFAULT_CONSENT_TIMEOUT_SECONDS = 120;

/**
 * A listener on SyncActivity.CHUNK_PORT (45124 = 0xB044) in state 0A.
 *
 * The remote address is matched by shape rather than by width, because the two tables do not
 * agree on it: tcp writes 8 hex characters and tcp6 writes 32. A pattern fixed at the IPv4 width
 * reads a live tcp6 listener as absent, and an unbound ServerSocket is exactly where it lands -
 * which is how O30 was lost with the host running perfectly the whole time.
 */
const LISTENING_ON_CHUNK_PORT = /:B044\s+[0-9A-F]+:[0-9A-F]{4}\s+0A\b/i;

/**
 * Shape of an Android package name: dot separated segments, each starting with a letter. Deliberately
 * strict - the value travels into an `am start` argument list on the handset, so anything that is not
 * a package name has no business getting that far.
 */
const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;

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
 * Reads the handset model behind each role, so a stored run says which device produced it.
 *
 * The finding this harness is chasing follows the device, not the role, and until now no artifact
 * recorded which handset played which part - recovering it afterwards meant inferring it from the
 * renderer's output buffer size, which only works while the two models happen to differ there.
 * The model is written; the serial never is, on the same terms as every error message here.
 *
 * Nulls rather than throwing: this is provenance about a run that has already produced its numbers,
 * and a getprop that comes back empty is not a reason to discard them.
 */
export async function readDeviceModels({ hostSerial, sinkSerial, runAdbHost }) {
  const read = async serial => {
    const result = await runAdbHost({ args: ['-s', serial, 'shell', 'getprop', 'ro.product.model'] }).catch(() => null);
    if (!result || result.exitCode !== 0) return null;
    const model = (Buffer.isBuffer(result.stdout) ? result.stdout.toString('utf8') : String(result.stdout ?? '')).trim();
    return model === '' ? null : model;
  };
  return { host: await read(hostSerial), sink: await read(sinkSerial) };
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
/**
 * The earliest either handset can have written its report, in seconds from the run's start.
 *
 * The audio segment is only the first part of a run: the chirp schedule keeps playing after it,
 * and the report is not written until the last recording closes. Leaving the repeats out of this
 * made the harness give up mid-run - the polling budget hid it at two repeats and it broke at
 * four, exporting a recording the probe was still writing.
 */
export function completionFloorSeconds({ seconds, chirpRepeats, chirpIntervalSeconds }) {
  const scheduleSeconds = ((chirpRepeats ?? 1) - 1) * (chirpIntervalSeconds ?? 0);
  return seconds + scheduleSeconds + COMPLETION_FLOOR_SECONDS;
}

/**
 * How long to keep asking for a report after the earliest it could exist.
 *
 * The flat budget was set when a handset wrote its report the moment the run ended. It now reads
 * its own recording first, and every chirp pair is another windowed cross-correlation - work that
 * takes seconds, not milliseconds, and whose cost on a handset is exactly what the next run is
 * meant to measure. A budget too small does not degrade the run, it discards it: the reports are
 * declared missing while both handsets are working normally.
 *
 * The allowance is therefore deliberately generous rather than tuned. Tighten it once the cost has
 * been measured rather than guessed at.
 */
export function completionBudgetSeconds({ chirpRepeats }) {
  return COMPLETION_BUDGET_SECONDS + (chirpRepeats ?? 0) * ON_DEVICE_ANALYSIS_SECONDS_PER_PAIR;
}

/**
 * Refuses a run where either handset never wrote its report.
 *
 * Not a courtesy check. A report missing at the end of the budget means the probe was still
 * running, and the recording exported from under it can still hold both chirps and correlate into
 * a perfectly plausible number - a failed run reported as a measurement. `failureCode` cannot
 * catch this: an unavailable report has no fields at all, so the failure guard reads undefined and
 * waves it through.
 */
export function requireBothReports(reports) {
  for (const [role, name] of [['Host', 'host'], ['Sink', 'sink']]) {
    if (reports[name]?.unavailable) {
      throw new Error(`${role} never reported (${reports[name].unavailable}). The run did not finish; nothing has been analysed.`);
    }
  }
}

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
 * Half-width of the window a later pair is looked for in, once the first pair has been located.
 *
 * Half a second is far wider than anything that can move a pair inside a run: the whole point of
 * the drift loop is to hold the two handsets together to within milliseconds, and even a loop that
 * had given up entirely would need 100 ppm running for over an hour to travel this far. It is also
 * comfortably inside the shortest interval the CLI accepts, so a window can never reach a
 * neighbouring pair.
 */
export const ANCHOR_RADIUS_FRAMES = SAMPLE_RATE / 2;

/**
 * Where to look for pair [pairIndex], given where the first pair turned out to be.
 *
 * Without an anchor the only bound is the pair's own slice of the recording, and that slice is as
 * long as the interval - so the search cost grows with how far apart the pairs are asked to sit.
 * That is what stopped a run from spanning more than a couple of minutes: three pairs a minute
 * apart already took a minute to read, and the question worth asking next (does the alignment hold
 * over the length of a song, or a party?) needs pairs spread over far longer than that.
 *
 * With the first pair located, every later one is due exactly [pairIndex] intervals after it, so a
 * fixed radius around that instant bounds the search no matter how long the interval is.
 */
export function pairSearchWindow({ pairIndex, intervalFrames, anchorIndex }) {
  if (anchorIndex !== null && pairIndex > 0) {
    // The anchor is where the first chirp of pair 0 landed, so `due` is where this pair's FIRST
    // chirp is expected - and the pair runs a stagger past it. A window of one radius either side
    // of `due` would therefore end exactly on the second chirp, which is not a margin at all.
    // It cost real measurements: on the host's recording the second chirp is the partner's, from
    // across the room, and it is the one that correlates loudest, so the outer search returned it
    // pinned to that edge. O45 to O47 disagreed with the handsets on exactly the pairs whose
    // second chirp fell one to seven frames beyond it.
    const due = anchorIndex + pairIndex * intervalFrames;
    return { searchFrom: due - ANCHOR_RADIUS_FRAMES, searchTo: due + STAGGER_FRAMES + ANCHOR_RADIUS_FRAMES };
  }
  return {
    searchFrom: pairIndex * intervalFrames,
    searchTo: intervalFrames === 0 ? Infinity : (pairIndex + 1) * intervalFrames - 1
  };
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
  const read = window => analyzeAlignment({
    recorded, reference,
    sampleRate: SAMPLE_RATE, staggerFrames: STAGGER_FRAMES, searchRadiusFrames: SEARCH_RADIUS_FRAMES,
    separationMetres, ...window
  });
  const repeats = [];
  let anchorIndex = null;
  for (let index = 0; index < (chirpRepeats ?? 1); index++) {
    const slice = pairSearchWindow({ pairIndex: index, intervalFrames, anchorIndex: null });
    const anchored = anchorIndex === null ? null : read(pairSearchWindow({ pairIndex: index, intervalFrames, anchorIndex }));
    // Falls back to the pair's whole slice whenever the anchored window came up empty, so a drift
    // wider than the radius costs time rather than a lost measurement.
    const pair = anchored?.confidence === 'OK' ? anchored : read(slice);
    if (index === 0 && pair.confidence === 'OK') anchorIndex = pair.firstIndex;
    repeats.push(pair);
  }
  return repeats.length > 1 ? { ...repeats[0], repeats } : repeats[0];
}

/**
 * Pairs up the two handsets' recordings of the same run, pair by pair.
 *
 * Positional, and safely so: both recordings open on the same host instant and are sliced on the
 * same interval, so slice i of one holds exactly the pair slice i of the other does. The guard is
 * there because mispairing is silent - pair 1 combined with pair 2 still produces a plausible
 * number - so a run whose two sides read a different number of pairs reports nothing instead.
 *
 * Returns null on a run where only the host recorded, which is what leaves the artifact and the
 * log identical in shape to every run stored before the sink recorded at all.
 */
export function combineFacingRun({ hostSide, sinkSide }) {
  const pairsOf = side => side?.repeats ?? (side ? [side] : []);
  const hostPairs = pairsOf(hostSide);
  const sinkPairs = pairsOf(sinkSide);
  if (hostPairs.length === 0 || hostPairs.length !== sinkPairs.length) return null;
  return hostPairs.map((pair, index) => combineFacingPair({ hostSide: pair, sinkSide: sinkPairs[index] }));
}

/**
 * The lines describing what the two handsets concluded on their own, with no PC in the loop.
 *
 * Printed beside this script's own numbers rather than instead of them. The on-device analysis is
 * a line-for-line port of the PC one precisely so that every alignment number ever recorded stays
 * comparable, and a port only stays a port if the agreement is checked on every run - checking it
 * once, by hand, tests the day it was written.
 *
 * The disagreement is the worst single pair, never the mean: two pairs wrong by the same amount in
 * opposite directions average to nothing, so a mean would report a port that disagrees everywhere
 * as one that agrees perfectly. One frame is 0.021 ms, so six decimals is enough to see the
 * smallest disagreement the correlator can even express.
 */
export function pairedAlignmentLines({ paired, facing }) {
  if (!paired) return [];
  if (paired.failure) {
    return [`on-device pairing  FAILED (${paired.failure}) - host ${paired.hostPairs} pairs, sink ${paired.sinkPairs ?? 'n/a'}`];
  }
  const ms = value => (value == null ? 'n/a' : value.toFixed(3));
  const verdict = paired.verdict;
  const lines = [
    `on-device verdict  ${verdict.passed ? 'PASS' : `FAIL (${verdict.failures.join(', ')})`}`,
    `  cluster mean     ${ms(verdict.clusterMeanMs)} ms over ${verdict.clusterCount} pairs, sd ${ms(verdict.clusterSdMs)} ms`,
    `  worst single     ${ms(verdict.maxAbsMs)} ms`
  ];
  const worst = worstDisagreementMs(paired.pairs, facing);
  if (worst != null) lines.push(`  vs PC            worst pair differs by ${worst.toFixed(6)} ms`);
  return lines;
}

/**
 * The line describing the calibration loop: what the sink stood on this run, where that came
 * from, and what it will stand on next time.
 *
 * The constant used to be carried by a person - read out of one run's output, typed into the next
 * run's command line - which is the last step of the calibration that needed a PC at all. It now
 * travels between the handsets, and this line is how a person at the terminal can still see it.
 *
 * "kept" and "zeroed" are opposite outcomes and must not read alike: a run whose verdict could not
 * be trusted leaves the sink on the correction it already had.
 *
 * The observation and the run counts are printed beside the estimate because the estimate is a
 * running mean: it deliberately moves only a fraction of the way to what this run saw, and without
 * both numbers a loop that is working looks like one that is stuck.
 */
export function calibrationLines({ sink }) {
  if (!sink || sink.alignmentOffsetSource === undefined) return [];
  const ms = micros => (micros / 1000).toFixed(3);
  const seen = sink.alignmentOffsetObservations;
  const runs = seen === undefined ? '' : `, ${seen} runs`;
  const parts = [];
  // First, because it says whose correction this is. `anonymous` is the run that was handed an
  // address and never learned who answered, so nothing it stores is attached to a partner.
  if (sink.alignmentOffsetPeer !== undefined) parts.push(`peer ${sink.alignmentOffsetPeer}`);
  parts.push(`applied ${ms(sink.alignmentOffsetMicros)} ms (${sink.alignmentOffsetSource}${runs})`);
  if (sink.observedOffsetMicros != null) parts.push(`observed ${ms(sink.observedOffsetMicros)} ms`);
  parts.push(
    sink.adoptedOffsetMicros == null
      ? 'kept'
      : `next run ${ms(sink.adoptedOffsetMicros)} ms${seen === undefined ? '' : ` (${seen + 1} runs)`}`
  );
  return [`calibration        ${parts.join(', ')}`];
}


/**
 * The line describing the code the host put on its screen.
 *
 * Printed even though nothing scans it yet: the payload is what a scanner will have to read, and
 * having it in the run's own output is what lets a scan be checked against what was shown rather
 * than against what the code was supposed to say.
 *
 * A host with no code is not a failure. It means the handset could not name one address a peer in
 * the room would reach, which mDNS never had to answer because there the sink resolves it.
 */
export function pairingLines({ host }) {
  if (!host || host.pairingCode === undefined) return [];
  if (host.pairingCode === null) {
    return ['pairing            no code: the host has no single address a peer could reach'];
  }
  const [, , hostId, address, port] = host.pairingCode.split(' ');
  return [`pairing            peer ${hostId} at ${address}:${port}`];
}

/**
 * What the sink read off the host's screen, when that is how it found it.
 *
 * The counterpart of the pairing line: that one says what the host offered, this one says what a
 * handset across the room actually got out of it. A run where the two disagree is a run where the
 * code on disk is stale, which is otherwise only visible as a connection that will not open.
 */
export function scanLines({ sink }) {
  if (!sink?.scan) return [];
  return [`scanned            peer ${sink.scan.hostId} at ${sink.scan.address}:${sink.scan.port}`];
}
/** Null rather than zero when there is nothing to compare: no evidence is not agreement. */
function worstDisagreementMs(devicePairs, pcPairs) {
  if (!Array.isArray(devicePairs) || !Array.isArray(pcPairs)) return null;
  if (devicePairs.length !== pcPairs.length) return null;
  const differences = devicePairs
    .map((pair, index) => (pair && pcPairs[index] ? Math.abs(pair.alignmentErrorMs - pcPairs[index].alignmentErrorMs) : null))
    .filter(value => value != null);
  return differences.length === 0 ? null : Math.max(...differences);
}

/**
 * Waits until the host is listening on SyncActivity.CHUNK_PORT, which it only binds once the run
 * itself is under way.
 *
 * A capture run cannot start both roles on a fixed stagger. The host stops at a consent dialog and
 * binds nothing until a person has answered it, so the sink's head start guaranteed a
 * ConnectException that no tapping speed could avoid - which is exactly how O29 was lost.
 *
 * Both tables are read because a ServerSocket with no bound address lands on the IPv6 wildcard, and
 * a listener present in tcp6 alone would otherwise read as absent.
 */
/**
 * Puts the audio file the host should stream into the probe's own directory, and answers with
 * the bare name the run passes on - the activity fixes the directory, so only a name travels.
 *
 * A missing local file is left for adb to report: it names the path it could not stat, which is
 * the same thing a check here would have said and one fewer place for the two to disagree.
 */
export async function pushSourceFile({ serial, localPath, runAdbHost }) {
  // basename('asset/') answers 'asset', so a directory would pass the name check and adb would
  // push the whole directory. The trailing separator is the only thing that tells them apart.
  if (/[/\\]$/.test(localPath)) throw new Error(`--source-file needs a file, not a directory: ${JSON.stringify(localPath)} ends with a path separator.`);
  const name = basename(localPath);
  if (!SAFE_SOURCE_FILE.test(name)) {
    throw new Error(`--source-file needs a file whose name starts with a letter or digit and holds only letters, digits, dot, dash and underscore, up to 64 characters. ${JSON.stringify(name)} is not one, and the probe would silently fall back to the generated tone.`);
  }
  // The directory exists only once the app has called getExternalFilesDir(), which it does inside
  // a run - so on a fresh install the first push would fail on a directory adb can simply make.
  await runAdbHost({ args: ['-s', serial, 'shell', 'mkdir', '-p', PROBE_FILES_DIR] });
  const result = await runAdbHost({ args: ['-s', serial, 'push', localPath, `${PROBE_FILES_DIR}/${name}`], timeoutMs: 120_000 });
  if (result.exitCode !== 0) {
    const detail = `${result.stdout ?? ''}${result.stderr ?? ''}`.trim();
    throw new Error(`Pushing ${localPath} to the host failed: ${detail || `adb exited ${result.exitCode}`}`);
  }
  return name;
}

export async function awaitHostListening({ serial, runAdbHost, timeoutMs, log = () => {}, pollMs = 1000 }) {
  const deadline = Date.now() + timeoutMs;
  let announced = false;
  while (Date.now() < deadline) {
    const result = await runAdbHost({ args: ['-s', serial, 'shell', 'cat', '/proc/net/tcp', '/proc/net/tcp6'] });
    if (result.exitCode === 0 && LISTENING_ON_CHUNK_PORT.test(result.stdout)) return;
    if (!announced) {
      log('Waiting for the capture consent dialog on the host. Answer it now - the sink starts once the host is listening.');
      announced = true;
    }
    await wait(pollMs);
  }
  throw new Error(`The host never started listening within ${Math.round(timeoutMs / 1000)}s of launch. A capture run waits on the consent dialog: answer it on the host, or raise --consent-timeout-s.`);
}

export async function main(args = process.argv.slice(2), { client = createProbeClient(), log = text => process.stdout.write(`${text}\n`), runAdbHost = createAdbHostRunner(), listStoredCases = storedCaseIds } = {}) {
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
  // Deliberately NOT a both-or-neither flag: the host records unconditionally, so this only ever
  // adds the second recording. What it buys is the flight time between the handsets, which the two
  // recordings carry with opposite signs and which therefore cancels when they are combined - so
  // --separation-m stops being load-bearing and becomes a cross-check against a measured distance.
  const sinkRecords = args.includes('--sink-records');
  // The sink finds the host on the network instead of being handed its address. --host-address
  // stays required either way: this tool still pings it for the link RTT line, and the sink
  // simply ignores it once discovery is on.
  const discover = args.includes('--discover');
  // The sink uses the host it last scanned off a screen, and spends no discovery window at all.
  // --host-address stays required for the same reason it does under --discover: this tool still
  // pings it for the link RTT line, and the sink ignores it.
  const pairedHost = args.includes('--paired');
  if (pairedHost && discover) {
    throw new Error('--paired and --discover are two ways for the sink to find its host, and the report would not say which one it used. Pass one. --paired needs a scan first: run scan-pair.mjs against the sink.');
  }
  // Same both-or-neither rule as --low-latency, for the same reason: the alignment number is a
  // comparison between the two handsets, so a threshold applied to one loop only would put the
  // difference between the two loops straight into it.
  const reacquireRaw = value(args, '--reacquire-threshold');
  const reacquireThresholdFrames = reacquireRaw === undefined ? undefined : Number(reacquireRaw);
  if (reacquireRaw !== undefined && (!Number.isInteger(reacquireThresholdFrames) || reacquireThresholdFrames <= 0)) {
    throw new Error('--reacquire-threshold takes a positive whole number of frames. Leave it out to use the probe\'s own threshold; lower it far below the drift-sample noise floor only to force the TRACKING fallback to fire, which is the one way to see the mechanism execute on a device.');
  }
  // Both-or-neither again, and for a second reason on top of the first: the renderer's trim band
  // and the scheduler's early release are the two halves of one mirror, and the probe derives both
  // from this one number so they cannot be set apart.
  const trimRaw = value(args, '--trim-frames');
  const trimFrames = trimRaw === undefined ? undefined : Number(trimRaw);
  if (trimRaw !== undefined && (!Number.isInteger(trimFrames) || trimFrames <= 0 || trimFrames > 480)) {
    throw new Error('--trim-frames takes a whole number of frames between 1 and 480 (half a chunk). Leave it out to use the probe\'s own 48-frame band, which every archived run was measured on. A product session showed the default edits the waveform 6.6 times a second and a listener hears it; this is how the alignment cost of a wider band gets measured.');
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
  if (repeatsRaw !== undefined && (!Number.isInteger(chirpRepeats) || chirpRepeats < 1 || chirpRepeats > MAX_CHIRP_REPEATS)) {
    throw new Error(`--chirp-repeats takes a whole number of chirp pairs, 1 to ${MAX_CHIRP_REPEATS}. Leave it out for the single pair every measurement so far was taken on.`);
  }
  const intervalRaw = value(args, '--chirp-interval-s');
  const chirpIntervalSeconds = intervalRaw === undefined ? undefined : Number(intervalRaw);
  if (intervalRaw !== undefined && (!Number.isInteger(chirpIntervalSeconds) || chirpIntervalSeconds < MIN_CHIRP_INTERVAL_SECONDS)) {
    throw new Error(`--chirp-interval-s takes a whole number of seconds, at least ${MIN_CHIRP_INTERVAL_SECONDS}: below that a slice of the recording cannot hold one pair on its own.`);
  }
  // Both-or-neither, like the other loop overrides. The deadband is the leading explanation for the
  // per-emission scatter: the loop corrects nothing inside +/-48 frames, so an emission lands
  // wherever the uncorrected error happens to sit, and a uniform draw across that band has a
  // standard deviation of 0.577 ms against the 0.510 ms measured. Narrowing it is the direct test -
  // if the scatter is the band, it shrinks with the band.
  // Mirrors the range the probe itself honours - it clamps anything outside silently, and a run
  // that collected at the default while its notes claim otherwise is worse than one that refused.
  const clockIntervalRaw = value(args, '--clock-interval-ms');
  const clockIntervalMs = clockIntervalRaw === undefined ? undefined : Number(clockIntervalRaw);
  if (clockIntervalRaw !== undefined && (!Number.isInteger(clockIntervalMs) || clockIntervalMs < MIN_CLOCK_INTERVAL_MS || clockIntervalMs > MAX_CLOCK_INTERVAL_MS)) {
    throw new Error(`--clock-interval-ms takes a whole number of milliseconds, ${MIN_CLOCK_INTERVAL_MS} to ${MAX_CLOCK_INTERVAL_MS}. Leave it out for the production cadence every measurement so far was taken on.`);
  }
  // Standing correction for the fixed part of the acoustic error, sink only - the host plays on
  // its own clock and has no host-time conversion to correct. Pass back the alignmentErrorMs a
  // previous run reported, verbatim and with its sign; the sink subtracts it from its view of host
  // time, so a negative error advances the sink and the next run should read near zero.
  //
  // Bounded by the stagger: a correction that large would move the sink's chirp past the host's and
  // the two would stop being tellable apart, which analyzeAlignment relies on.
  const alignmentOffsetRaw = value(args, '--alignment-offset-ms');
  const alignmentOffsetMs = alignmentOffsetRaw === undefined ? undefined : Number(alignmentOffsetRaw);
  if (alignmentOffsetRaw !== undefined && (!Number.isFinite(alignmentOffsetMs) || Math.abs(alignmentOffsetMs) >= MAX_ALIGNMENT_OFFSET_MS)) {
    throw new Error(`--alignment-offset-ms takes a number of milliseconds, magnitude under ${MAX_ALIGNMENT_OFFSET_MS}. Pass back the alignmentErrorMs a previous run reported, sign and all. Leave it out for the uncorrected run every measurement so far was taken on.`);
  }
  const alignmentOffsetMicros = alignmentOffsetMs === undefined ? undefined : Math.round(alignmentOffsetMs * 1000);

  // The package whose playback the host captures and streams in place of the generated tone. Its
  // presence is the switch: without it the host generates, which is what every measurement so far
  // was taken on.
  //
  // The probe's own package is refused because the host plays back the very stream it is capturing.
  // Capturing itself is a feedback loop, and it would run and build a file rather than fail.
  const capturePackage = value(args, '--capture-package');
  // The local path of an audio file the host decodes and streams in place of the tone. Unlike
  // capture this needs no consent, and the host plays only what it streams - so the pair can be
  // judged by ear, which a capture run cannot be: the tap does not mute the app it taps.
  const sourceFilePath = value(args, '--source-file');
  if (sourceFilePath !== undefined && capturePackage !== undefined) {
    throw new Error('--source-file and --capture-package are two sources for one loop; the host would play one of them and the report would not say which. Pass one.');
  }
  const consentTimeoutRaw = value(args, '--consent-timeout-s');
  const consentTimeoutSeconds = consentTimeoutRaw === undefined ? DEFAULT_CONSENT_TIMEOUT_SECONDS : Number(consentTimeoutRaw);
  if (consentTimeoutRaw !== undefined && (!Number.isInteger(consentTimeoutSeconds) || consentTimeoutSeconds < 1)) {
    throw new Error('--consent-timeout-s takes a whole number of seconds, at least 1: it is how long a capture run waits for the consent dialog to be answered on the host.');
  }
  if (capturePackage !== undefined && (!PACKAGE_NAME.test(capturePackage) || capturePackage === PROBE_PACKAGE)) {
    throw new Error(`--capture-package takes the package name of the app whose audio the host should stream, such as com.tencent.qqmusic. It cannot be ${PROBE_PACKAGE}: the host plays what it captures, so capturing the probe feeds its own output back into itself.`);
  }

  const deadbandRaw = value(args, '--deadband-frames');
  const deadbandFrames = deadbandRaw === undefined ? undefined : Number(deadbandRaw);
  if (deadbandRaw !== undefined && (!Number.isInteger(deadbandFrames) || deadbandFrames < MIN_DEADBAND_FRAMES)) {
    throw new Error(`--deadband-frames takes a whole number of frames, at least ${MIN_DEADBAND_FRAMES}: below that the drift loop chases its own measurement noise. Leave it out for the production band every measurement so far was taken on.`);
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
  // Required for the same reason the separation is, and learned the same way. Every run before
  // this one was made on the host's own hotspot and not one report says so, so months later the
  // configuration had to be recalled rather than read - and was recalled wrongly.
  //
  // It is stated rather than sniffed: reading the live WiFi state needs permissions this app has
  // no other use for, and would report what the phone is doing rather than what the operator
  // meant. The distinction that matters is whether the host is also the access point, because
  // that welds the host role to the AP role - the leading candidate for the role residual, since
  // sink-to-host is a contended STA-to-AP uplink while host-to-sink is not.
  const networkMode = value(args, '--network-mode');
  if (!NETWORK_MODES.includes(networkMode)) {
    throw new Error(`Use --network-mode <${NETWORK_MODES.join('|')}>: say whether the host is itself the access point (hotspot) or both handsets are clients of some other one (shared). It changes how a run should be read and cannot be recovered afterwards.`);
  }
  // Revalidates both confirmed serials immediately before any device action: with two phones
  // attached at once, a mistyped or swapped serial would otherwise silently target the wrong one.
  const directory = resolve(root, 'sync', caseId);
  // Before either handset is touched: a case ID clash costs a whole stored batch, and finding out
  // after the run has played is finding out far too late.
  assertCaseDirectoryIsFree({ caseId, taken: await listStoredCases(resolve(root, 'sync')), overwrite: args.includes('--overwrite') });
  await requireBothSerialsAuthorized({ hostSerial, sinkSerial, runAdbHost });
  await requireBothDevicesAwake({ hostSerial, sinkSerial, runAdbHost });
  await grantRecordAudio({ serial: hostSerial, runAdbHost });
  if (sinkRecords) await grantRecordAudio({ serial: sinkSerial, runAdbHost });
  const link = await measureLink({ serial: sinkSerial, address: hostAddress, runAdbHost });
  log(link.unavailable
    ? `link RTT           unavailable (${link.unavailable})`
    : `link RTT           min ${link.minMs} / avg ${link.avgMs} / max ${link.maxMs} ms, ${link.lossPercent}% loss`);

  for (const serial of [hostSerial, sinkSerial]) await client.clearSyncArtifacts({ serial, caseId });
  // Before the run, not during it: a push that fails after the handsets have started would
  // leave them playing the tone under a run named for a song.
  const sourceFile = sourceFilePath === undefined ? undefined : await pushSourceFile({ serial: hostSerial, localPath: sourceFilePath, runAdbHost });
  await client.startSync({ serial: hostSerial, caseId, role: 'HOST', seconds, mode, lowLatency, discover, reacquireThresholdFrames, trimFrames, audioSource, chirpRepeats, chirpIntervalSeconds, deadbandFrames, capturePackage, sourceFile, networkMode });
  // A generated run keeps the stagger it always had: the host binds its ports immediately. A
  // capture run has to wait for a person, so it waits on the port rather than on a clock.
  if (capturePackage) await awaitHostListening({ serial: hostSerial, runAdbHost, timeoutMs: consentTimeoutSeconds * 1000, log });
  else await wait(2000);
  await client.startSync({ serial: sinkSerial, caseId, role: 'SINK', seconds, mode, hostAddress, lowLatency, discover, reacquireThresholdFrames, trimFrames, audioSource, chirpRepeats, chirpIntervalSeconds, deadbandFrames, sinkRecords, clockIntervalMs, alignmentOffsetMicros, networkMode, paired: pairedHost });
  log(`Both roles started for ${seconds}s on the ${lowLatency ? 'low latency' : 'default'} output path. Keep the room quiet and do not touch either phone.`);
  const reports = await awaitBothReports({
    client, hostSerial, sinkSerial, caseId,
    floorMs: completionFloorSeconds({ seconds, chirpRepeats, chirpIntervalSeconds }) * 1000,
    budgetMs: completionBudgetSeconds({ chirpRepeats }) * 1000
  });
  await mkdir(directory, { recursive: true });
  // The link goes in the same artifact as the reports so a stored run always carries the condition
  // it was taken under, not just its result.
  const models = await readDeviceModels({ hostSerial, sinkSerial, runAdbHost });
  await writeFile(resolve(directory, 'sync-reports.json'), `${JSON.stringify({ link, models, ...reports }, null, 2)}\n`, 'utf8');
  log(`roles              host ${models.host ?? 'unknown'} / sink ${models.sink ?? 'unknown'}`);
  // After the artifact is written, not before: a run that did not finish is still worth keeping the
  // link and whatever did report, which is the only evidence of why it did not.
  requireBothReports(reports);

  // The sink now waits (bounded at 40s) for its clock offset estimate to converge before it
  // plays anything; on that timeout sync.json carries a failureCode instead of estimates, and
  // neither phone ever wrote calibration.wav/chirp.wav. Skip the doomed export/analysis so the
  // person at the terminal gets a clear failure line instead of a WAV-export stack trace.
  const failureCode = reports.host?.failureCode ?? reports.sink?.failureCode;
  let alignment = null;
  let facing = null;
  if (mode === 'FULL' && !failureCode) {
    await client.exportNamedWav({ serial: hostSerial, caseId, fileName: 'calibration.wav', path: resolve(directory, 'calibration.wav') });
    await client.exportNamedWav({ serial: hostSerial, caseId, fileName: 'chirp.wav', path: resolve(directory, 'chirp.wav') });
    const reference = readPcm16(await readFile(resolve(directory, 'chirp.wav')));
    alignment = readAlignment({
      recorded: readPcm16(await readFile(resolve(directory, 'calibration.wav'))),
      reference, separationMetres, chirpRepeats, chirpIntervalSeconds
    });
    if (sinkRecords) {
      await client.exportNamedWav({ serial: sinkSerial, caseId, fileName: 'calibration.wav', path: resolve(directory, 'calibration-sink.wav') });
      // Read with no separation on purpose: the single-sided correction assumes the host geometry
      // and the sink's is its mirror, so applying it here would double the error rather than
      // remove it. Combining the two sides takes the flight time out without being told it.
      const sinkSide = readAlignment({
        recorded: readPcm16(await readFile(resolve(directory, 'calibration-sink.wav'))),
        reference, separationMetres: 0, chirpRepeats, chirpIntervalSeconds
      });
      facing = combineFacingRun({ hostSide: alignment, sinkSide });
      await writeFile(resolve(directory, 'alignment-sink.json'), `${JSON.stringify(sinkSide, null, 2)}\n`, 'utf8');
    }
    await writeFile(resolve(directory, 'alignment.json'), `${JSON.stringify(facing ? { ...alignment, facing } : alignment, null, 2)}\n`, 'utf8');
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
  if (facing) {
    facing.forEach((pair, index) => {
      const label = facing.length > 1 ? `two-sided error #${index + 1}` : 'two-sided error   ';
      log(pair
        ? `${label} ${pair.alignmentErrorMs.toFixed(3)} ms, over a measured ${pair.separationMetres.toFixed(2)} m`
        : `${label} n/a  (one side of the pair could not be read)`);
    });
    // The distance is an independent reading of the same room, so a wide disagreement with the
    // tape says something is wrong with the measurement chain itself, not with the alignment.
    const measured = facing.filter(Boolean).map(pair => pair.separationMetres);
    if (measured.length > 0) {
      const mean = measured.reduce((total, value) => total + value, 0) / measured.length;
      log(`  separation       measured ${mean.toFixed(2)} m against ${separationMetres} m entered`);
    }
  }
  // Last, so the handsets' own answer reads as a check on everything above it rather than as
  // another number among them.
  pairedAlignmentLines({ paired: reports.host?.pairedAlignment, facing }).forEach(line => log(line));
  pairingLines({ host: reports.host }).forEach(line => log(line));
  scanLines({ sink: reports.sink }).forEach(line => log(line));
  calibrationLines({ sink: reports.sink }).forEach(line => log(line));
  return { reports, alignment, facing, link };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
