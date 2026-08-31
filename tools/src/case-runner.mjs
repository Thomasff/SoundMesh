import { readFile } from 'node:fs/promises';
import { analyzeCapture as defaultAnalyze } from './capture-analysis.mjs';
import { ProbeStatusNotReadyError } from './probe-client.mjs';

const fail = message => { throw new Error(message); };

export async function driveProbeStates(states, acknowledge, { maxPolls = 120, deviceState = 'device', checkpoints = {}, allowPending = false } = {}) {
  if (deviceState !== 'device') fail(`Device is ${deviceState}; refusing to continue`);
  for (let index = 0; index < Math.min(states.length, maxPolls); index++) {
    const state = typeof states[index] === 'string' ? states[index] : states[index]?.state;
    if (state === 'AWAITING_PERMISSION' && !checkpoints.permissionPrompted) { await acknowledge('Approve MediaProjection on the phone'); checkpoints.permissionPrompted = true; }
    if (state === 'COMPLETE') return Object.freeze({ state });
    if (state === 'VOLUME_RESTORE_REQUIRED') fail('Probe requires volume restoration; stop and restore it before another case');
    if (state === 'FAILED' || state === 'CANCELLED') fail(`Probe failed with state ${state}`);
    if (!state || !['AWAITING_PERMISSION', 'SESSION_READY', 'CAPTURING'].includes(state)) fail('Probe returned an ambiguous state');
  }
  if (allowPending && states.length > 0) return Object.freeze({ state: typeof states.at(-1) === 'string' ? states.at(-1) : states.at(-1)?.state });
  fail('Probe polling timed out');
}

export async function runCaptureCase({ serial, apkPath, probe, acknowledge, deviceAlias, appName, probeCase, sessionId = crypto.randomUUID(), maxPolls = 120, pollDelay = () => new Promise(resolve => setTimeout(resolve, 1000)), analyze = defaultAnalyze, wavPath, readWav = readFile, audibleSource = true }) {
  if (!serial) fail('A confirmed serial is required');
  if (!probe || !probeCase || !acknowledge) fail('Probe, case, and acknowledgement dependencies are required');
  await probe.install({ serial, apkPath });
  await probe.start({ serial, sessionId, ...probeCase });
  const checkpoints = {}; let playbackPrompted = false; let terminal;
  for (let index = 0; index < maxPolls; index++) {
    let status;
    try { status = await probe.readStatus({ serial, caseId: probeCase.caseId }); }
    catch (error) { if (error instanceof ProbeStatusNotReadyError || error?.code === 'NOT_READY') { await pollDelay(); continue; } throw error; }
    if (status.state === 'AWAITING_PERMISSION') { await driveProbeStates([status], async action => acknowledge(`HUMAN ACTION: ${action}`), { checkpoints, allowPending: true }); await pollDelay(); continue; }
    if (status.state === 'SESSION_READY' && !playbackPrompted) { await acknowledge(`HUMAN ACTION: On ${deviceAlias}, open ${appName}, play a continuously audible free track at normal speaker volume, then press Enter here.`); playbackPrompted = true; await pollDelay(); continue; }
    if (status.state === 'COMPLETE') { terminal = status; break; }
    await driveProbeStates([status], async () => {}, { allowPending: true });
    await pollDelay();
  }
  if (!terminal) fail('Probe polling timed out');
  const capture = await probe.readCapture({ serial, caseId: probeCase.caseId });
  if (!wavPath) fail('An artifact WAV path is required');
  await probe.exportWav({ serial, caseId: probeCase.caseId, path: wavPath });
  const analysis = analyze({ wav: await readWav(wavPath), capture, audibleSource });
  return Object.freeze({ caseId: probeCase.caseId, appId: probeCase.expectedPackage, expectedBytes: capture.expectedBytes, capturedBytes: capture.capturedBytes, ...analysis });
}
