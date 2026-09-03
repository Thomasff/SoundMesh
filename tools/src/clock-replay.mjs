/**
 * Replays recorded clock exchanges through the offset estimator, offline.
 *
 * The estimator is the largest remaining error source in a sink's emission timing, and until now
 * every candidate change to it could only be judged by running the phones again - one run per arm,
 * against between-run scatter large enough that the last such comparison could not separate a real
 * improvement from luck. A run now records the exchanges it was built on, so any number of designs
 * can be scored on that same input instead, paired exactly.
 *
 * That only holds if this computes what the shipped estimator computes. The arithmetic below mirrors
 * ClockOffsetEstimator.estimate step for step, and both are pinned to one golden series - see
 * core/src/test/java/com/soundmesh/core/ClockReplayGoldenTest.kt. A recorded run carries its own
 * check as well: replaying its exchanges must reproduce the estimates it actually used.
 *
 * Timestamps arrive as [t1, t2, t3, t4] tuples of nanoseconds. The integer steps run in BigInt so
 * they truncate the way Kotlin's Long arithmetic does; the fit itself runs in doubles, which is what
 * the estimator does too.
 */

export const MIN_SAMPLES = 8;
export const DEFAULT_WINDOW = 32;
export const DEFAULT_BEST = 8;
const MAX_DRIFT_PPM = 500;

const roundTripNanos = ([t1, t2, t3, t4]) => (BigInt(t4) - BigInt(t1)) - (BigInt(t3) - BigInt(t2));
const offsetMidpointNanos = ([t1, t2, t3, t4]) => ((BigInt(t2) - BigInt(t1)) + (BigInt(t3) - BigInt(t4))) / 2n;

/** The estimate a window of exchanges supports, or null on the same terms the estimator refuses one. */
export function estimateFromWindow(window, { bestCount = DEFAULT_BEST } = {}) {
  if (window.length < MIN_SAMPLES) return null;
  const valid = window.filter(exchange => roundTripNanos(exchange) >= 0n);
  if (valid.length < MIN_SAMPLES) return null;
  // Stable, like Kotlin's sortedBy: exchanges tied on round trip keep the order they arrived in,
  // which decides which of them the best-of cut keeps.
  const best = valid
    .map((exchange, index) => ({ exchange, index, trip: roundTripNanos(exchange) }))
    .sort((left, right) => (left.trip === right.trip ? left.index - right.index : (left.trip < right.trip ? -1 : 1)))
    .slice(0, bestCount)
    .map(entry => entry.exchange);

  const baseNanos = best.reduce((lowest, [t1]) => (BigInt(t1) < lowest ? BigInt(t1) : lowest), BigInt(best[0][0]));
  let sumX = 0, sumY = 0, sumXX = 0, sumXY = 0;
  for (const exchange of best) {
    const x = Number(BigInt(exchange[0]) - baseNanos) / 1e9;
    const y = Number(offsetMidpointNanos(exchange));
    sumX += x; sumY += y; sumXX += x * x; sumXY += x * y;
  }
  const count = best.length;
  const denominator = count * sumXX - sumX * sumX;
  const slope = denominator === 0 ? 0 : (count * sumXY - sumX * sumY) / denominator;
  const intercept = (sumY - slope * sumX) / count;
  // Anchored at the centroid of the kept exchanges, exactly as the estimator anchors it.
  const offset = sumY / count;
  const driftPpm = slope / 1000;

  if (!Number.isFinite(offset) || !Number.isFinite(slope) || !Number.isFinite(intercept)) return null;
  const offsetNanos = Math.trunc(offset);
  if (Math.abs(driftPpm) > MAX_DRIFT_PPM) return null;

  const shortest = best.reduce((lowest, exchange) => {
    const trip = roundTripNanos(exchange);
    return trip < lowest ? trip : lowest;
  }, roundTripNanos(best[0]));

  // Where on the time axis this estimate actually sits: the mean t1 of the kept exchanges, which is
  // the centroid the fit is anchored at. Not part of what the shipped ClockEstimate carries - it is
  // diagnostic, and the four fields above are the ones pinned against Kotlin - but without it two
  // runs offset from each other cannot be compared, and a design's lag cannot be priced.
  const anchorT1 = Number(baseNanos) + (sumX / count) * 1e9;

  return { offsetNanos, uncertaintyNanos: Number(shortest / 2n), driftPpm, sampleCount: count, anchorT1 };
}

/**
 * One step per exchange, in the order the run fed them, each carrying the estimate that exchange
 * produced - null where the run had no usable one. Keeping the nulls in place is what lets an
 * estimate be located in the run that produced it rather than counted off from a compacted list.
 */
export function replay(exchanges, { windowSize = DEFAULT_WINDOW, bestCount = DEFAULT_BEST } = {}) {
  const window = [];
  return exchanges.map(exchange => {
    window.push(exchange);
    while (window.length > windowSize) window.shift();
    return { t1: exchange[0], t4: exchange[3], estimate: estimateFromWindow(window, { bestCount }) };
  });
}

/**
 * Every [stride]-th exchange starting at [phase] - one of [stride] disjoint runs at [stride] times
 * the recorded interval.
 *
 * A run collected at a denser cadence than production therefore yields several genuine runs of the
 * production cadence, all over the same stretch of the same two clocks. What they disagree about is
 * the estimator's own noise, and nothing has to be assumed about the true offset to read it.
 */
export function decimate(exchanges, stride, phase) {
  return exchanges.filter((_, index) => index % stride === phase);
}
