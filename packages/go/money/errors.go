package money

import (
	"errors"
)

// Sentinel errors returned by this package. Use errors.Is to test for them.
var (
	// ErrUnknownCurrency is returned when a currency code is not in the V1
	// currency table.
	ErrUnknownCurrency = errors.New("money: unknown currency")

	// ErrCurrencyMismatch is returned by cross-currency (or cross-exponent)
	// arithmetic such as Add, Sub and Compare.
	ErrCurrencyMismatch = errors.New("money: currency mismatch")

	// ErrExponentMismatch is returned when a Money value's exponent does not
	// match its currency's registered exponent (or is not positive).
	ErrExponentMismatch = errors.New("money: exponent mismatch")

	// ErrInvalidAmount is returned when an amount string cannot be parsed.
	ErrInvalidAmount = errors.New("money: invalid amount")

	// ErrOverflow is returned when a result would not fit in int64 minor
	// units.
	ErrOverflow = errors.New("money: amount out of int64 range")

	// ErrInvalidRatios is returned by Allocate when the ratios/total inputs
	// are not usable.
	ErrInvalidRatios = errors.New("money: invalid allocation ratios")
)
