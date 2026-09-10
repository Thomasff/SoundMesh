/**
 * Prints the correlation profile before a picked arrival, with no threshold at all.
 *
 * The peak lister answers "is there another peak"; a room whose reverberant field dominates at
 * the far microphone can put the direct sound below any threshold worth naming, and then the
 * honest question is not whether a peak clears a bar but what the curve actually does. So this
 * prints it, in decibels relative to the picked peak, one bin per millisecond.
 *
 * Usage: node tools/analysis/profile-before-the-peak.mjs <run-dir> [msBefore]
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
const msBefore = Number(process.argv[3] ?? 40)
const reference = readWav(join(dir, 'M6-chirp.wav'))
const sides = [
  { name: 'HOST (M6) hearing the sink', pcm: readWav(join(dir, 'M6-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'M6-run.json'), 'utf8')), peerIs: 'first' },
  { name: 'SINK (X10) hearing the host', pcm: readWav(join(dir, 'X10-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'sink-last.json'), 'utf8')), peerIs: 'second' }
]

const bar = db => {
  const n = Math.max(0, Math.round((db + 60) / 2))
  return '#'.repeat(Math.min(30, n))
}

for (const side of sides) {
  console.log(`\n== ${side.name} ==`)
  const pair = side.report.pairs[0]
  const picked = side.peerIs === 'first' ? pair.firstIndex : pair.secondIndex
  const from = picked - Math.round(msBefore / 1000 * SAMPLE_RATE)
  const at = offset => {
    let total = 0
    for (let i = 0; i < reference.length; i++) total += side.pcm[offset + i] * reference[i]
    return Math.abs(total)
  }
  const top = at(picked)
  // One bin per millisecond, taking the best lag inside each bin so a narrow arrival is not
  // stepped over.
  console.log(`  ms before picked | dB below the picked peak`)
  for (let ms = msBefore; ms >= -2; ms--) {
    let best = 0
    for (let k = 0; k < SAMPLE_RATE / 1000; k++) best = Math.max(best, at(from + (msBefore - ms) * SAMPLE_RATE / 1000 + k))
    const db = 20 * Math.log10(best / top)
    console.log(`  ${String(-ms).padStart(6)} ms       ${db.toFixed(1).padStart(7)} dB  ${bar(db)}`)
  }
}
