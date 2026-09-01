// Package storage provides the ledger persistence layer: a PostgreSQL
// implementation of domain.Store (schema `ledger`, migrations/001_ledger_init.sql)
// plus an in-memory FakeStore used by tests and the dev-only memory mode.
package storage

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/domain"
)

// PostgresStore implements domain.Store on PostgreSQL. It is compiled and
// exercised only against a live database (CI integration job — docker-compose
// Postgres); all unit tests run against FakeStore, which mirrors this flow.
type PostgresStore struct {
	pool *pgxpool.Pool
}

// Compile-time interface conformance.
var _ domain.Store = (*PostgresStore)(nil)

// PostgreSQL error codes used below.
const (
	pgUniqueViolation     = "23505" // unique_violation
	pgCheckViolation      = "23514" // check_violation (our invariant triggers)
	pgForeignKeyViolation = "23503" // foreign_key_violation
)

// queryer abstracts query execution over both the pool and an open
// transaction, so read helpers can serve the idempotency fast path and the
// locked critical section alike. *pgxpool.Pool and pgx.Tx both satisfy it.
type queryer interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
}

// NewPostgresStore opens a connection pool and verifies connectivity.
// databaseURL is the standard DATABASE_URL (postgres://user:pass@host/db).
func NewPostgresStore(ctx context.Context, databaseURL string) (*PostgresStore, error) {
	cfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse DATABASE_URL: %w", err)
	}
	cfg.MaxConns = 16
	cfg.MaxConnLifetime = time.Hour
	cfg.MaxConnIdleTime = 10 * time.Minute
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("open postgres pool: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping postgres: %w", err)
	}
	return &PostgresStore{pool: pool}, nil
}

func (s *PostgresStore) Close() error {
	s.pool.Close()
	return nil
}

func (s *PostgresStore) Ping(ctx context.Context) error {
	return s.pool.Ping(ctx)
}

