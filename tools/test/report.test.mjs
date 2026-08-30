import assert from 'node:assert/strict';
import test from 'node:test';
import { renderSummary, redactOutcome } from '../src/report.mjs';

test('redacts device identifiers and private values from case report output', () => {
  const outcome = redactOutcome({ serial: 'secret-serial', fingerprintHash: 'hash', rawTrackUrl: 'https://x/?token=secret', caseId: 'C1', appId: 'com.netease.cloudmusic', outcome: 'PASS', peakDbfs: -12 });
  assert.deepEqual(outcome, { fingerprintHash: 'hash', caseId: 'C1', appId: 'com.netease.cloudmusic', outcome: 'PASS', peakDbfs: -12 });
  assert.doesNotMatch(renderSummary(outcome), /secret|serial|token/i);
});
