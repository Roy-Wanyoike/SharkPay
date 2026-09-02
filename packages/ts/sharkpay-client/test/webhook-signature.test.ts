/**
 * Webhook signature verification: cross-checked against node:crypto's
 * HMAC-SHA256 (the same construction the api-gateway signs with).
 */

import { createHmac } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { verifyWebhookSignature } from '../src/webhook-signature.js';

const SECRET = 'whsec_5f8a2b9c1d4e6f7a8b9c0d1e2f3a4b5c';
const NOW_MS = 1_767_312_000_000; // 2026-01-02T00:00:00Z
const T = 1_767_312_000; // matching unix seconds

function sign(body: string, timestamp: number = T, secret: string = SECRET): string {
  const v1 = createHmac('sha256', secret).update(`${timestamp}.${body}`).digest('hex');
  return `t=${timestamp},v1=${v1}`;
}

const BODY = JSON.stringify({
  id: '0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d',
  type: 'payment.succeeded',
  specversion: '1.0',
});

describe('verifyWebhookSignature', () => {
  it('accepts a correctly signed fresh delivery', async () => {
    await expect(verifyWebhookSignature(BODY, sign(BODY), SECRET, { nowMs: NOW_MS })).resolves.toBe(true);
  });

  it('rejects a signature computed over a different body (byte-level raw body matters)', async () => {
    await expect(
      verifyWebhookSignature('{"id":"0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d","type":"payment.failed","specversion":"1.0"}', sign(BODY), SECRET, {
        nowMs: NOW_MS,
      }),
    ).resolves.toBe(false);
  });

  it('rejects a signature keyed with the wrong secret', async () => {
    await expect(verifyWebhookSignature(BODY, sign(BODY), 'whsec_wrong', { nowMs: NOW_MS })).resolves.toBe(
      false,
    );
  });

  it('rejects stale timestamps beyond the ±5 minute window and accepts the edge', async () => {
    const sixMinutesOld = (T - 360) * 1000;
    await expect(
      verifyWebhookSignature(BODY, sign(BODY), SECRET, { nowMs: NOW_MS }),
    ).resolves.toBe(true); // T === now/1000
    await expect(
      verifyWebhookSignature(BODY, sign(BODY, T - 301), SECRET, { nowMs: NOW_MS }),
    ).resolves.toBe(false); // 301s old > 300s tolerance
    await expect(
      verifyWebhookSignature(BODY, sign(BODY, T - 300), SECRET, { nowMs: NOW_MS }),
    ).resolves.toBe(true); // exactly at the edge
    await expect(
      verifyWebhookSignature(BODY, sign(BODY, T + 301), SECRET, { nowMs: NOW_MS }),
    ).resolves.toBe(false); // future timestamps are also bounded
    await expect(
      verifyWebhookSignature(BODY, sign(BODY, T - 360), SECRET, { nowMs: NOW_MS }),
    ).resolves.toBe(false);
    expect(sixMinutesOld).toBeGreaterThan(0);
  });

  it('accepts a custom tolerance window', async () => {
    await expect(
      verifyWebhookSignature(BODY, sign(BODY, T - 500), SECRET, {
        nowMs: NOW_MS,
        toleranceMs: 600_000,
      }),
    ).resolves.toBe(true);
  });

  it('rejects malformed headers without throwing', async () => {
    await expect(verifyWebhookSignature(BODY, 'garbage', SECRET, { nowMs: NOW_MS })).resolves.toBe(false);
    await expect(verifyWebhookSignature(BODY, '', SECRET, { nowMs: NOW_MS })).resolves.toBe(false);
    await expect(
      verifyWebhookSignature(BODY, `t=${T},v1=${'x'.repeat(64)}`, SECRET, { nowMs: NOW_MS }),
    ).resolves.toBe(false); // non-hex v1
    await expect(
      verifyWebhookSignature(BODY, `t=${T},v1=${'a'.repeat(63)}`, SECRET, { nowMs: NOW_MS }),
    ).resolves.toBe(false); // too short
  });
});
