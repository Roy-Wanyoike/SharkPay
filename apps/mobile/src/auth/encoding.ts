/**
 * Pure base64/base64url + UTF-8 decoding for JWT payloads.
 *
 * React Native's JS runtime does not guarantee `atob` or `TextDecoder`, so
 * this module carries dependency-free implementations (testable in isolation
 * — see src/auth/__tests__/encoding.test.ts).
 */

const BASE64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

function base64CharIndex(char: string): number {
  const index = BASE64_ALPHABET.indexOf(char);
  if (index < 0) {
    throw new SyntaxError(`invalid base64 character ${JSON.stringify(char)}`);
  }
  return index;
}

/**
 * Decodes a base64 or base64url string into its bytes. Padding is optional;
 * URL-safe characters (`-`/`_`) are normalised to `+`/`/` first.
 */
export function decodeBase64ToBytes(input: string): Uint8Array {
  if (typeof input !== 'string') {
    throw new TypeError('input must be a string');
  }
  const normalized = input.replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '');
  if (normalized.length === 0) {
    return new Uint8Array(0);
  }
  if (normalized.length % 4 === 1) {
    throw new SyntaxError('invalid base64 length');
  }

  const byteCount = Math.floor((normalized.length * 3) / 4);
  const bytes = new Uint8Array(byteCount);
  let byteIndex = 0;
  for (let index = 0; index < normalized.length; index += 4) {
    const remaining = normalized.length - index;
    const c1 = base64CharIndex(normalized[index] ?? '');
    const c2 = base64CharIndex(normalized[index + 1] ?? '');
    const c3 = remaining > 2 ? base64CharIndex(normalized[index + 2] ?? '') : 0;
    const c4 = remaining > 3 ? base64CharIndex(normalized[index + 3] ?? '') : 0;

    bytes[byteIndex++] = (c1 << 2) | (c2 >> 4);
    if (remaining > 2) {
      bytes[byteIndex++] = ((c2 & 0xf) << 4) | (c3 >> 2);
    }
    if (remaining > 3) {
      bytes[byteIndex++] = ((c3 & 0x3) << 6) | c4;
    }
  }
  return bytes.subarray(0, byteIndex);
}

/** Decodes a byte array as UTF-8 (dependency-free TextDecoder replacement). */
export function decodeUtf8(bytes: Uint8Array): string {
  let result = '';
  let codePoint = 0;
  let continuation = 0;
  for (const byte of bytes) {
    if (continuation === 0) {
      if (byte < 0x80) {
        result += String.fromCharCode(byte);
      } else if (byte >= 0xc0 && byte <= 0xdf) {
        codePoint = byte & 0x1f;
        continuation = 1;
      } else if (byte >= 0xe0 && byte <= 0xef) {
        codePoint = byte & 0x0f;
        continuation = 2;
      } else if (byte >= 0xf0 && byte <= 0xf7) {
        codePoint = byte & 0x07;
        continuation = 3;
      } else {
        throw new SyntaxError('invalid UTF-8 continuation byte');
      }
    } else {
      if ((byte & 0xc0) !== 0x80) {
        throw new SyntaxError('invalid UTF-8 sequence');
      }
      codePoint = (codePoint << 6) | (byte & 0x3f);
      continuation -= 1;
      if (continuation === 0) {
        result += String.fromCodePoint(codePoint);
      }
    }
  }
  if (continuation !== 0) {
    throw new SyntaxError('truncated UTF-8 sequence');
  }
  return result;
}

/** Decodes a base64url JWT segment to a string. */
export function decodeBase64UrlToString(input: string): string {
  return decodeUtf8(decodeBase64ToBytes(input));
}
