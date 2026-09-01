package storage

import (
	"context"
	"testing"

	"github.com/google/uuid"

	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/domain"
)

func setupAccounts(t *testing.T, f *FakeStore) (wallet, clearing string) {
	t.Helper()
	ctx := context.Background()
	w, created, err := f.EnsureAccount(ctx, domain.Account{
		Code: "wallet:usr_t:KES", Type: domain.AccountTypeWallet, Currency: domain.KES, OwnerPrincipal: uuid.NewString(),
	})
	if err != nil || !created {
		t.Fatalf("wallet: %v created=%v", err, created)
	}
	c, created, err := f.EnsureAccount(ctx, domain.Account{
		Code: "honeycoin:clearing:KES", Type: domain.AccountTypeProviderClearing, Currency: domain.KES,
	})
	if err != nil || !created {
		t.Fatalf("clearing: %v created=%v", err, created)
	}
	return w.ID, c.ID
}

// LockOrder must always be ascending account-id, regardless of the leg order
// in the transaction — the deadlock-free acquisition contract
// (ARCHITECTURE §5; postgres path: SELECT ... FOR UPDATE ORDER BY id).
func TestLockOrderIsAscending(t *testing.T) {
	f := NewFakeStore()
	wallet, clearing := setupAccounts(t, f)

	// legs deliberately unordered: clearing id > wallet id (or vice versa) —
	// the lock order must still come out sorted.
	tx := domain.Transaction{
		TransactionKey: "payments:" + uuid.NewString() + ":capture",
		Source:         domain.SourcePayments, SourceRef: uuid.NewString(),
		EntryType: domain.EntryTypeCapture,
		Postings: []domain.Leg{
			{AccountID: clearing, Debit: 100},
			{AccountID: wallet, Credit: 100},
		},
	}
	if _, err := f.InsertTransaction(context.Background(), tx); err != nil {
		t.Fatalf("insert: %v", err)
	}
	order := f.LastLockOrder()
	if len(order) != 2 {
		t.Fatalf("lock order length = %d", len(order))
	}
	if order[0] > order[1] {
		t.Fatalf("lock order not ascending: %v", order)
	}

	// reversed leg order → same sorted lock order
	tx2 := domain.Transaction{
		TransactionKey: "payments:" + uuid.NewString() + ":capture",
		Source:         domain.SourcePayments, SourceRef: uuid.NewString(),
		EntryType: domain.EntryTypeCapture,
		Postings: []domain.Leg{
			{AccountID: wallet, Debit: 10},
			{AccountID: clearing, Credit: 10},
		},
	}
	walletBal := f.Balances()[wallet] // 100 from tx
	_ = walletBal
	if wallet, clearing := wallet, clearing; wallet == "" || clearing == "" {
		t.Fatal("setup")
	}
	// top up so the debit clears non-negativity
	fundTx := domain.Transaction{
		TransactionKey: "payments:" + uuid.NewString() + ":capture",
		Source:         domain.SourcePayments, SourceRef: uuid.NewString(),
		EntryType: domain.EntryTypeCapture,
		Postings: []domain.Leg{
			{AccountID: clearing, Debit: 500},
			{AccountID: wallet, Credit: 500},
		},
	}
	if _, err := f.InsertTransaction(context.Background(), fundTx); err != nil {
		t.Fatalf("fund: %v", err)
	}
	if _, err := f.InsertTransaction(context.Background(), tx2); err != nil {
		t.Fatalf("insert 2: %v", err)
	}
	order2 := f.LastLockOrder()
	if len(order2) != 2 || order2[0] > order2[1] {
		t.Fatalf("lock order 2 not ascending: %v", order2)
	}
	if order[0] != order2[0] || order[1] != order2[1] {
		t.Fatalf("lock order must be identical for both leg orders: %v vs %v", order, order2)
	}
}

// The store-level double-reversal guard: a second DISTINCT reversal of the
// same entry (fresh key, correct inverse legs — i.e. bypassing the service's
// deterministic key) is rejected. The SQL equivalent is the deferred UNIQUE
// constraint on reverses_entry_id.
func TestDoubleReversalRejectedAtStoreLevel(t *testing.T) {
	f := NewFakeStore()
	wallet, clearing := setupAccounts(t, f)
	ctx := context.Background()

	capture := domain.Transaction{
		TransactionKey: "payments:" + uuid.NewString() + ":capture",
		Source:         domain.SourcePayments, SourceRef: uuid.NewString(),
		EntryType: domain.EntryTypeCapture,
		Postings: []domain.Leg{
			{AccountID: clearing, Debit: 100},
			{AccountID: wallet, Credit: 100},
		},
	}
	posted, err := f.InsertTransaction(ctx, capture)
	if err != nil {
		t.Fatalf("capture: %v", err)
	}

	// first reversal via the derived ops key
	rev1 := domain.Transaction{
		TransactionKey:  domain.ReversalTransactionKey(posted.Entry.ID),
		Source:          domain.SourceOps,
		SourceRef:       posted.Entry.SourceRef,
		EntryType:       domain.EntryTypeReversal,
		ReversesEntryID: posted.Entry.ID,
		Reason:          "first",
		Postings:        domain.InverseLegs(domain.LegsFromPostings(posted.Postings)),
	}
	if _, err := f.InsertTransaction(ctx, rev1); err != nil {
		t.Fatalf("first reversal: %v", err)
	}

	// second reversal: FRESH key, exact inverse legs → must be rejected
	rev2 := domain.Transaction{
		TransactionKey:  "ops:manual-rev:" + uuid.NewString(),
		Source:          domain.SourceOps,
		SourceRef:       posted.Entry.SourceRef,
		EntryType:       domain.EntryTypeReversal,
		ReversesEntryID: posted.Entry.ID,
		Reason:          "second attempt",
		OperatorID:      uuid.NewString(),
		Postings:        domain.InverseLegs(domain.LegsFromPostings(posted.Postings)),
	}
	_, err = f.InsertTransaction(ctx, rev2)
	var de *domain.Error
	if err == nil || !asDomainErr(err, &de) || de.Code != domain.CodeAlreadyReversed {
		t.Fatalf("second reversal must be rejected with already_reversed, got %v", err)
	}
}

func asDomainErr(err error, target **domain.Error) bool {
	e, ok := err.(*domain.Error)
	if ok {
		*target = e
	}
	return ok
}
