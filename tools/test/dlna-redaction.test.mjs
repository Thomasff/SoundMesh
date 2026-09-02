import assert from 'node:assert/strict';
import test from 'node:test';
import { redactHeaderNames, redactMediaUri } from '../src/dlna/redaction.mjs';

test('redacts signed query values while preserving diagnostic structure', () => {
  const result = redactMediaUri('https://music.example/song.m4a?id=42&token=secret&signature=abc');
  assert.equal(result.displayUri, 'https://music.example/song.m4a?id=42&token=REDACTED&signature=REDACTED');
  assert.equal(result.host, 'music.example');
  assert.equal(result.pathExtension, '.m4a');
  assert.equal(result.scheme, 'https:');
  assert.match(result.sha256, /^[a-f0-9]{64}$/);
});

test('redacts every sensitive key name case-insensitively', () => {
  const uri = 'https://h/x.mp3?Token=a&AUTH=b&authorization=c&Signature=d&sign=e&key=f&cookie=g&session=h&credential=i&bitrate=320';
  const { displayUri } = redactMediaUri(uri);
  assert.equal((displayUri.match(/REDACTED/g) || []).length, 9);
  assert.match(displayUri, /bitrate=320/);
});

test('keeps an unparseable or opaque uri out of the display value', () => {
  const opaque = redactMediaUri('not a uri at all');
  assert.equal(opaque.displayUri, 'UNPARSEABLE_URI');
  assert.equal(opaque.host, null);
  assert.match(opaque.sha256, /^[a-f0-9]{64}$/);
});

test('reports header names only and never the values of secret headers', () => {
  const names = redactHeaderNames({ 'Content-Type': 'audio/mpeg', Authorization: 'Bearer x', Cookie: 'a=b', 'Set-Cookie': 'c=d' });
  assert.deepEqual(names, ['authorization', 'content-type', 'cookie', 'set-cookie']);
  assert.equal(JSON.stringify(names).includes('Bearer'), false);
});
