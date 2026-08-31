const PRIVATE_KEY = /serial|token|auth|signature|sign|cookie|session|credential|private|raw|trackurl|screenshot/i;
function redact(value) {
  if (Array.isArray(value)) return Object.freeze(value.map(redact));
  if (!value || typeof value !== 'object') return value;
  return Object.freeze(Object.fromEntries(Object.entries(value).filter(([key]) => !PRIVATE_KEY.test(key)).map(([key, child]) => [key, redact(child)])));
}
export function redactOutcome(outcome) { return redact(outcome); }
export function renderSummary(outcome) {
  const safe = redactOutcome(outcome); const lines = [`# Capture case ${safe.caseId}`, '', `- App: ${safe.appId}`, `- Outcome: ${safe.outcome}`];
  if (safe.format) lines.push(`- Format: ${safe.format.sampleRate} Hz, ${safe.format.channelCount} channels, ${safe.format.bitsPerSample}-bit PCM`);
  for (const [label, key, unit = ''] of [['Expected bytes', 'expectedBytes'], ['Captured bytes', 'capturedBytes'], ['Nonzero ratio', 'nonZeroRatio'], ['Peak (one second)', 'maxOneSecondPeakDbfs', ' dBFS'], ['RMS (one second)', 'maxOneSecondRmsDbfs', ' dBFS'], ['Longest digital silence', 'longestZeroWindowFrames', ' frames']]) if (safe[key] !== undefined) lines.push(`- ${label}: ${safe[key]}${unit}`);
  if (safe.reasons?.length) lines.push(`- Reasons: ${safe.reasons.join(', ')}`);
  return `${lines.join('\n')}\n`;
}
