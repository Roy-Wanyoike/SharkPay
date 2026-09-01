package domain

// Currency identifies a supported currency. Money is always integer minor
// units of the currency; floats are never used anywhere.
type Currency string

// V1 supported currency set (PRD §7 D2). Fiat majors use 2 decimal places;
// stablecoins use 6 (the token-standard minor unit).
const (
	KES  Currency = "KES"
	USD  Currency = "USD"
	EUR  Currency = "EUR"
	GBP  Currency = "GBP"
	USDC Currency = "USDC"
	USDT Currency = "USDT"
)

var currencies = map[Currency]struct{}{
	KES: {}, USD: {}, EUR: {}, GBP: {}, USDC: {}, USDT: {},
}

// Valid reports whether c is a supported currency code.
func (c Currency) Valid() bool {
	_, ok := currencies[c]
	return ok
}

// Exponent returns the number of decimal places of the currency's minor
// unit (KES/USD/EUR/GBP: 2; USDC/USDT: 6). Used for display formatting only;
// ledger arithmetic is always integer minor units.
func (c Currency) Exponent() int {
	switch c {
	case USDC, USDT:
		return 6
	default:
		return 2
	}
}

// ParseCurrency validates s as an exact, uppercase, supported currency code.
func ParseCurrency(s string) (Currency, error) {
	c := Currency(s)
	if !c.Valid() {
		return "", NewError(CodeInvalidCurrency,
			"unsupported currency %q (supported: KES USD EUR GBP USDC USDT)", s)
	}
	return c, nil
}
