import assert from 'node:assert/strict';
import test from 'node:test';
import {
  awaitHostListening, main, assertAuthorizedPair, requireBothSerialsAuthorized, grantRecordAudio, assertAwake, requireBothDevicesAwake, awaitBothReports, parseLinkRtt, measureLink, readAlignment, combineFacingRun, readDeviceModels, completionFloorSeconds, requireBothReports, pushSourceFile, pairSearchWindow, ANCHOR_RADIUS_FRAMES, MAX_CHIRP_REPEATS } from '../src/cli/run-sync.mjs';

const SAMPLE_RATE = 48000;

/** A deterministic sweep standing in for the chirp the probe saves alongside the recording. */
function referenceSweep(frames = 5760) {
  const out = new Int16Array(frames);
  for (let index = 0; index < frames; index++) {
    const t = index / SAMPLE_RATE;
    const phase = 2 * Math.PI * (1000 * t + ((8000 - 1000) / 0.12) * t * t / 2);
    const window = 0.5 * (1 - Math.cos(2 * Math.PI * index / (frames - 1)));
    out[index] = Math.round(Math.sin(phase) * window * 12000);
  }
  return out;
}

const HOST_SERIAL = 'HOSTSERIALABC123';
const SINK_SERIAL = 'SINKSERIALXYZ789';

function devicesOutput(lines) {
  return `List of devices attached\n${lines.join('\n')}\n`;
}

test('proceeds when both host and sink serials are attached and authorised', async () => {
  const runAdbHost = async call => {
    assert.deepEqual(call.args, ['devices', '-l']);
    return {
      exitCode: 0,
      stdout: devicesOutput([
        `${HOST_SERIAL}    device product:pixel model:Pixel_7 transport_id:1`,
        `${SINK_SERIAL}    device product:pixel model:Pixel_6 transport_id:2`
      ]),
      stderr: ''
    };
  };
  await assert.doesNotReject(() => requireBothSerialsAuthorized({ hostSerial: HOST_SERIAL, sinkSerial: SINK_SERIAL, runAdbHost }));
});

test('refuses a serial that is missing from the attached devices without echoing it', async () => {
  const runAdbHost = async () => ({
    exitCode: 0,
    stdout: devicesOutput([`${HOST_SERIAL}    device product:pixel model:Pixel_7 transport_id:1`]),
    stderr: ''
  });
  const error = await requireBothSerialsAuthorized({ hostSerial: HOST_SERIAL, sinkSerial: SINK_SERIAL, runAdbHost }).catch(caught => caught);
  assert.match(error.message, /Sink serial is not among the attached, authorised devices/);
  assert.equal(error.message.includes(SINK_SERIAL), false);
});

test('refuses an unauthorised or offline serial without echoing it', async () => {
  const runAdbHost = async () => ({
    exitCode: 0,
    stdout: devicesOutput([
      `${HOST_SERIAL}    device product:pixel model:Pixel_7 transport_id:1`,
      `${SINK_SERIAL}    unauthorized transport_id:2`
    ]),
    stderr: ''
  });
  const error = await requireBothSerialsAuthorized({ hostSerial: HOST_SERIAL, sinkSerial: SINK_SERIAL, runAdbHost }).catch(caught => caught);
  assert.match(error.message, /Sink serial is attached but not authorised \(state: unauthorized\)/);
  assert.equal(error.message.includes(SINK_SERIAL), false);

  const offlineRunAdbHost = async () => ({
    exitCode: 0,
    stdout: devicesOutput([
      `${HOST_SERIAL}    offline transport_id:1`,
      `${SINK_SERIAL}    device product:pixel model:Pixel_6 transport_id:2`
    ]),
    stderr: ''
  });
  const offlineError = await requireBothSerialsAuthorized({ hostSerial: HOST_SERIAL, sinkSerial: SINK_SERIAL, runAdbHost: offlineRunAdbHost }).catch(caught => caught);
  assert.match(offlineError.message, /Host serial is attached but not authorised \(state: offline\)/);
  assert.equal(offlineError.message.includes(HOST_SERIAL), false);
});

test('refuses identical host and sink serials as one phone silently playing both roles', () => {
  assert.throws(
    () => assertAuthorizedPair(
      [{ serial: HOST_SERIAL, state: 'device', details: '' }],
      { hostSerial: HOST_SERIAL, sinkSerial: HOST_SERIAL }
    ),
    /--host-serial and --sink-serial must refer to different devices/
  );
});

test('main validates both serials before touching either device', async () => {
  const calls = [];
  const client = {
    clearSyncArtifacts: async call => calls.push(['clearSyncArtifacts', call]),
    startSync: async call => calls.push(['startSync', call]),
    readSync: async call => calls.push(['readSync', call]),
    exportNamedWav: async call => calls.push(['exportNamedWav', call])
  };
  const runAdbHost = async () => ({
    exitCode: 0,
    stdout: devicesOutput([`${HOST_SERIAL}    device product:pixel model:Pixel_7 transport_id:1`]),
    stderr: ''
  });
  await assert.rejects(
    () => main(['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2'], { client, runAdbHost, log: () => {}, listStoredCases: async () => [] }),
    /Sink serial is not among the attached, authorised devices/
  );
  assert.deepEqual(calls, []);
});

