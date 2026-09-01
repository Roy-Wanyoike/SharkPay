// Package service orchestrates ledger postings: request validation,
// idempotent replay, reversal construction, and statement reads. All writes
// funnel through domain.Store.InsertTransaction, which owns the locking
// contract (SELECT ... FOR UPDATE in account-id order) and the atomicity
// boundary. The service itself stays free of storage concerns.
package service

import (
	"context"
	"errors"
	"strconv"

	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/domain"
)

// Service is the ledger application service. It is safe for concurrent use.
type Service struct {
	store domain.Store
}

// New builds a Service over the given store.
func New(store domain.Store) *Service {
	return &Service{store: store}
}

// Ping passes through to the store (readiness probes).
func (s *Service) Ping(ctx context.Context) error {
	return s.store.Ping(ctx)
}

// PostRequest is the payload of PostTransaction.
type PostRequest struct {
	TransactionKey string
	Source         domain.Source
	SourceRef      string
	EntryType      domain.EntryType
	Reason         string
	OperatorID     string
	Postings       []domain.Leg
}

// PostTransaction validates and atomically persists a journal entry.
//
//   - Format invariants are checked here (transaction-key format
//     source:ref[:subtype], source/source_ref/entry_type/leg validation).
//   - The store checks the money invariants under the account locks:
//     per-currency balance, wallet non-negativity, account status.
//   - A duplicate (source, transaction_key) returns the ORIGINAL entry with
//     Replay=true (never an error); a different payload under the same key
//     is an idempotency_conflict.
//
// Reversal entries are rejected here: they are only created through
// ReverseTransaction, which derives and verifies the inverse legs — so
// invariant #4 cannot be bypassed.
func (s *Service) PostTransaction(ctx context.Context, r PostRequest) (domain.PostedTransaction, error) {
	tx := domain.Transaction{
		TransactionKey: r.TransactionKey,
		Source:         r.Source,
		SourceRef:      r.SourceRef,
		EntryType:      r.EntryType,
		Reason:         r.Reason,
		OperatorID:     r.OperatorID,
		Postings:       r.Postings,
	}
	if err := domain.ValidateTransaction(tx); err != nil {
		return domain.PostedTransaction{}, err
	}
	if tx.EntryType == domain.EntryTypeReversal {
		return domain.PostedTransaction{}, domain.NewError(domain.CodeInvalidEntryType,
			`entry_type "reversal" must be created via POST /internal/transactions/{id}/reverse`)
	}
	return s.store.InsertTransaction(ctx, tx)
}

// ReverseTransaction creates the compensating entry for the entry with the
// given id (FR-302: compensation entry only, reason captured).
//
// The reversal's transaction key is deterministic (ops:rev:<entry id>), so a
// retried reversal is an idempotent replay of the original reversal entry.
// Any DISTINCT second reversal of the same entry (fresh key, direct store
// use) is rejected by the store's double-reversal guard and the partial
// unique index on reverses_entry_id.
func (s *Service) ReverseTransaction(ctx context.Context, r domain.ReversalRequest) (domain.PostedTransaction, error) {
	if err := r.Validate(); err != nil {
		return domain.PostedTransaction{}, err
	}

	// Idempotent fast path: a retried reversal replays its own entry.
	if existing, err := s.store.GetEntryByTransactionKey(ctx, domain.SourceOps, domain.ReversalTransactionKey(r.EntryID)); err == nil {
		if existing.Entry.EntryType == domain.EntryTypeReversal && existing.Entry.ReversesEntryID == r.EntryID {
			return domain.PostedTransaction{EntryDetail: existing, Replay: true}, nil
		}
		// The deterministic reversal key is occupied by a non-reversal entry.
		return domain.PostedTransaction{}, domain.NewError(domain.CodeIdempotencyConflict,
			"transaction key %q is reserved for reversals", domain.ReversalTransactionKey(r.EntryID))
	} else if !errors.Is(err, domain.ErrNotFound) {
		return domain.PostedTransaction{}, err
	}

	// Load the entry to reverse.
	orig, err := s.store.GetEntry(ctx, r.EntryID)
	if err != nil {
		return domain.PostedTransaction{}, err
	}
	if orig.Entry.EntryType == domain.EntryTypeReversal {
		return domain.PostedTransaction{}, domain.NewError(domain.CodeReversalOfReversal,
			"entry %s is itself a reversal; post an adjustment entry instead", r.EntryID)
	}
	if _, err := s.store.GetReversalByOriginalID(ctx, r.EntryID); err == nil {
		return domain.PostedTransaction{}, domain.NewError(domain.CodeAlreadyReversed,
			"entry %s is already reversed", r.EntryID)
	} else if !errors.Is(err, domain.ErrNotFound) {
		return domain.PostedTransaction{}, err
	}

	// Build the compensating transaction: exact inverse legs, same accounts.
	tx, err := domain.BuildReversal(orig, r.Reason, r.OperatorID)
	if err != nil {
		return domain.PostedTransaction{}, err
	}
	return s.store.InsertTransaction(ctx, tx)
}

// GetStatement returns one page of the account's postings in ascending
// posting order with running balances, plus the account's final balance.
// cursor is the opaque next_cursor from a previous page (a decimal posting
// id); empty starts from the beginning. limit defaults to 50, max 100.
func (s *Service) GetStatement(ctx context.Context, accountID, cursor string, limit int) (domain.Statement, error) {
	if !domain.ValidUUID(accountID) {
		return domain.Statement{}, domain.NewError(domain.CodeInvalidUUID, "account id %q is not a UUID", accountID)
	}
	var cur int64
	if cursor != "" {
		n, err := strconv.ParseUint(cursor, 10, 63)
		if err != nil {
			return domain.Statement{}, domain.NewError(domain.CodeInvalidCursor, "invalid cursor %q", cursor)
		}
		cur = int64(n)
	}
	if limit == 0 {
		limit = domain.DefaultStatementLimit
	}
	if limit < 1 || limit > domain.MaxStatementLimit {
		return domain.Statement{}, domain.NewError(domain.CodeInvalidLimit,
			"limit must be between 1 and %d, got %d", domain.MaxStatementLimit, limit)
	}
	return s.store.GetStatement(ctx, domain.StatementQuery{AccountID: accountID, Cursor: cur, Limit: limit})
}

// EnsureAccount provisions an account (idempotent by code). Status defaults
// to active; wallet accounts must carry an owner_principal.
func (s *Service) EnsureAccount(ctx context.Context, a domain.Account) (domain.Account, bool, error) {
	if a.Status == "" {
		a.Status = domain.AccountStatusActive
	}
	if err := a.Validate(); err != nil {
		return domain.Account{}, false, err
	}
	return s.store.EnsureAccount(ctx, a)
}
