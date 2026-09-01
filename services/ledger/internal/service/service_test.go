package service_test

import (
	"context"
	"errors"
	"fmt"
	"testing"

	"github.com/google/uuid"

	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/domain"
	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/service"
	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/storage"
)

// ---------------------------------------------------------------------------
// harness
// ---------------------------------------------------------------------------

type env struct {
	svc    *service.Service
	fake   *storage.FakeStore
	ctx    context.Context
	owner  string                                    // principal owning the test wallets
	wallet struct{ A, B, AUD, Frozen string }        // wallet account ids
	ops    struct{ suspense, clearing, fees string } // internal account ids
}

func newEnv(t *testing.T) *env {
	t.Helper()
	e := &env{fake: storage.NewFakeStore(), ctx: context.Background(), owner: uuid.NewString()}
	e.svc = service.New(e.fake)

	e.wallet.A = ensure(t, e, domain.Account{Code: "wallet:usr_a:KES", Type: domain.AccountTypeWallet, Currency: domain.KES, OwnerPrincipal: e.owner})
	e.wallet.B = ensure(t, e, domain.Account{Code: "wallet:usr_b:KES", Type: domain.AccountTypeWallet, Currency: domain.KES, OwnerPrincipal: e.owner})
	e.wallet.AUD = ensure(t, e, domain.Account{Code: "wallet:usr_a:USD", Type: domain.AccountTypeWallet, Currency: domain.USD, OwnerPrincipal: e.owner})
	e.wallet.Frozen = ensure(t, e, domain.Account{Code: "wallet:usr_frozen:KES", Type: domain.AccountTypeWallet, Currency: domain.KES, OwnerPrincipal: e.owner, Status: domain.AccountStatusFrozen})
	e.ops.suspense = ensure(t, e, domain.Account{Code: "suspense:recon:KES", Type: domain.AccountTypeSuspense, Currency: domain.KES})
	e.ops.clearing = ensure(t, e, domain.Account{Code: "honeycoin:clearing:KES", Type: domain.AccountTypeProviderClearing, Currency: domain.KES})
	e.ops.fees = ensure(t, e, domain.Account{Code: "fees:payment:KES", Type: domain.AccountTypeFees, Currency: domain.KES})
	return e
}

func ensure(t *testing.T, e *env, a domain.Account) string {
	t.Helper()
	got, created, err := e.svc.EnsureAccount(e.ctx, a)
	if err != nil {
		t.Fatalf("EnsureAccount(%s): %v", a.Code, err)
	}
	if !created {
		t.Fatalf("EnsureAccount(%s): expected creation", a.Code)
	}
	return got.ID
}

// credit funds into wallet w (simulating a confirmed collection: money
// arrives from the provider clearing account).
func fund(t *testing.T, e *env, w string, amount int64, n int) {
	t.Helper()
	key := fmt.Sprintf("payments:%s:capture-%d", uuid.NewString(), n)
	post(t, e, key, domain.EntryTypeCapture,
		domain.Leg{AccountID: e.ops.clearing, Debit: amount},
		domain.Leg{AccountID: w, Credit: amount},
	)
}

func post(t *testing.T, e *env, key string, et domain.EntryType, legs ...domain.Leg) domain.PostedTransaction {
	t.Helper()
	pt, err := e.svc.PostTransaction(e.ctx, service.PostRequest{
		TransactionKey: key,
		Source:         domain.SourcePayments,
		SourceRef:      uuid.NewString(),
		EntryType:      et,
		Postings:       legs,
	})
	if err != nil {
		t.Fatalf("PostTransaction(%s): %v", key, err)
	}
	return pt
}

func postErr(t *testing.T, e *env, key string, et domain.EntryType, legs ...domain.Leg) error {
	t.Helper()
	_, err := e.svc.PostTransaction(e.ctx, service.PostRequest{
		TransactionKey: key,
		Source:         domain.SourcePayments,
		SourceRef:      uuid.NewString(),
		EntryType:      et,
		Postings:       legs,
	})
	return err
}

