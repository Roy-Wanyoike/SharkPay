package api

import (
	"strconv"
	"time"

	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/domain"
)

// ---------------------------------------------------------------------------
// request bodies
// ---------------------------------------------------------------------------

type postTransactionRequest struct {
	TransactionKey string    `json:"transaction_key"`
	Source         string    `json:"source"`
	SourceRef      string    `json:"source_ref"`
	EntryType      string    `json:"entry_type"`
	Reason         string    `json:"reason"`
	OperatorID     string    `json:"operator_id"`
	Postings       []legJSON `json:"postings"`
}

type legJSON struct {
	AccountID string `json:"account_id"`
	Debit     int64  `json:"debit"`
	Credit    int64  `json:"credit"`
}

type reverseRequest struct {
	Reason     string `json:"reason"`
	OperatorID string `json:"operator_id"`
}

type createAccountRequest struct {
	Code           string  `json:"code"`
	Type           string  `json:"type"`
	Currency       string  `json:"currency"`
	OwnerPrincipal *string `json:"owner_principal"`
	Status         string  `json:"status"`
}

// ---------------------------------------------------------------------------
// response bodies
// ---------------------------------------------------------------------------

type transactionResponse struct {
	EntryID          string            `json:"entry_id"`
	TransactionKey   string            `json:"transaction_key"`
	Source           string            `json:"source"`
	SourceRef        string            `json:"source_ref"`
	EntryType        string            `json:"entry_type"`
	ReversesEntryID  string            `json:"reverses_entry_id,omitempty"`
	Reason           string            `json:"reason,omitempty"`
	OperatorID       string            `json:"operator_id,omitempty"`
	CreatedAt        time.Time         `json:"created_at"`
	IdempotentReplay bool              `json:"idempotent_replay"`
	Postings         []postingResponse `json:"postings"`
}

type postingResponse struct {
	ID        int64  `json:"id"`
	AccountID string `json:"account_id"`
	Debit     int64  `json:"debit"`
	Credit    int64  `json:"credit"`
}

type accountResponse struct {
	ID             string    `json:"id"`
	Code           string    `json:"code"`
	Type           string    `json:"type"`
	Currency       string    `json:"currency"`
	Exponent       int       `json:"exponent"`
	OwnerPrincipal string    `json:"owner_principal,omitempty"`
	Status         string    `json:"status"`
	CreatedAt      time.Time `json:"created_at"`
}

type statementResponse struct {
	Account      accountResponse     `json:"account"`
	Currency     string              `json:"currency"`
	Exponent     int                 `json:"exponent"`
	BalanceMinor int64               `json:"balance_minor"`
	Lines        []statementLineJSON `json:"lines"`
	NextCursor   string              `json:"next_cursor,omitempty"`
	HasMore      bool                `json:"has_more"`
}

type statementLineJSON struct {
	PostingID         int64     `json:"posting_id"`
	EntryID           string    `json:"entry_id"`
	TransactionKey    string    `json:"transaction_key"`
	Source            string    `json:"source"`
	EntryType         string    `json:"entry_type"`
	Reason            string    `json:"reason,omitempty"`
	CreatedAt         time.Time `json:"created_at"`
	Debit             int64     `json:"debit"`
	Credit            int64     `json:"credit"`
	BalanceAfterMinor int64     `json:"balance_after_minor"`
}

// ---------------------------------------------------------------------------
// mappings
// ---------------------------------------------------------------------------

func legsFromJSON(ls []legJSON) []domain.Leg {
	legs := make([]domain.Leg, len(ls))
	for i, l := range ls {
		legs[i] = domain.Leg{AccountID: l.AccountID, Debit: l.Debit, Credit: l.Credit}
	}
	return legs
}

func transactionResponseFrom(pt domain.PostedTransaction) transactionResponse {
	postings := make([]postingResponse, 0, len(pt.Postings))
	for _, p := range pt.Postings {
		postings = append(postings, postingResponse{
			ID: p.ID, AccountID: p.AccountID, Debit: p.Debit, Credit: p.Credit,
		})
	}
	return transactionResponse{
		EntryID:          pt.Entry.ID,
		TransactionKey:   pt.Entry.TransactionKey,
		Source:           string(pt.Entry.Source),
		SourceRef:        pt.Entry.SourceRef,
		EntryType:        string(pt.Entry.EntryType),
		ReversesEntryID:  pt.Entry.ReversesEntryID,
		Reason:           pt.Entry.Reason,
		OperatorID:       pt.Entry.OperatorID,
		CreatedAt:        pt.Entry.CreatedAt,
		IdempotentReplay: pt.Replay,
		Postings:         postings,
	}
}

func accountResponseFrom(a domain.Account) accountResponse {
	return accountResponse{
		ID:             a.ID,
		Code:           a.Code,
		Type:           string(a.Type),
		Currency:       string(a.Currency),
		Exponent:       a.Currency.Exponent(),
		OwnerPrincipal: a.OwnerPrincipal,
		Status:         string(a.Status),
		CreatedAt:      a.CreatedAt,
	}
}

func statementResponseFrom(st domain.Statement) statementResponse {
	lines := make([]statementLineJSON, 0, len(st.Lines))
	for _, l := range st.Lines {
		lines = append(lines, statementLineJSON{
			PostingID:         l.PostingID,
			EntryID:           l.EntryID,
			TransactionKey:    l.TransactionKey,
			Source:            string(l.Source),
			EntryType:         string(l.EntryType),
			Reason:            l.Reason,
			CreatedAt:         l.CreatedAt,
			Debit:             l.Debit,
			Credit:            l.Credit,
			BalanceAfterMinor: l.BalanceAfter,
		})
	}
	resp := statementResponse{
		Account:      accountResponseFrom(st.Account),
		Currency:     string(st.Account.Currency),
		Exponent:     st.Account.Currency.Exponent(),
		BalanceMinor: st.BalanceMinor,
		Lines:        lines,
		HasMore:      st.HasMore,
	}
	if st.NextCursor > 0 {
		resp.NextCursor = strconv.FormatInt(st.NextCursor, 10)
	}
	return resp
}
