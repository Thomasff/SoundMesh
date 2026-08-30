import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createAdbHostRunner, parseAdbDevices, requireAuthorizedSerial } from '../adb.mjs';
import { collectInventory, redactInventory } from '../device-inventory.mjs';

const artifactsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), '../../../artifacts');
const selectedDevicePath = resolve(artifactsDirectory, 'session', 'selected-device.json');

export function assertApi29(inventory) {
  if (!Number.isInteger(inventory.apiLevel) || inventory.apiLevel < 29) {
    const error = new Error('DEVICE_BELOW_API_29');
    error.code = 'DEVICE_BELOW_API_29';
    error.exitCode = 29;
    throw error;
  }
  return inventory;
}

export async function saveInventory(inventory) {
  const directory = resolve(artifactsDirectory, 'feasibility', inventory.fingerprintHash);
  await mkdir(directory, { recursive: true });
  const path = resolve(directory, 'inventory.json');
  await writeFile(path, `${JSON.stringify(inventory, null, 2)}\n`, 'utf8');
  return path;
}

export async function saveConfirmedSelection(inventory) {
  await mkdir(dirname(selectedDevicePath), { recursive: true });
  await writeFile(selectedDevicePath, `${JSON.stringify({
    serial: inventory.serial,
    fingerprintHash: inventory.fingerprintHash
  }, null, 2)}\n`, 'utf8');
  return selectedDevicePath;
}

export async function loadConfirmedSerial({ runAdbHost = createAdbHostRunner() } = {}) {
  const selection = JSON.parse(await readFile(selectedDevicePath, 'utf8'));
  const result = await runAdbHost({ args: ['devices', '-l'] });
  if (result.exitCode !== 0) {
    throw new Error(`adb devices failed with exit code ${result.exitCode}: ${result.stderr}`);
  }
  const entries = parseAdbDevices(result.stdout);
  const authorized = entries.filter(({ state }) => state === 'device');
  if (authorized.length !== 1) {
    throw new Error('Confirmed device is no longer the sole authorized target');
  }
  return requireAuthorizedSerial(entries, selection.serial);
}

function requestedSerialFrom(argumentsList) {
  const index = argumentsList.indexOf('--serial');
  return index === -1 ? undefined : argumentsList[index + 1];
}

export async function inspectDevice({
  requestedSerial,
  confirmSelection = false,
  runAdbHost = createAdbHostRunner(),
  collect = collectInventory
} = {}) {
  const result = await runAdbHost({ args: ['devices', '-l'] });
  if (result.exitCode !== 0) {
    throw new Error(`adb devices failed with exit code ${result.exitCode}: ${result.stderr}`);
  }
  const serial = requireAuthorizedSerial(parseAdbDevices(result.stdout), requestedSerial);
  const inventory = await collect({ serial });
  await saveInventory(inventory);
  if (confirmSelection) {
    await saveConfirmedSelection(inventory);
  }
  return inventory;
}

export async function main(argumentsList = process.argv.slice(2)) {
  try {
    const inventory = await inspectDevice({
      requestedSerial: requestedSerialFrom(argumentsList),
      confirmSelection: argumentsList.includes('--confirm-selection')
    });
    console.log(JSON.stringify(redactInventory(inventory), null, 2));
    assertApi29(inventory);
  } catch (error) {
    if (error.code === 'DEVICE_BELOW_API_29') {
      console.error('DEVICE_BELOW_API_29');
      process.exitCode = error.exitCode;
      return;
    }
    throw error;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main();
}
