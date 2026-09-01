package money

import (
	"encoding/json"
	"fmt"
)

// moneyJSON is the wire shape of money in the public API
// (docs/API-CONTRACTS.md §1.6):
//
//	{"amount_minor": 1500, "currency": "KES", "exponent": 2}
type moneyJSON struct {
	AmountMinor int64  `json:"amount_minor"`
	Currency    string `json:"currency"`
	Exponent    int8   `json:"exponent"`
}

// moneyJSONIn is the decoding shape: pointers distinguish "absent" from
// "zero", so missing fields can be reported precisely.
type moneyJSONIn struct {
	AmountMinor *int64  `json:"amount_minor"`
	Currency    *string `json:"currency"`
	Exponent    *int8   `json:"exponent"`
}

// MarshalJSON renders the canonical API money object. Money values that
// would be rejected by the API contract (unknown currency, exponent not
// matching the currency table) fail with an error instead of emitting
// invalid money.
func (m Money) MarshalJSON() ([]byte, error) {
	if err := m.Validate(); err != nil {
		return nil, err
	}
	return json.Marshal(moneyJSON{
		AmountMinor: m.AmountMinor,
		Currency:    m.Currency,
		Exponent:    m.Exponent,
	})
}

// UnmarshalJSON parses the canonical API money object, validating the
// currency and requiring the exponent to match the currency table. Missing
// fields, unknown currencies and mismatched exponents are errors.
func (m *Money) UnmarshalJSON(data []byte) error {
	var raw moneyJSONIn
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	if raw.AmountMinor == nil || raw.Currency == nil || raw.Exponent == nil {
		return fmt.Errorf("money: amount_minor, currency and exponent are all required")
	}
	parsed, err := New(*raw.AmountMinor, *raw.Currency)
	if err != nil {
		return err
	}
	if parsed.Exponent != *raw.Exponent {
		return fmt.Errorf("%w: %s requires exponent %d, got %d",
			ErrExponentMismatch, parsed.Currency, parsed.Exponent, *raw.Exponent)
	}
	*m = parsed
	return nil
}
