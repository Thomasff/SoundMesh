import { readdir } from 'node:fs/promises';

/**
 * Guards the one irreversible thing this harness does to its own results: writing over them.
 *
 * Every CLI here stores a run under a case ID the caller picks, in `artifacts/`, which is
 * deliberately outside version control - the recordings run to tens of megabytes. Those two facts
 * together mean a run launched with a case ID some earlier batch already used replaces it in
 * place, with nothing to restore from. It has happened: a run launched as P1, by someone who had
 * checked the free letters and then not used one, destroyed all four files of a P1 batch recorded
 * the day before.
 *
 * The guard is not "ask before overwriting" but "refuse unless overwriting was the request", and
 * it belongs before any device is touched, so a clash costs a second rather than a whole run.
 */

/** The case IDs already stored under [directory]; empty when it does not exist yet. */
export async function storedCaseIds(directory) {
  const entries = await readdir(directory, { withFileTypes: true }).catch(() => []);
  return entries.filter(entry => entry.isDirectory()).map(entry => entry.name);
}

/**
 * The lowest unused number in [caseId]'s own letter series.
 *
 * Same letter on purpose: the letter groups a batch, so a clash should be resolved by carrying the
 * series on, not by jumping to whatever letter happens to be free. Counting is not capped at nine -
 * R10 and R11 already exist.
 */
export function firstFreeCaseId(caseId, taken) {
  const letter = caseId.slice(0, 1);
  const used = new Set(taken);
  for (let number = 1; ; number++) {
    const candidate = `${letter}${number}`;
    if (!used.has(candidate)) return candidate;
  }
}

/**
 * Refuses [caseId] when [taken] already holds it, naming a free one so the fix is mechanical.
 *
 * Naming the free ID matters more than it looks: the refusal arrives at the moment someone is
 * about to start a run, and a message that only says no invites a second guess at a letter, which
 * is exactly how the first accident happened.
 */
export function assertCaseDirectoryIsFree({ caseId, taken, overwrite }) {
  if (!taken.includes(caseId) || overwrite) return;
  throw new Error(
    `${caseId} already has stored artifacts, and artifacts/ is not in version control - writing here would destroy that batch for good. ` +
    `Use ${firstFreeCaseId(caseId, taken)}, or pass --overwrite if replacing it is what you mean.`
  );
}
