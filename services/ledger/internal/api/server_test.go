package api_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/google/uuid"

	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/api"
	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/domain"
	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/service"
	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/storage"
)

const token = "internal-dev-token"

func newServer(t *testing.T) (http.Handler, *service.Service, string, string) {
	t.Helper()
	svc := service.New(storage.NewFakeStore())
	h := api.New(svc, token)

	// provision a wallet + clearing account via the API
	owner := uuid.NewString()
	wallet := createAccount(t, h, map[string]any{
		"code": "wallet:usr_1:KES", "type": "wallet", "currency": "KES", "owner_principal": owner,
	})
	clearing := createAccount(t, h, map[string]any{
		"code": "honeycoin:clearing:KES", "type": "provider_clearing", "currency": "KES",
	})
	return h, svc, wallet, clearing
}

func createAccount(t *testing.T, h http.Handler, body map[string]any) string {
	t.Helper()
	w := do(t, h, http.MethodPost, "/internal/accounts", body, token)
	if w.Code != http.StatusCreated && w.Code != http.StatusOK {
		t.Fatalf("create account: status %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil || resp.ID == "" {
		t.Fatalf("create account decode: %v %s", err, w.Body.String())
	}
	return resp.ID
}

func do(t *testing.T, h http.Handler, method, path string, body any, tok string) *httptest.ResponseRecorder {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			t.Fatal(err)
		}
	}
	req := httptest.NewRequest(method, path, &buf)
	req.Header.Set("Content-Type", "application/json")
	if tok != "" {
		req.Header.Set("Authorization", "Bearer "+tok)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	return w
}

func fund(t *testing.T, h http.Handler, clearing, wallet string, amount int64) string {
	t.Helper()
	key := "payments:" + uuid.NewString() + ":capture"
	w := do(t, h, http.MethodPost, "/internal/transactions", map[string]any{
		"transaction_key": key,
		"source":          "payments",
		"source_ref":      uuid.NewString(),
		"entry_type":      "capture",
		"postings": []map[string]any{
			{"account_id": clearing, "debit": amount},
			{"account_id": wallet, "credit": amount},
		},
	}, token)
	if w.Code != http.StatusCreated {
		t.Fatalf("fund: status %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		EntryID string `json:"entry_id"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	return resp.EntryID
}

func TestHealthOpenWithoutAuth(t *testing.T) {
	h, _, _, _ := newServer(t)
	for _, p := range []string{"/healthz", "/readyz"} {
		w := do(t, h, http.MethodGet, p, nil, "")
		if w.Code != http.StatusOK {
			t.Fatalf("%s: status %d (probes must be open)", p, w.Code)
		}
	}
}

func TestAuthRequired(t *testing.T) {
	h, _, _, _ := newServer(t)
	w := do(t, h, http.MethodPost, "/internal/transactions", map[string]any{}, "")
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("missing token: status %d, want 401", w.Code)
	}
	w = do(t, h, http.MethodPost, "/internal/transactions", map[string]any{}, "wrong-token")
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("wrong token: status %d, want 401", w.Code)
	}
}

func TestPostTransactionFlow(t *testing.T) {
	h, _, wallet, clearing := newServer(t)
	entryID := fund(t, h, clearing, wallet, 10_000)

	// idempotent replay over HTTP: same key returns 200 with the original entry
	key := "payments:" + uuid.NewString() + ":capture"
	body := map[string]any{
		"transaction_key": key,
		"source":          "payments",
		"source_ref":      uuid.NewString(),
		"entry_type":      "capture",
		"postings": []map[string]any{
			{"account_id": clearing, "debit": 500},
			{"account_id": wallet, "credit": 500},
		},
	}
	first := do(t, h, http.MethodPost, "/internal/transactions", body, token)
	if first.Code != http.StatusCreated {
		t.Fatalf("first post: %d %s", first.Code, first.Body.String())
	}
	var resp struct {
		EntryID          string `json:"entry_id"`
		IdempotentReplay bool   `json:"idempotent_replay"`
	}
	json.Unmarshal(first.Body.Bytes(), &resp)
	second := do(t, h, http.MethodPost, "/internal/transactions", body, token)
	if second.Code != http.StatusOK {
		t.Fatalf("replay: status %d, want 200", second.Code)
	}
	var resp2 struct {
		EntryID          string `json:"entry_id"`
		IdempotentReplay bool   `json:"idempotent_replay"`
	}
	json.Unmarshal(second.Body.Bytes(), &resp2)
	if !resp2.IdempotentReplay || resp2.EntryID != resp.EntryID {
		t.Fatalf("replay response wrong: %+v", resp2)
	}
	_ = entryID
}