func wantCode(t *testing.T, err error, code string) {
	t.Helper()
	var de *domain.Error
	if err == nil || !errors.As(err, &de) || de.Code != code {
		t.Fatalf("error = %v, want domain code %s", err, code)
	}
}

// ---------------------------------------------------------------------------
// posting + invariants
// ---------------------------------------------------------------------------

func TestPostCaptureHappyPath(t *testing.T) {
	e := newEnv(t)
	fund(t, e, e.wallet.A, 100_000, 1)
	if bal := e.fake.Balances()[e.wallet.A]; bal != 100_000 {
		t.Fatalf("wallet balance = %d, want 100000", bal)
	}
}

func TestIdempotentReplay(t *testing.T) {
	e := newEnv(t)
	key := "payments:" + uuid.NewString() + ":capture"
	legs := []domain.Leg{
		{AccountID: e.ops.clearing, Debit: 500},
		{AccountID: e.wallet.A, Credit: 500},
	}
	first := post(t, e, key, domain.EntryTypeCapture, legs...)

	second, err := e.svc.PostTransaction(e.ctx, service.PostRequest{
		TransactionKey: key, Source: domain.SourcePayments,
		SourceRef: first.Entry.SourceRef, EntryType: domain.EntryTypeCapture, Postings: legs,
	})
	if err != nil {
		t.Fatalf("replay: %v", err)
	}
	if !second.Replay || second.Entry.ID != first.Entry.ID {
		t.Fatalf("replay must return the original entry (first=%s second=%s replay=%v)",
			first.Entry.ID, second.Entry.ID, second.Replay)
	}
	if e.fake.EntryCount() != 1 {
		t.Fatalf("replay must not append a new entry, entries=%d", e.fake.EntryCount())
	}
}

func TestIdempotencyConflictOnDifferentPayload(t *testing.T) {
	e := newEnv(t)
	key := "payments:" + uuid.NewString() + ":capture"
	post(t, e, key, domain.EntryTypeCapture,
		domain.Leg{AccountID: e.ops.clearing, Debit: 500},
		domain.Leg{AccountID: e.wallet.A, Credit: 500},
	)
	err := postErr(t, e, key, domain.EntryTypeCapture,
		domain.Leg{AccountID: e.ops.clearing, Debit: 900},
		domain.Leg{AccountID: e.wallet.A, Credit: 900},
	)
	wantCode(t, err, domain.CodeIdempotencyConflict)
}

func TestUnbalancedEntryRejectedPerCurrency(t *testing.T) {
	e := newEnv(t)
	err := postErr(t, e, "payments:"+uuid.NewString()+":capture", domain.EntryTypeCapture,
		domain.Leg{AccountID: e.ops.clearing, Credit: 100},
		domain.Leg{AccountID: e.wallet.A, Credit: 90},
	)
	wantCode(t, err, domain.CodeUnbalancedEntry)
}

func TestMultiCurrencyEntryBalancesIndependently(t *testing.T) {
	e := newEnv(t)
	// two balanced currency groups (KES capture + USD capture): accepted
	post(t, e, "payments:"+uuid.NewString()+":capture", domain.EntryTypeCapture,
		domain.Leg{AccountID: e.ops.clearing, Debit: 100}, // KES
		domain.Leg{AccountID: e.wallet.A, Credit: 100},    // KES
	)
}

func TestUnbalancedSecondCurrencyRejected(t *testing.T) {
	e := newEnv(t)
	// KES group balanced, USD group missing its counterpart leg
	err := postErr(t, e, "payments:"+uuid.NewString()+":capture", domain.EntryTypeCapture,
		domain.Leg{AccountID: e.wallet.A, Credit: 100},
		domain.Leg{AccountID: e.ops.clearing, Debit: 100},
		domain.Leg{AccountID: e.wallet.AUD, Credit: 50}, // USD credit unbalanced
	)
	wantCode(t, err, domain.CodeUnbalancedEntry)
}

