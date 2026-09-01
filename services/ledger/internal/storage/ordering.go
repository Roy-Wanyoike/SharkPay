package storage

import (
	"sort"

	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/domain"
)

// LockOrder returns accountIDs deduplicated and sorted ascending (UUIDs are
// fixed-width lowercase hex strings, so lexicographic order is a total
// order).
//
// Postgres row locks for a posting transaction MUST be acquired exactly in
// this order. Any two concurrent postings that touch overlapping account
// sets then contend on the same first account; the transaction that loses
// waits while holding only locks the winner already holds or will acquire
// strictly later in the same global order — the classic deadlock-free lock
// acquisition protocol.
func LockOrder(accountIDs []string) []string {
	seen := make(map[string]struct{}, len(accountIDs))
	uniq := make([]string, 0, len(accountIDs))
	for _, id := range accountIDs {
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		uniq = append(uniq, id)
	}
	sort.Strings(uniq)
	return uniq
}

// legAccountIDs collects the account references of entry legs.
func legAccountIDs(legs []domain.Leg) []string {
	ids := make([]string, len(legs))
	for i, l := range legs {
		ids[i] = l.AccountID
	}
	return ids
}

// postingAccountIDs collects the account references of stored postings.
func postingAccountIDs(ps []domain.Posting) []string {
	ids := make([]string, len(ps))
	for i, p := range ps {
		ids[i] = p.AccountID
	}
	return ids
}
