import dgram from 'node:dgram';

const MULTICAST_ADDRESS = '239.255.255.250';
const MULTICAST_PORT = 1900;
const MAX_AGE = 1800;

/** Search targets this renderer answers, in the order a control point expects them. */
export function advertisedTargets(uuid) {
  return Object.freeze([
    { st: 'upnp:rootdevice', usn: `uuid:${uuid}::upnp:rootdevice` },
    { st: `uuid:${uuid}`, usn: `uuid:${uuid}` },
    { st: 'urn:schemas-upnp-org:device:MediaRenderer:1', usn: `uuid:${uuid}::urn:schemas-upnp-org:device:MediaRenderer:1` },
    { st: 'urn:schemas-upnp-org:service:AVTransport:1', usn: `uuid:${uuid}::urn:schemas-upnp-org:service:AVTransport:1` },
    { st: 'urn:schemas-upnp-org:service:RenderingControl:1', usn: `uuid:${uuid}::urn:schemas-upnp-org:service:RenderingControl:1` },
    { st: 'urn:schemas-upnp-org:service:ConnectionManager:1', usn: `uuid:${uuid}::urn:schemas-upnp-org:service:ConnectionManager:1` }
  ]);
}

/** Targets that must answer one M-SEARCH, including the ssdp:all wildcard. */
export function matchingTargets(uuid, searchTarget) {
  const targets = advertisedTargets(uuid);
  if (searchTarget === 'ssdp:all') return targets;
  return targets.filter(target => target.st === searchTarget);
}

export function searchTargetOf(message) {
  const text = String(message);
  if (!/^M-SEARCH\s/i.test(text)) return null;
  const match = text.match(/\r?\nST:\s*([^\r\n]+)/i);
  return match ? match[1].trim() : null;
}

const response = ({ location, target }) => Buffer.from([
  'HTTP/1.1 200 OK',
  `CACHE-CONTROL: max-age=${MAX_AGE}`,
  'EXT:',
  `LOCATION: ${location}`,
  'SERVER: SoundMesh/0.1 UPnP/1.0 SoundMeshProbe/0.1',
  `ST: ${target.st}`,
  `USN: ${target.usn}`,
  '', ''
].join('\r\n'));

const notify = ({ location, target, alive }) => Buffer.from([
  'NOTIFY * HTTP/1.1',
  `HOST: ${MULTICAST_ADDRESS}:${MULTICAST_PORT}`,
  ...(alive ? [`CACHE-CONTROL: max-age=${MAX_AGE}`, `LOCATION: ${location}`, 'SERVER: SoundMesh/0.1 UPnP/1.0 SoundMeshProbe/0.1'] : []),
  `NT: ${target.st}`,
  `NTS: ssdp:${alive ? 'alive' : 'byebye'}`,
  `USN: ${target.usn}`,
  '', ''
].join('\r\n'));

/** Answers discovery on one chosen private interface only. */
export function createSsdpResponder({ uuid, address, location, onEvent = () => {} }) {
  const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  const targets = advertisedTargets(uuid);

  socket.on('message', (message, remote) => {
    const searchTarget = searchTargetOf(message);
    if (!searchTarget) return;
    const matches = matchingTargets(uuid, searchTarget);
    if (matches.length === 0) return;
    onEvent({ type: 'M-SEARCH', searchTarget, matched: matches.length });
    for (const target of matches) {
      socket.send(response({ location, target }), remote.port, remote.address);
    }
  });

  const announce = alive => {
    for (const target of targets) {
      socket.send(notify({ location, target, alive }), MULTICAST_PORT, MULTICAST_ADDRESS);
    }
  };

  return Object.freeze({
    start: () => new Promise((resolve, reject) => {
      socket.once('error', reject);
      socket.bind(MULTICAST_PORT, () => {
        try {
          socket.addMembership(MULTICAST_ADDRESS, address);
          socket.setMulticastInterface(address);
          announce(true);
          onEvent({ type: 'SSDP_ALIVE', address });
          resolve();
        } catch (error) {
          reject(error);
        }
      });
    }),
    stop: () => new Promise(resolve => {
      try { announce(false); onEvent({ type: 'SSDP_BYEBYE' }); } catch { /* the socket may already be gone */ }
      setTimeout(() => socket.close(() => resolve()), 100);
    })
  });
}
