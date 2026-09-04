import { fileURLToPath } from 'node:url';
import { createAdbHostRunner, parseAdbDevices, requireAuthorizedSerial } from '../adb.mjs';
import { createProbeClient } from '../probe-client.mjs';
import { pushSourceFile } from './run-sync.mjs';

const value = (args, key) => { const index = args.indexOf(key); return index === -1 ? undefined : args[index + 1]; };
const wait = ms => new Promise(resolveTimer => setTimeout(resolveTimer, ms));

const DEFAULT_SECONDS = 60;
const SINK_DELAY_MS = 2_000;

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
  sinkDelayMs = SINK_DELAY_MS
} = {}) {
  const requestedHost = value(args, '--host-serial');
  const requestedSink = value(args, '--sink-serial');
  const sourceFilePath = value(args, '--source-file');
  const keep = args.includes('--keep');
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

  // Before either handset starts, for the same reason the harness does it: a push that failed
  // afterwards would leave one phone playing and the other waiting on a file that never arrived.
  const sourceFile = await pushSourceFile({ serial: hostSerial, localPath: sourceFilePath, runAdbHost });
  await client.startSession({ serial: hostSerial, role: 'HOST', sourceFile });
  // The host has to be listening before the sink dials it. The harness solved this with a
  // readiness check on the host's own socket; here the delay is enough because nothing is being
  // measured - a sink that connects late simply joins late.
  await wait(sinkDelayMs);
  await client.startSession({ serial: sinkSerial, role: 'SINK' });
  log(`session started     host plays ${sourceFile}, sink follows the host it scanned`);

  if (keep) {
    log('Left running. Stop it with --stop-only when you are done.');
    return { sourceFile, stopped: false };
  }
  await wait(seconds * 1000);
  await client.stopSession({ serial: sinkSerial });
  await client.stopSession({ serial: hostSerial });
  log(`session stopped     after ${seconds}s`);
  return { sourceFile, stopped: true };
}

/** Stops whatever is running on both handsets, without starting anything. */
export async function stopOnly(args = process.argv.slice(2), {
  runAdbHost = createAdbHostRunner(),
  client = createProbeClient(),
  log = text => process.stdout.write(`${text}\n`)
} = {}) {
  const devices = await runAdbHost({ args: ['devices', '-l'] });
  if (devices.exitCode !== 0) throw new Error('ADB device discovery failed');
  const attached = parseAdbDevices(devices.stdout);
  const serials = ['--host-serial', '--sink-serial']
    .map(key => value(args, key))
    .filter(requested => requested !== undefined)
    .map(requested => requireAuthorizedSerial(attached, requested));
  if (serials.length === 0) throw new Error('Use --host-serial and --sink-serial to say which handsets to stop.');
  for (const serial of serials) await client.stopSession({ serial });
  log(`session stopped     on ${serials.length} handset${serials.length === 1 ? '' : 's'}`);
  return { stopped: serials.length };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.includes('--stop-only')) stopOnly(args); else main(args);
}
