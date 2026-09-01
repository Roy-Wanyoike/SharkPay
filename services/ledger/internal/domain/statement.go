package domain

import "time"

// Statement pagination constants (API-CONTRACTS §1.5: cursor-based, max 100).
const (
	DefaultStatementLimit = 50
	MaxStatementLimit     = 100
)

// StatementQuery asks for one page of an account's postings, in ascending
// posting-id (chronological) order. Cursor is the exclusive lower bound on
// posting id; 0 reads from the beginning.
type StatementQuery struct {
	AccountID string
	Cursor    int64
	Limit     int
}

// StatementLine is one posting rendered as a statement line with the
// account's running balance after the line is applied.
type StatementLine struct {
	PostingID      int64
	EntryID        string
	TransactionKey string
	Source         Source
	EntryType      EntryType
	Reason         string
	CreatedAt      time.Time
	Debit          int64
	Credit         int64
	BalanceAfter   int64
}

// Statement is one page plus the account's current (final) balance.
// NextCursor is 0 when there are no more pages.
type Statement struct {
	Account      Account
	Lines        []StatementLine
	NextCursor   int64
	HasMore      bool
	BalanceMinor int64
}
