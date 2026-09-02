import assert from 'node:assert/strict';
import test from 'node:test';
import { main, assertAuthorizedPair, requireBothSerialsAuthorized, grantRecordAudio, assertAwake, requireBothDevicesAwake, awaitBothReports } from '../src/cli/run-sync.mjs';

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
    () => main(['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2'], { client, runAdbHost, log: () => {} }),
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
    { client, runAdbHost, log: () => {} }
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
    () => main(['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7', '--separation-m', '1.2'], { client, runAdbHost, log: () => {} }),
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
      { client, runAdbHost: authorisedPairRunner(), log: () => {} }
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
        { client: {}, runAdbHost: authorisedPairRunner(), log: () => {} }
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
