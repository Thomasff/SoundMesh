/**
 * The noise floor ratio (peak / median of all correlation scores) is a structural property of
 * pure noise, not a function of how loud that noise is: with scores behaving like half-normal
 * draws across tens of thousands of lags, the peak sits near 4.2 sigma while the median sits
 * near 0.67 sigma, giving a ratio near 6.3 regardless of amplitude (it only grows weakly,
 * roughly logarithmically, with the number of lags searched). A real chirp's ratio lands
 * orders of magnitude higher (tens of thousands in measurement). 20 is kept well below a real
 * chirp on purpose: a rejected valid measurement costs an entire device session, which is the
 * more expensive failure than accepting a slightly weaker true peak.
 */
const MIN_TRUSTWORTHY_RATIO = 20;

const median = values => {
  const sorted = Float64Array.from(values).sort();
  return sorted.length === 0 ? 0 : sorted[sorted.length >> 1];
};

/**
 * Slides the reference across the recording and reports the offset of the best match.
 *
 * The noise floor is the median of every correlation score, not the best rival peak: a
 * recording of this test deliberately holds two equally strong chirps, so each would rate the
 * other as its rival and no real measurement would ever look trustworthy.
 */
export function findArrival(recorded, reference, { searchFrom, searchTo }) {
  const from = Math.max(0, searchFrom);
  const to = Math.min(searchTo, recorded.length - reference.length);
  if (to < from) return null;
  const scores = new Float64Array(to - from + 1);
  for (let offset = from; offset <= to; offset++) {
    let total = 0;
    for (let index = 0; index < reference.length; index++) total += recorded[offset + index] * reference[index];
    scores[offset - from] = Math.abs(total);
  }
  let bestAt = 0;
  for (let index = 1; index < scores.length; index++) if (scores[index] > scores[bestAt]) bestAt = index;
  const floor = median(scores);
  return Object.freeze({
    index: from + bestAt,
    peak: scores[bestAt],
    floor,
    ratio: floor === 0 ? Infinity : scores[bestAt] / floor
  });
}

/**
 * Turns one recording of two staggered chirps into the alignment error between the devices.
 * The stagger is deliberate and known, so whatever is left over is the error being measured.
 *
 * Which chirp is louder in the recording depends on the room, so the strongest peak is not
 * necessarily the first one. The partner is searched for on both sides and the pair is then
 * ordered by time.
 */
export function analyzeAlignment({ recorded, reference, sampleRate, staggerFrames, searchRadiusFrames }) {
  // The ordering below trusts that a window centred on best.index + staggerFrames cannot reach
  // back to best.index itself (and the mirror window can't reach forward past it). Once the
  // radius reaches the stagger that stops holding, and the two chirps can be told apart from
  // each other only by which one happens to correlate louder - silently swapping first/second
  // and negating the reported error.
  if (searchRadiusFrames >= staggerFrames) {
    throw new Error('searchRadiusFrames must be smaller than staggerFrames, or the two chirps can be mistaken for each other');
  }
  const best = findArrival(recorded, reference, { searchFrom: 0, searchTo: recorded.length });
  const window = centre => findArrival(recorded, reference, {
    searchFrom: centre - searchRadiusFrames,
    searchTo: centre + searchRadiusFrames
  });
  const after = best && window(best.index + staggerFrames);
  const before = best && window(best.index - staggerFrames);
  const partnerIsAfter = (after?.peak ?? -1) >= (before?.peak ?? -1);
  const partner = partnerIsAfter ? after : before;
  const first = partnerIsAfter ? best : partner;
  const second = partnerIsAfter ? partner : best;
  const trustworthy = Boolean(first && second) &&
    first.ratio >= MIN_TRUSTWORTHY_RATIO && second.ratio >= MIN_TRUSTWORTHY_RATIO;
  const measuredStaggerFrames = trustworthy ? second.index - first.index : null;
  return Object.freeze({
    firstIndex: first?.index ?? null,
    secondIndex: second?.index ?? null,
    measuredStaggerFrames,
    alignmentErrorMs: trustworthy ? ((measuredStaggerFrames - staggerFrames) / sampleRate) * 1000 : null,
    confidence: trustworthy ? 'OK' : 'UNRELIABLE',
    ratios: Object.freeze([first?.ratio ?? null, second?.ratio ?? null])
  });
}
