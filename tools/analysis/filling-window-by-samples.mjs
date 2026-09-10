/**
 * What the estimator's filling-window bias looks like on the axis both code paths share.
 *
 * Section 26 read the bias off archived exchanges and stated it in seconds - "the first four".
 * Seconds are not a property of the estimator. The estimator holds exchanges, and the two paths
 * that use it collect them at different rates: PeerCalibrateActivity every 250 ms, SinkSession -
 * the one that plays music - every 2000 ms. Four seconds is sixteen exchanges on one and two on
 * the other, so a conclusion stated in seconds does not cross between them.
 *
 * This restates 2026-09-10's eighteen acoustic runs on the held-exchange axis, checks the replay
 * against what those runs actually converted through, and then decimates their exchanges to the
 * playback cadence to reach the point playback starts at, which no run has ever chirped in.
 */
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { replay, estimateInForceAt, decimate } from '../src/clock-replay.mjs'

const ROOT = process.argv[2] ?? 'artifacts/item16'
const ARMS = {
  'new (fraction, fill=0)': { labels: [1, 2, 3, 4, 5, 6].map(i => `new-${i}`), keep: true },
  'old (frozen,   fill=0)': { labels: [1, 2, 3, 4, 5, 6].map(i => `old-${i}`), keep: false },
  'new (fraction, fill=16s)': { labels: [1, 2, 3, 4, 5, 6].map(i => `wait-${i}`), keep: true }
}

const loadRun = label => {
  const dir = join(ROOT, label)
  const sink = JSON.parse(readFileSync(join(dir, 'X10', 'peer-calibration.json'), 'utf8'))
  const hostFile = readdirSync(join(dir, 'M6')).find(f => /^peer-calibration-[0-9a-f]+\.json$/.test(f))
  const host = JSON.parse(readFileSync(join(dir, 'M6', hostFile), 'utf8'))
  return { label, sink, host }
}

/** Alignment error as both sides together saw it; flight time cancels in the half sum. */
const pairedError = ({ sink, host }, i) =>
  (sink.pairs[i].alignmentErrorMs + host.pairs[i].alignmentErrorMs) / 2

const mean = xs => xs.reduce((a, b) => a + b, 0) / xs.length
const sd = xs => xs.length < 2 ? NaN
  : Math.sqrt(xs.reduce((a, b) => a + (b - mean(xs)) ** 2, 0) / (xs.length - 1))

// ---------------------------------------------------------------- 1. the replay against the run

console.log('== 1. 重放对得上手机自己用的偏移吗（每一发 chirp 逐个核对）==')
let checked = 0, worstNanos = 0
for (const [arm, { labels, keep }] of Object.entries(ARMS)) {
  for (const run of labels.map(loadRun)) {
    const steps = replay(run.sink.clock.exchanges, { keepFractionWhileFilling: keep })
    for (const play of run.sink.renderer.chirpPlays) {
      const standing = estimateInForceAt(steps, play.localNanos)
      if (standing === null) { console.log('  !!', run.label, 'chirp', play.repeat, '重放说这时候没有估计'); continue }
      const gap = Math.abs(standing.offsetNanos - play.offsetNanos)
      worstNanos = Math.max(worstNanos, gap)
      checked++
    }
  }
}
console.log(`  核对了 ${checked} 发，最大分歧 ${(worstNanos / 1e6).toFixed(6)} ms`)
console.log(worstNanos === 0
  ? '  重放和手机逐位相同 —— 后面的外推可以信这份工具'
  : '  !! 有分歧，后面的结论先别信')

// ------------------------------------------------- 2. the microphone, on the held-exchange axis

console.log()
console.log('== 2. 麦克风测到的偏差 vs 估计器手里攥着几个样本 ==')
const heldAt = (exchanges, localNanos) => exchanges.filter(e => e[3] <= localNanos).length

for (const [arm, { labels }] of Object.entries(ARMS)) {
  const points = []
  for (const run of labels.map(loadRun)) {
    const held = run.sink.renderer.chirpPlays.map(p => heldAt(run.sink.clock.exchanges, p.localNanos))
    const err = run.sink.pairs.map((_, i) => pairedError(run, i))
    // The run's own settled reading, from the chirps whose window was full.
    const full = err.filter((_, i) => held[i] >= 64)
    if (full.length === 0) continue
    const base = mean(full)
    held.forEach((h, i) => { if (h < 64) points.push({ h, d: err[i] - base }) })
  }
  const bins = [[8, 24], [24, 44], [44, 64]]
  const line = bins.map(([lo, hi]) => {
    const g = points.filter(p => p.h >= lo && p.h < hi).map(p => p.d)
    return g.length ? `${lo}-${hi}: ${mean(g).toFixed(2)}±${(sd(g) || 0).toFixed(2)} (n=${g.length})` : `${lo}-${hi}: -`
  }).join('   ')
  console.log(' ', arm.padEnd(24), line)
}
console.log('  （单位 ms，相对同一轮窗口填满之后那几发；样本数 64 = 窗口满）')