// InsertTransaction atomically persists tx following the locking contract
// (ARCHITECTURE §5):
//
//  1. Idempotency fast path: if (source, transaction_key) already exists,
//     return the original entry (Replay=true); a differing payload for the
//     same key is an idempotency_conflict.
//  2. BEGIN.
//  3. Lock every involved account row FOR UPDATE in ascending account-id
//     order (LockOrder). Under READ COMMITTED a multi-row
//     "ORDER BY ... FOR UPDATE" can lock rows in scan order rather than sort
//     order (PostgreSQL explicit-locking caveat), so rows are locked one
//     statement at a time, strictly in sorted order — deadlock-free.
//  4. For reversal entries: lock the original entry row (serialization point
//     for concurrent reversals), verify it exists, is not itself a reversal,
//     has no reversal yet, and that tx posts the exact inverse legs. The
//     partial unique index on reverses_entry_id is the race-free backstop.
//  5. Under the locks, re-check account status, per-currency balance
//     (invariant #1) and wallet non-negativity (invariant #2) — the same
//     rules the deferred constraint triggers assert at COMMIT.
//  6. INSERT journal entry + postings (append-only).
//  7. COMMIT. A unique violation raced on transaction_key is retried as a
//     replay; one on reverses_entry_id maps to already_reversed; deferred
//     trigger violations map to their domain errors.
func (s *PostgresStore) InsertTransaction(ctx context.Context, tx domain.Transaction) (domain.PostedTransaction, error) {
	if err := domain.ValidateTransaction(tx); err != nil {
		return domain.PostedTransaction{}, err
	}

	// (1) Idempotent fast path (plain read, no lock).
	if existing, found, err := s.entryByKey(ctx, s.pool, tx.Source, tx.TransactionKey); err != nil {
		return domain.PostedTransaction{}, err
	} else if found {
		if err := domain.CheckReplayPayload(existing, tx); err != nil {
			return domain.PostedTransaction{}, err
		}
		return domain.PostedTransaction{EntryDetail: existing, Replay: true}, nil
	}

	// (2) BEGIN.
	conn, err := s.pool.Begin(ctx)
	if err != nil {
		return domain.PostedTransaction{}, fmt.Errorf("begin: %w", err)
	}
	defer conn.Rollback(ctx) // no-op after successful commit

	// (3) Lock accounts in the deadlock-free order.
	accountIDs := LockOrder(legAccountIDs(tx.Postings))
	accounts, currencies, err := s.lockAccounts(ctx, conn, accountIDs, tx.EntryType)
	if err != nil {
		return domain.PostedTransaction{}, err
	}

	// (4) Reversal pairing under lock.
	if tx.ReversesEntryID != "" {
		orig, err := s.entryForUpdate(ctx, conn, tx.ReversesEntryID)
		if err != nil {
			return domain.PostedTransaction{}, err
		}
		if orig.Entry.EntryType == domain.EntryTypeReversal {
			return domain.PostedTransaction{}, domain.NewError(domain.CodeReversalOfReversal,
				"entry %s is itself a reversal; post an adjustment entry instead", tx.ReversesEntryID)
		}
		reversed, err := s.reversalExists(ctx, conn, tx.ReversesEntryID)
		if err != nil {
			return domain.PostedTransaction{}, err
		}
		if reversed {
			return domain.PostedTransaction{}, domain.NewError(domain.CodeAlreadyReversed,
				"entry %s is already reversed", tx.ReversesEntryID)
		}
		if !domain.IsInverse(domain.LegsFromPostings(orig.Postings), tx.Postings) {
			return domain.PostedTransaction{}, domain.NewError(domain.CodeReversalMismatch,
				"reversal legs are not the exact inverse of entry %s", tx.ReversesEntryID)
		}
	}

	// (5) Invariants under the account locks.
	if err := domain.ValidateBalancedPerCurrency(tx.Postings, currencies); err != nil {
		return domain.PostedTransaction{}, err
	}
	balances, err := s.accountBalances(ctx, conn, accountIDs)
	if err != nil {
		return domain.PostedTransaction{}, err
	}
	if err := domain.ValidateWalletNonNegative(tx.Postings, accounts, balances); err != nil {
		return domain.PostedTransaction{}, err
	}

	// (6) Append-only inserts.
	entryID, err := newUUIDv7()
	if err != nil {
		return domain.PostedTransaction{}, err
	}
	detail, err := s.insertEntryAndPostings(ctx, conn, entryID, tx)
	if err != nil {
		// Lost an idempotency race: another transaction committed the same
		// (source, transaction_key) between our fast path and the insert.
		if isUniqueViolationOn(err, "transaction_key") {
			_ = conn.Rollback(ctx)
			if existing, found, rerr := s.entryByKey(ctx, s.pool, tx.Source, tx.TransactionKey); rerr == nil && found {
				if cerr := domain.CheckReplayPayload(existing, tx); cerr != nil {
					return domain.PostedTransaction{}, cerr
				}
				return domain.PostedTransaction{EntryDetail: existing, Replay: true}, nil
			}
		}
		if isUniqueViolationOn(err, "reverses_entry_id") {
			return domain.PostedTransaction{}, domain.NewError(domain.CodeAlreadyReversed,
				"entry %s is already reversed", tx.ReversesEntryID)
		}
		return domain.PostedTransaction{}, mapPgError(err)
	}

	// (7) COMMIT — deferred invariant triggers assert here (belt & suspenders).
	if err := conn.Commit(ctx); err != nil {
		return domain.PostedTransaction{}, mapPgError(err)
	}
	return domain.PostedTransaction{EntryDetail: detail}, nil
}

