import { fileURLToPath } from 'node:url';
import { createAdbHostRunner, parseAdbDevices, requireAuthorizedSerial } from '../adb.mjs';
import { createProbeClient } from '../probe-client.mjs';
import { pushSourceFile } from './run-sync.mjs';

const value = (args, key) => { const index = args.indexOf(key); return index === -1 ? undefined : args[index + 1]; };
const wait = ms => new Promise(resolveTimer => setTimeout(resolveTimer, ms));

/** A frame count off the command line, or undefined when the flag was not given at all. */
const frames = (args, key) => {
  const requested = value(args, key);
  if (requested === undefined) return undefined;
  const parsed = Number(requested);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > MAX_FRAMES) {
    throw new Error(`${key} takes a whole number of frames between 1 and ${MAX_FRAMES} (half a chunk)`);
  }
  return parsed;
};

const DEFAULT_SECONDS = 60;
const SINK_DELAY_MS = 2_000;
// The service reads its counters and writes them while shutting down; a read that raced it would
// find the file the previous session left, which is the one mistake this whole readout must not make.
const REPORT_DELAY_MS = 1_500;
// Half a chunk. Past that a band is a different scheme rather than a wider setting, and the device
// refuses the same way - so refusing here means the refusal is visible before anything starts.
const MAX_FRAMES = 480;

/**
 * Runs the product path rather than the harness: a session with no end instant, no chirp and no
 * report.
 *
 * The harness answers "how far apart were these two handsets"; this answers "does it keep playing".
 * They are separate commands because they are separate code - growing the harness into the product
 * would mean every alignment measurement after that point was taken with a different ruler.
 *
 * The sink takes its host from the code it scanned, so scan-pair has to have run at least once.
 * Nothing here names an address.
 */
export async function main(args = process.argv.slice(2), {
  runAdbHost = createAdbHostRunner(),
  client = createProbeClient(),
  log = text => process.stdout.write(`${text}\n`),
  sinkDelayMs = SINK_DELAY_MS,
  reportDelayMs = REPORT_DELAY_MS
} = {}) {
  const requestedHost = value(args, '--host-serial');
  const requestedSink = value(args, '--sink-serial');
  const sourceFilePath = value(args, '--source-file');
  const keep = args.includes('--keep');
  // Absent means the loop's own default. The value exists to be raised above the ~56-frame
  // emission quantum one handset moves in, which the 48-frame default sits below.
  const deadbandFrames = frames(args, '--deadband-frames');
  // The other band, and the one that actually gates the waveform edits: the drift loop's deadband
  // was tried first at 48 and 96 frames and moved the trim rate not at all (3.30/s against 3.32/s).
  const trimFrames = frames(args, '--trim-frames');
  const seconds = Number(value(args, '--seconds') || DEFAULT_SECONDS);
  if (!requestedHost || !requestedSink) throw new Error('Use --host-serial and --sink-serial: with both handsets attached, each role has to be named.');
  if (requestedHost === requestedSink) throw new Error('--host-serial and --sink-serial must refer to different devices');
  if (!sourceFilePath) throw new Error('Use --source-file: a product host plays a file, and there is no tone to fall back on.');
  if (!Number.isInteger(seconds) || seconds < 5) throw new Error('--seconds takes a whole number of seconds, at least 5');

  const devices = await runAdbHost({ args: ['devices', '-l'] });
  if (devices.exitCode !== 0) throw new Error('ADB device discovery failed');
  const attached = parseAdbDevices(devices.stdout);
  const hostSerial = requireAuthorizedSerial(attached, requestedHost);
  const sinkSerial = requireAuthorizedSerial(attached, requestedSink);

  // Cleared first so what a stop reports is what this session produced. A stale report from the
  // last session reads exactly like a fresh one and would be compared against as if it were.
  for (const serial of [hostSerial, sinkSerial]) await client.clearSessionReport({ serial });
  // Before either handset starts, for the same reason the harness does it: a push that failed
  // afterwards would leave one phone playing and the other waiting on a file that never arrived.
  const sourceFile = await pushSourceFile({ serial: hostSerial, localPath: sourceFilePath, runAdbHost });
  await client.startSession({ serial: hostSerial, role: 'HOST', sourceFile, deadbandFrames, trimFrames });
  // The sink retries its first connection, so this delay decides how many attempts it wastes, not
  // whether it succeeds. The earlier version had no retry and this delay in its place; the host
  // was still decoding, and the refused connection killed the sink's process.
  await wait(sinkDelayMs);
  await client.startSession({ serial: sinkSerial, role: 'SINK', deadbandFrames, trimFrames });
  log(`session started     host plays ${sourceFile}, sink follows the host it scanned`);

  if (keep) {
    log('Left running. Stop it with --stop-only when you are done.');
    return { sourceFile, stopped: false };
  }
  await wait(seconds * 1000);
  await client.stopSession({ serial: sinkSerial });
  await client.stopSession({ serial: hostSerial });
  log(`session stopped     after ${seconds}s`);
  // The service writes its counters while shutting down, so the file is not there the instant the
  // stop intent is delivered.
  await wait(reportDelayMs);
  const reports = [];
  for (const [role, serial] of [['host', hostSerial], ['sink', sinkSerial]]) {
    const report = await client.readSessionReport({ serial });
    reports.push({ role, report });
    for (const line of reportLines({ role, report })) log(line);
  }
  return { sourceFile, stopped: true, reports };
}

