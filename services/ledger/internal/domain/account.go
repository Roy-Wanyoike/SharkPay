package domain

import "time"

// AccountType enumerates the chart of accounts (DATA-MODEL §2).
type AccountType string

const (
	AccountTypeWallet           AccountType = "wallet"            // one per principal×currency balance container
	AccountTypeProviderClearing AccountType = "provider_clearing" // in-flight funds at a provider
	AccountTypeFees             AccountType = "fees"              // revenue recognition
	AccountTypeFxPosition       AccountType = "fx_position"       // FX conversion legs per pair
	AccountTypeSuspense         AccountType = "suspense"          // ops-owned unresolved breaks
	AccountTypeSettlement       AccountType = "settlement"        // provider settlement variance
)

var accountTypes = map[AccountType]struct{}{
	AccountTypeWallet: {}, AccountTypeProviderClearing: {}, AccountTypeFees: {},
	AccountTypeFxPosition: {}, AccountTypeSuspense: {}, AccountTypeSettlement: {},
}

// ParseAccountType validates s against the chart of accounts.
func ParseAccountType(s string) (AccountType, error) {
	t := AccountType(s)
	if _, ok := accountTypes[t]; !ok {
		return "", NewError(CodeInvalidAccountType,
			"invalid account type %q (valid: wallet, provider_clearing, fees, fx_position, suspense, settlement)", s)
	}
	return t, nil
}

// AccountStatus is the lifecycle state of an account. Wallet accounts move
// active ⇄ frozen by compliance and active → closed only at zero balance
// (STATE-MACHINES §5).
type AccountStatus string

const (
	AccountStatusActive AccountStatus = "active"
	AccountStatusFrozen AccountStatus = "frozen"
	AccountStatusClosed AccountStatus = "closed"
)

var accountStatuses = map[AccountStatus]struct{}{
	AccountStatusActive: {}, AccountStatusFrozen: {}, AccountStatusClosed: {},
}

// ParseAccountStatus validates s.
func ParseAccountStatus(s string) (AccountStatus, error) {
	st := AccountStatus(s)
	if _, ok := accountStatuses[st]; !ok {
		return "", NewError(CodeInvalidAccountStatus,
			"invalid account status %q (valid: active, frozen, closed)", s)
	}
	return st, nil
}

// Account is a row of the ledger's chart of accounts (DATA-MODEL §3.1).
// OwnerPrincipal is empty for internal accounts.
type Account struct {
	ID             string        `json:"id"`
	Code           string        `json:"code"`
	Type           AccountType   `json:"type"`
	Currency       Currency      `json:"currency"`
	OwnerPrincipal string        `json:"owner_principal,omitempty"`
	Status         AccountStatus `json:"status"`
	CreatedAt      time.Time     `json:"created_at"`
}

// Validate checks a new account request: code format, type, currency,
// owner, status.
func (a Account) Validate() error {
	if err := validateAccountCode(a.Code); err != nil {
		return err
	}
	if _, err := ParseAccountType(string(a.Type)); err != nil {
		return err
	}
	if _, err := ParseCurrency(string(a.Currency)); err != nil {
		return err
	}
	if a.OwnerPrincipal != "" && !ValidUUID(a.OwnerPrincipal) {
		return NewError(CodeInvalidUUID, "owner_principal %q is not a UUID", a.OwnerPrincipal)
	}
	// Wallet accounts are balance containers per principal×currency
	// (DATA-MODEL §2): they must name their owning principal.
	if a.Type == AccountTypeWallet && a.OwnerPrincipal == "" {
		return NewError(CodeInvalidRequest, "wallet accounts require owner_principal")
	}
	if a.Status == "" {
		a.Status = AccountStatusActive
	}
	if _, err := ParseAccountStatus(string(a.Status)); err != nil {
		return err
	}
	return nil
}

// validateAccountCode checks the human-readable account code
// (e.g. "wallet:usr_123:KES", "fxpos:KES/USD:KES").
func validateAccountCode(code string) error {
	if len(code) < 3 || len(code) > 128 {
		return NewError(CodeInvalidAccountCode, "account code must be 3-128 characters, got %d", len(code))
	}
	for _, r := range code {
		if !isCodeChar(r) {
			return NewError(CodeInvalidAccountCode, "account code %q contains invalid character %q", code, r)
		}
	}
	return nil
}

func isCodeChar(r rune) bool {
	switch {
	case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
		return true
	case r == ':' || r == '_' || r == '-' || r == '.' || r == '/':
		return true
	}
	return false
}

// PostingAllowedOnAccount decides whether an entry of entryType may post to
// an account in the given status. New money movement (hold, capture, fee,
// fx) requires an active account. Unwinding and correction entries
// (release, reversal, adjustment) are always accepted so in-flight funds
// can never be stranded on a frozen or closed account — the ledger must
// always be able to unwind (PRD §5: fail closed on money, open on features).
func PostingAllowedOnAccount(entryType EntryType, status AccountStatus) bool {
	switch entryType {
	case EntryTypeRelease, EntryTypeReversal, EntryTypeAdjustment:
		return true
	default:
		return status == AccountStatusActive
	}
}
