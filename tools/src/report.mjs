const PRIVATE_KEYS = new Set(['serial', 'buildFingerprint', 'rawTrackUrl', 'trackUrl', 'token', 'credentials', 'screenshot']);
export function redactOutcome(outcome) { return Object.freeze(Object.fromEntries(Object.entries(outcome).filter(([key]) => !PRIVATE_KEYS.has(key)))); }
export function renderSummary(outcome) { const safe = redactOutcome(outcome); return `# Capture case ${safe.caseId}\n\n- App: ${safe.appId}\n- Outcome: ${safe.outcome}\n${safe.peakDbfs === undefined ? '' : `- Peak: ${safe.peakDbfs} dBFS\n`}`; }
