import { createHash } from 'node:crypto';
import { runAdb as defaultRunAdb } from './adb.mjs';

export const MUSIC_PACKAGES = Object.freeze([
  'com.netease.cloudmusic',
  'com.tencent.qqmusic'
]);

export function parseProperties(text) {
  return Object.fromEntries([...text.matchAll(/^\[([^\]]+)\]: \[([^\]]*)\]$/gm)]
    .map(([, key, value]) => [key, value]));
}

export function parsePackageInfo(text, packageName) {
  const valueAfter = (key) => text.match(new RegExp(`^\\s*${key}=([^\\s]+)`, 'm'))?.[1] ?? null;
  const versionCode = Number.parseInt(valueAfter('versionCode'), 10);
  const targetSdkMatch = text.match(/\btargetSdk=(\d+)/);
  return Object.freeze({
    packageName,
    versionName: valueAfter('versionName'),
    versionCode: Number.isNaN(versionCode) ? null : versionCode,
    targetSdk: targetSdkMatch ? Number.parseInt(targetSdkMatch[1], 10) : null
  });
}

export function parseMediaVolume(text) {
  const match = text.match(/volume is (\d+) in range \[0\.\.(\d+)\]/i);
  if (!match) {
    throw new Error('Unable to parse media volume');
  }
  return Object.freeze({ current: Number.parseInt(match[1], 10), max: Number.parseInt(match[2], 10) });
}

export function buildInventory({ serial, properties, packages, mediaVolume }) {
  const fingerprint = properties['ro.build.fingerprint'] ?? '';
  const fingerprintHash = createHash('sha256').update(`${serial}\n${fingerprint}`).digest('hex');
  const manufacturer = properties['ro.product.manufacturer'] ?? null;
  const model = properties['ro.product.model'] ?? null;
  return Object.freeze({
    serial,
    fingerprintHash,
    deviceAlias: [manufacturer, model].filter(Boolean).join(' ') || 'Unknown device',
    manufacturer,
    model,
    apiLevel: Number.parseInt(properties['ro.build.version.sdk'], 10),
    androidRelease: properties['ro.build.version.release'] ?? null,
    buildFingerprint: fingerprint || null,
    apps: Object.freeze([...packages]),
    mediaVolume
  });
}

function requireSuccessful(result, description) {
  if (result.exitCode !== 0) {
    throw new Error(`${description} command failed`);
  }
  return result.stdout;
}

export async function collectInventory({ serial, runAdb = defaultRunAdb }) {
  const propertiesResult = await runAdb({ serial, args: ['shell', 'getprop'] });
  const properties = parseProperties(requireSuccessful(propertiesResult, 'getprop'));
  const packages = [];
  for (const packageName of MUSIC_PACKAGES) {
    const result = await runAdb({ serial, args: ['shell', 'dumpsys', 'package', packageName] });
    packages.push(parsePackageInfo(requireSuccessful(result, `dumpsys package ${packageName}`), packageName));
  }
  const volumeResult = await runAdb({ serial, args: ['shell', 'media', 'volume', '--stream', '3', '--get'] });
  const mediaVolume = parseMediaVolume(requireSuccessful(volumeResult, 'media volume'));
  return buildInventory({ serial, properties, packages, mediaVolume });
}

export function redactInventory(inventory) {
  const { serial, buildFingerprint, ...preview } = inventory;
  return Object.freeze(preview);
}
