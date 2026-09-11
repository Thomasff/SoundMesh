/**
 * Whether the copies that follow every chirp are in the recording or only in the correlation.
 *
 * Every archived run shows two or three fainter copies of each chirp behind it, spaced 121-143 ms
 * apart. At that spacing a reflection would have travelled forty metres, so the room cannot be
 * making them. The tool that found them blanks one reference length either side of each peak it
 * takes, which is 120 ms - so it can only ever report a copy at 120 ms or more, and its answer
 * cannot be used to say where the structure is. This prints the curve with no guard and no
 * threshold, beside two things that tell a played sound from a computed one:
 *
 *   - the reference correlated against a single clean copy of itself, which is what the correlator
 *     does when nothing is repeated. Beyond one reference length the two no longer overlap, so
 *     this is exactly zero there by construction and any peak past 120 ms in a recording is either
 *     real sound or noise.
 *   - the recording's own envelope, in dB over its quiet floor. A chirp that was played twice puts
 *     energy in the air the second time; one that was only correlated twice does not.
 *
 * Usage: node tools/analysis/is-the-chirp-played-twice.mjs <dir-with-the-pulled-wavs> [maxLagMs]
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const SAMPLE_RATE = 48000
const MAX_LAG_MS = Number(process.argv[3] ?? 300)
/** Envelope block. Short against the copies' spacing, long enough to average out one period. */
const BLOCK_MS = 5

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
  for (let i = 0; i < frames; i++) out[i] = data.readInt16LE(i * 2 * channels)
  return out
}

/** |sum of products| at every lag in [from, to], the same score the measurement ranks on. */
const correlate = (recorded, reference, from, to) => {
  const first = Math.max(0, from)
  const last = Math.min(to, recorded.length - reference.length)
  const scores = new Float64Array(Math.max(0, last - first + 1))
  for (let offset = first; offset <= last; offset++) {
    let total = 0
    for (let i = 0; i < reference.length; i++) total += recorded[offset + i] * reference[i]
    scores[offset - first] = Math.abs(total)
  }
  return scores
}

const rmsAt = (pcm, at, frames) => {
  let total = 0
  const first = Math.max(0, at)
  const last = Math.min(pcm.length, at + frames)
  for (let i = first; i < last; i++) total += pcm[i] * pcm[i]
  return Math.sqrt(total / Math.max(1, last - first))
}

const medianOf = values => {
  const sorted = Float64Array.from(values).sort()
  return sorted.length ? sorted[sorted.length >> 1] : 0
}

const db = (value, reference) =>
  value <= 0 || reference <= 0 ? -Infinity : 20 * Math.log10(value / reference)

const show = value => (value === -Infinity ? '   -inf' : value.toFixed(1).padStart(7))

const dir = process.argv[2]
if (!dir) throw new Error('give the directory holding M6-calibration.wav and X10-calibration.wav')

const reference = readWav(join(dir, 'M6-chirp.wav'))
const maxLag = Math.round(MAX_LAG_MS / 1000 * SAMPLE_RATE)
const block = Math.round(BLOCK_MS / 1000 * SAMPLE_RATE)

// ---- the control: one clean copy of the reference, nothing else in the buffer.
{
  const clean = new Float64Array(reference.length + maxLag + block)
  clean.set(reference, 0)
  const scores = correlate(clean, reference, 0, maxLag)
  const peak = scores[0]
  let worstPast = 0
  for (let lag = reference.length; lag < scores.length; lag++) worstPast = Math.max(worstPast, scores[lag])
  console.log('control: the reference against one clean copy of itself')
  console.log(`  peak ${peak.toExponential(3)}   highest score beyond one reference length ` +
    `${worstPast.toExponential(3)} (${show(db(worstPast, peak))} dB)`)
  console.log('  beyond 120.0 ms the two no longer overlap, so a correlator alone cannot put a copy there.')
}

const sides = [
  { name: 'HOST (M6)', pcm: readWav(join(dir, 'M6-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'M6-run.json'), 'utf8')) },
  { name: 'SINK (X10)', pcm: readWav(join(dir, 'X10-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'sink-last.json'), 'utf8')) }
]

for (const side of sides) {
  // The quiet floor of this recording, taken over the whole of it: the chirps are a few per cent
  // of it, so the median block is silence between them.
  const blocks = []
  for (let at = 0; at + block < side.pcm.length; at += block) blocks.push(rmsAt(side.pcm, at, block))
  const floor = medianOf(blocks)
  console.log(`\n===== ${side.name}   envelope floor ${floor.toFixed(1)} =====`)

  for (const [n, pair] of side.report.pairs.entries()) {
    if (n > 0) break
    const own = side.report.role === 'HOST' ? pair.secondIndex : pair.firstIndex
    const scores = correlate(side.pcm, reference, own, own + maxLag)
    const peak = scores[0]
    console.log(` pair ${n}: own chirp at ${own}`)
    console.log('     lag ms   at ms   corr dB   envelope dB over floor')
    // Binned rather than peak-picked: the loudest lag in each block, with no guard and no
    // threshold. A guard is what made the copies look like discrete events in the first place.
    for (let at = 0; at + block <= scores.length; at += block) {
      let bestAt = at
      for (let lag = at; lag < at + block; lag++) if (scores[lag] > scores[bestAt]) bestAt = lag
      const envelope = db(rmsAt(side.pcm, own + at, block), floor)
      console.log(`  ${(at / SAMPLE_RATE * 1000).toFixed(1).padStart(9)} ` +
        `${(bestAt / SAMPLE_RATE * 1000).toFixed(2).padStart(7)}  ` +
        `${show(db(scores[bestAt], peak))}   ${show(envelope)}`)
    }

    // And the test that tells the two apart. A sound that was really played again sits at a
    // fixed delay, so where it is reported cannot depend on how the reporting tool was set up.
    // The peak picker blanks one guard either side of everything it takes, so if the copies are
    // its own doing, they move when the guard moves - and the second one lands just past the
    // guard every time, wherever the guard is put.
    console.log('     guard ms   second peak reported at ms   corr dB')
    for (const guardMs of [10, 20, 40, 80, 120]) {
      const guard = Math.round(guardMs / 1000 * SAMPLE_RATE)
      let bestAt = -1
      for (let lag = guard + 1; lag < scores.length; lag++) {
        if (bestAt < 0 || scores[lag] > scores[bestAt]) bestAt = lag
      }
      console.log(`  ${String(guardMs).padStart(11)}   ${(bestAt / SAMPLE_RATE * 1000).toFixed(2).padStart(23)}   ` +
        `${show(db(scores[bestAt], peak))}`)
    }
  }
}
