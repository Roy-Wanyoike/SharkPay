package money

import (
	"fmt"
	"sort"
	"strings"
)

// CurrencyTable maps each supported currency code to its minor-unit exponent.
// ISO 4217 alpha codes are 3 letters; stablecoin codes (USDC/USDT) use 4.
// V1 currencies per docs/PRD.md §7 (D2): KES USD EUR GBP USDC USDT.
var CurrencyTable = map[string]int8{
	"KES":  2,
	"USD":  2,
	"EUR":  2,
	"GBP":  2,
	"USDC": 6,
	"USDT": 6,
}

// SupportedCurrencies returns the supported currency codes, sorted.
func SupportedCurrencies() []string {
	codes := make([]string, 0, len(CurrencyTable))
	for code := range CurrencyTable {
		codes = append(codes, code)
	}
	sort.Strings(codes)
	return codes
}

// IsSupported reports whether currency (case-insensitive) is known.
func IsSupported(currency string) bool {
	_, err := ExponentFor(currency)
	return err == nil
}

// ExponentFor returns the minor-unit exponent of a supported currency
// (case-insensitive lookup).
func ExponentFor(currency string) (int8, error) {
	cur, err := NormalizeCurrency(currency)
	if err != nil {
		return 0, err
	}
	return CurrencyTable[cur], nil
}

// NormalizeCurrency validates and canonicalises a currency code: surrounding
// whitespace is trimmed and the code is upper-cased; unknown codes return
// ErrUnknownCurrency.
func NormalizeCurrency(currency string) (string, error) {
	cur := strings.ToUpper(strings.TrimSpace(currency))
	if _, ok := CurrencyTable[cur]; !ok {
		return "", fmt.Errorf("%w: %q", ErrUnknownCurrency, currency)
	}
	return cur, nil
}
