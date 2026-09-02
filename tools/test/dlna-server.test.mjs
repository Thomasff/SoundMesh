import assert from 'node:assert/strict';
import test from 'node:test';
import { probeMedia } from '../src/dlna/server.mjs';
import { matchingTargets, searchTargetOf } from '../src/dlna/ssdp.mjs';

const headers = map => new Headers(map);

test('follows redirects, counts them, and discards the media bytes', async () => {
  const seen = [];
  const fetchImpl = async target => {
    seen.push(target);
    if (seen.length === 1) return { status: 302, headers: headers({ location: 'https://cdn.example/real.mp3' }), body: null };
    return {
      status: 200,
      headers: headers({ 'content-type': 'audio/mpeg', authorization: 'Bearer secret' }),
      body: (async function* () { yield Buffer.alloc(1024); yield Buffer.alloc(2048); })()
    };
  };
  const result = await probeMedia('https://music.example/song.mp3?token=abc', { fetchImpl });
  assert.equal(result.status, 200);
  assert.equal(result.redirects, 1);
  assert.equal(result.bytesRead, 3072);
  assert.equal(result.contentType, 'audio/mpeg');
  assert.deepEqual(result.headerNames, ['authorization', 'content-type']);
  assert.equal(JSON.stringify(result).includes('secret'), false);
  assert.equal(JSON.stringify(result).includes('music.example'), false);
});

test('classifies an aborted probe as a timeout instead of a readable resource', async () => {
  const fetchImpl = async (_target, { signal }) => {
    const error = new Error('aborted');
    error.name = 'AbortError';
    if (signal.aborted) throw error;
    return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(error)));
  };
  const result = await probeMedia('https://music.example/song.mp3', { fetchImpl, timeoutMs: 20 });
  assert.equal(result.failureClass, 'TIMEOUT');
  assert.equal(result.bytesRead, 0);
});

test('answers only the search targets this renderer advertises', () => {
  const uuid = '12345678-1234-1234-1234-1234567890ab';
  assert.equal(searchTargetOf('M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n'), 'ssdp:all');
  assert.equal(searchTargetOf('NOTIFY * HTTP/1.1\r\n\r\n'), null);
  assert.equal(matchingTargets(uuid, 'ssdp:all').length, 6);
  assert.deepEqual(matchingTargets(uuid, 'urn:schemas-upnp-org:device:MediaRenderer:1').map(t => t.usn), [`uuid:${uuid}::urn:schemas-upnp-org:device:MediaRenderer:1`]);
  assert.deepEqual(matchingTargets(uuid, 'urn:schemas-upnp-org:device:MediaServer:1'), []);
});

test('refuses an ambiguous interface choice and honours an explicit address', async () => {
  const { selectInterface } = await import('../src/cli/run-dlna.mjs');
  const two = [{ name: 'wifi', address: '192.168.1.5' }, { name: 'vpn', address: '10.8.0.2' }];
  assert.throws(() => selectInterface(two), /explicit --address/);
  assert.deepEqual(selectInterface(two, '10.8.0.2'), two[1]);
  assert.throws(() => selectInterface(two, '203.0.113.1'), /not a private IPv4 interface/);
  assert.throws(() => selectInterface([]), /No private IPv4 interface/);
  assert.deepEqual(selectInterface([two[0]]), two[0]);
});
