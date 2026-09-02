/**
 * base64url helpers shared by the session cookie seal and PKCE challenge.
 * Implemented with Web APIs only (btoa/atob/TextEncoder) so every helper is
 * safe in Node, the Edge middleware runtime, and the browser.
 */

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function toBase64Url(bytes: Uint8Array<ArrayBuffer>): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromBase64Url(value: string): Uint8Array<ArrayBuffer> {
  const normalized = value
    .replace(/-/g, "+")
    .replace(/_/g, "/")
    .padEnd(Math.ceil(value.length / 4) * 4, "=");
  const binary = atob(normalized);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

export function encodeText(value: string): Uint8Array<ArrayBuffer> {
  return encoder.encode(value);
}

export function decodeText(bytes: Uint8Array): string {
  return decoder.decode(bytes);
}

export function randomUrlSafeBytes(length: number): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}
