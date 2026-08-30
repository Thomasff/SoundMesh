import { mkdtemp, readFile, rm, writeFile, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectInstalledPackage, parseManifestFacts } from '../src/package-inspector.mjs';

const manifestXml = `<?xml version="1.0" encoding="utf-8"?>
<manifest package="com.example.music">
  <uses-sdk android:targetSdkVersion="35" xmlns:android="http://schemas.android.com/apk/res/android"/>
  <application android:allowAudioPlaybackCapture="false" xmlns:android="http://schemas.android.com/apk/res/android"/>
</manifest>`;

test('reads target SDK and explicit playback capture opt-out', () => {
  assert.deepEqual(parseManifestFacts(manifestXml), {
    packageName: 'com.example.music',
    targetSdk: 35,
    allowAudioPlaybackCapture: false
  });
});

test('keeps an omitted playback capture policy indeterminate', () => {
  assert.deepEqual(parseManifestFacts('<manifest package="com.example.music"><uses-sdk android:targetSdkVersion="29"/></manifest>'), {
    packageName: 'com.example.music',
    targetSdk: 29,
    allowAudioPlaybackCapture: null
  });
});

test('records manifest facts without retaining the pulled APK', async (t) => {
  const artifactsDir = await mkdtemp(join(tmpdir(), 'soundmesh-package-inspector-'));
  t.after(() => rm(artifactsDir, { recursive: true, force: true }));
  let pulledApkPath;

  const facts = await inspectInstalledPackage({
    serial: 'ABC123',
    packageName: 'com.example.music',
    artifactsDir,
    apkanalyzerPath: 'fake-apkanalyzer',
    runAdb: async ({ args }) => {
      if (args.join(' ') === 'shell pm path com.example.music') {
        return { exitCode: 0, stdout: 'package:/data/app/example/base.apk\n', stderr: '' };
      }
      if (args[0] === 'pull') {
        pulledApkPath = args[2];
        await writeFile(pulledApkPath, 'not a real APK');
        return { exitCode: 0, stdout: '1 file pulled', stderr: '' };
      }
      throw new Error('Unexpected fake ADB command');
    },
    runProcess: async ({ command, args }) => {
      assert.equal(command, 'fake-apkanalyzer');
      assert.deepEqual(args, ['manifest', 'print', pulledApkPath]);
      return { exitCode: 0, stdout: manifestXml, stderr: '' };
    }
  });

  assert.deepEqual(facts, {
    packageName: 'com.example.music',
    targetSdk: 35,
    allowAudioPlaybackCapture: false
  });
  await assert.rejects(access(pulledApkPath));
  const manifestDir = dirname(pulledApkPath);
  assert.equal(await readFile(join(manifestDir, 'manifest.xml'), 'utf8'), manifestXml);
  assert.deepEqual(JSON.parse(await readFile(join(manifestDir, 'manifest-facts.json'), 'utf8')), facts);
});

test('removes the pulled APK when manifest analysis fails without exposing stderr', async (t) => {
  const artifactsDir = await mkdtemp(join(tmpdir(), 'soundmesh-package-inspector-'));
  t.after(() => rm(artifactsDir, { recursive: true, force: true }));
  let pulledApkPath;

  await assert.rejects(
    inspectInstalledPackage({
      serial: 'ABC123',
      packageName: 'com.example.music',
      artifactsDir,
      runAdb: async ({ args }) => {
        if (args[0] === 'shell') {
          return { exitCode: 0, stdout: 'package:/data/app/example/base.apk\n', stderr: '' };
        }
        pulledApkPath = args[2];
        await writeFile(pulledApkPath, 'not a real APK');
        return { exitCode: 0, stdout: '', stderr: '' };
      },
      runProcess: async () => ({ exitCode: 1, stdout: '', stderr: 'PRIVATE_APKANALYZER_DIAGNOSTIC' })
    }),
    (error) => error.message === 'Manifest analysis failed' && !error.message.includes('PRIVATE_APKANALYZER_DIAGNOSTIC')
  );

  await assert.rejects(access(pulledApkPath));
});