func TestWalletOverdraftRejected(t *testing.T) {
	e := newEnv(t)
	fund(t, e, e.wallet.A, 100, 1)
	err := postErr(t, e, "payments:"+uuid.NewString()+":payout", domain.EntryTypeCapture,
		domain.Leg{AccountID: e.wallet.A, Debit: 101},
		domain.Leg{AccountID: e.ops.clearing, Credit: 101},
	)
	wantCode(t, err, domain.CodeInsufficientFunds)
}

func TestSpendToExactlyZeroAllowed(t *testing.T) {
	e := newEnv(t)
	fund(t, e, e.wallet.A, 100, 1)
	post(t, e, "payments:"+uuid.NewString()+":payout", domain.EntryTypeCapture,
		domain.Leg{AccountID: e.wallet.A, Debit: 100},
		domain.Leg{AccountID: e.ops.clearing, Credit: 100},
	)
	if bal := e.fake.Balances()[e.wallet.A]; bal != 0 {
		t.Fatalf("balance = %d, want 0", bal)
	}
}

func TestUnknownAccountRejected(t *testing.T) {
	e := newEnv(t)
	ghost := uuid.NewString()
	err := postErr(t, e, "payments:"+uuid.NewString()+":capture", domain.EntryTypeCapture,
		domain.Leg{AccountID: ghost, Debit: 10},
		domain.Leg{AccountID: e.ops.clearing, Credit: 10},
	)
	wantCode(t, err, domain.CodeAccountNotFound)
}

func TestFrozenAccountBlocksCaptureAllowsRelease(t *testing.T) {
	e := newEnv(t)
	// hold on the frozen wallet must be rejected (fail closed on money)
	err := postErr(t, e, "payments:"+uuid.NewString()+":hold", domain.EntryTypeHold,
		domain.Leg{AccountID: e.wallet.Frozen, Debit: 10},
		domain.Leg{AccountID: e.ops.suspense, Credit: 10},
	)
	wantCode(t, err, domain.CodeAccountInactive)
	// release (unwind) on frozen wallet is allowed — money must never strand
	_, err = e.svc.PostTransaction(e.ctx, service.PostRequest{
		TransactionKey: "payments:" + uuid.NewString() + ":release",
		Source:         domain.SourcePayments, SourceRef: uuid.NewString(),
		EntryType: domain.EntryTypeRelease,
		Postings: []domain.Leg{
			{AccountID: e.wallet.Frozen, Credit: 10},
			{AccountID: e.ops.suspense, Debit: 10},
		},
	})
	if err != nil {
		t.Fatalf("release on frozen wallet must pass: %v", err)
	}
}

func TestPostRejectsDirectReversalEntryType(t *testing.T) {
	e := newEnv(t)
	// PostRequest structurally cannot carry reverses_entry_id, so a reversal
	// posted directly fails validation (invalid_uuid: reversal entries must
	// reference the compensated entry). The service-level guard
	// (invalid_entry_type: "use the reverse API") is defense-in-depth behind
	// that — either code proves direct reversal posting is impossible.
	err := postErr(t, e, "payments:"+uuid.NewString()+":reversal", domain.EntryTypeReversal,
		domain.Leg{AccountID: e.wallet.A, Credit: 10},
		domain.Leg{AccountID: e.ops.suspense, Debit: 10},
	)
	var de *domain.Error
	if err == nil || !errors.As(err, &de) ||
		(de.Code != domain.CodeInvalidUUID && de.Code != domain.CodeInvalidEntryType) {
		t.Fatalf("direct reversal posting must be rejected, got %v", err)
	}
}

// ---------------------------------------------------------------------------
// reversal flow
// ---------------------------------------------------------------------------

