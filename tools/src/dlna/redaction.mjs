import { createHash } from 'node:crypto';

const SENSITIVE_KEY = /token|auth|authorization|signature|sign|key|cookie|session|credential/i;

const digest = value => createHash('sha256').update(value).digest('hex');

/**
 * Keeps a media URI usable for diagnosis without exposing anything signed.
 * The raw URI is never part of the returned value.
 */
export function redactMediaUri(uri) {
  const sha256 = digest(String(uri));
  let parsed;
  try {
    parsed = new URL(uri);
  } catch {
    return Object.freeze({ displayUri: 'UNPARSEABLE_URI', scheme: null, host: null, pathExtension: null, sha256 });
  }
  for (const key of [...parsed.searchParams.keys()]) {
    if (SENSITIVE_KEY.test(key)) parsed.searchParams.set(key, 'REDACTED');
  }
  const lastSegment = parsed.pathname.split('/').pop() ?? '';
  const dot = lastSegment.lastIndexOf('.');
  return Object.freeze({
    displayUri: decodeURIComponent(parsed.toString()),
    scheme: parsed.protocol,
    host: parsed.hostname,
    pathExtension: dot > 0 ? lastSegment.slice(dot) : null,
    sha256
  });
}

/** Header names only; a secret header's value must never reach a log or the terminal. */
export function redactHeaderNames(headers) {
  const names = headers instanceof Headers ? [...headers.keys()] : Object.keys(headers ?? {});
  return Object.freeze(names.map(name => name.toLowerCase()).sort());
}
