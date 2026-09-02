import assert from 'node:assert/strict';
import test from 'node:test';
import { main, assertAuthorizedPair, requireBothSerialsAuthorized, grantRecordAudio } from '../src/cli/run-sync.mjs';

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

  assert.equal(order[0], 'adb devices -l');
  assert.equal(order[1], `adb -s ${HOST_SERIAL} shell pm grant com.soundmesh.probe android.permission.RECORD_AUDIO`);
  assert.ok(order.indexOf('client startSync') > 1, 'the grant must come before either role starts');
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
