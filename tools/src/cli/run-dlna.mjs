import { appendFile, mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRendererState } from '../dlna/renderer-state.mjs';
import { createRendererServer, listPrivateIpv4Interfaces } from '../dlna/server.mjs';
import { createSsdpResponder } from '../dlna/ssdp.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../artifacts');
const value = (args, key) => { const index = args.indexOf(key); return index === -1 ? undefined : args[index + 1]; };

/** Chooses the one private interface to bind, or refuses when the choice is ambiguous. */
export function selectInterface(interfaces, requestedAddress) {
  if (interfaces.length === 0) throw new Error('No private IPv4 interface is available');
  if (requestedAddress) {
    const chosen = interfaces.find(entry => entry.address === requestedAddress);
    if (!chosen) throw new Error(`Requested address is not a private IPv4 interface: ${requestedAddress}`);
    return chosen;
  }
  if (interfaces.length !== 1) {
    throw new Error(`Multiple private interfaces need an explicit --address: ${interfaces.map(entry => entry.address).join(', ')}`);
  }
  return interfaces[0];
}

export async function main(args = process.argv.slice(2), {
  interfaces = listPrivateIpv4Interfaces(),
  paths = { root },
  sessionId = crypto.randomUUID(),
  log = text => process.stdout.write(`${text}\n`)
} = {}) {
  const chosen = selectInterface(interfaces, value(args, '--address'));
  const port = Number.parseInt(value(args, '--port') ?? '8200', 10);
  if (!Number.isInteger(port) || port < 1024 || port > 65_535) throw new Error('Use a --port between 1024 and 65535');

  const privateDirectory = resolve(paths.root, 'private', 'dlna', sessionId);
  await mkdir(privateDirectory, { recursive: true });
  const rawEventsPath = resolve(privateDirectory, 'raw-events.jsonl');

  const state = createRendererState();
  const publicEvents = [];
  const onEvent = event => {
    const { rawUri, ...safe } = event;
    publicEvents.push({ at: new Date().toISOString(), ...safe });
    appendFile(rawEventsPath, `${JSON.stringify({ at: new Date().toISOString(), ...event })}\n`, 'utf8')
      .catch(error => log(`WARN could not write the private event log: ${error.message}`));
    log(`EVENT ${JSON.stringify(safe)}`);
  };

  const server = createRendererServer({ uuid: sessionId, address: chosen.address, port, state, onEvent });
  await server.start();
  const ssdp = createSsdpResponder({ uuid: sessionId, address: chosen.address, location: server.location, onEvent });
  await ssdp.start();

  log(`SoundMesh Probe Renderer is advertising on ${chosen.name} at ${server.location}`);
  log('Open the music app, choose the cast button, and select "SoundMesh Probe Renderer". Press Ctrl+C to stop.');

  const shutdown = async () => {
    await ssdp.stop();
    await server.stop();
    const summary = { sessionId, address: chosen.address, port, snapshot: state.snapshot(), events: publicEvents };
    await writeFile(resolve(privateDirectory, 'session-summary.json'), `${JSON.stringify(summary, null, 2)}\n`, 'utf8');
    log(`Stopped. Redacted summary written for session ${sessionId}.`);
    return summary;
  };

  return Object.freeze({ location: server.location, address: chosen.address, port, sessionId, state, shutdown });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const renderer = await main();
  const stop = () => { renderer.shutdown().finally(() => process.exit(0)); };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}
