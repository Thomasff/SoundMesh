const MIN_SAMPLES = 30;
const MAX_UNAVAILABLE_RATIO = 0.5;
const NOISY_RESIDUAL_MS = 5;
const UNPREDICTABLE_HOLDOUT_MS = 5;
const GOOD_RESIDUAL_MS = 1;
const GOOD_HOLDOUT_MS = 2;

const MEDIAN_WINDOW = 5;

const median = values => {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  const middle = sorted.length >> 1;
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
};

/**
 * Least squares fit of presented frames against elapsed seconds.
 * Both axes are shifted to the first point first: raw nanosecond clocks are large enough
 * that squaring them loses the precision the whole measurement depends on.
 */
function fitLine(samples) {
  const baseNanos = samples[0][0];
  const baseFrames = samples[0][1];
  let sumX = 0; let sumY = 0; let sumXX = 0; let sumXY = 0;
  for (const [nanos, frames] of samples) {
    const x = (nanos - baseNanos) / 1e9;
    const y = frames - baseFrames;
    sumX += x; sumY += y; sumXX += x * x; sumXY += x * y;
  }
  const count = samples.length;
  const denominator = count * sumXX - sumX * sumX;
  if (denominator === 0) return null;
  const slope = (count * sumXY - sumX * sumY) / denominator;
  const intercept = (sumY - slope * sumX) / count;
  return { slope, intercept, baseNanos, baseFrames };
}

const predictFrames = (fit, nanos) => fit.baseFrames + fit.intercept + fit.slope * ((nanos - fit.baseNanos) / 1e9);

/**
 * What a consumer of these readings would actually see. Devices mix in occasional stale
 * snapshots that are isolated and one sided; a short median window removes them without
 * touching the trend, so both the raw and the usable noise floor get reported.
 */
function medianFilter(values) {
  const half = MEDIAN_WINDOW >> 1;
  return values.map((value, index) =>
    index < half || index >= values.length - half ? value : median(values.slice(index - half, index + half + 1)));
}

const spread = values => ({
  maxMs: Math.max(...values.map(Math.abs)),
  rmsMs: Math.sqrt(values.reduce((total, value) => total + value * value, 0) / values.length)
});

/**
 * Turns one device timestamp log into the two numbers the sync design depends on:
 * how fast this device's audio clock really runs, and how well a fitted model predicts it.
 */
export function analyzeClockProbe(deviceReport) {
  const nominalRate = deviceReport.sampleRate;
  const samples = deviceReport.samples ?? [];
  const requested = deviceReport.requested ?? samples.length;
  const unavailable = deviceReport.unavailable ?? 0;
  const framesToMs = frames => (frames / nominalRate) * 1000;
  const reasons = [];

  if (deviceReport.failureCode) reasons.push('DEVICE_REPORTED_FAILURE');
  if (requested > 0 && unavailable / requested > MAX_UNAVAILABLE_RATIO) reasons.push('TIMESTAMP_UNRELIABLE');
  let monotonic = true;
  for (let index = 1; index < samples.length; index++) {
    if (samples[index][1] < samples[index - 1][1] || samples[index][0] < samples[index - 1][0]) monotonic = false;
  }
  if (!monotonic) reasons.push('NON_MONOTONIC_FRAMES');

  const empty = Object.freeze({
    sampleCount: samples.length, requested, unavailable, duplicates: deviceReport.duplicates ?? 0,
    nominalSampleRate: nominalRate, actualSampleRate: null, ppmDeviation: null,
    residual: Object.freeze({ maxMs: null, rmsMs: null }),
    filteredResidual: Object.freeze({ maxMs: null, rmsMs: null }),
    holdout: Object.freeze({ fitSeconds: null, predictSeconds: null, maxErrorMs: null }),
    latency: Object.freeze({ medianFrames: null, medianMs: null }),
    timestampUpdate: Object.freeze({ medianMs: null, maxMs: null }),
    monotonic, verdict: 'POOR'
  });

  if (samples.length < MIN_SAMPLES) {
    return Object.freeze({ ...empty, reasons: Object.freeze([...reasons, 'INSUFFICIENT_SAMPLES']) });
  }
  const fit = fitLine(samples);
  if (!fit) return Object.freeze({ ...empty, reasons: Object.freeze([...reasons, 'INSUFFICIENT_SAMPLES']) });

  const residualsMs = samples.map(([nanos, frames]) => framesToMs(frames - predictFrames(fit, nanos)));
  const rawResidual = spread(residualsMs);
  const filteredResidual = spread(medianFilter(residualsMs));

  const split = samples.length >> 1;
  const holdoutFit = fitLine(samples.slice(0, split));
  let holdoutError = 0;
  for (const [nanos, frames] of samples.slice(split)) {
    holdoutError = Math.max(holdoutError, Math.abs(frames - predictFrames(holdoutFit, nanos)));
  }

  const gaps = [];
  for (let index = 1; index < samples.length; index++) gaps.push((samples[index][0] - samples[index - 1][0]) / 1e6);
  const depths = samples.map(([, frames, written]) => written - frames);

  const residualMaxMs = rawResidual.maxMs;
  const holdoutMaxErrorMs = framesToMs(holdoutError);
  if (residualMaxMs > NOISY_RESIDUAL_MS) reasons.push('NOISY_TIMESTAMPS');
  if (holdoutMaxErrorMs > UNPREDICTABLE_HOLDOUT_MS) reasons.push('UNPREDICTABLE_DRIFT');

  const verdict = reasons.length > 0 ? 'POOR'
    : residualMaxMs <= GOOD_RESIDUAL_MS && holdoutMaxErrorMs <= GOOD_HOLDOUT_MS ? 'GOOD'
      : 'WORKABLE';

  return Object.freeze({
    sampleCount: samples.length, requested, unavailable, duplicates: deviceReport.duplicates ?? 0,
    nominalSampleRate: nominalRate,
    actualSampleRate: fit.slope,
    ppmDeviation: ((fit.slope - nominalRate) / nominalRate) * 1e6,
    residual: Object.freeze(rawResidual),
    filteredResidual: Object.freeze(filteredResidual),
    holdout: Object.freeze({
      fitSeconds: (samples[split - 1][0] - samples[0][0]) / 1e9,
      predictSeconds: (samples[samples.length - 1][0] - samples[split][0]) / 1e9,
      maxErrorMs: holdoutMaxErrorMs
    }),
    latency: Object.freeze({ medianFrames: median(depths), medianMs: framesToMs(median(depths)) }),
    timestampUpdate: Object.freeze({ medianMs: median(gaps), maxMs: Math.max(...gaps) }),
    monotonic, verdict, reasons: Object.freeze(reasons)
  });
}
