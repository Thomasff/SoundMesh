import { fileURLToPath } from 'node:url';
import { createAdbHostRunner, parseAdbDevices, requireAuthorizedSerial } from '../adb.mjs';
import { createProbeClient } from '../probe-client.mjs';

const value = (args, key) => { const index = args.indexOf(key); return index === -1 ? undefined : args[index + 1]; };
const wait = ms => new Promise(resolveTimer => setTimeout(resolveTimer, ms));

const POLL_INTERVAL_MS = 1_000;
const DEFAULT_TIMEOUT_SECONDS = 120;

const PROBE_PACKAGE = 'com.soundmesh.probe';

/**
 * Grants CAMERA to the probe on the scanning handset.
 *
 * Granted here rather than left to the dialog for one reason that matters: the prompt appears over
 * the preview, and answering it means tapping the very phone that is being aimed. The scan itself
 * is the one act in this system where a hand on a handset is expected, and there is no reason to
 * add a second one. Only the scanner is granted, and only the role is ever named.
 */
export async function grantCamera({ serial, runAdbHost }) {
  const result = await runAdbHost({ args: ['-s', serial, 'shell', 'pm', 'grant', PROBE_PACKAGE, 'android.permission.CAMERA'] });
  if (result.exitCode !== 0) throw new Error('Granting CAMERA to the probe on the scanning device failed');
}

/**
 * Turns the stored line back into the four things it says.
 *
 * Deliberately not a second parser: the fields are read positionally out of the same text the
 * handset itself validated, so what this prints is what the phone accepted rather than a second
 * opinion about it.
 */
export function describeScan(payload) {
  const [magic, version, hostId, address, port] = String(payload).trim().split(' ');
  if (magic !== 'soundmesh-pairing') throw new Error('The sink stored something that is not a pairing code');
  return { version, hostId, address, port: Number(port) };
}

/**
 * Pairs the two handsets: the host puts its code up, the sink reads it and keeps it.
 *
 * Its own command rather than part of a run, because it is the one step in this whole system that
 * needs a hand on a phone - somebody holds the sink up to the host's screen. A run measures timing
 * that being touched destroys, so the two acts must not overlap. Afterwards every run can start
 * with --paired and nobody touches anything.
 */
export async function main(args = process.argv.slice(2), {
  runAdbHost = createAdbHostRunner(),
  client = createProbeClient(),
  log = text => process.stdout.write(`${text}\n`)
} = {}) {
  const requestedSink = value(args, '--sink-serial');
  const requestedHost = value(args, '--host-serial');
  const timeoutSeconds = Number(value(args, '--timeout-s') || DEFAULT_TIMEOUT_SECONDS);
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 5) throw new Error('--timeout-s takes a whole number of seconds, at least 5');
  // Named rather than guessed, for the same reason discovery refuses two hosts: with both handsets
  // attached the quiet choice would put a scanned host on the wrong phone and say nothing.
  if (!requestedSink) throw new Error('Use --sink-serial: with both handsets attached, the scanning one has to be named.');
  if (requestedSink === requestedHost) throw new Error('--host-serial and --sink-serial must refer to different devices');
  const devices = await runAdbHost({ args: ['devices', '-l'] });
  if (devices.exitCode !== 0) throw new Error('ADB device discovery failed');
  const attached = parseAdbDevices(devices.stdout);
  const sinkSerial = requireAuthorizedSerial(attached, requestedSink);
  const hostSerial = requestedHost === undefined ? undefined : requireAuthorizedSerial(attached, requestedHost);

  await grantCamera({ serial: sinkSerial, runAdbHost });
  if (hostSerial) await client.startCodeDisplay({ serial: hostSerial });
  // Cleared first so what this reports is what this scan read. Otherwise a scanner nobody pointed
  // at anything reports the host from last week and reads as a success.
  await client.clearScannedPairing({ serial: sinkSerial });
  await client.startScan({ serial: sinkSerial });
  log(hostSerial
    ? 'The host is showing its code. On the sink, grant the camera prompt if it appears, then hold the sink up to the host screen.'
    : 'Scanner open on the sink. Grant the camera prompt if it appears, then hold the sink up to the code on the host screen.');

  const deadline = Date.now() + timeoutSeconds * 1000;
  while (Date.now() < deadline) {
    await wait(POLL_INTERVAL_MS);
    const payload = await client.readScannedPairing({ serial: sinkSerial });
    if (!payload) continue;
    const scan = describeScan(payload);
    log(`scanned            peer ${scan.hostId} at ${scan.address}:${scan.port}`);
    log('Put both handsets back where they belong. The next run can use --paired instead of --discover.');
    return scan;
  }
  throw new Error(`Nothing scanned within ${timeoutSeconds}s. The sink stored no pairing code.`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
