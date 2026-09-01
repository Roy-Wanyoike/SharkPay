// Package api exposes the ledger's internal JSON/HTTP surface — the only
// entry points into the ledger (ARCHITECTURE §1 rule 2: every other service
// requests postings through this API). Transport is stdlib net/http.
//
// Routes:
//
//	POST /internal/transactions                       PostTransaction
//	POST /internal/transactions/{id}/reverse          ReverseTransaction
//	GET  /internal/accounts/{id}/statement            GetStatement
//	POST /internal/accounts                           EnsureAccount (provisioning)
//	GET  /healthz / readyz                            probes
//
// In production the listener sits behind mTLS-terminated ingress; an
// optional shared bearer token (INTERNAL_API_TOKEN) provides a second factor.
package api

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"

	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/domain"
	"github.com/Roy-Wanyoike/SharkPay/services/ledger/internal/service"
)

// Server wires the service into HTTP handlers.
type Server struct {
	svc   *service.Service
	token string // optional shared bearer; empty disables auth (dev/mTLS-only)
}

// New builds the HTTP handler for the ledger service.
func New(svc *service.Service, internalToken string) http.Handler {
	s := &Server{svc: svc, token: internalToken}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /internal/transactions", s.handlePostTransaction)
	mux.HandleFunc("POST /internal/transactions/{id}/reverse", s.handleReverseTransaction)
	mux.HandleFunc("GET /internal/accounts/{id}/statement", s.handleStatement)
	mux.HandleFunc("POST /internal/accounts", s.handleCreateAccount)
	mux.HandleFunc("GET /healthz", s.handleHealth)
	mux.HandleFunc("GET /readyz", s.handleReady)
	return s.middleware(mux)
}

// ---------------------------------------------------------------------------
// middleware: request id → logging → auth → handler, recover on top
// ---------------------------------------------------------------------------

type ctxKey int

const requestIDKey ctxKey = 1

func requestID(r *http.Request) string {
	if id, ok := r.Context().Value(requestIDKey).(string); ok {
		return id
	}
	return ""
}

func (s *Server) middleware(h http.Handler) http.Handler {
	return recoverMW(requestIDMW(authMW(h, s.token)))
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func requestIDMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id, err := uuid.NewV7()
		reqID := id.String()
		if err != nil {
			reqID = uuid.NewString()
		}
		w.Header().Set("X-Request-Id", reqID)
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		start := time.Now()
		next.ServeHTTP(rec, r.WithContext(context.WithValue(r.Context(), requestIDKey, reqID)))
		slog.Info("http_request",
			"request_id", reqID,
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"duration_ms", time.Since(start).Milliseconds(),
		)
	})
}

func recoverMW(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				slog.Error("panic recovered", "request_id", requestID(r), "panic", rec)
				writeJSON(w, http.StatusInternalServerError, errorEnvelope{Error: errorBody{
					Code: domain.CodeInternal, Message: "internal error", RequestID: requestID(r),
				}})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// authMW enforces the optional shared bearer token. Probes are always open.
func authMW(next http.Handler, token string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if token == "" || r.URL.Path == "/healthz" || r.URL.Path == "/readyz" {
			next.ServeHTTP(w, r)
			return
		}
		const prefix = "Bearer "
		got := r.Header.Get("Authorization")
		if len(got) <= len(prefix) || subtle.ConstantTimeCompare([]byte(got[len(prefix):]), []byte(token)) != 1 {
			writeJSON(w, http.StatusUnauthorized, errorEnvelope{Error: errorBody{
				Code: "unauthorized", Message: "missing or invalid bearer token", RequestID: requestID(r),
			}})
			return
		}
		next.ServeHTTP(w, r)
	})
}

// ---------------------------------------------------------------------------
// JSON helpers & error envelope (API-CONTRACTS §1.4)
// ---------------------------------------------------------------------------

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.Error("write response", "err", err)
	}
}

type errorEnvelope struct {
	Error errorBody `json:"error"`
}

type errorBody struct {
	Code      string         `json:"code"`
	Message   string         `json:"message"`
	RequestID string         `json:"request_id"`
	Details   map[string]any `json:"details,omitempty"`
}