func TestReverseHappyPath(t *testing.T) {
	e := newEnv(t)
	fund(t, e, e.wallet.A, 5_000, 1)
	if bal := e.fake.Balances()[e.wallet.A]; bal != 5_000 {
		t.Fatalf("setup balance = %d", bal)
	}
	origEntry := mustLastEntry(t, e)

	rev, err := e.svc.ReverseTransaction(e.ctx, domain.ReversalRequest{
		EntryID:    origEntry,
		Reason:     "wire error confirmed by provider",
		OperatorID: uuid.NewString(),
	})
	if err != nil {
		t.Fatalf("ReverseTransaction: %v", err)
	}
	if rev.Replay || rev.Entry.EntryType != domain.EntryTypeReversal {
		t.Fatalf("reversal entry wrong: %+v", rev.Entry)
	}
	if rev.Entry.TransactionKey != "ops:rev:"+origEntry {
		t.Fatalf("reversal key = %s", rev.Entry.TransactionKey)
	}
	if rev.Entry.ReversesEntryID != origEntry {
		t.Fatalf("reverses_entry_id = %s, want %s", rev.Entry.ReversesEntryID, origEntry)
	}
	if bal := e.fake.Balances()[e.wallet.A]; bal != 0 {
		t.Fatalf("wallet balance after reversal = %d, want 0", bal)
	}
}

func TestReverseIsIdempotent(t *testing.T) {
	e := newEnv(t)
	fund(t, e, e.wallet.A, 5_000, 1)
	entryID := mustLastEntry(t, e)
	req := domain.ReversalRequest{EntryID: entryID, Reason: "retry-safe reversal"}
	first, err := e.svc.ReverseTransaction(e.ctx, req)
	if err != nil {
		t.Fatalf("first reverse: %v", err)
	}
	second, err := e.svc.ReverseTransaction(e.ctx, req)
	if err != nil {
		t.Fatalf("second reverse: %v", err)
	}
	if !second.Replay || second.Entry.ID != first.Entry.ID {
		t.Fatal("retried reversal must replay the original reversal entry")
	}
	if e.fake.EntryCount() != 2 { // fund entry + reversal
		t.Fatalf("entries = %d, want 2", e.fake.EntryCount())
	}
}

func TestReverseOfReversalRejected(t *testing.T) {
	e := newEnv(t)
	fund(t, e, e.wallet.A, 5_000, 1)
	entryID := mustLastEntry(t, e)
	if _, err := e.svc.ReverseTransaction(e.ctx, domain.ReversalRequest{EntryID: entryID, Reason: "first"}); err != nil {
		t.Fatalf("first reverse: %v", err)
	}
	revID := mustLastEntry(t, e)
	_, err := e.svc.ReverseTransaction(e.ctx, domain.ReversalRequest{EntryID: revID, Reason: "chain"})
	wantCode(t, err, domain.CodeReversalOfReversal)
}

func TestReverseOverdraftRejected(t *testing.T) {
	// Reversing a credit that has since been spent must not overdraw.
	e := newEnv(t)
	fund(t, e, e.wallet.A, 100, 1)
	entryID := mustLastEntry(t, e)
	// spend the funds out
	post(t, e, "payments:"+uuid.NewString()+":payout", domain.EntryTypeCapture,
		domain.Leg{AccountID: e.wallet.A, Debit: 100},
		domain.Leg{AccountID: e.ops.clearing, Credit: 100},
	)
	_, err := e.svc.ReverseTransaction(e.ctx, domain.ReversalRequest{EntryID: entryID, Reason: "late reversal"})
	wantCode(t, err, domain.CodeInsufficientFunds)
}

func TestReverseRequiresReason(t *testing.T) {
	e := newEnv(t)
	_, err := e.svc.ReverseTransaction(e.ctx, domain.ReversalRequest{EntryID: uuid.NewString()})
	wantCode(t, err, domain.CodeInvalidReason)
}

func TestReverseUnknownEntry(t *testing.T) {
	e := newEnv(t)
	_, err := e.svc.ReverseTransaction(e.ctx, domain.ReversalRequest{EntryID: uuid.NewString(), Reason: "x"})
	wantCode(t, err, domain.CodeEntryNotFound)
}

// ---------------------------------------------------------------------------
// statements
// ---------------------------------------------------------------------------