// lockAccounts locks and loads each account row with SELECT ... FOR UPDATE,
// one statement per account, strictly in the given (sorted) order.
func (s *PostgresStore) lockAccounts(ctx context.Context, conn pgx.Tx, ids []string, entryType domain.EntryType) (map[string]domain.Account, map[string]domain.Currency, error) {
	accounts := make(map[string]domain.Account, len(ids))
	currencies := make(map[string]domain.Currency, len(ids))
	for _, id := range ids {
		var (
			code, typ, cur, owner, status string
		)
		err := conn.QueryRow(ctx, `
                        SELECT code, type, currency, COALESCE(owner_principal::text, ''), status
                        FROM ledger.accounts WHERE id = $1::uuid FOR UPDATE`, id,
		).Scan(&code, &typ, &cur, &owner, &status)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil, domain.NewError(domain.CodeAccountNotFound, "account %s not found", id)
		}
		if err != nil {
			return nil, nil, mapPgError(err)
		}
		if !domain.PostingAllowedOnAccount(entryType, domain.AccountStatus(status)) {
			return nil, nil, domain.NewError(domain.CodeAccountInactive,
				"account %s (%s) is %s and does not accept %s entries", code, id, status, entryType)
		}
		accounts[id] = domain.Account{
			ID:             id,
			Code:           code,
			Type:           domain.AccountType(typ),
			Currency:       domain.Currency(cur),
			OwnerPrincipal: owner,
			Status:         domain.AccountStatus(status),
		}
		currencies[id] = domain.Currency(cur)
	}
	return accounts, currencies, nil
}

// accountBalances computes current balances (credits − debits) for ids.
// Safe under concurrency: the caller holds FOR UPDATE locks on all these
// account rows, so no concurrent InsertTransaction can insert postings for
// them before this transaction commits.
func (s *PostgresStore) accountBalances(ctx context.Context, conn pgx.Tx, ids []string) (map[string]int64, error) {
	balances := make(map[string]int64, len(ids))
	rows, err := conn.Query(ctx, `
                SELECT account_id::text, COALESCE(SUM(credit - debit), 0)
                FROM ledger.postings WHERE account_id = ANY($1::uuid[])
                GROUP BY account_id`, ids,
	)
	if err != nil {
		return nil, mapPgError(err)
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		var bal int64
		if err := rows.Scan(&id, &bal); err != nil {
			return nil, mapPgError(err)
		}
		balances[id] = bal
	}
	if err := rows.Err(); err != nil {
		return nil, mapPgError(err)
	}
	return balances, nil
}

// entryForUpdate reads the entry row with a row lock. Journal rows are
// immutable, so the lock is purely a serialization point for concurrent
// reversals of the same entry (the partial unique index is the backstop).
func (s *PostgresStore) entryForUpdate(ctx context.Context, conn pgx.Tx, entryID string) (domain.EntryDetail, error) {
	var (
		id, transactionKey, source, sourceRef, entryType string
		reverses, reason, operator                       string
		createdAt                                        time.Time
	)
	err := conn.QueryRow(ctx, `
                SELECT id::text, transaction_key, source, source_ref::text, entry_type,
                       COALESCE(reverses_entry_id::text, ''), COALESCE(reason, ''), COALESCE(operator_id::text, ''),
                       created_at
                FROM ledger.journal_entries WHERE id = $1::uuid FOR UPDATE`, entryID,
	).Scan(&id, &transactionKey, &source, &sourceRef, &entryType, &reverses, &reason, &operator, &createdAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.EntryDetail{}, domain.NewError(domain.CodeEntryNotFound, "entry %s not found", entryID)
	}
	if err != nil {
		return domain.EntryDetail{}, mapPgError(err)
	}
	entry := domain.JournalEntry{
		ID: id, TransactionKey: transactionKey, Source: domain.Source(source),
		SourceRef: sourceRef, EntryType: domain.EntryType(entryType),
		ReversesEntryID: reverses, Reason: reason, OperatorID: operator, CreatedAt: createdAt,
	}
	postings, err := s.postingsForEntry(ctx, conn, entryID)
	if err != nil {
		return domain.EntryDetail{}, err
	}
	return domain.EntryDetail{Entry: entry, Postings: postings}, nil
}

// reversalExists reports whether any entry already reverses originalEntryID.
func (s *PostgresStore) reversalExists(ctx context.Context, conn pgx.Tx, originalEntryID string) (bool, error) {
	var one int
	err := conn.QueryRow(ctx,
		`SELECT 1 FROM ledger.journal_entries WHERE reverses_entry_id = $1::uuid`, originalEntryID,
	).Scan(&one)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, mapPgError(err)
	}
	return true, nil
}

