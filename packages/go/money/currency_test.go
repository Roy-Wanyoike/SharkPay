package money

import (
	"errors"
	"testing"
)

func TestCurrencyTable(t *testing.T) {
	want := map[string]int8{
		"KES":  2,
		"USD":  2,
		"EUR":  2,
		"GBP":  2,
		"USDC": 6,
		"USDT": 6,
	}
	if len(CurrencyTable) != len(want) {
		t.Fatalf("CurrencyTable has %d entries, want %d", len(CurrencyTable), len(want))
	}
	for code, exp := range want {
		if got, ok := CurrencyTable[code]; !ok || got != exp {
			t.Errorf("CurrencyTable[%s] = (%d, %v), want (%d, true)", code, got, ok, exp)
		}
	}
}

func TestExponentFor(t *testing.T) {
	tests := []struct {
		currency string
		want     int8
		wantErr  error
	}{
		{currency: "KES", want: 2},
		{currency: "kes", want: 2},
		{currency: " Kes ", want: 2},
		{currency: "USDC", want: 6},
		{currency: "usdt", want: 6},
		{currency: "JPY", wantErr: ErrUnknownCurrency},
		{currency: "", wantErr: ErrUnknownCurrency},
		{currency: "EURO", wantErr: ErrUnknownCurrency},
	}
	for _, tc := range tests {
		got, err := ExponentFor(tc.currency)
		if tc.wantErr != nil {
			if !errors.Is(err, tc.wantErr) {
				t.Errorf("ExponentFor(%q) error = %v, want %v", tc.currency, err, tc.wantErr)
			}
			continue
		}
		if err != nil {
			t.Errorf("ExponentFor(%q) unexpected error: %v", tc.currency, err)
			continue
		}
		if got != tc.want {
			t.Errorf("ExponentFor(%q) = %d, want %d", tc.currency, got, tc.want)
		}
	}
}

func TestSupportedCurrencies(t *testing.T) {
	got := SupportedCurrencies()
	want := []string{"EUR", "GBP", "KES", "USD", "USDC", "USDT"}
	if len(got) != len(want) {
		t.Fatalf("SupportedCurrencies() = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("SupportedCurrencies() = %v, want %v", got, want)
		}
	}
	// Must not alias the internal table for mutation safety.
	got[0] = "XXX"
	if _, ok := CurrencyTable["XXX"]; ok {
		t.Fatalf("mutating SupportedCurrencies() result modified CurrencyTable")
	}
}

func TestIsSupported(t *testing.T) {
	tests := []struct {
		currency string
		want     bool
	}{
		{currency: "KES", want: true},
		{currency: "usdc", want: true},
		{currency: "JPY", want: false},
		{currency: "", want: false},
	}
	for _, tc := range tests {
		if got := IsSupported(tc.currency); got != tc.want {
			t.Errorf("IsSupported(%q) = %v, want %v", tc.currency, got, tc.want)
		}
	}
}

func TestNormalizeCurrency(t *testing.T) {
	got, err := NormalizeCurrency(" kes ")
	if err != nil || got != "KES" {
		t.Errorf("NormalizeCurrency(\" kes \") = (%q, %v), want (\"KES\", nil)", got, err)
	}
	if got, err := NormalizeCurrency("AUD"); !errors.Is(err, ErrUnknownCurrency) || got != "" {
		t.Errorf("NormalizeCurrency(AUD) = (%q, %v), want (\"\", ErrUnknownCurrency)", got, err)
	}
}