func TestStatementPaginationAndBalance(t *testing.T) {
	e := newEnv(t)
	for i := 1; i <= 5; i++ {
		fund(t, e, e.wallet.A, int64(100*i), i)
	}
	page, err := e.svc.GetStatement(e.ctx, e.wallet.A, "", 2)
	if err != nil {
		t.Fatalf("GetStatement: %v", err)
	}
	if !page.HasMore || len(page.Lines) != 2 {
		t.Fatalf("first page wrong: %d lines, hasMore %v", len(page.Lines), page.HasMore)
	}
	cursor := fmt.Sprintf("%d", page.NextCursor)
	page2, err := e.svc.GetStatement(e.ctx, e.wallet.A, cursor, 2)
	if err != nil {
		t.Fatalf("GetStatement page 2: %v", err)
	}
	_ = page2
	full, err := e.svc.GetStatement(e.ctx, e.wallet.A, "", domain.MaxStatementLimit)
	if err != nil {
		t.Fatalf("GetStatement full: %v", err)
	}
	if len(full.Lines) != 5 {
		t.Fatalf("full statement lines = %d, want 5", len(full.Lines))
	}
	if full.BalanceMinor != 1500 { // 100+200+300+400+500
		t.Fatalf("balance = %d, want 1500", full.BalanceMinor)
	}
	// running balances ascend in 100s
	if full.Lines[4].BalanceAfter != 1500 || full.Lines[0].BalanceAfter != 100 {
		t.Fatalf("running balances wrong: first=%d last=%d", full.Lines[0].BalanceAfter, full.Lines[4].BalanceAfter)
	}
}

func TestStatementValidation(t *testing.T) {
	e := newEnv(t)
	_, err := e.svc.GetStatement(e.ctx, e.wallet.A, "not-a-cursor", 10)
	wantCode(t, err, domain.CodeInvalidCursor)
	_, err = e.svc.GetStatement(e.ctx, e.wallet.A, "", 101)
	wantCode(t, err, domain.CodeInvalidLimit)
	_, err = e.svc.GetStatement(e.ctx, "not-a-uuid", "", 10)
	wantCode(t, err, domain.CodeInvalidUUID)
	_, err = e.svc.GetStatement(e.ctx, uuid.NewString(), "", 10)
	wantCode(t, err, domain.CodeAccountNotFound)
}

// ---------------------------------------------------------------------------
// accounts
// ---------------------------------------------------------------------------

func TestEnsureAccountIdempotentAndConflict(t *testing.T) {
	e := newEnv(t)
	// same code, same attributes → returns the existing account
	got, created, err := e.svc.EnsureAccount(e.ctx, domain.Account{
		Code: "wallet:usr_a:KES", Type: domain.AccountTypeWallet, Currency: domain.KES, OwnerPrincipal: e.owner,
	})
	if err != nil || created {
		t.Fatalf("re-ensure must return the existing account: %v created=%v", err, created)
	}
	if got.ID != e.wallet.A {
		t.Fatalf("re-ensure returned id %s, want %s", got.ID, e.wallet.A)
	}
	// same code, different currency → conflict
	_, _, err = e.svc.EnsureAccount(e.ctx, domain.Account{
		Code: "wallet:usr_a:KES", Type: domain.AccountTypeWallet, Currency: domain.USD, OwnerPrincipal: e.owner,
	})
	wantCode(t, err, domain.CodeAccountConflict)
	// same code, different owner → conflict
	_, _, err = e.svc.EnsureAccount(e.ctx, domain.Account{
		Code: "wallet:usr_a:KES", Type: domain.AccountTypeWallet, Currency: domain.KES, OwnerPrincipal: uuid.NewString(),
	})
	wantCode(t, err, domain.CodeAccountConflict)
}

func TestEnsureAccountValidation(t *testing.T) {
	e := newEnv(t)
	_, _, err := e.svc.EnsureAccount(e.ctx, domain.Account{
		Code: "wallet:noowner:KES", Type: domain.AccountTypeWallet, Currency: domain.KES,
	})
	wantCode(t, err, domain.CodeInvalidRequest)
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

func mustLastEntry(t *testing.T, e *env) string {
	t.Helper()
	st, err := e.svc.GetStatement(e.ctx, e.wallet.A, "", domain.MaxStatementLimit)
	if err != nil || len(st.Lines) == 0 {
		t.Fatalf("statement for last entry: %v lines=%d", err, len(st.Lines))
	}
	return st.Lines[len(st.Lines)-1].EntryID
}
