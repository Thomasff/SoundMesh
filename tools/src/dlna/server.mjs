import { createServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import { deviceDescription, parseSoapAction, readSoapArgument, soapFault, soapResponse } from './xml.mjs';
import { redactHeaderNames, redactMediaUri } from './redaction.mjs';

const PRIVATE_IPV4 = /^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)/;
const PROBE_TIMEOUT_MS = 60_000;
const MAX_REDIRECTS = 5;

/** Arguments a control point may send; anything else is ignored rather than echoed back. */
const SOAP_ARGUMENTS = Object.freeze(['CurrentURI', 'CurrentURIMetaData', 'DesiredVolume', 'DesiredMute', 'Target', 'Unit', 'InstanceID']);

export function listPrivateIpv4Interfaces() {
  const found = [];
  for (const [name, entries] of Object.entries(networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.family === 'IPv4' && !entry.internal && PRIVATE_IPV4.test(entry.address)) {
        found.push({ name, address: entry.address });
      }
    }
  }
  return Object.freeze(found);
}

/**
 * Reads the media the music app offered, discarding the bytes.
 * The URI is never forwarded anywhere else and never appears in the result.
 */
export async function probeMedia(uri, { timeoutMs = PROBE_TIMEOUT_MS, fetchImpl = fetch, now = () => Date.now() } = {}) {
  const startedAt = now();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let redirects = 0;
  let target = uri;
  try {
    while (true) {
      const response = await fetchImpl(target, { redirect: 'manual', signal: controller.signal });
      const location = response.headers?.get?.('location');
      if (response.status >= 300 && response.status < 400 && location && redirects < MAX_REDIRECTS) {
        redirects++;
        target = new URL(location, target).toString();
        continue;
      }
      let bytesRead = 0;
      if (response.body) {
        for await (const chunk of response.body) bytesRead += chunk.length ?? chunk.byteLength ?? 0;
      }
      return Object.freeze({
        status: response.status,
        contentType: response.headers?.get?.('content-type') ?? null,
        headerNames: redactHeaderNames(response.headers ?? {}),
        bytesRead,
        redirects,
        elapsedMs: now() - startedAt,
        failureClass: null
      });
    }
  } catch (error) {
    return Object.freeze({
      status: 0,
      contentType: null,
      headerNames: [],
      bytesRead: 0,
      redirects,
      elapsedMs: now() - startedAt,
      failureClass: error?.name === 'AbortError' ? 'TIMEOUT' : 'REQUEST_FAILED'
    });
  } finally {
    clearTimeout(timer);
  }
}

/** HTTP surface of the renderer, bound to one chosen interface rather than every address. */
export function createRendererServer({ uuid, address, port, state, onEvent = () => {}, probe = probeMedia }) {
  const baseUrl = `http://${address}:${port}`;
  const server = createServer((request, response) => {
    const url = request.url ?? '/';
    if (request.method === 'GET' && (url === '/description.xml' || url === '/')) {
      onEvent({ type: 'DESCRIPTION_FETCHED', from: request.socket.remoteAddress });
      response.writeHead(200, { 'Content-Type': 'text/xml; charset="utf-8"' });
      response.end(deviceDescription({ uuid, baseUrl }));
      return;
    }
    if (request.method === 'GET' && url.startsWith('/scpd/')) {
      response.writeHead(200, { 'Content-Type': 'text/xml; charset="utf-8"' });
      response.end('<?xml version="1.0"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList/><serviceStateTable/></scpd>');
      return;
    }
    if (request.method === 'SUBSCRIBE' || request.method === 'UNSUBSCRIBE') {
      response.writeHead(200, { SID: `uuid:${uuid}`, TIMEOUT: 'Second-1800' });
      response.end();
      return;
    }
    if (request.method === 'POST' && url.startsWith('/control/')) {
      handleControl(request, response, url.slice('/control/'.length));
      return;
    }
    response.writeHead(404).end();
  });

  function handleControl(request, response, service) {
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => {
      const body = Buffer.concat(chunks).toString('utf8');
      const action = parseSoapAction(request.headers.soapaction);
      if (!action) {
        response.writeHead(500, { 'Content-Type': 'text/xml; charset="utf-8"' });
        response.end(soapFault(401, 'Invalid Action'));
        return;
      }
      const args = {};
      for (const name of SOAP_ARGUMENTS) {
        const value = readSoapArgument(body, name);
        if (value !== null) args[name] = value;
      }
      const result = state.invoke(service, action, args);
      onEvent({
        type: 'SOAP',
        service,
        action,
        accepted: !result.error,
        media: args.CurrentURI ? redactMediaUri(args.CurrentURI) : undefined,
        rawUri: args.CurrentURI
      });
      if (result.error) {
        response.writeHead(500, { 'Content-Type': 'text/xml; charset="utf-8"' });
        response.end(soapFault(result.error, result.description));
        return;
      }
      response.writeHead(200, { 'Content-Type': 'text/xml; charset="utf-8"' });
      response.end(soapResponse(service, action, result));
      if (action === 'Play' && state.currentUri()) startProbe();
    });
  }

  let probing = false;
  async function startProbe() {
    if (probing) return;
    probing = true;
    try {
      const result = await probe(state.currentUri());
      state.recordProbe(result);
      onEvent({ type: 'MEDIA_PROBE', result });
    } finally {
      probing = false;
    }
  }

  return Object.freeze({
    baseUrl,
    location: `${baseUrl}/description.xml`,
    start: () => new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(port, address, () => resolve());
    }),
    stop: () => new Promise(resolve => server.close(() => resolve()))
  });
}
