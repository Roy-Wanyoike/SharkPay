package money

import (
	"fmt"
	"math/big"
	"strings"
)

// Money is an exact monetary value in integer minor units, paired with one
// currency and that currency's fixed decimal exponent. Construct via New or
// FromString; never assemble the struct by hand unless the value has already
// passed Validate. Floats are forbidden anywhere in money code.
type Money struct {
	AmountMinor int64
	Currency    string
	Exponent    int8
}

// New creates a Money from minor units and a currency code
// (case-insensitive; whitespace tolerated).
func New(amountMinor int64, currency string) (Money, error) {
	code, err := NormalizeCurrency(currency)
	if err != nil {
		return Money{}, err
	}
	return Money{AmountMinor: amountMinor, Currency: code, Exponent: CurrencyTable[code]}, nil
}

// Zero returns a zero-value Money for a supported currency.
func Zero(currency string) (Money, error) {
	return New(0, currency)
}

// Validate reports whether the currency is supported and the exponent
// matches the currency table.
func (m Money) Validate() error {
	exp, err := ExponentFor(m.Currency)
	if err != nil {
		return err
	}
	if m.Exponent != exp {
		return fmt.Errorf("%w: %s requires exponent %d, got %d",
			ErrExponentMismatch, m.Currency, exp, m.Exponent)
	}
	return nil
}

// FromString parses a decimal string such as "150.00" or "-12.5" using
// integer math only — never floats. Fraction digits beyond the currency
// exponent are rejected (e.g. "1.234" for KES).
func FromString(s, currency string) (Money, error) {
	base, err := New(0, currency)
	if err != nil {
		return Money{}, err
	}
	s = strings.TrimSpace(s)
	if s == "" || s == "." || s == "-" || s == "+" {
		return Money{}, fmt.Errorf("%w: %q is not a decimal amount", ErrInvalidAmount, s)
	}
	neg := false
	switch s[0] {
	case '-':
		neg, s = true, s[1:]
	case '+':
		s = s[1:]
	}
	intPart, fracPart := s, ""
	if i := strings.IndexByte(s, '.'); i >= 0 {
		intPart, fracPart = s[:i], s[i+1:]
	}
	if intPart == "" {
		intPart = "0"
	}
	if !allDigits(intPart) || !allDigits(fracPart) {
		return Money{}, fmt.Errorf("%w: %q is not a decimal amount", ErrInvalidAmount, s)
	}
	if len(fracPart) > int(base.Exponent) {
		return Money{}, fmt.Errorf(
			"%w: %q has more than %d fraction digit(s) for %s",
			ErrInvalidAmount, s, base.Exponent, base.Currency)
	}
	fracPart += strings.Repeat("0", int(base.Exponent)-len(fracPart))
	v, ok := new(big.Int).SetString(intPart+fracPart, 10)
	if !ok {
		return Money{}, fmt.Errorf("%w: %q is not a decimal amount", ErrInvalidAmount, s)
	}
	var amt int64
	if neg {
		// The magnitude may equal |MinInt64| = 2^63, which is not an int64
		// but whose negation is. Compare against the exact bound.
		limit := new(big.Int).Neg(new(big.Int).SetInt64(-1 << 63))
		if v.Cmp(limit) > 0 {
			return Money{}, ErrOverflow
		}
		amt = new(big.Int).Neg(v).Int64()
	} else {
		if !v.IsInt64() {
			return Money{}, ErrOverflow
		}
		amt = v.Int64()
	}
	return Money{AmountMinor: amt, Currency: base.Currency, Exponent: base.Exponent}, nil
}

// String renders the value as a decimal string, e.g. "150.00" (KES) or
// "1.500000" (USDC). Safe for all int64 values including MinInt64.
func (m Money) String() string {
	neg := m.AmountMinor < 0
	var mag uint64
	if neg {
		mag = uint64(-(m.AmountMinor + 1)) + 1 // avoids MinInt64 overflow
	} else {
		mag = uint64(m.AmountMinor)
	}
	scale := uint64(pow10(m.Exponent))
	intPart := mag / scale
	frac := mag % scale
	var s string
	if m.Exponent > 0 {
		s = fmt.Sprintf("%d.%0*d", intPart, int(m.Exponent), frac)
	} else {
		s = fmt.Sprintf("%d", intPart)
	}
	if neg {
		return "-" + s
	}
	return s
}

// Add returns m+o. Requires the same currency; detects int64 overflow.
func (m Money) Add(o Money) (Money, error) {
	if err := m.sameCurrency(o); err != nil {
		return Money{}, err
	}
	s := m.AmountMinor + o.AmountMinor
	if (m.AmountMinor > 0 && o.AmountMinor > 0 && s < 0) ||
		(m.AmountMinor < 0 && o.AmountMinor < 0 && s > 0) {
		return Money{}, ErrOverflow
	}
	return Money{AmountMinor: s, Currency: m.Currency, Exponent: m.Exponent}, nil
}

// Sub returns m-o. Requires the same currency; detects int64 overflow.
func (m Money) Sub(o Money) (Money, error) {
	if err := m.sameCurrency(o); err != nil {
		return Money{}, err
	}
	d := m.AmountMinor - o.AmountMinor
	if (m.AmountMinor > 0 && o.AmountMinor < 0 && d < 0) ||
		(m.AmountMinor < 0 && o.AmountMinor > 0 && d > 0) {
		return Money{}, ErrOverflow
	}
	return Money{AmountMinor: d, Currency: m.Currency, Exponent: m.Exponent}, nil
}

// Negate returns -m. The negation of math.MinInt64 is not representable;
// callers working near that bound must check IsNegative before use.
func (m Money) Negate() Money {
	return Money{AmountMinor: -m.AmountMinor, Currency: m.Currency, Exponent: m.Exponent}
}

// Abs returns |m| (MinInt64 caveat as Negate).
func (m Money) Abs() Money {
	if m.AmountMinor < 0 {
		return m.Negate()
	}
	return m
}

func (m Money) IsZero() bool     { return m.AmountMinor == 0 }
func (m Money) IsNegative() bool { return m.AmountMinor < 0 }
func (m Money) IsPositive() bool { return m.AmountMinor > 0 }

// Equal reports currency and amount equality. Matching currency and amount
// imply matching exponent (the exponent is derived from the currency table).
func (m Money) Equal(o Money) bool {
	return m.Currency == o.Currency && m.AmountMinor == o.AmountMinor
}

// Compare returns -1, 0, or 1. Requires the same currency.
func (m Money) Compare(o Money) (int, error) {
	if err := m.sameCurrency(o); err != nil {
		return 0, err
	}
	switch {
	case m.AmountMinor < o.AmountMinor:
		return -1, nil
	case m.AmountMinor > o.AmountMinor:
		return 1, nil
	default:
		return 0, nil
	}
}

func (m Money) sameCurrency(o Money) error {
	if m.Currency != o.Currency {
		return fmt.Errorf("%w: %s vs %s", ErrCurrencyMismatch, m.Currency, o.Currency)
	}
	if m.Exponent != o.Exponent {
		return fmt.Errorf("%w: %s exponent %d vs %d",
			ErrExponentMismatch, m.Currency, m.Exponent, o.Exponent)
	}
	return nil
}

func allDigits(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}

// pow10 returns 10^e for 0 <= e <= 18 (covers every exponent in the table).
func pow10(e int8) int64 {
	p := int64(1)
	for i := int8(0); i < e; i++ {
		p *= 10
	}
	return p
}
