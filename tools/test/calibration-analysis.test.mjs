import assert from 'node:assert/strict';
import test from 'node:test';
import { analyzeAlignment, findArrival } from '../src/calibration-analysis.mjs';

const SAMPLE_RATE = 48000;

/** A deterministic sweep standing in for the device generated reference. */
function reference(frames = 5760) {
  const out = new Int16Array(frames);
  for (let index = 0; index < frames; index++) {
    const t = index / SAMPLE_RATE;
    const phase = 2 * Math.PI * (1000 * t + ((8000 - 1000) / 0.12) * t * t / 2);
    const window = 0.5 * (1 - Math.cos(2 * Math.PI * index / (frames - 1)));
    out[index] = Math.round(Math.sin(phase) * window * 12000);
  }
  return out;
}

/** Places copies of the reference at the given sample offsets inside a noisy buffer. */
function recording(offsets, { frames = SAMPLE_RATE * 2, noise = 40 } = {}) {
  const chirp = reference();
  const out = new Int16Array(frames);
  let seed = 7;
  for (let index = 0; index < frames; index++) {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff;
    out[index] = Math.round(((seed / 0x7fffffff) - 0.5) * 2 * noise);
  }
  for (const offset of offsets) {
    for (let index = 0; index < chirp.length; index++) out[offset + index] += chirp[index] * 0.6;
  }
  return out;
}

test('finds a chirp planted at a known sample offset', () => {
  const found = findArrival(recording([12345]), reference(), { searchFrom: 0, searchTo: 30000 });

  assert.equal(found.index, 12345);
  assert.ok(found.ratio > 6, `ratio was ${found.ratio}`);
});

test('returns nothing rather than a bogus index when the search range is empty', () => {
  assert.equal(findArrival(recording([1000]), reference(), { searchFrom: 5000, searchTo: 4000 }), null);
});

test('orders the two chirps by time even when the later one correlates more strongly', () => {
  const stagger = SAMPLE_RATE / 2;
  // The second copy is louder, so the global peak lands on it rather than on the first.
  const chirp = reference();
  const recorded = recording([9000]);
  for (let index = 0; index < chirp.length; index++) recorded[9000 + stagger + index] += chirp[index] * 1.4;

  const result = analyzeAlignment({
    recorded, reference: chirp, sampleRate: SAMPLE_RATE, staggerFrames: stagger, searchRadiusFrames: 4800
  });

  assert.equal(result.firstIndex, 9000);
  assert.equal(result.secondIndex, 9000 + stagger);
  assert.equal(result.alignmentErrorMs, 0);
});

test('measures the alignment error between two staggered devices', () => {
  const stagger = SAMPLE_RATE / 2;
  // The second device fires 96 samples (2 ms) later than it should have.
  const recorded = recording([10000, 10000 + stagger + 96]);

  const result = analyzeAlignment({
    recorded, reference: reference(), sampleRate: SAMPLE_RATE,
    staggerFrames: stagger, searchRadiusFrames: 4800
  });

  assert.equal(result.measuredStaggerFrames, stagger + 96);
  assert.ok(Math.abs(result.alignmentErrorMs - 2) < 0.05, `error was ${result.alignmentErrorMs}`);
});

test('reports a perfectly aligned pair as zero error', () => {
  const stagger = SAMPLE_RATE / 2;
  const result = analyzeAlignment({
    recorded: recording([8000, 8000 + stagger]), reference: reference(),
    sampleRate: SAMPLE_RATE, staggerFrames: stagger, searchRadiusFrames: 4800
  });

  assert.equal(result.alignmentErrorMs, 0);
});

test('refuses to report a number when no chirp stands out of the noise', () => {
  const result = analyzeAlignment({
    recorded: recording([], { noise: 3000 }), reference: reference(),
    sampleRate: SAMPLE_RATE, staggerFrames: SAMPLE_RATE / 2, searchRadiusFrames: 4800
  });

  assert.equal(result.confidence, 'UNRELIABLE');
  assert.equal(result.alignmentErrorMs, null);
});
