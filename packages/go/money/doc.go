// Package money implements SharkPay's canonical money representation.
//
// Money is a signed integer amount of minor units (int64) in a supported
// currency, together with the currency's minor-unit exponent (2 for
// KES/USD/EUR/GBP, 6 for USDC/USDT). The JSON shape matches the public API
// contract: {"amount_minor": 1500, "currency": "KES", "exponent": 2}.
//
// Invariants (docs/DATA-MODEL.md §1, docs/PRD.md §11):
//
//   - Integer-only: parsing, arithmetic, rendering and allocation use exact
//     integer math. Floats are never used, directly or indirectly.
//   - The exponent always matches the currency's registered exponent for
//     values constructed through this package (New, FromString,
//     UnmarshalJSON); hand-built struct literals should be passed through
//     Validate before use.
//   - Add and Sub require identical currency and exponent; mismatches return
//     ErrCurrencyMismatch.
//   - Allocate splits a value into parts whose sum equals the original to the
//     minor unit (largest-remainder method) — safe for split payments and fee
//     distribution.
//
// The zero value Money{} is not valid money; construct values with New,
// FromString or UnmarshalJSON, all of which validate the currency.
package money
