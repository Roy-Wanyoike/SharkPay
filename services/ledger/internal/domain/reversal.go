package domain

// ReversalRequest reverses one journal entry by creating a compensating
// entry (FR-302: reversal via compensation entry only, reason captured).
type ReversalRequest struct {
	EntryID    string
	Reason     string
	OperatorID string // empty for system-initiated reversals
}

// Validate checks the reversal request itself (entry reference, mandatory
// reason, optional operator).
func (r ReversalRequest) Validate() error {
	if !ValidUUID(r.EntryID) {
		return NewError(CodeInvalidUUID, "entry_id %q is not a UUID", r.EntryID)
	}
	if r.Reason == "" {
		return NewError(CodeInvalidReason, "a reversal requires a reason")
	}
	if err := ValidateReason(r.Reason); err != nil {
		return err
	}
	if r.OperatorID != "" && !ValidUUID(r.OperatorID) {
		return NewError(CodeInvalidUUID, "operator_id %q is not a UUID", r.OperatorID)
	}
	return nil
}

// ReversalTransactionKey derives the deterministic idempotency key of the
// reversal of entryID: "ops:rev:<entry id>". Retrying a reversal therefore
// replays the original reversal entry (idempotent), while any distinct
// second reversal of the same entry is rejected (double-reversal guard).
func ReversalTransactionKey(entryID string) string {
	return string(SourceOps) + ":rev:" + entryID
}

// InverseLegs returns the exact compensating legs of legs: the same
// accounts with debits and credits swapped (invariant #4).
func InverseLegs(legs []Leg) []Leg {
	out := make([]Leg, len(legs))
	for i, l := range legs {
		out[i] = Leg{AccountID: l.AccountID, Debit: l.Credit, Credit: l.Debit}
	}
	return out
}

// IsInverse reports whether reversal legs are the exact inverse of the
// original legs: identical account sets, and per account the reversal's
// total debits equal the original's total credits and vice versa.
func IsInverse(original, reversal []Leg) bool {
	o, r := legSums(original), legSums(reversal)
	if len(o) != len(r) {
		return false
	}
	for id, os := range o {
		rs, ok := r[id]
		if !ok || os[0] != rs[1] || os[1] != rs[0] {
			return false
		}
	}
	return true
}

// BuildReversal constructs the compensating transaction for orig: an
// ops-sourced reversal entry with deterministic key and the exact inverse
// legs. The store re-verifies pairing, double reversal, balance and wallet
// non-negativity under the locks (the SQL trigger is the final backstop).
func BuildReversal(orig EntryDetail, reason, operatorID string) (Transaction, error) {
	tx := Transaction{
		TransactionKey:  ReversalTransactionKey(orig.Entry.ID),
		Source:          SourceOps,
		SourceRef:       orig.Entry.SourceRef,
		EntryType:       EntryTypeReversal,
		ReversesEntryID: orig.Entry.ID,
		Reason:          reason,
		OperatorID:      operatorID,
		Postings:        InverseLegs(LegsFromPostings(orig.Postings)),
	}
	if err := ValidateTransaction(tx); err != nil {
		return Transaction{}, err
	}
	return tx, nil
}
