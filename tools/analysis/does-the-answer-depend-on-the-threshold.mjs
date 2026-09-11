/**
 * Asks whether a run's own answer holds still when the leading-edge threshold is moved.
 *
 * The threshold is the one fitted number in the distance measurement, and 09-11 showed it is
 * fitted to more than the room: with a clear line of sight the best share is 20%, and with a body
 * in the way it slides to 5-10%. A run cannot know which case it is in by looking at how
 * repeatable it is - the blocked runs were *more* self-consistent than the clear ones and still
 * wrong by 1.3 m.
 *
 * What it can look at is whether its own answer depends on the fitted number. When the direct
 * sound is a clean onset, every threshold lands on the same lag and the estimate does not move.
 * When the direct sound never arrived, each threshold lands on a different reflection and the
 * estimate slides. That spread is measurable per pair, needs no new instrument, and - unlike the
 * depth of the picked edge, which is 20*log10(share) by construction - is not circular.
 *
 * Usage: node tools/analysis/does-the-answer-depend-on-the-threshold.mjs <dir-of-run-dirs>
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join } from 'node:path'

const SAMPLE_RATE = 48000
const STAGGER_MS = 500
const SHARES = [0.30, 0.20, 0.15, 0.10, 0.05]

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

const edgeAt = (scores, from, share) => {
  let best = 0
  for (const v of scores) if (v > best) best = v
  for (let i = 0; i < scores.length; i++) if (scores[i] >= share * best) return from + i
  return null
}

const root = process.argv[2]
const rows = []
for (const tag of readdirSync(root).sort()) {
  const dir = join(root, tag)
  if (!existsSync(join(dir, 'M6-calibration.wav'))) continue
  const reference = readWav(join(dir, 'M6-chirp.wav'))
  const host = { pcm: readWav(join(dir, 'M6-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'M6-run.json'), 'utf8')), peerIs: 'first' }
  const sink = { pcm: readWav(join(dir, 'X10-calibration.wav')), report: JSON.parse(readFileSync(join(dir, 'sink-last.json'), 'utf8')), peerIs: 'second' }
  const half = Math.round(0.045 * SAMPLE_RATE)
  // One pass of the correlation per window, reused by every threshold: the curves do not depend
  // on the share, only the pick does, so sweeping thresholds costs nothing beyond the first pass.
  for (let p = 0; p < host.report.pairs.length; p++) {
    const windows = []
    for (const side of [host, sink]) {
      const pair = side.report.pairs[p]
      const own = side.peerIs === 'first' ? pair.secondIndex : pair.firstIndex
      const peer = side.peerIs === 'first' ? pair.firstIndex : pair.secondIndex
      if (own == null || peer == null) { windows.length = 0; break }
      windows.push({
        own: { at: own - half, scores: curve(side.pcm, reference, own - half, own + half) },
        peer: { at: peer - half, scores: curve(side.pcm, reference, peer - half, peer + half) }
      })
    }
    if (windows.length !== 2) continue
    const metres = []
    for (const share of SHARES) {
      const h = { own: edgeAt(windows[0].own.scores, windows[0].own.at, share), peer: edgeAt(windows[0].peer.scores, windows[0].peer.at, share) }
      const s = { own: edgeAt(windows[1].own.scores, windows[1].own.at, share), peer: edgeAt(windows[1].peer.scores, windows[1].peer.at, share) }
      if (h.own == null || h.peer == null || s.own == null || s.peer == null) { metres.push(null); continue }
      const hostGap = (h.own - h.peer) / SAMPLE_RATE * 1000
      const sinkGap = (s.peer - s.own) / SAMPLE_RATE * 1000
      metres.push((sinkGap - hostGap) / 2 / 1000 * 343)
    }
    const got = metres.filter(v => v != null)
    rows.push({ tag: `${tag}-${p}`, metres, spread: got.length ? Math.max(...got) - Math.min(...got) : null })
  }
}

console.log('pair              ' + SHARES.map(s => `@${(s * 100).toFixed(0)}%`.padStart(9)).join('') + '     跨度')
for (const r of rows) {
  console.log(
    r.tag.padEnd(18) +
    r.metres.map(v => (v == null ? '      -  ' : `${v.toFixed(2)}m`.padStart(9))).join('') +
    `   ${r.spread == null ? '-' : r.spread.toFixed(2) + 'm'}`.padStart(10)
  )
}
