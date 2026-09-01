package domain

// Posting is a single leg of a journal entry, as persisted: money moves from
// debit accounts to credit accounts. Exactly one of Debit/Credit is nonzero
// (postings table CHECK), amounts are minor units.
type Posting struct {
	ID        int64  `json:"id"` // bigserial, insertion (time) ordered
	EntryID   string `json:"entry_id"`
	AccountID string `json:"account_id"`
	Debit     int64  `json:"debit"`  // minor units
	Credit    int64  `json:"credit"` // minor units
}

// Leg is the input form of a posting (before persistence assigns ids).
type Leg struct {
	AccountID string `json:"account_id"`
	Debit     int64  `json:"debit"`
	Credit    int64  `json:"credit"`
}

const (
	// MaxLegMinorUnits bounds a single posting leg (1e15 minor units =
	// e.g. 10 trillion major units at exponent 2). Every realistic amount
	// fits, while per-entry sums of at most MaxPostingsPerEntry legs can
	// never overflow int64.
	MaxLegMinorUnits int64 = 1_000_000_000_000_000

	// MaxPostingsPerEntry bounds journal entry size.
	MaxPostingsPerEntry = 64
)

// Validate enforces the postings-table CHECK semantics in the domain:
// valid account reference, non-negative amounts, exactly one nonzero side,
// and the overflow-safety bound.
func (l Leg) Validate() error {
	if !ValidUUID(l.AccountID) {
		return NewError(CodeInvalidUUID, "posting account_id %q is not a UUID", l.AccountID)
	}
	if l.Debit < 0 || l.Credit < 0 {
		return NewError(CodeInvalidPosting, "posting amounts must be non-negative (debit=%d credit=%d)", l.Debit, l.Credit)
	}
	if l.Debit > MaxLegMinorUnits || l.Credit > MaxLegMinorUnits {
		return NewError(CodeInvalidPosting, "posting amount exceeds maximum %d minor units (debit=%d credit=%d)", MaxLegMinorUnits, l.Debit, l.Credit)
	}
	switch {
	case l.Debit == 0 && l.Credit == 0:
		return NewError(CodeInvalidPosting, "posting must debit or credit a nonzero amount")
	case l.Debit != 0 && l.Credit != 0:
		return NewError(CodeInvalidPosting, "posting cannot have both debit and credit nonzero (debit=%d credit=%d)", l.Debit, l.Credit)
	}
	return nil
}

// LegsFromPostings strips persistence ids from postings.
func LegsFromPostings(ps []Posting) []Leg {
	legs := make([]Leg, len(ps))
	for i, p := range ps {
		legs[i] = Leg{AccountID: p.AccountID, Debit: p.Debit, Credit: p.Credit}
	}
	return legs
}

// legSums aggregates legs per account: [debit total, credit total].
// Multiple legs on the same account within one entry are summed (the SQL
// invariant triggers use the same aggregation).
func legSums(legs []Leg) map[string][2]int64 {
	sums := make(map[string][2]int64, len(legs))
	for _, l := range legs {
		s := sums[l.AccountID]
		s[0] += l.Debit
		s[1] += l.Credit
		sums[l.AccountID] = s
	}
	return sums
}
