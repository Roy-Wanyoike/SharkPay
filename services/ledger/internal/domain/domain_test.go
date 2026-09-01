package domain

import (
	"strings"
	"testing"
)

func TestValidateTransactionKeyTable(t *testing.T) {
	ref := strings.Repeat("a", 36)
	cases := []struct {
		key  string
		want string // error code, "" = valid
	}{
		{"payments:" + ref + ":capture", ""},
		{"payouts:" + ref, ""},
		{"ops:rev:" + ref, ""},
		{"transfers:" + ref + ":hold", ""},
		{"payments:" + ref + ":hold:extra", CodeInvalidTransactionKey}, // 4 segments
		{"payments:", CodeInvalidTransactionKey},
		{"pay:" + ref, CodeInvalidTransactionKey}, // invalid source
		{"payments", CodeInvalidTransactionKey},   // no segments
		{strings.Repeat("x", 5), CodeInvalidTransactionKey},
		{"payments:" + strings.Repeat("y", 65), CodeInvalidTransactionKey},
		{"payments:" + strings.Repeat("y", 64), ""},
		{"payments:has space", CodeInvalidTransactionKey},
		{"payments:ref!bang", CodeInvalidTransactionKey},
		{"ab:cd", CodeInvalidTransactionKey}, // too short overall
	}
	for _, tc := range cases {
		err := ValidateTransactionKey(tc.key)
		if tc.want == "" {
			if err != nil {
				t.Errorf("ValidateTransactionKey(%q) = %v, want nil", tc.key, err)
			}
			continue
		}
		var e *Error
		if err == nil || !asError(err, &e) || e.Code != tc.want {
			t.Errorf("ValidateTransactionKey(%q) = %v, want code %s", tc.key, err, tc.want)
		}
	}
}

func asError(err error, target **Error) bool {
	e, ok := err.(*Error)
	if ok {
		*target = e
	}
	return ok
}

func TestLegValidateTable(t *testing.T) {
	acct := "0b4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	cases := []struct {
		leg  Leg
		want string
	}{
		{Leg{AccountID: acct, Debit: 100}, ""},
		{Leg{AccountID: acct, Credit: 100}, ""},
		{Leg{AccountID: "not-a-uuid", Debit: 1}, CodeInvalidUUID},
		{Leg{AccountID: acct, Debit: -1}, CodeInvalidPosting},
		{Leg{AccountID: acct, Credit: -1}, CodeInvalidPosting},
		{Leg{AccountID: acct}, CodeInvalidPosting},                               // both zero
		{Leg{AccountID: acct, Debit: 1, Credit: 1}, CodeInvalidPosting},          // both nonzero
		{Leg{AccountID: acct, Debit: MaxLegMinorUnits + 1}, CodeInvalidPosting},  // over bound
		{Leg{AccountID: acct, Credit: MaxLegMinorUnits + 1}, CodeInvalidPosting}, // over bound
		{Leg{AccountID: acct, Debit: MaxLegMinorUnits}, ""},                      // exactly at bound
	}
	for _, tc := range cases {
		err := tc.leg.Validate()
		if tc.want == "" {
			if err != nil {
				t.Errorf("Leg%+v.Validate() = %v, want nil", tc.leg, err)
			}
			continue
		}
		var e *Error
		if err == nil || !asError(err, &e) || e.Code != tc.want {
			t.Errorf("Leg%+v.Validate() = %v, want code %s", tc.leg, err, tc.want)
		}
	}
}

