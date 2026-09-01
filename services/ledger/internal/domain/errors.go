// Package domain contains the pure ledger domain model: accounts, journal
// entries, postings, and the money invariants that govern them
// (docs/DATA-MODEL.md §3.1, §4; PRD §11).
//
// The package has no infrastructure dependencies. Persistence and transport
// live in internal/storage and internal/api; everything here is pure data,
// validation, and derivation — unit-testable without a database.
//
// Money is ALWAYS int64 minor units. Floats are never used anywhere.
package domain

import (
	"errors"
	"fmt"
)

// Error codes are machine-readable identifiers shared with the HTTP layer.
// They are stable API surface: additive changes only.
const (
	CodeInvalidRequest               = "invalid_request"
	CodeInvalidTransactionKey        = "invalid_transaction_key"
	CodeInvalidSource                = "invalid_source"
	CodeInvalidEntryType             = "invalid_entry_type"
	CodeInvalidCurrency              = "invalid_currency"
	CodeInvalidAccountType           = "invalid_account_type"
	CodeInvalidAccountCode           = "invalid_account_code"
	CodeInvalidAccountStatus         = "invalid_account_status"
	CodeInvalidUUID                  = "invalid_uuid"
	CodeInvalidPosting               = "invalid_posting"
	CodeTooFewPostings               = "too_few_postings"
	CodeTooManyPostings              = "too_many_postings"
	CodeTransactionKeySourceMismatch = "transaction_key_source_mismatch"
	CodeInvalidReason                = "invalid_reason"
	CodeInvalidCursor                = "invalid_cursor"
	CodeInvalidLimit                 = "invalid_limit"

	CodeNotFound            = "not_found"
	CodeAccountNotFound     = "account_not_found"
	CodeEntryNotFound       = "entry_not_found"
	CodeAccountInactive     = "account_inactive"
	CodeAccountConflict     = "account_conflict"
	CodeAlreadyReversed     = "already_reversed"
	CodeReversalOfReversal  = "reversal_of_reversal"
	CodeIdempotencyConflict = "idempotency_conflict"

	CodeUnbalancedEntry   = "unbalanced_entry"
	CodeInsufficientFunds = "insufficient_funds"
	CodeReversalMismatch  = "reversal_mismatch"

	CodeInternal = "internal_error"
)

// Error is the canonical domain error. Code is machine-readable; Message is
// human-readable and safe to expose on the internal API.
type Error struct {
	Code    string
	Message string
}

func (e *Error) Error() string { return e.Message }

// Is lets errors.Is match any *Error carrying the same code, so sentinel
// comparison works across independently constructed and wrapped errors.
func (e *Error) Is(target error) bool {
	var t *Error
	if errors.As(target, &t) {
		return e.Code == t.Code
	}
	return false
}

// NewError builds a *Error with a formatted message.
func NewError(code, format string, args ...any) *Error {
	return &Error{Code: code, Message: fmt.Sprintf(format, args...)}
}

// Sentinel errors for errors.Is checks.
var (
	ErrNotFound            = &Error{Code: CodeNotFound, Message: "not found"}
	ErrAccountNotFound     = &Error{Code: CodeAccountNotFound, Message: "account not found"}
	ErrEntryNotFound       = &Error{Code: CodeEntryNotFound, Message: "entry not found"}
	ErrAccountInactive     = &Error{Code: CodeAccountInactive, Message: "account is not active"}
	ErrAccountConflict     = &Error{Code: CodeAccountConflict, Message: "account exists with different attributes"}
	ErrUnbalanced          = &Error{Code: CodeUnbalancedEntry, Message: "entry is unbalanced"}
	ErrInsufficientFunds   = &Error{Code: CodeInsufficientFunds, Message: "wallet balance would go negative"}
	ErrAlreadyReversed     = &Error{Code: CodeAlreadyReversed, Message: "entry is already reversed"}
	ErrReversalOfReversal  = &Error{Code: CodeReversalOfReversal, Message: "cannot reverse a reversal entry"}
	ErrReversalMismatch    = &Error{Code: CodeReversalMismatch, Message: "reversal legs are not the exact inverse"}
	ErrIdempotencyConflict = &Error{Code: CodeIdempotencyConflict, Message: "transaction key already used with a different payload"}
	ErrInternal            = &Error{Code: CodeInternal, Message: "internal error"}
)