// ------------------------------------------------------- 3. the same link at playback's cadence

console.log()
console.log('== 3. 同一条链路，换成放歌那条路的节奏（2000 ms）==')
console.log('   参照系：同一时刻、同一条链路、窗口已经填满的那份读数（250 ms 那一路给的）。')
console.log('   它不是绝对真值，但第 2 节证明了「窗口满」对应的声学偏差就在零附近。')

const STRIDE = 8  // 250 ms recorded -> 2000 ms, the cadence SinkSession actually runs at
const rows = new Map()

for (const [arm, { labels, keep }] of Object.entries(ARMS)) {
  for (const run of labels.map(loadRun)) {
    const exchanges = run.sink.clock.exchanges
    const mature = replay(exchanges).map(s => (s.held >= 64 ? s : { ...s, estimate: null }))
    for (let phase = 0; phase < STRIDE; phase++) {
      const slow = replay(decimate(exchanges, STRIDE, phase), { keepFractionWhileFilling: keep })
      for (const step of slow) {
        if (step.estimate === null) continue
        const reference = estimateInForceAt(mature, step.t4)
        if (reference === null) continue          // the dense run has not matured yet at this instant
        const key = `${keep ? 'fraction' : 'frozen'}|${step.held}`
        if (!rows.has(key)) rows.set(key, [])
        rows.get(key).push((step.estimate.offsetNanos - reference.offsetNanos) / 1e6)
      }
    }
  }
}

console.log()
console.log('  攥着几个   走到第几秒     老规则(frozen)          新规则(fraction)')
for (const held of [8, 9, 10, 12, 16, 20, 24, 32, 48, 64]) {
  const f = rows.get(`frozen|${held}`) ?? []
  const r = rows.get(`fraction|${held}`) ?? []
  const cell = g => g.length
    ? `${mean(g).toFixed(2).padStart(6)} ± ${(sd(g) || 0).toFixed(2)}  (n=${String(g.length).padStart(3)})`
    : '        -'
  console.log(`   ${String(held).padStart(4)}      ${String(held * 2).padStart(5)} s     ${cell(f)}    ${cell(r)}`)
}
console.log('  （单位 ms。放歌那条路的第一个估计就在「攥着 8 个」那一行。）')

// ------------------------------------------------ 4. would a fast first eight be a worse first eight

console.log()
console.log('== 4. 头八次快发（250 ms）对比慢发（2000 ms），第一个估计谁更准 ==')
console.log('   参照系：同一轮窗口填满之后那份读数，按它自己报的漂移折算回被问的时刻。')

/** The mature reading projected to [atNanos] along the drift it reports, so an early estimate can
 *  be scored against it without the projection itself being the thing measured. */
const referenceAt = (mature, atNanos) => {
  const last = mature.filter(s => s.estimate !== null).pop()
  if (!last) return null
  const seconds = (atNanos - Number(last.estimate.anchorT1)) / 1e9
  return last.estimate.offsetNanos + last.estimate.driftPpm * seconds * 1e3
}

const fast = { frozen: [], fraction: [] }
const slow = { frozen: [], fraction: [] }

for (const [, { labels }] of Object.entries(ARMS)) {
  for (const run of labels.map(loadRun)) {
    const exchanges = run.sink.clock.exchanges
    const mature = replay(exchanges).map(s => (s.held >= 64 ? s : { ...s, estimate: null }))
    for (const keep of [true, false]) {
      const bucket = keep ? 'fraction' : 'frozen'
      // 250 ms as recorded: the first eight span two seconds.
      const dense = replay(exchanges, { keepFractionWhileFilling: keep }).find(s => s.held === 8 && s.estimate)
      if (dense) {
        const ref = referenceAt(mature, dense.t4)
        if (ref !== null) fast[bucket].push((dense.estimate.offsetNanos - ref) / 1e6)
      }
      // 2000 ms: the first eight span sixteen.
      for (let phase = 0; phase < STRIDE; phase++) {
        const step = replay(decimate(exchanges, STRIDE, phase), { keepFractionWhileFilling: keep })
          .find(s => s.held === 8 && s.estimate)
        if (!step) continue
        const ref = referenceAt(mature, step.t4)
        if (ref !== null) slow[bucket].push((step.estimate.offsetNanos - ref) / 1e6)
      }
    }
  }
}

const cell = g => g.length
  ? `${mean(g).toFixed(2).padStart(6)} ± ${(sd(g) || 0).toFixed(2)}   最大 ${Math.max(...g.map(Math.abs)).toFixed(2).padStart(5)}  (n=${g.length})`
  : '   -'
console.log()
console.log('   第一个估计（攥着 8 个）离参照系多远，ms：')
console.log('                        老规则(frozen)                        新规则(fraction)')
console.log('   头八次快发 250 ms  ', cell(fast.frozen), '  ', cell(fast.fraction))
console.log('   头八次慢发 2000 ms ', cell(slow.frozen), '  ', cell(slow.fraction))
console.log()
console.log('   快发到第一个估计要 2 秒，慢发要 14 秒。')