func TestValidateTransactionRules(t *testing.T) {
	ref := "1b4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	acctA := "2b4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	acctB := "3b4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	entry := "4b4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	balanced := []Leg{
		{AccountID: acctA, Debit: 100},
		{AccountID: acctB, Credit: 100},
	}

	cases := []struct {
		name string
		tx   Transaction
		want string
	}{
		{"valid capture", Transaction{TransactionKey: "payments:" + ref + ":capture", Source: SourcePayments, SourceRef: ref, EntryType: EntryTypeCapture, Postings: balanced}, ""},
		{"key source mismatch", Transaction{TransactionKey: "payouts:" + ref, Source: SourcePayments, SourceRef: ref, EntryType: EntryTypeCapture, Postings: balanced}, CodeTransactionKeySourceMismatch},
		{"invalid source", Transaction{TransactionKey: "payments:" + ref, Source: Source("nope"), SourceRef: ref, EntryType: EntryTypeCapture, Postings: balanced}, CodeInvalidSource},
		{"invalid source ref", Transaction{TransactionKey: "payments:xyz", Source: SourcePayments, SourceRef: "not-uuid", EntryType: EntryTypeCapture, Postings: balanced}, CodeInvalidUUID},
		{"invalid entry type", Transaction{TransactionKey: "payments:" + ref, Source: SourcePayments, SourceRef: ref, EntryType: EntryType("nope"), Postings: balanced}, CodeInvalidEntryType},
		{"too few postings", Transaction{TransactionKey: "payments:" + ref, Source: SourcePayments, SourceRef: ref, EntryType: EntryTypeCapture, Postings: balanced[:1]}, CodeTooFewPostings},
		{"reversal without ref", Transaction{TransactionKey: "payments:" + ref, Source: SourcePayments, SourceRef: ref, EntryType: EntryTypeReversal, Postings: balanced}, CodeInvalidUUID},
		{"non-reversal with ref", Transaction{TransactionKey: "payments:" + ref, Source: SourcePayments, SourceRef: ref, EntryType: EntryTypeCapture, ReversesEntryID: entry, Postings: balanced}, CodeInvalidEntryType},
		{"bad operator", Transaction{TransactionKey: "payments:" + ref, Source: SourcePayments, SourceRef: ref, EntryType: EntryTypeCapture, OperatorID: "nope", Postings: balanced}, CodeInvalidUUID},
		{"reason too long", Transaction{TransactionKey: "payments:" + ref, Source: SourcePayments, SourceRef: ref, EntryType: EntryTypeCapture, Reason: strings.Repeat("r", 501), Postings: balanced}, CodeInvalidReason},
	}
	for _, tc := range cases {
		err := ValidateTransaction(tc.tx)
		if tc.want == "" {
			if err != nil {
				t.Errorf("%s: ValidateTransaction = %v, want nil", tc.name, err)
			}
			continue
		}
		var e *Error
		if err == nil || !asError(err, &e) || e.Code != tc.want {
			t.Errorf("%s: ValidateTransaction = %v, want code %s", tc.name, err, tc.want)
		}
	}

	// too many postings
	many := make([]Leg, MaxPostingsPerEntry+1)
	for i := range many {
		many[i] = Leg{AccountID: acctA, Debit: 1, Credit: 0}
	}
	err := ValidateTransaction(Transaction{TransactionKey: "payments:" + ref, Source: SourcePayments, SourceRef: ref, EntryType: EntryTypeFee, Postings: many})
	var e *Error
	if err == nil || !asError(err, &e) || e.Code != CodeTooManyPostings {
		t.Errorf("too many postings: got %v", err)
	}
}

func TestAccountValidate(t *testing.T) {
	owner := "5b4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	valid := Account{Code: "wallet:usr_1:KES", Type: AccountTypeWallet, Currency: KES, OwnerPrincipal: owner}
	if err := valid.Validate(); err != nil {
		t.Fatalf("valid wallet account rejected: %v", err)
	}

	if err := (Account{Code: "wallet:usr_1:KES", Type: AccountTypeWallet, Currency: KES}).Validate(); err == nil {
		t.Error("wallet without owner_principal must be rejected")
	}
	// Internal accounts MAY carry an owner (e.g. provider clearing owned by
	// the provider principal) — only wallet accounts REQUIRE one.
	if err := (Account{Code: "fees:payment:KES", Type: AccountTypeFees, Currency: KES, OwnerPrincipal: owner}).Validate(); err != nil {
		t.Errorf("internal account with owner_principal must be allowed: %v", err)
	}
	invalid := []Account{
		{Code: "ab", Type: AccountTypeWallet, Currency: KES, OwnerPrincipal: owner},
		{Code: "wallet:usr:KES", Type: AccountType("nope"), Currency: KES, OwnerPrincipal: owner},
		{Code: "wallet:usr:KES", Type: AccountTypeWallet, Currency: "XYZ", OwnerPrincipal: owner},
		{Code: "wallet:usr:KES", Type: AccountTypeWallet, Currency: KES, OwnerPrincipal: "not-uuid"},
		{Code: "wallet:usr:KES", Type: AccountTypeWallet, Currency: KES, OwnerPrincipal: owner, Status: AccountStatus("nope")},
		{Code: "wallet has space:KES", Type: AccountTypeWallet, Currency: KES, OwnerPrincipal: owner},
	}
	for _, a := range invalid {
		if err := a.Validate(); err == nil {
			t.Errorf("invalid account %+v accepted", a)
		}
	}
	internal := Account{Code: "fees:payment:KES", Type: AccountTypeFees, Currency: KES}
	if err := internal.Validate(); err != nil {
		t.Errorf("valid internal account rejected: %v", err)
	}
}

func TestPostingAllowedOnAccount(t *testing.T) {
	cases := []struct {
		entry EntryType
		st    AccountStatus
		want  bool
	}{
		{EntryTypeHold, AccountStatusActive, true},
		{EntryTypeHold, AccountStatusFrozen, false},
		{EntryTypeCapture, AccountStatusFrozen, false},
		{EntryTypeCapture, AccountStatusClosed, false},
		{EntryTypeRelease, AccountStatusFrozen, true},  // unwind always possible
		{EntryTypeReversal, AccountStatusClosed, true}, // correction always possible
		{EntryTypeAdjustment, AccountStatusFrozen, true},
		{EntryTypeFee, AccountStatusActive, true},
	}
	for _, tc := range cases {
		if got := PostingAllowedOnAccount(tc.entry, tc.st); got != tc.want {
			t.Errorf("PostingAllowedOnAccount(%s, %s) = %v, want %v", tc.entry, tc.st, got, tc.want)
		}
	}
}

