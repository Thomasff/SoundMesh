import test from 'node:test';
import assert from 'node:assert/strict';
import { main, stopOnly } from '../src/cli/run-session.mjs';

const DEVICES = 'List of devices attached\nHOSTSERIAL\tdevice\nSINKSERIAL\tdevice\n';

function harness({ ...rest } = {}) {
  const calls = [];
  return {
    calls,
    options: {
      runAdbHost: async ({ args }) => {
        if (args.includes('push')) calls.push(`push ${args[args.length - 1]}`);
        return { exitCode: 0, stdout: DEVICES, stderr: '' };
      },
      client: {
        startSession: async ({ serial, role, sourceFile }) => { calls.push(`start ${role} ${serial}${sourceFile ? ` ${sourceFile}` : ''}`); },
        stopSession: async ({ serial }) => { calls.push(`stop ${serial}`); }
      },
      log: () => {},
      sinkDelayMs: 0,
      ...rest
    }
  };
}

const RUN = ['--host-serial', 'HOSTSERIAL', '--sink-serial', 'SINKSERIAL', '--source-file', 'C:\\music\\song.mp3', '--keep'];

// The file has to be on the host before either role starts. A push that failed afterwards would
// leave one handset playing and the other waiting on a file that never arrived.
test('pushes the source before starting the host', async () => {
  const { calls, options } = harness();

  await main(RUN, options);

  assert.equal(calls[0], 'push /sdcard/Android/data/com.soundmesh.probe/files/song.mp3');
  assert.equal(calls[1], 'start HOST HOSTSERIAL song.mp3');
});

// The sink dials the host, so a sink that starts first connects to nothing.
test('starts the host before the sink', async () => {
  const { calls, options } = harness();

  await main(RUN, options);

  assert.deepEqual(calls.slice(1), ['start HOST HOSTSERIAL song.mp3', 'start SINK SINKSERIAL']);
});

// The sink is never told an address: it plays for the host whose code it scanned, and a second
// answer to that question is a second thing that can be wrong.
test('names no host address for the sink', async () => {
  const { calls, options } = harness();

  await main(RUN, options);

  assert.equal(calls.filter(call => /192\.168|address/.test(call)).length, 0);
});

test('leaves both handsets running when asked to keep them', async () => {
  const { calls, options } = harness();

  const result = await main(RUN, options);

  assert.equal(result.stopped, false);
  assert.equal(calls.filter(call => call.startsWith('stop')).length, 0);
});

test('refuses a run that does not name both roles', async () => {
  const { options } = harness();

  await assert.rejects(main(['--host-serial', 'HOSTSERIAL', '--source-file', 'x.mp3'], options), /has to be named/);
});

test('refuses a host with no file to play', async () => {
  const { options } = harness();

  await assert.rejects(main(['--host-serial', 'HOSTSERIAL', '--sink-serial', 'SINKSERIAL'], options), /--source-file/);
});

test('refuses one serial in both roles', async () => {
  const { options } = harness();

  await assert.rejects(main(['--host-serial', 'A', '--sink-serial', 'A', '--source-file', 'x.mp3'], options), /different devices/);
});

// Stopping starts nothing, so it works on handsets whose session this command never started -
// which is the only way to end a --keep run.
test('stops both handsets without starting anything', async () => {
  const { calls, options } = harness();

  const result = await stopOnly(['--host-serial', 'HOSTSERIAL', '--sink-serial', 'SINKSERIAL'], options);

  assert.equal(result.stopped, 2);
  assert.deepEqual(calls, ['stop HOSTSERIAL', 'stop SINKSERIAL']);
});

test('refuses a stop that names no handset', async () => {
  const { options } = harness();

  await assert.rejects(stopOnly([], options), /which handsets/);
});
