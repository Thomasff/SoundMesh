import assert from 'node:assert/strict';
import test from 'node:test';
import { main, assertAuthorizedPair, requireBothSerialsAuthorized } from '../src/cli/run-sync.mjs';

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
    () => main(['--host-serial', HOST_SERIAL, '--sink-serial', SINK_SERIAL, '--host-address', '192.168.1.7'], { client, runAdbHost, log: () => {} }),
    /Sink serial is not among the attached, authorised devices/
  );
  assert.deepEqual(calls, []);
});
