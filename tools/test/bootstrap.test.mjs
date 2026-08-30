import test from 'node:test';
import assert from 'node:assert/strict';

test('Node runtime is new enough for built-in fetch', () => {
  assert.ok(Number.parseInt(process.versions.node.split('.')[0], 10) >= 24);
  assert.equal(typeof fetch, 'function');
});
