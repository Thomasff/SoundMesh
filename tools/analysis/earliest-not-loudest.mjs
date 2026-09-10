/**
 * Recomputes the separation from the first significant arrival instead of the loudest one.
 *
 * Measured 09-11: at two metres one side's reception is a thirty millisecond plateau of peaks
 * within a decibel of each other, so `argmax` picks a different one from run to run and the
 * separation jumps by metres with nothing moved. The direct sound is the *earliest* arrival, not
 * the strongest - every later path is longer by definition - so the leading edge is what a
 * distance should be read from.
 *
 * Usage: node tools/analysis/earliest-not-loudest.mjs <dir-of-run-dirs>
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join } from 'node:path'

const SAMPLE_RATE = 48000
const STAGGER_MS = 500

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

/** The first lag reaching `share` of the window's best, which is the leading edge of the arrival. */
const leadingEdge = (scores, from, share) => {
  let best = 0
  for (const v of scores) if (v > best) best = v
  for (let i = 0; i < scores.length; i++) if (scores[i] >= share * best) return from + i
  return null
}

const root = process.argv[2]
const shares = [1.0, 0.7, 0.5, 0.3, 0.2]
const rows = []
for (const tag of readdirSync(root)) {
  const dir = join(root, tag)
  if (!existsSync(join(dir, 'M6-calibration.wav'))) continue
  const reference = readWav(join(dir, 'M6-chirp.wav'))
  const host = { pcm: readWav(join(dir, 'M6-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'M6-run.json'), 'utf8')), peerIs: 'first' }
  const sink = { pcm: readWav(join(dir, 'X10-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'sink-last.json'), 'utf8')), peerIs: 'second' }
  for (let p = 0; p < host.report.pairs.length; p++) {
    const row = { tag: `${tag}-${p}` }
    for (const share of shares) {
      const edgeOf = side => {
        const pair = side.report.pairs[p]
        const own = side.peerIs === 'first' ? pair.secondIndex : pair.firstIndex
        const peer = side.peerIs === 'first' ? pair.firstIndex : pair.secondIndex
        if (own == null || peer == null) return null
        // A window wide enough to hold the whole plateau but not the next chirp's copies.
        const half = Math.round(0.045 * SAMPLE_RATE)
        const ownEdge = leadingEdge(curve(side.pcm, reference, own - half, own + half), own - half, share)
        const peerEdge = leadingEdge(curve(side.pcm, reference, peer - half, peer + half), peer - half, share)
        return { own: ownEdge, peer: peerEdge }
      }
      const h = edgeOf(host), s = edgeOf(sink)
      if (!h || !s) { row[share] = null; continue }
      const hostGap = (h.own - h.peer) / SAMPLE_RATE * 1000
      const sinkGap = (s.peer - s.own) / SAMPLE_RATE * 1000
      row[share] = { d: (sinkGap - hostGap) / 2 / 1000 * 343, e: (hostGap + sinkGap) / 2 - STAGGER_MS }
    }
    rows.push(row)
  }
}
console.log('run           ' + shares.map(s => `edge@${(s * 100).toFixed(0)}%`.padStart(11)).join(''))
for (const r of rows) {
  console.log(r.tag.padEnd(14) + shares.map(s => (r[s] ? `${r[s].d.toFixed(2)}m`.padStart(11) : '       -   ')).join(''))
}
for (const s of shares) {
  const d = rows.map(r => r[s]?.d).filter(v => v != null)
  const mean = d.reduce((a, b) => a + b, 0) / d.length
  const sd = Math.sqrt(d.reduce((a, b) => a + (b - mean) ** 2, 0) / d.length)
  console.log(`edge@${(s * 100).toFixed(0)}%  mean ${mean.toFixed(2)} m   sd ${sd.toFixed(2)} m   min ${Math.min(...d).toFixed(2)}  max ${Math.max(...d).toFixed(2)}`)
}
