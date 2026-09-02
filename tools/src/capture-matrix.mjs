import { runCaptureCase as defaultRunCase } from './case-runner.mjs';

const VOLUME_MODES = Object.freeze({ UNCHANGED: baseline => baseline.current, MIN_NONZERO: () => 1, ZERO: () => 0 });

export function expectedVolumeIndex(volumeMode, baselineVolume) {
  const resolve = VOLUME_MODES[volumeMode];
  if (!resolve) throw new Error(`Unknown volume mode ${volumeMode}`);
  return resolve(baselineVolume);
}

export function caseInstruction({ probeCase, appName, targetVolume, maxVolume }) {
  return `HUMAN ACTION: [${probeCase.caseId}] ${appName}: ${probeCase.instruction} Set the media volume to ${targetVolume} of ${maxVolume}, then confirm here.`;
}

/** Runs several cases inside one user-approved projection session and always releases it. */
export async function runCaptureMatrix({
  serial, apkPath, probe, acknowledge, appId, appName, cases, baselineVolume, readVolume, wavPathFor,
  sessionId = crypto.randomUUID(), runCase = defaultRunCase, outcomes = {}, unavailable = []
}) {
  const results = []; const known = { ...outcomes };
  let sessionStarted = false; let volumeChanged = false;
  let aborted = false; let abortReason; let finishError;
  try {
    for (const probeCase of cases) {
      if (unavailable.includes(probeCase.caseId)) {
        results.push({ caseId: probeCase.caseId, outcome: 'NOT_AVAILABLE', reason: 'The user reported the required content is not available' });
        continue;
      }
      if (probeCase.prerequisite && known[probeCase.prerequisite] !== 'PASS') {
        results.push({ caseId: probeCase.caseId, outcome: 'SKIPPED', reason: `${probeCase.prerequisite} did not pass` });
        continue;
      }
      const targetVolume = expectedVolumeIndex(probeCase.volumeMode, baselineVolume);
      await acknowledge(caseInstruction({ probeCase, appName, targetVolume, maxVolume: baselineVolume.max }));
      const volume = await readVolume({ serial });
      if (volume.current !== targetVolume) {
        aborted = true;
        abortReason = `${probeCase.caseId} expects media volume ${targetVolume} of ${baselineVolume.max} but the device reports ${volume.current}`;
        break;
      }
      const installProbe = !sessionStarted;
      sessionStarted = true;
      if (probeCase.volumeMode !== 'UNCHANGED') volumeChanged = true;
      try {
        const outcome = await runCase({
          serial, apkPath, probe, acknowledge, sessionId, appName, installProbe, deviceAlias: 'Selected device',
          probeCase: { caseId: probeCase.caseId, durationSeconds: probeCase.durationSeconds, expectedPackage: appId },
          wavPath: wavPathFor(probeCase.caseId)
        });
        known[probeCase.caseId] = outcome.outcome;
        results.push(outcome);
      } catch (error) {
        aborted = true; abortReason = error.message;
        results.push({ caseId: probeCase.caseId, outcome: 'ERROR', reason: error.message });
        break;
      }
    }
  } finally {
    // Release the projection even when a case aborted, without masking why it aborted.
    if (sessionStarted) {
      try { await probe.finishSession({ serial, sessionId }); } catch (error) { finishError = error.message; }
    }
  }
  let volumeRestored;
  if (volumeChanged) {
    await acknowledge(`HUMAN ACTION: Restore the media volume to ${baselineVolume.current} of ${baselineVolume.max}, then confirm here.`);
    volumeRestored = (await readVolume({ serial })).current === baselineVolume.current;
  }
  return Object.freeze({ appId, sessionId, cases: Object.freeze(results), aborted, abortReason, volumeRestored, finishError });
}