func (s *Server) writeError(w http.ResponseWriter, r *http.Request, err error) {
	var derr *domain.Error
	if !errors.As(err, &derr) {
		slog.Error("internal_error", "err", err, "request_id", requestID(r))
		derr = domain.ErrInternal
	}
	writeJSON(w, statusForCode(derr.Code), errorEnvelope{Error: errorBody{
		Code:      derr.Code,
		Message:   derr.Message,
		RequestID: requestID(r),
	}})
}

func statusForCode(code string) int {
	switch code {
	case domain.CodeUnbalancedEntry, domain.CodeInsufficientFunds, domain.CodeReversalMismatch:
		return http.StatusUnprocessableEntity
	case domain.CodeNotFound, domain.CodeAccountNotFound, domain.CodeEntryNotFound:
		return http.StatusNotFound
	case domain.CodeAccountInactive, domain.CodeAccountConflict, domain.CodeAlreadyReversed,
		domain.CodeReversalOfReversal, domain.CodeIdempotencyConflict:
		return http.StatusConflict
	case domain.CodeInternal:
		return http.StatusInternalServerError
	default:
		return http.StatusBadRequest
	}
}

func decodeJSON(w http.ResponseWriter, r *http.Request, v any) error {
	// 1 MiB is generous for a ≤64-leg transaction and blocks abuse.
	return json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(v)
}

// ---------------------------------------------------------------------------
// handlers
// ---------------------------------------------------------------------------

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) handleReady(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.svc.Ping(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}

func (s *Server) handlePostTransaction(w http.ResponseWriter, r *http.Request) {
	var req postTransactionRequest
	if err := decodeJSON(w, r, &req); err != nil {
		s.writeError(w, r, domain.NewError(domain.CodeInvalidRequest, "invalid JSON body: %v", err))
		return
	}
	result, err := s.svc.PostTransaction(r.Context(), service.PostRequest{
		TransactionKey: req.TransactionKey,
		Source:         domain.Source(req.Source),
		SourceRef:      req.SourceRef,
		EntryType:      domain.EntryType(req.EntryType),
		Reason:         req.Reason,
		OperatorID:     req.OperatorID,
		Postings:       legsFromJSON(req.Postings),
	})
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	status := http.StatusCreated
	if result.Replay {
		status = http.StatusOK
		w.Header().Set("X-Idempotent-Replay", "true")
	}
	writeJSON(w, status, transactionResponseFrom(result))
}

func (s *Server) handleReverseTransaction(w http.ResponseWriter, r *http.Request) {
	entryID := r.PathValue("id")
	var req reverseRequest
	if err := decodeJSON(w, r, &req); err != nil {
		s.writeError(w, r, domain.NewError(domain.CodeInvalidRequest, "invalid JSON body: %v", err))
		return
	}
	result, err := s.svc.ReverseTransaction(r.Context(), domain.ReversalRequest{
		EntryID:    entryID,
		Reason:     req.Reason,
		OperatorID: req.OperatorID,
	})
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	status := http.StatusCreated
	if result.Replay {
		status = http.StatusOK
		w.Header().Set("X-Idempotent-Replay", "true")
	}
	writeJSON(w, status, transactionResponseFrom(result))
}

func (s *Server) handleStatement(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit := 0
	if ls := q.Get("limit"); ls != "" {
		n, err := strconv.Atoi(ls)
		if err != nil {
			s.writeError(w, r, domain.NewError(domain.CodeInvalidLimit, "invalid limit %q", ls))
			return
		}
		limit = n
	}
	stmt, err := s.svc.GetStatement(r.Context(), r.PathValue("id"), q.Get("cursor"), limit)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, statementResponseFrom(stmt))
}

func (s *Server) handleCreateAccount(w http.ResponseWriter, r *http.Request) {
	var req createAccountRequest
	if err := decodeJSON(w, r, &req); err != nil {
		s.writeError(w, r, domain.NewError(domain.CodeInvalidRequest, "invalid JSON body: %v", err))
		return
	}
	owner := ""
	if req.OwnerPrincipal != nil {
		owner = *req.OwnerPrincipal
	}
	acc, created, err := s.svc.EnsureAccount(r.Context(), domain.Account{
		Code:           req.Code,
		Type:           domain.AccountType(req.Type),
		Currency:       domain.Currency(req.Currency),
		OwnerPrincipal: owner,
		Status:         domain.AccountStatus(req.Status),
	})
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	status := http.StatusCreated
	if !created {
		status = http.StatusOK
	}
	writeJSON(w, status, accountResponseFrom(acc))
}
