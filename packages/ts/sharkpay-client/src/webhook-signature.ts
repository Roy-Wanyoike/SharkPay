/**
 * Webhook delivery signature verification, implementing the outbound
 * delivery contract from contracts/openapi/v1/webhooks.yaml:
 *
 * `X-SharkPay-Signature: t=<unix>,v1=<hmac-sha256(t + '.' + raw_body, secret)>`
 *
 * Verify the signature **before** trusting the body, reject timestamps
 * outside ±5 minutes (constant-time compare of the digests), and remember
 * delivery is at-least-once: dedupe on `event.id`.
 */

import { hmacSha256Hex } from './platform.js';
import { SIGNATURE_TOLERANCE_MS } from './types/webhooks.js';

/** Options for {@link verifyWebhookSignature}. */
export interface VerifyWebhookSignatureOptions {
  /** Current time in ms (default `Date.now()`; inject for deterministic tests). */
  nowMs?: number;
  /** Allowed clock skew in ms (default ±5 minutes per the contract). */
  toleranceMs?: number;
}

const SIGNATURE_PATTERN = /^t=(\d+),v1=([0-9a-f]{64})$/;

/** Constant-time comparison of two hex digests (same length). */
function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let difference = 0;
  for (let index = 0; index < a.length; index += 1) {
    difference |= (a.charCodeAt(index) ?? 0) ^ (b.charCodeAt(index) ?? 0);
  }
  return difference === 0;
}

/**
 * Verify an `X-SharkPay-Signature` header against the **raw** request body
 * (byte-for-byte as received — do not re-serialize parsed JSON) and the
 * endpoint secret.
 *
 * Checks, in order: header shape (`t=<digits>,v1=<64 lowercase hex>`),
 * timestamp freshness within the tolerance window, then the HMAC. Returns
 * `false` on any mismatch (never throws for a bad signature).
 *
 * ```ts
 * const raw = await request.text(); // keep the raw bytes!
 * const ok = await verifyWebhookSignature(raw, request.headers['x-sharkpay-signature'], secret);
 * if (!ok) return respond(400);
 * const event = parseWebhookEvent(JSON.parse(raw));
 * ```
 */
export async function verifyWebhookSignature(
  rawBody: string,
  signatureHeader: string,
  secret: string,
  options?: VerifyWebhookSignatureOptions,
): Promise<boolean> {
  const match = SIGNATURE_PATTERN.exec(signatureHeader.trim());
  if (match === null) {
    return false;
  }
  const timestamp = Number(match[1]);
  const provided = match[2];
  if (provided === undefined) {
    return false;
  }

  const nowMs = options?.nowMs ?? Date.now();
  const toleranceMs = options?.toleranceMs ?? SIGNATURE_TOLERANCE_MS;
  if (!Number.isFinite(timestamp) || Math.abs(nowMs / 1000 - timestamp) > toleranceMs / 1000) {
    return false;
  }

  const expected = await hmacSha256Hex(secret, `${timestamp}.${rawBody}`);
  return timingSafeEqualHex(provided, expected);
}
