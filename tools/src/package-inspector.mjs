import { createHash } from 'node:crypto';
import { mkdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { runAdb as defaultRunAdb } from './adb.mjs';

const DEFAULT_APKANALYZER_PATH = 'D:\\DevSoft\\AndroidSDK\\cmdline-tools\\latest\\bin\\apkanalyzer.bat';
const DEFAULT_ARTIFACTS_DIR = join('artifacts', 'private', 'apk-temp');

export function parseManifestFacts(xml) {
  const attribute = (element, name) => element?.match(new RegExp(`\\b${name}\\s*=\\s*["']([^"']+)["']`))?.[1] ?? null;
  const manifest = xml.match(/<manifest\b[^>]*>/i)?.[0];
  const usesSdk = xml.match(/<uses-sdk\b[^>]*>/i)?.[0];
  const application = xml.match(/<application\b[^>]*>/i)?.[0];
  const targetSdk = Number.parseInt(attribute(usesSdk, 'android:targetSdkVersion'), 10);
  const capturePolicy = attribute(application, 'android:allowAudioPlaybackCapture');

  return Object.freeze({
    packageName: attribute(manifest, 'package'),
    targetSdk: Number.isNaN(targetSdk) ? null : targetSdk,
    allowAudioPlaybackCapture: capturePolicy === null ? null : capturePolicy === 'true'
  });
}

function createProcessRunner() {
  return ({ command, args }) => new Promise((resolve, reject) => {
    const child = spawn(command, args, { shell: false, windowsHide: true });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.once('error', reject);
    child.once('close', (exitCode) => resolve({ exitCode, stdout, stderr }));
  });
}

function requireSuccess(result, description) {
  if (result.exitCode !== 0) {
    throw new Error(`${description} failed`);
  }
  return result.stdout;
}

async function runSafely(run, request, description) {
  try {
    return requireSuccess(await run(request), description);
  } catch {
    throw new Error(`${description} failed`);
  }
}

function findBaseApkPath(output) {
  const line = output.split(/\r?\n/)
    .map((value) => value.trim())
    .find((value) => value.startsWith('package:') && value.endsWith('/base.apk'));
  if (!line) {
    throw new Error('Installed package base APK was not found');
  }
  return line.slice('package:'.length);
}

export async function inspectInstalledPackage({
  serial,
  packageName,
  runAdb = defaultRunAdb,
  runProcess = createProcessRunner(),
  apkanalyzerPath = DEFAULT_APKANALYZER_PATH,
  artifactsDir = DEFAULT_ARTIFACTS_DIR
}) {
  if (!serial) throw new Error('A selected device serial is required');
  if (!packageName) throw new Error('A package name is required');

  const pmOutput = await runSafely(
    runAdb,
    { serial, args: ['shell', 'pm', 'path', packageName] },
    'Package path lookup'
  );
  const remoteApkPath = findBaseApkPath(pmOutput);
  const apkHash = createHash('sha256').update(`${serial}\n${packageName}\n${remoteApkPath}`).digest('hex');
  const manifestDir = join(artifactsDir, apkHash);
  const localApkPath = join(manifestDir, 'base.apk');

  await mkdir(manifestDir, { recursive: true });
  try {
    await runSafely(
      runAdb,
      { serial, args: ['pull', remoteApkPath, localApkPath] },
      'APK pull'
    );
    const xml = await runSafely(
      runProcess,
      { command: apkanalyzerPath, args: ['manifest', 'print', localApkPath] },
      'Manifest analysis'
    );
    const facts = parseManifestFacts(xml);
    await writeFile(join(manifestDir, 'manifest.xml'), xml, 'utf8');
    await writeFile(join(manifestDir, 'manifest-facts.json'), `${JSON.stringify(facts, null, 2)}\n`, 'utf8');
    return facts;
  } catch (error) {
    if (error.message === 'Manifest analysis failed' || error.message === 'APK pull failed') throw error;
    throw new Error('Manifest inspection failed');
  } finally {
    try {
      await rm(localApkPath, { force: true });
    } catch {
      throw new Error('Temporary APK cleanup failed');
    }
  }
}
