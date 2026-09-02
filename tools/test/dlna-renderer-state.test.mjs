import assert from 'node:assert/strict';
import test from 'node:test';
import { createRendererState } from '../src/dlna/renderer-state.mjs';

const uri = 'https://music.example/song.m4a?token=secret';

test('rejects an unknown action with upnp error 401', () => {
  const state = createRendererState();
  assert.deepEqual(state.invoke('AVTransport', 'Teleport', {}), { error: 401, description: 'Invalid Action' });
  assert.deepEqual(state.invoke('Nonexistent', 'Play', {}), { error: 401, description: 'Invalid Action' });
});

test('refuses to play before a transport uri has been set with upnp error 701', () => {
  const state = createRendererState();
  assert.deepEqual(state.invoke('AVTransport', 'Play', {}), { error: 701, description: 'Transition not available' });
  assert.equal(state.snapshot().transportState, 'NO_MEDIA_PRESENT');
});

test('drives the documented transport transitions', () => {
  const state = createRendererState();
  assert.deepEqual(state.invoke('AVTransport', 'SetAVTransportURI', { CurrentURI: uri }), {});
  assert.equal(state.snapshot().transportState, 'STOPPED');
  assert.deepEqual(state.invoke('AVTransport', 'Play', {}), {});
  assert.equal(state.snapshot().transportState, 'PLAYING');
  assert.deepEqual(state.invoke('AVTransport', 'Pause', {}), {});
  assert.equal(state.snapshot().transportState, 'PAUSED_PLAYBACK');
  assert.deepEqual(state.invoke('AVTransport', 'Play', {}), {});
  assert.deepEqual(state.invoke('AVTransport', 'Stop', {}), {});
  assert.equal(state.snapshot().transportState, 'STOPPED');
  assert.deepEqual(state.invoke('AVTransport', 'GetTransportInfo', {}), { CurrentTransportState: 'STOPPED', CurrentTransportStatus: 'OK', CurrentSpeed: '1' });
});

test('keeps the raw uri out of the snapshot and never claims a successful decode', () => {
  const state = createRendererState();
  state.invoke('AVTransport', 'SetAVTransportURI', { CurrentURI: uri });
  const snapshot = state.snapshot();
  assert.equal(JSON.stringify(snapshot).includes('secret'), false);
  assert.equal(snapshot.media.host, 'music.example');
  assert.equal(snapshot.mediaReadable, 'NOT_PROBED');
  assert.equal(state.currentUri(), uri, 'the raw uri stays reachable for the private log only');
});

test('answers rendering control and connection manager queries', () => {
  const state = createRendererState();
  assert.deepEqual(state.invoke('RenderingControl', 'GetVolume', {}), { CurrentVolume: '50' });
  assert.deepEqual(state.invoke('RenderingControl', 'SetVolume', { DesiredVolume: '30' }), {});
  assert.deepEqual(state.invoke('RenderingControl', 'GetVolume', {}), { CurrentVolume: '30' });
  assert.deepEqual(state.invoke('RenderingControl', 'SetMute', { DesiredMute: '1' }), {});
  assert.deepEqual(state.invoke('RenderingControl', 'GetMute', {}), { CurrentMute: '1' });
  assert.match(state.invoke('ConnectionManager', 'GetProtocolInfo', {}).Sink, /audio\/mpeg/);
  assert.deepEqual(state.invoke('ConnectionManager', 'GetCurrentConnectionIDs', {}), { ConnectionIDs: '0' });
});

test('records a media probe result without asserting playability', () => {
  const state = createRendererState();
  state.invoke('AVTransport', 'SetAVTransportURI', { CurrentURI: uri });
  state.recordProbe({ status: 403, contentType: null, bytesRead: 0, redirects: 1, elapsedMs: 120 });
  const snapshot = state.snapshot();
  assert.equal(snapshot.mediaReadable, 'UNREADABLE');
  assert.equal(snapshot.probe.status, 403);

  const readable = createRendererState();
  readable.invoke('AVTransport', 'SetAVTransportURI', { CurrentURI: uri });
  readable.recordProbe({ status: 200, contentType: 'audio/mpeg', bytesRead: 1_000_000, redirects: 0, elapsedMs: 60_000 });
  assert.equal(readable.snapshot().mediaReadable, 'READABLE');
});
