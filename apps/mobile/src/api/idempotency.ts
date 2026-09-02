/**
 * Idempotency-Key generation for state-changing requests.
 *
 * common.yaml `IdempotencyKey` header: UUID format, 1..128 chars. Scope is
 * `(principal, endpoint, key)`; retries with the same key return the original
 * response with `X-Idempotent-Replay: true`; reuse with a different payload
 * is a 409 `idempotency_conflict` (see src/api/errors.ts).
 *
 * The generator is RFC-4122 v4 shaped. It prefers `crypto.getRandomValues`
 * (available under Hermes/New Architecture when a polyfill registers it) and
 * falls back to `Math.random`. Idempotency keys are NOT secrets — their only
 * job is uniqueness per logical mutation so a retried POST can never move
 * money twice. The random source is injectable for deterministic tests.
 */

/** Minimal structural type of `crypto.getRandomValues`. */
export type RandomValuesFn = <TArray extends ArrayBufferView>(array: TArray) => TArray;

/** Byte-level random source used by {@link randomUuid}. */
export type RandomByteSource = (count: number) => Uint8Array;

function sourceFromGetRandomValues(fn: RandomValuesFn): RandomByteSource {
  return (count) => {
    const bytes = new Uint8Array(count);
    fn(bytes);
    return bytes;
  };
}

function sourceFromMathRandom(): RandomByteSource {
  return (count) => {
    const bytes = new Uint8Array(count);
    for (let index = 0; index < count; index += 4) {
      // Math.random yields 2^53 values; extract 32 bits per call.
      const word = Math.floor(Math.random() * 0x1_0000_0000);
      bytes[index] = (word >>> 24) & 0xff;
      if (index + 1 < count) bytes[index + 1] = (word >>> 16) & 0xff;
      if (index + 2 < count) bytes[index + 2] = (word >>> 8) & 0xff;
      if (index + 3 < count) bytes[index + 3] = word & 0xff;
    }
    return bytes;
  };
}

/** Resolves the best available byte source in the current runtime. */
export function resolveRandomByteSource(): RandomByteSource {
  const globalCrypto = (globalThis as { crypto?: { getRandomValues?: RandomValuesFn } }).crypto;
  if (globalCrypto !== undefined && typeof globalCrypto.getRandomValues === 'function') {
    return sourceFromGetRandomValues(globalCrypto.getRandomValues);
  }
  return sourceFromMathRandom();
}

function hexDigit(byte: number): string {
  return (byte & 0xf).toString(16);
}

/**
 * RFC-4122 v4 UUID from an injected byte source (16 bytes consumed).
 * Pure — no runtime global access — so tests can pin the exact output.
 */
export function uuidV4FromBytes(bytes: Uint8Array): string {
  if (bytes.length !== 16) {
    throw new RangeError(`uuidV4FromBytes requires exactly 16 bytes (got ${bytes.length})`);
  }
  const hex: string[] = [];
  for (let index = 0; index < 16; index++) {
    const byte = bytes[index] ?? 0;
    hex.push((byte >>> 4).toString(16), hexDigit(byte));
  }
  const value = hex.join('');
  const withVersion =
    value.slice(0, 12) +
    '4' + // version nibble
    hexDigit((parseInt(value[16] ?? '0', 16) & 0x3) | 0x8) + // variant 10xx
    value.slice(17);
  return `${withVersion.slice(0, 8)}-${withVersion.slice(8, 12)}-${withVersion.slice(
    12,
    16,
  )}-${withVersion.slice(16, 20)}-${withVersion.slice(20, 32)}`;
}

/**
 * Generates a fresh RFC-4122 v4 UUID (the default idempotency key shape).
 * Uses `crypto.getRandomValues` when the runtime provides it, else
 * `Math.random` (uniqueness, not secrecy, is the requirement).
 */
export function randomUuid(source: RandomByteSource = resolveRandomByteSource()): string {
  return uuidV4FromBytes(source(16));
}

/**
 * The idempotency key generator used by the API client. Wraps
 * {@link randomUuid}; swap through client options for deterministic tests.
 */
export function generateIdempotencyKey(): string {
  return randomUuid();
}