// insertEntryAndPostings writes the journal entry and its postings, then
// reads back the persistence-assigned posting ids (bigserial) in order.
func (s *PostgresStore) insertEntryAndPostings(ctx context.Context, conn pgx.Tx, entryID string, tx domain.Transaction) (domain.EntryDetail, error) {
	_, err := conn.Exec(ctx, `
                INSERT INTO ledger.journal_entries
                        (id, transaction_key, source, source_ref, entry_type, reverses_entry_id, reason, operator_id, created_at)
                VALUES ($1::uuid, $2, $3, $4::uuid, $5, NULLIF($6, '')::uuid, $7, NULLIF($8, '')::uuid, $9)`,
		entryID, tx.TransactionKey, string(tx.Source), tx.SourceRef, string(tx.EntryType),
		tx.ReversesEntryID, tx.Reason, tx.OperatorID, time.Now().UTC(),
	)
	if err != nil {
		return domain.EntryDetail{}, err
	}

	batch := &pgx.Batch{}
	const insertPostingSQL = `
                INSERT INTO ledger.postings (entry_id, account_id, debit, credit)
                VALUES ($1::uuid, $2::uuid, $3, $4)`
	for _, l := range tx.Postings {
		batch.Queue(insertPostingSQL, entryID, l.AccountID, l.Debit, l.Credit)
	}
	br := conn.SendBatch(ctx, batch)
	defer br.Close()
	for range tx.Postings {
		if _, err := br.Exec(); err != nil {
			return domain.EntryDetail{}, err
		}
	}

	postings, err := s.postingsForEntry(ctx, conn, entryID)
	if err != nil {
		return domain.EntryDetail{}, err
	}
	return domain.EntryDetail{
		Entry: domain.JournalEntry{
			ID:              entryID,
			TransactionKey:  tx.TransactionKey,
			Source:          tx.Source,
			SourceRef:       tx.SourceRef,
			EntryType:       tx.EntryType,
			ReversesEntryID: tx.ReversesEntryID,
			Reason:          tx.Reason,
			OperatorID:      tx.OperatorID,
		},
		Postings: postings,
	}, nil
}

func (s *PostgresStore) postingsForEntry(ctx context.Context, q queryer, entryID string) ([]domain.Posting, error) {
	rows, err := q.Query(ctx, `
                SELECT id, entry_id::text, account_id::text, debit, credit
                FROM ledger.postings WHERE entry_id = $1::uuid ORDER BY id`, entryID)
	if err != nil {
		return nil, mapPgError(err)
	}
	defer rows.Close()
	postings := make([]domain.Posting, 0, 4)
	for rows.Next() {
		var p domain.Posting
		if err := rows.Scan(&p.ID, &p.EntryID, &p.AccountID, &p.Debit, &p.Credit); err != nil {
			return nil, mapPgError(err)
		}
		postings = append(postings, p)
	}
	if err := rows.Err(); err != nil {
		return nil, mapPgError(err)
	}
	return postings, nil
}

// entryByKey is the idempotency lookup: (source, transaction_key) → entry.
func (s *PostgresStore) entryByKey(ctx context.Context, q queryer, source domain.Source, transactionKey string) (domain.EntryDetail, bool, error) {
	var id string
	err := q.QueryRow(ctx, `
                SELECT id::text FROM ledger.journal_entries
                WHERE source = $1 AND transaction_key = $2`, string(source), transactionKey,
	).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.EntryDetail{}, false, nil
	}
	if err != nil {
		return domain.EntryDetail{}, false, mapPgError(err)
	}
	entry, err := s.getEntry(ctx, q, id)
	if err != nil {
		return domain.EntryDetail{}, false, err
	}
	return entry, true, nil
}