/**
 * The renderer's counters as rates, because rates are what the question needs.
 *
 * A count on its own cannot separate "a click every now and then" from "a click forty times a
 * second", and that separation is the one the last crackle hunt turned on - a fix that removed a
 * real defect changed nothing a listener could hear, because the defect fired 1.8 times a second
 * against a symptom heard dozens of times a second.
 *
 * The run's length is derived from `played` rather than timed here: chunks are 20 ms by
 * construction, so the renderer's own count is a better clock than the wall this command watched.
 */
export function reportLines({ role, report }) {
  if (!report) return [`${role.padEnd(5)} no report`];
  const seconds = (report.played ?? 0) * 0.02;
  const rate = count => (seconds > 0 ? (count / seconds).toFixed(2) : '-');
  const trims = report.releaseTrims ?? 0;
  const perTrim = trims > 0 ? (report.trimmedFrames / trims).toFixed(0) : '-';
  const lines = [
    `${role.padEnd(5)} played ${report.played} chunks (${seconds.toFixed(0)}s)`,
    `      trims ${trims} (${rate(trims)}/s, mean ${perTrim} frames deleted)`,
    `      silence writes ${report.silenceWrites ?? 0} (${rate(report.silenceWrites ?? 0)}/s)`,
    `      dropped late ${report.droppedLate ?? 0}, overflow ${report.droppedOverflow ?? 0}, underruns ${report.trackUnderruns ?? 0}`
  ];
  // Only a sink has these: only a sink dials anybody, and only a sink converts through a clock it
  // did not author. Printed when present rather than as a row of zeroes on the host, which would
  // read as a host that survived nothing rather than one with nothing to survive.
  if (report.reconnects !== undefined) {
    const worstMs = (report.worstUncertaintyNanos ?? 0) / 1e6;
    lines.push(`      reconnects ${report.reconnects}, rediscoveries ${report.rediscoveries ?? 0}, clock ${report.clockHealth ?? '-'} (worst ${worstMs.toFixed(2)} ms)`);
  }
  return lines;
}

/** Stops whatever is running on both handsets, without starting anything. */
export async function stopOnly(args = process.argv.slice(2), {
  runAdbHost = createAdbHostRunner(),
  client = createProbeClient(),
  log = text => process.stdout.write(`${text}\n`),
  reportDelayMs = REPORT_DELAY_MS
} = {}) {
  const devices = await runAdbHost({ args: ['devices', '-l'] });
  if (devices.exitCode !== 0) throw new Error('ADB device discovery failed');
  const attached = parseAdbDevices(devices.stdout);
  const roles = [['host', '--host-serial'], ['sink', '--sink-serial']]
    .map(([role, key]) => ({ role, requested: value(args, key) }))
    .filter(({ requested }) => requested !== undefined)
    .map(({ role, requested }) => ({ role, serial: requireAuthorizedSerial(attached, requested) }));
  if (roles.length === 0) throw new Error('Use --host-serial and --sink-serial to say which handsets to stop.');
  for (const { serial } of roles) await client.stopSession({ serial });
  log(`session stopped     on ${roles.length} handset${roles.length === 1 ? '' : 's'}`);
  // The service writes its counters as it shuts down, so the file is not there the instant the
  // stop intent is delivered.
  await wait(reportDelayMs);
  const reports = [];
  for (const { role, serial } of roles) {
    const report = await client.readSessionReport({ serial });
    reports.push({ role, report });
    for (const line of reportLines({ role, report })) log(line);
  }
  return { stopped: roles.length, reports };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.includes('--stop-only')) stopOnly(args); else main(args);
}
