import assert from 'node:assert/strict';
import test from 'node:test';
import { analyzeAlignment, combineFacingPair, findArrival } from '../src/calibration-analysis.mjs';

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
  assert.ok(found.ratio > 20, `ratio was ${found.ratio}`);
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
    recorded, reference: chirp, sampleRate: SAMPLE_RATE, staggerFrames: stagger, searchRadiusFrames: 4800, separationMetres: 0
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
    staggerFrames: stagger, searchRadiusFrames: 4800, separationMetres: 0
  });

  assert.equal(result.measuredStaggerFrames, stagger + 96);
  assert.ok(Math.abs(result.alignmentErrorMs - 2) < 0.05, `error was ${result.alignmentErrorMs}`);
});

test('reports a perfectly aligned pair as zero error', () => {
  const stagger = SAMPLE_RATE / 2;
  const result = analyzeAlignment({
    recorded: recording([8000, 8000 + stagger]), reference: reference(),
    sampleRate: SAMPLE_RATE, staggerFrames: stagger, searchRadiusFrames: 4800, separationMetres: 0
  });

  assert.equal(result.alignmentErrorMs, 0);
});

test('rejects a search radius that could let the two chirps be mistaken for each other', () => {
  const stagger = SAMPLE_RATE / 2;

  assert.throws(() => analyzeAlignment({
    recorded: recording([8000, 8000 + stagger]), reference: reference(),
    sampleRate: SAMPLE_RATE, staggerFrames: stagger, searchRadiusFrames: stagger, separationMetres: 0
  }));
});

test('refuses to report a number when no chirp stands out of the noise', () => {
  const result = analyzeAlignment({
    recorded: recording([], { noise: 3000 }), reference: reference(),
    sampleRate: SAMPLE_RATE, staggerFrames: SAMPLE_RATE / 2, searchRadiusFrames: 4800, separationMetres: 0
  });

  assert.equal(result.confidence, 'UNRELIABLE');
  assert.equal(result.alignmentErrorMs, null);
});

test('refuses to measure at all without the distance between the two handsets', () => {
  const stagger = SAMPLE_RATE / 2;
  const call = separationMetres => () => analyzeAlignment({
    recorded: recording([8000, 8000 + stagger]), reference: reference(),
    sampleRate: SAMPLE_RATE, staggerFrames: stagger, searchRadiusFrames: 4800, separationMetres
  });

  assert.throws(call(undefined), /separationMetres is required/);
  assert.throws(call(-1), /separationMetres is required/);
  assert.doesNotThrow(call(0));
});

test('adds the sink chirp flight time back, so a wider separation reads more positive', () => {
  const stagger = SAMPLE_RATE / 2;
  // Physically aligned devices: the raw difference is exactly the stagger. What is left after
  // the correction is the bias the host's own chirp reaching its own microphone first would
  // otherwise have hidden.
  const measure = separationMetres => analyzeAlignment({
    recorded: recording([8000, 8000 + stagger]), reference: reference(),
    sampleRate: SAMPLE_RATE, staggerFrames: stagger, searchRadiusFrames: 4800, separationMetres
  });

  const near = measure(1);
  const far = measure(3);

  assert.ok(Math.abs(near.propagationCorrectionMs - 1000 / 343) < 1e-9, `correction was ${near.propagationCorrectionMs}`);
  assert.ok(Math.abs(near.alignmentErrorMs - 1000 / 343) < 1e-9, `error was ${near.alignmentErrorMs}`);
  assert.ok(far.alignmentErrorMs > near.alignmentErrorMs, 'a wider separation must read more positive');
  assert.ok(Math.abs(far.alignmentErrorMs - near.alignmentErrorMs - 2000 / 343) < 1e-9);
  assert.equal(measure(0).alignmentErrorMs, 0);
});

test('refuses a partner chirp whose peak sits on the edge of the search window', () => {
  const stagger = SAMPLE_RATE / 2;
  const radius = 12000;
  // The partner lands exactly on the last lag searched: the peak is real and clears the
  // confidence ratio easily, but it is indistinguishable from a chirp that fell outside the
  // window entirely and only overlapped its final lag.
  const chirp = reference();
  const recorded = recording([10000]);
  for (let index = 0; index < chirp.length; index++) {
    recorded[10000 + stagger + radius + index] += chirp[index] * 0.4;
  }

  const result = analyzeAlignment({
    recorded, reference: chirp, sampleRate: SAMPLE_RATE,
    staggerFrames: stagger, searchRadiusFrames: radius, separationMetres: 1
  });

  assert.equal(result.secondIndex, 10000 + stagger + radius);
  assert.deepEqual([...result.atSearchEdge], [false, true]);
  assert.equal(result.confidence, 'UNRELIABLE');
  assert.equal(result.alignmentErrorMs, null);
});

