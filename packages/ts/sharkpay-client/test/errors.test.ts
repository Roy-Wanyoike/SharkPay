/** Error envelope parsing and status → error class mapping. */

import { describe, expect, it } from 'vitest';
import {
  ApiError,
  AuthError,
  IdempotencyConflictError,
  RateLimitError,
  SharkPayError,
  TimeoutError,
  ValidationError,
  parseErrorEnvelope,
  toSharkPayError,
} from '../src/errors.js';
import { REQUEST_ID } from './helpers.js';

function envelope(code: string, message: string, details?: Record<string, unknown>) {
  return { error: { code, message, request_id: REQUEST_ID, ...(details !== undefined ? { details } : {}) } };
}

describe('parseErrorEnvelope', () => {
  it('parses a full envelope with details', () => {
    const body = envelope('insufficient_funds', 'Wallet balance after holds is 1200, requested 5000.', {
      available_minor: 1200,
      requested_minor: 5000,
    });
    expect(parseErrorEnvelope(body)).toEqual({
      code: 'insufficient_funds',
      message: 'Wallet balance after holds is 1200, requested 5000.',
      request_id: REQUEST_ID,
      details: { available_minor: 1200, requested_minor: 5000 },
    });
  });

  it('parses a minimal envelope', () => {
    expect(parseErrorEnvelope(envelope('not_found', 'gone'))).toEqual({
      code: 'not_found',
      message: 'gone',
      request_id: REQUEST_ID,
    });
  });

  it('rejects non-envelope bodies', () => {
    expect(parseErrorEnvelope(null)).toBeNull();
    expect(parseErrorEnvelope('nope')).toBeNull();
    expect(parseErrorEnvelope({})).toBeNull();
    expect(parseErrorEnvelope({ error: 'flat' })).toBeNull();
    expect(parseErrorEnvelope({ error: { code: 1, message: 'x', request_id: 'y' } })).toBeNull();
    expect(parseErrorEnvelope({ error: { code: 'c', message: 'x' } })).toBeNull(); // missing request_id
    expect(parseErrorEnvelope({ error: { code: 'c', message: 'x', request_id: 'r', details: 'flat' } })).toEqual({
      code: 'c',
      message: 'x',
      request_id: 'r',
    });
  });
});

describe('toSharkPayError mapping', () => {
  it('400 + validation_error → ValidationError carrying details', () => {
    const error = toSharkPayError({
      status: 400,
      body: envelope('validation_error', 'amount_minor: must be a positive integer.', { field: 'amount_minor' }),
    });
    expect(error).toBeInstanceOf(ValidationError);
    expect(error).toBeInstanceOf(SharkPayError);
    expect(error.code).toBe('validation_error');
    expect(error.status).toBe(400);
    expect(error.requestId).toBe(REQUEST_ID);
    expect(error.details).toEqual({ field: 'amount_minor' });
    expect(error.message).toBe('amount_minor: must be a positive integer.');
  });

  it('401 → AuthError (unauthorized), 403 → AuthError (forbidden)', () => {
    const unauthorized = toSharkPayError({ status: 401, body: envelope('unauthorized', 'Invalid API key.') });
    expect(unauthorized).toBeInstanceOf(AuthError);
    expect(unauthorized.code).toBe('unauthorized');

    const forbidden = toSharkPayError({
      status: 403,
      body: envelope('forbidden', 'Key lacks required scope payments:write.', {
        required_scope: 'payments:write',
      }),
    });
    expect(forbidden).toBeInstanceOf(AuthError);
    expect(forbidden.code).toBe('forbidden');
    expect(forbidden.details).toEqual({ required_scope: 'payments:write' });
  });

  it('409 + idempotency_conflict → IdempotencyConflictError', () => {
    const error = toSharkPayError({
      status: 409,
      body: envelope('idempotency_conflict', 'Idempotency-Key was already used with a different request payload.'),
    });
    expect(error).toBeInstanceOf(IdempotencyConflictError);
    expect(error.code).toBe('idempotency_conflict');
  });

  it('409 + state_conflict stays a generic ApiError', () => {
    const error = toSharkPayError({
      status: 409,
      body: envelope('state_conflict', 'Payment is already in terminal state SUCCEEDED and cannot be cancelled.'),
    });
    expect(error).toBeInstanceOf(ApiError);
    expect(error).not.toBeInstanceOf(IdempotencyConflictError);
    expect(error.code).toBe('state_conflict');
  });

  it('422 business rules stay ApiError with their code', () => {
    const error = toSharkPayError({
      status: 422,
      body: envelope('risk_blocked', 'Transaction blocked by risk rules (velocity).', {
        rule: 'velocity_per_hour',
      }),
    });
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('risk_blocked');
    expect(error.details).toEqual({ rule: 'velocity_per_hour' });
  });

  it('429 → RateLimitError with parsed retryAfterMs', () => {
    const error = toSharkPayError({
      status: 429,
      body: envelope('quota_exceeded', 'Payment creation burst limit of 50/min exceeded.'),
      retryAfterMs: 7000,
    });
    expect(error).toBeInstanceOf(RateLimitError);
    expect((error as RateLimitError).retryAfterMs).toBe(7000);
  });

  it('429 without Retry-After has retryAfterMs undefined', () => {
    const error = toSharkPayError({ status: 429, body: envelope('quota_exceeded', 'slow down') });
    expect(error).toBeInstanceOf(RateLimitError);
    expect((error as RateLimitError).retryAfterMs).toBeUndefined();
  });

  it('500 → ApiError internal_error; unparseable body falls back to status-derived code', () => {
    const withBody = toSharkPayError({ status: 500, body: envelope('internal_error', 'An unexpected error occurred.') });
    expect(withBody).toBeInstanceOf(ApiError);
    expect(withBody.code).toBe('internal_error');

    const garbage = toSharkPayError({ status: 503, body: null, headerRequestId: REQUEST_ID });
    expect(garbage).toBeInstanceOf(ApiError);
    expect(garbage.code).toBe('internal_error');
    expect(garbage.message).toContain('503');
    expect(garbage.requestId).toBe(REQUEST_ID); // header fallback when the envelope lacks request_id
  });

  it('404 with no envelope falls back to not_found', () => {
    const error = toSharkPayError({ status: 404, body: null });
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('not_found');
  });

  it('every mapped error is a SharkPayError with a useful name and stack', () => {
    const error = toSharkPayError({ status: 400, body: envelope('validation_error', 'bad') });
    expect(error).toBeInstanceOf(SharkPayError);
    expect(error.name).toBe('ValidationError');
    expect(error.stack).toBeTypeOf('string');
    expect(new TimeoutError({ code: 'timeout', status: 0, message: 'x' }).name).toBe('TimeoutError');
  });
});
