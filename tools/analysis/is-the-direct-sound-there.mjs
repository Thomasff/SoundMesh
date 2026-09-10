/**
 * Looks for a direct arrival earlier than the one a measurement picked.
 *
 * A reflection is always later than the direct sound, so a peak picked too late can only be a
 * reflection if something quieter sits ahead of it. The peak lister filters at the trustworthy
 * ratio; this one deliberately does not, because the question is whether the direct sound is
 * present at all - not whether it would have been believed.
 *
 * Usage: node tools/analysis/is-the-direct-sound-there.mjs <run-dir> [msBefore]
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const SAMPLE_RATE = 48000

const readWav = path => {
  const raw = readFileSync(path)
  let at = 12, channels = 1, data = null
  while (at + 8 <= raw.length) {
    const id = raw.toString('ascii', at, at + 4)
    const size = raw.readUInt32LE(at + 4)
    if (id === 'fmt ') channels = raw.readUInt16LE(at + 10)
    if (id === 'data') { data = raw.subarray(at + 8, at + 8 + size); break }
    at += 8 + size + (size & 1)
  }
  const frames = Math.floor(data.length / 2 / channels)
  const out = new Float64Array(frames)
  for (let i = 0; i < frames; i++) out[i] = data.readInt16LE(i * 2 * channels)
  return out
}

const dir = process.argv[2]
const msBefore = Number(process.argv[3] ?? 120)
const reference = readWav(join(dir, 'M6-chirp.wav'))
const sides = [
  { name: 'HOST (M6)  peer chirp', pcm: readWav(join(dir, 'M6-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'M6-run.json'), 'utf8')), peerIs: 'first' },
  { name: 'SINK (X10) peer chirp', pcm: readWav(join(dir, 'X10-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'sink-last.json'), 'utf8')), peerIs: 'second' }
]

for (const side of sides) {
  console.log(`\n== ${side.name} ==`)
  for (const [n, pair] of side.report.pairs.entries()) {
    const picked = side.peerIs === 'first' ? pair.firstIndex : pair.secondIndex
    if (picked == null) { console.log(` pair ${n}: unreadable`); continue }
    const from = picked - Math.round(msBefore / 1000 * SAMPLE_RATE)
    const to = picked + Math.round(0.01 * SAMPLE_RATE)
    const scores = new Float64Array(to - from + 1)
    for (let offset = from; offset <= to; offset++) {
      let total = 0
      for (let i = 0; i < reference.length; i++) total += side.pcm[offset + i] * reference[i]
      scores[offset - from] = Math.abs(total)
    }
    // The floor comes from a quiet stretch well before, so a window dominated by one strong
    // arrival does not raise its own noise estimate.
    const quietFrom = picked - Math.round(0.9 * SAMPLE_RATE)
    const quiet = new Float64Array(SAMPLE_RATE / 10)
    for (let k = 0; k < quiet.length; k++) {
      let total = 0
      for (let i = 0; i < reference.length; i++) total += side.pcm[quietFrom + k + i] * reference[i]
      quiet[k] = Math.abs(total)
    }
    const floor = Float64Array.from(quiet).sort()[quiet.length >> 1]
    const local = []
    for (let i = 1; i < scores.length - 1; i++) {
      if (scores[i] >= scores[i - 1] && scores[i] > scores[i + 1] && scores[i] / floor >= 3) {
        if (local.length && from + i - local[local.length - 1].index < reference.length / 2) {
          if (scores[i] > local[local.length - 1].score) local[local.length - 1] = { index: from + i, score: scores[i] }
        } else local.push({ index: from + i, score: scores[i] })
      }
    }
    const line = local.map(p => `${((p.index - picked) / SAMPLE_RATE * 1000).toFixed(1)}ms:r${(p.score / floor).toFixed(0)}`).join('  ')
    console.log(` pair ${n}: picked ${picked}. peaks in [-${msBefore}ms, +10ms] relative to it:`)
    console.log(`    ${line || '(nothing above 3x the floor)'}`)
  }
}
