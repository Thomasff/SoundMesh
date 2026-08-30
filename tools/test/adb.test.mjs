import { EventEmitter } from 'node:events';
import test from 'node:test';
import assert from 'node:assert/strict';
import { createAdbHostRunner, createAdbRunner, parseAdbDevices, requireAuthorizedSerial } from '../src/adb.mjs';

test('rejects an unauthorized phone', () => {
  const entries = parseAdbDevices('List of devices attached\nABC123\tunauthorized usb:1-1\n');
  assert.throws(() => requireAuthorizedSerial(entries), /unauthorized/i);
});

test('rejects ambiguous selection when two phones are attached', () => {
  const entries = parseAdbDevices('List of devices attached\nA\tdevice\nB\tdevice\n');
  assert.throws(() => requireAuthorizedSerial(entries), /explicit serial/i);
});

test('accepts an explicitly selected authorized phone', () => {
  const entries = parseAdbDevices('List of devices attached\nA\tdevice\nB\tdevice\n');
  assert.equal(requireAuthorizedSerial(entries, 'B'), 'B');
});

test('rejects an offline device and an unknown requested serial', () => {
  const entries = parseAdbDevices('List of devices attached\nA\toffline\nB\tdevice\n');
  assert.throws(() => requireAuthorizedSerial(entries, 'A'), /offline/i);
  assert.throws(() => requireAuthorizedSerial(entries, 'C'), /unknown/i);
});

test('runs selected-device commands with an argv array and serial guard', async () => {
  const calls = [];
  const runAdb = createAdbRunner({
    adbPath: 'fake-adb',
    spawnImpl(command, args, options) {
      calls.push({ command, args, options });
      const child = new EventEmitter();
      child.stdout = new EventEmitter();
      child.stderr = new EventEmitter();
      queueMicrotask(() => {
        child.stdout.emit('data', Buffer.from('fixture output'));
        child.emit('close', 0);
      });
      return child;
    }
  });

  const result = await runAdb({ serial: 'ABC123', args: ['shell', 'getprop'], timeoutMs: 100 });

  assert.deepEqual(calls, [{
    command: 'fake-adb',
    args: ['-s', 'ABC123', 'shell', 'getprop'],
    options: { shell: false, windowsHide: true }
  }]);
  assert.deepEqual(result, { exitCode: 0, stdout: 'fixture output', stderr: '' });
  assert.equal(Object.isFrozen(result), true);
});

test('runs host-only discovery without a selected-device serial', async () => {
  const calls = [];
  const runAdbHost = createAdbHostRunner({
    adbPath: 'fake-adb',
    spawnImpl(command, args, options) {
      calls.push({ command, args, options });
      const child = new EventEmitter();
      child.stdout = new EventEmitter();
      child.stderr = new EventEmitter();
      queueMicrotask(() => child.emit('close', 0));
      return child;
    }
  });

  await runAdbHost({ args: ['devices', '-l'] });

  assert.deepEqual(calls[0].args, ['devices', '-l']);
});

test('terminates a selected-device command at its timeout', async () => {
  let killed = false;
  const runAdb = createAdbRunner({
    spawnImpl() {
      const child = new EventEmitter();
      child.stdout = new EventEmitter();
      child.stderr = new EventEmitter();
      child.kill = () => { killed = true; };
      setTimeout(() => child.emit('close', 0), 20);
      return child;
    }
  });

  await assert.rejects(
    runAdb({ serial: 'ABC123', args: ['shell', 'getprop'], timeoutMs: 1 }),
    /timed out/i
  );
  assert.equal(killed, true);
});
