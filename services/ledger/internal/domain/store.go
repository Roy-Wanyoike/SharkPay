package domain

import "context"

// Store is the persistence contract for the ledger. There are two
// implementations: internal/storage.PostgresStore (production) and
// internal/storage.FakeStore (tests / dev memory mode).
//
// Concurrency & correctness contract (ARCHITECTURE §5):
//
//   - InsertTransaction persists entry + postings atomically (one DB
//     transaction).
//   - It locks every involved account row with SELECT ... FOR UPDATE in
//     ascending account-id order (storage.LockOrder) BEFORE validating
//     balance and non-negativity — the deadlock-free acquisition order.
//   - Idempotency (invariant #3): a duplicate (source, transaction_key)
//     returns the original entry with Replay=true, never an error; a
//     duplicate key with a different payload returns idempotency_conflict.
//   - It enforces invariants #1 (per-currency balance), #2 (wallet
//     non-negativity) and #4 (reversal pairing, double-reversal rejection)
//     under those locks. SQL triggers assert the same at commit (belt &
//     suspenders).
//   - journal_entries and postings are append-only: no implementation may
//     UPDATE or DELETE them (invariant #5); corrections are new entries.
type Store interface {
	// InsertTransaction validates and atomically persists tx, or replays
	// the existing entry for its (source, transaction_key).
	InsertTransaction(ctx context.Context, tx Transaction) (PostedTransaction, error)

	// GetEntry returns one journal entry with its postings
	// (ErrEntryNotFound when absent).
	GetEntry(ctx context.Context, entryID string) (EntryDetail, error)

	// GetEntryByTransactionKey returns the entry stored under
	// (source, transaction_key), or an error matching ErrNotFound.
	GetEntryByTransactionKey(ctx context.Context, source Source, transactionKey string) (EntryDetail, error)

	// GetReversalByOriginalID returns the reversal entry of
	// originalEntryID, or an error matching ErrNotFound.
	GetReversalByOriginalID(ctx context.Context, originalEntryID string) (EntryDetail, error)

	// GetAccount returns one account by id (ErrAccountNotFound).
	GetAccount(ctx context.Context, accountID string) (Account, error)

	// EnsureAccount creates acct, or returns the existing account with the
	// same code (created=false). A code collision with different type /
	// currency / owner returns account_conflict.
	EnsureAccount(ctx context.Context, acct Account) (Account, bool, error)

	// GetStatement returns one statement page (account_not_found when the
	// account does not exist).
	GetStatement(ctx context.Context, q StatementQuery) (Statement, error)

	// Ping checks connectivity (readiness probes).
	Ping(ctx context.Context) error

	// Close releases resources.
	Close() error
}

// EntryDetail is a journal entry plus its postings.
type EntryDetail struct {
	Entry    JournalEntry
	Postings []Posting
}

// PostedTransaction is the result of InsertTransaction. Replay is true when
// the entry already existed under the same (source, transaction_key) and
// the original was returned unchanged (idempotent replay).
type PostedTransaction struct {
	EntryDetail
	Replay bool
}