test('refuses a run that has not measured the distance between the handsets', async () => {
  const calls = [];
  const runAdbHost = async call => { calls.push(call.args); return { exitCode: 0, stdout: '', stderr: '' }; };
  const client = { clearSyncArtifacts: async () => calls.push(['client']) };
  const run = extra => main(
    ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', ...extra],
    { client, runAdbHost, log: () => {}, listStoredCases: async () => [] }
  );

  await assert.rejects(() => run([]), /Use --separation-m/);
  await assert.rejects(() => run(['--separation-m', 'about a metre']), /Use --separation-m/);
  // Refused before ADB is touched at all, so nothing on either phone is disturbed.
  assert.deepEqual(calls, []);
});

test('grants RECORD_AUDIO on the recording device before either role starts', async () => {
  const order = [];
  const runAdbHost = async call => {
    order.push(`adb ${call.args.join(' ')}`);
    if (call.args[0] === 'devices') {
      return {
        exitCode: 0,
        stdout: devicesOutput([
          `${HOST_SERIAL}    device product:pixel model:Pixel_7 transport_id:1`,
          `${SINK_SERIAL}    device product:pixel model:Pixel_6 transport_id:2`
        ]),
        stderr: ''
      };
    }
    // main now refuses to run against a sleeping handset, so both fixtures have to answer the
    // wakefulness probe; these stand in for awake devices.
    if (call.args.includes('dumpsys')) return { exitCode: 0, stdout: 'mWakefulness=Awake', stderr: '' };
    return { exitCode: 0, stdout: '', stderr: '' };
  };
  const client = {
    clearSyncArtifacts: async () => { order.push('client clearSyncArtifacts'); },
    startSync: async () => { order.push('client startSync'); throw new Error('stop the run here'); }
  };

  await assert.rejects(
    () => main(['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2'], { client, runAdbHost, log: () => {}, listStoredCases: async () => [] }),
    /stop the run here/
  );

  // Order is the point of this test: everything that only reads state comes first, and nothing
  // that changes a device happens until both serials are confirmed and both handsets are awake.
  assert.equal(order[0], 'adb devices -l');
  assert.equal(order[1], `adb -s ${HOST_SERIAL} shell dumpsys power`);
  assert.equal(order[2], `adb -s ${SINK_SERIAL} shell dumpsys power`);
  assert.equal(order[3], `adb -s ${HOST_SERIAL} shell pm grant com.soundmesh.probe android.permission.RECORD_AUDIO`);
  assert.ok(order.indexOf('client startSync') > 3, 'the grant must come before either role starts');
});

function authorisedPairRunner(order = []) {
  return async call => {
    order.push(`adb ${call.args.join(' ')}`);
    if (call.args[0] === 'devices') {
      return {
        exitCode: 0,
        stdout: devicesOutput([
          `${HOST_SERIAL}    device product:pixel model:Pixel_7 transport_id:1`,
          `${SINK_SERIAL}    device product:pixel model:Pixel_6 transport_id:2`
        ]),
        stderr: ''
      };
    }
    if (call.args.includes('dumpsys')) return { exitCode: 0, stdout: 'mWakefulness=Awake', stderr: '' };
    // A host already listening on the chunk port, so a capture run passes the readiness gate
    // instead of sitting out its consent timeout. Tests about the gate itself supply their own.
    if (call.args.includes('/proc/net/tcp')) return { exitCode: 0, stdout: '   0: 012BA8C0:B044 00000000:0000 0A', stderr: '' };
    return { exitCode: 0, stdout: '', stderr: '' };
  };
}

/**
 * Runs main far enough to see both startSync calls and then aborts on the second, so the test
 * never reaches the (seconds + 30)s wait for a run that has no devices behind it. It still pays
 * the 2s stagger between the two roles.
 */
async function startSyncCalls(extra) {
  const calls = [];
  const client = {
    clearSyncArtifacts: async () => {},
    startSync: async call => {
      calls.push(call);
      if (calls.length === 2) throw new Error('stop the run here');
    }
  };
  await assert.rejects(
    () => main(
      ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', ...extra],
      { client, runAdbHost: authorisedPairRunner(), log: () => {}, listStoredCases: async () => [] }
    ),
    /stop the run here/
  );
  return calls;
}

test('--low-latency puts both roles on the same output path, and its absence puts neither', async () => {
  const requested = await startSyncCalls(['--low-latency']);
  assert.deepEqual(requested.map(({ role, lowLatency }) => [role, lowLatency]), [['HOST', true], ['SINK', true]]);

  // Both handsets are compared against each other, so a run that asks for nothing must leave both
  // on the path every measurement so far was taken on - never one of each.
  const plain = await startSyncCalls([]);
  assert.deepEqual(plain.map(({ role, lowLatency }) => [role, lowLatency]), [['HOST', false], ['SINK', false]]);
});

test('refuses the run when the RECORD_AUDIO grant fails rather than recording silence', async () => {
  const runAdbHost = async () => ({ exitCode: 1, stdout: '', stderr: 'Unknown package' });
  await assert.rejects(
    () => grantRecordAudio({ serial: HOST_SERIAL, runAdbHost }),
    /Granting RECORD_AUDIO to the probe on the recording device failed/
  );
});

test('assertAwake refuses a sleeping or dozing handset and names only the role', () => {
  assert.doesNotThrow(() => assertAwake('  mWakefulness=Awake\n  mHoldingDisplay=true', 'Host'));
  for (const state of ['Asleep', 'Dozing', 'Dreaming']) {
    assert.throws(
      () => assertAwake(`mWakefulness=${state}`, 'Sink'),
      error => error.message.includes('Sink') && error.message.includes(state) && !/[0-9A-Z]{8,}/.test(error.message)
    );
  }
});

