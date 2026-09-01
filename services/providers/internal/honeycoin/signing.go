package honeycoin

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"strings"
)

// Outbound request signing (HoneyCoin wire contract).
//
// Every request to HoneyCoin carries:
//
//	X-HoneyCoin-Timestamp: unix seconds (when the request was signed)
//	X-HoneyCoin-Signature: hex HMAC-SHA256(HONEYCOIN_SIGNING_KEY,
//	                               ts "\n" method "\n" path "\n" body)
//	X-Idempotency-Key:     our transaction key, on state-changing calls
//	                       (Initiate; derived keys for Cancel/Reverse)
//
// The "\n" separators keep the signed message unambiguous (compact JSON
// bodies never contain raw newlines). The path is the exact request path —
// refs are validated to a URL-safe charset so no percent-escaping can
// diverge the signed path from the wire path.
//
// Inbound callbacks use the SharkPay callback scheme (internal/callback):
// X-SharkPay-Timestamp + X-SharkPay-Signature = HMAC(ts "." raw body).
const (
	headerTimestamp = "X-HoneyCoin-Timestamp"
	headerSignature = "X-HoneyCoin-Signature"

	// IdempotencyHeader carries the adapter-level idempotency key
	// (SECURITY §4: adapter keys derive from the original request key
	// chain). Exported so gateways/integration tests can assert it.
	IdempotencyHeader = "X-Idempotency-Key"
)

// SignRequest computes the hex HMAC-SHA256 over the canonical request
// signing message (ts "\n" method "\n" path "\n" body) with key.
// Exported for integration tooling and conformance harnesses.
func SignRequest(key []byte, timestamp, method, path string, body []byte) string {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(timestamp))
	mac.Write([]byte("\n"))
	mac.Write([]byte(method))
	mac.Write([]byte("\n"))
	mac.Write([]byte(path))
	mac.Write([]byte("\n"))
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

// VerifyRequestMessage checks a request signature (used by test doubles
// and by the ops echo endpoint to re-validate wire traffic).
func VerifyRequestMessage(key []byte, timestamp, method, path string, body []byte, hexSig string) bool {
	sig, err := hex.DecodeString(hexSig)
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(timestamp))
	mac.Write([]byte("\n"))
	mac.Write([]byte(method))
	mac.Write([]byte("\n"))
	mac.Write([]byte(path))
	mac.Write([]byte("\n"))
	mac.Write(body)
	return hmac.Equal(mac.Sum(nil), sig)
}

// ResolveSigningKey returns the signing key bytes: an explicit value wins
// (tests / vault-injected secrets); otherwise the env var. The
// "vault:<path>" secret-ref FORM is recognized but rejected explicitly
// until the AWS Secrets Manager resolver lands (follow-up): in production
// secrets come from the vault, never from other services' environments
// (ARCHITECTURE §4.3).
func ResolveSigningKey(explicit []byte, envVar string) ([]byte, error) {
	if len(explicit) > 0 {
		return explicit, nil
	}
	v := strings.TrimSpace(os.Getenv(envVar))
	if v == "" {
		return nil, fmt.Errorf("honeycoin: %s is not configured", envVar)
	}
	if strings.HasPrefix(v, "vault:") {
		return nil, fmt.Errorf("honeycoin: vault secret reference %q is not supported yet; set a raw key in %s for local/sandbox/staging or wire the Secrets Manager resolver", v, envVar)
	}
	return []byte(v), nil
}
