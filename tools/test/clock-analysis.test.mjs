import assert from 'node:assert/strict';
import test from 'node:test';
import { analyzeClockProbe } from '../src/clock-analysis.mjs';

const NOMINAL = 48000;
const START_NANOS = 1_000_000_000_000;

/** Builds a device report whose true playback rate and noise are known, so the fit can be checked. */
function report({ seconds = 300, stepMs = 200, frameAt, noiseFrames = () => 0, latencyFrames = 4800, unavailable = 0, failureCode = null } = {}) {
  const samples = [];
  for (let index = 0; index * stepMs <= seconds * 1000; index++) {
    const elapsed = (index * stepMs) / 1000;
    const framePosition = Math.round(frameAt(elapsed) + noiseFrames(index));
    samples.push([START_NANOS + Math.round(elapsed * 1e9), framePosition, framePosition + latencyFrames]);
  }
  return { schemaVersion: 1, sampleRate: NOMINAL, channelCount: 2, requested: samples.length + unavailable, unavailable, duplicates: 0, failureCode, samples };
}

const steady = rate => elapsed => elapsed * rate;

test('recovers the real playback rate a slightly fast crystal produces', () => {
  const result = analyzeClockProbe(report({ frameAt: steady(48001.5) }));

  assert.ok(Math.abs(result.actualSampleRate - 48001.5) < 0.01, `sample rate was ${result.actualSampleRate}`);
  assert.ok(Math.abs(result.ppmDeviation - 31.25) < 0.1, `ppm was ${result.ppmDeviation}`);
  assert.ok(result.residual.maxMs < 0.02, `residual was ${result.residual.maxMs}`);
  assert.ok(result.holdout.maxErrorMs < 0.05, `holdout error was ${result.holdout.maxErrorMs}`);
  assert.equal(result.verdict, 'GOOD');
  assert.deepEqual(result.reasons, []);
});

test('reports the output buffer depth the device carries between written and presented frames', () => {
  const result = analyzeClockProbe(report({ frameAt: steady(NOMINAL), latencyFrames: 4800 }));

  assert.equal(result.latency.medianFrames, 4800);
  assert.ok(Math.abs(result.latency.medianMs - 100) < 0.001, `latency was ${result.latency.medianMs}ms`);
});

test('still calls a noisy but linear clock good, because the drift stays predictable', () => {
  // +/- 24 frames at 48 kHz is +/- 0.5 ms of measurement jitter.
  const result = analyzeClockProbe(report({ frameAt: steady(48000.6), noiseFrames: index => ((index % 5) - 2) * 12 }));

  assert.ok(result.residual.maxMs > 0.4 && result.residual.maxMs < 0.6, `residual was ${result.residual.maxMs}`);
  assert.ok(result.holdout.maxErrorMs < 2, `holdout error was ${result.holdout.maxErrorMs}`);
  assert.equal(result.verdict, 'GOOD');
});

test('rejects a clock whose rate changes halfway, because a fitted model cannot predict it', () => {
  const half = 150;
  const result = analyzeClockProbe(report({
    frameAt: elapsed => (elapsed <= half ? elapsed * NOMINAL : half * NOMINAL + (elapsed - half) * 48200)
  }));

  assert.ok(result.reasons.includes('UNPREDICTABLE_DRIFT'), `reasons were ${result.reasons.join(',')}`);
  assert.ok(result.holdout.maxErrorMs > 100, `holdout error was ${result.holdout.maxErrorMs}`);
  assert.equal(result.verdict, 'POOR');
});

test('rejects a timestamp that ever moves backwards', () => {
  const base = report({ frameAt: steady(NOMINAL) });
  base.samples[800][1] = base.samples[700][1];

  const result = analyzeClockProbe(base);

  assert.ok(result.reasons.includes('NON_MONOTONIC_FRAMES'));
  assert.equal(result.verdict, 'POOR');
});

test('rejects a device that mostly fails to answer the timestamp query', () => {
  const result = analyzeClockProbe(report({ frameAt: steady(NOMINAL), unavailable: 4000 }));

  assert.ok(result.reasons.includes('TIMESTAMP_UNRELIABLE'));
  assert.equal(result.verdict, 'POOR');
});

test('refuses to fit a line through too few points instead of reporting a confident number', () => {
  const result = analyzeClockProbe(report({ seconds: 4, frameAt: steady(NOMINAL) }));

  assert.ok(result.reasons.includes('INSUFFICIENT_SAMPLES'));
  assert.equal(result.actualSampleRate, null);
  assert.equal(result.verdict, 'POOR');
});

test('carries a device side failure through instead of analysing a partial run as if it were clean', () => {
  const result = analyzeClockProbe(report({ frameAt: steady(NOMINAL), failureCode: 'IllegalStateException' }));

  assert.ok(result.reasons.includes('DEVICE_REPORTED_FAILURE'));
  assert.equal(result.verdict, 'POOR');
});

test('reports how often the device actually refreshes its timestamp', () => {
  const result = analyzeClockProbe(report({ frameAt: steady(NOMINAL), stepMs: 200 }));

  assert.equal(result.timestampUpdate.medianMs, 200);
});