func (s *PostgresStore) getEntry(ctx context.Context, q queryer, entryID string) (domain.EntryDetail, error) {
	var (
		id, transactionKey, source, sourceRef, entryType string
		reverses, reason, operator                       string
		createdAt                                        time.Time
	)
	err := q.QueryRow(ctx, `
                SELECT id::text, transaction_key, source, source_ref::text, entry_type,
                       COALESCE(reverses_entry_id::text, ''), COALESCE(reason, ''), COALESCE(operator_id::text, ''),
                       created_at
                FROM ledger.journal_entries WHERE id = $1::uuid`, entryID,
	).Scan(&id, &transactionKey, &source, &sourceRef, &entryType, &reverses, &reason, &operator, &createdAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.EntryDetail{}, domain.NewError(domain.CodeEntryNotFound, "entry %s not found", entryID)
	}
	if err != nil {
		return domain.EntryDetail{}, mapPgError(err)
	}
	postings, err := s.postingsForEntry(ctx, q, entryID)
	if err != nil {
		return domain.EntryDetail{}, err
	}
	return domain.EntryDetail{
		Entry: domain.JournalEntry{
			ID: id, TransactionKey: transactionKey, Source: domain.Source(source),
			SourceRef: sourceRef, EntryType: domain.EntryType(entryType),
			ReversesEntryID: reverses, Reason: reason, OperatorID: operator, CreatedAt: createdAt,
		},
		Postings: postings,
	}, nil
}

func (s *PostgresStore) GetEntry(ctx context.Context, entryID string) (domain.EntryDetail, error) {
	return s.getEntry(ctx, s.pool, entryID)
}

func (s *PostgresStore) GetEntryByTransactionKey(ctx context.Context, source domain.Source, transactionKey string) (domain.EntryDetail, error) {
	entry, found, err := s.entryByKey(ctx, s.pool, source, transactionKey)
	if err != nil {
		return domain.EntryDetail{}, err
	}
	if !found {
		return domain.EntryDetail{}, domain.NewError(domain.CodeNotFound, "no entry for transaction key %q", transactionKey)
	}
	return entry, nil
}

func (s *PostgresStore) GetReversalByOriginalID(ctx context.Context, originalEntryID string) (domain.EntryDetail, error) {
	var id string
	err := s.pool.QueryRow(ctx, `
                SELECT id::text FROM ledger.journal_entries
                WHERE reverses_entry_id = $1::uuid`, originalEntryID,
	).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.EntryDetail{}, domain.NewError(domain.CodeNotFound, "entry %s has no reversal", originalEntryID)
	}
	if err != nil {
		return domain.EntryDetail{}, mapPgError(err)
	}
	return s.getEntry(ctx, s.pool, id)
}

func (s *PostgresStore) GetAccount(ctx context.Context, accountID string) (domain.Account, error) {
	return s.getAccount(ctx, s.pool, accountID)
}

func (s *PostgresStore) getAccount(ctx context.Context, q queryer, accountID string) (domain.Account, error) {
	var code, typ, cur, owner, status string
	var createdAt time.Time
	err := q.QueryRow(ctx, `
                SELECT code, type, currency, COALESCE(owner_principal::text, ''), status, created_at
                FROM ledger.accounts WHERE id = $1::uuid`, accountID,
	).Scan(&code, &typ, &cur, &owner, &status, &createdAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Account{}, domain.NewError(domain.CodeAccountNotFound, "account %s not found", accountID)
	}
	if err != nil {
		return domain.Account{}, mapPgError(err)
	}
	return domain.Account{
		ID: accountID, Code: code, Type: domain.AccountType(typ),
		Currency: domain.Currency(cur), OwnerPrincipal: owner,
		Status: domain.AccountStatus(status), CreatedAt: createdAt,
	}, nil
}