test('assertAwake refuses to guess when wakefulness is absent', () => {
  assert.throws(() => assertAwake('some unrelated dumpsys output', 'Host'), /refusing to run blind/);
});

test('requireBothDevicesAwake checks both roles and stops at the first asleep one', async () => {
  const queried = [];
  const runAdbHost = async ({ args }) => {
    queried.push(args[1]);
    return { exitCode: 0, stdout: args[1] === 'host-serial' ? 'mWakefulness=Awake' : 'mWakefulness=Asleep' };
  };
  await assert.rejects(
    requireBothDevicesAwake({ hostSerial: 'host-serial', sinkSerial: 'sink-serial', runAdbHost }),
    /Sink is Asleep/
  );
  assert.deepEqual(queried, ['host-serial', 'sink-serial']);
});

test('--reacquire-threshold reaches both roles, and its absence leaves both on the probe default', async () => {
  const lowered = await startSyncCalls(['--reacquire-threshold', '8']);
  assert.deepEqual(lowered.map(({ role, reacquireThresholdFrames }) => [role, reacquireThresholdFrames]), [['HOST', 8], ['SINK', 8]]);

  // Same reason as --low-latency: the measurement compares the two handsets against each other,
  // so a threshold on one side only would put the difference between the two loops into the number.
  const plain = await startSyncCalls([]);
  assert.deepEqual(plain.map(({ role, reacquireThresholdFrames }) => [role, reacquireThresholdFrames]), [['HOST', undefined], ['SINK', undefined]]);
});

test('refuses a reacquire threshold that is not a positive whole number of frames', async () => {
  for (const bad of ['0', '-8', 'wide', '8.5']) {
    await assert.rejects(
      () => main(
        ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', '--reacquire-threshold', bad],
        { client: {}, runAdbHost: authorisedPairRunner(), log: () => {}, listStoredCases: async () => [] }
      ),
      /--reacquire-threshold/
    );
  }
});

test('awaitBothReports returns as soon as both roles have written, without burning the budget', async () => {
  let slept = 0;
  const sleep = async ms => { slept += ms; };
  let attempts = 0;
  const client = {
    readSync: async ({ serial }) => {
      // The sink lands two polls after the host, so the pair is only complete on the third pass.
      if (serial === SINK_SERIAL && attempts++ < 2) throw new Error('no such file or directory');
      return { role: serial === HOST_SERIAL ? 'HOST' : 'SINK' };
    }
  };
  const reports = await awaitBothReports({
    client, hostSerial: HOST_SERIAL, sinkSerial: SINK_SERIAL, caseId: 'S2',
    floorMs: 95_000, budgetMs: 25_000, pollMs: 2000, sleep
  });
  assert.deepEqual(reports, { host: { role: 'HOST' }, sink: { role: 'SINK' } });
  // The floor plus exactly the two polls it took - not the whole budget the old fixed wait paid.
  assert.equal(slept, 95_000 + 2 * 2000);
});

test('awaitBothReports gives up at the budget and reports the missing role as unavailable', async () => {
  let slept = 0;
  const sleep = async ms => { slept += ms; };
  const client = {
    readSync: async ({ serial }) => {
      if (serial === SINK_SERIAL) throw new Error('no such file or directory');
      return { role: 'HOST' };
    }
  };
  const reports = await awaitBothReports({
    client, hostSerial: HOST_SERIAL, sinkSerial: SINK_SERIAL, caseId: 'S2',
    floorMs: 95_000, budgetMs: 4000, pollMs: 2000, sleep
  });
  assert.deepEqual(reports.host, { role: 'HOST' });
  assert.match(reports.sink.unavailable, /no such file or directory/);
  // Never longer than the fixed wait it replaces: floor plus budget is the old (seconds + 30).
  assert.equal(slept, 95_000 + 4000);
});

const PING_OUTPUT = [
  'PING 192.168.43.1 (192.168.43.1) 56(84) bytes of data.',
  '64 bytes from 192.168.43.1: icmp_seq=1 ttl=64 time=4.46 ms',
  '',
  '--- 192.168.43.1 ping statistics ---',
  '5 packets transmitted, 5 received, 0% packet loss, time 4005ms',
  'rtt min/avg/max/mdev = 4.467/12.169/20.627/5.123 ms',
  ''
].join('\n');

test('parseLinkRtt reads the round-trip summary and the loss figure', () => {
  assert.deepEqual(parseLinkRtt(PING_OUTPUT), { minMs: 4.467, avgMs: 12.169, maxMs: 20.627, lossPercent: 0 });
  // Some builds print the BSD spelling instead.
  const bsd = PING_OUTPUT
    .replace('rtt min/avg/max/mdev', 'round-trip min/avg/max/stddev')
    .replace('0% packet loss', '20% packet loss');
  assert.deepEqual(parseLinkRtt(bsd), { minMs: 4.467, avgMs: 12.169, maxMs: 20.627, lossPercent: 20 });
});

test('parseLinkRtt returns null rather than guessing when nothing came back', () => {
  assert.equal(parseLinkRtt('5 packets transmitted, 0 received, 100% packet loss, time 4010ms'), null);
  assert.equal(parseLinkRtt(''), null);
  assert.equal(parseLinkRtt(undefined), null);
});

