import test from 'node:test';
import assert from 'node:assert/strict';
import { describeScan, main } from '../src/cli/scan-pair.mjs';

const DEVICES = 'List of devices attached\nHOSTSERIAL\tdevice\nSINKSERIAL\tdevice\n';
const CODE = 'soundmesh-pairing 2 da3fe1c00de55dc6 192.168.43.1 45124';

function harness({ payloads = [], ...rest } = {}) {
  const calls = [];
  const remaining = [...payloads];
  return {
    calls,
    options: {
      runAdbHost: async ({ args }) => { if (args[3] === 'pm') calls.push(`grant ${args[1]}`); return { exitCode: 0, stdout: DEVICES, stderr: '' }; },
      client: {
        startCodeDisplay: async ({ serial }) => { calls.push(`show ${serial}`); },
        clearScannedPairing: async ({ serial }) => { calls.push(`clear ${serial}`); },
        startScan: async ({ serial }) => { calls.push(`scan ${serial}`); },
        readScannedPairing: async () => remaining.shift() ?? null
      },
      log: () => {},
      ...rest
    }
  };
}

test('reads back the four things a pairing code says', () => {
  assert.deepEqual(describeScan(`${CODE}\n`), {
    version: '2',
    hostId: 'da3fe1c00de55dc6',
    address: '192.168.43.1',
    port: 45124
  });
});

test('refuses to describe something that is not a pairing code', () => {
  assert.throws(() => describeScan('https://example.invalid'), /not a pairing code/);
});

// The clear has to happen before the scanner opens, or a scanner nobody pointed at anything
// reports the host from the last session and reads as a success.
test('puts the code up, clears the old host, then opens the scanner', async () => {
  const { calls, options } = harness({ payloads: [null, CODE] });

  const scan = await main(['--sink-serial', 'SINKSERIAL', '--host-serial', 'HOSTSERIAL'], options);

  assert.deepEqual(calls, ['grant SINKSERIAL', 'show HOSTSERIAL', 'clear SINKSERIAL', 'scan SINKSERIAL']);
  assert.equal(scan.hostId, 'da3fe1c00de55dc6');
});

test('scans without a host serial, for a host already showing its code', async () => {
  const { calls, options } = harness({ payloads: [CODE] });

  await main(['--sink-serial', 'SINKSERIAL'], options);

  assert.deepEqual(calls, ['grant SINKSERIAL', 'clear SINKSERIAL', 'scan SINKSERIAL']);
});

test('refuses to guess which attached handset is the scanner', async () => {
  const { calls, options } = harness({ payloads: [CODE] });

  await assert.rejects(main([], options), /--sink-serial/);
  assert.deepEqual(calls, []);
});

test('refuses one serial for both roles', async () => {
  const { calls, options } = harness({ payloads: [CODE] });

  await assert.rejects(main(['--sink-serial', 'SINKSERIAL', '--host-serial', 'SINKSERIAL'], options), /different devices/);
  assert.deepEqual(calls, []);
});

test('fails rather than returning nothing when no code is scanned', async () => {
  const { options } = harness({ payloads: [] });

  await assert.rejects(main(['--sink-serial', 'SINKSERIAL', '--timeout-s', '5'], options), /Nothing scanned/);
});