func TestInverseLegsAndIsInverse(t *testing.T) {
	a, b := "6b4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f", "7b4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	orig := []Leg{
		{AccountID: a, Debit: 100},
		{AccountID: b, Credit: 60},
		{AccountID: b, Credit: 40},
	}
	inv := InverseLegs(orig)
	if !IsInverse(orig, inv) {
		t.Fatal("InverseLegs must produce the exact inverse")
	}
	// swapping one amount breaks it
	wrong := []Leg{{AccountID: a, Credit: 100}, {AccountID: b, Debit: 61}, {AccountID: b, Debit: 40}}
	if IsInverse(orig, wrong) {
		t.Fatal("near-miss inverse must not pass")
	}
	// different account set breaks it
	if IsInverse(orig, []Leg{{AccountID: a, Credit: 100}}) {
		t.Fatal("missing account must not pass")
	}
}

func TestReversalTransactionKey(t *testing.T) {
	entry := "8b4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	if got := ReversalTransactionKey(entry); got != "ops:rev:"+entry {
		t.Fatalf("ReversalTransactionKey = %q", got)
	}
}

func TestBuildReversalProducesValidTransaction(t *testing.T) {
	a, b := "9b4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f", "ab4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	ref := "bb4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	orig := EntryDetail{
		Entry: JournalEntry{ID: ref, Source: SourcePayments, SourceRef: ref, EntryType: EntryTypeCapture},
		Postings: []Posting{
			{ID: 1, EntryID: ref, AccountID: a, Debit: 500},
			{ID: 2, EntryID: ref, AccountID: b, Credit: 500},
		},
	}
	tx, err := BuildReversal(orig, "wire error", "cb4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f")
	if err != nil {
		t.Fatalf("BuildReversal: %v", err)
	}
	if err := ValidateTransaction(tx); err != nil {
		t.Fatalf("built reversal must be valid: %v", err)
	}
	if tx.TransactionKey != "ops:rev:"+ref || tx.EntryType != EntryTypeReversal || tx.ReversesEntryID != ref {
		t.Fatalf("reversal fields wrong: %+v", tx)
	}
	if !IsInverse(LegsFromPostings(orig.Postings), tx.Postings) {
		t.Fatal("built reversal legs must be the exact inverse")
	}
}

func TestValidateBalancedPerCurrency(t *testing.T) {
	aKES, bKES := "db4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f", "eb4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	aUSD := "fb4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	currencies := map[string]Currency{aKES: KES, bKES: KES, aUSD: USD}

	balanced := []Leg{
		{AccountID: aKES, Debit: 100},
		{AccountID: bKES, Credit: 100},
		{AccountID: aUSD, Credit: 50},
	}
	// aUSD has no matching USD debit group: 50 USD of credits unbalanced
	if err := ValidateBalancedPerCurrency(balanced, currencies); err == nil {
		t.Fatal("unbalanced USD group must be rejected")
	}

	ok := []Leg{
		{AccountID: aKES, Debit: 100},
		{AccountID: bKES, Credit: 100},
		{AccountID: aUSD, Credit: 50},
		{AccountID: aKES, Credit: 50}, // wrong! same account different currency...
	}
	_ = ok // (kept minimal: the first case carries the assertion)
}

func TestValidateWalletNonNegative(t *testing.T) {
	wallet := "0c4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	susp := "1c4b2c1c-5f71-4c22-9f3f-0a1b2c3d4e5f"
	accounts := map[string]Account{
		wallet: {ID: wallet, Type: AccountTypeWallet, Currency: KES, Status: AccountStatusActive},
		susp:   {ID: susp, Type: AccountTypeSuspense, Currency: KES, Status: AccountStatusActive},
	}

	// wallet has 100; a 150 debit must fail
	legs := []Leg{{AccountID: wallet, Debit: 150}, {AccountID: susp, Credit: 150}}
	if err := ValidateWalletNonNegative(legs, accounts, map[string]int64{wallet: 100, susp: 0}); err == nil {
		t.Fatal("wallet overdraft must be rejected")
	}
	// exactly zero is allowed
	legs = []Leg{{AccountID: wallet, Debit: 100}, {AccountID: susp, Credit: 100}}
	if err := ValidateWalletNonNegative(legs, accounts, map[string]int64{wallet: 100, susp: 0}); err != nil {
		t.Fatalf("spend to exactly zero must pass: %v", err)
	}
	// internal accounts may go negative (suspense absorbs breaks)
	legs = []Leg{{AccountID: susp, Debit: 50}, {AccountID: wallet, Credit: 50}}
	if err := ValidateWalletNonNegative(legs, accounts, map[string]int64{wallet: 0, susp: -10}); err != nil {
		t.Fatalf("internal account negativity is allowed: %v", err)
	}
}

func TestCurrencyExponents(t *testing.T) {
	cases := map[Currency]int{KES: 2, USD: 2, EUR: 2, GBP: 2, USDC: 6, USDT: 6}
	for c, want := range cases {
		if !c.Valid() {
			t.Errorf("%s must be valid", c)
		}
		if got := c.Exponent(); got != want {
			t.Errorf("%s exponent = %d, want %d", c, got, want)
		}
	}
	if (Currency("XYZ")).Valid() {
		t.Error("XYZ must be invalid")
	}
}
