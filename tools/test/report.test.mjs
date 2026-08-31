import assert from 'node:assert/strict';
import test from 'node:test';
import { renderSummary, redactOutcome } from '../src/report.mjs';

test('redacts device identifiers and private values from case report output', () => {
  const outcome = redactOutcome({ serial: 'secret-serial', fingerprintHash: 'hash', rawTrackUrl: 'https://x/?token=secret', caseId: 'C1', appId: 'com.netease.cloudmusic', outcome: 'PASS', peakDbfs: -12 });
  assert.deepEqual(outcome, { fingerprintHash: 'hash', caseId: 'C1', appId: 'com.netease.cloudmusic', outcome: 'PASS', peakDbfs: -12 });
  assert.doesNotMatch(renderSummary(outcome), /secret|serial|token/i);
});

test('summary retains redacted verifier evidence', () => {
  const summary = renderSummary({ caseId: 'C1', appId: 'app', outcome: 'PARTIAL', format: { sampleRate: 48_000, channelCount: 2, bitsPerSample: 16 }, expectedBytes: 192000, capturedBytes: 188160, nonZeroRatio: 0.01, maxOneSecondPeakDbfs: -50, maxOneSecondRmsDbfs: -60, longestZeroWindowFrames: 24001, reasons: ['LOW_PEAK'] });
  assert.match(summary, /Expected bytes: 192000/); assert.match(summary, /Reasons: LOW_PEAK/); assert.match(summary, /48000 Hz/);
});

test('recursively drops nested secret-bearing values', () => {
  const redacted = redactOutcome({ caseId: 'C1', verifier: { serial: 'nope', nested: [{ authorization: 'nope' }, { peakDbfs: -12 }] }, privateMetadata: { cookie: 'nope' }, sessionId: 'nope' });
  assert.deepEqual(redacted, { caseId: 'C1', verifier: { nested: [{}, { peakDbfs: -12 }] } });
  assert.doesNotMatch(renderSummary(redacted), /nope|authorization|cookie|session/i);
});