test('measureLink probes from the sink towards the host and never fails the run', async () => {
  const calls = [];
  const ok = async ({ args }) => { calls.push(args); return { exitCode: 0, stdout: PING_OUTPUT, stderr: '' }; };
  assert.deepEqual(
    await measureLink({ serial: SINK_SERIAL, address: '192.168.43.1', runAdbHost: ok, count: 5 }),
    { minMs: 4.467, avgMs: 12.169, maxMs: 20.627, lossPercent: 0 }
  );
  assert.deepEqual(calls, [['-s', SINK_SERIAL, 'shell', 'ping', '-c', '5', '-W', '1', '192.168.43.1']]);

  // Diagnostic, not a gate: an unreachable host is recorded and the run still goes ahead, because
  // the run itself is the thing that says whether the link works.
  const dead = async () => ({ exitCode: 1, stdout: '', stderr: 'connect: Network is unreachable' });
  const result = await measureLink({ serial: SINK_SERIAL, address: '192.168.43.1', runAdbHost: dead, count: 5 });
  assert.ok(result.unavailable);
});

test('main measures the link before either role starts, and writes it beside the reports', async () => {
  const order = [];
  const runAdbHost = async call => {
    order.push(call.args.includes('ping') ? 'ping' : `adb ${call.args.join(' ')}`);
    if (call.args[0] === 'devices') {
      return {
        exitCode: 0,
        stdout: devicesOutput([
          `${HOST_SERIAL}    device product:pixel model:Pixel_7 transport_id:1`,
          `${SINK_SERIAL}    device product:pixel model:Pixel_6 transport_id:2`
        ]),
        stderr: ''
      };
    }
    if (call.args.includes('dumpsys')) return { exitCode: 0, stdout: 'mWakefulness=Awake', stderr: '' };
    if (call.args.includes('ping')) return { exitCode: 0, stdout: PING_OUTPUT, stderr: '' };
    return { exitCode: 0, stdout: '', stderr: '' };
  };
  const client = {
    clearSyncArtifacts: async () => {},
    startSync: async () => { order.push('client startSync'); throw new Error('stop the run here'); }
  };

  await assert.rejects(
    () => main(['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2'], { client, runAdbHost, log: () => {}, listStoredCases: async () => [] }),
    /stop the run here/
  );

  // Before, never during: a probe running alongside the measurement would be perturbing the very
  // thing being measured.
  assert.ok(order.indexOf('ping') < order.indexOf('client startSync'), 'the link probe must finish before either role starts');
});

test('--audio-source reaches both roles, and its absence leaves the probe on MIC', async () => {
  const chosen = await startSyncCalls(['--audio-source', 'UNPROCESSED']);
  assert.deepEqual(chosen.map(({ role, audioSource }) => [role, audioSource]), [['HOST', 'UNPROCESSED'], ['SINK', 'UNPROCESSED']]);

  const plain = await startSyncCalls([]);
  assert.deepEqual(plain.map(({ role, audioSource }) => [role, audioSource]), [['HOST', undefined], ['SINK', undefined]]);
});

test('refuses a capture source the probe does not implement', async () => {
  for (const bad of ['mic', 'RAW', 'CAMCORDER']) {
    await assert.rejects(
      () => main(
        ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', '--audio-source', bad],
        { client: {}, runAdbHost: authorisedPairRunner(), log: () => {}, listStoredCases: async () => [] }
      ),
      /--audio-source/
    );
  }
});

test('--chirp-repeats reaches both roles with its interval, and its absence leaves both on one chirp', async () => {
  const repeated = await startSyncCalls(['--chirp-repeats', '2', '--chirp-interval-s', '15']);
  assert.deepEqual(
    repeated.map(({ role, chirpRepeats, chirpIntervalSeconds }) => [role, chirpRepeats, chirpIntervalSeconds]),
    [['HOST', 2, 15], ['SINK', 2, 15]]
  );

  const plain = await startSyncCalls([]);
  assert.deepEqual(
    plain.map(({ role, chirpRepeats, chirpIntervalSeconds }) => [role, chirpRepeats, chirpIntervalSeconds]),
    [['HOST', undefined, undefined], ['SINK', undefined, undefined]]
  );
});

test('refuses a repeat count or interval that would put two pairs in one search window', async () => {
  // The pairs are told apart by slicing the recording at the interval, so a repeat count below one
  // or an interval that does not clear the record lead plus the stagger cannot be measured.
  for (const bad of [['--chirp-repeats', '0'], ['--chirp-repeats', '2.5'], ['--chirp-repeats', '2', '--chirp-interval-s', '3']]) {
    await assert.rejects(
      () => main(
        ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', ...bad],
        { client: {}, runAdbHost: authorisedPairRunner(), log: () => {}, listStoredCases: async () => [] }
      ),
      /--chirp-repeats|--chirp-interval-s/
    );
  }
});

test('readAlignment slices one pair per repeat and keeps the first pair at the top level', () => {
  // Pair 0 at the record lead, pair 1 one interval later, each a 24000 frame stagger and the
  // second deliberately 96 frames wider so the two cannot be confused for one another.
  const chirp = referenceSweep();
  const recorded = new Int16Array(SAMPLE_RATE * 12);
  const plant = offset => { for (let i = 0; i < chirp.length; i++) recorded[offset + i] += chirp[i]; };
  const lead = SAMPLE_RATE;
  plant(lead); plant(lead + 24000);
  plant(lead + 5 * SAMPLE_RATE); plant(lead + 5 * SAMPLE_RATE + 24096);

  const both = readAlignment({ recorded, reference: chirp, separationMetres: 0, chirpRepeats: 2, chirpIntervalSeconds: 5 });

  assert.equal(both.repeats.length, 2);
  assert.deepEqual(both.repeats.map(pair => pair.measuredStaggerFrames), [24000, 24096]);
  assert.equal(both.measuredStaggerFrames, 24000, 'the first pair stays at the top level');

  // A run without repeats searches the whole recording and gains no repeats field, so every
  // artifact stored before this option existed keeps the exact same shape.
  const single = readAlignment({ recorded, reference: chirp, separationMetres: 0 });
  assert.equal(single.repeats, undefined);
  assert.equal(single.measuredStaggerFrames, 24000);
});