func (s *PostgresStore) getAccountByCode(ctx context.Context, q queryer, code string) (domain.Account, error) {
	var id, typ, cur, owner, status string
	var createdAt time.Time
	err := q.QueryRow(ctx, `
                SELECT id::text, type, currency, COALESCE(owner_principal::text, ''), status, created_at
                FROM ledger.accounts WHERE code = $1`, code,
	).Scan(&id, &typ, &cur, &owner, &status, &createdAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return domain.Account{}, domain.NewError(domain.CodeAccountNotFound, "account with code %q not found", code)
	}
	if err != nil {
		return domain.Account{}, mapPgError(err)
	}
	return domain.Account{
		ID: id, Code: code, Type: domain.AccountType(typ), Currency: domain.Currency(cur),
		OwnerPrincipal: owner, Status: domain.AccountStatus(status), CreatedAt: createdAt,
	}, nil
}

func (s *PostgresStore) EnsureAccount(ctx context.Context, acct domain.Account) (domain.Account, bool, error) {
	id := acct.ID
	if id == "" {
		var err error
		id, err = newUUIDv7()
		if err != nil {
			return domain.Account{}, false, err
		}
	}
	status := acct.Status
	if status == "" {
		status = domain.AccountStatusActive
	}
	var (
		rID, code, typ, cur, owner, rStatus string
		createdAt                           time.Time
	)
	err := s.pool.QueryRow(ctx, `
                INSERT INTO ledger.accounts (id, code, type, currency, owner_principal, status)
                VALUES ($1::uuid, $2, $3, $4, NULLIF($5, '')::uuid, $6)
                ON CONFLICT (code) DO NOTHING
                RETURNING id::text, code, type, currency, COALESCE(owner_principal::text, ''), status, created_at`,
		id, acct.Code, string(acct.Type), string(acct.Currency), acct.OwnerPrincipal, string(status),
	).Scan(&rID, &code, &typ, &cur, &owner, &rStatus, &createdAt)
	if errors.Is(err, pgx.ErrNoRows) {
		// The code already exists: idempotent ensure, unless attributes differ.
		existing, gerr := s.getAccountByCode(ctx, s.pool, acct.Code)
		if gerr != nil {
			return domain.Account{}, false, gerr
		}
		if existing.Type != acct.Type || existing.Currency != acct.Currency ||
			existing.OwnerPrincipal != acct.OwnerPrincipal {
			return domain.Account{}, false, domain.NewError(domain.CodeAccountConflict,
				"account code %q exists with different attributes", acct.Code)
		}
		return existing, false, nil
	}
	if err != nil {
		return domain.Account{}, false, mapPgError(err)
	}
	return domain.Account{
		ID: rID, Code: code, Type: domain.AccountType(typ), Currency: domain.Currency(cur),
		OwnerPrincipal: owner, Status: domain.AccountStatus(rStatus), CreatedAt: createdAt,
	}, true, nil
}

func (s *PostgresStore) GetStatement(ctx context.Context, q domain.StatementQuery) (domain.Statement, error) {
	acc, err := s.getAccount(ctx, s.pool, q.AccountID)
	if err != nil {
		return domain.Statement{}, err
	}

	// Running balance: sum of everything up to and including the cursor.
	balance := int64(0)
	if q.Cursor > 0 {
		err = s.pool.QueryRow(ctx, `
                        SELECT COALESCE(SUM(credit - debit), 0) FROM ledger.postings
                        WHERE account_id = $1::uuid AND id <= $2`, q.AccountID, q.Cursor,
		).Scan(&balance)
		if err != nil {
			return domain.Statement{}, mapPgError(err)
		}
	}

	// Page: LIMIT+1 rows to detect has_more without a second count query.
	rows, err := s.pool.Query(ctx, `
                SELECT p.id, p.entry_id::text, p.debit, p.credit,
                       e.transaction_key, e.source, e.entry_type, COALESCE(e.reason, ''), e.created_at
                FROM ledger.postings p
                JOIN ledger.journal_entries e ON e.id = p.entry_id
                WHERE p.account_id = $1::uuid AND p.id > $2
                ORDER BY p.id ASC
                LIMIT $3`, q.AccountID, q.Cursor, q.Limit+1)
	if err != nil {
		return domain.Statement{}, mapPgError(err)
	}
	defer rows.Close()

	lines := make([]domain.StatementLine, 0, q.Limit)
	var nextCursor int64
	for rows.Next() {
		var l domain.StatementLine
		if err := rows.Scan(&l.PostingID, &l.EntryID, &l.Debit, &l.Credit,
			&l.TransactionKey, &l.Source, &l.EntryType, &l.Reason, &l.CreatedAt); err != nil {
			return domain.Statement{}, mapPgError(err)
		}
		if len(lines) == q.Limit {
			// One extra row fetched: there are more pages.
			return s.finishStatement(ctx, q, acc, lines, nextCursor, true)
		}
		balance += l.Credit - l.Debit
		l.BalanceAfter = balance
		lines = append(lines, l)
		nextCursor = l.PostingID
	}
	if err := rows.Err(); err != nil {
		return domain.Statement{}, mapPgError(err)
	}
	return s.finishStatement(ctx, q, acc, lines, nextCursor, false)
}

