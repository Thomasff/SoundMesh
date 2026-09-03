import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { assertCaseDirectoryIsFree, firstFreeCaseId, storedCaseIds } from '../src/case-directory.mjs';

test('refuses a case ID that is already stored, and names one that is free', () => {
  // artifacts/ is outside version control: a run writing into a used case ID replaces that batch
  // in place with nothing to restore from. It has happened - a run launched as P1 destroyed the
  // recording, reports and alignment of an earlier P1.
  assert.throws(
    () => assertCaseDirectoryIsFree({ caseId: 'P1', taken: ['P1', 'P2', 'P3'], overwrite: false }),
    error => /P1 already has stored artifacts/.test(error.message) && /P4/.test(error.message)
  );
  assert.doesNotThrow(() => assertCaseDirectoryIsFree({ caseId: 'P4', taken: ['P1', 'P2', 'P3'], overwrite: false }));
  // Replacing a batch stays possible, but as someone's stated decision rather than an accident.
  assert.doesNotThrow(() => assertCaseDirectoryIsFree({ caseId: 'P1', taken: ['P1'], overwrite: true }));
});

test('the free ID it names carries on the same series rather than jumping letters', () => {
  assert.equal(firstFreeCaseId('P1', ['P1', 'P2', 'P3']), 'P4');
  // Gaps are filled: a series that lost a run should not leave the hole open to be reused blindly,
  // but a genuinely unused number is the least surprising suggestion.
  assert.equal(firstFreeCaseId('R1', ['R1', 'R2', 'R4']), 'R3');
  assert.equal(firstFreeCaseId('Z9', []), 'Z1');
  // Double digits are already in use (R10, R11), so counting must not stop at nine.
  assert.equal(firstFreeCaseId('R1', Array.from({ length: 11 }, (_, index) => `R${index + 1}`)), 'R12');
});

test('storedCaseIds lists what a family directory already holds, and is empty when it does not exist', async () => {
  const base = await mkdtemp(resolve(tmpdir(), 'soundmesh-cases-'));
  assert.deepEqual(await storedCaseIds(resolve(base, 'never-created')), []);

  await mkdir(resolve(base, 'A1'), { recursive: true });
  await mkdir(resolve(base, 'A2'), { recursive: true });
  // A stray file is not a case; only directories hold a batch.
  await writeFile(resolve(base, 'README.txt'), 'notes', 'utf8');

  assert.deepEqual((await storedCaseIds(base)).sort(), ['A1', 'A2']);
});
