package money

import (
	"encoding/json"
	"errors"
	"math"
	"math/rand"
	"strings"
	"testing"
)

func TestMarshalJSON(t *testing.T) {
	tests := []struct {
		name    string
		m       Money
		want    string
		wantErr error
	}{
		{name: "KES", m: mustNew(t, 1500, "KES"), want: `{"amount_minor":1500,"currency":"KES","exponent":2}`},
		{name: "negative USD", m: mustNew(t, -99, "USD"), want: `{"amount_minor":-99,"currency":"USD","exponent":2}`},
		{name: "USDC", m: mustNew(t, 2000001, "USDC"), want: `{"amount_minor":2000001,"currency":"USDC","exponent":6}`},
		{name: "zero", m: mustNew(t, 0, "GBP"), want: `{"amount_minor":0,"currency":"GBP","exponent":2}`},
		{name: "MinInt64", m: mustNew(t, math.MinInt64, "EUR"), want: `{"amount_minor":-9223372036854775808,"currency":"EUR","exponent":2}`},
		{name: "unknown currency rejected", m: Money{AmountMinor: 1500, Currency: "JPY", Exponent: 2}, wantErr: ErrUnknownCurrency},
		{name: "exponent mismatch rejected", m: Money{AmountMinor: 1500, Currency: "KES", Exponent: 6}, wantErr: ErrExponentMismatch},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := json.Marshal(tc.m)
			if tc.wantErr != nil {
				if !errors.Is(err, tc.wantErr) {
					t.Fatalf("Marshal error = %v, want %v", err, tc.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatalf("Marshal unexpected error: %v", err)
			}
			if string(got) != tc.want {
				t.Errorf("Marshal = %s, want %s", got, tc.want)
			}
		})
	}
}

func TestUnmarshalJSON(t *testing.T) {
	tests := []struct {
		name    string
		in      string
		want    Money
		wantErr bool
	}{
		{name: "KES", in: `{"amount_minor":1500,"currency":"KES","exponent":2}`, want: mustNew(t, 1500, "KES")},
		{name: "negative", in: `{"amount_minor":-1500,"currency":"KES","exponent":2}`, want: mustNew(t, -1500, "KES")},
		{name: "USDC", in: `{"amount_minor":1,"currency":"USDC","exponent":6}`, want: mustNew(t, 1, "USDC")},
		{name: "unknown currency", in: `{"amount_minor":1,"currency":"JPY","exponent":2}`, wantErr: true},
		{name: "exponent mismatch", in: `{"amount_minor":1,"currency":"KES","exponent":6}`, wantErr: true},
		{name: "stablecoin wrong exponent", in: `{"amount_minor":1,"currency":"USDC","exponent":2}`, wantErr: true},
		{name: "missing amount_minor", in: `{"currency":"KES","exponent":2}`, wantErr: true},
		{name: "missing currency", in: `{"amount_minor":1,"exponent":2}`, wantErr: true},
		{name: "missing exponent", in: `{"amount_minor":1,"currency":"KES"}`, wantErr: true},
		{name: "null body", in: `null`, wantErr: true},
		{name: "empty object", in: `{}`, wantErr: true},
		{name: "malformed json", in: `{"amount_minor":`, wantErr: true},
		{name: "string amount", in: `{"amount_minor":"1","currency":"KES","exponent":2}`, wantErr: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var got Money
			err := json.Unmarshal([]byte(tc.in), &got)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("Unmarshal(%s) = %+v, want error", tc.in, got)
				}
				return
			}
			if err != nil {
				t.Fatalf("Unmarshal(%s) unexpected error: %v", tc.in, err)
			}
			if got != tc.want {
				t.Errorf("Unmarshal(%s) = %+v, want %+v", tc.in, got, tc.want)
			}
		})
	}
}

func TestJSONRoundtripProperty(t *testing.T) {
	rng := rand.New(rand.NewSource(2024))
	currencies := SupportedCurrencies()
	for i := 0; i < 200; i++ {
		cur := currencies[rng.Intn(len(currencies))]
		var amount int64
		switch rng.Intn(3) {
		case 0:
			amount = rng.Int63()
		case 1:
			amount = -rng.Int63() - 1
		default:
			amount = int64(rng.Intn(1000000)) - 500000
		}
		m := mustNew(t, amount, cur)
		data, err := json.Marshal(m)
		if err != nil {
			t.Fatalf("iteration %d: Marshal(%v) error: %v", i, m, err)
		}
		var back Money
		if err := json.Unmarshal(data, &back); err != nil {
			t.Fatalf("iteration %d: Unmarshal(%s) error: %v", i, data, err)
		}
		if back != m {
			t.Fatalf("iteration %d: roundtrip %s gave %+v, want %+v", i, data, back, m)
		}
	}
}

type receipt struct {
	Total Money `json:"total"`
	Fee   Money `json:"fee"`
}

func TestJSONInStruct(t *testing.T) {
	in := `{"total":{"amount_minor":1500,"currency":"KES","exponent":2},"fee":{"amount_minor":75,"currency":"KES","exponent":2}}`
	var r receipt
	if err := json.Unmarshal([]byte(in), &r); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}
	if r.Total.AmountMinor != 1500 || r.Fee.AmountMinor != 75 {
		t.Fatalf("parsed %+v, want totals 1500/75", r)
	}

	out, err := json.Marshal(r)
	if err != nil {
		t.Fatalf("Marshal error: %v", err)
	}
	if string(out) != in {
		t.Errorf("Marshal = %s, want %s", out, in)
	}
}

func TestJSONErrorMessages(t *testing.T) {
	var m Money
	err := json.Unmarshal([]byte(`{"amount_minor":1,"currency":"JPY","exponent":2}`), &m)
	if err == nil || !strings.Contains(err.Error(), "JPY") {
		t.Errorf("error %v should mention the offending currency JPY", err)
	}
	err = json.Unmarshal([]byte(`{"amount_minor":1,"currency":"KES","exponent":6}`), &m)
	if err == nil || !strings.Contains(err.Error(), "exponent") {
		t.Errorf("error %v should mention the exponent mismatch", err)
	}
}