// finishStatement fills the account's current (final) balance — the running
// sum over ALL postings, not just the page.
func (s *PostgresStore) finishStatement(ctx context.Context, q domain.StatementQuery, acc domain.Account, lines []domain.StatementLine, nextCursor int64, hasMore bool) (domain.Statement, error) {
	var final int64
	err := s.pool.QueryRow(ctx, `
                SELECT COALESCE(SUM(credit - debit), 0) FROM ledger.postings
                WHERE account_id = $1::uuid`, q.AccountID,
	).Scan(&final)
	if err != nil {
		return domain.Statement{}, mapPgError(err)
	}
	return domain.Statement{
		Account:      acc,
		Lines:        lines,
		NextCursor:   nextCursor,
		HasMore:      hasMore,
		BalanceMinor: final,
	}, nil
}

// isUniqueViolationOn reports a 23505 error whose constraint involves namePart.
func isUniqueViolationOn(err error, namePart string) bool {
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) || pgErr.Code != pgUniqueViolation {
		return false
	}
	return strings.Contains(pgErr.ConstraintName, namePart)
}

// mapPgError translates PostgreSQL errors into domain errors. The invariant
// triggers raise with ERRCODE 23514 and message prefixes
// ledger_invariant_<name>; see migrations/001_ledger_init.sql.
func mapPgError(err error) error {
	if err == nil {
		return nil
	}
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) {
		return err
	}
	switch pgErr.Code {
	case pgUniqueViolation:
		if strings.Contains(pgErr.ConstraintName, "reverses_entry_id") {
			return domain.NewError(domain.CodeAlreadyReversed, "entry is already reversed")
		}
		if strings.Contains(pgErr.ConstraintName, "transaction_key") {
			return domain.NewError(domain.CodeIdempotencyConflict,
				"transaction_key %q already used", pgErr.Detail)
		}
		return err
	case pgCheckViolation, pgForeignKeyViolation:
		switch {
		case strings.Contains(pgErr.Message, "ledger_invariant_unbalanced"):
			return domain.NewError(domain.CodeUnbalancedEntry, "entry is unbalanced: %s", pgErr.Message)
		case strings.Contains(pgErr.Message, "ledger_invariant_wallet_negative"):
			return domain.NewError(domain.CodeInsufficientFunds, "wallet balance would go negative: %s", pgErr.Message)
		case strings.Contains(pgErr.Message, "ledger_invariant_reversal"):
			return domain.NewError(domain.CodeReversalMismatch, "reversal invariant violated: %s", pgErr.Message)
		case strings.Contains(pgErr.Message, "ledger_invariant_append_only"):
			return domain.NewError(domain.CodeInternal, "append-only violation: %s", pgErr.Message)
		case strings.Contains(pgErr.Message, "postings"):
			return domain.NewError(domain.CodeInvalidPosting, "invalid posting: %s", pgErr.Message)
		case strings.Contains(pgErr.Message, "accounts"):
			return domain.NewError(domain.CodeInvalidAccountType, "invalid account: %s", pgErr.Message)
		case strings.Contains(pgErr.Message, "journal_entries"):
			return domain.NewError(domain.CodeInvalidEntryType, "invalid journal entry: %s", pgErr.Message)
		}
	}
	return err
}
