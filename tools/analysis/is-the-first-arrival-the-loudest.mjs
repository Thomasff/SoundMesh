/**
 * Asks, of the peer's arrival, whether the earliest path is also the strongest one.
 *
 * With a clear line of sight the direct sound is guaranteed to arrive *first* - every reflection
 * travels further - but nothing guarantees it arrives *loudest*. A handset's speaker and mic are
 * both directional and both point somewhere other than at the peer, so a ceiling or table bounce
 * can catch a stronger lobe than the straight line does. That is the whole reason `argmax` fails
 * at two metres even with nothing in the way, and it is why the fix is to pick the leading edge.
 *
 * Prints, for the peer's window on each side of each pair:
 *   depth     how far the leading edge sits below the window's own maximum, in dB
 *   later     how much later the maximum arrives than the leading edge, in ms
 *   plateau   the span of lags within 1 dB of the maximum, in ms
 *   edgeSnr   how far the leading edge sits above the noise floor, in dB
 *
 * Usage: node tools/analysis/is-the-first-arrival-the-loudest.mjs <dir-of-run-dirs>
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join } from 'node:path'

const SAMPLE_RATE = 48000
const SHARE = 0.20

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

const curve = (pcm, reference, from, to) => {
  const scores = new Float64Array(to - from + 1)
  for (let offset = from; offset <= to; offset++) {
    let total = 0
    for (let i = 0; i < reference.length; i++) total += pcm[offset + i] * reference[i]
    scores[offset - from] = Math.abs(total)
  }
  return scores
}

const medianOf = values => {
  const sorted = Float64Array.from(values).sort()
  return sorted.length ? sorted[sorted.length >> 1] : 0
}

const dB = ratio => 20 * Math.log10(Math.max(ratio, 1e-12))
const ms = frames => frames / SAMPLE_RATE * 1000

/** Everything this tool reports about one window, so the four numbers come from one pass. */
const shapeOf = (scores, floor) => {
  let best = 0, bestAt = 0
  for (let i = 0; i < scores.length; i++) if (scores[i] > best) { best = scores[i]; bestAt = i }
  let edgeAt = null
  for (let i = 0; i < scores.length; i++) if (scores[i] >= SHARE * best) { edgeAt = i; break }
  if (edgeAt === null) return null
  const nearTop = 10 ** (-1 / 20) * best
  let first = bestAt, last = bestAt
  for (let i = 0; i < scores.length; i++) if (scores[i] >= nearTop) { first = Math.min(first, i); last = Math.max(last, i) }
  return {
    depth: dB(scores[edgeAt] / best),
    later: ms(bestAt - edgeAt),
    plateau: ms(last - first),
    edgeSnr: dB(scores[edgeAt] / floor)
  }
}

const root = process.argv[2]
const groups = new Map()
for (const tag of readdirSync(root)) {
  const dir = join(root, tag)
  if (!existsSync(join(dir, 'M6-calibration.wav'))) continue
  const reference = readWav(join(dir, 'M6-chirp.wav'))
  const sides = [
    { pcm: readWav(join(dir, 'M6-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'M6-run.json'), 'utf8')), peerIs: 'first', name: 'M6' },
    { pcm: readWav(join(dir, 'X10-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'sink-last.json'), 'utf8')), peerIs: 'second', name: 'X10' }
  ]
  for (let p = 0; p < sides[0].report.pairs.length; p++) {
    for (const side of sides) {
      const pair = side.report.pairs[p]
      const peer = side.peerIs === 'first' ? pair.firstIndex : pair.secondIndex
      if (peer == null) continue
      const half = Math.round(0.045 * SAMPLE_RATE)
      const floor = medianOf(curve(side.pcm, reference, peer - Math.round(0.9 * SAMPLE_RATE), peer - Math.round(0.8 * SAMPLE_RATE)))
      const shape = shapeOf(curve(side.pcm, reference, peer - half, peer + half), floor)
      if (!shape) continue
      const key = `${tag[0]} ${side.name}`
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key).push(shape)
    }
  }
}

const summarise = values => {
  const mean = values.reduce((a, b) => a + b, 0) / values.length
  const sd = Math.sqrt(values.reduce((a, b) => a + (b - mean) ** 2, 0) / Math.max(values.length - 1, 1))
  return `${mean.toFixed(1)} ± ${sd.toFixed(1)}`
}

console.log('组 / 听的那台      n   前沿比峰顶低      最强比前沿晚       高原宽度        前沿高出本底')
for (const [key, rows] of [...groups].sort()) {
  console.log(
    key.padEnd(16) + String(rows.length).padStart(3) +
    `   ${summarise(rows.map(r => r.depth))} dB`.padEnd(20) +
    `${summarise(rows.map(r => r.later))} ms`.padEnd(18) +
    `${summarise(rows.map(r => r.plateau))} ms`.padEnd(16) +
    `${summarise(rows.map(r => r.edgeSnr))} dB`
  )
}
