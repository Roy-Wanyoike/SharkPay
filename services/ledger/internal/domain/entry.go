package domain

import "time"

// Source identifies the business domain that requested a posting
// (DATA-MODEL §3.1).
type Source string

const (
	SourcePayments  Source = "payments"
	SourcePayouts   Source = "payouts"
	SourceTransfers Source = "transfers"
	SourceFx        Source = "fx"
	SourceFees      Source = "fees"
	SourceOps       Source = "ops"
)

var sources = map[Source]struct{}{
	SourcePayments: {}, SourcePayouts: {}, SourceTransfers: {},
	SourceFx: {}, SourceFees: {}, SourceOps: {},
}

// ParseSource validates s.
func ParseSource(s string) (Source, error) {
	src := Source(s)
	if _, ok := sources[src]; !ok {
		return "", NewError(CodeInvalidSource,
			"invalid source %q (valid: payments, payouts, transfers, fx, fees, ops)", s)
	}
	return src, nil
}

// EntryType describes the business phase of a journal entry
// (STATE-MACHINES §1: hold → release | capture; corrections are reversals).
type EntryType string

const (
	EntryTypeHold       EntryType = "hold"
	EntryTypeRelease    EntryType = "release"
	EntryTypeCapture    EntryType = "capture"
	EntryTypeReversal   EntryType = "reversal"
	EntryTypeFee        EntryType = "fee"
	EntryTypeFx         EntryType = "fx"
	EntryTypeAdjustment EntryType = "adjustment"
)

var entryTypes = map[EntryType]struct{}{
	EntryTypeHold: {}, EntryTypeRelease: {}, EntryTypeCapture: {},
	EntryTypeReversal: {}, EntryTypeFee: {}, EntryTypeFx: {}, EntryTypeAdjustment: {},
}

// ParseEntryType validates s.
func ParseEntryType(s string) (EntryType, error) {
	t := EntryType(s)
	if _, ok := entryTypes[t]; !ok {
		return "", NewError(CodeInvalidEntryType,
			"invalid entry_type %q (valid: hold, release, capture, reversal, fee, fx, adjustment)", s)
	}
	return t, nil
}

// JournalEntry is one business transaction posting (DATA-MODEL §3.1).
// ReversesEntryID is empty except on reversal entries, which reference the
// entry they compensate (invariant #4).
type JournalEntry struct {
	ID              string    `json:"id"`
	TransactionKey  string    `json:"transaction_key"`
	Source          Source    `json:"source"`
	SourceRef       string    `json:"source_ref"`
	EntryType       EntryType `json:"entry_type"`
	ReversesEntryID string    `json:"reverses_entry_id,omitempty"`
	Reason          string    `json:"reason,omitempty"`
	OperatorID      string    `json:"operator_id,omitempty"`
	CreatedAt       time.Time `json:"created_at"`
}
