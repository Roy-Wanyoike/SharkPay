/**
 * Minimal, dependency-free access to the platform WebCrypto primitives the
 * SDK needs (UUID v4 generation for idempotency keys, HMAC-SHA256 for
 * webhook signature verification).
 *
 * The structural interfaces below describe exactly the call shapes the SDK
 * uses, resolved at runtime from `globalThis.crypto` — available on
 * Node >= 19 (we require >= 20) and every modern browser, with no
 * ambient-typing dependency on either `lib.dom` or `@types/node`.
 */

/** Opaque handle for an imported WebCrypto key (structurally typed). */
export interface CryptoKeyHandle {
  readonly type: string;
}

/** The subset of `SubtleCrypto` the SDK calls. */
export interface HmacSubtleCrypto {
  importKey(
    format: 'raw',
    keyData: Uint8Array,
    algorithm: { name: 'HMAC'; hash: 'SHA-256' },
    extractable: false,
    keyUsages: string[],
  ): Promise<CryptoKeyHandle>;
  sign(algorithm: 'HMAC', key: CryptoKeyHandle, data: Uint8Array): Promise<ArrayBuffer>;
}

/** The subset of `Crypto` the SDK uses. */
export interface PlatformCrypto {
  randomUUID(): string;
  subtle: HmacSubtleCrypto;
}

function getPlatformCrypto(): PlatformCrypto {
  const maybe = (globalThis as { readonly crypto?: unknown }).crypto;
  if (
    typeof maybe === 'object' &&
    maybe !== null &&
    typeof (maybe as PlatformCrypto).randomUUID === 'function' &&
    typeof (maybe as PlatformCrypto).subtle === 'object' &&
    (maybe as PlatformCrypto).subtle !== null &&
    typeof (maybe as PlatformCrypto).subtle.importKey === 'function' &&
    typeof (maybe as PlatformCrypto).subtle.sign === 'function'
  ) {
    return maybe as PlatformCrypto;
  }
  throw new Error(
    '@sharkpay/client requires globalThis.crypto with randomUUID() and subtle (WebCrypto). ' +
      'Use Node >= 20 or a modern browser.',
  );
}

/** Generate a UUID (v4) via `crypto.randomUUID` — used for idempotency keys. */
export function randomUuid(): string {
  return getPlatformCrypto().randomUUID();
}

function bytesToHex(bytes: Uint8Array): string {
  let hex = '';
  for (let index = 0; index < bytes.length; index += 1) {
    const byte = bytes[index] ?? 0;
    hex += byte.toString(16).padStart(2, '0');
  }
  return hex;
}

/**
 * Compute the hex-encoded HMAC-SHA256 of `message` keyed with `secret`,
 * via WebCrypto (`crypto.subtle`) — works in Node and browsers alike.
 */
export async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const encoder = new TextEncoder();
  const subtle = getPlatformCrypto().subtle;
  const key = await subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const signature = await subtle.sign('HMAC', key, encoder.encode(message));
  return bytesToHex(new Uint8Array(signature));
}
