import assert from 'node:assert/strict';
import test from 'node:test';
import { analyzeCapture, parseWav } from '../src/capture-analysis.mjs';

function wav(samples, rate = 48_000, channels = 2) {
  const data = Buffer.alloc(samples.length * 2); samples.forEach((sample, i) => data.writeInt16LE(sample, i * 2));
  const output = Buffer.alloc(44 + data.length); output.write('RIFF'); output.writeUInt32LE(36 + data.length, 4); output.write('WAVEfmt ', 8); output.writeUInt32LE(16, 16); output.writeUInt16LE(1, 20); output.writeUInt16LE(channels, 22); output.writeUInt32LE(rate, 24); output.writeUInt32LE(rate * channels * 2, 28); output.writeUInt16LE(channels * 2, 32); output.writeUInt16LE(16, 34); output.write('data', 36); output.writeUInt32LE(data.length, 40); data.copy(output, 44); return output;
}

test('parses PCM16 WAV frame and signal metrics independently', () => {
  const file = wav([0, 0, 16_384, -16_384, 0, 0]);
  const parsed = parseWav(file);
  assert.deepEqual(parsed.format, { sampleRate: 48_000, channelCount: 2, bitsPerSample: 16 });
  assert.equal(parsed.frameCount, 3); assert.equal(parsed.nonZeroRatio, 2 / 6); assert.equal(parsed.peakAbsolute, 16_384); assert.equal(parsed.rmsLinear, Math.sqrt(1 / 12)); assert.equal(parsed.longestZeroWindowFrames, 1);
});

test('rejects corrupt and truncated WAV files', () => {
  assert.throws(() => parseWav(Buffer.from('not a wav')), /RIFF/);
  const file = wav([1, 2]); file.writeUInt32LE(400, 40);
  assert.throws(() => parseWav(file), /truncated/);
});

test('fails an audible source that is digital silence and detects mismatched result bytes', () => {
  const silent = wav(new Array(48_000 * 2).fill(0));
  const result = analyzeCapture({ wav: silent, capture: { expectedBytes: silent.length - 44, capturedBytes: 1, actualFormat: { sampleRate: 48_000, channelCount: 2, encoding: 'PCM16' } }, audibleSource: true });
  assert.equal(result.outcome, 'FAIL_DIGITAL_SILENCE');
  assert.ok(result.reasons.includes('CAPTURE_BYTES_MISMATCH'));
});

test('passes a complete audible PCM16 capture at the exact format', () => {
  const file = wav(new Array(48_000 * 2).fill(4_096));
  const result = analyzeCapture({ wav: file, capture: { expectedBytes: file.length - 44, capturedBytes: file.length - 44, actualFormat: { sampleRate: 48_000, channelCount: 2, encoding: 'PCM16' } }, audibleSource: true });
  assert.equal(result.outcome, 'PASS');
  assert.deepEqual(result.reasons, []);
});
