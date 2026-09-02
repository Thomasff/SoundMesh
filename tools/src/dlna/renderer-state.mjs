import { redactMediaUri } from './redaction.mjs';

const INVALID_ACTION = Object.freeze({ error: 401, description: 'Invalid Action' });
const INVALID_TRANSITION = Object.freeze({ error: 701, description: 'Transition not available' });
const OK = Object.freeze({});

const PROTOCOL_INFO = 'http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,http-get:*:audio/x-flac:*,http-get:*:audio/wav:*';

/**
 * Deterministic MediaRenderer state. It records what a control point asked for and
 * never claims the media decoded; readability is a separate, probed fact.
 */
export function createRendererState() {
  let transportState = 'NO_MEDIA_PRESENT';
  let rawUri = null;
  let media = null;
  let metadata = null;
  let volume = '50';
  let mute = '0';
  let probe = null;
  let positionSeconds = 0;

  const avTransport = {
    SetAVTransportURI: args => {
      const uri = args.CurrentURI;
      if (!uri) return INVALID_TRANSITION;
      rawUri = uri;
      media = redactMediaUri(uri);
      metadata = args.CurrentURIMetaData ? { length: String(args.CurrentURIMetaData).length } : null;
      probe = null;
      positionSeconds = 0;
      transportState = 'STOPPED';
      return OK;
    },
    Play: () => {
      if (transportState !== 'STOPPED' && transportState !== 'PAUSED_PLAYBACK' && transportState !== 'PLAYING') return INVALID_TRANSITION;
      transportState = 'PLAYING';
      return OK;
    },
    Pause: () => {
      if (transportState !== 'PLAYING') return INVALID_TRANSITION;
      transportState = 'PAUSED_PLAYBACK';
      return OK;
    },
    Stop: () => {
      if (transportState === 'NO_MEDIA_PRESENT') return INVALID_TRANSITION;
      transportState = 'STOPPED';
      positionSeconds = 0;
      return OK;
    },
    Seek: args => {
      if (transportState === 'NO_MEDIA_PRESENT') return INVALID_TRANSITION;
      positionSeconds = parseTarget(args.Target);
      return OK;
    },
    GetTransportInfo: () => ({ CurrentTransportState: transportState, CurrentTransportStatus: 'OK', CurrentSpeed: '1' }),
    GetPositionInfo: () => ({ Track: media ? '1' : '0', TrackDuration: '0:00:00', RelTime: formatTime(positionSeconds), AbsTime: formatTime(positionSeconds), TrackURI: rawUri ?? '' }),
    GetMediaInfo: () => ({ NrTracks: media ? '1' : '0', MediaDuration: '0:00:00', CurrentURI: rawUri ?? '', PlayMedium: 'NETWORK' })
  };

  const renderingControl = {
    GetVolume: () => ({ CurrentVolume: volume }),
    SetVolume: args => { volume = String(args.DesiredVolume ?? volume); return OK; },
    GetMute: () => ({ CurrentMute: mute }),
    SetMute: args => { mute = String(args.DesiredMute ?? mute); return OK; }
  };

  const connectionManager = {
    GetProtocolInfo: () => ({ Source: '', Sink: PROTOCOL_INFO }),
    GetCurrentConnectionIDs: () => ({ ConnectionIDs: '0' }),
    GetCurrentConnectionInfo: () => ({ RcsID: '0', AVTransportID: '0', ProtocolInfo: PROTOCOL_INFO, PeerConnectionManager: '', PeerConnectionID: '-1', Direction: 'Input', Status: 'OK' })
  };

  const services = { AVTransport: avTransport, RenderingControl: renderingControl, ConnectionManager: connectionManager };

  return Object.freeze({
    invoke(service, action, args = {}) {
      const handler = services[service]?.[action];
      if (!handler) return INVALID_ACTION;
      return handler(args);
    },
    recordProbe(result) {
      probe = Object.freeze({ ...result });
    },
    currentUri: () => rawUri,
    snapshot: () => Object.freeze({
      transportState,
      media,
      metadata,
      volume,
      mute,
      probe,
      mediaReadable: readability(probe)
    })
  });
}

function readability(probe) {
  if (!probe) return 'NOT_PROBED';
  return probe.status >= 200 && probe.status < 300 && probe.bytesRead > 0 ? 'READABLE' : 'UNREADABLE';
}

function parseTarget(target) {
  const parts = String(target ?? '0').split(':').map(Number);
  if (parts.length === 3 && parts.every(Number.isFinite)) return parts[0] * 3600 + parts[1] * 60 + parts[2];
  return Number.isFinite(parts[0]) ? parts[0] : 0;
}

function formatTime(totalSeconds) {
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = Math.floor(totalSeconds % 60);
  return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}
