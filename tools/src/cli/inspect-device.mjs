import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createAdbHostRunner, parseAdbDevices, requireAuthorizedSerial } from '../adb.mjs';
import { collectInventory, redactInventory } from '../device-inventory.mjs';

const artifactsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), '../../../artifacts');
const selectedDevicePath = resolve(artifactsDirectory, 'session', 'selected-device.json');
const pendingInspectionPath = resolve(artifactsDirectory, 'session', 'pending-inspection.json');

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

async function savePendingInspection(inventory) {
  await mkdir(dirname(pendingInspectionPath), { recursive: true });
  await writeFile(pendingInspectionPath, `${JSON.stringify({
    fingerprintHash: inventory.fingerprintHash
  }, null, 2)}\n`, 'utf8');
}

async function loadPendingInspection() {
  try {
    return JSON.parse(await readFile(pendingInspectionPath, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') {
      return undefined;
    }
    throw error;
  }
}

export function assertPendingInspection(pendingInspection, inventory) {
  if (!pendingInspection) {
    throw new Error('Run an ordinary inspection and review its preview before confirming selection');
  }
  if (pendingInspection.fingerprintHash !== inventory.fingerprintHash) {
    throw new Error('Inspected device changed before confirmation');
  }
}

export function requireConfirmedSerial(entries, selectedSerial) {
  if (entries.some(({ state }) => state !== 'device')) {
    throw new Error('Confirmed device validation found an unavailable attached device');
  }
  if (entries.length !== 1) {
    throw new Error('Confirmed device is no longer the sole authorized target');
  }
  return requireAuthorizedSerial(entries, selectedSerial);
}

export async function loadConfirmedSerial({ runAdbHost = createAdbHostRunner() } = {}) {
  const selection = JSON.parse(await readFile(selectedDevicePath, 'utf8'));
  const result = await runAdbHost({ args: ['devices', '-l'] });
  if (result.exitCode !== 0) {
    throw new Error('ADB device discovery failed');
  }
  const entries = parseAdbDevices(result.stdout);
  return requireConfirmedSerial(entries, selection.serial);
}

function requestedSerialFrom(argumentsList) {
  const index = argumentsList.indexOf('--serial');
  return index === -1 ? undefined : argumentsList[index + 1];
}

async function readCurrentInventory({
  requestedSerial,
  runAdbHost = createAdbHostRunner(),
  collect = collectInventory
} = {}) {
  const result = await runAdbHost({ args: ['devices', '-l'] });
  if (result.exitCode !== 0) {
    throw new Error('ADB device discovery failed');
  }
  const serial = requireAuthorizedSerial(parseAdbDevices(result.stdout), requestedSerial);
  return collect({ serial });
}

export async function inspectDevice(options = {}) {
  const inventory = await readCurrentInventory(options);
  await saveInventory(inventory);
  await savePendingInspection(inventory);
  return inventory;
}

export async function confirmDeviceSelection(options = {}) {
  const pendingInspection = await loadPendingInspection();
  const inventory = await readCurrentInventory(options);
  assertPendingInspection(pendingInspection, inventory);
  await saveInventory(inventory);
  await saveConfirmedSelection(inventory);
  return inventory;
}

export async function main(argumentsList = process.argv.slice(2)) {
  try {
    const options = { requestedSerial: requestedSerialFrom(argumentsList) };
    const inventory = argumentsList.includes('--confirm-selection')
      ? await confirmDeviceSelection(options)
      : await inspectDevice(options);
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
