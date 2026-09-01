package storage

import (
	"context"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/domain"
)

// FakeStore is an in-memory domain.Store used by tests and the dev-only
// LEDGER_STORE=memory mode. It mirrors the PostgresStore semantics exactly:
//
//   - one mutex stands in for the FOR UPDATE account row locks, acquired in
//     LockOrder (deadlock-free ordering) — LastLockOrder exposes the order
//     for tests;
//   - idempotent replays by (source, transaction_key) with payload conflict
//     detection;
//   - append-only entries and postings;
//   - the same domain invariant checks, evaluated inside the critical
//     section (per-currency balance, wallet non-negativity, reversal
//     pairing, double-reversal rejection).
type FakeStore struct {
	mu sync.Mutex

	accountsByID   map[string]domain.Account
	accountsByCode map[string]domain.Account
	entries        map[string]domain.EntryDetail // by entry id — append-only
	entryByKey     map[string]string             // source + "\x00" + transaction_key → entry id
	reversalOf     map[string]string             // original entry id → reversal entry id
	postings       []domain.Posting              // global, append-only, id-ordered
	nextPostingID  int64
	clock          time.Time // deterministic monotonic timestamps
	lastLock       []string  // account ids locked by the last InsertTransaction
}

// NewFakeStore returns an empty in-memory store.
func NewFakeStore() *FakeStore {
	return &FakeStore{
		accountsByID:   map[string]domain.Account{},
		accountsByCode: map[string]domain.Account{},
		entries:        map[string]domain.EntryDetail{},
		entryByKey:     map[string]string{},
		reversalOf:     map[string]string{},
		clock:          time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC),
	}
}

// Compile-time interface conformance.
var _ domain.Store = (*FakeStore)(nil)

func (f *FakeStore) Ping(context.Context) error { return nil }
func (f *FakeStore) Close() error               { return nil }

// LastLockOrder returns the account ids locked by the most recent
// InsertTransaction, in acquisition order (for lock-ordering tests).
func (f *FakeStore) LastLockOrder() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.lastLock...)
}

// Balances returns a copy of all current account balances
// (credits − debits). Test helper.
func (f *FakeStore) Balances() map[string]int64 {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.balancesLocked(allAccountIDs(f.accountsByID))
}

// EntryCount returns the number of journal entries. Test helper.
func (f *FakeStore) EntryCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.entries)
}

func (f *FakeStore) now() time.Time {
	f.clock = f.clock.Add(time.Millisecond)
	return f.clock
}

func idempotencyKey(source domain.Source, transactionKey string) string {
	return string(source) + "\x00" + transactionKey
}

func cloneDetail(d domain.EntryDetail) domain.EntryDetail {
	out := d
	out.Postings = append([]domain.Posting(nil), d.Postings...)
	return out
}

func allAccountIDs(m map[string]domain.Account) []string {
	ids := make([]string, 0, len(m))
	for id := range m {
		ids = append(ids, id)
	}
	return ids
}