test('--deadband-frames reaches both roles, and its absence leaves both on the production deadband', async () => {
  const narrowed = await startSyncCalls(['--deadband-frames', '8']);
  assert.deepEqual(narrowed.map(({ role, deadbandFrames }) => [role, deadbandFrames]), [['HOST', 8], ['SINK', 8]]);

  const plain = await startSyncCalls([]);
  assert.deepEqual(plain.map(({ role, deadbandFrames }) => [role, deadbandFrames]), [['HOST', undefined], ['SINK', undefined]]);
});

test('refuses a deadband that would put the loop under its own noise floor', async () => {
  // The measured drift-sample noise floor is about 2.4 frames; a deadband at or below it makes the
  // controller chase its own noise, which jitters worse than leaving the error uncorrected.
  for (const bad of ['0', '-8', '2', '4.5']) {
    await assert.rejects(
      () => main(
        ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', '--deadband-frames', bad],
        { client: {}, runAdbHost: authorisedPairRunner(), log: () => {}, listStoredCases: async () => [] }
      ),
      /--deadband-frames/
    );
  }
});

test('--sink-records reaches the sink alone, and grants RECORD_AUDIO on both handsets', async () => {
  const granted = [];
  const runAdbHost = async call => {
    if (call.args[2] === 'shell' && call.args[3] === 'pm') granted.push(call.args[1]);
    return authorisedPairRunner()(call);
  };
  const calls = [];
  const client = {
    clearSyncArtifacts: async () => {},
    startSync: async call => { calls.push(call); if (calls.length === 2) throw new Error('stop the run here'); }
  };
  await assert.rejects(
    () => main(
      ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', '--sink-records'],
      { client, runAdbHost, log: () => {}, listStoredCases: async () => [] }
    ),
    /stop the run here/
  );

  // Only the sink is told to record - the host already does, unconditionally.
  assert.deepEqual(calls.map(({ role, sinkRecords }) => [role, sinkRecords]), [['HOST', undefined], ['SINK', true]]);
  assert.deepEqual(granted, [HOST_SERIAL, SINK_SERIAL]);

  const plain = await startSyncCalls([]);
  assert.deepEqual(plain.map(({ role, sinkRecords }) => [role, sinkRecords]), [['HOST', undefined], ['SINK', false]]);
});

test('combineFacingRun pairs the two recordings slice by slice', () => {
  const pair = (errorMs, correctionMs = 0) => ({ alignmentErrorMs: errorMs, propagationCorrectionMs: correctionMs });
  // Host reads E - D/c, sink reads E + D/c. Pair 1 is a 1ms error, pair 2 a -2ms one, both across
  // the same 3ms of air - so a run that mispaired them would report 1ms and -2ms swapped or mixed.
  const combined = combineFacingRun({
    hostSide: { ...pair(-2), repeats: [pair(-2), pair(-5)] },
    sinkSide: { ...pair(4), repeats: [pair(4), pair(1)] }
  });

  assert.deepEqual(combined.map(entry => entry.alignmentErrorMs), [1, -2]);
  assert.deepEqual(combined.map(entry => entry.flightTimeMs), [3, 3]);
});

test('combineFacingRun refuses to pair up recordings that read a different number of pairs', () => {
  const pair = errorMs => ({ alignmentErrorMs: errorMs, propagationCorrectionMs: 0 });
  assert.equal(combineFacingRun({ hostSide: { ...pair(-2), repeats: [pair(-2), pair(-5)] }, sinkSide: pair(4) }), null);
  assert.equal(combineFacingRun({ hostSide: pair(-2), sinkSide: null }), null);
});

test('records which handset model played which role, and never the serials', async () => {
  const runAdbHost = async ({ args }) => ({
    exitCode: 0,
    stdout: args[1] === HOST_SERIAL ? 'KKG-AN00\r\n' : 'BVL-AN16\n',
    stderr: ''
  });
  assert.deepEqual(
    await readDeviceModels({ hostSerial: HOST_SERIAL, sinkSerial: SINK_SERIAL, runAdbHost }),
    { host: 'KKG-AN00', sink: 'BVL-AN16' }
  );
});

test('a model query that fails leaves the provenance blank rather than losing the run', async () => {
  // The models are provenance, not a measurement: a run that already produced numbers must not be
  // thrown away because one extra getprop came back empty.
  const runAdbHost = async () => ({ exitCode: 1, stdout: '', stderr: 'closed' });
  assert.deepEqual(
    await readDeviceModels({ hostSerial: HOST_SERIAL, sinkSerial: SINK_SERIAL, runAdbHost }),
    { host: null, sink: null }
  );
});

test('the completion floor grows with the chirp schedule, not just the audio segment', () => {
  // A run that repeats the chirp keeps playing long after the audio segment ends, and the report
  // is not written until the last recording closes. Waiting only the audio segment made the
  // harness give up mid-run and export a half-written WAV.
  assert.equal(completionFloorSeconds({ seconds: 90 }), 95);
  assert.equal(completionFloorSeconds({ seconds: 90, chirpRepeats: 2, chirpIntervalSeconds: 15 }), 110);
  assert.equal(completionFloorSeconds({ seconds: 90, chirpRepeats: 4, chirpIntervalSeconds: 15 }), 140);
});

