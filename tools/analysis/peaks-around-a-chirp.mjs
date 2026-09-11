/**
 * Lists every correlation peak near a chirp, not just the two the measurement picked.
 *
 * The on-device analysis takes the loudest peak, looks one stagger either side of it, and keeps
 * whichever side correlates louder. That is two numbers out of a curve with many, and when the
 * two handsets are far enough apart for one chirp to be a hundred times louder than the other,
 * the two numbers stop being enough to say what happened. This prints more of them.
 *
 * It does NOT print the curve, and reading it as though it did cost a day. After taking a peak
 * it blanks one reference length either side - 120 ms - so the next peak it is able to report
 * is always 120 ms or more away, and the one after that 240 ms or more. Run it on a chirp
 * followed by nothing but reverberation and it reports evenly spaced copies that are not there.
 * Whenever the spacing between what it reports is near the reference length, suspect this first:
 * `is-the-chirp-played-twice.mjs` prints the curve with no guard and no threshold, and sweeps
 * the guard to show what moves with it.
 *
 * Usage: node tools/analysis/peaks-around-a-chirp.mjs <dir-with-the-pulled-wavs>
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const SAMPLE_RATE = 48000

/** 16-bit PCM out of a RIFF file, by walking the chunks rather than assuming a 44 byte header. */
const readWav = path => {
  const raw = readFileSync(path)
  if (raw.toString('ascii', 0, 4) !== 'RIFF') throw new Error(`${path} is not RIFF`)
  let at = 12
  let channels = 1
  let data = null
  while (at + 8 <= raw.length) {
    const id = raw.toString('ascii', at, at + 4)
    const size = raw.readUInt32LE(at + 4)
    if (id === 'fmt ') channels = raw.readUInt16LE(at + 10)
    if (id === 'data') { data = raw.subarray(at + 8, at + 8 + size); break }
    at += 8 + size + (size & 1)
  }
  if (!data) throw new Error(`${path} has no data chunk`)
  const frames = Math.floor(data.length / 2 / channels)
  const out = new Float64Array(frames)
  // Only the left channel: the reference is mono and the recording is what it is compared to.
  for (let i = 0; i < frames; i++) out[i] = data.readInt16LE(i * 2 * channels)
  return out
}

const correlate = (recorded, reference, from, to) => {
  const first = Math.max(0, from)
  const last = Math.min(to, recorded.length - reference.length)
  const scores = new Float64Array(Math.max(0, last - first + 1))
  for (let offset = first; offset <= last; offset++) {
    let total = 0
    for (let i = 0; i < reference.length; i++) total += recorded[offset + i] * reference[i]
    scores[offset - first] = Math.abs(total)
  }
  return { first, scores }
}

const medianOf = values => {
  const sorted = Float64Array.from(values).sort()
  return sorted.length ? sorted[sorted.length >> 1] : 0
}

/** Local maxima at least `guard` frames apart, strongest first. */
const peaksOf = ({ first, scores }, floor, guard, want) => {
  const found = []
  const taken = new Uint8Array(scores.length)
  for (let n = 0; n < want; n++) {
    let at = -1
    for (let i = 0; i < scores.length; i++) if (!taken[i] && (at < 0 || scores[i] > scores[at])) at = i
    if (at < 0 || scores[at] <= 0) break
    found.push({ index: first + at, ratio: floor === 0 ? Infinity : scores[at] / floor })
    for (let i = Math.max(0, at - guard); i <= Math.min(scores.length - 1, at + guard); i++) taken[i] = 1
  }
  return found
}

const dir = process.argv[2]
if (!dir) throw new Error('give the directory holding M6-calibration.wav and X10-calibration.wav')

const reference = readWav(join(dir, 'M6-chirp.wav'))
const host = { name: 'HOST (M6)', pcm: readWav(join(dir, 'M6-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'M6-run.json'), 'utf8')) }
const sink = { name: 'SINK (X10)', pcm: readWav(join(dir, 'X10-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'sink-last.json'), 'utf8')) }

console.log(`reference ${reference.length} frames (${(reference.length / SAMPLE_RATE * 1000).toFixed(1)} ms)`)
// Printed because it is the answer to the question this tool's output invites: peaks nearer
// than this to one another cannot be reported, so a spacing near it means nothing.
console.log(`peaks nearer than ${(reference.length / SAMPLE_RATE * 1000).toFixed(1)} ms to a louder one are not reported`)
const ms = frames => (frames / SAMPLE_RATE * 1000)

for (const side of [host, sink]) {
  console.log(`\n===== ${side.name}  recording ${side.pcm.length} frames =====`)
  for (const [n, pair] of side.report.pairs.entries()) {
    // Anchor on the handset's own chirp, which is the peak nobody doubts, and look a whole
    // stagger either side of it plus margin.
    const own = side.report.role === 'HOST' ? pair.secondIndex : pair.firstIndex
    const from = own - Math.round(0.8 * SAMPLE_RATE)
    const to = own + Math.round(0.8 * SAMPLE_RATE)
    const curve = correlate(side.pcm, reference, from, to)
    const floor = medianOf(curve.scores)
    // Sorted by time, not by height: the direct sound is the earliest arrival above the noise,
    // and a reflection can be louder than it when the two handsets are a room apart.
    const peaks = peaksOf(curve, floor, reference.length, 10)
      .filter(p => p.ratio >= 20)
      .sort((a, b) => a.index - b.index)
    console.log(` pair ${n}: own chirp at ${own}, measurement picked ${pair.firstIndex} and ${pair.secondIndex}`)
    for (const p of peaks) {
      const tag = p.index === pair.firstIndex ? ' <- picked as FIRST'
        : p.index === pair.secondIndex ? ' <- picked as SECOND' : ''
      console.log(`    peak at ${String(p.index).padStart(7)}  ${ms(p.index - own).toFixed(2).padStart(9)} ms from own  ratio ${p.ratio.toFixed(0).padStart(7)}${tag}`)
    }
  }
}