// InsertTransaction mirrors PostgresStore.InsertTransaction: see the
// Store interface comment for the full contract.
func (f *FakeStore) InsertTransaction(_ context.Context, tx domain.Transaction) (domain.PostedTransaction, error) {
	// Format-level validation first — the postgres path re-validates the
	// same rules via the service layer and SQL CHECK constraints.
	if err := domain.ValidateTransaction(tx); err != nil {
		return domain.PostedTransaction{}, err
	}

	f.mu.Lock()
	defer f.mu.Unlock()

	// Invariant #3: idempotent replay of (source, transaction_key).
	if id, ok := f.entryByKey[idempotencyKey(tx.Source, tx.TransactionKey)]; ok {
		stored := f.entries[id]
		if err := domain.CheckReplayPayload(stored, tx); err != nil {
			return domain.PostedTransaction{}, err
		}
		return domain.PostedTransaction{EntryDetail: cloneDetail(stored), Replay: true}, nil
	}

	// ---- critical section: the FOR UPDATE section of the postgres path ----
	// Lock (and thereby read) the involved accounts in deadlock-free order.
	ids := LockOrder(legAccountIDs(tx.Postings))
	f.lastLock = append([]string(nil), ids...)

	accounts := map[string]domain.Account{}
	currencies := map[string]domain.Currency{}
	for _, id := range ids {
		a, ok := f.accountsByID[id]
		if !ok {
			return domain.PostedTransaction{}, domain.NewError(domain.CodeAccountNotFound, "account %s not found", id)
		}
		if !domain.PostingAllowedOnAccount(tx.EntryType, a.Status) {
			return domain.PostedTransaction{}, domain.NewError(domain.CodeAccountInactive,
				"account %s (%s) is %s and does not accept %s entries",
				a.Code, id, a.Status, tx.EntryType)
		}
		accounts[id] = a
		currencies[id] = a.Currency
	}

	// Invariant #4: reversal pairing + double-reversal rejection.
	if tx.ReversesEntryID != "" {
		orig, ok := f.entries[tx.ReversesEntryID]
		if !ok {
			return domain.PostedTransaction{}, domain.NewError(domain.CodeEntryNotFound,
				"entry %s not found", tx.ReversesEntryID)
		}
		if orig.Entry.EntryType == domain.EntryTypeReversal {
			return domain.PostedTransaction{}, domain.NewError(domain.CodeReversalOfReversal,
				"entry %s is itself a reversal; post an adjustment entry instead", tx.ReversesEntryID)
		}
		if _, already := f.reversalOf[tx.ReversesEntryID]; already {
			return domain.PostedTransaction{}, domain.NewError(domain.CodeAlreadyReversed,
				"entry %s is already reversed", tx.ReversesEntryID)
		}
		if !domain.IsInverse(domain.LegsFromPostings(orig.Postings), tx.Postings) {
			return domain.PostedTransaction{}, domain.NewError(domain.CodeReversalMismatch,
				"reversal legs are not the exact inverse of entry %s", tx.ReversesEntryID)
		}
	}

	// Invariant #1: per-currency balance.
	if err := domain.ValidateBalancedPerCurrency(tx.Postings, currencies); err != nil {
		return domain.PostedTransaction{}, err
	}
	// Invariant #2: wallet balance ≥ 0 after applying the legs.
	if err := domain.ValidateWalletNonNegative(tx.Postings, accounts, f.balancesLocked(ids)); err != nil {
		return domain.PostedTransaction{}, err
	}

	// Append-only insert.
	entryID, err := newUUIDv7()
	if err != nil {
		return domain.PostedTransaction{}, err
	}
	entry := domain.JournalEntry{
		ID:              entryID,
		TransactionKey:  tx.TransactionKey,
		Source:          tx.Source,
		SourceRef:       tx.SourceRef,
		EntryType:       tx.EntryType,
		ReversesEntryID: tx.ReversesEntryID,
		Reason:          tx.Reason,
		OperatorID:      tx.OperatorID,
		CreatedAt:       f.now(),
	}
	detail := domain.EntryDetail{Entry: entry, Postings: make([]domain.Posting, len(tx.Postings))}
	for i, l := range tx.Postings {
		f.nextPostingID++
		detail.Postings[i] = domain.Posting{
			ID:        f.nextPostingID,
			EntryID:   entryID,
			AccountID: l.AccountID,
			Debit:     l.Debit,
			Credit:    l.Credit,
		}
		f.postings = append(f.postings, detail.Postings[i])
	}
	f.entries[entryID] = detail
	f.entryByKey[idempotencyKey(tx.Source, tx.TransactionKey)] = entryID
	if tx.ReversesEntryID != "" {
		f.reversalOf[tx.ReversesEntryID] = entryID
	}
	return domain.PostedTransaction{EntryDetail: cloneDetail(detail)}, nil
}

// balancesLocked computes current balances (credits − debits) for the given
// accounts. The caller must hold f.mu — the equivalent of holding the
// account row locks in postgres.
func (f *FakeStore) balancesLocked(ids []string) map[string]int64 {
	balances := make(map[string]int64, len(ids))
	for _, id := range ids {
		balances[id] = 0
	}
	for _, p := range f.postings {
		if _, ok := balances[p.AccountID]; ok {
			balances[p.AccountID] += p.Credit - p.Debit
		}
	}
	return balances
}

func (f *FakeStore) GetEntry(_ context.Context, entryID string) (domain.EntryDetail, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	d, ok := f.entries[entryID]
	if !ok {
		return domain.EntryDetail{}, domain.NewError(domain.CodeEntryNotFound, "entry %s not found", entryID)
	}
	return cloneDetail(d), nil
}

