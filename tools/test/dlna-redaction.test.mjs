import assert from 'node:assert/strict';
import test from 'node:test';
import { redactHeaderNames, redactMediaUri } from '../src/dlna/redaction.mjs';

test('keeps only known harmless parameters and redacts everything else', () => {
  const result = redactMediaUri('https://music.example/song.m4a?id=42&token=secret&signature=abc&bitrate=320&dlna=1');
  assert.equal(result.displayUri, 'https://music.example/song.m4a?id=REDACTED&token=REDACTED&signature=REDACTED&bitrate=320&dlna=1');
  assert.equal(result.host, 'music.example');
  assert.equal(result.pathExtension, '.m4a');
  assert.equal(result.scheme, 'https:');
  assert.match(result.sha256, /^[a-f0-9]{64}$/);
});

test('redacts vendor token names no deny-list would have predicted', () => {
  const uri = 'http://m801.music.126.net/x/a.mp3?vuutv=Zm9vYmFyKw==&dlna=1';
  const { displayUri } = redactMediaUri(uri);
  assert.equal(displayUri.includes('Zm9vYmFy'), false);
  assert.match(displayUri, /vuutv=REDACTED/);
  assert.match(displayUri, /dlna=1/);
});

test('redacts the account and device identifiers a cast uri carries', () => {
  const uri = 'http://ws.stream.example.com/track.mp3?guid=abc123&vkey=secret&uin=1234567890&src=other.mp3&redirect=1&fromtag=111042';
  const { displayUri } = redactMediaUri(uri);
  for (const leaked of ['1234567890', 'abc123', 'secret', 'other.mp3']) {
    assert.equal(displayUri.includes(leaked), false, `${leaked} must not survive redaction`);
  }
  assert.match(displayUri, /fromtag=111042/);
  assert.match(displayUri, /redirect=1/);
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
