/**
 * Is a fast first eight a worse first eight?
 *
 * SinkSession exchanges every 2000 ms and answers nothing until MIN_SAMPLES, so a joining handset
 * is silent for fourteen seconds. Bursting the first eight would cut that to two - if the estimate
 * those eight support is good enough to play on. They span two seconds instead of sixteen, which is
 * both the saving and the risk: less of the link's own variation is averaged into them.
 *
 * The reference is a least squares line through that run's own mature readings rather than the
 * driftPpm one eight point fit reports. driftPpm is not crystal drift - the same pair has swung it
 * +113 to -203 inside ten minutes - so projecting a single fit back thirty seconds injects several
 * milliseconds of the thing being measured. A line over thirty-four seconds of mature readings does
 * not, and its residual is printed so the reference can be judged rather than assumed.
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { replay, decimate } from '../src/clock-replay.mjs'

const ROOT = process.argv[2] ?? 'artifacts/item16'
const LABELS = ['new', 'old', 'wait'].flatMap(a => [1, 2, 3, 4, 5, 6].map(i => `${a}-${i}`))
const STRIDE = 8

const mean = x => x.reduce((a, b) => a + b, 0) / x.length
const sd = x => x.length < 2 ? NaN : Math.sqrt(x.reduce((a, b) => a + (b - mean(x)) ** 2, 0) / (x.length - 1))

const exchangesOf = label =>
  JSON.parse(readFileSync(join(ROOT, label, 'X10', 'peer-calibration.json'), 'utf8')).clock.exchanges

const referenceLine = mature => {
  const pts = mature.filter(s => s.estimate).map(s => ({ x: s.t4 / 1e9, y: s.estimate.offsetNanos }))
  if (pts.length < 20) return null
  const mx = mean(pts.map(p => p.x)), my = mean(pts.map(p => p.y))
  const slope = pts.reduce((a, p) => a + (p.x - mx) * (p.y - my), 0) / pts.reduce((a, p) => a + (p.x - mx) ** 2, 0)
  const resid = pts.map(p => p.y - (my + slope * (p.x - mx)))
  return {
    at: t => my + slope * (t / 1e9 - mx),
    residMs: sd(resid) / 1e6,
    driftPpm: slope / 1e3,
    spanS: Math.max(...pts.map(p => p.x)) - Math.min(...pts.map(p => p.x))
  }
}

const out = { fast: { frozen: [], fraction: [] }, slow: { frozen: [], fraction: [] } }
const residuals = [], drifts = [], spans = []

for (const label of LABELS) {
  const ex = exchangesOf(label)
  const mature = replay(ex).map(s => (s.held >= 64 ? s : { ...s, estimate: null }))
  const line = referenceLine(mature)
  if (!line) { console.log('  跳过', label, '（成熟读数不足 20 个）'); continue }
  residuals.push(line.residMs); drifts.push(line.driftPpm); spans.push(line.spanS)

  for (const keep of [true, false]) {
    const bucket = keep ? 'fraction' : 'frozen'
    const dense = replay(ex, { keepFractionWhileFilling: keep }).find(s => s.held === 8 && s.estimate)
    if (dense) out.fast[bucket].push((dense.estimate.offsetNanos - line.at(dense.t4)) / 1e6)
    for (let phase = 0; phase < STRIDE; phase++) {
      const step = replay(decimate(ex, STRIDE, phase), { keepFractionWhileFilling: keep })
        .find(s => s.held === 8 && s.estimate)
      if (step) out.slow[bucket].push((step.estimate.offsetNanos - line.at(step.t4)) / 1e6)
    }
  }
}

const med = x => [...x].sort((a, b) => a - b)[Math.floor(x.length / 2)]
console.log('参照系自身的质量：成熟读数对直线的残差 sd 中位 ' + med(residuals).toFixed(3) +
  ' ms，跨度中位 ' + med(spans).toFixed(0) + ' s')
console.log('各轮拟合出的漂移：' + Math.min(...drifts).toFixed(0) + ' ~ ' + Math.max(...drifts).toFixed(0) + ' ppm')
console.log()
const cell = g => g.length
  ? `${mean(g).toFixed(2).padStart(6)} ± ${(sd(g) || 0).toFixed(2)}  最大|·| ${Math.max(...g.map(Math.abs)).toFixed(2).padStart(5)}  (n=${String(g.length).padStart(3)})`
  : '   —  拟合全被漂移闸门拒了'
console.log('第一个估计（攥着 8 个）离参照直线多远，ms：')
console.log('                       老规则(frozen)                        新规则(fraction)')
console.log('  头八次快发 250 ms  ', cell(out.fast.frozen), ' ', cell(out.fast.fraction))
console.log('  头八次慢发 2000 ms ', cell(out.slow.frozen), ' ', cell(out.slow.fraction))
console.log()
console.log('  快发到第一个估计约 2 秒；慢发 14 秒。')

// ------------------------------------------- how good is it if the burst simply runs a bit longer

console.log()
console.log('== 全程快发 250 ms，等到攥着 N 个再开播 ==')
console.log('   N     等多久     离参照直线 (ms)')
const HELD = [8, 12, 16, 24, 32, 48]
const byHeld = new Map(HELD.map(h => [h, []]))
for (const label of LABELS) {
  const ex = exchangesOf(label)
  const line = referenceLine(replay(ex).map(s => (s.held >= 64 ? s : { ...s, estimate: null })))
  if (!line) continue
  const steps = replay(ex)
  for (const h of HELD) {
    const step = steps.find(s => s.held === h && s.estimate)
    if (step) byHeld.get(h).push((step.estimate.offsetNanos - line.at(step.t4)) / 1e6)
  }
}
for (const h of HELD) {
  const g = byHeld.get(h)
  console.log('  ' + String(h).padStart(3) + '   ' + `${(h * 0.25).toFixed(1)} s`.padStart(7) + '    ' + cell(g))
}
console.log()
console.log('  对照，今天出厂的样子（2000 ms、N=8、等 14 秒）：' + cell(out.slow.fraction))
