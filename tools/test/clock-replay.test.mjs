import assert from 'node:assert/strict';
import test from 'node:test';
import { decimate, replay } from '../src/clock-replay.mjs';

// The same series as core/src/test/java/com/soundmesh/core/ClockReplayGoldenTest.kt, which pins what
// the shipped estimator answers for it. The two must agree: an offline ranking of candidate designs
// computed by a replay that quietly differs from the estimator looks exactly like a correct one.
const SERIES = [
  [1000000000, 4007261232, 4007561232, 1011979747],
  [1500000000, 4507089868, 4507389868, 1517326917],
  [2000000000, 5007475692, 5007775692, 2015120679],
  [2500000000, 5503431584, 5503731584, 2512746961],
  [3000000000, 6007718191, 6008018191, 3016578467],
  [3500000000, 6507985355, 6508285355, 3512917289],
  [4000000000, 7008431169, 7008731169, 4012188850],
  [4500000000, 7506728973, 7507028973, 4510809069],
  [5000000000, 8003771587, 8004071587, 5012272175],
  [5500000000, 8509080451, 8509380451, 5518022351],
  [6000000000, 9003010709, 9003310709, 6008953809],
  [6500000000, 9507980665, 9508280665, 6513078772],
  [7000000000, 10004374538, 10004674538, 7009480582],
  [7500000000, 10509499795, 10509799795, 7518615912],
  [8000000000, 11005514152, 11005814152, 8011083396],
  [8500000000, 11508727666, 11509027666, 8512239478]
];

const GOLDEN = [
  '3000212145/-57.95153383458646/8/5254534',
  '3000212145/-57.95153383458646/8/5254534',
  '3000100251/-249.96854634146342/8/4326904',
  '3000290991/-58.45035519125683/8/4326904',
  '3000065112/-226.5201791411043/8/4326904',
  '3000065112/-226.5201791411043/8/4326904',
  '2999870830/-96.44137162162163/8/4326904',
  '3000564558/-69.76448192771085/8/4326904'
];

test('answers exactly what the shipped estimator answers, including the window it rejects', () => {
  const answers = replay(SERIES)
    .filter(step => step.estimate !== null)
    .map(({ estimate }) => `${estimate.offsetNanos}/${estimate.driftPpm}/${estimate.sampleCount}/${estimate.uncertaintyNanos}`);

  assert.deepEqual(answers, GOLDEN);
});

test('every exchange gets a step, so an estimate can be located in the run that produced it', () => {
  const steps = replay(SERIES);
  assert.equal(steps.length, SERIES.length);
  // Nothing before the window has filled, and the rejected fit is a null in place rather than a gap.
  assert.equal(steps.slice(0, 7).every(step => step.estimate === null), true);
  assert.equal(steps.filter(step => step.estimate === null).length, 8);
  assert.deepEqual(steps.map(step => step.t1), SERIES.map(([t1]) => t1));
});

test('decimate splits a dense recording into independent runs of the real cadence', () => {
  // This is what a denser collection buys: four disjoint 2s-cadence runs out of one 500ms recording,
  // each a genuine run of the shipped configuration, all seeing the same true clock. Their spread is
  // the estimator's own noise, measured without any model of what the true offset was doing.
  const phases = [0, 1, 2, 3].map(phase => decimate(SERIES, 4, phase));

  assert.deepEqual(phases.map(phase => phase.length), [4, 4, 4, 4]);
  assert.deepEqual(phases[0].map(([t1]) => t1), [1000000000, 3000000000, 5000000000, 7000000000]);
  assert.deepEqual(phases[1].map(([t1]) => t1), [1500000000, 3500000000, 5500000000, 7500000000]);
  // Disjoint and complete: every exchange lands in exactly one phase.
  assert.deepEqual(phases.flat().map(([t1]) => t1).sort((a, b) => a - b), SERIES.map(([t1]) => t1));
});

test('each estimate carries the instant it is anchored at, which is not the instant it was asked at', () => {
  // The estimator anchors at the centroid of the exchanges it kept, so that is where an estimate
  // actually describes the clocks - and the gap back to now is the lag it pays. Both a comparison
  // between two runs offset from each other and the lag half of a design's cost need this instant;
  // neither can be read off the four fields the shipped estimate carries.
  const steps = replay(SERIES).filter(step => step.estimate !== null);

  for (const step of steps) {
    assert.equal(Number.isFinite(step.estimate.anchorT1), true);
    // Anchored inside the run, and behind the exchange that produced it.
    assert.equal(step.estimate.anchorT1 <= step.t1, true);
    assert.equal(step.estimate.anchorT1 >= SERIES[0][0], true);
  }

  // On a series where every round trip is equal and the offset is flat, the best-of cut keeps the
  // eight it has and the anchor is unambiguous: the mean of their t1. Reading it off SERIES instead
  // would mean re-deriving which exchanges the cut kept, which is the logic under test.
  const flat = Array.from({ length: 8 }, (_, index) => {
    const t1 = index * 1_000_000_000;
    const t2 = t1 + 1_000_000 + 3_000_000_000;
    return [t1, t2, t2, t2 - 3_000_000_000 + 1_000_000];
  });
  const [only] = replay(flat).filter(step => step.estimate !== null);

  assert.equal(only.estimate.anchorT1, 3_500_000_000);
  assert.equal(only.estimate.driftPpm, 0);
  assert.equal(only.estimate.offsetNanos, 3_000_000_000);
});