func (f *FakeStore) GetEntryByTransactionKey(_ context.Context, source domain.Source, transactionKey string) (domain.EntryDetail, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	id, ok := f.entryByKey[idempotencyKey(source, transactionKey)]
	if !ok {
		return domain.EntryDetail{}, domain.NewError(domain.CodeNotFound,
			"no entry for transaction key %q", transactionKey)
	}
	return cloneDetail(f.entries[id]), nil
}

func (f *FakeStore) GetReversalByOriginalID(_ context.Context, originalEntryID string) (domain.EntryDetail, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	revID, ok := f.reversalOf[originalEntryID]
	if !ok {
		return domain.EntryDetail{}, domain.NewError(domain.CodeNotFound,
			"entry %s has no reversal", originalEntryID)
	}
	return cloneDetail(f.entries[revID]), nil
}

func (f *FakeStore) GetAccount(_ context.Context, accountID string) (domain.Account, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	a, ok := f.accountsByID[accountID]
	if !ok {
		return domain.Account{}, domain.NewError(domain.CodeAccountNotFound, "account %s not found", accountID)
	}
	return a, nil
}

func (f *FakeStore) EnsureAccount(_ context.Context, acct domain.Account) (domain.Account, bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if existing, ok := f.accountsByCode[acct.Code]; ok {
		if existing.Type != acct.Type || existing.Currency != acct.Currency ||
			existing.OwnerPrincipal != acct.OwnerPrincipal {
			return domain.Account{}, false, domain.NewError(domain.CodeAccountConflict,
				"account code %q exists with different attributes", acct.Code)
		}
		return existing, false, nil
	}
	a := acct
	if a.ID == "" {
		id, err := newUUIDv7()
		if err != nil {
			return domain.Account{}, false, err
		}
		a.ID = id
	}
	if a.Status == "" {
		a.Status = domain.AccountStatusActive
	}
	a.CreatedAt = f.now()
	f.accountsByID[a.ID] = a
	f.accountsByCode[a.Code] = a
	return a, true, nil
}

func (f *FakeStore) GetStatement(_ context.Context, q domain.StatementQuery) (domain.Statement, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	acc, ok := f.accountsByID[q.AccountID]
	if !ok {
		return domain.Statement{}, domain.NewError(domain.CodeAccountNotFound,
			"account %s not found", q.AccountID)
	}
	limit := q.Limit
	if limit <= 0 {
		limit = domain.DefaultStatementLimit
	}

	balanceBeforeCursor := int64(0)
	lines := make([]domain.StatementLine, 0, limit)
	var nextCursor int64
	hasMore := false
	for _, p := range f.postings { // global slice is in ascending id order
		if p.AccountID != q.AccountID {
			continue
		}
		if p.ID <= q.Cursor {
			balanceBeforeCursor += p.Credit - p.Debit
			continue
		}
		if len(lines) == limit {
			// There is at least one more matching posting after the page.
			hasMore = true
			break
		}
		balanceBeforeCursor += p.Credit - p.Debit
		e := f.entries[p.EntryID].Entry
		lines = append(lines, domain.StatementLine{
			PostingID:      p.ID,
			EntryID:        p.EntryID,
			TransactionKey: e.TransactionKey,
			Source:         e.Source,
			EntryType:      e.EntryType,
			Reason:         e.Reason,
			CreatedAt:      e.CreatedAt,
			Debit:          p.Debit,
			Credit:         p.Credit,
			BalanceAfter:   balanceBeforeCursor,
		})
		nextCursor = p.ID
	}

	return domain.Statement{
		Account:      acc,
		Lines:        lines,
		NextCursor:   nextCursor,
		HasMore:      hasMore,
		BalanceMinor: f.balancesLocked([]string{q.AccountID})[q.AccountID],
	}, nil
}

// newUUIDv7 generates a time-ordered UUID v7 (repo convention).
func newUUIDv7() (string, error) {
	id, err := uuid.NewV7()
	if err != nil {
		return "", domain.NewError(domain.CodeInternal, "generate uuid v7: %v", err)
	}
	return id.String(), nil
}
