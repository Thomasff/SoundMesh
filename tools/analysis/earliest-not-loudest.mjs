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

const medianOf = values => {
  const sorted = Float64Array.from(values).sort()
  return sorted.length ? sorted[sorted.length >> 1] : 0
}

/**
 * The first lag that counts as the arrival.
 *
 * A number share is a fraction of the window's own best, which has to be calibrated against known
 * distances because how far the plateau sits below the direct sound depends on the room. The
 * 'floor20' rule instead takes the first lag that clears the same MIN_TRUSTWORTHY_RATIO the
 * codebase already derives from pure-noise statistics - a rule with nothing fitted in it.
 */
const leadingEdge = (scores, from, share, floor) => {
  if (share === 'floor20') {
    for (let i = 0; i < scores.length; i++) if (scores[i] >= 20 * floor) return from + i
    return null
  }
  let best = 0
  for (const v of scores) if (v > best) best = v
  for (let i = 0; i < scores.length; i++) if (scores[i] >= share * best) return from + i
  return null
}

const root = process.argv[2]
// Half the window each arrival is read in, in ms. The runtime uses SEARCH_RADIUS_FRAMES = 12000,
// which is 250 - so this is how the two are checked for having read the same thing.
const HALF_WIDTH_MS = Number(process.argv[3] ?? 45)
const shares = [1.0, 0.3, 0.2, 0.15, 0.1, 0.05, 'floor20']
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
        const half = Math.round(HALF_WIDTH_MS / 1000 * SAMPLE_RATE)
        // The floor comes from a stretch with no chirp in it, so a window dominated by one
        // arrival cannot raise its own noise estimate.
        const quiet = medianOf(curve(side.pcm, reference, peer - Math.round(0.9 * SAMPLE_RATE), peer - Math.round(0.8 * SAMPLE_RATE)))
        const ownEdge = leadingEdge(curve(side.pcm, reference, own - half, own + half), own - half, share, quiet)
        const peerEdge = leadingEdge(curve(side.pcm, reference, peer - half, peer + half), peer - half, share, quiet)
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
console.log('run           ' + shares.map(s => (typeof s === 'string' ? s : `edge@${(s * 100).toFixed(0)}%`).padStart(11)).join(''))
for (const r of rows) {
  console.log(r.tag.padEnd(14) + shares.map(s => (r[s] ? `${r[s].d.toFixed(2)}m`.padStart(11) : '       -   ')).join(''))
}
for (const s of shares) {
  const d = rows.map(r => r[s]?.d).filter(v => v != null)
  const mean = d.reduce((a, b) => a + b, 0) / d.length
  const sd = Math.sqrt(d.reduce((a, b) => a + (b - mean) ** 2, 0) / d.length)
  console.log(`${(typeof s === 'string' ? s : 'edge@' + (s * 100).toFixed(0) + '%').padEnd(9)} mean ${mean.toFixed(2)} m   sd ${sd.toFixed(2)} m   min ${Math.min(...d).toFixed(2)}  max ${Math.max(...d).toFixed(2)}`)
}
