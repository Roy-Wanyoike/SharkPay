package money

import (
	"errors"
	"testing"
)

func TestNewAndValidate(t *testing.T) {
	for _, code := range SupportedCurrencies() {
		m, err := New(100, code)
		if err != nil {
			t.Fatalf("New(100, %s): %v", code, err)
		}
		if m.Currency != code {
			t.Errorf("currency = %q, want %q", m.Currency, code)
		}
		if err := m.Validate(); err != nil {
			t.Errorf("Validate(%s): %v", code, err)
		}
	}
	if _, err := New(1, "XYZ"); !errors.Is(err, ErrUnknownCurrency) {
		t.Errorf("New(1, XYZ) error = %v, want ErrUnknownCurrency", err)
	}
	if err := (Money{AmountMinor: 1, Currency: "KES", Exponent: 6}).Validate(); !errors.Is(err, ErrExponentMismatch) {
		t.Errorf("Validate exponent mismatch error = %v, want ErrExponentMismatch", err)
	}
	if m, err := New(5, " kes "); err != nil || m.Currency != "KES" {
		t.Errorf("New(5, \" kes \") = %v, %v; want normalized KES", m, err)
	}
}

func TestFromStringTable(t *testing.T) {
	cases := []struct {
		in      string
		code    string
		want    int64
		wantErr error
	}{
		{"150.00", "KES", 15000, nil},
		{"0.01", "KES", 1, nil},
		{"0", "KES", 0, nil},
		{"1", "USD", 100, nil},
		{"1.5", "USDC", 1500000, nil},
		{"0.000001", "USDT", 1, nil},
		{"-12.34", "EUR", -1234, nil},
		{"+7.5", "GBP", 750, nil},
		{"150.", "KES", 15000, nil}, // trailing dot tolerated
		{"1.234", "KES", 0, ErrInvalidAmount},
		{"abc", "KES", 0, ErrInvalidAmount},
		{"1.2.3", "KES", 0, ErrInvalidAmount},
		{"", "KES", 0, ErrInvalidAmount},
		{"1.0000001", "USDC", 0, ErrInvalidAmount},
		{"--5", "KES", 0, ErrInvalidAmount},
	}
	for _, tc := range cases {
		m, err := FromString(tc.in, tc.code)
		if tc.wantErr != nil {
			if !errors.Is(err, tc.wantErr) {
				t.Errorf("FromString(%q, %s) error = %v, want %v", tc.in, tc.code, err, tc.wantErr)
			}
			continue
		}
		if err != nil {
			t.Fatalf("FromString(%q, %s): %v", tc.in, tc.code, err)
		}
		if m.AmountMinor != tc.want {
			t.Errorf("FromString(%q, %s) = %d, want %d", tc.in, tc.code, m.AmountMinor, tc.want)
		}
	}
	if _, err := FromString("1", "ZZZ"); !errors.Is(err, ErrUnknownCurrency) {
		t.Errorf("unknown currency error = %v", err)
	}
}

func TestStringRoundtrip(t *testing.T) {
	values := []int64{0, 1, -1, 99, 100, 15000, -1234, 1 << 62, -(1 << 62), 999999999999}
	for _, code := range SupportedCurrencies() {
		for _, v := range values {
			m, err := New(v, code)
			if err != nil {
				t.Fatal(err)
			}
			back, err := FromString(m.String(), code)
			if err != nil {
				t.Fatalf("FromString(%q, %s): %v", m.String(), code, err)
			}
			if back != m {
				t.Errorf("roundtrip %s: got %v, want %v", m.String(), back, m)
			}
		}
	}
	// MinInt64 must not panic and must round-trip via big.Int parsing.
	m, _ := New(-1<<63, "KES")
	if m.String() == "" {
		t.Fatal("String() empty for MinInt64")
	}
	if back, err := FromString(m.String(), "KES"); err != nil || back.AmountMinor != -1<<63 {
		t.Errorf("MinInt64 roundtrip = %v, %v", back, err)
	}
}

func TestStringRendering(t *testing.T) {
	cases := []struct {
		m    Money
		want string
	}{
		{mustNew(t, 15000, "KES"), "150.00"},
		{mustNew(t, 1, "KES"), "0.01"},
		{mustNew(t, -1234, "EUR"), "-12.34"},
		{mustNew(t, 1500000, "USDC"), "1.500000"},
		{mustNew(t, 0, "USD"), "0.00"},
	}
	for _, tc := range cases {
		if got := tc.m.String(); got != tc.want {
			t.Errorf("String() = %q, want %q", got, tc.want)
		}
	}
}

func TestAddSub(t *testing.T) {
	a := mustNew(t, 100, "KES")
	b := mustNew(t, 50, "KES")
	c := mustNew(t, 25, "USD")

	s, err := a.Add(b)
	if err != nil || s.AmountMinor != 150 {
		t.Errorf("Add = %v, %v; want 150", s, err)
	}
	d, err := a.Sub(b)
	if err != nil || d.AmountMinor != 50 {
		t.Errorf("Sub = %v, %v; want 50", d, err)
	}
	if _, err := a.Add(c); !errors.Is(err, ErrCurrencyMismatch) {
		t.Errorf("mixed Add error = %v, want ErrCurrencyMismatch", err)
	}
	if _, err := a.Sub(c); !errors.Is(err, ErrCurrencyMismatch) {
		t.Errorf("mixed Sub error = %v, want ErrCurrencyMismatch", err)
	}

	big := mustNew(t, 1<<62, "KES")
	if _, err := big.Add(big); !errors.Is(err, ErrOverflow) {
		t.Errorf("overflow Add error = %v, want ErrOverflow", err)
	}
	neg := mustNew(t, -(1 << 62), "KES")
	if _, err := neg.Sub(mustNew(t, (1<<62)+1, "KES")); !errors.Is(err, ErrOverflow) {
		t.Errorf("overflow Sub error = %v, want ErrOverflow", err)
	}
	if _, err := mustNew(t, -(1<<62)-1, "KES").Add(mustNew(t, -(1 << 62), "KES")); !errors.Is(err, ErrOverflow) {
		t.Errorf("overflow Add error = %v, want ErrOverflow", err)
	}
}

func TestCompareAndPredicates(t *testing.T) {
	a := mustNew(t, 100, "KES")
	b := mustNew(t, 200, "KES")
	u := mustNew(t, 100, "USD")

	if c, _ := a.Compare(b); c != -1 {
		t.Errorf("Compare(100,200) = %d, want -1", c)
	}
	if c, _ := b.Compare(a); c != 1 {
		t.Errorf("Compare(200,100) = %d, want 1", c)
	}
	if c, _ := a.Compare(a); c != 0 {
		t.Errorf("Compare(100,100) = %d, want 0", c)
	}
	if _, err := a.Compare(u); !errors.Is(err, ErrCurrencyMismatch) {
		t.Errorf("mixed Compare error = %v", err)
	}
	if !a.Equal(a) || a.Equal(b) {
		t.Error("Equal broken")
	}
	if !a.IsPositive() || b.IsNegative() || !a.Negate().IsNegative() {
		t.Error("predicates broken")
	}
	if !a.Abs().Equal(a) || !a.Negate().Abs().Equal(a) {
		t.Error("Abs broken")
	}
	if z, _ := Zero("KES"); !z.IsZero() {
		t.Error("Zero broken")
	}
}

func mustNew(t *testing.T, amount int64, code string) Money {
	t.Helper()
	m, err := New(amount, code)
	if err != nil {
		t.Fatalf("New(%d, %s): %v", amount, code, err)
	}
	return m
}
