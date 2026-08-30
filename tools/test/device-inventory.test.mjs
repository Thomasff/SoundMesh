import test from 'node:test';
import assert from 'node:assert/strict';
import {
  assertApi29,
  assertPendingInspection,
  requireConfirmedSerial
} from '../src/cli/inspect-device.mjs';
import { buildInventory, collectInventory } from '../src/device-inventory.mjs';

test('builds a normalized device inventory', () => {
  const inventory = buildInventory({
    serial: 'ABC123',
    properties: {
      'ro.product.manufacturer': 'HONOR',
      'ro.product.model': 'TEL-AN00a',
      'ro.build.version.sdk': '29',
      'ro.build.version.release': '10'
    },
    packages: [
      { packageName: 'com.netease.cloudmusic', versionName: '9.3.20', versionCode: 9032000, targetSdk: 35 },
      { packageName: 'com.tencent.qqmusic', versionName: '14.6.0', versionCode: 14060000, targetSdk: 35 }
    ],
    mediaVolume: { current: 7, max: 15 }
  });

  assert.equal(inventory.apiLevel, 29);
  assert.equal(inventory.apps.length, 2);
  assert.equal(inventory.deviceAlias, 'HONOR TEL-AN00a');
  assert.equal(inventory.mediaVolume.current, 7);
  assert.match(inventory.fingerprintHash, /^[a-f0-9]{64}$/);
});

test('collects only the defined read-only inventory commands for the selected serial', async () => {
  const calls = [];
  const fixtures = new Map([
    ['shell getprop', '[ro.product.manufacturer]: [HONOR]\n[ro.product.model]: [TEL-AN00a]\n[ro.build.version.sdk]: [29]\n[ro.build.version.release]: [10]\n[ro.build.fingerprint]: [honor/test/fingerprint]\n'],
    ['shell dumpsys package com.netease.cloudmusic', 'Package [com.netease.cloudmusic]\n  versionCode=9032000 minSdk=23 targetSdk=35\n  versionName=9.3.20\n'],
    ['shell dumpsys package com.tencent.qqmusic', 'Package [com.tencent.qqmusic]\n  versionCode=14060000 minSdk=23 targetSdk=35\n  versionName=14.6.0\n'],
    ['shell media volume --stream 3 --get', 'volume is 7 in range [0..15]\n']
  ]);
  const inventory = await collectInventory({
    serial: 'ABC123',
    runAdb: async ({ serial, args }) => {
      calls.push({ serial, args });
      return { exitCode: 0, stdout: fixtures.get(args.join(' ')), stderr: '' };
    }
  });

  assert.deepEqual(calls, [
    { serial: 'ABC123', args: ['shell', 'getprop'] },
    { serial: 'ABC123', args: ['shell', 'dumpsys', 'package', 'com.netease.cloudmusic'] },
    { serial: 'ABC123', args: ['shell', 'dumpsys', 'package', 'com.tencent.qqmusic'] },
    { serial: 'ABC123', args: ['shell', 'media', 'volume', '--stream', '3', '--get'] }
  ]);
  assert.equal(inventory.apps[0].versionName, '9.3.20');
  assert.deepEqual(inventory.mediaVolume, { current: 7, max: 15 });
});

test('marks below-API-29 inventory with the required gate exit code', () => {
  assert.throws(
    () => assertApi29({ apiLevel: 28 }),
    (error) => error.code === 'DEVICE_BELOW_API_29' && error.exitCode === 29
  );
});

test('requires a prior ordinary inspection before selection can be confirmed', () => {
  assert.throws(
    () => assertPendingInspection(undefined, { fingerprintHash: 'current-hash' }),
    /ordinary inspection/i
  );
  assert.throws(
    () => assertPendingInspection({ fingerprintHash: 'old-hash' }, { fingerprintHash: 'current-hash' }),
    /changed/i
  );
  assert.doesNotThrow(() => assertPendingInspection(
    { fingerprintHash: 'current-hash' },
    { fingerprintHash: 'current-hash' }
  ));
});

test('rejects confirmed selection when any attached device is unavailable', () => {
  assert.throws(
    () => requireConfirmedSerial([
      { serial: 'A', state: 'device' },
      { serial: 'B', state: 'unauthorized' }
    ], 'A'),
    /unavailable/i
  );
});

test('keeps failed command stderr out of public inventory errors', async () => {
  await assert.rejects(
    collectInventory({
      serial: 'ABC123',
      runAdb: async () => ({ exitCode: 1, stdout: '', stderr: 'PRIVATE_DEVICE_DIAGNOSTIC' })
    }),
    (error) => error.message === 'getprop command failed' && !error.message.includes('PRIVATE_DEVICE_DIAGNOSTIC')
  );
});
