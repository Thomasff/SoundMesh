import test from 'node:test';
import assert from 'node:assert/strict';
import { main, reportLines, stopOnly } from '../src/cli/run-session.mjs';

const DEVICES = 'List of devices attached\nHOSTSERIAL\tdevice\nSINKSERIAL\tdevice\n';

function harness({ reports = [], ...rest } = {}) {
  const calls = [];
  const remaining = [...reports];
  return {
    calls,
    options: {
      runAdbHost: async ({ args }) => {
        if (args.includes('push')) calls.push(`push ${args[args.length - 1]}`);
        return { exitCode: 0, stdout: DEVICES, stderr: '' };
      },
      client: {
        startSession: async ({ serial, role, sourceFile, deadbandFrames, trimFrames }) => {
          const extras = [
            sourceFile,
            deadbandFrames === undefined ? null : `deadband=${deadbandFrames}`,
            trimFrames === undefined ? null : `trim=${trimFrames}`
          ].filter(Boolean);
          calls.push(`start ${role} ${serial}${extras.length ? ` ${extras.join(' ')}` : ''}`);
        },
        stopSession: async ({ serial }) => { calls.push(`stop ${serial}`); },
        clearSessionReport: async ({ serial }) => { calls.push(`clear ${serial}`); },
        readSessionReport: async () => remaining.shift() ?? null
      },
      log: () => {},
      sinkDelayMs: 0,
      reportDelayMs: 0,
      ...rest
    }
  };
}

const RUN = ['--host-serial', 'HOSTSERIAL', '--sink-serial', 'SINKSERIAL', '--source-file', 'C:\\music\\song.mp3', '--keep'];
const starts = calls => calls.filter(call => call.startsWith('start'));

// The file has to be on the host before either role starts. A push that failed afterwards would
// leave one handset playing and the other waiting on a file that never arrived.
test('pushes the source before starting the host', async () => {
  const { calls, options } = harness();

  await main(RUN, options);

  assert.ok(calls.indexOf('push /sdcard/Android/data/com.soundmesh.probe/files/song.mp3') < calls.indexOf('start HOST HOSTSERIAL song.mp3'));
});

// A stale report from the last session reads exactly like a fresh one, and would be compared
// against as if it were.
test('clears both stale reports before anything starts', async () => {
  const { calls, options } = harness();

  await main(RUN, options);

  assert.ok(calls.indexOf('clear HOSTSERIAL') < calls.indexOf('start HOST HOSTSERIAL song.mp3'));
  assert.ok(calls.indexOf('clear SINKSERIAL') < calls.indexOf('start SINK SINKSERIAL'));
});

// The sink dials the host, so a sink that starts first connects to nothing.
test('starts the host before the sink', async () => {
  const { calls, options } = harness();

  await main(RUN, options);

  assert.deepEqual(starts(calls), ['start HOST HOSTSERIAL song.mp3', 'start SINK SINKSERIAL']);
});

// Absent means the loop's own default, so a session started with no opinion has to be spelled the
// same way as one started before the flag existed - otherwise the control arm is a third arm.
test('sends no deadband when none was asked for', async () => {
  const { calls, options } = harness();

  await main(RUN, options);

  assert.equal(calls.filter(call => call.includes('deadband')).length, 0);
});

// Both handsets edit their own waveform, so an experiment that moved only one arm would compare a
// changed handset against an unchanged one rather than two settings against each other.
test('sends the deadband to both roles when one is asked for', async () => {
  const { calls, options } = harness();

  await main([...RUN, '--deadband-frames', '120'], options);

  assert.deepEqual(starts(calls), ['start HOST HOSTSERIAL song.mp3 deadband=120', 'start SINK SINKSERIAL deadband=120']);
});

test('refuses a deadband past half a chunk', async () => {
  const { options } = harness();

  await assert.rejects(main([...RUN, '--deadband-frames', '900'], options), /between 1 and 480/);
});

// A widened trim band and a scheduler left on the default early release is not a wider band, it is
// a one-sided one - the arrangement O38 removed. The device derives the pair from this one number,
// so both arms of the mirror move together or neither does.
test('sends the trim band to both roles when one is asked for', async () => {
  const { calls, options } = harness();

  await main([...RUN, '--trim-frames', '240'], options);

  assert.deepEqual(starts(calls), ['start HOST HOSTSERIAL song.mp3 trim=240', 'start SINK SINKSERIAL trim=240']);
});

test('refuses a trim band past half a chunk', async () => {
  const { options } = harness();

  await assert.rejects(main([...RUN, '--trim-frames', '481'], options), /between 1 and 480/);
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

// A count on its own cannot tell "now and then" from "dozens a second", and that is the whole
// question. The run's length comes from the chunk count because chunks are 20ms by construction.
test('turns counts into rates against the length the chunks imply', () => {
  const lines = reportLines({
    role: 'host',
    report: { played: 3000, releaseTrims: 90, trimmedFrames: 6120, silenceWrites: 30, droppedLate: 2, droppedOverflow: 0, trackUnderruns: 0 }
  });

  assert.match(lines[0], /played 3000 chunks \(60s\)/);
  assert.match(lines[1], /trims 90 \(1\.50\/s, mean 68 frames deleted\)/);
  assert.match(lines[2], /silence writes 30 \(0\.50\/s\)/);
});

// A handset that never ran leaves no file, and saying so beats printing a row of zeroes that
// reads like a session which played nothing.
test('says so when a handset left no report', () => {
  assert.deepEqual(reportLines({ role: 'sink', report: null }), ['sink  no report']);
});

test('reads a report back from each handset it stopped', async () => {
  const { options } = harness({ reports: [{ played: 100, releaseTrims: 1, trimmedFrames: 60 }, null] });

  const result = await stopOnly(['--host-serial', 'HOSTSERIAL', '--sink-serial', 'SINKSERIAL'], options);

  assert.equal(result.reports.length, 2);
  assert.equal(result.reports[0].role, 'host');
  assert.equal(result.reports[1].report, null);
});