func TestPostTransactionValidationError(t *testing.T) {
	h, _, wallet, clearing := newServer(t)
	w := do(t, h, http.MethodPost, "/internal/transactions", map[string]any{
		"transaction_key": "payments:ref!bang",
		"source":          "payments",
		"source_ref":      uuid.NewString(),
		"entry_type":      "capture",
		"postings": []map[string]any{
			{"account_id": clearing, "debit": 100},
			{"account_id": wallet, "credit": 100},
		},
	}, token)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("bad key: status %d, want 400", w.Code)
	}
	var env struct {
		Error struct {
			Code      string `json:"code"`
			RequestID string `json:"request_id"`
		} `json:"error"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &env); err != nil {
		t.Fatal(err)
	}
	if env.Error.Code == "" || env.Error.RequestID == "" {
		t.Fatalf("error envelope incomplete: %s", w.Body.String())
	}

	// unbalanced entry → 422 with unbalanced_entry code
	w = do(t, h, http.MethodPost, "/internal/transactions", map[string]any{
		"transaction_key": "payments:" + uuid.NewString() + ":capture",
		"source":          "payments",
		"source_ref":      uuid.NewString(),
		"entry_type":      "capture",
		"postings": []map[string]any{
			{"account_id": clearing, "debit": 100},
			{"account_id": wallet, "credit": 90},
		},
	}, token)
	if w.Code != http.StatusUnprocessableEntity {
		t.Fatalf("unbalanced: status %d, want 422", w.Code)
	}
	if !strings.Contains(w.Body.String(), domain.CodeUnbalancedEntry) {
		t.Fatalf("unbalanced body: %s", w.Body.String())
	}
}

func TestReverseFlowOverHTTP(t *testing.T) {
	h, _, wallet, clearing := newServer(t)
	entryID := fund(t, h, clearing, wallet, 7_000)

	w := do(t, h, http.MethodPost, fmt.Sprintf("/internal/transactions/%s/reverse", entryID), map[string]any{
		"reason": "provider wire mismatch", "operator_id": uuid.NewString(),
	}, token)
	if w.Code != http.StatusCreated {
		t.Fatalf("reverse: status %d: %s", w.Code, w.Body.String())
	}
	var rev struct {
		EntryID         string `json:"entry_id"`
		EntryType       string `json:"entry_type"`
		ReversesEntryID string `json:"reverses_entry_id"`
	}
	json.Unmarshal(w.Body.Bytes(), &rev)
	if rev.EntryType != "reversal" || rev.ReversesEntryID != entryID {
		t.Fatalf("reversal response wrong: %+v", rev)
	}

	// retried reversal is an idempotent replay (200)
	w = do(t, h, http.MethodPost, fmt.Sprintf("/internal/transactions/%s/reverse", entryID), map[string]any{
		"reason": "provider wire mismatch",
	}, token)
	if w.Code != http.StatusOK {
		t.Fatalf("reverse retry: status %d, want 200", w.Code)
	}

	// balance is back to zero
	st := do(t, h, http.MethodGet, fmt.Sprintf("/internal/accounts/%s/statement", wallet), nil, token)
	var stmt struct {
		BalanceMinor int64 `json:"balance_minor"`
	}
	json.Unmarshal(st.Body.Bytes(), &stmt)
	if stmt.BalanceMinor != 0 {
		t.Fatalf("balance after reversal = %d, want 0", stmt.BalanceMinor)
	}
}

func TestStatementEndpoint(t *testing.T) {
	h, _, wallet, clearing := newServer(t)
	for i := 1; i <= 3; i++ {
		fund(t, h, clearing, wallet, int64(100*i))
	}
	w := do(t, h, http.MethodGet, fmt.Sprintf("/internal/accounts/%s/statement?limit=2", wallet), nil, token)
	if w.Code != http.StatusOK {
		t.Fatalf("statement: status %d: %s", w.Code, w.Body.String())
	}
	var page struct {
		BalanceMinor int64  `json:"balance_minor"`
		HasMore      bool   `json:"has_more"`
		NextCursor   string `json:"next_cursor"`
		Lines        []struct {
			PostingID         int64 `json:"posting_id"`
			BalanceAfterMinor int64 `json:"balance_after_minor"`
		} `json:"lines"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil {
		t.Fatal(err)
	}
	if len(page.Lines) != 2 || !page.HasMore || page.NextCursor == "" {
		t.Fatalf("page 1 wrong: %d lines, hasMore %v, cursor %q", len(page.Lines), page.HasMore, page.NextCursor)
	}
	// follow the cursor
	w = do(t, h, http.MethodGet,
		fmt.Sprintf("/internal/accounts/%s/statement?limit=2&cursor=%s", wallet, page.NextCursor), nil, token)
	if w.Code != http.StatusOK {
		t.Fatalf("page 2: status %d", w.Code)
	}
	var page2 struct {
		BalanceMinor int64 `json:"balance_minor"`
		Lines        []struct {
			BalanceAfterMinor int64 `json:"balance_after_minor"`
		} `json:"lines"`
	}
	json.Unmarshal(w.Body.Bytes(), &page2)
	if len(page2.Lines) != 1 || page2.BalanceMinor != 600 {
		t.Fatalf("page 2 wrong: %d lines, balance %d", len(page2.Lines), page2.BalanceMinor)
	}
}

func TestMethodNotAllowed(t *testing.T) {
	h, _, _, _ := newServer(t)
	w := do(t, h, http.MethodGet, "/internal/transactions", nil, token)
	if w.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET /internal/transactions: status %d, want 405", w.Code)
	}
}