test('refuses to analyse a run whose handsets never reported, instead of exporting a half-written WAV', () => {
  // The dangerous case is not the crash: a recording cut short can still hold both chirps and
  // yield a plausible number from a run that never finished. An unreported role is a failed run.
  assert.throws(
    () => requireBothReports({ host: { unavailable: 'no such file' }, sink: { failureCode: null } }),
    /Host never reported/
  );
  assert.throws(
    () => requireBothReports({ host: { failureCode: null }, sink: { unavailable: 'no such file' } }),
    /Sink never reported/
  );
  assert.doesNotThrow(() => requireBothReports({ host: { failureCode: null }, sink: { failureCode: null } }));
});

test('anchors each later pair near where it is due, so the search cost does not grow with the interval', () => {
  const chirp = referenceSweep();
  const stagger = SAMPLE_RATE / 2;
  const frames = SAMPLE_RATE * 130;
  const recorded = new Int16Array(frames);
  let seed = 11;
  for (let index = 0; index < frames; index++) {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    recorded[index] = Math.round(((seed / 0x7fffffff) - 0.5) * 80);
  }
  // Three pairs a minute apart. A slice-wide search would scan 2.88M lags per pair; anchored on
  // the first pair it scans a fixed window regardless of how far apart they sit.
  const interval = SAMPLE_RATE * 60;
  const plant = (at, gain) => { for (let i = 0; i < chirp.length; i++) recorded[at + i] += chirp[i] * gain; };
  for (let pair = 0; pair < 3; pair++) {
    plant(48000 + pair * interval, 0.6);
    plant(48000 + pair * interval + stagger + pair * 40, 0.6);
  }

  const read = readAlignment({ recorded, reference: chirp, separationMetres: 0, chirpRepeats: 3, chirpIntervalSeconds: 60 });

  assert.equal(read.repeats.length, 3);
  assert.deepEqual(read.repeats.map(p => p.confidence), ['OK', 'OK', 'OK']);
  // Pair n was planted n*40 frames wide of the stagger, so the error must climb by 40 frames a pair.
  assert.deepEqual(read.repeats.map(p => p.measuredStaggerFrames - stagger), [0, 40, 80]);
});

test('pairSearchWindow bounds a later pair near where the first one puts it', () => {
  const interval = SAMPLE_RATE * 60;
  // Without an anchor there is nothing better than the pair's own slice.
  assert.deepEqual(pairSearchWindow({ pairIndex: 0, intervalFrames: interval, anchorIndex: null }),
    { searchFrom: 0, searchTo: interval - 1 });
  assert.deepEqual(pairSearchWindow({ pairIndex: 2, intervalFrames: interval, anchorIndex: null }),
    { searchFrom: 2 * interval, searchTo: 3 * interval - 1 });
  // A run with no repeats at all keeps searching the whole recording.
  assert.deepEqual(pairSearchWindow({ pairIndex: 0, intervalFrames: 0, anchorIndex: null }),
    { searchFrom: 0, searchTo: Infinity });

  // With the first pair located, pair 2 is due exactly two intervals later, so the window is a
  // fixed radius around that - the same width whether the pairs sit 5 seconds or 5 minutes apart.
  const w = pairSearchWindow({ pairIndex: 2, intervalFrames: interval, anchorIndex: 48000 });
  assert.equal(w.searchTo - w.searchFrom, 2 * ANCHOR_RADIUS_FRAMES);
  assert.equal((w.searchFrom + w.searchTo) / 2, 48000 + 2 * interval);
  // The first pair is the anchor; it cannot be anchored on itself.
  assert.deepEqual(pairSearchWindow({ pairIndex: 0, intervalFrames: interval, anchorIndex: 48000 }),
    { searchFrom: 0, searchTo: interval - 1 });
});

test('refuses more chirp repeats than the scheduler can hold, rather than dropping them silently', async () => {
  // Every repeat is queued up front and each costs six chunks of the scheduler's 150, so past
  // twenty-five the extra chirps would be dropped as overflow - a shorter schedule than asked for,
  // reported as a clean run.
  await assert.rejects(
    () => main(
      ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7',
        '--separation-m', '1.2', '--chirp-repeats', '30', '--chirp-interval-s', '15'],
      { client: {}, runAdbHost: authorisedPairRunner(), log: () => {}, listStoredCases: async () => [] }
    ),
    /--chirp-repeats/
  );
  await assert.doesNotReject(() => startSyncCalls(['--chirp-repeats', String(MAX_CHIRP_REPEATS), '--chirp-interval-s', '15']));
});

test('refuses a case ID that already has stored artifacts, before touching either handset', async () => {
  // The guard itself is covered in case-directory.test.mjs; what matters here is that main asks it
  // first, before any of the ADB work - a clash found after a ten minute run is found too late.
  const touched = [];
  await assert.rejects(
    () => main(
      ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', '--case', 'S2'],
      {
        client: {},
        runAdbHost: async call => { touched.push(call.args); return authorisedPairRunner()(call); },
        log: () => {},
        listStoredCases: async () => ['S1', 'S2']
      }
    ),
    /S2 already has stored artifacts/
  );
  assert.deepEqual(touched, [], 'no ADB call may run before the case ID is cleared');
});

