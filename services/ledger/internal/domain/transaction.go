package domain

import (
	"fmt"
	"strings"
)

// Transaction is a journal entry request: the unit of atomic persistence.
// ReversesEntryID is non-empty only for reversal entries (created via the
// reverse flow, which derives the inverse legs).
type Transaction struct {
	TransactionKey  string
	Source          Source
	SourceRef       string
	EntryType       EntryType
	ReversesEntryID string
	Reason          string
	OperatorID      string
	Postings        []Leg
}

const (
	minTransactionKeyLen = 5
	maxTransactionKeyLen = 128
	maxKeySegmentLen     = 64
)

// ValidateTransactionKey checks the idempotency key format:
//
//	source:ref[:subtype]
//
// e.g. "payments:019283…:capture", "payouts:0192…:hold", "ops:rev:019283…".
// The first segment must be a valid source.
func ValidateTransactionKey(key string) error {
	if len(key) < minTransactionKeyLen || len(key) > maxTransactionKeyLen {
		return NewError(CodeInvalidTransactionKey,
			"transaction_key must be %d-%d characters, got %d", minTransactionKeyLen, maxTransactionKeyLen, len(key))
	}
	parts := strings.Split(key, ":")
	if len(parts) < 2 || len(parts) > 3 {
		return NewError(CodeInvalidTransactionKey,
			"transaction_key %q must have the form source:ref[:subtype]", key)
	}
	for _, p := range parts {
		if p == "" || len(p) > maxKeySegmentLen {
			return NewError(CodeInvalidTransactionKey,
				"transaction_key %q has an empty or over-long segment", key)
		}
		for _, r := range p {
			if !isKeyChar(r) {
				return NewError(CodeInvalidTransactionKey,
					"transaction_key %q contains invalid character %q (allowed: A-Z a-z 0-9 . _ -)", key, r)
			}
		}
	}
	if _, err := ParseSource(parts[0]); err != nil {
		return NewError(CodeInvalidTransactionKey,
			"transaction_key %q must start with a valid source (payments|payouts|transfers|fx|fees|ops)", key)
	}
	return nil
}

func isKeyChar(r rune) bool {
	switch {
	case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
		return true
	case r == '_' || r == '-' || r == '.':
		return true
	}
	return false
}

// ValidateTransaction applies every format-level invariant that does not
// need account data. The account-level invariants — per-currency balance
// (#1), wallet non-negativity (#2), reversal pairing (#4) — are checked by
// the store under the account row locks (and again by SQL triggers).
func ValidateTransaction(tx Transaction) error {
	if _, err := ParseSource(string(tx.Source)); err != nil {
		return err
	}
	if err := ValidateTransactionKey(tx.TransactionKey); err != nil {
		return err
	}
	// The key embeds its source (source:ref[:subtype]) and must agree with
	// the entry's source — a replay with a different source cannot even
	// address the original key.
	if !strings.HasPrefix(tx.TransactionKey, string(tx.Source)+":") {
		return NewError(CodeTransactionKeySourceMismatch,
			"transaction_key %q must start with source %q", tx.TransactionKey, tx.Source)
	}
	if !ValidUUID(tx.SourceRef) {
		return NewError(CodeInvalidUUID, "source_ref %q is not a UUID", tx.SourceRef)
	}
	if _, err := ParseEntryType(string(tx.EntryType)); err != nil {
		return err
	}
	// Reversal pairing (invariant #4): reversal entries must reference the
	// prior entry they compensate; no other entry type may.
	if tx.EntryType == EntryTypeReversal {
		if !ValidUUID(tx.ReversesEntryID) {
			return NewError(CodeInvalidUUID, "reversal entries must reference the entry they reverse")
		}
	} else if tx.ReversesEntryID != "" {
		return NewError(CodeInvalidEntryType, "reverses_entry_id is only allowed on reversal entries")
	}
	if len(tx.Postings) < 2 {
		return NewError(CodeTooFewPostings,
			"a journal entry needs at least 2 postings (a balanced currency group cannot have fewer), got %d", len(tx.Postings))
	}
	if len(tx.Postings) > MaxPostingsPerEntry {
		return NewError(CodeTooManyPostings,
			"a journal entry supports at most %d postings, got %d", MaxPostingsPerEntry, len(tx.Postings))
	}
	for i, l := range tx.Postings {
		if err := l.Validate(); err != nil {
			return fmt.Errorf("posting %d: %w", i, err)
		}
	}
	if err := ValidateReason(tx.Reason); err != nil {
		return err
	}
	if tx.OperatorID != "" && !ValidUUID(tx.OperatorID) {
		return NewError(CodeInvalidUUID, "operator_id %q is not a UUID", tx.OperatorID)
	}
	return nil
}

// ValidateReason bounds the free-text reason. It may be empty for automated
// entries; reversals require one (checked in ReversalRequest.Validate).
func ValidateReason(reason string) error {
	if len(reason) > 500 {
		return NewError(CodeInvalidReason, "reason must be at most 500 characters, got %d", len(reason))
	}
	return nil
}
