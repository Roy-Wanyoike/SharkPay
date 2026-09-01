package domain

import "fmt"

// ValidateBalancedPerCurrency enforces invariant #1 (PRD §11, DATA-MODEL §4):
// for every journal entry, per currency, total debits equal total credits.
// The currency of a leg is the currency of its account (postings carry no
// currency column), so a mixed KES+USD entry must balance each currency
// independently. accountCurrencies maps every touched account id to its
// currency; a missing entry is an account_not_found error.
func ValidateBalancedPerCurrency(legs []Leg, accountCurrencies map[string]Currency) error {
	sums := map[Currency][2]int64{}
	for _, l := range legs {
		cur, ok := accountCurrencies[l.AccountID]
		if !ok {
			return NewError(CodeAccountNotFound, "account %s not found", l.AccountID)
		}
		s := sums[cur]
		s[0] += l.Debit
		s[1] += l.Credit
		sums[cur] = s
	}
	for cur, s := range sums {
		if s[0] != s[1] {
			return &Error{
				Code: CodeUnbalancedEntry,
				Message: fmt.Sprintf("entry is unbalanced in %s: debits=%d credits=%d",
					cur, s[0], s[1]),
			}
		}
	}
	return nil
}

// ValidateWalletNonNegative enforces invariant #2: wallet accounts may never
// hold a negative running balance (credits − debits ≥ 0; holds are modeled
// by the wallet service as release+capture pairs, so raw ledger balances are
// the available balance). balances holds the current committed balances of
// the touched accounts (missing key = zero balance). Only wallet-type
// accounts are constrained; internal accounts (fees, suspense, clearing, FX
// position, settlement) may swing negative.
func ValidateWalletNonNegative(legs []Leg, accounts map[string]Account, balances map[string]int64) error {
	deltas := map[string]int64{}
	for _, l := range legs {
		deltas[l.AccountID] += l.Credit - l.Debit
	}
	for id, delta := range deltas {
		acc, ok := accounts[id]
		if !ok {
			return NewError(CodeAccountNotFound, "account %s not found", id)
		}
		if acc.Type != AccountTypeWallet {
			continue
		}
		current := balances[id]
		if current+delta < 0 {
			return &Error{
				Code: CodeInsufficientFunds,
				Message: fmt.Sprintf("wallet account %s (%s) would go negative: balance=%d delta=%d",
					acc.Code, id, current, delta),
			}
		}
	}
	return nil
}

// SameLegs reports whether two leg lists are monetarily identical: same
// accounts with identical per-account debit and credit totals (order and
// same-account leg splitting are irrelevant). Used for idempotency conflict
// detection: a replayed key must carry the same payload.
func SameLegs(a, b []Leg) bool {
	sa, sb := legSums(a), legSums(b)
	if len(sa) != len(sb) {
		return false
	}
	for id, s := range sa {
		t, ok := sb[id]
		if !ok || s != t {
			return false
		}
	}
	return true
}

// CheckReplayPayload compares a stored entry against a replayed request for
// the same (source, transaction_key). Equal → nil (safe replay); different
// → idempotency_conflict. Reason and operator metadata are intentionally
// not compared: they cannot change any money movement.
func CheckReplayPayload(stored EntryDetail, tx Transaction) error {
	if stored.Entry.SourceRef != tx.SourceRef ||
		stored.Entry.EntryType != tx.EntryType ||
		stored.Entry.ReversesEntryID != tx.ReversesEntryID {
		return &Error{
			Code:    CodeIdempotencyConflict,
			Message: fmt.Sprintf("transaction_key %q already used with a different payload", tx.TransactionKey),
		}
	}
	if !SameLegs(LegsFromPostings(stored.Postings), tx.Postings) {
		return &Error{
			Code:    CodeIdempotencyConflict,
			Message: fmt.Sprintf("transaction_key %q already used with different postings", tx.TransactionKey),
		}
	}
	return nil
}