test('--clock-interval-ms reaches the sink alone, and its absence leaves the run on the production cadence', async () => {
  // Only the sink runs a clock client; the host answers. A denser cadence is a collection choice -
  // the recorded exchanges decimate back into several independent runs of the real 2s configuration,
  // and their disagreement measures the estimator's own noise without any model of the true clock.
  const dense = await startSyncCalls(['--clock-interval-ms', '500']);
  assert.deepEqual(dense.map(({ role, clockIntervalMs }) => [role, clockIntervalMs]), [['HOST', undefined], ['SINK', 500]]);

  const plain = await startSyncCalls([]);
  assert.deepEqual(plain.map(({ role, clockIntervalMs }) => [role, clockIntervalMs]), [['HOST', undefined], ['SINK', undefined]]);
});

test('--alignment-offset-ms reaches the sink alone, in microseconds and with its sign intact', async () => {
  // Only the sink converts host time into local time, so only the sink has anything to correct -
  // the host plays on its own clock. The value is the alignmentErrorMs a previous run reported,
  // handed back verbatim, and the sign has to survive the trip: reversed, it would double the very
  // error it is meant to cancel and the run would still look plausible.
  const corrected = await startSyncCalls(['--alignment-offset-ms', '-34.744']);
  assert.deepEqual(
    corrected.map(({ role, alignmentOffsetMicros }) => [role, alignmentOffsetMicros]),
    [['HOST', undefined], ['SINK', -34744]]
  );

  const plain = await startSyncCalls([]);
  assert.deepEqual(
    plain.map(({ role, alignmentOffsetMicros }) => [role, alignmentOffsetMicros]),
    [['HOST', undefined], ['SINK', undefined]]
  );
});

test('refuses an alignment correction large enough to cross the two chirps', async () => {
  // analyzeAlignment tells the chirps apart by the 500ms stagger. A correction at that scale moves
  // the sink's chirp past the host's, and the pairing reverses without anything looking wrong.
  for (const bad of ['250', '-250', '600', 'later']) {
    await assert.rejects(
      () => main(
        ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', '--alignment-offset-ms', bad],
        { client: {}, runAdbHost: authorisedPairRunner(), log: () => {}, listStoredCases: async () => [] }
      ),
      /--alignment-offset-ms/
    );
  }
});

test('--capture-package reaches the host alone, because only the host captures', async () => {
  const requested = await startSyncCalls(['--capture-package', 'com.tencent.qqmusic']);
  assert.deepEqual(requested.map(({ role, capturePackage }) => [role, capturePackage]), [['HOST', 'com.tencent.qqmusic'], ['SINK', undefined]]);

  // The sink plays what the host sends it. A sink told to capture would be capturing its own
  // output, which is the feedback loop this flag exists to avoid on the host side too.
  const plain = await startSyncCalls([]);
  assert.deepEqual(plain.map(({ role, capturePackage }) => [role, capturePackage]), [['HOST', undefined], ['SINK', undefined]]);
});

test('a capture run holds the sink back until the host is listening', async () => {
  // O29 failed here. The host only binds its ports inside the run, and the run only starts once the
  // user has answered the consent dialog, so the sink's fixed head start guaranteed a
  // ConnectException no matter how fast anyone tapped.
  const calls = [];
  let probes = 0;
  const runAdbHost = async call => {
    if (call.args[0] === 'devices') {
      return { exitCode: 0, stdout: devicesOutput([`${HOST_SERIAL}    device transport_id:1`, `${SINK_SERIAL}    device transport_id:2`]), stderr: '' };
    }
    if (call.args.includes('/proc/net/tcp')) {
      probes += 1;
      // Not listening until the third look, the way a dialog waits on a person.
      return { exitCode: 0, stdout: probes < 3 ? '' : '   0: 012BA8C0:B044 00000000:0000 0A', stderr: '' };
    }
    if (call.args.includes('dumpsys')) return { exitCode: 0, stdout: 'mWakefulness=Awake', stderr: '' };
    return { exitCode: 0, stdout: '', stderr: '' };
  };
  const client = {
    clearSyncArtifacts: async () => {},
    startSync: async call => {
      calls.push({ role: call.role, probesSoFar: probes });
      if (calls.length === 2) throw new Error('stop the run here');
    }
  };
  await assert.rejects(
    () => main(
      ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', '--capture-package', 'com.tencent.qqmusic'],
      { client, runAdbHost, log: () => {}, listStoredCases: async () => [] }
    ),
    /stop the run here/
  );
  assert.deepEqual(calls.map(entry => entry.role), ['HOST', 'SINK']);
  assert.equal(calls[0].probesSoFar, 0, 'the host starts before anything is listening');
  assert.equal(calls[1].probesSoFar, 3, 'the sink waits for the port the host binds inside the run');
});

test('the readiness gate sees a listener in tcp6, where an unbound ServerSocket actually lands', async () => {
  // O30 was lost to a gate that could not match the table it was written for. The addresses in
  // tcp6 are 32 hex characters, not 8, so a pattern built around the IPv4 width reports a live
  // listener as absent - and a ServerSocket given no address binds the IPv6 wildcard.
  const tcp6 = [
    '  sl  local_address                         remote_address                        st',
    '   0: 00000000000000000000000000000000:B044 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10162 0 1234 1'
  ].join('\n');
  let seen = false;
  await awaitHostListening({
    serial: HOST_SERIAL,
    runAdbHost: async () => { seen = true; return { exitCode: 0, stdout: tcp6, stderr: '' }; },
    timeoutMs: 5000,
    pollMs: 10
  });
  assert.ok(seen);

  // The IPv4 table still has to work: the same run reads both.
  await awaitHostListening({
    serial: HOST_SERIAL,
    runAdbHost: async () => ({ exitCode: 0, stdout: '   3: 012BA8C0:B044 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10162 0 99 1', stderr: '' }),
    timeoutMs: 5000,
    pollMs: 10
  });

  // A listener on a neighbouring port is not this one. 45123 is the clock port, bound first.
  await assert.rejects(
    () => awaitHostListening({
      serial: HOST_SERIAL,
      runAdbHost: async () => ({ exitCode: 0, stdout: '   3: 012BA8C0:B043 00000000:0000 0A', stderr: '' }),
      timeoutMs: 100,
      pollMs: 10
    }),
    /never started listening/
  );
});