test('reads the pair inside the given window when the recording holds several chirp pairs', () => {
  // Two pairs well apart, each a 24000 frame stagger, the FIRST one far louder. A global
  // search always lands in the loud pair, so a call windowed onto the quiet one can only answer
  // correctly if the window is honoured.
  const chirp = reference();
  const recorded = recording([], { frames: SAMPLE_RATE * 3 });
  const plant = (offset, gain) => { for (let i = 0; i < chirp.length; i++) recorded[offset + i] += chirp[i] * gain; };
  plant(6000, 1.0); plant(30000, 1.0);
  plant(90000, 0.3); plant(114200, 0.3);
  const shared = { recorded, reference: chirp, sampleRate: SAMPLE_RATE, staggerFrames: 24000, searchRadiusFrames: 12000, separationMetres: 0 };

  assert.equal(analyzeAlignment(shared).firstIndex, 6000);

  const second = analyzeAlignment({ ...shared, searchFrom: 80000, searchTo: 130000 });

  assert.equal(second.confidence, 'OK');
  assert.equal(second.firstIndex, 90000);
  assert.equal(second.secondIndex, 114200);
});

/**
 * The two recordings of one chirp pair, as the two handsets hear it.
 *
 * Both hear the sink's chirp first and the host's a stagger later, but the flight time between
 * them enters with opposite signs: the host's own chirp reaches its own microphone across a few
 * centimetres while the sink's crosses the room, and on the sink it is the other way round. That
 * opposite sign is the whole point of recording on both - it is what lets the pair be combined
 * into an error that carries no flight time at all.
 */
function facingRecordings({ errorFrames, propagationFrames, sinkChirpAt = 20000 }) {
  const stagger = SAMPLE_RATE / 2;
  return {
    hostSide: recording([sinkChirpAt, sinkChirpAt + stagger + errorFrames - propagationFrames]),
    sinkSide: recording([sinkChirpAt, sinkChirpAt + stagger + errorFrames + propagationFrames])
  };
}

test('combines the two facing recordings into an error carrying no flight time, and measures the separation', () => {
  // 48 frames is 1ms of real misalignment; 140 frames is the 1m of air between the handsets.
  const { hostSide, sinkSide } = facingRecordings({ errorFrames: 48, propagationFrames: 140 });
  const read = side => analyzeAlignment({
    recorded: side, reference: reference(), sampleRate: SAMPLE_RATE,
    staggerFrames: SAMPLE_RATE / 2, searchRadiusFrames: 12000, separationMetres: 0
  });

  const combined = combineFacingPair({ hostSide: read(hostSide), sinkSide: read(sinkSide) });

  assert.equal(combined.confidence, 'OK');
  assert.ok(Math.abs(combined.alignmentErrorMs - 1.0) < 0.001, `error was ${combined.alignmentErrorMs}`);
  assert.ok(Math.abs(combined.separationMetres - 1.0) < 0.01, `separation was ${combined.separationMetres}`);
});

test('the combined error is free of the separation the single-sided reading has to be told', () => {
  // Same misalignment, twice the gap between the handsets. A single-sided reading needs the
  // distance handed to it and is wrong by 2.9ms per metre if that number is wrong; the combined
  // one is told nothing and still lands on the same error.
  const near = facingRecordings({ errorFrames: 48, propagationFrames: 140 });
  const far = facingRecordings({ errorFrames: 48, propagationFrames: 280 });
  const read = side => analyzeAlignment({
    recorded: side, reference: reference(), sampleRate: SAMPLE_RATE,
    staggerFrames: SAMPLE_RATE / 2, searchRadiusFrames: 12000, separationMetres: 0
  });

  const nearError = combineFacingPair({ hostSide: read(near.hostSide), sinkSide: read(near.sinkSide) }).alignmentErrorMs;
  const farError = combineFacingPair({ hostSide: read(far.hostSide), sinkSide: read(far.sinkSide) }).alignmentErrorMs;

  assert.ok(Math.abs(nearError - farError) < 0.001, `${nearError} vs ${farError}`);
});

test('refuses to combine when either side of the pair could not be read', () => {
  const { hostSide } = facingRecordings({ errorFrames: 48, propagationFrames: 140 });
  const good = analyzeAlignment({
    recorded: hostSide, reference: reference(), sampleRate: SAMPLE_RATE,
    staggerFrames: SAMPLE_RATE / 2, searchRadiusFrames: 12000, separationMetres: 0
  });
  const unreadable = { ...good, alignmentErrorMs: null, confidence: 'UNRELIABLE' };

  assert.equal(combineFacingPair({ hostSide: good, sinkSide: unreadable }), null);
  assert.equal(combineFacingPair({ hostSide: unreadable, sinkSide: good }), null);
  assert.equal(combineFacingPair({ hostSide: good, sinkSide: null }), null);
});
