const PCM16_SCALE = 32768;
const dbfs = value => value === 0 ? null : 20 * Math.log10(value);

export function parseWav(wav) {
  const data = Buffer.from(wav);
  if (data.length < 44 || data.toString('ascii', 0, 4) !== 'RIFF' || data.toString('ascii', 8, 12) !== 'WAVE') throw new Error('Invalid RIFF/WAVE header');
  if (data.readUInt32LE(4) + 8 > data.length) throw new Error('WAV file is truncated');
  let offset = 12; let format; let pcm;
  while (offset + 8 <= data.length) {
    const id = data.toString('ascii', offset, offset + 4); const length = data.readUInt32LE(offset + 4); const start = offset + 8; const end = start + length;
    if (end > data.length) throw new Error('WAV chunk is truncated');
    if (id === 'fmt ') format = { audioFormat: data.readUInt16LE(start), channelCount: data.readUInt16LE(start + 2), sampleRate: data.readUInt32LE(start + 4), bitsPerSample: data.readUInt16LE(start + 14) };
    if (id === 'data') pcm = data.subarray(start, end);
    offset = end + (length % 2);
  }
  if (!format || !pcm || format.audioFormat !== 1 || format.bitsPerSample !== 16 || format.channelCount < 1) throw new Error('Unsupported PCM16 WAV format');
  const bytesPerFrame = format.channelCount * 2;
  if (pcm.length % bytesPerFrame) throw new Error('WAV data ends with incomplete frame');
  let nonZero = 0; let peakAbsolute = 0; let sumSquares = 0; let longest = 0; let current = 0;
  const sampleValues = [];
  for (let offset = 0; offset < pcm.length; offset += 2) { const sample = pcm.readInt16LE(offset); sampleValues.push(sample); const magnitude = Math.abs(sample); if (magnitude) { nonZero++; peakAbsolute = Math.max(peakAbsolute, magnitude); sumSquares += (sample / PCM16_SCALE) ** 2; } }
  for (let frame = 0; frame < pcm.length / bytesPerFrame; frame++) { const allZero = sampleValues.slice(frame * format.channelCount, (frame + 1) * format.channelCount).every(sample => sample === 0); current = allZero ? current + 1 : 0; longest = Math.max(longest, current); }
  const samples = pcm.length / 2;
  let maxOneSecondPeak = 0; let maxOneSecondRms = 0;
  const windowSamples = format.sampleRate * format.channelCount;
  for (let start = 0; start + windowSamples <= samples; start += windowSamples) { let windowPeak = 0; let windowSquares = 0; for (let i = start; i < start + windowSamples; i++) { const linear = sampleValues[i] / PCM16_SCALE; windowPeak = Math.max(windowPeak, Math.abs(sampleValues[i])); windowSquares += linear ** 2; } maxOneSecondPeak = Math.max(maxOneSecondPeak, windowPeak); maxOneSecondRms = Math.max(maxOneSecondRms, Math.sqrt(windowSquares / windowSamples)); }
  return Object.freeze({ format: Object.freeze({ sampleRate: format.sampleRate, channelCount: format.channelCount, bitsPerSample: 16 }), dataBytes: pcm.length, frameCount: pcm.length / bytesPerFrame, nonZeroRatio: samples ? nonZero / samples : 0, peakAbsolute, peakDbfs: dbfs(peakAbsolute / PCM16_SCALE), rmsLinear: samples ? Math.sqrt(sumSquares / samples) : 0, rmsDbfs: dbfs(samples ? Math.sqrt(sumSquares / samples) : 0), maxOneSecondPeakDbfs: dbfs(maxOneSecondPeak / PCM16_SCALE), maxOneSecondRmsDbfs: dbfs(maxOneSecondRms), longestZeroWindowFrames: longest });
}

export function analyzeCapture({ wav, capture, audibleSource = false }) {
  const metrics = parseWav(wav); const expectedBytes = capture.expectedBytes; const reasons = [];
  if (!Number.isFinite(expectedBytes) || metrics.dataBytes < expectedBytes * 0.98) reasons.push('INSUFFICIENT_CAPTURE_BYTES');
  if (capture.capturedBytes !== metrics.dataBytes) reasons.push('CAPTURE_BYTES_MISMATCH');
  const actual = capture.actualFormat;
  if (!actual || actual.sampleRate !== metrics.format.sampleRate || actual.channelCount !== metrics.format.channelCount || actual.encoding !== 'PCM16') reasons.push('CAPTURE_FORMAT_MISMATCH');
  if (metrics.nonZeroRatio <= 0.01) reasons.push('LOW_NONZERO_RATIO');
  const oneSecondFrames = metrics.format.sampleRate;
  if (metrics.frameCount < oneSecondFrames || metrics.maxOneSecondPeakDbfs === null || metrics.maxOneSecondPeakDbfs <= -50) reasons.push('LOW_PEAK');
  if (metrics.frameCount < oneSecondFrames || metrics.maxOneSecondRmsDbfs === null || metrics.maxOneSecondRmsDbfs <= -60) reasons.push('LOW_RMS');
  if (metrics.longestZeroWindowFrames > metrics.format.sampleRate * 0.5) reasons.push('DIGITAL_SILENCE_WINDOW');
  const thresholdFailure = reasons.some(reason => ['LOW_NONZERO_RATIO', 'LOW_PEAK', 'LOW_RMS', 'DIGITAL_SILENCE_WINDOW'].includes(reason));
  const outcome = reasons.length === 0 ? 'PASS' : audibleSource && thresholdFailure ? 'FAIL_DIGITAL_SILENCE' : 'PARTIAL';
  return Object.freeze({ outcome, reasons: Object.freeze(reasons), ...metrics });
}