test('a capture run gives up on its own terms when the host never starts listening', async () => {
  const runAdbHost = async call => {
    if (call.args[0] === 'devices') {
      return { exitCode: 0, stdout: devicesOutput([`${HOST_SERIAL}    device transport_id:1`, `${SINK_SERIAL}    device transport_id:2`]), stderr: '' };
    }
    if (call.args.includes('dumpsys')) return { exitCode: 0, stdout: 'mWakefulness=Awake', stderr: '' };
    return { exitCode: 0, stdout: '', stderr: '' };
  };
  await assert.rejects(
    () => main(
      ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', '--capture-package', 'com.tencent.qqmusic', '--consent-timeout-s', '1'],
      { client: { clearSyncArtifacts: async () => {}, startSync: async () => {} }, runAdbHost, log: () => {}, listStoredCases: async () => [] }
    ),
    /consent/i
  );
});

test('refuses a capture package that is not a package name, and refuses the probe capturing itself', async () => {
  for (const bad of ['', 'no-dots', 'com..empty', 'com.soundmesh.probe', 'com.a; rm -rf /']) {
    await assert.rejects(
      () => main(
        ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--capture-package', bad],
        { client: { clearSyncArtifacts: async () => {}, startSync: async () => {} }, runAdbHost: authorisedPairRunner(), log: () => {}, listStoredCases: async () => [] }
      ),
      /--capture-package/
    );
  }
});

test('refuses a clock cadence outside what the probe will honour', async () => {
  // The probe clamps silently to its own range, so a value it would ignore has to be refused here:
  // a run that quietly collected at 2000ms while its notes say 50ms is worse than one that failed.
  for (const bad of ['0', '-500', '50', '20000', '500.5']) {
    await assert.rejects(
      () => main(
        ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2', '--clock-interval-ms', bad],
        { client: {}, runAdbHost: authorisedPairRunner(), log: () => {}, listStoredCases: async () => [] }
      ),
      /--clock-interval-ms/
    );
  }
});

test('--source-file reaches the host alone, and travels as a bare name', async () => {
  // Only the host decodes: the sink plays the PCM the host sends it, so a sink told to open a file
  // would be playing a second copy of the song against the one on the wire.
  const requested = await startSyncCalls(['--source-file', 'asset/song.mp3']);
  assert.deepEqual(requested.map(({ role, sourceFile }) => [role, sourceFile]), [['HOST', 'song.mp3'], ['SINK', undefined]]);

  const plain = await startSyncCalls([]);
  assert.deepEqual(plain.map(({ role, sourceFile }) => [role, sourceFile]), [['HOST', undefined], ['SINK', undefined]]);
});

test('refuses a file source and a capture source together, because one of them would silently win', async () => {
  await assert.rejects(
    () => main(
      ['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2',
       '--source-file', 'asset/song.mp3', '--capture-package', 'com.tencent.qqmusic'],
      { client: {}, runAdbHost: authorisedPairRunner(), log: () => {}, listStoredCases: async () => [] }
    ),
    /--source-file/
  );
});

test('refuses a source file whose name the probe would not accept', async () => {
  // The probe matches the same shape on its side and would fall back to the generated tone on a
  // mismatch - a run that quietly played a sine while its notes say it played music.
  for (const bad of ['asset/-lead.mp3', 'asset/my song.mp3', 'asset/_x.mp3', 'asset/', `asset/${'a'.repeat(70)}.mp3`]) {
    await assert.rejects(
      () => pushSourceFile({ serial: HOST_SERIAL, localPath: bad, runAdbHost: authorisedPairRunner() }),
      /--source-file/
    );
  }
});

test('pushes the source file into the probe own directory, and nowhere else', async () => {
  const calls = [];
  const runAdbHost = async call => { calls.push(call.args); return { exitCode: 0, stdout: '', stderr: '' }; };
  const name = await pushSourceFile({ serial: HOST_SERIAL, localPath: 'asset/song.mp3', runAdbHost });
  assert.equal(name, 'song.mp3');
  // The directory is created first: it only exists once the app has called getExternalFilesDir(),
  // so on a fresh install the push would otherwise fail on a directory adb could have made.
  assert.deepEqual(calls, [
    ['-s', HOST_SERIAL, 'shell', 'mkdir', '-p', '/sdcard/Android/data/com.soundmesh.probe/files'],
    ['-s', HOST_SERIAL, 'push', 'asset/song.mp3', '/sdcard/Android/data/com.soundmesh.probe/files/song.mp3']
  ]);
});

test('a push that fails stops the run instead of playing the tone under a music run name', async () => {
  const runAdbHost = async () => ({ exitCode: 1, stdout: '', stderr: 'adb: error: cannot stat: No such file or directory' });
  await assert.rejects(
    () => pushSourceFile({ serial: HOST_SERIAL, localPath: 'asset/song.mp3', runAdbHost }),
    /cannot stat/
  );
});
